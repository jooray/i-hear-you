package app.ihearyou.button

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Gesture { SHORT, LONG, DOUBLE }

/**
 * Turns raw press/release events (GN handle 53: `01 00` / `00 00`) into gestures.
 *
 * - LONG: held >= [longMs].
 * - SHORT: released earlier; emitted after [doubleWindowMs] so a second press can upgrade it,
 *   or at once when [doubleEnabled] says no double press is mapped.
 * - DOUBLE: two short presses within [doubleWindowMs]. The aid reports the second press of a
 *   double press about 1.1 s after the first (it is busy with its own program change), so the
 *   window is far longer than a phone's double tap.
 *
 * Pure logic apart from the scheduler, so it is unit-testable with a test scope.
 */
class ButtonEngine(
    private val scope: CoroutineScope,
    private val longMs: Long = 400,
    private val doubleWindowMs: Long = 1_300,
    private val doubleEnabled: () -> Boolean = { true },
    private val onGesture: (Gesture) -> Unit,
) {
    private var pressedAt = 0L
    private var isDown = false
    private var lastShortRelease = 0L
    private var pendingShort: Job? = null
    /** The release that ends a double press belongs to it and must not start a new SHORT. */
    private var swallowRelease = false

    @Synchronized
    fun onPress(now: Long = System.currentTimeMillis()) {
        isDown = true
        pressedAt = now
        if (pendingShort?.isActive == true) {
            pendingShort?.cancel()
            pendingShort = null
            lastShortRelease = 0L
            swallowRelease = true
            onGesture(Gesture.DOUBLE)
        }
    }

    @Synchronized
    fun onRelease(now: Long = System.currentTimeMillis()) {
        if (!isDown) return
        isDown = false
        if (swallowRelease) {
            swallowRelease = false
            return
        }
        val held = now - pressedAt
        if (held >= longMs) {
            lastShortRelease = 0L
            onGesture(Gesture.LONG)
            return
        }
        lastShortRelease = now
        pendingShort?.cancel()
        if (!doubleEnabled()) {
            pendingShort = null
            onGesture(Gesture.SHORT)
            return
        }
        pendingShort = scope.launch {
            delay(doubleWindowMs)
            onGesture(Gesture.SHORT)
        }
    }
}
