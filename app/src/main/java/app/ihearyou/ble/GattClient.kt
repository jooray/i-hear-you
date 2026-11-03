package app.ihearyou.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Serialized wrapper around BluetoothGatt. One instance per aid, holding at most one live
 * BluetoothGatt: [connect] closes the previous one first, and callbacks from any other instance
 * are ignored, so reconnect loops cannot pile up registered clients.
 * All GATT operations go through [mutex]; results are delivered via CompletableDeferred and every
 * pending operation fails at once when the link drops.
 */
class GattClient(context: Context, val device: BluetoothDevice) {

    private val appContext = context.applicationContext
    @Volatile private var gatt: BluetoothGatt? = null
    /** Instances already closed; their late callbacks must not touch the current link. */
    private val stale: MutableSet<BluetoothGatt> =
        java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap()))
    private val mutex = Mutex()

    private var connectDeferred: CompletableDeferred<Int>? = null
    private var servicesDeferred: CompletableDeferred<Int>? = null
    private var mtuDeferred: CompletableDeferred<Int>? = null
    private var readDeferred: CompletableDeferred<Pair<Int, ByteArray?>>? = null
    private var writeDeferred: CompletableDeferred<Int>? = null
    private var cccdDeferred: CompletableDeferred<Int>? = null
    private var rssiDeferred: CompletableDeferred<Int>? = null

    private val _linkUp = MutableStateFlow(false)
    /** True while the current BluetoothGatt is connected; a StateFlow so a drop is never missed. */
    val linkUp: StateFlow<Boolean> = _linkUp

    /** Status of the last disconnect (0 when closed locally). */
    @Volatile var lastDisconnectStatus: Int = 0
        private set

    private val _notifications = MutableSharedFlow<Pair<UUID, ByteArray>>(
        replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications: SharedFlow<Pair<UUID, ByteArray>> = _notifications

    private fun failPending(reason: String) {
        val e = IOException(reason)
        listOf(servicesDeferred, mtuDeferred, readDeferred, writeDeferred, cccdDeferred, rssiDeferred)
            .forEach { it?.completeExceptionally(e) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g in stale) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _linkUp.value = true
                connectDeferred?.complete(0)
            } else {
                lastDisconnectStatus = status
                _linkUp.value = false
                connectDeferred?.complete(if (status == 0) -1 else status)
                failPending("link lost ($status)")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !in stale) servicesDeferred?.complete(status)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !in stale) mtuDeferred?.complete(mtu)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            if (g !in stale) readDeferred?.complete(status to value)
        }

        @Deprecated("Android 12 path")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (g !in stale) readDeferred?.complete(status to ch.value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (g !in stale) writeDeferred?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g !in stale) cccdDeferred?.complete(status)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (g !in stale) rssiDeferred?.complete(rssi)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            if (g !in stale) _notifications.tryEmit(ch.uuid to value)
        }

        @Deprecated("Android 12 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (g !in stale) ch.value?.let { _notifications.tryEmit(ch.uuid to it.copyOf()) }
        }
    }

    /**
     * Opens a fresh link. With [autoConnect] the OS waits for the aid to come into range, so
     * [timeoutMs] may be null to wait indefinitely.
     */
    suspend fun connect(timeoutMs: Long? = 25_000, autoConnect: Boolean = true) {
        close()
        val d = CompletableDeferred<Int>()
        connectDeferred = d
        gatt = device.connectGatt(appContext, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        val status = if (timeoutMs == null) d.await() else withTimeout(timeoutMs) { d.await() }
        if (status != 0) throw IOException("connect failed ($status)")
        try {
            val m = CompletableDeferred<Int>()
            mtuDeferred = m
            gatt?.requestMtu(247)
            withTimeout(3000) { m.await() }
        } catch (_: TimeoutCancellationException) {
            // MTU negotiation is best-effort
        }
        val s = CompletableDeferred<Int>()
        servicesDeferred = s
        if (gatt?.discoverServices() != true) throw IOException("discoverServices failed")
        val st = withTimeout(20_000) { s.await() }
        if (st != 0) throw IOException("service discovery failed ($st)")
    }

    private fun requireGatt(): BluetoothGatt =
        gatt?.takeIf { _linkUp.value } ?: throw IOException("not connected")

    fun hasService(uuid: UUID): Boolean = gatt?.getService(uuid) != null

    fun characteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    suspend fun read(uuid: UUID, timeoutMs: Long = 6_000): ByteArray = mutex.withLock {
        val ch = characteristic(uuid) ?: throw IOException("no characteristic $uuid")
        val d = CompletableDeferred<Pair<Int, ByteArray?>>()
        readDeferred = d
        if (!requireGatt().readCharacteristic(ch)) throw IOException("read rejected")
        val (status, value) = withTimeout(timeoutMs) { d.await() }
        if (status != 0) throw IOException("read status $status on $uuid")
        value ?: throw IOException("empty read on $uuid")
    }

    /**
     * Writes one characteristic, retrying up to [attempts] times when the stack or the aid
     * answers with an error status (131 = busy/congested). The same bytes are resent: an
     * encrypted frame the aid refused was never consumed, and sending a newly encrypted frame
     * instead would put the counters out of step. A timeout is not retried, because the frame
     * may have arrived.
     */
    suspend fun write(
        uuid: UUID, data: ByteArray, response: Boolean = true, timeoutMs: Long = 6_000, attempts: Int = 1,
    ) = mutex.withLock {
        var last: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val ch = characteristic(uuid) ?: throw IOException("no characteristic $uuid")
                val type = if (response) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                val d = CompletableDeferred<Int>()
                if (response) writeDeferred = d
                writeCompat(requireGatt(), ch, data, type)
                if (response) {
                    val status = withTimeout(timeoutMs) { d.await() }
                    if (status != 0) throw IOException("write status $status on $uuid")
                }
                return@withLock
            } catch (e: TimeoutCancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (!_linkUp.value) throw e
                if (attempt < attempts - 1) kotlinx.coroutines.delay(120L * (attempt + 1))
            }
        }
        throw last ?: IOException("write failed")
    }

    private fun writeCompat(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray, type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(ch, data, type)
            if (rc != BluetoothGatt.GATT_SUCCESS) throw IOException("write rejected ($rc)")
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = type
                ch.value = data
                if (!g.writeCharacteristic(ch)) throw IOException("write rejected")
            }
        }
    }

    suspend fun readRssi(timeoutMs: Long = 4_000): Int = mutex.withLock {
        val d = CompletableDeferred<Int>()
        rssiDeferred = d
        if (!requireGatt().readRemoteRssi()) throw IOException("readRemoteRssi rejected")
        withTimeout(timeoutMs) { d.await() }
    }

    suspend fun subscribe(uuid: UUID, timeoutMs: Long = 4_000) = mutex.withLock {
        val ch = characteristic(uuid) ?: throw IOException("no characteristic $uuid")
        val g = requireGatt()
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            ?: return@withLock
        val d = CompletableDeferred<Int>()
        cccdDeferred = d
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
        val status = withTimeout(timeoutMs) { d.await() }
        if (status != 0) throw IOException("CCCD write status $status")
    }

    /** Disconnects and unregisters the current BluetoothGatt. Safe to call repeatedly. */
    fun close() {
        val g = gatt
        gatt = null
        _linkUp.value = false
        connectDeferred?.complete(-1)
        failPending("closed")
        if (g != null) {
            stale += g
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
    }
}
