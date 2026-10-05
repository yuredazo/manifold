package dev.mkzk.manifold.hub

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import dev.mkzk.manifold.hub.net.Address
import dev.mkzk.manifold.hub.net.Control
import dev.mkzk.manifold.hub.net.Device
import dev.mkzk.manifold.hub.net.DeviceBook
import dev.mkzk.manifold.hub.net.Endpoint
import dev.mkzk.manifold.hub.net.FeedInfo
import dev.mkzk.manifold.hub.net.Identity
import dev.mkzk.manifold.hub.net.StreamSnapshot
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "Network"
private const val LISTEN_PORT = 47200
private const val TICK_MS = 250L
private const val CONNECT_EVERY_TICKS = 20
private const val POLL_MS = 4L

internal sealed interface PairingUi {
    data object None : PairingUi

    data class Connecting(val address: String) : PairingUi

    /** Set once the owner said the codes match and the other device has yet to answer. */
    data class Code(val remoteName: String, val code: String, val confirmed: Boolean = false) : PairingUi

    data class Failed(val reason: String) : PairingUi
}

internal data class NetworkState(
    val listening: Boolean = false,
    val port: Int = LISTEN_PORT,
    val addresses: List<String> = emptyList(),
    /** Time on the [SystemClock.elapsedRealtime] clock until which other devices may start pairing. */
    val pairingOpenUntil: Long = 0,
    val pairing: PairingUi = PairingUi.None,
    val online: Set<String> = emptySet(),
    val remoteFeeds: Map<String, List<FeedInfo>> = emptyMap(),
    val streams: List<StreamSnapshot> = emptyList(),
    /** Feeds another device would not send, by device key and then feed name, until its feed list changes. */
    val refused: Map<String, Map<String, Control.Refusal>> = emptyMap(),
)

/** The network thread is the only one that touches the [DeviceBook] and the [Endpoint]. */
internal class Network(val identity: Identity, private val book: DeviceBook) {

