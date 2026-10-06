package dev.mkzk.manifold.hub.network.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun String.hex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** Official cacophony test vectors, https://github.com/centromere/cacophony. Fixed ephemeral keys make every byte comparable. */
class NoiseVectorsTest {

    private class Vector(val name: String, val fields: Map<String, String>, val messages: List<Pair<ByteArray, ByteArray>>) {
        fun bytes(key: String) = fields.getValue(key).hex()
    }

    private fun vectors(): List<Vector> {
        val text = javaClass.getResourceAsStream("/noise_vectors.txt")!!.bufferedReader().readText()
        return text.split("\n\n").filter { it.isNotBlank() }.map { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val fields = HashMap<String, String>()
            val messages = ArrayList<Pair<ByteArray, ByteArray>>()
            lines.drop(1).forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                if (key == "message") {
                    val (payload, ciphertext) = value.split(",")
                    messages += payload.hex() to ciphertext.hex()
                } else {
                    fields[key] = value
                }
            }
            Vector(lines.first().trim('[', ']'), fields, messages)
        }
    }

    private fun run(vector: Vector, pattern: Pattern) {
        val initStatic = Crypto.keyPairFrom(vector.bytes("init_static"))
        val respStatic = Crypto.keyPairFrom(vector.bytes("resp_static"))
        val prologue = vector.bytes("init_prologue")
        val initEphemeral = Crypto.keyPairFrom(vector.bytes("init_ephemeral"))
        val respEphemeral = Crypto.keyPairFrom(vector.bytes("resp_ephemeral"))

        val initiator = NoiseHandshake(
            pattern, true, initStatic,
            remoteStatic = if (pattern.initiatorKnowsResponder) respStatic.public else null,
            prologue = prologue,
            newEphemeral = { initEphemeral },
        )
        val responder = NoiseHandshake(pattern, false, respStatic, prologue = prologue, newEphemeral = { respEphemeral })

        val handshakeMessages = pattern.messages.size
        vector.messages.take(handshakeMessages).forEachIndexed { index, (payload, expected) ->
            val (writer, reader) = if (index % 2 == 0) initiator to responder else responder to initiator
            val sent = writer.writeMessage(payload)
            assertArrayEquals("${vector.name} handshake message $index", expected, sent)
            assertArrayEquals("${vector.name} payload $index", payload, reader.readMessage(sent))
        }

        assertTrue(initiator.complete && responder.complete)
        assertArrayEquals("${vector.name} handshake hash", vector.bytes("handshake_hash"), initiator.handshakeHash)
        assertArrayEquals(vector.bytes("handshake_hash"), responder.handshakeHash)
        assertArrayEquals(respStatic.public, initiator.remoteStaticKey)
        assertArrayEquals(initStatic.public, responder.remoteStaticKey)

        val fromInitiator = initiator.split()
        val fromResponder = responder.split()
        assertArrayEquals(fromInitiator.send, fromResponder.receive)
        assertArrayEquals(fromInitiator.receive, fromResponder.send)

        val counters = longArrayOf(0, 0)
        vector.messages.drop(handshakeMessages).forEachIndexed { offset, (payload, expected) ->
            val index = handshakeMessages + offset
            val side = index % 2
            val sendKey = if (side == 0) fromInitiator.send else fromResponder.send
            val sealed = Crypto.seal(sendKey, counters[side], ByteArray(0), payload)
            assertArrayEquals("${vector.name} transport message $index", expected, sealed)
            val receiveKey = if (side == 0) fromResponder.receive else fromInitiator.receive
            assertArrayEquals(payload, Crypto.open(receiveKey, counters[side], ByteArray(0), sealed))
            counters[side]++
        }
    }

    @Test
    fun xxMatchesTheOfficialVector() {
        run(vectors().first { it.name == Pattern.XX.protocolName }, Pattern.XX)
    }

    @Test
    fun ikMatchesTheOfficialVector() {
        run(vectors().first { it.name == Pattern.IK.protocolName }, Pattern.IK)
    }

    @Test
    fun theVectorFileHoldsBothPatterns() {
        assertEquals(setOf(Pattern.XX.protocolName, Pattern.IK.protocolName), vectors().map { it.name }.toSet())
    }
}
