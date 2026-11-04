package app.ihearyou.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.ihearyou.domain.SettingsStore
import app.ihearyou.protocol.Eq
import app.ihearyou.protocol.PriceTier
import app.ihearyou.protocol.SoundPresets
import app.ihearyou.protocol.StandardFeatures
import app.ihearyou.service.SoundState
import app.ihearyou.service.AidInfo
import app.ihearyou.service.AidUiState
import app.ihearyou.service.Cmd
import app.ihearyou.service.LinkPhase
import app.ihearyou.service.LinkState
import app.ihearyou.service.RemoteService
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A sample pair, reachable from Setup. It exists so a stranger can see what the instrument does
 * before bonding anything, and so the layout can be reviewed without hearing aids on the desk.
 * It never touches the radio and is stamped SAMPLE wherever it is shown.
 */
class DemoPanel {

    private val leftAddress = "D0:00:00:00:00:01"
    private val rightAddress = "D0:00:00:00:00:02"

    val addresses = listOf(leftAddress, rightAddress)

    val names = mapOf(
        leftAddress to "Nexia CROS",
        rightAddress to "Nexia 5",
    )

    val infos = MutableStateFlow(
        mapOf(
            leftAddress to AidInfo(model = "CX160S-DRWC", firmware = "D3C10620100", cros = true),
            rightAddress to AidInfo(model = "NX560S-DRWC", firmware = "D3C10620100", cros = false),
        ),
    )

    val sides = MutableStateFlow(mapOf(leftAddress to "Left", rightAddress to "Right"))

    // sound presets: the sample receiver's programs are all-around, restaurant, music, outdoor
    private val environments = listOf(1, 2, 3, 4)
    private val eqs = mutableMapOf<Int, Eq>()
    private val before = mutableMapOf<Int, Eq>()

    private fun soundFor(program: Int?): SoundState? {
        val p = program ?: return null
        val presets = SoundPresets.forProgram(environments.getOrNull(p), 10, PriceTier.PLUS, false)
        val eq = eqs[p] ?: Eq.FLAT
        return SoundState(
            program = p,
            eq = eq,
            presets = presets.map { it.kind },
            active = SoundPresets.active(presets, listOf(eq to null), false)?.kind,
            canReset = eq != Eq.FLAT,
        )
    }

    val states = MutableStateFlow(
        mapOf(
            leftAddress to AidUiState(
                battery = 9,
                volume = 0xC1,
                micDefault = 0xAA,
                streamStatus = 0,
                streamType = null,
                rssi = -58,
                confirmedAt = System.currentTimeMillis(),
            ),
            rightAddress to AidUiState(
                battery = 7,
                program = 0,
                programName = "All around",
                programs = listOf(
                    0 to "All around",
                    1 to "Restaurant",
                    2 to "Music",
                    3 to "Outdoors",
                ),
                volume = 0xAA,
                micDefault = 0xAA,
                tap = 0x02,
                streamStatus = 4,
                streamType = 1,
                rssi = -62,
                features = listOf(
                    StandardFeatures.Feature("Speech focus", "Medium"),
                    StandardFeatures.Feature("Directionality", "Dynamic"),
                    StandardFeatures.Feature("Noise reduction", "Strong"),
                    StandardFeatures.Feature("Wind noise", "Mild"),
                ),
                confirmedAt = System.currentTimeMillis(),
                sound = SoundState(
                    program = 0,
                    eq = Eq.FLAT,
                    presets = listOf(SoundPresets.Kind.NOISE_FILTER, SoundPresets.Kind.SPEECH_CLARITY),
                ),
            ),
        ),
    )

    /** Sample favourites live in memory only and are gone when the sample pair is closed. */
    val favourites = MutableStateFlow<List<app.ihearyou.domain.Favourite>>(emptyList())
    val run = MutableStateFlow<app.ihearyou.domain.FavouriteRun?>(null)

