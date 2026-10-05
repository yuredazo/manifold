package dev.mkzk.manifold.hub.net

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

internal class KeyPair(val private: ByteArray, val public: ByteArray)

/** Nothing here is original cryptography: these are the Noise primitives as the specification defines them. */
internal object Crypto {
    const val KEY_LENGTH = 32
    const val TAG_LENGTH = 16
    const val HASH_LENGTH = 32

    private val random = SecureRandom()

    fun generateKeyPair(): KeyPair {
        val seed = ByteArray(KEY_LENGTH).also(random::nextBytes)
        return keyPairFrom(seed)
    }

    fun keyPairFrom(private: ByteArray): KeyPair {
        val key = X25519PrivateKeyParameters(private, 0)
        return KeyPair(key.encoded, key.generatePublicKey().encoded)
    }

    /** Fails if the other side sent a weak point, which gives an all-zero secret. */
    fun diffieHellman(private: ByteArray, public: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(private, 0))
        val secret = ByteArray(KEY_LENGTH)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(public, 0), secret, 0)
        } catch (e: RuntimeException) {
            throw GeneralSecurityException("key agreement failed", e)
        }
        return secret
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    fun hkdf(chainingKey: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = hmac(chainingKey, input)
        val first = hmac(temp, byteArrayOf(1))
        val second = hmac(temp, first, byteArrayOf(2))
        return first to second
    }

    fun seal(key: ByteArray, counter: Long, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), TAG_LENGTH * 8, nonce(counter), associatedData))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val written = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, written)
        return out
    }

    fun open(key: ByteArray, counter: Long, associatedData: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (ciphertext.size < TAG_LENGTH) return null
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), TAG_LENGTH * 8, nonce(counter), associatedData))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        return try {
            val written = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            cipher.doFinal(out, written)
            out
        } catch (_: InvalidCipherTextException) {
            null
        }
    }

    private fun nonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[4 + i] = (counter ushr (8 * i)).toByte()
        return nonce
    }
}
