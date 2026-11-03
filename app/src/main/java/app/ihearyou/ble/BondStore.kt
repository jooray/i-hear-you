package app.ihearyou.ble

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.ihearyou.protocol.Crypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredBond(
    val sharedAppKey: ByteArray,
    val index: Int,
    val mic: Boolean,
    val secVersion: Int,
)

/**
 * Local bond records, one per aid. The shared key is a secret: it never leaves the device,
 * is never logged, and is removed by "forget". Records are sealed with an AES-GCM key that lives
 * in the Android Keystore, and backup is off for the app, so a copied prefs file is useless.
 */
class BondStore(context: Context) {

    private val prefs = context.getSharedPreferences("bonds", Context.MODE_PRIVATE)

    fun load(address: String): StoredBond? {
        val stored = prefs.getString(key(address), null) ?: return null
        val sealed = stored.startsWith(SEALED)
        val raw = if (sealed) unseal(stored.removePrefix(SEALED)) ?: return null else stored
        val parts = raw.split(":")
        if (parts.size != 4) return null
        return runCatching {
            StoredBond(
                sharedAppKey = Crypto.hex(parts[0]),
                index = parts[1].toInt(),
                mic = parts[2] == "1",
                secVersion = parts[3].toInt(),
            )
        }.getOrNull()?.also { if (!sealed) save(address, it) } // migrate records from before sealing
    }

    fun save(address: String, bond: StoredBond) {
        val raw = "${Crypto.toHex(bond.sharedAppKey)}:${bond.index}:${if (bond.mic) 1 else 0}:${bond.secVersion}"
        prefs.edit().putString(key(address), SEALED + seal(raw)).commit()
    }

    fun clear(address: String) {
        prefs.edit().remove(key(address)).apply()
    }

    fun knownAddresses(): Set<String> = prefs.all.keys.map { it.removePrefix("bond_") }.toSet()

    private fun key(address: String) = "bond_${address.uppercase()}"

    private companion object {
        const val SEALED = "v1:"
        const val ALIAS = "ihearyou-bonds"

        /** Synchronized: two aids loading at once must not each generate (and overwrite) the key. */
        @Synchronized
        fun secret(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }

        fun seal(plain: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, secret())
            return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        }

        fun unseal(text: String): String? = runCatching {
            val blob = Base64.decode(text, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, blob, 0, 12))
            String(c.doFinal(blob, 12, blob.size - 12))
        }.getOrNull()
    }
}