    /** The same order as the service: program, levels, then sound; the sample has no feature controls. */
    private fun applyFavourite(f: app.ihearyou.domain.Favourite) {
        val results = LinkedHashMap<app.ihearyou.domain.FavouritePart, app.ihearyou.domain.PartResult>()
        if (f.program != null) {
            val has = states.value[rightAddress]?.programs?.any { it.first == f.program } == true
            if (has) send(Cmd.SelectProgram(rightAddress, f.program))
            results[app.ihearyou.domain.FavouritePart.PROGRAM] =
                if (has) app.ihearyou.domain.PartResult.DONE else app.ihearyou.domain.PartResult.SKIPPED
        }
        if (f.volumes.isNotEmpty()) {
            var any = false
            addresses.forEach { a ->
                f.volumes[app.ihearyou.domain.Favourites.aidKey(null, a)]?.let { send(Cmd.SetVolume(a, it)); any = true }
            }
            results[app.ihearyou.domain.FavouritePart.VOLUME] =
                if (any) app.ihearyou.domain.PartResult.DONE else app.ihearyou.domain.PartResult.SKIPPED
        }
        if (f.preset != null || f.eq != null) {
            val st = states.value[rightAddress]?.sound
            results[app.ihearyou.domain.FavouritePart.SOUND] = when {
                st == null -> app.ihearyou.domain.PartResult.SKIPPED
                f.preset != null && st.active == f.preset -> app.ihearyou.domain.PartResult.DONE
                f.preset != null && f.preset in st.presets -> {
                    send(Cmd.SoundPreset(st.program, f.preset))
                    app.ihearyou.domain.PartResult.DONE
                }
                f.eq != null -> {
                    setEq(st.program, f.eq)
                    app.ihearyou.domain.PartResult.DONE
                }
                else -> app.ihearyou.domain.PartResult.SKIPPED
            }
        }
        if (f.enhancer.isNotEmpty()) results[app.ihearyou.domain.FavouritePart.ENHANCER] = app.ihearyou.domain.PartResult.SKIPPED
        run.value = app.ihearyou.domain.FavouriteRun(
            f.id, f.name, running = false, current = null, results = results, finishedAt = System.currentTimeMillis(),
        )
    }

    private fun update(address: String, block: (AidUiState) -> AidUiState) {
        states.value = states.value.mapValues { (key, value) ->
            if (key == address) block(value) else value
        }
    }

    /** Sample pair only: sets the receiver's EQ in memory, nothing is sent anywhere. */
    private fun setEq(program: Int, eq: Eq) {
        eqs[program] = eq.clamped()
        update(rightAddress) { it.copy(sound = soundFor(it.program), confirmedAt = System.currentTimeMillis()) }
    }

