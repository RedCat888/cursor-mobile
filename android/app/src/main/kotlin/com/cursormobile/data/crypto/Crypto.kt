package com.cursormobile.data.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * X25519 + HKDF-SHA256 + ChaCha20-Poly1305 (IETF, 12-byte nonce).
 *
 * Android ships two X25519 implementations and they encode keys differently:
 *
 *   - JDK (java.security.interfaces.XEC*) — encoded as X.509 SubjectPublicKeyInfo
 *     for public keys, PKCS8 for private keys.
 *   - Conscrypt (com.android.org.conscrypt.OpenSSLX25519*) — encoded as raw 32
 *     little-endian bytes, format `"raw"`. Does not implement XECPublicKey.
 *
 * Wire format is the RFC 7748 raw little-endian 32 bytes. To stay portable we:
 *   - On generate, read `getEncoded()` and slice the trailing 32 bytes (true for
 *     both encodings since the raw key is the last element).
 *   - On import, hand-build the SPKI / PKCS8 DER blob with the 32-byte payload.
 *     Both providers happily decode the standard DER form.
 */
object Crypto {
    private val random = SecureRandom()
    private const val ALGO = "X25519"

    // Fixed DER prefixes for X25519. Just before <32 raw bytes>.
    // SPKI: SEQUENCE { SEQUENCE { OID 1.3.101.110 }, BIT STRING { 00 || key } }
    private val SPKI_PREFIX = byteArrayOf(
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x6E, 0x03, 0x21, 0x00,
    )
    // PKCS8 v1: SEQUENCE { INTEGER 0, SEQUENCE { OID }, OCTET STRING { OCTET STRING { key } } }
    private val PKCS8_PREFIX = byteArrayOf(
        0x30, 0x2E, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x6E,
        0x04, 0x22, 0x04, 0x20,
    )

    data class KeyPair(val publicKeyB64: String, val privateKeyB64: String)

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(ALGO)
        val kp = kpg.generateKeyPair()
        val pubBytes = rawFromEncoded(kp.public.encoded)
        val privBytes = rawFromEncoded(kp.private.encoded)
        return KeyPair(b64(pubBytes), b64(privBytes))
    }

    fun deriveSharedKey(ourPrivB64: String, peerPubB64: String): ByteArray {
        val kf = KeyFactory.getInstance(ALGO)
        val pub = kf.generatePublic(X509EncodedKeySpec(SPKI_PREFIX + unb64(peerPubB64)))
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + unb64(ourPrivB64)))
        val ka = KeyAgreement.getInstance(ALGO)
        ka.init(priv)
        ka.doPhase(pub, true)
        val shared = ka.generateSecret()
        return hkdfSha256(shared, ByteArray(0), "cursor-mobile/v1".toByteArray(), 32)
    }

    data class Sealed(val nonceB64: String, val ciphertextB64: String)

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): Sealed {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return Sealed(b64(nonce), b64(ct))
    }

    fun open(key: ByteArray, sealed: Sealed, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(unb64(sealed.nonceB64)))
        cipher.updateAAD(aad)
        return cipher.doFinal(unb64(sealed.ciphertextB64))
    }

    fun fingerprint(pubB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(unb64(pubB64))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /**
     * Extract 32 raw little-endian bytes from whatever encoded form the
     * provider hands us. SPKI and PKCS8 both place the raw key as the final
     * element in their TLV chain, so the last 32 bytes are always the payload.
     * For Conscrypt's `format == "raw"` the whole array is the 32-byte key.
     */
    private fun rawFromEncoded(encoded: ByteArray): ByteArray {
        require(encoded.size >= 32) { "encoded key too short: ${encoded.size}" }
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.reset()
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val take = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, take)
            pos += take
            counter++
        }
        return out
    }
}
