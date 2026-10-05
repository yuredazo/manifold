package dev.mkzk.manifold.hub

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdaterTest {
    private val server = MockWebServer()
    private val apk = ByteArray(300_000) { (it % 251).toByte() }
    private var latest: String? = null
    private var latestStatus = 200
    private var requests = 0
    private val saved = mutableListOf<Boolean>()

    private class FakeSession : ApkSession {
        val received = ByteArrayOutputStream()
        var committed = false
        var abandoned = false
        override val stream: OutputStream = received
        override fun commit() { committed = true }
        override fun abandon() { abandoned = true }
    }

    private val session = FakeSession()
    private val installer = object : ApkInstaller {
        override fun begin(sizeBytes: Long) = session
    }

    private val base get() = server.url("/").toString().trimEnd('/')

    @Before
    fun startServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests++
                if (request.path.orEmpty().endsWith("/releases/latest")) {
                    val status = if (latest == null && latestStatus == 200) 404 else latestStatus
                    return MockResponse().setResponseCode(status).addHeader("x-ratelimit-reset", "1791179488").setBody(latest.orEmpty())
                }
                return MockResponse().setBody(Buffer().write(apk))
            }
        }
        server.start()
    }

    @After
    fun stopServer() = server.shutdown()

    private fun updater(current: String = "1.0.0", checkOnLaunch: Boolean = true) = Updater(
        currentVersion = current,
        installer = installer,
        checkOnLaunch = checkOnLaunch,
        saveCheckOnLaunch = { saved += it },
        apiBase = base,
        assetPrefix = "$base/download/",
        now = { 42L },
        dispatcher = Dispatchers.Unconfined,
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun release(tag: String, digest: String? = sha256(apk), name: String = "manifold-hub-$tag.apk", url: String = "$base/download/$name"): String {
        val digestField = if (digest == null) "" else ",\"digest\":\"sha256:$digest\""
        return "{\"tag_name\":\"$tag\",\"html_url\":\"https://github.com/yuredazo/manifold/releases/tag/$tag\"," +
            "\"assets\":[{\"name\":\"$name\",\"browser_download_url\":\"$url\",\"size\":${apk.size}$digestField}]}"
    }

    @Test
    fun versionsCompareByNumber() {
        assertTrue(isNewer("v1.0.1", "1.0.0"))
        assertTrue(isNewer("1.10.0", "1.9.0"))
        assertTrue(isNewer("v2.0.0", "1.99.99"))
        assertFalse(isNewer("v1.0.0", "1.0.0"))
        assertFalse(isNewer("0.9.0", "1.0.0"))
        assertFalse(isNewer("nightly", "1.0.0"))
    }

    @Test
    fun noReleasePublishedIsNotAnError() {
        val updater = updater()
        updater.check()
        assertEquals(UpdateState.UpToDate(42L), updater.state.value)
    }

    @Test
    fun theSameVersionIsUpToDate() {
        latest = release("v1.0.0")
        val updater = updater()
        updater.check()
        assertEquals(UpdateState.UpToDate(42L), updater.state.value)
    }

    @Test
    fun onlyTheCheckAtLaunchAnnouncesANewerRelease() {
        latest = release("v1.2.0")

        val asked = updater()
        asked.check()
        assertNull(asked.announcement.value)

        val launched = updater()
        launched.checkOnLaunchIfWanted()
        assertEquals("1.2.0", launched.announcement.value?.version)
        launched.dismissAnnouncement()
        assertNull(launched.announcement.value)
    }

    @Test
    fun nothingIsAnnouncedWhenTheVersionIsCurrentOrLaunchChecksAreOff() {
        latest = release("v1.0.0")
        val current = updater()
        current.checkOnLaunchIfWanted()
        assertNull(current.announcement.value)

        latest = release("v1.2.0")
        val off = updater(checkOnLaunch = false)
        off.checkOnLaunchIfWanted()
        assertNull(off.announcement.value)
    }

    @Test
    fun aNewerReleaseOffersTheApkWithItsChecksum() {
        latest = release("v1.2.0")
        val updater = updater()
        updater.check()
        val offered = (updater.state.value as UpdateState.Available).release
        assertEquals("1.2.0", offered.version)
        assertEquals("manifold-hub-v1.2.0.apk", offered.assetName)
        assertEquals(sha256(apk), offered.sha256)
        assertEquals(apk.size.toLong(), offered.sizeBytes)
    }

    @Test
    fun aReleaseWithoutAChecksumIsNotOffered() {
        latest = release("v1.2.0", digest = null)
        val updater = updater()
        updater.check()
        assertEquals(Problem.NO_DOWNLOAD, (updater.state.value as UpdateState.Failed).problem)
    }

    @Test
    fun aDownloadLinkOutsideTheReleaseHostIsRefused() {
        latest = release("v1.2.0", url = "http://127.0.0.1:1/evil.apk")
        val updater = updater()
        updater.check()
        assertEquals(Problem.NO_DOWNLOAD, (updater.state.value as UpdateState.Failed).problem)
    }

    @Test
    fun theWindowsZipIsNotTakenForTheApk() {
        latest = release("v1.2.0", name = "manifold-hub-windows-v1.2.0.zip")
        val updater = updater()
        updater.check()
        assertEquals(Problem.NO_DOWNLOAD, (updater.state.value as UpdateState.Failed).problem)
    }

    @Test
    fun aRateLimitCarriesTheResetTime() {
        latestStatus = 403
        val updater = updater()
        updater.check()
        assertEquals(UpdateState.Failed(Problem.RATE_LIMITED, "1791179488"), updater.state.value)
    }

    @Test
    fun anAnswerThatIsNotJsonFailsCleanly() {
        latest = "<html>"
        val updater = updater()
        updater.check()
        assertEquals(Problem.BAD_ANSWER, (updater.state.value as UpdateState.Failed).problem)
    }

    @Test
    fun noServerMeansOffline() {
        val updater = updater()
        server.shutdown()
        updater.check()
        assertEquals(Problem.OFFLINE, (updater.state.value as UpdateState.Failed).problem)
    }

    @Test
    fun aGoodDownloadIsCommittedAndWaitsForTheSystemPrompt() {
        latest = release("v1.2.0")
        val updater = updater()
        updater.check()
        updater.install()

        assertTrue(updater.state.value is UpdateState.AwaitingConfirmation)
        assertTrue(session.committed)
        assertFalse(session.abandoned)
        assertTrue(apk.contentEquals(session.received.toByteArray()))
    }

    @Test
    fun aDownloadThatDoesNotMatchItsChecksumIsAbandoned() {
        latest = release("v1.2.0", digest = sha256(byteArrayOf(1, 2, 3)))
        val updater = updater()
        updater.check()
        updater.install()

        assertEquals(Problem.BAD_CHECKSUM, (updater.state.value as UpdateState.Failed).problem)
        assertTrue(session.abandoned)
        assertFalse(session.committed)
    }

    @Test
    fun cancellingTheSystemPromptGoesBackToTheOffer() {
        latest = release("v1.2.0")
        val updater = updater()
        updater.check()
        updater.install()
        updater.installFinished(InstallOutcome.Cancelled)

        assertTrue(updater.state.value is UpdateState.Available)
    }

    @Test
    fun aSignatureMismatchIsReportedAsSuch() {
        latest = release("v1.2.0")
        val updater = updater()
        updater.check()
        updater.install()
        updater.installFinished(InstallOutcome.DifferentSignature)

        assertEquals(Problem.DIFFERENT_SIGNATURE, (updater.state.value as UpdateState.Failed).problem)
    }

    @Test
    fun theLaunchCheckRunsOnceAndOnlyWhenWanted() {
        latest = release("v1.0.0")
        val off = updater(checkOnLaunch = false)
        off.checkOnLaunchIfWanted()
        assertEquals(0, requests)

        val on = updater()
        on.checkOnLaunchIfWanted()
        on.checkOnLaunchIfWanted()
        assertEquals(1, requests)
    }

    @Test
    fun theLaunchSettingIsSaved() {
        val updater = updater()
        updater.setCheckOnLaunch(false)
        assertEquals(listOf(false), saved)
        assertFalse(updater.checkOnLaunch.value)
    }
}