    fun send(cmd: Cmd) {
        val now = System.currentTimeMillis()
        when (cmd) {
            is Cmd.SoundEq -> setEq(cmd.program, cmd.eq)
            is Cmd.SoundReset -> {
                before.remove(cmd.program)
                setEq(cmd.program, Eq.FLAT)
            }
            is Cmd.SoundPreset -> {
                val state = soundFor(cmd.program) ?: return
                val preset = SoundPresets.forProgram(environments.getOrNull(cmd.program), 10, PriceTier.PLUS, false)
                    .firstOrNull { it.kind == cmd.kind } ?: return
                if (state.active == cmd.kind) {
                    setEq(cmd.program, before.remove(cmd.program) ?: Eq.FLAT)
                } else {
                    if (state.active == null) before[cmd.program] = state.eq
                    setEq(cmd.program, preset.eq)
                }
            }
            is Cmd.SelectProgram -> update(cmd.address) {
                it.copy(
                    program = cmd.index,
                    programName = it.programs.firstOrNull { p -> p.first == cmd.index }?.second,
                    confirmedAt = now,
                    sound = if (it.sound != null) soundFor(cmd.index) else null,
                )
            }
            is Cmd.Program -> update(cmd.address) { state ->
                val indices = state.programs.map { it.first }
                if (indices.isEmpty()) return@update state
                val pos = indices.indexOf(state.program).takeIf { it >= 0 } ?: 0
                val next = indices[(pos + cmd.delta + indices.size) % indices.size]
                state.copy(
                    program = next,
                    programName = state.programs.firstOrNull { it.first == next }?.second,
                    confirmedAt = now,
                    sound = if (state.sound != null) soundFor(next) else null,
                )
            }
            is Cmd.SetVolume -> update(cmd.address) { it.copy(volume = cmd.value.coerceIn(0, 255), confirmedAt = now) }
            is Cmd.Volume -> update(cmd.address) {
                it.copy(volume = ((it.volume ?: 0xAA) + cmd.delta).coerceIn(0, 255), confirmedAt = now)
            }
            is Cmd.Forget -> Unit
            is Cmd.ApplyFavourite -> applyFavourite(cmd.favourite)
            // voice notes have no sample-pair simulation
            is Cmd.VoiceStart, is Cmd.VoiceStop, is Cmd.VoiceRetry, is Cmd.VoiceDelete, is Cmd.VoiceImport,
            is Cmd.WhiteNoiseChanged, is Cmd.AcceptInvite, is Cmd.SimulateGesture -> Unit
            // the sample pair has no stream, feature or tinnitus controls
            is Cmd.SoundFeature, is Cmd.TinnitusSet, is Cmd.StreamLevel, is Cmd.StreamMute, is Cmd.MixIn -> Unit
            is Cmd.VolumeAll -> addresses.forEach { a ->
                update(a) { it.copy(volume = app.ihearyou.protocol.StreamVolume.stepped(it.volume ?: 0xAA, cmd.steps, it.micDefault), confirmedAt = now) }
            }
            is Cmd.Mute -> {
                val targets = cmd.address?.let { listOf(it) } ?: addresses
                val mute = cmd.on ?: targets.any { (states.value[it]?.volume ?: 0) > 0 }
                targets.forEach { a -> update(a) { it.copy(volume = if (mute) 0 else 0xAA, confirmedAt = now) } }
            }
            is Cmd.Tap -> update(cmd.address) { state ->
                var v = state.tap ?: 0
                if (cmd.calls != null) v = if (cmd.calls) v or 0x0002 else v and 0x0002.inv()
                if (cmd.program != null) v = if (cmd.program) v or 0x0001 else v and 0x0001.inv()
                state.copy(tap = v, confirmedAt = now)
            }
            is Cmd.RefreshRssi -> update(cmd.address) {
                val base = it.rssi ?: -62
                val drift = ((System.nanoTime() / 1_000_000_000L) % 5).toInt() - 2
                it.copy(rssi = (base + drift).coerceIn(-90, -40))
            }
        }
    }
}

data class AidScope(
    val states: Map<String, AidUiState>,
    val infos: Map<String, AidInfo>,
    val sides: Map<String, String>,
    val names: Map<String, String>,
    val logs: List<String>,
    val connected: Int,
    /** Why each aid is or is not linked; empty in the sample pair. */
    val links: Map<String, LinkState> = emptyMap(),
    /** The ReSound Smart 3D app is installed; only one app can hold the aids at a time. */
    val rivalInstalled: Boolean = false,
    val sample: Boolean,
    val addresses: List<String>,
    val send: (Cmd) -> Unit,
    val setSample: (Boolean) -> Unit,
    /** This pair's favourites, in the order the wearer keeps them. */
    val favourites: List<app.ihearyou.domain.Favourite> = emptyList(),
    /** The favourite being applied, or the last one's result. */
    val favouriteRun: app.ihearyou.domain.FavouriteRun? = null,
    /** Replaces this pair's favourites (save, rename, delete). */
    val saveFavourites: (List<app.ihearyou.domain.Favourite>) -> Unit = {},
    /** The key a favourite stores an aid's level under. */
    val aidKey: (String) -> String = { it.uppercase() },
) {
    val anyLinked: Boolean get() = states.isNotEmpty()
}

