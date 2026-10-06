package dev.mkzk.manifold.hub.network.protocol

import dev.mkzk.manifold.Manifold
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom

/** No socket or clock of its own, so it can be tested without a network. Not thread-safe. */
internal class Endpoint(
    private val identity: Identity,
    private val devices: DeviceBook,
    private val clock: () -> Long,
    private val transmit: (Address, ByteArray) -> Unit,
    private val listener: Listener,
    private val newKeyPair: () -> KeyPair = Crypto::generateKeyPair,
    /** Stamps each hello. It has to keep growing across restarts and reboots, which [clock] does not. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    interface Listener {
        fun onPairingCode(address: Address, remoteName: String, code: String) {}

        fun onPaired(device: Device) {}

        fun onPairingFailed(reason: String) {}

        fun onLinkUp(device: Device, address: Address) {}

        fun onLinkDown(device: Device) {}

        fun onConnectFailed(device: Device) {}

        fun onFeeds(device: Device, feeds: List<FeedInfo>) {}

        fun onSubscribe(device: Device, request: Control.Subscribe) {}

        fun onSubscribeRefused(device: Device, refusal: Control.SubscribeRefused) {}

        fun onUnsubscribe(device: Device, streamId: Int) {}

        fun onKeyframeRequest(device: Device, streamId: Int) {}

        fun onNack(device: Device, nack: Control.Nack) {}

        fun onStreamReport(device: Device, report: Control.StreamReport) {}

        fun onSenderStats(device: Device, stats: Control.SenderStats) {}

        fun onVideo(device: Device, streamId: Int, fragment: ByteArray) {}

        /** [timestamp] counts 90 kHz ticks on the same clock as the picture's. */
        fun onAudio(device: Device, streamId: Int, timestamp: Int, frame: ByteArray) {}
    }

    private class Link(val publicKey: String, var address: Address, val session: Session, val index: Int) {
        lateinit var control: ControlChannel
        var heard = 0L
        var pinged = 0L
        var probed = 0L

        /** Negative until the first reply. */
        var rttMs = -1f
        val rttSamples = ArrayDeque<Float>()
    }

    private class Pairing(val initiator: Boolean, var address: Address, val handshake: NoiseHandshake, val localIndex: Int, val startedAt: Long) {
        var remoteIndex = 0
        var lastSent: ByteArray? = null
        var lastSentAt = 0L
        var resends = 0
        var link: Link? = null
        var remoteName = ""
        var code = ""
        var heardData = false
        var localConfirmed = false
        var remoteConfirmed = false
        val established get() = link != null
    }

    private class Dialing(val device: Device, val address: Address, val index: Int, var handshake: NoiseHandshake, var sentAt: Long, var attempts: Int)

    private val random = SecureRandom()
    private var pairingOpenUntil = 0L
    private var pairing: Pairing? = null
    private val dialing = HashMap<String, Dialing>()
    private val links = HashMap<String, Link>()
    private val linksByIndex = HashMap<Int, Link>()
    private val lastHello = HashMap<String, Long>()

    fun openPairing(durationMs: Long = PAIRING_WINDOW_MS) {
        pairingOpenUntil = clock() + durationMs
    }

    fun closePairing() {
        pairingOpenUntil = 0
    }

    fun pair(address: Address) {
        check(pairing == null) { "a pairing is already in progress" }
        val handshake = NoiseHandshake(Pattern.XX, true, identity.keys, prologue = PROLOGUE, newEphemeral = newKeyPair)
        val attempt = Pairing(true, address, handshake, newIndex(), clock())
        pairing = attempt
        sendHandshake(attempt, Wire.Handshake(Wire.TYPE_PAIR, 0, attempt.localIndex, 0, handshake.writeMessage()))
    }

    fun confirmPairing(accept: Boolean) {
        val current = pairing ?: return
        val link = current.link ?: return
        if (accept) {
            current.localConfirmed = true
            link.control.send(Control.PairConfirm, clock())
            completePairingIfBoth(current)
        } else {
            link.control.send(Control.PairReject, clock())
            endPairing("declined")
        }
    }

    fun cancelPairing() {
        endPairing("cancelled")
    }

    fun connect(device: Device, address: Address) {
        if (links.containsKey(device.publicKey) || dialing.containsKey(device.publicKey)) return
        val key = device.publicKey.fromHex() ?: return
        val attempt = Dialing(device, address, newIndex(), helloHandshake(key), clock(), 1)
        dialing[device.publicKey] = attempt
        sendHello(attempt)
    }

    fun disconnect(publicKey: String) {
        dialing.remove(publicKey)
        val link = links[publicKey] ?: return
        link.control.send(Control.Bye, clock())
        drop(link, "closed from this device")
    }

    fun unpair(publicKey: String) {
        disconnect(publicKey)
        devices.remove(publicKey)
    }

    fun sendFeeds(publicKey: String, feeds: List<FeedInfo>) {
        links[publicKey]?.control?.send(Control.FeedList(feeds), clock())
    }

    fun subscribe(publicKey: String, request: Control.Subscribe) {
        links[publicKey]?.control?.send(request, clock())
    }

    fun refuseSubscribe(publicKey: String, streamId: Int, reason: Control.Refusal) {
        links[publicKey]?.control?.send(Control.SubscribeRefused(streamId, reason), clock())
    }

    fun unsubscribe(publicKey: String, streamId: Int) {
        links[publicKey]?.control?.send(Control.Unsubscribe(streamId), clock())
    }

    fun requestKeyframe(publicKey: String, streamId: Int) {
        links[publicKey]?.control?.send(Control.KeyframeRequest(streamId), clock())
    }

    /**
     * Video is not resent when lost on its own: a late frame is no use, so the receiver asks for the pieces
     * it still wants.
     */
    fun sendVideo(publicKey: String, streamId: Int, fragments: List<ByteArray>) {
        val link = links[publicKey] ?: return
        for (fragment in fragments) transmit(link.address, link.session.seal(Stream.Video, VideoPacket.encode(streamId, fragment)))
    }

    /** Like video, audio is not resent: a late frame would only be heard as a glitch. */
    fun sendAudio(publicKey: String, streamId: Int, timestamp: Int, frame: ByteArray) {
        val link = links[publicKey] ?: return
        transmit(link.address, link.session.seal(Stream.Audio, AudioPacket.encode(streamId, timestamp, frame)))
    }

    fun requestRetransmit(publicKey: String, nack: Control.Nack) {
        links[publicKey]?.control?.send(nack, clock())
    }

    fun sendStreamReport(publicKey: String, report: Control.StreamReport) {
        links[publicKey]?.control?.send(report, clock())
    }

    fun sendSenderStats(publicKey: String, stats: Control.SenderStats) {
        links[publicKey]?.control?.send(stats, clock())
    }

    fun rttMs(publicKey: String): Float? = links[publicKey]?.rttMs?.takeIf { it >= 0 }

    fun isLinked(publicKey: String) = links.containsKey(publicKey)

    fun isDialing(publicKey: String) = dialing.containsKey(publicKey)

    fun onDatagram(from: Address, datagram: ByteArray) {
        when (datagram.firstOrNull()) {
            Wire.TYPE_PAIR, Wire.TYPE_HELLO -> Wire.decodeHandshake(datagram)?.let { handshake ->
                if (handshake.type == Wire.TYPE_PAIR) onPair(from, handshake) else onHello(from, handshake)
            }
            Session.TYPE_DATA -> onData(from, datagram)
        }
    }

    private fun onPair(from: Address, packet: Wire.Handshake) {
        when (packet.step) {
            0 -> startedByOther(from, packet)
            1 -> answeredByOther(from, packet)
            2 -> finishedByOther(from, packet)
        }
    }

    private fun startedByOther(from: Address, packet: Wire.Handshake) {
        val current = pairing
        if (current != null) {
            // The same request again means our answer was lost.
            if (!current.initiator && !current.established && current.remoteIndex == packet.senderIndex) {
                current.lastSent?.let { transmit(from, it) }
            }
            return
        }
        if (clock() > pairingOpenUntil) return
        val handshake = NoiseHandshake(Pattern.XX, false, identity.keys, prologue = PROLOGUE, newEphemeral = newKeyPair)
        try {
            handshake.readMessage(packet.message)
        } catch (_: GeneralSecurityException) {
            return
        }
        val attempt = Pairing(false, from, handshake, newIndex(), clock())
        attempt.remoteIndex = packet.senderIndex
        pairing = attempt
        val reply = handshake.writeMessage(nameBytes(identity.name))
        sendHandshake(attempt, Wire.Handshake(Wire.TYPE_PAIR, 1, attempt.localIndex, attempt.remoteIndex, reply))
    }

    private fun answeredByOther(from: Address, packet: Wire.Handshake) {
        val current = pairing ?: return
        if (!current.initiator || current.established || packet.receiverIndex != current.localIndex) return
        val payload = try {
            current.handshake.readMessage(packet.message)
        } catch (_: GeneralSecurityException) {
            return
        }
        current.address = from
        current.remoteIndex = packet.senderIndex
        current.remoteName = readName(payload)
        val third = current.handshake.writeMessage(nameBytes(identity.name))
        sendHandshake(current, Wire.Handshake(Wire.TYPE_PAIR, 2, current.localIndex, current.remoteIndex, third))
        establishPairing(current)
    }

    private fun finishedByOther(from: Address, packet: Wire.Handshake) {
        val current = pairing ?: return
        if (current.initiator || current.established || packet.receiverIndex != current.localIndex || packet.senderIndex != current.remoteIndex) return
        val payload = try {
            current.handshake.readMessage(packet.message)
        } catch (_: GeneralSecurityException) {
            return
        }
        current.address = from
        current.remoteName = readName(payload)
        establishPairing(current)
    }

    private fun establishPairing(current: Pairing) {
        val key = current.handshake.remoteStaticKey ?: return endPairing("the other device sent no key")
        val session = Session(current.localIndex, current.remoteIndex, current.handshake.split())
        current.link = newLink(key.toHex(), current.address, session, current.localIndex)
        current.code = pairingCode(current.handshake.handshakeHash)
        linksByIndex[current.localIndex] = current.link!!
        listener.onPairingCode(current.address, current.remoteName, current.code)
    }

    private fun onHello(from: Address, packet: Wire.Handshake) {
        when (packet.step) {
            0 -> helloReceived(from, packet)
            1 -> helloAnswered(from, packet)
        }
    }

    // Unknown keys and replays get no answer at all.
    private fun helloReceived(from: Address, packet: Wire.Handshake) {
        val handshake = NoiseHandshake(Pattern.IK, false, identity.keys, prologue = PROLOGUE, newEphemeral = newKeyPair)
        val payload = try {
            handshake.readMessage(packet.message)
        } catch (_: GeneralSecurityException) {
            return
        }
        val remote = handshake.remoteStaticKey ?: return
        val device = devices.find(remote) ?: return
        if (payload.size < 8) return
        // Both devices dialing at once would leave each holding a different session. The device
        // with the smaller key keeps its own attempt; the other drops its own and answers.
        if (dialing.containsKey(device.publicKey)) {
            if (identity.keys.public.toHex() < device.publicKey) return
            dialing.remove(device.publicKey)
        }
        // Each hello carries a time that must be newer than the last, so a recorded one is useless.
        val sentAt = ByteBuffer.wrap(payload).getLong()
        if (sentAt <= (lastHello[device.publicKey] ?: Long.MIN_VALUE)) return
        lastHello[device.publicKey] = sentAt

        val reply = handshake.writeMessage()
        val index = newIndex()
        val session = Session(index, packet.senderIndex, handshake.split())
        transmit(from, Wire.encode(Wire.Handshake(Wire.TYPE_HELLO, 1, index, packet.senderIndex, reply)))
        bringUp(device.publicKey, from, session, index)
    }

    private fun helloAnswered(from: Address, packet: Wire.Handshake) {
        val attempt = dialing.values.firstOrNull { it.index == packet.receiverIndex } ?: return
        try {
            attempt.handshake.readMessage(packet.message)
        } catch (_: GeneralSecurityException) {
            return
        }
        dialing.remove(attempt.device.publicKey)
        val session = Session(attempt.index, packet.senderIndex, attempt.handshake.split())
        bringUp(attempt.device.publicKey, from, session, attempt.index)
    }

    private fun bringUp(publicKey: String, address: Address, session: Session, index: Int) {
        // A peer that restarts dials in again before the old link has timed out. Whatever was
        // running over the old link has to hear that it ended, because the peer forgot it.
        links[publicKey]?.let { drop(it, "the other device dialed in again") }
        val link = newLink(publicKey, address, session, index)
        link.control.giveUpAfter = ESTABLISHED_ATTEMPTS
        links[publicKey] = link
        linksByIndex[index] = link
        devices.find(publicKey)?.let { listener.onLinkUp(it, address) }
    }

    private fun onData(from: Address, datagram: ByteArray) {
        val index = Wire.dataReceiver(datagram) ?: return
        val link = linksByIndex[index] ?: return
        val opened = link.session.open(datagram) ?: return
        link.heard = clock()
        link.address = from
        pairing?.takeIf { it.link === link }?.heardData = true
        when (opened.stream) {
            Stream.Control -> link.control.receive(opened.payload)
            Stream.Video -> onVideoPacket(link, opened.payload)
            Stream.Audio -> onAudioPacket(link, opened.payload)
        }
    }

    private fun onVideoPacket(link: Link, payload: ByteArray) {
        if (links[link.publicKey] !== link) return
        val device = devices.find(link.publicKey) ?: return
        val streamId = VideoPacket.streamOf(payload) ?: return
        listener.onVideo(device, streamId, VideoPacket.fragmentOf(payload))
    }

    private fun onAudioPacket(link: Link, payload: ByteArray) {
        if (links[link.publicKey] !== link) return
        val device = devices.find(link.publicKey) ?: return
        val packet = AudioPacket.decode(payload) ?: return
        listener.onAudio(device, packet.streamId, packet.timestamp, packet.frame)
    }

    private fun handleControl(link: Link, control: Control) {
        val current = pairing?.takeIf { it.link === link }
        if (current != null) {
            when (control) {
                Control.PairConfirm -> {
                    current.remoteConfirmed = true
                    completePairingIfBoth(current)
                }
                Control.PairReject -> endPairing("the other device declined")
                else -> Unit
            }
            return
        }
        val device = devices.find(link.publicKey) ?: return
        when (control) {
            is Control.FeedList -> listener.onFeeds(device, control.feeds)
            is Control.Subscribe -> listener.onSubscribe(device, control)
            is Control.SubscribeRefused -> listener.onSubscribeRefused(device, control)
            is Control.Unsubscribe -> listener.onUnsubscribe(device, control.streamId)
            is Control.KeyframeRequest -> listener.onKeyframeRequest(device, control.streamId)
            is Control.TimeRequest -> link.control.send(Control.TimeReply(control.sentAt), clock())
            is Control.TimeReply -> measureRtt(link, control.sentAt)
            is Control.SenderStats -> listener.onSenderStats(device, control)
            is Control.Nack -> listener.onNack(device, control)
            is Control.StreamReport -> listener.onStreamReport(device, control)
            Control.Bye -> drop(link, "the other device said goodbye")
            else -> Unit
        }
    }

    fun tick() {
        val now = clock()
        pairing?.let { tickPairing(it, now) }
        for (attempt in dialing.values.toList()) tickDialing(attempt, now)
        for (link in links.values.toList()) tickLink(link, now)
    }

    private fun tickPairing(current: Pairing, now: Long) {
        val link = current.link
        if (link == null) {
            if (now - current.startedAt > HANDSHAKE_TIMEOUT_MS) return endPairing("no answer")
            resendHandshake(current, now)
            return
        }
        if (now - current.startedAt > CONFIRM_TIMEOUT_MS) return endPairing("timed out")
        // The third message may have been lost, and the other side cannot continue without it.
        if (current.initiator && !current.heardData) resendHandshake(current, now)
        link.control.tick(now)
    }

    private fun resendHandshake(current: Pairing, now: Long) {
        val last = current.lastSent ?: return
        if (now - current.lastSentAt < HANDSHAKE_RESEND_MS || current.resends >= MAX_HANDSHAKE_RESENDS) return
        current.resends++
        current.lastSentAt = now
        transmit(current.address, last)
    }

    private fun tickDialing(attempt: Dialing, now: Long) {
        if (now - attempt.sentAt < HANDSHAKE_RESEND_MS) return
        if (attempt.attempts >= MAX_HANDSHAKE_RESENDS) {
            dialing.remove(attempt.device.publicKey)
            listener.onConnectFailed(attempt.device)
            return
        }
        // A fresh handshake each time: the other side refuses a hello it has already seen.
        attempt.handshake = helloHandshake(attempt.device.publicKey.fromHex() ?: return)
        attempt.attempts++
        attempt.sentAt = now
        sendHello(attempt)
    }

    private fun measureRtt(link: Link, sentAt: Long) {
        link.rttSamples.addLast((clock() - sentAt).coerceAtLeast(0).toFloat())
        if (link.rttSamples.size > RTT_SAMPLES) link.rttSamples.removeFirst()
        // The first reply after a quiet spell can be slow while the radio wakes. A median does not carry
        // that along.
        link.rttMs = link.rttSamples.sorted()[link.rttSamples.size / 2]
    }

    private fun tickLink(link: Link, now: Long) {
        link.control.tick(now)
        if (!links.containsValue(link)) return
        if (now - link.heard > LINK_TIMEOUT_MS) return drop(link, "nothing heard for ${(now - link.heard) / 1000.0} s")
        if (now - link.probed >= PROBE_INTERVAL_MS) {
            link.probed = now
            link.control.send(Control.TimeRequest(now), now)
        }
        if (now - link.pinged >= PING_INTERVAL_MS) {
            link.pinged = now
            link.control.send(Control.Ping, now)
        }
    }

    private fun completePairingIfBoth(current: Pairing) {
        if (!current.localConfirmed || !current.remoteConfirmed) return
        val link = current.link ?: return
        // Pairing again keeps what the owner had switched on for this device.
        val device = (devices.find(link.publicKey) ?: Device(link.publicKey, current.remoteName, null))
            .copy(name = current.remoteName, address = current.address.toString())
        devices.put(device)
        pairing = null
        // One pairing is what the owner opened the window for.
        pairingOpenUntil = 0
        links[link.publicKey]?.let { drop(it, "replaced by a new pairing") }
        link.control.giveUpAfter = ESTABLISHED_ATTEMPTS
        links[link.publicKey] = link
        listener.onPaired(devices.find(link.publicKey) ?: device)
        listener.onLinkUp(devices.find(link.publicKey) ?: device, current.address)
    }

    private fun endPairing(reason: String) {
        val current = pairing ?: return
        pairing = null
        current.link?.let { linksByIndex.remove(it.index) }
        listener.onPairingFailed(reason)
    }

    private fun drop(link: Link, reason: String) {
        val known = links[link.publicKey] === link
        if (known) links.remove(link.publicKey)
        linksByIndex.remove(link.index)
        if (!known) return
        val device = devices.find(link.publicKey) ?: Device(link.publicKey, "", null)
        log("link to ${device.name.ifEmpty { link.publicKey.take(8) }} down: $reason")
        listener.onLinkDown(device)
    }

    private fun newLink(publicKey: String, address: Address, session: Session, index: Int): Link {
        val link = Link(publicKey, address, session, index)
        link.control = ControlChannel(
            transmit = { payload -> transmit(link.address, session.seal(Stream.Control, payload)) },
            onMessage = { handleControl(link, it) },
            onFailed = { if (pairing?.link === link) endPairing("lost contact") else drop(link, "a message got no answer after ${link.control.giveUpAfter} tries") },
        )
        link.heard = clock()
        link.pinged = link.heard
        return link
    }

    private fun sendHandshake(current: Pairing, packet: Wire.Handshake) {
        val bytes = Wire.encode(packet)
        current.lastSent = bytes
        current.lastSentAt = clock()
        current.resends = 0
        transmit(current.address, bytes)
    }

    private fun helloHandshake(remoteKey: ByteArray) =
        NoiseHandshake(Pattern.IK, true, identity.keys, remoteStatic = remoteKey, prologue = PROLOGUE, newEphemeral = newKeyPair)

    private fun sendHello(attempt: Dialing) {
        val payload = ByteBuffer.allocate(8).putLong(wallClock()).array() + nameBytes(identity.name)
        val message = attempt.handshake.writeMessage(payload)
        transmit(attempt.address, Wire.encode(Wire.Handshake(Wire.TYPE_HELLO, 0, attempt.index, 0, message)))
    }

    private fun newIndex(): Int {
        while (true) {
            val candidate = random.nextInt()
            val free = !linksByIndex.containsKey(candidate) && pairing?.localIndex != candidate && dialing.values.none { it.index == candidate }
            if (candidate != 0 && free) return candidate
        }
    }

    private fun pairingCode(handshakeHash: ByteArray): String {
        val number = ByteBuffer.wrap(handshakeHash, 0, 4).int.toLong() and 0xFFFFFFFFL
        return "%06d".format(number % 1_000_000)
    }

    private fun nameBytes(name: String) = name.take(Manifold.MAX_NAME_LENGTH).toByteArray(Charsets.UTF_8)

    private fun readName(bytes: ByteArray): String {
        val name = bytes.toString(Charsets.UTF_8).replace('\t', ' ').replace('\n', ' ').trim().take(Manifold.MAX_NAME_LENGTH)
        return name.ifEmpty { "Unknown device" }
    }

    companion object {
        const val PAIRING_WINDOW_MS = 120_000L
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val HANDSHAKE_RESEND_MS = 1_000L
        const val MAX_HANDSHAKE_RESENDS = 5
        const val CONFIRM_TIMEOUT_MS = 120_000L
        const val PING_INTERVAL_MS = 3_000L
        const val PROBE_INTERVAL_MS = 1_000L
        private const val RTT_SAMPLES = 9
        const val LINK_TIMEOUT_MS = 15_000L

        // Resent until the link would time out anyway.
        private const val ESTABLISHED_ATTEMPTS = (LINK_TIMEOUT_MS / ControlChannel.RESEND_AFTER_MS).toInt()

        private val PROLOGUE = "manifold-net/1".toByteArray(Charsets.US_ASCII)
    }
}
