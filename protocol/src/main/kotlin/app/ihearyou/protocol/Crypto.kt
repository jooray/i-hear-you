package app.ihearyou.protocol

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.SecretKeySpec

/**
 * GN link-layer crypto: AES-128-CTR + 4-byte MIC, and the session key schedule.
 */
object Crypto {

    /** Application keys used by the GN authentication handshake (security version 1 and 2). */
    val BASE_V1: ByteArray = hex("D5E8E50C3E8B64858626EA43F7247AE6D6653DDB3E88D557C1898AE52DF66893")
    val BASE_V2: ByteArray = hex("5468A98AFF90D955C48B7F1F98170F53E951C6E17E55A2D05BD18B7E41562F64")

    fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace(":", "").replace("\n", "")
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        }
    }

    fun toHex(b: ByteArray?): String = b?.joinToString("") { "%02X".format(it) } ?: ""

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }

    fun aesBlock(key: ByteArray, block: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(block)
    }

    /** Increment the last 4 bytes as a big-endian integer. */
    fun increment(counter: ByteArray) {
        for (i in 15 downTo 12) {
            counter[i]++
            if (counter[i].toInt() != 0) return
        }
        throw IllegalStateException("AES counter overflow")
    }

    fun ctrXor(data: ByteArray, key: ByteArray, counter: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var i = 0
        while (i < data.size) {
            val ks = aesBlock(key, counter)
            increment(counter)
            var j = 0
            while (j < 16 && i + j < data.size) {
                out[i + j] = (data[i + j].toInt() xor ks[j].toInt()).toByte()
                j++
            }
            i += 16
        }
        return out
    }

    /** MIC: CBC-MAC over B0 = 09 | ctr[12:16] | "AppLayer-" | len_be16. */
    fun mac(data: ByteArray, key: ByteArray, counter: ByteArray): ByteArray {
        val b0 = ByteArray(16)
        b0[0] = 9
        System.arraycopy(counter, 12, b0, 1, 4)
        val iv = "AppLayer-".toByteArray(Charsets.UTF_8)
        System.arraycopy(iv, 0, b0, 5, 9)
        b0[14] = ((data.size shr 8) and 0xff).toByte()
        b0[15] = (data.size and 0xff).toByte()
        var x = aesBlock(key, b0)
        var i = 0
        while (i < data.size) {
            val blk = ByteArray(16)
            System.arraycopy(data, i, blk, 0, minOf(16, data.size - i))
            for (j in 0 until 16) blk[j] = (blk[j].toInt() xor x[j].toInt()).toByte()
            x = aesBlock(key, blk)
            i += 16
        }
        return x.copyOfRange(0, 4)
    }

    /** Ephemeral P-256 key pair; the 65-byte uncompressed point is what the aid expects. */
    fun newKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun publicPoint(kp: KeyPair): ByteArray {
        val x509 = kp.public.encoded
        return x509.copyOfRange(x509.size - 65, x509.size) // 0x04 | X | Y
    }

    /** ECDH with the aid's public key (64 or 65 bytes); returns X with leading zeros stripped. */
    fun sharedSecret(own: KeyPair, peer: ByteArray): ByteArray {
        val point = if (peer.size == 65) peer else byteArrayOf(4) + peer
        val params = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val ecPoint = ECPoint(
            java.math.BigInteger(1, point.copyOfRange(1, 33)),
            java.math.BigInteger(1, point.copyOfRange(33, 65)),
        )
        val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ecPoint, params))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(own.private)
        ka.doPhase(pub, true)
        val dh = ka.generateSecret()
        var z = 0
        while (z < dh.size - 1 && dh[z].toInt() == 0) z++
        return dh.copyOfRange(z, dh.size)
    }
}

/** One direction-pair of session keys. Counters reset to ...0001 on set. */
class Encoder(var mic: Boolean) {
    private var ek: ByteArray = ByteArray(16)
    private var dk: ByteArray = ByteArray(16)
    private var ectr: ByteArray = ByteArray(16)
    private var dctr: ByteArray = ByteArray(16)

    fun setKeys(decryptKey: ByteArray, encryptKey: ByteArray) {
        ek = encryptKey.copyOfRange(0, 16)
        ectr = encryptKey.copyOfRange(16, 32)
        dk = decryptKey.copyOfRange(0, 16)
        dctr = decryptKey.copyOfRange(16, 32)
        for (c in arrayOf(ectr, dctr)) {
            c[12] = 0; c[13] = 0; c[14] = 0; c[15] = 1
        }
    }