/** Names of OS-bonded hearing aids, filtered with the same rule as the service's discovery. */
fun bondedAids(context: Context): List<Pair<String, String>> = runCatching {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    val known = app.ihearyou.ble.BondStore(context).knownAddresses()
    adapter.bondedDevices
        .filter { app.ihearyou.ble.AidFilter.isCandidate(it, known) }
        .map { it.address to (it.name ?: it.address) }
        .sortedBy { it.first }
}.getOrDefault(emptyList())

const val SMART3D_PACKAGE = "dk.resound.smart3d"

fun smart3dInstalled(context: Context): Boolean = runCatching {
    context.packageManager.getPackageInfo(SMART3D_PACKAGE, 0)
    true
}.getOrDefault(false)

@Composable
fun rememberAidScope(): AidScope {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val demo = remember { DemoPanel() }
    var sample by remember { mutableStateOf(settings.previewPair()) }

    val serviceStates by RemoteService.ui.collectAsState()
    val serviceInfos by RemoteService.info.collectAsState()
    val serviceSides by RemoteService.sides.collectAsState()
    val serviceLogs by RemoteService.log.collectAsState()
    val connected by RemoteService.connected.collectAsState()
    val serviceLinks by RemoteService.links.collectAsState()
    val rival = remember { smart3dInstalled(context) }

    val demoStates by demo.states.collectAsState()
    val demoInfos by demo.infos.collectAsState()
    val demoSides by demo.sides.collectAsState()

    // re-read when the service reports links too: on a fresh install the first read runs before
    // the Bluetooth permission is granted and finds nothing, and info only arrives after pairing
    val bonded = remember(sample, serviceInfos.size, serviceLinks.keys) { bondedAids(context) }
    val bondedAddresses = bonded.map { it.first }
    val addresses = if (sample) demo.addresses else (bondedAddresses + serviceInfos.keys + serviceLinks.keys).distinct()
        .filter { serviceLinks[it]?.phase != LinkPhase.UNSUPPORTED }

    val favouriteStore = remember { app.ihearyou.domain.FavouriteStore(context) }
    val favouriteVersion by app.ihearyou.domain.FavouriteStore.changed.collectAsState()
    val serviceRun by RemoteService.favouriteRun.collectAsState()
    val demoFavourites by demo.favourites.collectAsState()
    val demoRun by demo.run.collectAsState()
    val aidKey: (String) -> String = if (sample) {
        { a -> app.ihearyou.domain.Favourites.aidKey(null, a) }
    } else {
        { a -> favouriteStore.aidKey(a, serviceInfos[a]?.serial) }
    }
    val system = favouriteStore.systemKey(addresses.map(aidKey))
    val stored = remember(system, favouriteVersion) { favouriteStore.load(system) }

    return AidScope(
        states = if (sample) demoStates else serviceStates,
        infos = if (sample) demoInfos else serviceInfos,
        sides = if (sample) demoSides else serviceSides,
        names = if (sample) demo.names else bonded.toMap(),
        logs = if (sample) sampleLog else serviceLogs,
        connected = if (sample) demo.addresses.size else connected,
        links = if (sample) emptyMap() else serviceLinks,
        rivalInstalled = !sample && rival,
        sample = sample,
        addresses = addresses,
        send = { cmd -> if (sample) demo.send(cmd) else RemoteService.commands.trySend(cmd) },
        setSample = { on ->
            settings.setPreviewPair(on)
            sample = on
        },
        favourites = if (sample) demoFavourites else stored,
        favouriteRun = if (sample) demoRun else serviceRun,
        saveFavourites = { list -> if (sample) demo.favourites.value = list else favouriteStore.save(system, list) },
        aidKey = aidKey,
    )
}

private val sampleLog = listOf(
    "00:00:01  sample pair loaded (no radio)",
    "00:00:01  D0:00:00:00:00:02: connected (Nexia 5)",
    "00:00:01  D0:00:00:00:00:01: connected (Nexia CROS)",
    "00:00:02  D0:00:00:00:00:02: program=0 volume=0xAA tap=0x02",
    "00:00:02  D0:00:00:00:00:01: volume=0xC1",
)
