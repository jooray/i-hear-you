package app.ihearyou.ble

import android.bluetooth.BluetoothDevice
import android.os.ParcelUuid

/**
 * Which OS-bonded devices are worth probing. Aids are often renamed ("Sluchadla …"), and the GN
 * service is not always in the cached UUID list, so a device qualifies by any hearing-aid
 * service (GN, OTC, ASHA, HAP), a stored session key, or a GN brand name. The service then
 * checks for the GN service after connecting and drops devices without it.
 */
object AidFilter {

    private val SERVICES = listOf(
        "0000fefe-0000-1000-8000-00805f9b34fb", // GN
        "0000fd71-0000-1000-8000-00805f9b34fb", // GN OTC (Jabra Enhance)
        "0000fdf0-0000-1000-8000-00805f9b34fb", // ASHA
        "00001854-0000-1000-8000-00805f9b34fb", // Hearing Access (HAP)
    ).map { ParcelUuid.fromString(it) }

    private val BRANDS = listOf("ReSound", "Nexia", "Omnia", "Vivia", "Beltone", "Jabra", "Enhance", "Danalogic", "Interton")

    fun nameLooksLikeAid(name: String?): Boolean =
        name != null && BRANDS.any { name.contains(it, ignoreCase = true) }

    fun isCandidate(device: BluetoothDevice, knownAddresses: Set<String>): Boolean {
        if (device.address.uppercase() in knownAddresses) return true
        val uuids = runCatching { device.uuids }.getOrNull().orEmpty()
        if (uuids.any { it in SERVICES }) return true
        return nameLooksLikeAid(runCatching { device.name }.getOrNull())
    }
}
