package dev.mkzk.manifold.hub.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointTest {

    private class World {
        var now = 1_000_000L
        val nodes = HashMap<Address, Node>()
        val log = ArrayList<Triple<Address, Address, ByteArray>>()
        var dropIf: (Int, ByteArray) -> Boolean = { _, _ -> false }
        var cut = false
        private val queue = ArrayDeque<Triple<Address, Address, ByteArray>>()
        private var sent = 0

        fun enqueue(from: Address, to: Address, bytes: ByteArray) {
            log += Triple(from, to, bytes)
            queue.addLast(Triple(from, to, bytes))
        }

        fun pump() {
            while (queue.isNotEmpty()) {
                val (from, to, bytes) = queue.removeFirst()
                if (cut || dropIf(sent++, bytes)) continue
                nodes[to]?.endpoint?.onDatagram(from, bytes)
            }
        }

        fun advance(ms: Long) {
            var left = ms
            while (left > 0) {
                now += 100
                left -= 100
                nodes.values.forEach { it.endpoint.tick() }
                pump()
            }
        }
    }

    private class Node(val world: World, val name: String, val address: Address) {
        val identity = Identity(Crypto.generateKeyPair(), name)
        var stored: String? = null
        var book = DeviceBook(stored) { stored = it }
        val codes = ArrayList<String>()
        val paired = ArrayList<Device>()
        val failures = ArrayList<String>()
        val linksUp = ArrayList<Device>()
        val linksDown = ArrayList<Device>()
        val connectFailed = ArrayList<Device>()
        val feeds = ArrayList<List<FeedInfo>>()
        val subscribes = ArrayList<Control.Subscribe>()
        val unsubscribes = ArrayList<Int>()
        val keyframeRequests = ArrayList<Int>()
        val senderStats = ArrayList<Control.SenderStats>()
        val nacks = ArrayList<Control.Nack>()
        val video = ArrayList<kotlin.Pair<Int, ByteArray>>()
        val audio = ArrayList<AudioPacket>()
        lateinit var endpoint: Endpoint

        init {
            start()
            world.nodes[address] = this
        }

        fun start() {
            book = DeviceBook(stored) { stored = it }
            endpoint = Endpoint(identity, book, { world.now }, { to, bytes -> world.enqueue(address, to, bytes) }, object : Endpoint.Listener {
                override fun onPairingCode(address: Address, remoteName: String, code: String) {
                    codes += code
                }

                override fun onPaired(device: Device) {
                    paired += device
                }

                override fun onPairingFailed(reason: String) {
                    failures += reason
                }

                override fun onLinkUp(device: Device, address: Address) {
                    linksUp += device
                }

                override fun onLinkDown(device: Device) {
                    linksDown += device
                }

                override fun onConnectFailed(device: Device) {
                    connectFailed += device
                }

                override fun onFeeds(device: Device, feeds: List<FeedInfo>) {
                    this@Node.feeds += feeds
                }

                override fun onSubscribe(device: Device, request: Control.Subscribe) {
                    subscribes += request
                }

                override fun onUnsubscribe(device: Device, streamId: Int) {
                    unsubscribes += streamId
                }

                override fun onKeyframeRequest(device: Device, streamId: Int) {
                    keyframeRequests += streamId
                }

                override fun onNack(device: Device, nack: Control.Nack) {
                    nacks += nack
                }

                override fun onSenderStats(device: Device, stats: Control.SenderStats) {
                    senderStats += stats
                }

                override fun onVideo(device: Device, streamId: Int, fragment: ByteArray) {
                    video += streamId to fragment
                }

                override fun onAudio(device: Device, streamId: Int, timestamp: Int, frame: ByteArray) {
                    audio += AudioPacket(streamId, timestamp, frame)
                }
            }, wallClock = { world.now })
        }

        val publicKey get() = identity.keys.public.toHex()
    }

    private fun pairedWorld(): Triple<World, Node, Node> {
        val world = World()
        val alpha = Node(world, "Alpha", Address("10.0.0.1", 4000))
        val beta = Node(world, "Beta", Address("10.0.0.2", 4000))
        beta.endpoint.openPairing()
        alpha.endpoint.pair(beta.address)
        world.pump()
        alpha.endpoint.confirmPairing(true)
        beta.endpoint.confirmPairing(true)
        world.pump()
        return Triple(world, alpha, beta)
    }

    @Test
    fun twoOwnersPairByComparingTheSameCode() {
        val world = World()
        val alpha = Node(world, "Alpha", Address("10.0.0.1", 4000))
        val beta = Node(world, "Beta", Address("10.0.0.2", 4000))
        beta.endpoint.openPairing()

        alpha.endpoint.pair(beta.address)
        world.pump()

        assertEquals(1, alpha.codes.size)
        assertEquals(alpha.codes, beta.codes)
        assertTrue(alpha.codes.single().matches(Regex("\\d{6}")))
        assertTrue("nothing is saved before both confirm", alpha.paired.isEmpty() && beta.paired.isEmpty())

        alpha.endpoint.confirmPairing(true)
        world.pump()
        assertTrue("one confirmation is not enough", alpha.paired.isEmpty() && beta.paired.isEmpty())

        beta.endpoint.confirmPairing(true)
        world.pump()

        assertEquals("Beta", alpha.paired.single().name)
        assertEquals("Alpha", beta.paired.single().name)
        assertEquals(beta.identity.fingerprint, alpha.paired.single().fingerprint)
        assertEquals(alpha.identity.fingerprint, beta.paired.single().fingerprint)
        assertNotNull(alpha.book.find(beta.identity.keys.public))
        assertTrue(alpha.endpoint.isLinked(beta.publicKey) && beta.endpoint.isLinked(alpha.publicKey))
    }

    @Test
    fun aNewlyPairedDeviceHasNothingSwitchedOn() {
        val (_, alpha, beta) = pairedWorld()

        val device = alpha.book.find(beta.identity.keys.public)!!

        assertFalse(device.receive)
        assertFalse(device.send)
    }

    @Test
    fun differentPairingsGiveDifferentCodes() {
        val first = pairedWorld().second.codes.single()
        val second = pairedWorld().second.codes.single()

        assertNotEquals(first, second)
    }

    @Test
    fun aDeviceThatDeclinesEndsThePairingForBoth() {
        val world = World()
        val alpha = Node(world, "Alpha", Address("10.0.0.1", 4000))
        val beta = Node(world, "Beta", Address("10.0.0.2", 4000))
        beta.endpoint.openPairing()
        alpha.endpoint.pair(beta.address)
        world.pump()

        beta.endpoint.confirmPairing(false)
        alpha.endpoint.confirmPairing(true)
        world.pump()

        assertEquals(listOf("declined"), beta.failures)
        assertEquals(1, alpha.failures.size)
        assertTrue(alpha.book.devices.value.isEmpty() && beta.book.devices.value.isEmpty())
    }

    @Test
    fun pairingIsIgnoredWhileTheOtherDeviceHasItClosed() {
        val world = World()
        val alpha = Node(world, "Alpha", Address("10.0.0.1", 4000))
        val beta = Node(world, "Beta", Address("10.0.0.2", 4000))

        alpha.endpoint.pair(beta.address)
        world.pump()
        world.advance(Endpoint.HANDSHAKE_TIMEOUT_MS + 1_000)

        assertEquals(listOf("no answer"), alpha.failures)
        assertTrue(beta.codes.isEmpty())
        assertEquals("only the resends of the first message went out", 1 + Endpoint.MAX_HANDSHAKE_RESENDS, world.log.size)
    }

    @Test
    fun theWindowForPairingCloses() {
        val world = World()
        val alpha = Node(world, "Alpha", Address("10.0.0.1", 4000))
        val beta = Node(world, "Beta", Address("10.0.0.2", 4000))
        beta.endpoint.openPairing(durationMs = 5_000)
        world.advance(6_000)

        alpha.endpoint.pair(beta.address)
        world.pump()

        assertTrue(beta.codes.isEmpty())
    }

    @Test
    fun aPairingNobodyConfirmsEndsAndSavesNothing() {
        val world = World()
        val alpha = Node(world, "Alpha", Address("10.0.0.1", 4000))
        val beta = Node(world, "Beta", Address("10.0.0.2", 4000))
        beta.endpoint.openPairing()
        alpha.endpoint.pair(beta.address)
        world.pump()
        alpha.endpoint.confirmPairing(true)

        world.advance(Endpoint.CONFIRM_TIMEOUT_MS + 1_000)

        assertEquals(listOf("timed out"), beta.failures)
        assertTrue(alpha.book.devices.value.isEmpty() && beta.book.devices.value.isEmpty())
    }

    @Test
    fun pairingSurvivesLostPackets() {
        val world = World()
        val alpha = Node(world, "Alpha", Address("10.0.0.1", 4000))
        val beta = Node(world, "Beta", Address("10.0.0.2", 4000))
        beta.endpoint.openPairing()
        val seen = HashSet<Int>()
        world.dropIf = { _, bytes -> bytes[0] != Session.TYPE_DATA && seen.add(bytes[0] * 31 + bytes[1]) }

        alpha.endpoint.pair(beta.address)
        world.advance(8_000)

        assertEquals(alpha.codes, beta.codes)
        assertEquals(1, alpha.codes.size)
    }

    @Test
    fun pairedDevicesReconnectAfterARestart() {
        val (world, alpha, beta) = pairedWorld()
        alpha.start()
        beta.start()
        assertFalse(alpha.endpoint.isLinked(beta.publicKey))

        alpha.endpoint.connect(alpha.book.find(beta.identity.keys.public)!!, beta.address)
        world.pump()

        assertTrue(alpha.endpoint.isLinked(beta.publicKey))
        assertTrue(beta.endpoint.isLinked(alpha.publicKey))
        assertEquals("Alpha", beta.linksUp.last().name)
    }

    @Test
    fun aPeerThatRestartsAndDialsInReplacesItsOldLinkAndTheOldOneIsReportedDown() {
        val (world, alpha, beta) = pairedWorld()
        assertEquals(1, alpha.linksUp.size)

        // Only beta restarts, so alpha still holds the link that beta has forgotten.
        beta.start()
        beta.endpoint.connect(beta.book.find(alpha.identity.keys.public)!!, alpha.address)
        world.pump()

        assertEquals(1, alpha.linksDown.size)
        assertEquals(2, alpha.linksUp.size)
        assertTrue(alpha.endpoint.isLinked(beta.publicKey) && beta.endpoint.isLinked(alpha.publicKey))
    }

    @Test
    fun pairingAgainWhileLinkedReportsTheOldLinkDown() {
        val (world, alpha, beta) = pairedWorld()

        beta.endpoint.openPairing()
        alpha.endpoint.pair(beta.address)
        world.pump()
        alpha.endpoint.confirmPairing(true)
        beta.endpoint.confirmPairing(true)
        world.pump()

        assertEquals(1, alpha.linksDown.size)
        assertEquals(1, beta.linksDown.size)
        assertEquals(2, alpha.linksUp.size)
    }

    @Test
    fun theWindowForPairingClosesOncePairingSucceeds() {
        val (world, _, beta) = pairedWorld()
        val stranger = Node(world, "Stranger", Address("10.0.0.9", 4000))
        val codesBefore = beta.codes.size

        stranger.endpoint.pair(beta.address)
        world.pump()

        assertEquals(codesBefore, beta.codes.size)
    }

    @Test
    fun theRoundTripIsMeasuredOnceTheLinkHasBeenUpForASecond() {
        val (world, alpha, beta) = pairedWorld()
        assertNull(alpha.endpoint.rttMs(beta.publicKey))

        world.advance(Endpoint.PROBE_INTERVAL_MS + 500)

        assertNotNull(alpha.endpoint.rttMs(beta.publicKey))
        assertNotNull(beta.endpoint.rttMs(alpha.publicKey))
        assertNull("a device that is not linked has no round trip", alpha.endpoint.rttMs("00".repeat(32)))
    }

    @Test
    fun aRequestToSendFragmentsAgainReachesThePublisher() {
        val (world, alpha, beta) = pairedWorld()
        val nack = Control.Nack(streamId = 4, frameId = 77, indexes = listOf(1, 5))

        alpha.endpoint.requestRetransmit(beta.publicKey, nack)
        world.pump()

        assertEquals(listOf(nack), beta.nacks)
    }

    @Test
    fun senderStatsReachTheWatchingDevice() {
        val (world, alpha, beta) = pairedWorld()
        val stats = Control.SenderStats(streamId = 3, bitrateKbps = 2_000, fps10 = 300, encodeMs10 = 80, sendMs10 = 5)

        beta.endpoint.sendSenderStats(alpha.publicKey, stats)
        world.pump()

        assertEquals(listOf(stats), alpha.senderStats)
    }

    @Test
    fun aDeviceThatWasNeverPairedGetsNoAnswer() {
        val (world, alpha, beta) = pairedWorld()
        val stranger = Node(world, "Stranger", Address("10.0.0.9", 4000))
        val beforeThat = world.log.size

        stranger.endpoint.connect(Device(beta.publicKey, "Beta", null), beta.address)
        world.advance(7_000)

        assertFalse(beta.endpoint.isLinked(stranger.publicKey))
        assertEquals(1, stranger.connectFailed.size)
        assertTrue("the hub stayed silent", world.log.drop(beforeThat).none { it.first == beta.address && it.second == stranger.address })
    }

    @Test
    fun aRecordedHelloCannotBeReplayed() {
        val (world, alpha, beta) = pairedWorld()
        alpha.start()
        beta.start()
        val mark = world.log.size
        alpha.endpoint.connect(alpha.book.find(beta.identity.keys.public)!!, beta.address)
        world.pump()
        val hello = world.log.drop(mark).first { it.first == alpha.address && it.third[0] == Wire.TYPE_HELLO }
        val answers = world.log.count { it.first == beta.address && it.third[0] == Wire.TYPE_HELLO }

        beta.endpoint.onDatagram(hello.first, hello.third)
        world.pump()

        assertEquals("no second answer", answers, world.log.count { it.first == beta.address && it.third[0] == Wire.TYPE_HELLO })
    }

    @Test
    fun aSilentLinkIsDroppedOnBothSides() {
        val (world, alpha, beta) = pairedWorld()

        world.cut = true
        world.advance(Endpoint.LINK_TIMEOUT_MS + 2_000)

        assertFalse(alpha.endpoint.isLinked(beta.publicKey))
        assertFalse(beta.endpoint.isLinked(alpha.publicKey))
        assertEquals(1, alpha.linksDown.size)
        assertEquals(1, beta.linksDown.size)
    }

    @Test
    fun anIdleLinkStaysUpOnPings() {
        val (world, alpha, beta) = pairedWorld()

        world.advance(60_000)

        assertTrue(alpha.endpoint.isLinked(beta.publicKey) && beta.endpoint.isLinked(alpha.publicKey))
        assertTrue(alpha.linksDown.isEmpty())
    }

    @Test
    fun feedListsArriveOnceEvenWhenPacketsAreLostOrRepeated() {
        val (world, alpha, beta) = pairedWorld()
        var dropped = 0
        world.dropIf = { _, bytes -> bytes[0] == Session.TYPE_DATA && dropped++ < 2 }
        val offered = listOf(FeedInfo("alpha", 720, 1080, 30, false), FeedInfo("camera", 1280, 720, 30, true))

        alpha.endpoint.sendFeeds(beta.publicKey, offered)
        world.advance(3_000)

        assertEquals("delivered exactly once", listOf(offered), beta.feeds)
    }

    @Test
    fun saidGoodbyeTheOtherSideDropsTheLinkAtOnce() {
        val (world, alpha, beta) = pairedWorld()

        alpha.endpoint.disconnect(beta.publicKey)
        world.pump()

        assertFalse(beta.endpoint.isLinked(alpha.publicKey))
        assertEquals(1, beta.linksDown.size)
    }

    @Test
    fun unpairingForgetsTheDeviceAndItCannotReconnect() {
        val (world, alpha, beta) = pairedWorld()

        beta.endpoint.unpair(alpha.publicKey)
        world.pump()
        alpha.endpoint.connect(alpha.book.find(beta.identity.keys.public)!!, beta.address)
        world.advance(7_000)

        assertTrue(beta.book.devices.value.isEmpty())
        assertFalse(beta.endpoint.isLinked(alpha.publicKey))
    }

    @Test
    fun pairingAgainKeepsWhatWasSwitchedOn() {
        val (world, alpha, beta) = pairedWorld()
        alpha.book.update(beta.publicKey) { it.copy(receive = true) }
        alpha.endpoint.disconnect(beta.publicKey)
        beta.endpoint.disconnect(alpha.publicKey)
        world.pump()

        beta.endpoint.openPairing()
        alpha.endpoint.pair(beta.address)
        world.pump()
        alpha.endpoint.confirmPairing(true)
        beta.endpoint.confirmPairing(true)
        world.pump()

        assertTrue(alpha.book.find(beta.identity.keys.public)!!.receive)
    }

    @Test
    fun twoDevicesDialingAtTheSameTimeEndUpWithOneWorkingLink() {
        val (world, alpha, beta) = pairedWorld()
        alpha.start()
        beta.start()

        alpha.endpoint.connect(alpha.book.find(beta.identity.keys.public)!!, beta.address)
        beta.endpoint.connect(beta.book.find(alpha.identity.keys.public)!!, alpha.address)
        world.pump()
        world.advance(2_000)
        val offered = listOf(FeedInfo("alpha", 720, 1080, 30, false))
        alpha.endpoint.sendFeeds(beta.publicKey, offered)
        beta.endpoint.sendFeeds(alpha.publicKey, offered)
        world.advance(1_000)

        assertEquals("the first device received the list of the second", listOf(offered), beta.feeds)
        assertEquals("the second device received the list of the first", listOf(offered), alpha.feeds)
        assertTrue(alpha.endpoint.isLinked(beta.publicKey) && beta.endpoint.isLinked(alpha.publicKey))
    }

    @Test
    fun aDeviceIsNotDialedTwice() {
        val (world, alpha, beta) = pairedWorld()
        alpha.start()
        val device = alpha.book.find(beta.identity.keys.public)!!

        alpha.endpoint.connect(device, beta.address)
        val sent = world.log.size
        alpha.endpoint.connect(device, beta.address)

        assertEquals(sent, world.log.size)
        assertTrue(alpha.endpoint.isDialing(beta.publicKey))
    }

    @Test
    fun aSubscriptionReachesThePublisherUnchanged() {
        val (world, alpha, beta) = pairedWorld()
        val request = Control.Subscribe(streamId = 7, feed = "alpha", width = 720, height = 1080, bitrateKbps = 2_500)

        alpha.endpoint.subscribe(beta.publicKey, request)
        alpha.endpoint.requestKeyframe(beta.publicKey, 7)
        alpha.endpoint.unsubscribe(beta.publicKey, 7)
        world.pump()

        assertEquals(listOf(request), beta.subscribes)
        assertEquals(listOf(7), beta.keyframeRequests)
        assertEquals(listOf(7), beta.unsubscribes)
    }

    @Test
    fun videoCrossesTheLinkAndIsRebuiltIntoTheSameFrame() {
        val (world, alpha, beta) = pairedWorld()
        val original = Frame(id = 9, timestamp = 90_000, keyframe = true, encoded = ByteArray(40_000) { (it * 7).toByte() })

        beta.endpoint.sendVideo(alpha.publicKey, 12, Fragmenter.split(original))
        world.pump()

        assertTrue(alpha.video.all { it.first == 12 })
        val buffer = FrameBuffer()
        val rebuilt = alpha.video.flatMap { buffer.add(it.second, 0) }.single().frame
        assertArrayEquals(original.encoded, rebuilt.encoded)
        assertTrue(rebuilt.keyframe)
    }

    @Test
    fun audioCrossesTheLinkWithItsStreamAndTimestamp() {
        val (world, alpha, beta) = pairedWorld()
        val frame = ByteArray(340) { it.toByte() }

        beta.endpoint.sendAudio(alpha.publicKey, 12, 90_000, frame)
        beta.endpoint.sendAudio(alpha.publicKey, 12, 91_875, frame)
        world.pump()

        assertEquals(listOf(12, 12), alpha.audio.map { it.streamId })
        assertEquals(listOf(90_000, 91_875), alpha.audio.map { it.timestamp })
        assertArrayEquals(frame, alpha.audio.first().frame)
        assertTrue("audio is not mistaken for video", alpha.video.isEmpty())
    }

    @Test
    fun audioForADeviceThatIsNotLinkedGoesNowhere() {
        val (world, _, beta) = pairedWorld()
        val before = world.log.size

        beta.endpoint.sendAudio("00".repeat(32), 1, 0, ByteArray(100))

        assertEquals(before, world.log.size)
    }

    @Test
    fun videoForADeviceThatIsNotLinkedGoesNowhere() {
        val (world, _, beta) = pairedWorld()
        val before = world.log.size

        beta.endpoint.sendVideo("00".repeat(32), 1, listOf(ByteArray(100)))

        assertEquals(before, world.log.size)
    }

    @Test
    fun aLostVideoPacketLeavesAGapTheReceiverCanSee() {
        val (world, alpha, beta) = pairedWorld()
        var seen = 0
        world.dropIf = { _, bytes -> bytes[0] == Session.TYPE_DATA && seen++ == 1 }
        val key = Frame(1, 0, keyframe = true, encoded = ByteArray(3_000))
        val delta = Frame(2, 3_000, keyframe = false, encoded = ByteArray(6_000))
        val next = Frame(3, 6_000, keyframe = false, encoded = ByteArray(300))

        beta.endpoint.sendVideo(alpha.publicKey, 1, Fragmenter.split(key) + Fragmenter.split(delta) + Fragmenter.split(next))
        world.pump()

        val buffer = FrameBuffer()
        val frames = alpha.video.flatMap { buffer.add(it.second, 0) } + buffer.poll(buffer.maxWait + 1).frames
        assertTrue("the keyframe or the frame after it was lost, and nothing broken came out", frames.size < 3)
        assertTrue(buffer.needsKeyframe || frames.size == 3)
    }
}