    fun encrypt(plain: ByteArray): ByteArray {
        if (!mic) return Crypto.ctrXor(plain, ek, ectr)
        val m = Crypto.mac(plain, ek, ectr)
        val ks = Crypto.aesBlock(ek, ectr)
        val micBytes = ByteArray(4) { (m[it].toInt() xor ks[it].toInt()).toByte() }
        Crypto.increment(ectr)
        val ct = Crypto.ctrXor(plain, ek, ectr)
        return ct + micBytes
    }

    fun decrypt(data: ByteArray): ByteArray {
        if (!mic) return Crypto.ctrXor(data, dk, dctr)
        require(data.size >= 5) { "short packet" }
        val ct = data.copyOfRange(0, data.size - 4)
        val got = data.copyOfRange(data.size - 4, data.size)
        val start = dctr.copyOf()
        val ks = Crypto.aesBlock(dk, start)
        Crypto.increment(dctr)
        val pt = Crypto.ctrXor(ct, dk, dctr)
        val m = Crypto.mac(pt, dk, start)
        val micBytes = ByteArray(4) { (m[it].toInt() xor ks[it].toInt()).toByte() }
        require(micBytes.contentEquals(got)) { "MIC mismatch" }
        return pt
    }
}

data class Derived(
    val appSession: ByteArray,
    val hiSession: ByteArray,
    val sharedAppKey: ByteArray,
)

data class DeriveResult(
    val derived: Derived,
    val ownPublicPoint: ByteArray?,
)

/**
 * Session key schedule. Pass [hiPublicKey] only for the first boot auth (ECDH is mixed in);
 * pass [sharedAppKey] only for finalize/session bonds.
 */
fun deriveKeys(
    challenge: ByteArray,
    secVersion: Int,
    hiPublicKey: ByteArray? = null,
    sharedAppKey: ByteArray? = null,
): DeriveResult {
    require(challenge.size >= 36) { "challenge too short" }
    val base = if (secVersion == 1) Crypto.BASE_V1 else Crypto.BASE_V2
    val hikey = if (secVersion == 1) {
        Crypto.sha256(challenge.copyOfRange(0, 20), base)
    } else {
        Crypto.sha256(base, challenge.copyOfRange(0, 20))
    }
    var secret = Crypto.sha256(hikey, challenge.copyOfRange(20, 36))

    var ownPoint: ByteArray? = null
    if (hiPublicKey != null) {
        val kp = Crypto.newKeyPair()
        val dh = Crypto.sharedSecret(kp, hiPublicKey)
        ownPoint = Crypto.publicPoint(kp)
        secret = Crypto.sha256(secret, dh)
    }
    if (sharedAppKey != null) {
        secret = Crypto.sha256(secret, sharedAppKey.copyOfRange(0, 16))
    }
    val d = Derived(
        appSession = Crypto.sha256(secret, "appsession".toByteArray()),
        hiSession = Crypto.sha256(secret, "hisession ".toByteArray()),
        sharedAppKey = Crypto.sha256(secret, "appSharedBaseKey".toByteArray()),
    )
    return DeriveResult(d, ownPoint)
}

/**
 * Auth payload written to GNTrustedAppChallenge.
 * connType: 9/10/12 with MIC, 1/2/4 without. sharedIndex: 0 for boot, stored index for session.
 */
fun authPayload(encoder: Encoder, connType: Int, sharedIndex: Int, ourPoint: ByteArray?): ByteArray {
    val header = byteArrayOf(0, 0, 4, 0, connType.toByte(), sharedIndex.toByte())
    val body = encoder.encrypt("APP says hi ".toByteArray())
    return if (ourPoint != null) header + body + ourPoint.copyOfRange(1, ourPoint.size) else header + body
}

/** Parsed auth reply: code + payload ([sharedIndex]["HI says hi"]). */
data class AuthReply(val code: Int, val payload: ByteArray?)

fun parseAuthReply(body: ByteArray, encoder: Encoder): AuthReply {
    val code = body[0].toInt() and 0xff
    if (code != 0 || body.size <= 1) return AuthReply(code, null)
    val dec = encoder.decrypt(body.copyOfRange(1, body.size))
    return AuthReply(code, dec)
}
