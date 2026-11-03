package app.ihearyou.marmot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secret map for the White Noise identity key: values are AES-GCM encrypted under a
 * non-exportable Android Keystore key and kept in no-backup SharedPreferences. Thread-safe
 * (MDK calls its SecretStore from worker threads); decrypted values are cached in memory
 * because Keystore calls can take hundreds of milliseconds.
 */
class SecureKv(context: Context) {

    private val prefs = context.getSharedPreferences("marmot_secrets", Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<String, String>()

    @Synchronized
    private fun key(): SecretKey {
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

    fun get(name: String): String? {
        cache[name]?.let { return it }
        val stored = prefs.getString(name, null) ?: return null
        val (iv, ct) = stored.split(':').let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(c.doFinal(ct), Charsets.UTF_8).also { cache[name] = it }
    }

    @Synchronized
    fun put(name: String, value: String?) {
        if (value == null) {
            cache.remove(name)
            prefs.edit().remove(name).commit()
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(
            name, Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP),
        ).commit()
        cache[name] = value
    }

    companion object {
        private const val ALIAS = "ihearyou.marmot"
    }
}