    private val worker = HandlerThread("manifold-net").apply { start() }
    private val handler = Handler(worker.looper)
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())
    private val resolver = Executors.newSingleThreadExecutor()
    private val publisher: NetworkPublisher by lazy { NetworkPublisher(endpoint, book, ::onNetworkThread) }
    private val remote: RemoteFeeds by lazy {
        RemoteFeeds(
            endpoint,
            ::onNetworkThread,
            onStats = { snapshots -> flow.update { it.copy(streams = snapshots) } },
            onRefused = { deviceKey, feed, reason ->
                flow.update { it.copy(refused = it.refused + (deviceKey to (it.refused[deviceKey].orEmpty() + (feed to reason)))) }
            },
        )
    }
    private val resolved = ConcurrentHashMap<String, InetAddress>()

    private val flow = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = flow
    val devices: StateFlow<List<Device>> = book.devices

    @Volatile private var socket: DatagramSocket? = null
    private var ticks = 0
    private var polling = false

    private val endpoint: Endpoint = Endpoint(identity, book, SystemClock::elapsedRealtime, ::send, object : Endpoint.Listener {
        override fun onPairingCode(address: Address, remoteName: String, code: String) {
            flow.update { it.copy(pairing = PairingUi.Code(remoteName, code)) }
        }

        override fun onPaired(device: Device) {
            flow.update { it.copy(pairing = PairingUi.None, pairingOpenUntil = 0) }
        }

        override fun onPairingFailed(reason: String) {
            flow.update { it.copy(pairing = PairingUi.Failed(reason)) }
        }

        override fun onLinkUp(device: Device, address: Address) {
            flow.update { it.copy(online = it.online + device.publicKey) }
            book.update(device.publicKey) { it.copy(address = address.toString()) }
            offerFeeds(device)
        }

        override fun onLinkDown(device: Device) {
            flow.update {
                it.copy(online = it.online - device.publicKey, remoteFeeds = it.remoteFeeds - device.publicKey, refused = it.refused - device.publicKey)
            }
            publisher.stopDevice(device.publicKey)
            remote.clear(device.publicKey)
        }

        override fun onFeeds(device: Device, feeds: List<FeedInfo>) {
            flow.update { it.copy(remoteFeeds = it.remoteFeeds + (device.publicKey to feeds), refused = it.refused - device.publicKey) }
            syncRemote(device.publicKey)
        }

        override fun onSubscribe(device: Device, request: Control.Subscribe) {
            publisher.onSubscribe(device, request)
        }

        override fun onSubscribeRefused(device: Device, refusal: Control.SubscribeRefused) {
            remote.onRefused(device, refusal)
        }

        override fun onUnsubscribe(device: Device, streamId: Int) {
            publisher.onUnsubscribe(device, streamId)
        }

        override fun onKeyframeRequest(device: Device, streamId: Int) {
            publisher.onKeyframeRequest(device, streamId)
        }

        override fun onStreamReport(device: Device, report: Control.StreamReport) {
            publisher.onStreamReport(device, report)
        }

        override fun onNack(device: Device, nack: Control.Nack) {
            publisher.onNack(device, nack)
        }

        override fun onSenderStats(device: Device, stats: Control.SenderStats) {
            remote.onSenderStats(device, stats)
        }

        override fun onAudio(device: Device, streamId: Int, timestamp: Int, frame: ByteArray) {
            remote.onAudio(device, streamId, timestamp, frame)
        }

        override fun onVideo(device: Device, streamId: Int, fragment: ByteArray) {
            remote.onVideo(device, streamId, fragment)
        }
    })

    private val tick = object : Runnable {
        override fun run() {
            if (socket == null) return
            endpoint.tick()
            publisher.tick()
            remote.tick(SystemClock.elapsedRealtime())
            if (!polling && remote.hasStreams()) {
                polling = true
                handler.post(poll)
            }
            if (ticks++ % CONNECT_EVERY_TICKS == 0) connectKnownDevices()
            handler.postDelayed(this, TICK_MS)
        }
    }

    // A quarter second tick is too coarse to wait a couple of frames for a missing piece.
    private val poll = object : Runnable {
        override fun run() {
            remote.poll(System.nanoTime())
            if (socket != null && remote.hasStreams()) handler.postDelayed(this, POLL_MS) else polling = false
        }
    }

    init {
        val feeds = Registry.instance.state.map { it.senders }.distinctUntilChanged()
        val choices = book.devices.map { list -> list.map { Triple(it.publicKey, it.send, it.receive) } }.distinctUntilChanged()
        scope.launch {
            combine(feeds, choices) { _, _ -> }.collect {
                offerToAll()
                publisher.enforce()
                book.devices.value.forEach { syncRemote(it.publicKey) }
            }
        }
    }

    fun start() {
        handler.post {
            if (socket != null) return@post
            val opened = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(LISTEN_PORT))
                }
            } catch (e: IOException) {
                Log.e(TAG, "cannot listen on port $LISTEN_PORT", e)
                return@post
            }
            socket = opened
            Thread({ receiveLoop(opened) }, "manifold-net-receive").apply { isDaemon = true }.start()
            flow.update { it.copy(listening = true, port = LISTEN_PORT, addresses = localAddresses()) }
            ticks = 0
            handler.post(tick)
        }
    }

    fun stop() {
        handler.post {
            val open = socket ?: return@post
            publisher.stopAll()
            remote.stopAll()
            book.devices.value.forEach { endpoint.disconnect(it.publicKey) }
            open.close()
            socket = null
            handler.removeCallbacks(tick)
            flow.value = NetworkState()
        }
    }

    fun openPairing() {
        start()
        handler.post {
            endpoint.openPairing()
            flow.update { it.copy(pairingOpenUntil = SystemClock.elapsedRealtime() + Endpoint.PAIRING_WINDOW_MS) }
        }
    }

    fun closePairing() {
        handler.post {
            endpoint.closePairing()
            flow.update { it.copy(pairingOpenUntil = 0) }
        }
    }

    fun pair(text: String) {
        val address = Address.parse(text, LISTEN_PORT)
        if (address == null) {
            flow.update { it.copy(pairing = PairingUi.Failed("that is not a usable address")) }
            return
        }
        start()
        flow.update { it.copy(pairing = PairingUi.Connecting(address.toString())) }
        // Looking up a name can take seconds, so it stays off the network thread.
        resolver.execute {
            val found = try {
                InetAddress.getByName(address.host)
            } catch (_: UnknownHostException) {
                null
            }
            handler.post {
                if (found == null) {
                    flow.update { it.copy(pairing = PairingUi.Failed("could not find ${address.host}")) }
                    return@post
                }
                resolved[address.host] = found
                try {
                    endpoint.pair(address)
                } catch (_: IllegalStateException) {
                    // A pairing is already in progress; its dialog is still showing.
                }
            }
        }
    }

    fun confirmPairing(accept: Boolean) {
        handler.post {
            endpoint.confirmPairing(accept)
            flow.update {
                val shown = it.pairing
                when {
                    !accept -> it.copy(pairing = PairingUi.None)
                    shown is PairingUi.Code -> it.copy(pairing = shown.copy(confirmed = true))
                    else -> it
                }
            }
        }
    }

    fun cancelPairing() {
        handler.post {
            endpoint.cancelPairing()
            flow.update { it.copy(pairing = PairingUi.None) }
        }
    }

    fun dismissPairing() {
        flow.update { it.copy(pairing = PairingUi.None) }
    }

    fun setReceive(publicKey: String, on: Boolean) {
        handler.post { book.update(publicKey) { it.copy(receive = on) } }
    }

    fun setSend(publicKey: String, on: Boolean) {
        handler.post { book.update(publicKey) { it.copy(send = on) } }
    }

    fun unpair(publicKey: String) {
        handler.post {
            endpoint.unpair(publicKey)
            flow.update { it.copy(online = it.online - publicKey, remoteFeeds = it.remoteFeeds - publicKey) }
        }
    }

    private fun connectKnownDevices() {
        for (device in book.devices.value) {
            val address = device.address?.let { Address.parse(it, LISTEN_PORT) } ?: continue
            if (endpoint.isLinked(device.publicKey) || endpoint.isDialing(device.publicKey)) continue
            endpoint.connect(device, address)
        }
    }

    private fun syncRemote(publicKey: String) {
        val device = book.find(publicKey)
        val feeds = flow.value.remoteFeeds[publicKey]
        if (device != null && device.receive && feeds != null && publicKey in flow.value.online) {
            remote.update(device, feeds)
        } else {
            remote.clear(publicKey)
        }
    }

    private fun onNetworkThread(block: () -> Unit) {
        handler.post { block() }
    }

    private fun offerToAll() {
        book.devices.value.forEach(::offerFeeds)
    }

    private fun offerFeeds(device: Device) {
        val current = book.find(device.publicKey) ?: return
        val feeds = if (current.send) {
            Registry.instance.state.value.senders
                .filter { !it.packageName.startsWith(REMOTE_PREFIX) }
                .map { FeedInfo(it.name, it.width, it.height, it.fps, it.hasAudio) }
        } else {
            emptyList()
        }
        endpoint.sendFeeds(current.publicKey, feeds)
    }

    private fun send(to: Address, bytes: ByteArray) {
        val open = socket ?: return
        val ip = resolved[to.host] ?: try {
            // Addresses seen on incoming packets are literal, so this does not reach a name server.
            InetAddress.getByName(to.host).also { resolved[to.host] = it }
        } catch (_: UnknownHostException) {
            return
        }
        try {
            open.send(DatagramPacket(bytes, bytes.size, ip, to.port))
        } catch (e: IOException) {
            Log.w(TAG, "send to $to failed: ${e.message}")
        }
    }

    private fun receiveLoop(open: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (!open.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                open.receive(packet)
            } catch (_: IOException) {
                break
            }
            val host = packet.address?.hostAddress ?: continue
            val datagram = packet.data.copyOfRange(0, packet.length)
            val from = Address(host, packet.port)
            handler.post { endpoint.onDatagram(from, datagram) }
        }
    }

    private fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .map { "$it:$LISTEN_PORT" }
    } catch (_: IOException) {
        emptyList()
    }

    companion object {
        lateinit var instance: Network
            private set

        fun install(identity: Identity, book: DeviceBook) {
            instance = Network(identity, book)
        }
    }
}
