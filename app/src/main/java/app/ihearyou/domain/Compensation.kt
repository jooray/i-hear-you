package app.ihearyou.domain

/**
 * Where the aid should end up after a mapped button gesture, worked out from the state before
 * the press. The aid's own button action (program step, volume step) is undone; actions that
 * change program or volume step from the pre-press state, not from whatever the aid did.
 *
 * [attenuation] is handle 5: `[program, level]`.
 */
object Compensation {

    data class Snapshot(val program: Int?, val attenuation: ByteArray?)

    const val VOLUME_STEP = 0x17

    fun target(snap: Snapshot, action: ButtonAction, programs: List<Int>): Snapshot = when (action) {
        ButtonAction.PROGRAM_NEXT, ButtonAction.PROGRAM_PREVIOUS -> {
            val from = snap.program
            if (from == null || programs.isEmpty()) snap else {
                val step = if (action == ButtonAction.PROGRAM_NEXT) 1 else -1
                val pos = programs.indexOf(from).takeIf { it >= 0 } ?: 0
                snap.copy(program = programs[(pos + step + programs.size) % programs.size])
            }
        }
        ButtonAction.VOLUME_UP, ButtonAction.VOLUME_DOWN -> {
            val at = snap.attenuation?.takeIf { it.size >= 2 }
            if (at == null) snap else {
                val step = if (action == ButtonAction.VOLUME_UP) VOLUME_STEP else -VOLUME_STEP
                val level = ((at[1].toInt() and 0xff) + step).coerceIn(0, 255)
                snap.copy(attenuation = byteArrayOf(at[0], level.toByte()))
            }
        }
        else -> snap
    }
}
