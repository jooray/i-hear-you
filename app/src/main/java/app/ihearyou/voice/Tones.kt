package app.ihearyou.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import app.ihearyou.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Short cues played into the aids, so recording can be driven without looking at the phone.
 * Synthesised (no assets), sonification usage so they mix over music. Blocking: call off the
 * main thread; start plays once the microphone is open and its route has settled, the others after it closes.
 */
object Tones {

    enum class Cue(val notes: List<Pair<Double, Int>>) {
        /** rising pair */
        START(listOf(660.0 to 110, 0.0 to 40, 990.0 to 140)),
        /** falling pair: recording stopped, saved */
        STOP(listOf(990.0 to 110, 0.0 to 40, 660.0 to 140)),
        /** three quick high notes: delivered */
        SENT(listOf(1320.0 to 70, 0.0 to 50, 1320.0 to 70, 0.0 to 50, 1320.0 to 70)),
        /** low long note: something failed (the recording itself is still saved) */
        FAILED(listOf(220.0 to 420)),
        /** a soft single note before a reply is read out */
        REPLY(listOf(880.0 to 90)),
        /** one minute left before the length limit */
        WARN(listOf(440.0 to 90, 0.0 to 60, 440.0 to 90)),
        /** three falling notes: the aids' microphone failed, now recording from the phone */
        PHONE_MIC(listOf(880.0 to 110, 0.0 to 40, 660.0 to 110, 0.0 to 40, 440.0 to 160)),
    }

    private const val RATE = 24_000

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * A woman's voice saying "go ahead" in the app's language (res/raw-<lang>), played once the
     * microphone is already recording, so it can be talked over. The START cue if it can't play.
     */
    fun playGoAhead(context: Context) = playVoice(context, R.raw.voice_go_ahead, Cue.START)

    /**
     * After the stop cue, what happens next in a few words ("Saved, transcribing.", "Sending to
     * White Noise."), so a press read as another gesture is noticed right away.
     */
    fun playDone(context: Context, destination: Destination, transcribing: Boolean) = playVoice(
        context,
        when (destination) {
            Destination.SAVE -> if (transcribing) R.raw.voice_done_save_tr else R.raw.voice_done_save
            Destination.WHITE_NOISE -> if (transcribing) R.raw.voice_done_white_noise_tr else R.raw.voice_done_white_noise
            Destination.WEBHOOK -> if (transcribing) R.raw.voice_done_webhook_tr else R.raw.voice_done_webhook
            Destination.SHARE -> if (transcribing) R.raw.voice_done_share_tr else R.raw.voice_done_share
        },
        null,
    )

    /**
     * The clips start with 2 s of silence: the stream is open while the aids' link switches
     * mode, so the first word isn't clipped.
     */
    private fun playVoice(context: Context, res: Int, fallback: Cue?) {
        val done = CountDownLatch(1)
        val mp = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                context.resources.openRawResourceFd(res).use { setDataSource(it) }
                setOnCompletionListener { done.countDown() }
                setOnErrorListener { _, _, _ -> done.countDown(); true }
                prepare()
            }
        }.getOrElse {
            fallback?.let { play(it) }
            return
        }
        try {
            mp.start()
            done.await(mp.duration.toLong().coerceAtLeast(0) + 1_000, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { mp.stop() }
            mp.release()
        }
    }

    fun play(cue: Cue) {
        val pcm = render(cue)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(cue.notes.sumOf { it.second }.toLong() + 60)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun render(cue: Cue): ShortArray {
        val total = cue.notes.sumOf { it.second } * RATE / 1000
        val out = ShortArray(total)
        var i = 0
        for ((freq, ms) in cue.notes) {
            val n = ms * RATE / 1000
            val fade = min(n / 4, RATE / 200)
            for (k in 0 until n) {
                val env = when {
                    k < fade -> k.toDouble() / fade
                    k > n - fade -> (n - k).toDouble() / fade
                    else -> 1.0
                }
                out[i + k] = if (freq == 0.0) 0 else (sin(2 * PI * freq * k / RATE) * env * 9_000).toInt().toShort()
            }
            i += n
        }
        return out
    }
}
