package app.ihearyou.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.ihearyou.ble.AidFilter
import app.ihearyou.ble.BondStore
import app.ihearyou.ble.GattClient
import app.ihearyou.ble.GnSession
import app.ihearyou.button.ButtonEngine
import app.ihearyou.R
import app.ihearyou.button.Gesture
import app.ihearyou.domain.ButtonAction
import app.ihearyou.domain.Compensation
import app.ihearyou.domain.MappingStore
import app.ihearyou.media.MediaBridgeService
import app.ihearyou.protocol.Frames
import app.ihearyou.protocol.Gn
import app.ihearyou.protocol.Handles
import app.ihearyou.protocol.StandardFeatures
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the BLE links and turns physical button presses into phone actions.
 *
 * M0 behaviour:
 *  - keeps both aids connected with capped exponential backoff,
 *  - short = play/pause, long = next, double = previous,
 *  - compensates the aid's own button action (program shift / volume step) by restoring the
 *    pre-press value, using a timestamped history to avoid stale-snapshot races.
 */
data class AidUiState(
    val battery: Int? = null,
    /** Handle 50/54 bitfield: bit 1 charging, bit 5 in the charger, bit 6 battery full. */
    val hiState: Int? = null,
    val program: Int? = null,
    val programName: String? = null,
    val programs: List<Pair<Int, String>> = emptyList(),
    /** Standard program type per program index (handle 17), when the aid reports one. */
    val programKinds: Map<Int, app.ihearyou.protocol.ProgramNames.Kind> = emptyMap(),
    val volume: Int? = null,
    val tap: Int? = null,
    val streamStatus: Int? = null,
    val streamType: Int? = null,
    val rssi: Int? = null,
    val features: List<StandardFeatures.Feature> = emptyList(),
    /** Wall clock of the last value the aid itself confirmed, for the READ line on the panel. */
    val confirmedAt: Long = 0L,
    /** Active program's bass/middle/treble and presets; null where the app cannot change them. */
    val sound: SoundState? = null,
    /** The platform has a stream level (handle 6) the app may set. */
    val streamControl: Boolean = false,
    /** Stream level of the active program (handle 6): 0 muted, 1..255. */
    val streamAttenuation: Int? = null,
    /** Default microphone and stream levels (handle 16). */
    val micDefault: Int? = null,
    val streamDefault: Int? = null,
    /** Program category per program index (handle 11). */
    val programCategories: Map<Int, Int> = emptyMap(),
    /** Program type per program index (handle 17). */
    val environments: List<Int> = emptyList(),
    /** Programs with the tinnitus generator (handle 19); null when unknown. */
    val tinnitusPrograms: Set<Int>? = null,
    /** Receiver check (handle 37); null when not read or not readable. */
    val receiver: app.ihearyou.protocol.ReceiverCheck.Status? = null,
    /** The last stream, tinnitus or quick-key level change was not confirmed. */
    val streamFailed: Boolean = false,
)

sealed class Cmd {
    data class Program(val address: String, val delta: Int) : Cmd()
    data class SelectProgram(val address: String, val index: Int) : Cmd()
    data class Volume(val address: String, val delta: Int) : Cmd()
    data class SetVolume(val address: String, val value: Int) : Cmd()
    data class RefreshRssi(val address: String) : Cmd()
    data class Tap(val address: String, val calls: Boolean? = null, val program: Boolean? = null) : Cmd()
    /** Mute ([on] true), unmute (false) or toggle (null); [address] null means every linked aid. */
    data class Mute(val address: String? = null, val on: Boolean? = null) : Cmd()
    /** Step every linked aid's volume together. */
    data class VolumeAll(val delta: Int) : Cmd()
    /** Delete the stored key; the link then drops and the aid shows as needing pairing. */
    data class Forget(val address: String) : Cmd()
    /** End the current voice note and save it (notification action, no aid press). */
    object VoiceStop : Cmd()
    /** Start a voice note from the panel (no aid press). */
    object VoiceStart : Cmd()
    data class VoiceRetry(val id: String, val destination: app.ihearyou.voice.Destination? = null) : Cmd()
    data class VoiceDelete(val id: String) : Cmd()
    /** Debug builds: run a WAV through the voice-note pipeline. */
    data class VoiceImport(val file: java.io.File, val destination: app.ihearyou.voice.Destination) : Cmd()
    /** White Noise destination turned on or off, or its group changed. */
    object WhiteNoiseChanged : Cmd()
    data class AcceptInvite(val groupId: String, val accept: Boolean) : Cmd()
    /** Debug builds: act as if [side] ("left"/"right") made [gesture]. */
    data class SimulateGesture(val side: String, val gesture: String) : Cmd()
    /** A preset key on [program] (the active one): on, or off when it is already the active preset. */
    data class SoundPreset(val program: Int, val kind: app.ihearyou.protocol.SoundPresets.Kind) : Cmd()
    /** Bass/middle/treble in dB for [program] (the active one), on every aid that has a gain record. */
    data class SoundEq(val program: Int, val eq: app.ihearyou.protocol.Eq) : Cmd()
    /** [program] back to its fitted sound. */
    data class SoundReset(val program: Int) : Cmd()
    /** A feature control position for [program] (the active one), on every aid that has the feature. */
    data class SoundFeature(val program: Int, val position: app.ihearyou.protocol.SoundEnhancer.Position) : Cmd()
    /** A tinnitus generator setting for [program] (the active one), on every aid whose program has it. */
    data class TinnitusSet(val program: Int, val change: TinnitusChange) : Cmd()
    /** Stream level (or, with [tinnitus], the tinnitus generator level) on every aid it applies to. */
    data class StreamLevel(val value: Int, val tinnitus: Boolean = false) : Cmd()
    data class StreamMute(val on: Boolean, val tinnitus: Boolean = false) : Cmd()
    /** A microphone quick key while a phone or broadcast stream is mixed in. */
    data class MixIn(val kind: app.ihearyou.protocol.MixInButtons.Kind) : Cmd()
    /** Program, then each aid's level, then the sound parts, through the same paths as the keys. */
    data class ApplyFavourite(val favourite: app.ihearyou.domain.Favourite) : Cmd()
}

/** Where each aid's link stands, for status words and recovery hints in the UI. */
enum class LinkPhase { CONNECTING, LINKED, NEEDS_PAIRING, BLOCKED, TAKEN_OVER, UNSUPPORTED, BLUETOOTH_OFF }

/** [recovering]: still within the automatic reconnect attempts, so no fix is shown yet. */
data class LinkState(val phase: LinkPhase, val detail: String? = null, val recovering: Boolean = false)

data class AidInfo(
    val model: String? = null,
    val firmware: String? = null,
    val serial: String? = null,
    val cros: Boolean = false,
)

class RemoteService : Service() {

