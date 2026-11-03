package app.ihearyou.voice

import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Posts a voice note as multipart/form-data: `metadata` (JSON), `transcript` (text, when there is
 * one) and `audio` (the .m4a, unless only text is sent). Works with n8n, Home Assistant webhooks,
 * a self-hosted agent or a transcription server; any 2xx counts as delivered.
 */
object Webhook {

    fun post(url: String, auth: String, metadata: JSONObject, transcript: String?, audio: File?, audioName: String) {
        val boundary = "ihearyou-" + System.nanoTime()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setChunkedStreamingMode(64 * 1024)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("User-Agent", "IHearYou")
            if (auth.isNotBlank()) conn.setRequestProperty("Authorization", auth)
            DataOutputStream(conn.outputStream).use { out ->
                fun part(name: String, type: String, fileName: String? = null) {
                    out.writeBytes("--$boundary\r\n")
                    val disp = "form-data; name=\"$name\"" + (fileName?.let { "; filename=\"${it.replace("\"", "")}\"" } ?: "")
                    out.writeBytes("Content-Disposition: $disp\r\n")
                    out.writeBytes("Content-Type: $type\r\n\r\n")
                }
                part("metadata", "application/json")
                out.write(metadata.toString().toByteArray())
                out.writeBytes("\r\n")
                if (transcript != null) {
                    part("transcript", "text/plain; charset=utf-8")
                    out.write(transcript.toByteArray())
                    out.writeBytes("\r\n")
                }
                if (audio != null) {
                    part("audio", AacEncoder.MIME, audioName)
                    audio.inputStream().use { it.copyTo(out) }
                    out.writeBytes("\r\n")
                }
                out.writeBytes("--$boundary--\r\n")
            }
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
        } finally {
            conn.disconnect()
        }
    }
}
