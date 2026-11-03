package app.ihearyou.domain

import android.content.Context
import androidx.annotation.StringRes
import app.ihearyou.R
import app.ihearyou.button.Gesture

enum class ButtonAction(@StringRes val labelRes: Int, @StringRes val hintRes: Int) {
    AID_DEFAULT(R.string.action_aid_default, R.string.action_aid_default_hint),
    PLAY_PAUSE(R.string.action_play_pause, R.string.action_restore_hint),
    NEXT_TRACK(R.string.action_next_track, R.string.action_restore_hint),
    PREVIOUS_TRACK(R.string.action_previous_track, R.string.action_restore_hint),
    VOLUME_UP(R.string.action_volume_up, R.string.action_volume_up_hint),
    VOLUME_DOWN(R.string.action_volume_down, R.string.action_volume_down_hint),
    PROGRAM_NEXT(R.string.action_program_next, R.string.action_program_next_hint),
    PROGRAM_PREVIOUS(R.string.action_program_previous, R.string.action_program_previous_hint),
    VOICE_NOTE(R.string.action_voice_note, R.string.action_voice_note_hint),
    NONE(R.string.action_none, R.string.action_none_hint),
}

/**
 * Per-ear, per-gesture action mapping. Stored locally, no cloud. Callers pass the aid's address;
 * the mapping is kept under the side that aid reports ([AidSides]), so it survives re-pairing,
 * reinstalls and a replaced aid. An aid whose side is not known yet keeps its own behaviour.
 */
class MappingStore(context: Context) {

    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("mapping", Context.MODE_PRIVATE)

    fun get(address: String, gesture: Gesture): ButtonAction {
        val side = AidSides.of(context, address) ?: return default(gesture)
        val stored = prefs.getString(key(side, gesture), null) ?: return default(gesture)
        return runCatching { ButtonAction.valueOf(stored) }.getOrDefault(default(gesture))
    }

    fun set(address: String, gesture: Gesture, action: ButtonAction) {
        val side = AidSides.of(context, address) ?: return
        prefs.edit().putString(key(side, gesture), action.name).apply()
    }

    @Suppress("UNUSED_PARAMETER")
    fun default(gesture: Gesture): ButtonAction = ButtonAction.AID_DEFAULT

    /** True when no gesture on this aid is mapped, so presses are left entirely to the aid. */
    fun untouched(address: String): Boolean = Gesture.entries.all { get(address, it) == ButtonAction.AID_DEFAULT }

    private fun key(side: String, gesture: Gesture) = "$side:${gesture.name}"
}
