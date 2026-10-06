package dev.mkzk.manifold.hub.network

import android.os.SystemClock
import android.util.Log
import dev.mkzk.manifold.hub.network.protocol.Beacon
import dev.mkzk.manifold.hub.network.protocol.BeaconCodec
import dev.mkzk.manifold.hub.network.protocol.Identity
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "Discovery"
private const val LIMITED_BROADCAST = "255.255.255.255"

internal class NearbyDevice(val id: String, val name: String, val host: String, val port: Int)

/** Devices heard recently. A device that stops announcing drops out after [ttlMs]. */
internal class NearbyBook(private val ttlMs: Long = 4_000, private val limit: Int = 16) {
    private class Heard(val device: NearbyDevice, val at: Long)

    private val heard = LinkedHashMap<String, Heard>()

    fun hear(beacon: Beacon, host: String, now: Long) {
        prune(now)
        if (heard.size >= limit && beacon.id !in heard) return
        heard[beacon.id] = Heard(NearbyDevice(beacon.id, beacon.name, host, beacon.port), now)
    }

    fun current(now: Long): List<NearbyDevice> {
        prune(now)
        return heard.values.map { it.device }.sortedBy { it.name.lowercase() }
    }

    fun clear() = heard.clear()

    private fun prune(now: Long) {
        heard.values.removeAll { now - it.at > ttlMs }
    }
}

/**
 * Finds devices on the same network that are open for pairing. It listens only while the pairing sheet is open and
 * announces only while this device lets others pair, so an idle hub says nothing.
 */
internal class Discovery(private val identity: Identity, private val hold: () -> Closeable? = { null }) {
    private val ownId = identity.fingerprint.take(16)
    private val book = NearbyBook()
    private val flow = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val nearby: StateFlow<List<NearbyDevice>> = flow

    private var socket: DatagramSocket? = null
    private var multicastLock: Closeable? = null

    @Synchronized
    fun listen() {
        if (socket != null) return
        val opened = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1_000
                bind(InetSocketAddress(BeaconCodec.PORT))
            }
        } catch (e: IOException) {
            Log.w(TAG, "cannot listen on port ${BeaconCodec.PORT}: ${e.message}")
            return
        }
        socket = opened
        multicastLock = hold()
        Thread({ receiveLoop(opened) }, "manifold-discovery").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        socket?.close()
        socket = null
        multicastLock?.close()
        multicastLock = null
        synchronized(book) { book.clear() }
        flow.value = emptyList()
    }

    /** One beacon out of each private network this device is on, so it leaves through the right interface. */
    fun announce(listenPort: Int) {
        val packet = BeaconCodec.encode(Beacon(ownId, listenPort, identity.name))
        for (local in privateAddresses()) {
            try {
                DatagramSocket(InetSocketAddress(local, 0)).use { out ->
                    out.broadcast = true
                    out.send(DatagramPacket(packet, packet.size, InetAddress.getByName(LIMITED_BROADCAST), BeaconCodec.PORT))
                }
            } catch (e: IOException) {
                Log.w(TAG, "cannot announce from ${local.hostAddress}: ${e.message}")
            }
        }
    }

    private fun receiveLoop(open: DatagramSocket) {
        val buffer = ByteArray(256)
        while (!open.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                open.receive(packet)
            } catch (_: SocketTimeoutException) {
                publish()
                continue
            } catch (_: IOException) {
                break
            }
            val beacon = BeaconCodec.decode(packet.data, packet.length)
            val host = packet.address?.hostAddress
            if (beacon != null && host != null && beacon.id != ownId) {
                synchronized(book) { book.hear(beacon, host, SystemClock.elapsedRealtime()) }
                publish()
            }
        }
    }

    private fun publish() {
        if (socket == null) return
        flow.value = synchronized(book) { book.current(SystemClock.elapsedRealtime()) }
    }

    /** Private ranges only: a mobile carrier or a tunnel cannot carry a broadcast, and nothing should be announced there. */
    private fun privateAddresses(): List<Inet4Address> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
    } catch (_: IOException) {
        emptyList()
    }
}
