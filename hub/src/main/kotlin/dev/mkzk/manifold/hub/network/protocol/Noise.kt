package dev.mkzk.manifold.hub.network.protocol

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException

internal enum class Token { E, S, EE, ES, SE, SS }

internal enum class Pattern(val protocolName: String, val initiatorKnowsResponder: Boolean, val messages: List<List<Token>>) {
    XX(
        "Noise_XX_25519_ChaChaPoly_SHA256",
        false,
        listOf(
            listOf(Token.E),
            listOf(Token.E, Token.EE, Token.S, Token.ES),
            listOf(Token.S, Token.SE),
        ),
    ),
    IK(
        "Noise_IK_25519_ChaChaPoly_SHA256",
        true,
        listOf(
            listOf(Token.E, Token.ES, Token.S, Token.SS),
            listOf(Token.E, Token.EE, Token.SE),
        ),
    ),
}

internal class TransportKeys(val send: ByteArray, val receive: ByteArray)

private class SymmetricState(protocolName: ByteArray) {
    var chainingKey: ByteArray
    var hash: ByteArray
    private var key: ByteArray? = null
    private var nonce = 0L

    val hasKey get() = key != null

    init {
        hash = if (protocolName.size <= Crypto.HASH_LENGTH) protocolName.copyOf(Crypto.HASH_LENGTH) else Crypto.sha256(protocolName)
        chainingKey = hash
    }

    fun mixHash(data: ByteArray) {
        hash = Crypto.sha256(hash, data)
    }

    fun mixKey(input: ByteArray) {
        val (next, temp) = Crypto.hkdf(chainingKey, input)
        chainingKey = next
        key = temp
        nonce = 0
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val k = key
        val out = if (k == null) plaintext else Crypto.seal(k, nonce++, hash, plaintext)
        mixHash(out)
        return out
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val k = key
        val out = if (k == null) {
            ciphertext
        } else {
            Crypto.open(k, nonce, hash, ciphertext) ?: throw GeneralSecurityException("handshake message failed authentication")
        }
        if (k != null) nonce++
        mixHash(ciphertext)
        return out
    }

    fun split() = Crypto.hkdf(chainingKey, ByteArray(0))
}

/** Failures throw [GeneralSecurityException]. Trusting [remoteStaticKey] is the caller's job. */
internal class NoiseHandshake(
    private val pattern: Pattern,
    private val initiator: Boolean,
    private val staticKey: KeyPair,
    remoteStatic: ByteArray? = null,
    prologue: ByteArray = ByteArray(0),
    private val newEphemeral: () -> KeyPair = Crypto::generateKeyPair,
) {
    private val symmetric = SymmetricState(pattern.protocolName.toByteArray(Charsets.US_ASCII))
    private var ephemeral: KeyPair? = null
    private var remoteEphemeral: ByteArray? = null
    private var step = 0

    var remoteStaticKey: ByteArray? = remoteStatic
        private set

    val complete get() = step == pattern.messages.size

    val handshakeHash: ByteArray get() = symmetric.hash

    init {
        symmetric.mixHash(prologue)
        if (pattern.initiatorKnowsResponder) {
            val responderStatic = if (initiator) {
                requireNotNull(remoteStatic) { "${pattern.name} needs the responder's static key" }
            } else {
                staticKey.public
            }
            symmetric.mixHash(responderStatic)
        }
    }

    private val myTurn get() = (step % 2 == 0) == initiator

    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        check(!complete) { "the handshake is finished" }
        check(myTurn) { "it is the other side's turn" }
        val out = ByteArrayOutputStream()
        for (token in pattern.messages[step]) {
            when (token) {
                Token.E -> {
                    val fresh = newEphemeral()
                    ephemeral = fresh
                    out.write(fresh.public)
                    symmetric.mixHash(fresh.public)
                }
                Token.S -> out.write(symmetric.encryptAndHash(staticKey.public))
                else -> mixSecret(token)
            }
        }
        out.write(symmetric.encryptAndHash(payload))
        step++
        return out.toByteArray()
    }

    fun readMessage(message: ByteArray): ByteArray {
        check(!complete) { "the handshake is finished" }
        check(!myTurn) { "it is this side's turn to write" }
        var offset = 0
        fun take(count: Int): ByteArray {
            if (message.size - offset < count) throw GeneralSecurityException("handshake message too short")
            return message.copyOfRange(offset, offset + count).also { offset += count }
        }
        for (token in pattern.messages[step]) {
            when (token) {
                Token.E -> {
                    val theirs = take(Crypto.KEY_LENGTH)
                    remoteEphemeral = theirs
                    symmetric.mixHash(theirs)
                }
                Token.S -> {
                    val length = Crypto.KEY_LENGTH + if (symmetric.hasKey) Crypto.TAG_LENGTH else 0
                    remoteStaticKey = symmetric.decryptAndHash(take(length))
                }
                else -> mixSecret(token)
            }
        }
        val payload = symmetric.decryptAndHash(message.copyOfRange(offset, message.size))
        step++
        return payload
    }

    fun split(): TransportKeys {
        check(complete) { "the handshake is not finished" }
        val (first, second) = symmetric.split()
        return if (initiator) TransportKeys(send = first, receive = second) else TransportKeys(send = second, receive = first)
    }

    private fun mixSecret(token: Token) {
        val secret = when (token) {
            Token.EE -> dh(ephemeral, remoteEphemeral)
            Token.ES -> if (initiator) dh(ephemeral, remoteStaticKey) else dh(staticKey, remoteEphemeral)
            Token.SE -> if (initiator) dh(staticKey, remoteEphemeral) else dh(ephemeral, remoteStaticKey)
            Token.SS -> dh(staticKey, remoteStaticKey)
            else -> error("not a key agreement token")
        }
        symmetric.mixKey(secret)
    }

    private fun dh(own: KeyPair?, theirs: ByteArray?): ByteArray {
        val mine = own ?: throw GeneralSecurityException("handshake message came out of order")
        val other = theirs ?: throw GeneralSecurityException("handshake message came out of order")
        return Crypto.diffieHellman(mine.private, other)
    }
}