    companion object {
        private const val CHANNEL_ID = "remote"
        private const val NOTIF_ID = 1

        val log = MutableStateFlow<List<String>>(emptyList())
        val connected = MutableStateFlow(0)
        /** The service holds the microphone foreground type, so a voice note can record in the background. */
        val micReady = MutableStateFlow(false)
        /** address -> "Left" / "Right" / "?" (handle 2). */
        val sides = MutableStateFlow<Map<String, String>>(emptyMap())

        /** address -> DIS/feature info. */
        val info = MutableStateFlow<Map<String, AidInfo>>(emptyMap())

        val ui = MutableStateFlow<Map<String, AidUiState>>(emptyMap())
        val links = MutableStateFlow<Map<String, LinkState>>(emptyMap())
        /** Emits an address after the pairing wizard stores a new key for it. */
        val bondsChanged = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
        /** "Take back": log in again on aids another app has taken over. */
        val takeBack = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        /**
         * Starts a fresh connection attempt now for an aid that isn't linked: its address, or
         * null for all. Sent when Android sees the aid again, on unlock, on opening the app,
         * and by the Reconnect key.
         */
        val kick = kotlinx.coroutines.flow.MutableSharedFlow<String?>(extraBufferCapacity = 8)

        /**
         * How long to wait for the background (autoConnect) link before starting over with a
         * direct connect, per attempt since the link was lost: about 16 minutes in all, then
         * only the background wait, which Android can leave hanging, hence the attempts.
         */
        private val RECONNECT_PATIENCE_MS = listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 480_000L)
        private const val KICK_MIN_INTERVAL_MS = 30_000L
        private val lastKick = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** [kick], at most once per 30 s per aid (screen-on and unlock come often). */
        fun kickReconnect(address: String? = null) {
            val key = address?.uppercase() ?: "*"
            val now = android.os.SystemClock.elapsedRealtime()
            val last = lastKick[key]
            if (last != null && now - last < KICK_MIN_INTERVAL_MS) return
            lastKick[key] = now
            kick.tryEmit(address)
        }
        val commands = kotlinx.coroutines.channels.Channel<Cmd>(256)
        /** The favourite being applied, or the last one's result. */
        val favouriteRun = MutableStateFlow<app.ihearyou.domain.FavouriteRun?>(null)

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RemoteService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteService::class.java))
        }

        fun addLog(line: String) {
            val stamped = java.time.LocalTime.now().withNano(0).toString() + "  " + line
            android.util.Log.i("IHearYou", stamped)
            app.ihearyou.util.EventLog.append(line)
            log.update { (it + stamped).takeLast(300) }
        }
    }

    private data class AidState(val program: Int? = null, val attenuation: ByteArray? = null)
    private data class Sample(val ts: Long, val program: Int?, val attenuation: ByteArray?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var voice: app.ihearyou.voice.VoiceNotes
    private val clients = mutableListOf<GattClient>()
    private val sessions = ConcurrentHashMap<String, GnSession>()
    private val gatts = ConcurrentHashMap<String, GattClient>()
    @Volatile private var supervising = false
    private var supervisor: kotlinx.coroutines.Job? = null
    private val supervised = ConcurrentHashMap.newKeySet<String>()
    /** When each aid last fell out of step; a second time within a minute means another app. */
    private val lastDesync = ConcurrentHashMap<String, Long>()
    private val states = ConcurrentHashMap<String, AidState>()
    private val preGesture = ConcurrentHashMap<String, AidState>()
    /**
     * Per pressed aid: the other connected aids' state at the press. Binaurally linked aids
     * apply one aid's button step (volume, program) to both, so compensation must hold the
     * partner too, not only the aid that was pressed.
     */
    private val partnersPre = ConcurrentHashMap<String, Map<String, AidState>>()
    private val history = ConcurrentHashMap<String, ArrayDeque<Sample>>()
    private val pendingUntil = ConcurrentHashMap<String, Long>()
    /** State the aid is held to after a mapped gesture; absent while the gesture is undecided. */
    private val target = ConcurrentHashMap<String, AidState>()
    private val revertInFlight = ConcurrentHashMap.newKeySet<String>()
    /** Last non-zero level per aid, restored on unmute. */
    private val lastAudible = ConcurrentHashMap<String, Int>()
    private lateinit var mapping: MappingStore
    private lateinit var sound: SoundControl
    private lateinit var stream: StreamControl
    private val lastSeen by lazy { app.ihearyou.domain.LastSeenStore(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(app.ihearyou.domain.Language.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        app.ihearyou.util.EventLog.init(this)
        mapping = MappingStore(this)
        sound = SoundControl(this, scope, sessions, ::addLog)
        stream = StreamControl(sessions, ::addLog, { sound.tinnitusMembers() }) { address, payload ->
            states[address] = (states[address] ?: AidState()).copy(attenuation = payload)
            remember(address, payload[0].toInt() and 0xff, payload)
            ui.update { m -> m[address]?.let { st -> m + (address to st.copy(volume = payload[1].toInt() and 0xff, confirmedAt = System.currentTimeMillis())) } ?: m }
        }
        voice = app.ihearyou.voice.VoiceNotes(this, scope, ::addLog)
        voice.recover()
        if (app.ihearyou.voice.VoiceSettings(this).whiteNoise) scope.launch { app.ihearyou.marmot.MarmotHost.start(this@RemoteService) }
        createChannel()
        ContextCompat.registerReceiver(
            this, bluetoothState, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, wakeUps,
            android.content.IntentFilter().apply {
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Moments an unlinked aid is likely reachable: reconnect then instead of waiting. */
    private val wakeUps = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val d = androidx.core.content.IntentCompat.getParcelableExtra(
                        intent, android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java,
                    ) ?: return
                    if (supervised.any { it.equals(d.address, ignoreCase = true) }) kickReconnect(d.address)
                }
                else -> kickReconnect()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // The microphone type can only be taken while the app is visible (Android 14
            // while-in-use rule), so voice notes after a reboot wait for the app to be opened
            // once. A start without it must not drop a microphone type the service already holds.
            val mic = runCatching {
                check(ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED)
                startForeground(
                    NOTIF_ID, notification(getString(R.string.notif_listening)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            }.isSuccess
            if (mic) micReady.value = true
            else if (!micReady.value) startForeground(
                NOTIF_ID, notification(getString(R.string.notif_listening)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: SecurityException) {
            // Android 14+ refuses a connectedDevice service before BLUETOOTH_CONNECT is granted
            addLog("waiting for the Bluetooth permission")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!supervising) {
            supervising = true
            startSupervisor()
            scope.launch { for (c in commands) runCatching { handleCommand(c) } }
            scope.launch { bondsChanged.collect { address -> superviseNew(address) } }
            scope.launch {
                kotlinx.coroutines.flow.combine(links, ui) { l, u ->
                    val volumes = u.values.mapNotNull { it.volume }
                    Triple(statusLine(l), volumes.isNotEmpty() && volumes.all { it == 0 }, volumes.isNotEmpty())
                }.distinctUntilChanged().collect { (text, muted, controls) ->
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, notification(if (muted) getString(R.string.notif_muted, text) else text, muted, controls))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // the app stops watching: what was linked was last seen now
        runCatching { sessions.keys.forEach { lastSeen.record(it) } }
        runCatching { voice.shutdown() }
        runCatching { unregisterReceiver(bluetoothState) }
        runCatching { unregisterReceiver(wakeUps) }
        supervisor?.cancel()
        clients.toList().forEach { runCatching { it.close() } }
        scope.cancel()
        sessions.clear()
        gatts.clear()
        connected.value = 0
        ui.value = emptyMap()
        links.value = emptyMap()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val bluetoothState = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF,
                android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                    addLog("Bluetooth off")
                    supervisor?.cancel()
                    links.update { m -> m.mapValues { LinkState(LinkPhase.BLUETOOTH_OFF) } }
                }
                android.bluetooth.BluetoothAdapter.STATE_ON -> {
                    addLog("Bluetooth on")
                    startSupervisor()
                }
            }
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(text: String, muted: Boolean = false, controls: Boolean = false): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setColor(0xFFB23A1E.toInt())
            .setSmallIcon(app.ihearyou.R.drawable.ic_stat_meter)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0, Intent(this, app.ihearyou.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (controls) {
            b.addAction(0, getString(R.string.quieter), control(app.ihearyou.control.ControlReceiver.ACTION_QUIETER, 1))
            b.addAction(0, getString(if (muted) R.string.unmute else R.string.mute), control(app.ihearyou.control.ControlReceiver.ACTION_MUTE, 2))
            b.addAction(0, getString(R.string.louder), control(app.ihearyou.control.ControlReceiver.ACTION_LOUDER, 3))
        }
        return b.build()
    }

    private fun control(action: String, code: Int): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            this, code,
            Intent(action).setPackage(packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )

    /** "Right linked · Left connecting", in side order, for the ongoing notification. */
    private fun statusLine(m: Map<String, LinkState>): String {
        if (m.isEmpty()) return getString(R.string.notif_looking)
        return m.entries.sortedBy { sides.value[it.key] ?: it.key }.joinToString(" · ") { (address, link) ->
            val side = sides.value[address].let {
                when (it) {
                    null -> getString(R.string.side_aid_title)
                    "Left", "Right" -> app.ihearyou.ui.sideTitle(this, it)
                    else -> it
                }
            }
            getString(R.string.notif_aid_status, side, app.ihearyou.ui.linkWord(this, link.phase).lowercase())
        }
    }

    private fun setLink(address: String, phase: LinkPhase, detail: String? = null, recovering: Boolean = false) {
        links.update { it + (address to LinkState(phase, detail, recovering)) }
    }

    // ------------------------------------------------------------------ discovery

    private fun startSupervisor() {
        supervisor?.cancel()
        supervisor = scope.launch { discoverAndSupervise() }
    }

    /** A freshly paired aid joins the running supervisor without disturbing the others. */
    private fun superviseNew(address: String) {
        if (supervised.any { it.equals(address, ignoreCase = true) }) return
        val parent = supervisor
        val device = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
        }.getOrNull() ?: return
        if (parent?.isActive == true) {
            scope.launch(parent) { superviseAid(device, BondStore(this@RemoteService)) }
        } else {
            startSupervisor()
        }
    }

    private suspend fun discoverAndSupervise() = coroutineScope {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            addLog("Bluetooth is off")
            return@coroutineScope
        }
        val store = BondStore(this@RemoteService)
        val known = store.knownAddresses()
        val aids = runCatching { adapter.bondedDevices.filter { AidFilter.isCandidate(it, known) } }
            .getOrElse {
                addLog("cannot list bonded devices: ${it.message}")
                emptyList()
            }
        if (aids.isEmpty()) {
            addLog("no bonded aids found")
            return@coroutineScope
        }
        aids.forEach { device -> launch { superviseAid(device, store) } }
    }

    // ------------------------------------------------------------------ supervisor

    /** STALE: no link within the attempt's patience; KICKED: restarted by [kick]. */
    private enum class Outcome { ENDED, RETRY, NEEDS_PAIRING, BLOCKED, TAKEN_OVER, UNSUPPORTED, STALE, KICKED }

    private suspend fun superviseAid(device: BluetoothDevice, store: BondStore) {
        val address = device.address
        if (!supervised.add(address)) return
        val engine = ButtonEngine(
            scope,
            doubleEnabled = {
                if (voice.recording) {
                    // while recording a double press only matters when it ends the note differently
                    val v = app.ihearyou.voice.VoiceSettings(this)
                    v.finish(address, Gesture.DOUBLE) != v.finish(address, Gesture.SHORT)
                } else {
                    mapping.get(address, Gesture.DOUBLE).let { it != ButtonAction.AID_DEFAULT && it != ButtonAction.NONE }
                }
            },
        ) { gesture -> onGesture(address, gesture) }
        val gatt = GattClient(this, device)
        synchronized(clients) { clients += gatt }
        var backoff = 2_000L
        // fresh attempts since the link was lost; within RECONNECT_PATIENCE_MS no fix is shown
        var attempt = 0
        var direct = false
        try { kotlinx.coroutines.supervisorScope {
            var current: kotlinx.coroutines.Job? = null
            launch {
                kick.collect { a ->
                    if ((a == null || a.equals(address, ignoreCase = true)) &&
                        links.value[address]?.phase == LinkPhase.CONNECTING
                    ) current?.cancel()
                }
            }
            while (true) {
                val patience = RECONNECT_PATIENCE_MS.getOrNull(attempt)
                val run = async { runSession(device, gatt, store, engine, patience, direct) }
                current = run
                val outcome = try {
                    run.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (!isActive) throw e
                    Outcome.KICKED
                } catch (t: Throwable) {
                    addLog("$address: ${t.message ?: t.javaClass.simpleName}")
                    Outcome.RETRY
                }
                current = null
                direct = false
                when (outcome) {
                    Outcome.STALE -> {
                        attempt++
                        direct = true
                        addLog("$address: no link after ${(patience ?: 0) / 1000} s, starting over (attempt $attempt)")
                        continue
                    }
                    Outcome.KICKED -> {
                        attempt = 0
                        direct = true
                        addLog("$address: reconnecting now")
                        continue
                    }
                    Outcome.UNSUPPORTED -> { coroutineContext.cancelChildren(); return@supervisorScope }
                    Outcome.NEEDS_PAIRING -> {
                        // nothing to retry until the wizard stores a key for this aid
                        bondsChanged.first { it.equals(address, ignoreCase = true) }
                        backoff = 2_000L
                        continue
                    }
                    Outcome.BLOCKED -> {
                        kotlinx.coroutines.withTimeoutOrNull(60_000) {
                            bondsChanged.first { it.equals(address, ignoreCase = true) }
                        }
                        continue
                    }
                    Outcome.TAKEN_OVER -> {
                        // yield instead of fighting: re-login would knock the other app off,
                        // and it may do the same back
                        takeBack.first()
                        backoff = 2_000L
                        continue
                    }
                    Outcome.ENDED -> {
                        backoff = 2_000L
                        attempt = 0
                        // the aid was here a moment ago: try it directly first
                        direct = true
                    }
                    Outcome.RETRY -> Unit
                }
                setLink(address, LinkPhase.CONNECTING, recovering = RECONNECT_PATIENCE_MS.getOrNull(attempt) != null)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        } } finally {
            gatt.close()
            synchronized(clients) { clients -= gatt }
            supervised.remove(address)
        }
    }

    private suspend fun runSession(
        device: BluetoothDevice,
        gatt: GattClient,
        store: BondStore,
        engine: ButtonEngine,
        patienceMs: Long?,
        direct: Boolean,
    ): Outcome = coroutineScope {
        val address = device.address
        setLink(address, LinkPhase.CONNECTING, recovering = patienceMs != null)
        // a direct connect finds an aid in range at once; autoConnect waits for it to come
        // into range, but Android sometimes leaves that pending for good, hence the patience
        var up = false
        if (direct) {
            up = try {
                gatt.connect(timeoutMs = 12_000, autoConnect = false)
                true
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                false
            } catch (e: java.io.IOException) {
                false
            }
        }
        if (!up) {
            try {
                gatt.connect(timeoutMs = patienceMs, autoConnect = true)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                gatt.close()
                return@coroutineScope Outcome.STALE
            }
        }
        if (!gatt.hasService(Gn.SERVICE)) {
            addLog("$address: no GN service, not a supported aid")
            setLink(address, LinkPhase.UNSUPPORTED, getString(R.string.link_detail_no_gn))
            gatt.close()
            return@coroutineScope Outcome.UNSUPPORTED
        }
        val session = GnSession(gatt, this)
        var counted = false
        var collector: kotlinx.coroutines.Job? = null
        try {
            session.prepare()
            val platform = session.platform
            val inf = session.info
            if (platform == null) {
                addLog("$address: unknown GN version ${inf?.protocolVersion}.${inf?.handleListVersion}, read-only")
            }
            val bond = store.load(address)
            if (bond == null) {
                addLog("$address: no stored key, needs pairing")
                setLink(address, LinkPhase.NEEDS_PAIRING)
                return@coroutineScope Outcome.NEEDS_PAIRING
            }
            val result = session.sessionBond(bond.sharedAppKey, bond.index)
            when (result.code) {
                0 -> Unit
                13, 19, 20 -> {
                    addLog("$address: stored key rejected (code ${result.code}), needs pairing")
                    setLink(address, LinkPhase.NEEDS_PAIRING, getString(R.string.link_detail_key_rejected, result.code))
                    return@coroutineScope Outcome.NEEDS_PAIRING
                }
                3 -> {
                    addLog("$address: auth blocked (code 3)")
                    setLink(address, LinkPhase.BLOCKED, getString(R.string.link_detail_blocked))
                    return@coroutineScope Outcome.BLOCKED
                }
                else -> {
                    addLog("$address: session bond failed (code ${result.code})")
                    return@coroutineScope Outcome.RETRY
                }
            }
            sessions[address] = session
            gatts[address] = gatt
            connected.update { it + 1 }
            counted = true
            setLink(address, LinkPhase.LINKED, platform?.label)
            addLog("$address: connected (${device.name}, ${platform?.label ?: "unknown platform"})")

            // DIS + feature support are unencrypted: read before/independent of the bond.
            runCatching {
                fun str(b: ByteArray) = String(b, Charsets.UTF_8).trim('\u0000', ' ')
                val model = gatt.read(Gn.DIS_MODEL).let(::str)
                val fw = runCatching { gatt.read(Gn.DIS_FIRMWARE).let(::str) }.getOrNull()
                val serial = runCatching { gatt.read(Gn.DIS_SERIAL).let(::str) }.getOrNull()
                // CROS units are the CXxxx models; feature-support bits were ambiguous on our pair
                val cros = model.startsWith("CX", ignoreCase = true)
                info.update { it + (address to AidInfo(model, fw, serial, cros)) }
                addLog("$address: model=$model fw=$fw cros=$cros")
            }.onFailure { addLog("$address: device info failed: ${it.message}") }

            runCatching {
                val side = session.readHandle(Handles.SIDE)[0].toInt() and 0xff
                val label = when (side) { 0 -> "Left"; 1 -> "Right"; else -> "?$side" }
                app.ihearyou.domain.AidSides.remember(this@RemoteService, address, label)
                sides.update { it + (address to label) }
            }
            runCatching {
                val p = session.readHandle(Handles.ACTIVE_PROGRAM)[0].toInt() and 0xff
                val a = session.readHandle(Handles.MIC_ATTENUATION)
                states[address] = AidState(p, a)
                remember(address, p, a)
            }
            val buttonHandle = platform?.pushButton
            val hiStateHandle = platform?.hiState
            val streamTypeHandle = platform?.streamType
            val receiverHandle = platform?.receiverCheck
            session.setNotifyVector(
                listOfNotNull(
                    buttonHandle, Handles.ACTIVE_PROGRAM, Handles.MIC_ATTENUATION,
                    Handles.STREAM_STATUS, streamTypeHandle, hiStateHandle,
                    Handles.STREAMER_ATTENUATION.takeIf { platform?.streamVolume == true }, receiverHandle,
                ),
            )
            // battery level notifies in plaintext on its own characteristic
            runCatching { gatt.subscribe(Gn.BATTERY) }
            launch {
                gatt.notifications.collect { (uuid, data) ->
                    if (uuid == Gn.BATTERY && data.isNotEmpty()) {
                        val level = data[0].toInt() and 0xff
                        ui.update { m -> m[address]?.let { st -> m + (address to st.copy(battery = level)) } ?: m }
                    }
                }
            }
            if (buttonHandle != null) addLog("$address: subscribed to button events")
            else addLog("$address: no button handle on this platform")
            runCatching { refreshState(address, session, gatt) }
            runCatching {
                stream.onConnect(address, session, info.value[address]?.cros == true)
            }.onFailure { addLog("$address: stream read failed: ${it.message}") }
            runCatching {
                val i = info.value[address]
                sound.onConnect(address, session, i?.serial, i?.cros == true)
            }.onFailure { addLog("$address: sound read failed: ${it.message}") }

            collector = launch {
                session.events.collect { (type, body) ->
                    if (type != Frames.TYPE_NOTIFY) return@collect
                    for (v in Frames.parseHandleValues(body)) {
                        if (v.handle == buttonHandle) {
                            onButton(address, engine, v.data)
                            continue
                        }
                        if (v.handle == hiStateHandle && v.data.isNotEmpty()) {
                            val b = v.data[0].toInt() and 0xff
                            ui.update { m -> m[address]?.let { st -> m + (address to st.copy(hiState = b)) } ?: m }
                            continue
                        }
                        if (v.handle == streamTypeHandle && v.data.isNotEmpty()) {
                            val v0 = v.data[0].toInt() and 0xff
                            ui.update { m -> m[address]?.let { st -> m + (address to st.copy(streamType = v0)) } ?: m }
                            // the program presets give way to the stream keys and back
                            sound.publish()
                            continue
                        }
                        if (v.handle == receiverHandle) {
                            stream.receiver(address, v.data, info.value[address]?.cros == true)
                            continue
                        }
                        when (v.handle) {
                            Handles.ACTIVE_PROGRAM -> {
                                // our own compensation write echoes back with the target value, which
                                // maybeRevert then sees as already settled
                                val p = v.data[0].toInt() and 0xff
                                states[address] = (states[address] ?: AidState()).copy(program = p)
                                remember(address, p, states[address]?.attenuation)
                                ui.update { m -> m[address]?.let { st ->
                                    m + (address to st.copy(
                                        program = p,
                                        programName = st.programs.firstOrNull { it.first == p }?.second,
                                        confirmedAt = System.currentTimeMillis(),
                                    ))
                                } ?: m }
                                sound.programChanged(address)
                                launch { stream.reread(address) }
                                maybeRevert(address)
                            }
                            Handles.STREAM_STATUS -> {
                                val v0 = v.data[0].toInt() and 0xff
                                ui.update { m -> m[address]?.let { st -> m + (address to st.copy(streamStatus = v0)) } ?: m }
                            }
                            Handles.STREAMER_ATTENUATION -> stream.level(address, v.data)
                            Handles.MIC_ATTENUATION -> {
                                val a = v.data.copyOf()
                                states[address] = (states[address] ?: AidState()).copy(attenuation = a)
                                remember(address, states[address]?.program, a)
                                ui.update { m -> m[address]?.let { st ->
                                    m + (address to st.copy(
                                        volume = a.getOrNull(1)?.toInt()?.and(0xff),
                                        confirmedAt = System.currentTimeMillis(),
                                    ))
                                } ?: m }
                                maybeRevert(address)
                            }
                        }
                    }
                }
            }
            val taken = kotlinx.coroutines.flow.merge(
                gatt.linkUp.filter { !it }.map { false },
                session.desync.filter { it }.map { true },
            ).first()
            val now = System.currentTimeMillis()
            val repeat = now - (lastDesync[address] ?: 0L) < 60_000
            if (taken) lastDesync[address] = now
            if (taken && !repeat) {
                // once: a lost frame can put the counters out of step; logging in again fixes it
                addLog("$address: session out of step, logging in again")
                Outcome.RETRY
            } else if (taken) {
                addLog("$address: another app logged in on this aid, standing back")
                setLink(address, LinkPhase.TAKEN_OVER, getString(R.string.link_detail_taken_over))
                Outcome.TAKEN_OVER
            } else {
                addLog("$address: link lost (${gatt.lastDisconnectStatus})")
                Outcome.ENDED
            }
        } finally {
            // every listener launched for this session (events, battery, …) must end with it,
            // or coroutineScope waits forever and the aid is never reconnected
            coroutineContext.cancelChildren()
            collector?.cancel()
            session.close()
            sessions.remove(address)
            gatts.remove(address)
            preGesture.remove(address)
            partnersPre.remove(address)
            target.remove(address)
            pendingUntil.remove(address)
            if (counted) {
                connected.update { (it - 1).coerceAtLeast(0) }
                runCatching { lastSeen.record(address) }
            }
            ui.update { it - address }
            sound.forget(address)
            gatt.close()
        }
    }

    private fun onButton(address: String, engine: ButtonEngine, data: ByteArray) {
        if (Frames.buttonPressed(data)) {
            if (!mapping.untouched(address) || voice.recording || voice.speaker.busy) {
                // while a gesture's target is still being held, the aid's momentary state is our
                // own correction in flight; the target is what the user had before this press
                val holding = target[address]?.takeIf { (pendingUntil[address] ?: 0L) > System.currentTimeMillis() }
                val snap = holding ?: stateBefore(address, System.currentTimeMillis())
                    ?: states[address] ?: AidState()
                val now = System.currentTimeMillis()
                if (preGesture.putIfAbsent(address, snap) == null) {
                    target.remove(address)
                    partnersPre[address] = sessions.keys.filter { it != address }.associateWith { other ->
                        target[other]?.takeIf { (pendingUntil[other] ?: 0L) > now }
                            ?: stateBefore(other, now) ?: states[other] ?: AidState()
                    }
                }
                pendingUntil[address] = now + 4_000
                addLog("  press snapshot: program=${snap.program} atten=${snap.attenuation?.joinToString("") { "%02X".format(it) }}")
            }
            engine.onPress()
        } else {
            engine.onRelease()
        }
    }

    // ------------------------------------------------------------------ state history

    private fun stateBefore(address: String, now: Long, marginMs: Long = 150): AidState? {
        val h = history[address] ?: return null
        synchronized(h) {
            val cutoff = now - marginMs
            for (s in h) if (s.ts <= cutoff) return AidState(s.program, s.attenuation)
            return h.lastOrNull()?.let { AidState(it.program, it.attenuation) }
        }
    }

    private fun remember(address: String, program: Int?, attenuation: ByteArray?) {
        val h = history.getOrPut(address) { ArrayDeque() }
        synchronized(h) {
            h.addFirst(Sample(System.currentTimeMillis(), program, attenuation))
            while (h.size > 24) h.removeLast()
        }
    }

    // ------------------------------------------------------------------ state / commands

    /** Name (handle 10) and, where the stream level exists, category (11) of program [index]. */
    private suspend fun readProgram(session: GnSession, index: Int): Pair<String?, Int?> {
        val name = runCatching {
            session.writeHandle(Handles.PROGRAM_NAME_SELECTOR, byteArrayOf(index.toByte()))
            val raw = session.readHandle(Handles.PROGRAM_NAME)
            String(raw, Charsets.UTF_8).trim('\u0000', ' ').ifBlank { null }
        }.getOrNull()
        val category = if (session.platform?.streamVolume == true) runCatching {
            session.readHandle(Handles.PROGRAM_CATEGORY).getOrNull(0)?.toInt()?.and(0xff)
        }.getOrNull() else null
        return name to category
    }

    private suspend fun refreshState(address: String, session: GnSession, gatt: GattClient) {
        val battery = runCatching { gatt.read(Gn.BATTERY)[0].toInt() and 0xff }.getOrNull()
        val tapHandle = session.platform?.tapConfig
        val tapRaw = tapHandle?.let { h -> runCatching { session.readHandle(h) }.getOrNull() }
        val tap = tapRaw?.let { ((it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8)) }
        val mask = runCatching { session.readHandle(Handles.AVAILABLE_PROGRAMS) }.getOrNull()
        val indices = mask?.let { Frames.programIndices(it) } ?: emptyList()
        val active = runCatching { session.readHandle(Handles.ACTIVE_PROGRAM)[0].toInt() and 0xff }.getOrNull()
        val programInfo = indices.associateWith { idx -> readProgram(session, idx) }
        val rawNames = programInfo.mapValues { it.value.first }
        val categories = programInfo.mapNotNull { (idx, v) -> v.second?.let { idx to it } }.toMap()
        val names = indices.map { idx -> idx to (rawNames[idx] ?: getString(R.string.program_default_name, idx + 1)) }
        val environments = runCatching {
            app.ihearyou.protocol.ProgramNames.parseEnvironmentIds(session.readHandle(Handles.ENVIRONMENT_ID))
        }.getOrDefault(emptyList())
        val kinds = indices.mapNotNull { idx ->
            app.ihearyou.protocol.ProgramNames.stockKind(environments.getOrNull(idx), rawNames[idx])?.let { idx to it }
        }.toMap()
        addLog("$address: program types $environments -> ${kinds.mapValues { it.value.name }}${if (categories.isNotEmpty()) " categories $categories" else ""}")
        val volume = runCatching { session.readHandle(Handles.MIC_ATTENUATION)[1].toInt() and 0xff }.getOrNull()
        addLog("$address: state battery=$battery tap=${tapRaw?.joinToString(" ") { "%02X".format(it) }} programs=$indices active=$active volume=$volume")
        val blob = if (session.platform?.standardFeatures == true) {
            runCatching { session.readHandle(Handles.ALL_STANDARD_FEATURES) }.getOrNull()
        } else null
        val perProgram = blob?.let { StandardFeatures.decodePrograms(it) } ?: emptyList()
        val features = perProgram.getOrNull(active ?: 0) ?: emptyList()
        val hiState = session.platform?.hiState?.let { h ->
            runCatching { session.readHandle(h)[0].toInt() and 0xff }.getOrNull()
        }
        val streamStatus = runCatching { session.readHandle(Handles.STREAM_STATUS)[0].toInt() and 0xff }.getOrNull()
        val streamType = session.platform?.streamType?.let { h -> runCatching { session.readHandle(h)[0].toInt() and 0xff }.getOrNull() }
        val previous = ui.value[address]
        ui.update {
            it + (address to AidUiState(
                battery = battery,
                hiState = hiState,
                program = active,
                programName = names.firstOrNull { p -> p.first == active }?.second,
                programs = names,
                programKinds = kinds,
                volume = volume,
                tap = tap,
                streamStatus = streamStatus,
                streamType = streamType,
                features = features,
                confirmedAt = System.currentTimeMillis(),
                sound = previous?.sound,
                streamControl = previous?.streamControl ?: false,
                streamAttenuation = previous?.streamAttenuation,
                micDefault = previous?.micDefault,
                streamDefault = previous?.streamDefault,
                programCategories = categories,
                environments = environments,
                tinnitusPrograms = previous?.tinnitusPrograms,
                receiver = previous?.receiver,
            ))
        }
        sound.publish()
    }

    private suspend fun handleCommand(c: Cmd) {
        when (c) {
            is Cmd.Program -> {
                val session = sessions[c.address] ?: return
                changeProgram(session, c.address, c.delta)
                gatts[c.address]?.let { g -> refreshState(c.address, session, g) }
                sound.programChanged(c.address)
                stream.reread(c.address)
            }
            is Cmd.SelectProgram -> selectProgram(c.address, c.index)
            is Cmd.ApplyFavourite -> applyFavourite(c.favourite)
            is Cmd.Volume -> {
                val session = sessions[c.address] ?: return
                adjustVolume(session, c.address, c.delta)
            }
            is Cmd.RefreshRssi -> {
                val g = gatts[c.address] ?: return
                runCatching {
                    val rssi = g.readRssi()
                    ui.update { m -> m[c.address]?.let { st -> m + (c.address to st.copy(rssi = rssi)) } ?: m }
                }
            }
            is Cmd.SetVolume -> setVolume(c.address, c.value)
            is Cmd.VolumeAll -> sessions.keys.toList().forEach { address ->
                val now = ui.value[address]?.volume ?: return@forEach
                val base = if (now == 0) lastAudible[address] ?: 0xAA else now
                setVolume(address, base + c.delta)
            }
            is Cmd.Mute -> {
                val targets = c.address?.let { listOf(it) } ?: sessions.keys.toList()
                val mute = c.on ?: targets.any { (ui.value[it]?.volume ?: 0) > 0 }
                targets.forEach { address ->
                    if (mute) setVolume(address, 0)
                    else setVolume(address, lastAudible[address] ?: 0xAA)
                }
            }
            is Cmd.VoiceStart -> voice.start(sessions.keys.firstOrNull() ?: "", micReady.value, sessions.keys)
            is Cmd.VoiceStop -> voice.finishWith(app.ihearyou.voice.Destination.SAVE, "notification")
            is Cmd.VoiceRetry -> voice.retry(c.id, c.destination)
            is Cmd.VoiceDelete -> voice.delete(c.id)
            is Cmd.VoiceImport -> voice.import(c.file, c.destination)
            is Cmd.WhiteNoiseChanged -> {
                if (app.ihearyou.voice.VoiceSettings(this).whiteNoise) app.ihearyou.marmot.MarmotHost.start(this)
                else app.ihearyou.marmot.MarmotHost.stop()
            }
            is Cmd.AcceptInvite -> runCatching {
                if (c.accept) app.ihearyou.marmot.MarmotHost.accept(c.groupId) else app.ihearyou.marmot.MarmotHost.decline(c.groupId)
                addLog("white noise: invite ${if (c.accept) "accepted" else "declined"}")
            }.onFailure { addLog("white noise: invite failed: ${it.message}") }
            is Cmd.SimulateGesture -> {
                val side = c.side.lowercase()
                val address = sides.value.entries.firstOrNull { it.value.lowercase().startsWith(side.take(1)) }?.key
                    ?: sessions.keys.firstOrNull()
                val gesture = runCatching { Gesture.valueOf(c.gesture.uppercase()) }.getOrNull()
                if (address == null || gesture == null) addLog("debug: no aid for ${c.side} / ${c.gesture}")
                else {
                    addLog("debug: simulated $gesture on $address")
                    preGesture.putIfAbsent(address, states[address] ?: AidState())
                    onGesture(address, gesture)
                }
            }
            is Cmd.Forget -> {
                BondStore(this).clear(c.address)
                addLog("${c.address}: key forgotten")
                gatts[c.address]?.close()
            }
            is Cmd.SoundPreset -> sound.applyPreset(c.program, c.kind)
            is Cmd.SoundEq -> sound.setEq(c.program, c.eq)
            is Cmd.SoundReset -> sound.reset(c.program)
            is Cmd.SoundFeature -> sound.setFeature(c.program, c.position)
            is Cmd.TinnitusSet -> sound.setTinnitus(c.program, c.change)
            is Cmd.StreamLevel -> stream.setLevel(c.value, c.tinnitus)
            is Cmd.StreamMute -> stream.mute(c.on, c.tinnitus)
            is Cmd.MixIn -> stream.mixIn(c.kind)
            is Cmd.Tap -> {
                val session = sessions[c.address] ?: return
                val tapHandle = session.platform?.tapConfig ?: return
                val cur = session.readHandle(tapHandle)
                var v = (cur[0].toInt() and 0xff) or ((cur[1].toInt() and 0xff) shl 8)
                if (c.calls != null) v = if (c.calls) v or 0x0002 else v and 0x0002.inv()
                if (c.program != null) v = if (c.program) v or 0x0001 else v and 0x0001.inv()
                val payload = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
                session.writeHandle(tapHandle, payload)
                val back = session.readHandle(tapHandle)
                val confirmed = (back[0].toInt() and 0xff) or ((back[1].toInt() and 0xff) shl 8)
                addLog("${c.address}: tap -> %02X %02X, reads back %04X".format(payload[0], payload[1], confirmed))
                ui.update { m -> m[c.address]?.let { st -> m + (c.address to st.copy(tap = confirmed)) } ?: m }
            }
        }
    }

    // ------------------------------------------------------------------ gestures

    private fun onGesture(address: String, gesture: Gesture) {
        val mapped = mapping.get(address, gesture)
        // While a voice note records, every press on either aid ends it (whatever it is mapped
        // to); a short press during a spoken reply stops the reply. Either way the aid's own
        // step is undone like any mapped action's.
        val action = when {
            voice.recording -> {
                voice.finish(address, gesture)
                ButtonAction.NONE
            }
            gesture == Gesture.SHORT && voice.speaker.busy && voice.skipReply() -> {
                addLog("  reply stopped")
                ButtonAction.NONE
            }
            mapped == ButtonAction.VOICE_NOTE -> {
                voice.start(address, micReady.value, sessions.keys)
                ButtonAction.NONE
            }
            else -> mapped
        }
        addLog("$address: $gesture -> ${if (action == mapped) action.name else "${mapped.name} (voice: ${action.name})"}")
        val snap = preGesture.remove(address)
        val partners = partnersPre.remove(address).orEmpty()
        if (action == ButtonAction.AID_DEFAULT || snap == null) {
            pendingUntil.remove(address)
            target.remove(address)
            return
        }
        scope.launch {
            // Hold the aid to the pre-press state (or one step from it); the aid's own change
            // may land before or after this point, maybeRevert handles both. A linked partner
            // aid took the same step, so it is held the same way, from its own pre-press state.
            target[address] = targetFor(address, snap, action)
            pendingUntil[address] = System.currentTimeMillis() + 3_000
            maybeRevert(address)
            for ((other, before) in partners) {
                if (!sessions.containsKey(other)) continue
                target[other] = targetFor(other, before, action)
                pendingUntil[other] = System.currentTimeMillis() + 3_000
                maybeRevert(other)
            }
            performAction(address, action)
        }
    }

    private suspend fun targetFor(address: String, snap: AidState, action: ButtonAction): AidState {
        val programs = if (action == ButtonAction.PROGRAM_NEXT || action == ButtonAction.PROGRAM_PREVIOUS) {
            runCatching {
                Frames.programIndices(sessions[address]!!.readHandle(Handles.AVAILABLE_PROGRAMS))
            }.getOrNull().orEmpty()
        } else emptyList()
        val t = Compensation.target(Compensation.Snapshot(snap.program, snap.attenuation), action, programs)
        return AidState(t.program, t.attenuation)
    }

    private suspend fun performAction(address: String, action: ButtonAction) {
        val bridge = MediaBridgeService.instance
        val session = sessions[address]
        when (action) {
            ButtonAction.NONE, ButtonAction.AID_DEFAULT, ButtonAction.VOICE_NOTE -> return
            ButtonAction.PLAY_PAUSE -> {
                if (bridge == null) addLog("  media bridge not granted") else
                    addLog("  -> ${if (bridge.playPause()) "play/pause" else "no media session"}")
            }
            ButtonAction.NEXT_TRACK -> {
                if (bridge == null) addLog("  media bridge not granted") else
                    addLog("  -> ${if (bridge.next()) "next" else "no media session"}")
            }
            ButtonAction.PREVIOUS_TRACK -> {
                if (bridge == null) addLog("  media bridge not granted") else
                    addLog("  -> ${if (bridge.previous()) "previous" else "no media session"}")
            }
            // applied through the gesture target by maybeRevert
            ButtonAction.VOLUME_UP, ButtonAction.VOLUME_DOWN,
            ButtonAction.PROGRAM_NEXT, ButtonAction.PROGRAM_PREVIOUS -> return
        }
    }

    private suspend fun selectProgram(address: String, index: Int) {
        val session = sessions[address] ?: return
        session.writeHandle(Handles.ACTIVE_PROGRAM, byteArrayOf(index.toByte()))
        addLog("$address: program -> $index")
        ui.update { m -> m[address]?.let { st ->
            m + (address to st.copy(program = index,
                programName = st.programs.firstOrNull { it.first == index }?.second))
        } ?: m }
        sound.programChanged(address)
        stream.reread(address)
    }

    /** The first receiver's sound state, as the panel shows it. */
    private fun pairSoundState(): SoundState? {
        val entries = ui.value.entries.filter { it.value.sound != null }
        return (entries.firstOrNull { info.value[it.key]?.cros != true } ?: entries.firstOrNull())?.value?.sound
    }

    /**
     * Applies [f] part by part with the commands the panel keys use, so their guards apply: the
     * program on every receiver that has it, then each aid's level (the aid may reset it with the
     * program), then preset or bass/middle/treble, then feature positions. Values already set are
     * not written again. Each part is confirmed by a read; a part these aids cannot take is skipped.
     */
    private suspend fun applyFavourite(f: app.ihearyou.domain.Favourite) {
        val results = LinkedHashMap<app.ihearyou.domain.FavouritePart, app.ihearyou.domain.PartResult>()
        var current: app.ihearyou.domain.FavouritePart? = null
        fun progress(part: app.ihearyou.domain.FavouritePart?) {
            current = part
            favouriteRun.value = app.ihearyou.domain.FavouriteRun(f.id, f.name, running = true, current = part, results = results.toMap())
        }
        addLog("favourite \"${f.name}\": applying")
        val store = app.ihearyou.domain.FavouriteStore(this)
        try {
            if (f.program != null) {
                progress(app.ihearyou.domain.FavouritePart.PROGRAM)
                val receivers = sessions.keys.filter { a ->
                    info.value[a]?.cros != true && ui.value[a]?.programs?.any { it.first == f.program } == true
                }
                results[app.ihearyou.domain.FavouritePart.PROGRAM] = if (receivers.isEmpty()) {
                    app.ihearyou.domain.PartResult.SKIPPED
                } else {
                    var ok = true
                    var changed = false
                    for (a in receivers) {
                        if (ui.value[a]?.program == f.program) continue
                        changed = true
                        val confirmed = runCatching {
                            selectProgram(a, f.program)
                            delay(300)
                            (sessions[a]?.readHandle(Handles.ACTIVE_PROGRAM)?.get(0)?.toInt()?.and(0xff)) == f.program
                        }.getOrDefault(false)
                        if (!confirmed) ok = false
                    }
                    // let the aid settle on the program before the level and sound follow
                    if (changed) delay(1_200)
                    if (ok) app.ihearyou.domain.PartResult.DONE else app.ihearyou.domain.PartResult.FAILED
                }
            }
            if (f.volumes.isNotEmpty()) {
                progress(app.ihearyou.domain.FavouritePart.VOLUME)
                val targets = sessions.keys.mapNotNull { a ->
                    f.volumes[store.aidKey(a, info.value[a]?.serial)]?.let { a to it }
                }
                results[app.ihearyou.domain.FavouritePart.VOLUME] = if (targets.isEmpty()) {
                    app.ihearyou.domain.PartResult.SKIPPED
                } else {
                    var ok = true
                    for ((a, level) in targets) {
                        val now = runCatching { sessions[a]?.readHandle(Handles.MIC_ATTENUATION)?.get(1)?.toInt()?.and(0xff) }.getOrNull()
                        if (now == level) continue
                        if (!setVolume(a, level)) { ok = false; continue }
                        val back = runCatching { sessions[a]?.readHandle(Handles.MIC_ATTENUATION)?.get(1)?.toInt()?.and(0xff) }.getOrNull()
                        if (back != level) ok = false
                    }
                    if (ok) app.ihearyou.domain.PartResult.DONE else app.ihearyou.domain.PartResult.FAILED
                }
            }
            if (f.preset != null || f.eq != null) {
                progress(app.ihearyou.domain.FavouritePart.SOUND)
                val st = pairSoundState()
                results[app.ihearyou.domain.FavouritePart.SOUND] = when {
                    st == null || (f.program != null && st.program != f.program) -> app.ihearyou.domain.PartResult.SKIPPED
                    f.preset != null && st.active == f.preset -> app.ihearyou.domain.PartResult.DONE
                    f.preset != null && f.preset in st.presets ->
                        if (sound.applyPreset(st.program, f.preset)) app.ihearyou.domain.PartResult.DONE else app.ihearyou.domain.PartResult.FAILED
                    f.eq != null && st.eq == f.eq && st.active == null -> app.ihearyou.domain.PartResult.DONE
                    f.eq != null ->
                        if (sound.setEq(st.program, f.eq)) app.ihearyou.domain.PartResult.DONE else app.ihearyou.domain.PartResult.FAILED
                    else -> app.ihearyou.domain.PartResult.SKIPPED
                }
            }
            if (f.enhancer.isNotEmpty()) {
                progress(app.ihearyou.domain.FavouritePart.ENHANCER)
                var applied = 0
                var failed = false
                for (position in f.enhancer) {
                    val st = pairSoundState() ?: break
                    if (f.program != null && st.program != f.program) break
                    val control = st.enhancer.firstOrNull { it.control == position.control } ?: continue
                    if (position !in control.positions) continue
                    if (control.selected == position) { applied++; continue }
                    if (sound.setFeature(st.program, position)) applied++ else failed = true
                }
                results[app.ihearyou.domain.FavouritePart.ENHANCER] = when {
                    failed -> app.ihearyou.domain.PartResult.FAILED
                    applied == 0 -> app.ihearyou.domain.PartResult.SKIPPED
                    else -> app.ihearyou.domain.PartResult.DONE
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            addLog("favourite \"${f.name}\": ${t.message ?: t.javaClass.simpleName}")
            current?.let { results.putIfAbsent(it, app.ihearyou.domain.PartResult.FAILED) }
        } finally {
            favouriteRun.value = app.ihearyou.domain.FavouriteRun(
                f.id, f.name, running = false, current = null, results = results.toMap(), finishedAt = System.currentTimeMillis(),
            )
            addLog("favourite \"${f.name}\": ${results.entries.joinToString(", ") { "${it.key.name.lowercase()} ${it.value.name.lowercase()}" }}")
        }
    }

    private suspend fun setVolume(address: String, value: Int): Boolean {
        val session = sessions[address] ?: return false
        val v = value.coerceIn(0, 255)
        return runCatching {
            val current = session.readHandle(Handles.MIC_ATTENUATION)
            val now = current[1].toInt() and 0xff
            if (now > 0) lastAudible[address] = now
            val payload = byteArrayOf(current[0], v.toByte())
            session.writeHandle(Handles.MIC_ATTENUATION, payload)
            states[address] = (states[address] ?: AidState()).copy(attenuation = payload)
            remember(address, current[0].toInt() and 0xff, payload)
            ui.update { m -> m[address]?.let { st -> m + (address to st.copy(volume = v)) } ?: m }
            addLog("$address: volume -> 0x%02X".format(v))
            true
        }.getOrElse {
            addLog("$address: volume failed: ${it.message}")
            false
        }
    }

    private suspend fun adjustVolume(session: GnSession?, address: String, delta: Int) {
        if (session == null) return
        runCatching {
            val cur = session.readHandle(Handles.MIC_ATTENUATION)
            val program = cur[0].toInt() and 0xff
            val vol = ((cur[1].toInt() and 0xff) + delta).coerceIn(0, 255)
            val payload = byteArrayOf(program.toByte(), vol.toByte())
            session.writeHandle(Handles.MIC_ATTENUATION, payload)
            states[address] = AidState(program, payload)
            remember(address, program, payload)
            addLog("  volume -> 0x%02X".format(vol))
        }.onFailure { addLog("  volume failed: ${it.message}") }
    }

    private suspend fun changeProgram(session: GnSession?, address: String, delta: Int) {
        if (session == null) return
        runCatching {
            val mask = session.readHandle(Handles.AVAILABLE_PROGRAMS)
            val indices = Frames.programIndices(mask)
            if (indices.isEmpty()) return@runCatching
            val cur = session.readHandle(Handles.ACTIVE_PROGRAM)[0].toInt() and 0xff
            val pos = indices.indexOf(cur).takeIf { it >= 0 } ?: 0
            val next = indices[(pos + delta + indices.size) % indices.size]
            session.writeHandle(Handles.ACTIVE_PROGRAM, byteArrayOf(next.toByte()))
            states[address] = (states[address] ?: AidState()).copy(program = next)
            remember(address, next, states[address]?.attenuation)
            addLog("  program -> $next")
        }.onFailure { addLog("  program failed: ${it.message}") }
    }

    /**
     * Event-driven compensation: once the gesture is decided, any program/attenuation change seen
     * inside the window is pushed back to the gesture target with ordinary property writes
     * (handles 8, 5).
     */
    private fun maybeRevert(address: String) {
        val goal = target[address] ?: return
        val until = pendingUntil[address] ?: 0L
        if (System.currentTimeMillis() > until) {
            target.remove(address)
            pendingUntil.remove(address)
            return
        }
        if (!revertInFlight.add(address)) return
        scope.launch {
            try {
                val session = sessions[address] ?: return@launch
                delay(80)
                val st = states[address]
                if (st?.program != null && goal.program != null && st.program != goal.program) {
                    session.writeHandle(Handles.ACTIVE_PROGRAM, byteArrayOf(goal.program.toByte()))
                    addLog("  program ${st.program} -> ${goal.program}")
                    states[address] = st.copy(program = goal.program)
                    remember(address, goal.program, st.attenuation)
                    showProgram(address, goal.program)
                }
                val at = states[address]?.attenuation
                if (at != null && goal.attenuation != null && at.size >= 2 && goal.attenuation.size >= 2 &&
                    at[1] != goal.attenuation[1]
                ) {
                    val program = goal.program ?: (at[0].toInt() and 0xff)
                    val payload = byteArrayOf(program.toByte(), goal.attenuation[1])
                    session.writeHandle(Handles.MIC_ATTENUATION, payload)
                    addLog("  volume 0x%02X -> 0x%02X".format(at[1], goal.attenuation[1]))
                    states[address] = AidState(program, payload)
                    remember(address, program, payload)
                    ui.update { m -> m[address]?.let { u -> m + (address to u.copy(volume = payload[1].toInt() and 0xff)) } ?: m }
                }
            } catch (t: Throwable) {
                addLog("  revert failed: ${t.message}")
            } finally {
                delay(120)
                revertInFlight.remove(address)
            }
            if (!settled(address)) maybeRevert(address)
        }
    }

    private fun settled(address: String): Boolean {
        val goal = target[address] ?: return true
        val st = states[address] ?: return true
        val programOk = goal.program == null || st.program == null || st.program == goal.program
        val volumeOk = goal.attenuation == null || st.attenuation == null ||
            st.attenuation.getOrNull(1) == goal.attenuation.getOrNull(1)
        return programOk && volumeOk
    }

    private fun showProgram(address: String, program: Int) {
        ui.update { m -> m[address]?.let { u ->
            m + (address to u.copy(program = program, programName = u.programs.firstOrNull { it.first == program }?.second))
        } ?: m }
        sound.programChanged(address)
        scope.launch { stream.reread(address) }
    }

}
