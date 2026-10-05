package dev.mkzk.manifold.hub

import android.os.ParcelFileDescriptor
import android.view.Surface
import dev.mkzk.manifold.SenderInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSessionTest {

    private class FakeSender : SenderLink {
        override val key = Any()
        val delivered = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        var audioSinks = mutableListOf<ParcelFileDescriptor?>()

        override fun deliver(subscriptionId: String, surface: Surface?, width: Int, height: Int, audioSink: ParcelFileDescriptor?) {
            delivered += subscriptionId
            audioSinks += audioSink
        }

        override fun revoke(subscriptionId: String) {
            revoked += subscriptionId
        }
    }

    private val hub = Owner(uid = 500, packageName = "dev.mkzk.manifold", label = "Preview")
    private val app = Owner(uid = 10_001, packageName = "app.a", label = "App A")

    private fun info(name: String) = SenderInfo().also {
        it.name = name
        it.width = 640
        it.height = 480
    }

    @Test
    fun theHubGetsTheFeedWithoutAskingItselfForPermission() {
        val registry = Registry(ownUid = hub.uid)
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), app)

        assertTrue(PreviewSession(registry, hub, "avatar").start(null, 320, 240))

        assertEquals(1, sender.delivered.size)
        val watcher = registry.state.value.subscriptions.single()
        assertEquals("Preview", watcher.receiverApp)
        assertEquals(Access.ALLOWED, watcher.access)
        assertTrue(watcher.live)
    }

    @Test
    fun aPreviewIsPictureOnly() {
        val registry = Registry(ownUid = hub.uid)
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), app)

        PreviewSession(registry, hub, "avatar").start(null, 320, 240)

        assertNull(sender.audioSinks.single())
    }

    @Test
    fun stoppingTakesTheFeedBackAndLeavesNothingBehind() {
        val registry = Registry(ownUid = hub.uid)
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), app)
        val preview = PreviewSession(registry, hub, "avatar")
        preview.start(null, 320, 240)

        preview.stop()

        assertEquals(sender.delivered, sender.revoked)
        assertTrue(registry.state.value.subscriptions.isEmpty())
    }

    @Test
    fun stoppingTwiceIsHarmless() {
        val registry = Registry(ownUid = hub.uid)
        registry.addSender(FakeSender(), info("avatar"), app)
        val preview = PreviewSession(registry, hub, "avatar")
        preview.start(null, 320, 240)

        preview.stop()
        preview.stop()

        assertTrue(registry.state.value.subscriptions.isEmpty())
    }

    @Test
    fun startingTwiceIsAMistake() {
        val registry = Registry(ownUid = hub.uid)
        val preview = PreviewSession(registry, hub, "avatar")
        preview.start(null, 320, 240)

        val failure = runCatching { preview.start(null, 320, 240) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun aRegistryThatIsFullRefusesTheStart() {
        val registry = Registry(limits = Limits(maxSubscriptionsPerApp = 0), ownUid = hub.uid)
        val preview = PreviewSession(registry, hub, "avatar")

        assertFalse(preview.start(null, 320, 240))

        assertTrue(registry.state.value.subscriptions.isEmpty())
    }

    @Test
    fun aFeedThatIsNotThereYetIsWatchedOnceItAnnounces() {
        val registry = Registry(ownUid = hub.uid)
        PreviewSession(registry, hub, "avatar").start(null, 320, 240)
        val sender = FakeSender()

        registry.addSender(sender, info("avatar"), app)

        assertEquals(1, sender.delivered.size)
    }
}
