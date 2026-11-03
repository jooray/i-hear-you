package app.ihearyou.voice

import android.content.Context
import androidx.annotation.StringRes
import app.ihearyou.R
import app.ihearyou.button.Gesture
import app.ihearyou.domain.AidSides

/** Where a finished voice note goes. Every destination saves the recording first. */
enum class Destination(@StringRes val labelRes: Int, @StringRes val hintRes: Int) {
    SAVE(R.string.voice_dest_save, R.string.voice_dest_save_hint),
    WHITE_NOISE(R.string.voice_dest_white_noise, R.string.voice_dest_white_noise_hint),
    WEBHOOK(R.string.voice_dest_webhook, R.string.voice_dest_webhook_hint),
    SHARE(R.string.voice_dest_share, R.string.voice_dest_share_hint),
}

/** What is sent. A transcript, when made, is always saved next to the audio. */
enum class Transcription(@StringRes val labelRes: Int, @StringRes val hintRes: Int) {
    AUDIO(R.string.voice_tx_audio, R.string.voice_tx_audio_hint),
    AUDIO_AND_TEXT(R.string.voice_tx_both, R.string.voice_tx_both_hint),
    TEXT(R.string.voice_tx_text, R.string.voice_tx_text_hint),
}

/** Which incoming White Noise messages are played into the aids. */
enum class ReplyMode(@StringRes val labelRes: Int, @StringRes val hintRes: Int) {
    OFF(R.string.voice_replies_off, R.string.voice_replies_off_hint),
    AFTER_NOTE(R.string.voice_replies_after, R.string.voice_replies_after_hint),
    ALWAYS(R.string.voice_replies_always, R.string.voice_replies_always_hint),
}

/** Voice-note preferences. Stored locally, no cloud. */
class VoiceSettings(context: Context) {

    companion object {
        val LENGTHS_MIN = listOf(2, 5, 10, 25, 60)
        const val DEFAULT_LENGTH_MIN = 5
        /** Replies after a note are read for this long when the mode is AFTER_NOTE. */
        const val REPLY_WINDOW_MIN = 15
        const val DEFAULT_REPLY_IGNORE = "\uD83C\uDF99\uFE0F"

        /**
         * Webhooks are https only: Android blocks cleartext for this app anyway (target SDK 36, no
         * exception), so an http:// URL only failed when a note was sent; and it carries the
         * recording and its transcript.
         */
        fun isHttps(url: String): Boolean = url.trim().lowercase().let { it.startsWith("https://") && it.length > "https://".length }
    }

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    var maxMinutes: Int
        get() = prefs.getInt("max_minutes", DEFAULT_LENGTH_MIN)
        set(v) = prefs.edit().putInt("max_minutes", v).apply()

    var transcription: Transcription
        get() = enumOr(prefs.getString("transcription", null), Transcription.AUDIO)
        set(v) = prefs.edit().putString("transcription", v.name).apply()

    /** Explicit OSTT (or other recognition service) component, flattened; null picks the best found. */
    var transcriber: String?
        get() = prefs.getString("transcriber", null)
        set(v) = prefs.edit().putString("transcriber", v).apply()

    /** Record from the aids' own microphones when they offer one (else the phone's). */
    var aidMic: Boolean
        get() = prefs.getBoolean("aid_mic", true)
        set(v) = prefs.edit().putBoolean("aid_mic", v).apply()

    /** SAF tree chosen by the user; null saves to Documents/I Hear You. */
    var folderUri: String?
        get() = prefs.getString("folder", null)
        set(v) = prefs.edit().putString("folder", v).apply()

    var webhookUrl: String
        get() = prefs.getString("webhook_url", "") ?: ""
        set(v) = prefs.edit().putString("webhook_url", v.trim()).apply()

    /** Sent as the Authorization header value when not blank, e.g. "Bearer …". */
    var webhookAuth: String
        get() = prefs.getString("webhook_auth", "") ?: ""
        set(v) = prefs.edit().putString("webhook_auth", v.trim()).apply()

    /** The advanced White Noise destination: own identity, a joined group, replies. */
    var whiteNoise: Boolean
        get() = prefs.getBoolean("white_noise", false)
        set(v) = prefs.edit().putBoolean("white_noise", v).apply()

    /** The White Noise identity's display name (what group members see); null is "I Hear You". */
    var whiteNoiseName: String?
        get() = prefs.getString("wn_name", null)
        set(v) = prefs.edit().putString("wn_name", v?.trim()?.ifEmpty { null }).apply()

    var group: String?
        get() = prefs.getString("wn_group", null)
        set(v) = prefs.edit().putString("wn_group", v).apply()

    var replies: ReplyMode
        get() = enumOr(prefs.getString("replies", null), ReplyMode.AFTER_NOTE)
        set(v) = prefs.edit().putString("replies", v.name).apply()

    /**
     * Replies containing any of these are not played. Default: the microphone emoji, which marks
     * the transcript of your own voice note when the agent echoes it back.
     */
    var replyIgnore: List<String>
        get() = (if (prefs.contains("reply_ignore")) prefs.getString("reply_ignore", "") ?: "" else DEFAULT_REPLY_IGNORE)
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("reply_ignore", v.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")).apply()

    /** Off: only voice replies play, text is not read out (ask the agent to answer in voice). */
    var replyReadText: Boolean
        get() = prefs.getBoolean("reply_read_text", true)
        set(v) = prefs.edit().putBoolean("reply_read_text", v).apply()

    /** Destination for the press that ends a recording, per ear (see [AidSides]) and gesture. */
    fun finish(address: String, gesture: Gesture): Destination {
        val side = AidSides.of(appContext, address) ?: return Destination.SAVE
        return enumOr(prefs.getString(finishKey(side, gesture), null), Destination.SAVE)
    }

    fun setFinish(address: String, gesture: Gesture, destination: Destination) {
        val side = AidSides.of(appContext, address) ?: return
        prefs.edit().putString(finishKey(side, gesture), destination.name).apply()
    }

    /** Destinations that are set up enough to pick. */
    fun available(): List<Destination> = Destination.entries.filter {
        when (it) {
            Destination.SAVE, Destination.SHARE -> true
            Destination.WEBHOOK -> isHttps(webhookUrl)
            Destination.WHITE_NOISE -> whiteNoise
        }
    }

    private fun finishKey(side: String, gesture: Gesture) = "finish:$side:${gesture.name}"

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
}
