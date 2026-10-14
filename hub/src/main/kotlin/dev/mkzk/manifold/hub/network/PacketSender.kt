package dev.mkzk.manifold.hub.network

import android.os.Process
import android.util.Log
import dev.mkzk.manifold.hub.network.protocol.Address

private const val STATS_TAG = "NetStats"

// Media that waited past staleNs is dropped: the receiver has given up on it, and a late burst only crowds out fresh frames.
internal class OutgoingQueue(private val staleNs: Long, private val maxMediaBytes: Int) {
    class Packet(val to: Address, val bytes: ByteArray, val queuedAt: Long)

    private val control = ArrayDeque<Packet>()
    private val media = ArrayDeque<Packet>()
    private var mediaBytes = 0

    var droppedMedia = 0
        private set

    val isEmpty get() = control.isEmpty() && media.isEmpty()

    val waiting get() = control.size + media.size

    fun addControl(packet: Packet) {
        control.addLast(packet)
    }

    fun addMedia(packet: Packet) {
        media.addLast(packet)
        mediaBytes += packet.bytes.size
        while (mediaBytes > maxMediaBytes) dropOldestMedia()
    }

    fun next(now: Long): Packet? {
        control.removeFirstOrNull()?.let { return it }
        while (true) {
            val packet = media.removeFirstOrNull() ?: return null
            mediaBytes -= packet.bytes.size
            if (now - packet.queuedAt <= staleNs) return packet
            droppedMedia++
        }
    }

    private fun dropOldestMedia() {
        val dropped = media.removeFirstOrNull() ?: return
        mediaBytes -= dropped.bytes.size
        droppedMedia++
    }
}

// A congested uplink fills the socket buffer and a send then waits, which on the network thread held up everything else.
internal class PacketSender(private val deliver: (Address, ByteArray) -> Unit, staleNs: Long = STALE_NS, maxMediaBytes: Int = MAX_MEDIA_BYTES) {
    private val queue = OutgoingQueue(staleNs, maxMediaBytes)
    private val lock = Object()
    private var reported = 0
    private var reportedAt = 0L
    private var packets = 0
    private var bytes = 0
    private var longestSendNs = 0L

    init {
        Thread(::loop, "manifold-send").apply { isDaemon = true }.start()
    }

    fun sendControl(to: Address, bytes: ByteArray) = add { queue.addControl(OutgoingQueue.Packet(to, bytes, System.nanoTime())) }

    fun sendMedia(to: Address, bytes: ByteArray) = add { queue.addMedia(OutgoingQueue.Packet(to, bytes, System.nanoTime())) }

    private inline fun add(enqueue: () -> Unit) {
        synchronized(lock) {
            enqueue()
            lock.notifyAll()
        }
    }

    private fun report(sent: Int, sentBytes: Int, sendNs: Long) {
        packets += sent
        bytes += sentBytes
        longestSendNs = maxOf(longestSendNs, sendNs)
        val now = System.nanoTime()
        if (now - reportedAt < REPORT_EVERY_NS) return
        val (dropped, waiting) = synchronized(lock) { queue.droppedMedia to queue.waiting }
        Log.i(STATS_TAG, "wire ${bytes * 8 / 1000} kbps $packets packets, longest send ${longestSendNs / 1_000_000} ms, ${dropped - reported} dropped, $waiting waiting")
        reported = dropped
        reportedAt = now
        packets = 0
        bytes = 0
        longestSendNs = 0
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        while (true) {
            val packet = synchronized(lock) {
                var next = queue.next(System.nanoTime())
                while (next == null) {
                    lock.wait()
                    next = queue.next(System.nanoTime())
                }
                next
            }
            val started = System.nanoTime()
            deliver(packet.to, packet.bytes)
            report(1, packet.bytes.size, System.nanoTime() - started)
        }
    }

    companion object {
        const val STALE_NS = 250_000_000L
        const val MAX_MEDIA_BYTES = 512 * 1024
        private const val REPORT_EVERY_NS = 1_000_000_000L
    }
}
