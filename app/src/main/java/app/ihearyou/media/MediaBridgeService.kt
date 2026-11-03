package app.ihearyou.media

import android.content.ComponentName
import android.media.MediaMetadata
import android.service.notification.NotificationListenerService
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import app.ihearyou.service.RemoteService
import app.ihearyou.util.EventLog

/**
 * Media actions via MediaSessionManager. Requires the user to grant notification-listener access
 * (Settings > Notifications > Device & app notifications), which is the only way a normal app may
 * control another app's playback. No root, no MEDIA_CONTENT_CONTROL.
 *
 * Also logs every session's state transitions, so a player that stops on its own (or whose
 * process dies: session gone with no pause before it) can be told apart from a command we sent.
 */
class MediaBridgeService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: MediaBridgeService? = null
            private set

        fun available(): Boolean = instance != null
    }

    private val watched = HashMap<MediaSession.Token, Watch>()

    private inner class Watch(val controller: MediaController) : MediaController.Callback() {
        val pkg: String = controller.packageName
        var state: Int? = controller.playbackState?.state

        override fun onPlaybackStateChanged(s: PlaybackState?) {
            val next = s?.state
            if (next == state) return
            RemoteService.addLog("  media $pkg: ${stateName(state)} -> ${stateName(next)}" +
                (s?.errorMessage?.let { " error=$it" } ?: ""))
            state = next
        }

        override fun onSessionDestroyed() {
            RemoteService.addLog("  media $pkg: session destroyed (was ${stateName(state)})")
            unwatch(controller.sessionToken)
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        sync(list.orEmpty())
    }

    override fun onListenerConnected() {
        instance = this
        EventLog.init(this)
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(this, MediaBridgeService::class.java)
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, component)
            sync(msm.getActiveSessions(component))
        }.onFailure { RemoteService.addLog("media watch failed: ${it.message}") }
    }

    override fun onListenerDisconnected() {
        instance = null
        runCatching { getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        watched.keys.toList().forEach(::unwatch)
    }

    private fun sync(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        watched.filterKeys { it !in tokens }.forEach { (token, w) ->
            RemoteService.addLog("  media ${w.pkg}: session gone (was ${stateName(w.state)})")
            unwatch(token)
        }
        list.filter { it.sessionToken !in watched }.forEach { c ->
            val w = Watch(c)
            watched[c.sessionToken] = w
            c.registerCallback(w)
            RemoteService.addLog("  media ${w.pkg}: session seen (${stateName(w.state)})")
        }
    }

    private fun unwatch(token: MediaSession.Token) {
        watched.remove(token)?.let { runCatching { it.controller.unregisterCallback(it) } }
    }

    private fun stateName(state: Int?): String = when (state) {
        null -> "none"
        PlaybackState.STATE_NONE -> "none"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_FAST_FORWARDING -> "ff"
        PlaybackState.STATE_REWINDING -> "rewinding"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skip-prev"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "skip-next"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "skip-queue"
        else -> "state $state"
    }

    private fun activeController(): MediaController? {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return null
        val component = ComponentName(this, MediaBridgeService::class.java)
        val sessions = runCatching { msm.getActiveSessions(component) }.getOrDefault(emptyList())
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }

    private fun transport(): MediaController.TransportControls? = activeController()?.transportControls

    fun isPlaying(): Boolean = activeController()?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun playPause(): Boolean {
        val c = activeController() ?: return false
        val state = c.playbackState?.state
        val play = state != PlaybackState.STATE_PLAYING
        RemoteService.addLog("  media ${c.packageName}: was ${stateName(state)}, sending ${if (play) "play" else "pause"}")
        if (play) c.transportControls.play() else c.transportControls.pause()
        return true
    }

    fun next(): Boolean {
        val c = activeController() ?: return false
        RemoteService.addLog("  media ${c.packageName}: sending next")
        c.transportControls.skipToNext()
        return true
    }

    fun previous(): Boolean {
        val c = activeController() ?: return false
        RemoteService.addLog("  media ${c.packageName}: sending previous")
        c.transportControls.skipToPrevious()
        return true
    }

    fun stop(): Boolean {
        val t = transport() ?: return false
        t.stop()
        return true
    }

    private fun sessions(): List<MediaController> {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        return runCatching { msm.getActiveSessions(ComponentName(this, MediaBridgeService::class.java)) }.getOrDefault(emptyList())
    }

    /** Pauses every playing session; returns their packages for [resume]. */
    fun pausePlaying(): List<String> = sessions()
        .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        .map { c ->
            RemoteService.addLog("  media ${c.packageName}: pausing for a voice note")
            c.transportControls.pause()
            c.packageName
        }

    /** Resumes those of [packages] that are still paused (nobody started anything meanwhile). */
    fun resume(packages: List<String>) {
        val list = sessions()
        if (list.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }) return
        for (c in list.filter { it.packageName in packages && it.playbackState?.state == PlaybackState.STATE_PAUSED }) {
            RemoteService.addLog("  media ${c.packageName}: resuming after the voice note")
            c.transportControls.play()
        }
    }

    fun currentTitle(): String? {
        val c = activeController() ?: return null
        return c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
    }
}
