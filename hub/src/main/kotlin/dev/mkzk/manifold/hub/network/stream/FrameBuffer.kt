package dev.mkzk.manifold.hub.network.stream

import dev.mkzk.manifold.hub.network.protocol.Fragmenter
import dev.mkzk.manifold.hub.network.protocol.Frame
import java.nio.ByteBuffer
import java.util.TreeMap

internal class Assembled(val frame: Frame, val assemblyTime: Long)

internal class MissingFragments(val frameId: Int, val indexes: List<Int>)

internal class Poll(val frames: List<Assembled>, val missing: List<MissingFragments>)

internal class FrameBuffer {
    private class Partial(val id: Int, val timestamp: Int, val keyframe: Boolean, count: Int, val firstAt: Long) {
        val chunks = arrayOfNulls<ByteArray>(count)
        var received = 0
        var lastAt = firstAt
        val nacks = Nacks()
        val complete get() = received == chunks.size
    }

    private class Nacks {
        var lastAt = LONG_AGO
        var count = 0
    }

    private val pending = TreeMap<Int, Partial>(::compareIds)
    private val wholeFrameNacks = HashMap<Int, Nacks>()
    private var nextId = 0
    private var started = false

    var maxWait = 50_000_000L

    var reorderWait = 4_000_000L

    var nackInterval = 15_000_000L

    var needsKeyframe = true
        private set
    var lostFrames = 0
        private set

    var requestedFragments = 0
        private set

    fun add(fragment: ByteArray, now: Long): List<Assembled> {
        if (fragment.size < Fragmenter.HEADER_LENGTH) return emptyList()
        val reader = ByteBuffer.wrap(fragment)
        val id = reader.getInt()
        val timestamp = reader.getInt()
        val flags = reader.get().toInt()
        val index = reader.getShort().toInt() and 0xFFFF
        val count = reader.getShort().toInt() and 0xFFFF
        if (count == 0 || count > Fragmenter.MAX_FRAGMENTS || index >= count) return emptyList()

        if (!started) {
            started = true
            nextId = id
        }
        val ahead = id - nextId
        if (ahead < 0) return emptyList()
        if (ahead > MAX_AHEAD) restartAt(id)

        val partial = pending.getOrPut(id) { Partial(id, timestamp, Fragmenter.isKeyframe(flags), count, now) }
        if (count != partial.chunks.size || partial.chunks[index] != null) return emptyList()
        partial.chunks[index] = fragment.copyOfRange(Fragmenter.HEADER_LENGTH, fragment.size)
        partial.received++
        partial.lastAt = now
        wholeFrameNacks.remove(id)
        return release()
    }

    fun poll(now: Long): Poll {
        val frames = ArrayList<Assembled>()
        while (pending.isNotEmpty()) {
            val waitingSince = (pending[nextId] ?: pending.firstEntry().value).firstAt
            if (now - waitingSince < maxWait) break
            pending.remove(nextId)
            wholeFrameNacks.remove(nextId)
            nextId++
            lostFrames++
            needsKeyframe = true
            frames += release()
        }
        return Poll(frames, missingAt(now))
    }

    fun needKeyframe() {
        needsKeyframe = true
    }

    private fun release(): List<Assembled> {
        val out = ArrayList<Assembled>()
        while (true) {
            val head = pending[nextId]?.takeIf { it.complete } ?: break
            pending.remove(nextId)
            nextId++
            if (head.keyframe) needsKeyframe = false
            if (needsKeyframe) continue
            val encoded = ByteBuffer.allocate(head.chunks.sumOf { it!!.size }).also { buffer -> head.chunks.forEach { buffer.put(it!!) } }.array()
            out += Assembled(Frame(head.id, head.timestamp, head.keyframe, encoded), head.lastAt - head.firstAt)
        }
        return out
    }

    private fun missingAt(now: Long): List<MissingFragments> {
        if (pending.isEmpty()) return emptyList()
        val missing = ArrayList<MissingFragments>()
        val newest = pending.lastKey()
        var id = nextId
        while (id - newest <= 0) {
            val partial = pending[id]
            if (partial == null) {
                val after = pending.higherEntry(id)?.value
                val nacks = wholeFrameNacks.getOrPut(id) { Nacks() }
                if (after != null && now - after.firstAt >= reorderWait && due(nacks, now)) {
                    missing += MissingFragments(id, emptyList())
                }
            } else if (!partial.complete && now - partial.lastAt >= reorderWait && due(partial.nacks, now)) {
                val indexes = partial.chunks.indices.filter { partial.chunks[it] == null }
                requestedFragments += indexes.size
                missing += MissingFragments(id, indexes)
            }
            id++
        }
        return missing
    }

    private fun due(nacks: Nacks, now: Long): Boolean {
        // Each ask waits twice as long as the one before, so one bad stretch of radio does not use them all up.
        val wait = nackInterval shl (nacks.count - 1).coerceAtLeast(0)
        if (nacks.count >= MAX_REQUESTS || now - nacks.lastAt < wait) return false
        nacks.lastAt = now
        nacks.count++
        return true
    }

    private fun restartAt(id: Int) {
        lostFrames += pending.size
        pending.clear()
        wholeFrameNacks.clear()
        nextId = id
        needsKeyframe = true
    }

    private companion object {
        /** Far enough back that the time since it is large, yet far enough from the limit that subtracting cannot overflow. */
        const val LONG_AGO = Long.MIN_VALUE / 2
        const val MAX_AHEAD = 120
        const val MAX_REQUESTS = 6

        /** Frame ids wrap around, so they are compared by the difference. */
        fun compareIds(first: Int, second: Int): Int = (first - second).coerceIn(-1, 1)
    }
}
