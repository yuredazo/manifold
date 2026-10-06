package dev.mkzk.manifold.hub.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import dev.mkzk.manifold.hub.network.protocol.Crypto
import dev.mkzk.manifold.hub.network.protocol.KeyPair
import dev.mkzk.manifold.hub.network.protocol.fromHex
import dev.mkzk.manifold.hub.network.protocol.toHex
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "IdentityKeys"
private const val SEALED_PREFIX = "sealed:"
private const val IV_BYTES = 12
private const val TAG_BITS = 128

internal interface Sealer {
    fun seal(plain: ByteArray): ByteArray

    /** Null when the bytes were not sealed by this device's key, for instance after a restore onto another phone. */
    fun open(sealed: ByteArray): ByteArray?
}

internal class KeystoreSealer(private val alias: String = "manifold-identity") : Sealer {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }
    }
}

// A device that cannot seal keeps the key unsealed: a hub that forgets its identity on every start could never stay paired.
internal fun loadIdentityKeys(read: () -> String?, write: (String) -> Unit, sealer: Sealer): KeyPair {
    val stored = read()
    val legacy = stored?.takeIf { !it.startsWith(SEALED_PREFIX) }?.fromHex()?.takeIf { it.size == Crypto.KEY_LENGTH }
    if (legacy != null) {
        val keys = Crypto.keyPairFrom(legacy)
        write(sealOrPlain(keys, sealer))
        return keys
    }
    stored?.takeIf { it.startsWith(SEALED_PREFIX) }?.removePrefix(SEALED_PREFIX)?.fromHex()
        ?.let { sealer.open(it) }
        ?.takeIf { it.size == Crypto.KEY_LENGTH }
        ?.let { return Crypto.keyPairFrom(it) }
    if (stored != null) Log.w(TAG, "the stored identity could not be read, so this device gets a new one and has to be paired again")
    return Crypto.generateKeyPair().also { write(sealOrPlain(it, sealer)) }
}

private fun sealOrPlain(keys: KeyPair, sealer: Sealer): String = try {
    SEALED_PREFIX + sealer.seal(keys.private).toHex()
} catch (e: GeneralSecurityException) {
    Log.w(TAG, "cannot seal the identity key on this device", e)
    keys.private.toHex()
}
