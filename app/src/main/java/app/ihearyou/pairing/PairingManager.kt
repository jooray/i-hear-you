package app.ihearyou.pairing

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import app.ihearyou.R
import app.ihearyou.ble.BondStore
import app.ihearyou.ble.GattClient
import app.ihearyou.ble.GnSession
import app.ihearyou.ble.StoredBond
import app.ihearyou.protocol.Gn
import java.io.IOException
import app.ihearyou.ble.AidFilter
import app.ihearyou.service.RemoteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ScanHit(val address: String, val name: String?, val rssi: Int)

sealed class PairState {
    data object Idle : PairState()
    data object Scanning : PairState()
    data class Progress(val message: String) : PairState()
    data class Done(val address: String) : PairState()
    data class Failed(val message: String) : PairState()
}

/**
 * First-time bonding with an aid:
 *   1. connect + first auth (expected code 21 = "restart me"),
 *   2. user power-cycles the aid,
 *   3. reconnect + finalize auth, store the shared key.
 */
class PairingManager private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: PairingManager? = null

        /** Process-wide, so leaving the Setup screen mid-reboot does not cancel pairing. */
        fun get(context: Context): PairingManager = instance ?: synchronized(this) {
            instance ?: PairingManager(context.applicationContext).also { instance = it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val hits = MutableStateFlow<List<ScanHit>>(emptyList())
    val state = MutableStateFlow<PairState>(PairState.Idle)

    private val adapter get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var scanner: BluetoothLeScanner? = null
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val name = record?.deviceName ?: result.device.name
            val isGn = record?.serviceUuids?.any { it.uuid == Gn.SERVICE } == true
            if (!isGn && !AidFilter.nameLooksLikeAid(name)) return
            val hit = ScanHit(result.device.address, name, result.rssi)
            hits.value = (hits.value.filterNot { it.address == hit.address } + hit)
                .sortedByDescending { it.rssi }
        }
    }

    fun scan() {
        val s = adapter?.bluetoothLeScanner ?: return
        scanner = s
        hits.value = emptyList()
        state.value = PairState.Scanning
        s.startScan(callback)
    }

    fun stopScan() {
        scanner?.stopScan(callback)
        scanner = null
        if (state.value == PairState.Scanning) state.value = PairState.Idle
    }

    fun pair(address: String) {
        stopScan()
        if (job?.isActive == true) return
        job = scope.launch { runPair(address) }
    }

    fun cancel() {
        job?.cancel()
        state.value = PairState.Idle
    }

    private suspend fun runPair(address: String) {
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            state.value = PairState.Failed(context.getString(R.string.pair_unknown_device, address))
            return
        }
        val gatt = GattClient(context, device)
        try {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                state.value = PairState.Progress(context.getString(R.string.pair_with_android))
                device.createBond()
                val bonded = withTimeoutOrNull(45_000) {
                    while (device.bondState != BluetoothDevice.BOND_BONDED) delay(500)
                    true
                }
                if (bonded != true) {
                    state.value = PairState.Failed(context.getString(R.string.pair_android_not_finished))
                    return
                }
            }

            state.value = PairState.Progress(context.getString(R.string.pair_connecting))
            gatt.connect(autoConnect = false)
            val session = GnSession(gatt, scope)
            session.prepare()
            val info = session.info
            if (info != null && info.bondMethod != 1) {
                state.value = PairState.Failed(
                    if (info.bondMethod == 2) context.getString(R.string.pair_passcode)
                    else context.getString(R.string.pair_update_mode),
                )
                return
            }
            val secVersion = info?.secVersion ?: 2
            val mic = info?.mic ?: false

            state.value = PairState.Progress(context.getString(R.string.pair_first_auth))
            val first = session.bootAuthFirst()
            session.close()
            if (first.sharedAppKey == null) {
                state.value = PairState.Failed(context.getString(R.string.pair_auth_failed, first.code))
                return
            }
            if (first.code == 0) {
                done(address, first.sharedAppKey, first.sharedIndex, mic, secVersion)
                return
            }
            if (first.code != 21) {
                state.value = PairState.Failed(failure(first.code))
                return
            }

            // up to 5 finalize attempts, 2–16 s apart; code 21 means
            // wait for another reboot, an auth error means disconnect and try again.
            var awaitReboot = true
            for (attempt in 1..5) {
                if (awaitReboot) {
                    state.value = PairState.Progress(context.getString(R.string.pair_restart_aid))
                    withTimeoutOrNull(180_000) { gatt.linkUp.first { !it } }
                }
                gatt.close()
                state.value = PairState.Progress(context.getString(R.string.pair_waiting))
                gatt.connect(autoConnect = true, timeoutMs = 120_000)
                val s2 = GnSession(gatt, scope)
                val fin = try {
                    s2.prepare()
                    state.value = PairState.Progress(
                        if (attempt > 1) context.getString(R.string.pair_finalizing_try, attempt)
                        else context.getString(R.string.pair_finalizing),
                    )
                    s2.bootAuthFinal(first.sharedAppKey)
                } catch (e: java.io.IOException) {
                    null
                } finally {
                    s2.close()
                }
                when (fin?.code) {
                    0 -> {
                        done(address, first.sharedAppKey, fin.sharedIndex, mic, secVersion)
                        return
                    }
                    21 -> awaitReboot = true
                    3 -> {
                        state.value = PairState.Failed(failure(3))
                        return
                    }
                    else -> awaitReboot = false
                }
                gatt.close()
                delay(1000L shl minOf(attempt, 4))
            }
            state.value = PairState.Failed(context.getString(R.string.pair_not_accepted))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            state.value = PairState.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            gatt.close()
        }
    }

    private fun failure(code: Int) = when (code) {
        3 -> context.getString(R.string.pair_blocked)
        13, 19, 20 -> context.getString(R.string.pair_rejected)
        else -> context.getString(R.string.pair_auth_failed_code, code)
    }

    private fun done(address: String, shared: ByteArray, index: Int, mic: Boolean, sec: Int) {
        save(address, shared, index, mic, sec)
        RemoteService.bondsChanged.tryEmit(address)
        RemoteService.start(context)
        state.value = PairState.Done(address)
    }

    private fun save(address: String, shared: ByteArray, index: Int, mic: Boolean, sec: Int) {
        BondStore(context).save(address, StoredBond(shared, index, mic, sec))
    }
}
