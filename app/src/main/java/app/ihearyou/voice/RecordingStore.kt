package app.ihearyou.voice

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Delivery state of one voice note, as shown in the recent list. */
enum class Delivery { RECORDING, PROCESSING, SAVED, SENT, FAILED }

/**
 * One voice note: its working files under filesDir/voice/<id>/ (audio.wav while recording,
 * audio.m4a, transcript.txt, note.json) and where the user-visible copy was saved.
 */
data class Note(
    val id: String,
    val startedAt: Long,
    val durationMs: Long = 0,
    val destination: Destination = Destination.SAVE,
    val mic: String? = null,
    val savedAs: String? = null,
    val transcript: Boolean = false,
    val transcriptError: String? = null,
    val delivery: Delivery = Delivery.RECORDING,
    val error: String? = null,
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id).put("startedAt", startedAt).put("durationMs", durationMs)
        .put("destination", destination.name).put("mic", mic).put("savedAs", savedAs)
        .put("transcript", transcript).put("transcriptError", transcriptError)
        .put("delivery", delivery.name).put("error", error)

    companion object {
        fun of(o: JSONObject) = Note(
            id = o.getString("id"),
            startedAt = o.optLong("startedAt"),
            durationMs = o.optLong("durationMs"),
            destination = runCatching { Destination.valueOf(o.optString("destination")) }.getOrDefault(Destination.SAVE),
            mic = o.optString("mic").ifBlank { null }.takeIf { o.has("mic") && !o.isNull("mic") },
            savedAs = o.optString("savedAs").takeIf { o.has("savedAs") && !o.isNull("savedAs") },
            transcript = o.optBoolean("transcript"),
            transcriptError = o.optString("transcriptError").takeIf { o.has("transcriptError") && !o.isNull("transcriptError") },
            delivery = runCatching { Delivery.valueOf(o.optString("delivery")) }.getOrDefault(Delivery.FAILED),
            error = o.optString("error").takeIf { o.has("error") && !o.isNull("error") },
        )
    }
}

class RecordingStore(private val context: Context) {

    companion object {
        /** Notes in the recent list; older working folders are removed. */
        private const val KEEP = 40
        val notes = MutableStateFlow<List<Note>>(emptyList())
        private val nameFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")
    }

    private val root = File(context.filesDir, "voice").apply { mkdirs() }

    fun dir(id: String) = File(root, id).apply { mkdirs() }
    fun wav(id: String) = File(dir(id), "audio.wav")
    fun m4a(id: String) = File(dir(id), "audio.m4a")
    fun transcriptFile(id: String) = File(dir(id), "transcript.txt")

    fun transcript(id: String): String? = transcriptFile(id).takeIf { it.exists() }?.readText()?.ifBlank { null }

    @Synchronized
    fun put(note: Note) {
        File(dir(note.id), "note.json").writeText(note.json().toString())
        reload()
    }

    fun get(id: String): Note? = runCatching { Note.of(JSONObject(File(root, "$id/note.json").readText())) }.getOrNull()

    @Synchronized
    fun reload() {
        val all = root.listFiles().orEmpty().filter { it.isDirectory }
            .mapNotNull { d -> runCatching { Note.of(JSONObject(File(d, "note.json").readText())) }.getOrNull() }
            .sortedByDescending { it.startedAt }
        all.drop(KEEP).forEach { File(root, it.id).deleteRecursively() }
        notes.value = all.take(KEEP)
    }

    /** Working folders whose recording never finished (the process died mid-note). */
    fun unfinished(): List<Note> = root.listFiles().orEmpty().mapNotNull { d ->
        runCatching { Note.of(JSONObject(File(d, "note.json").readText())) }.getOrNull()
    }.filter { it.delivery == Delivery.RECORDING || it.delivery == Delivery.PROCESSING }

    fun baseName(startedAt: Long): String =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startedAt), java.time.ZoneId.systemDefault())
            .format(nameFormat) + " voice note"

    /**
     * Copies a file into the user-visible folder: the chosen SAF tree, or Documents/I Hear You.
     * Returns a display location for the log and the list.
     */
    fun export(source: File, displayName: String, mime: String, folder: String?): String {
        if (folder != null) {
            val tree = Uri.parse(folder)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, displayName)
                ?: error("could not create $displayName in the chosen folder")
            context.contentResolver.openOutputStream(doc, "w")!!.use { out -> source.inputStream().use { it.copyTo(out) } }
            return displayName
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/I Hear You")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("could not create $displayName in Documents")
        resolver.openOutputStream(uri, "w")!!.use { out -> source.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return "Documents/I Hear You/$displayName"
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        reload()
    }
}
