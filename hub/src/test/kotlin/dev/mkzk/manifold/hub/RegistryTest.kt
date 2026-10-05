package dev.mkzk.manifold.hub

import android.os.ParcelFileDescriptor
import android.view.Surface
import dev.mkzk.manifold.SenderInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryTest {

    private class FakeSender : SenderLink {
        override val key = Any()
        val delivered = mutableListOf<String>()
        val revoked = mutableListOf<String>()

        override fun deliver(subscriptionId: String, surface: Surface?, width: Int, height: Int, audioSink: ParcelFileDescriptor?) {
            delivered += subscriptionId
        }

        override fun revoke(subscriptionId: String) {
            revoked += subscriptionId
        }
    }

    private class FakeReceiver : ReceiverLink {
        override val key = Any()
        val lists = mutableListOf<List<String>>()
        val access = mutableListOf<Access>()

        override fun sendersChanged(senders: List<SenderInfo>) {
            lists += senders.map { it.name }
        }

        override fun accessChanged(subscriptionId: String, access: Access) {
            this.access += access
        }
    }

    private val appA = Owner(uid = 10_001, packageName = "app.a", label = "App A")
    private val appB = Owner(uid = 10_002, packageName = "app.b", label = "App B")
    private val viewer = Owner(uid = 10_003, packageName = "app.viewer", label = "Viewer")

    private fun info(name: String, width: Int = 640, height: Int = 480) = SenderInfo().also {
        it.name = name
        it.label = "spoofed"
        it.packageName = "spoofed.package"
        it.width = width
        it.height = height
    }

    private fun Registry.watch(receiver: FakeReceiver, owner: Owner = viewer, name: String = "avatar"): String {
        addReceiver(receiver, owner)
        setAccess(owner.packageName, Access.ALLOWED)
        return subscribe(receiver.key, owner.uid, name, null, 320, 240, null)!!
    }

    private fun Registry.watchUndecided(receiver: FakeReceiver, owner: Owner = viewer, name: String = "avatar"): String {
        addReceiver(receiver, owner)
        return subscribe(receiver.key, owner.uid, name, null, 320, 240, null)!!
    }

    @Test
    fun announcedSendersReachReceiversWithTheRealIdentity() {
        val registry = Registry()
        val receiver = FakeReceiver()
        registry.addReceiver(receiver, viewer)

        assertEquals("avatar", registry.addSender(FakeSender(), info("avatar"), appA))

        assertEquals(listOf("avatar"), receiver.lists.last())
        val row = registry.state.value.senders.single()
        assertEquals("App A", row.app)
        assertEquals("app.a", row.packageName)
    }

    @Test
    fun anotherAppUsingTheSameNameGetsASuffix() {
        val registry = Registry()
        assertEquals("avatar", registry.addSender(FakeSender(), info("avatar"), appA))
        assertEquals("avatar (2)", registry.addSender(FakeSender(), info("avatar"), appB))
        assertEquals("avatar (3)", registry.addSender(FakeSender(), info("avatar"), viewer))
    }

    @Test
    fun anAppAnnouncingItsOwnNameAgainTakesOverFromItsOldEntry() {
        val registry = Registry()
        val old = FakeSender()
        val fresh = FakeSender()
        registry.addSender(old, info("avatar"), appA)

        assertEquals("avatar", registry.addSender(fresh, info("avatar"), appA))
        assertEquals(1, registry.state.value.senders.size)
    }

    @Test
    fun subscribingBeforeTheSenderExistsDeliversWhenItAnnounces() {
        val registry = Registry()
        val id = registry.watch(FakeReceiver())
        assertFalse(registry.state.value.subscriptions.single().live)

        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)

        assertEquals(listOf(id), sender.delivered)
        assertTrue(registry.state.value.subscriptions.single().live)
    }

    @Test
    fun aSenderThatLeavesAndComesBackGetsTheSubscriptionAgain() {
        val registry = Registry()
        val first = FakeSender()
        registry.addSender(first, info("avatar"), appA)
        val id = registry.watch(FakeReceiver())
        assertEquals(listOf(id), first.delivered)

        registry.removeSender(first.key)
        assertFalse(registry.state.value.subscriptions.single().live)

        val second = FakeSender()
        registry.addSender(second, info("avatar"), appA)
        assertEquals(listOf(id), second.delivered)
    }

    @Test
    fun unsubscribingTellsTheSender() {
        val registry = Registry()
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)
        val id = registry.watch(FakeReceiver())

        registry.unsubscribe(id, viewer.uid)

        assertEquals(listOf(id), sender.revoked)
        assertTrue(registry.state.value.subscriptions.isEmpty())
    }

    @Test
    fun aReceiverThatDiesTakesItsSubscriptionsWithIt() {
        val registry = Registry()
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)
        val receiver = FakeReceiver()
        val id = registry.watch(receiver)

        registry.removeReceiver(receiver.key)

        assertEquals(listOf(id), sender.revoked)
        assertTrue(registry.state.value.subscriptions.isEmpty())
        assertFalse(registry.state.value.apps.single().connected)
    }

    @Test
    fun oneAppCannotTouchAnotherAppsSubscription() {
        val registry = Registry()
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)
        val id = registry.watch(FakeReceiver())

        registry.unsubscribe(id, appB.uid)

        assertEquals(1, registry.state.value.subscriptions.size)
        assertTrue(sender.revoked.isEmpty())
    }

    @Test
    fun oneAppCannotUpdateOrRemoveAnotherAppsSender() {
        val registry = Registry()
        val sender = FakeSender()
        registry.addSender(sender, info("avatar", width = 100), appA)

        registry.updateSender(sender.key, info("avatar", width = 999), appB.uid)
        registry.removeSender(sender.key, appB.uid)

        assertEquals(100, registry.state.value.senders.single().width)
    }

    @Test
    fun aSubscriptionNeedsARegisteredReceiverOfTheSameApp() {
        val registry = Registry()
        val receiver = FakeReceiver()

        assertNull(registry.subscribe(receiver.key, viewer.uid, "avatar", null, 320, 240, null))
        registry.addReceiver(receiver, viewer)
        assertNull(registry.subscribe(receiver.key, appB.uid, "avatar", null, 320, 240, null))
        assertNotNull(registry.subscribe(receiver.key, viewer.uid, "avatar", null, 320, 240, null))
    }

    @Test
    fun badNamesAndOutOfRangeNumbersAreHandled() {
        val registry = Registry(Limits(maxNameLength = 8, maxDimension = 4096, maxFps = 120))

        assertNull(registry.addSender(FakeSender(), info("   "), appA))
        assertNull(registry.addSender(FakeSender(), info("line\nbreak"), appA))
        assertNull(registry.addSender(FakeSender(), info("far too long a name"), appA))

        val oversized = info("ok", width = 99_999, height = -5).also { it.fps = 1_000 }
        registry.addSender(FakeSender(), oversized, appA)
        val row = registry.state.value.senders.single()
        assertEquals(4096, row.width)
        assertEquals(0, row.height)
        assertEquals(120, row.fps)
    }

    @Test
    fun suffixedNamesStillFitTheLimit() {
        val registry = Registry(Limits(maxNameLength = 8))
        registry.addSender(FakeSender(), info("12345678"), appA)

        val name = registry.addSender(FakeSender(), info("12345678"), appB)

        assertEquals("1234 (2)", name)
    }

    @Test
    fun limitsStopRunawayApps() {
        val registry = Registry(Limits(maxSenders = 1, maxReceivers = 1, maxSubscriptionsPerApp = 1))

        assertNotNull(registry.addSender(FakeSender(), info("one"), appA))
        assertNull(registry.addSender(FakeSender(), info("two"), appB))

        val receiver = FakeReceiver()
        assertTrue(registry.addReceiver(receiver, viewer))
        assertFalse(registry.addReceiver(FakeReceiver(), appB))

        assertNotNull(registry.subscribe(receiver.key, viewer.uid, "one", null, 1, 1, null))
        assertNull(registry.subscribe(receiver.key, viewer.uid, "one", null, 1, 1, null))
    }

    @Test
    fun anAppThatWasNeverDecidedAboutWaitsAndGetsNothing() {
        val registry = Registry()
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)
        val receiver = FakeReceiver()

        registry.watchUndecided(receiver)

        assertTrue(sender.delivered.isEmpty())
        assertEquals(listOf(Access.ASK), receiver.access)
        val pending = registry.state.value.pending.single()
        assertEquals("app.viewer", pending.packageName)
        assertEquals(listOf("avatar"), pending.feeds)
    }

    @Test
    fun aSenderThatAnnouncesLaterIsNotGivenToAnUndecidedApp() {
        val registry = Registry()
        registry.watchUndecided(FakeReceiver())
        val sender = FakeSender()

        registry.addSender(sender, info("avatar"), appA)

        assertTrue(sender.delivered.isEmpty())
    }

    @Test
    fun allowingAnAppDeliversWhatWasWaiting() {
        val registry = Registry()
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)
        val receiver = FakeReceiver()
        val id = registry.watchUndecided(receiver)

        registry.setAccess("app.viewer", Access.ALLOWED)

        assertEquals(listOf(id), sender.delivered)
        assertEquals(Access.ALLOWED, receiver.access.last())
        assertTrue(registry.state.value.pending.isEmpty())
        assertTrue(registry.state.value.subscriptions.single().live)
    }

    @Test
    fun blockingAnAppTakesBackWhatItHadAndHidesTheSenders() {
        val registry = Registry()
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)
        val receiver = FakeReceiver()
        val id = registry.watch(receiver)
        assertEquals(listOf(id), sender.delivered)

        registry.setAccess("app.viewer", Access.BLOCKED)

        assertEquals(listOf(id), sender.revoked)
        assertFalse(registry.state.value.subscriptions.single().live)
        assertEquals(Access.BLOCKED, receiver.access.last())
        assertEquals(emptyList<String>(), receiver.lists.last())
    }

    @Test
    fun aBlockedAppStaysBlockedWhenItSubscribesAgain() {
        val registry = Registry()
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)
        val first = FakeReceiver()
        registry.watch(first)
        registry.setAccess("app.viewer", Access.BLOCKED)
        registry.removeReceiver(first.key)

        val second = FakeReceiver()
        registry.addReceiver(second, viewer)
        registry.subscribe(second.key, viewer.uid, "avatar", null, 320, 240, null)

        assertEquals(1, sender.delivered.size)
        assertEquals(Access.BLOCKED, second.access.last())
        assertTrue(registry.state.value.pending.isEmpty())
    }

    @Test
    fun anAppThatChangedItsSigningCertificateIsAskedAgain() {
        val registry = Registry()
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)
        val before = FakeReceiver()
        registry.addReceiver(before, viewer.copy(certSha256 = "aa"))
        registry.setAccess("app.viewer", Access.ALLOWED)
        registry.removeReceiver(before.key)

        val after = FakeReceiver()
        registry.addReceiver(after, viewer.copy(certSha256 = "bb"))
        registry.subscribe(after.key, viewer.uid, "avatar", null, 320, 240, null)

        assertEquals(Access.ASK, after.access.last())
        assertTrue(registry.state.value.pending.single().certificateChanged)
        assertTrue(sender.delivered.isEmpty())
    }

    @Test
    fun anUninstalledAppStartsUndecidedIfItComesBack() {
        val registry = Registry()
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)
        val before = FakeReceiver()
        registry.watch(before)
        registry.removeReceiver(before.key)

        registry.forget("app.viewer")
        assertTrue(registry.state.value.apps.isEmpty())

        val after = FakeReceiver()
        registry.watchUndecided(after)

        assertEquals(Access.ASK, after.access.last())
        assertEquals(1, sender.delivered.size)
    }

    @Test
    fun forgettingAConnectedAppTakesBackWhatItHad() {
        val registry = Registry()
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)
        val receiver = FakeReceiver()
        val id = registry.watch(receiver)

        registry.forget("app.viewer")

        assertEquals(listOf(id), sender.revoked)
        assertEquals(Access.ASK, receiver.access.last())
    }

    @Test
    fun theHubsOwnCodeIsNotAskedAndDoesNotShowUpAsAnApp() {
        val registry = Registry(ownUid = 500)
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)
        val internal = Owner(uid = 500, packageName = "net:abc", label = "Phone (network)")
        val receiver = FakeReceiver()

        registry.addReceiver(receiver, internal)
        val id = registry.subscribe(receiver.key, 500, "avatar", null, 720, 1080, null)!!

        assertEquals(listOf(id), sender.delivered)
        assertEquals(Access.ALLOWED, receiver.access.single())
        assertTrue(registry.state.value.apps.isEmpty())
        assertTrue(registry.state.value.pending.isEmpty())
    }

    @Test
    fun anotherAppIsStillAskedWhenTheHubHasAnOwnUid() {
        val registry = Registry(ownUid = 500)
        val sender = FakeSender()
        registry.addSender(sender, info("avatar"), appA)

        registry.watchUndecided(FakeReceiver())

        assertTrue(sender.delivered.isEmpty())
        assertEquals(1, registry.state.value.pending.size)
    }
}
