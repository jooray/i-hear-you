package app.ihearyou.control

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.ihearyou.R
import app.ihearyou.service.Cmd
import app.ihearyou.service.RemoteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings tile: mute or unmute every linked aid without opening the app. */
class MuteTileService : TileService() {

    private var watcher: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watcher = CoroutineScope(Dispatchers.Main).launch {
            RemoteService.ui.collect { render(it.values.mapNotNull { s -> s.volume }) }
        }
    }

    override fun onStopListening() {
        watcher?.cancel()
        watcher = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (RemoteService.ui.value.isEmpty()) return
        RemoteService.commands.trySend(Cmd.Mute())
    }

    private fun render(volumes: List<Int>) {
        val tile = qsTile ?: return
        tile.state = when {
            volumes.isEmpty() -> Tile.STATE_UNAVAILABLE
            volumes.all { it == 0 } -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            volumes.isEmpty() -> getString(R.string.tile_not_linked)
            volumes.all { it == 0 } -> getString(R.string.tile_muted)
            else -> getString(R.string.tile_on)
        }
        tile.updateTile()
    }
}
