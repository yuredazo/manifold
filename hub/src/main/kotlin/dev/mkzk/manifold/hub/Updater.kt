package dev.mkzk.manifold.hub

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

private const val REPO = "yuredazo/manifold"
private const val NETWORK_TIMEOUT_MS = 20_000
private const val MAX_API_BYTES = 2 * 1024 * 1024
private const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024
private val VERSION = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")
private val ASSET_NAME = Regex("""^manifold-hub-v[0-9A-Za-z.\-]+\.apk$""")

internal class Release(val version: String, val page: String, val assetName: String, val assetUrl: String, val sha256: String, val sizeBytes: Long)

internal enum class Problem(val whileInstalling: Boolean) {
    OFFLINE(false),
    TIMEOUT(false),
    RATE_LIMITED(false),
    GITHUB_STATUS(false),
    BAD_ANSWER(false),
    NO_DOWNLOAD(false),
    DOWNLOAD_FAILED(true),
    TOO_LARGE(true),
    BAD_CHECKSUM(true),
    DIFFERENT_SIGNATURE(true),
    INSTALL_FAILED(true),
}

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val fraction: Float?) : UpdateState
    data class AwaitingConfirmation(val release: Release) : UpdateState

    data class Failed(val problem: Problem, val detail: String? = null) : UpdateState
}

internal sealed interface InstallOutcome {
    data object Cancelled : InstallOutcome
    data object DifferentSignature : InstallOutcome
    data class Failed(val message: String?) : InstallOutcome
}

internal interface ApkSession {
    val stream: OutputStream
    fun commit()
    fun abandon()
}

internal interface ApkInstaller {
    fun begin(sizeBytes: Long): ApkSession
}

internal fun isNewer(candidate: String, current: String): Boolean {
    val a = VERSION.find(candidate.trim())?.groupValues ?: return false
    val b = VERSION.find(current.trim())?.groupValues ?: return false
    for (part in 1..3) {
        val difference = a[part].toInt() - b[part].toInt()
        if (difference != 0) return difference > 0
    }
    return false
}

internal class Updater(
    private val currentVersion: String,
    private val installer: ApkInstaller,
    checkOnLaunch: Boolean,
    private val saveCheckOnLaunch: (Boolean) -> Unit,
    private val apiBase: String = "https://api.github.com",
    /** A download link outside this prefix is refused even when the release data lists it. */
    private val assetPrefix: String = "https://github.com/$REPO/releases/download/",
    private val now: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val flow = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val checkFlow = MutableStateFlow(checkOnLaunch)
    private var launchCheckDone = false
    private var pending: Release? = null

    val state: StateFlow<UpdateState> = flow
    val checkOnLaunch: StateFlow<Boolean> = checkFlow

    val version: String get() = currentVersion

    private val busy get() = flow.value.let { it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.AwaitingConfirmation }

    fun setCheckOnLaunch(on: Boolean) {
        checkFlow.value = on
        saveCheckOnLaunch(on)
    }

    /** Once per process, so turning the screen or reopening the activity does not ask GitHub again. */
    fun checkOnLaunchIfWanted() {
        if (launchCheckDone || !checkFlow.value) return
        launchCheckDone = true
        check()
    }

    fun check() {
        if (busy) return
        flow.value = UpdateState.Checking
        scope.launch { flow.value = fetchLatest() }
    }

    fun install() {
        val release = (flow.value as? UpdateState.Available)?.release ?: return
        flow.value = UpdateState.Downloading(release, null)
        scope.launch { download(release)?.let { flow.value = it } }
    }

    fun installFinished(outcome: InstallOutcome) {
        val release = pending ?: return
        pending = null
        flow.value = when (outcome) {
            InstallOutcome.Cancelled -> UpdateState.Available(release)
            InstallOutcome.DifferentSignature -> UpdateState.Failed(Problem.DIFFERENT_SIGNATURE)
            is InstallOutcome.Failed -> UpdateState.Failed(Problem.INSTALL_FAILED, outcome.message)
        }
    }

    private fun fetchLatest(): UpdateState {
        val connection = open("$apiBase/repos/$REPO/releases/latest")
        return try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            when (val code = connection.responseCode) {
                200 -> interpret(connection.inputStream.readLimited(MAX_API_BYTES))
                404 -> UpdateState.UpToDate(now())
                403, 429 -> UpdateState.Failed(Problem.RATE_LIMITED, connection.getHeaderField("x-ratelimit-reset"))
                else -> UpdateState.Failed(Problem.GITHUB_STATUS, code.toString())
            }
        } catch (_: SocketTimeoutException) {
            UpdateState.Failed(Problem.TIMEOUT)
        } catch (_: IOException) {
            UpdateState.Failed(Problem.OFFLINE)
        } finally {
            connection.disconnect()
        }
    }

    private fun interpret(body: ByteArray): UpdateState {
        val json = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: JSONException) {
            return UpdateState.Failed(Problem.BAD_ANSWER)
        }
        val tag = json.optString("tag_name")
        if (!isNewer(tag, currentVersion)) return UpdateState.UpToDate(now())
        val version = tag.removePrefix("v")
        val listed = json.optString("html_url")
        val page = if (listed.startsWith("https://github.com/$REPO/")) listed else "https://github.com/$REPO/releases"
        val assets = json.optJSONArray("assets")
        for (index in 0 until (assets?.length() ?: 0)) {
            val asset = assets?.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            val digest = asset.optString("digest")
            if (!ASSET_NAME.matches(name) || !url.startsWith(assetPrefix) || !digest.startsWith("sha256:")) continue
            return UpdateState.Available(Release(version, page, name, url, digest.removePrefix("sha256:").lowercase(), asset.optLong("size", -1)))
        }
        return UpdateState.Failed(Problem.NO_DOWNLOAD, version)
    }

    /** The APK is hashed on its way into the session, so a bad download is never committed. */
    private fun download(release: Release): UpdateState? {
        if (release.sizeBytes > MAX_DOWNLOAD_BYTES) return UpdateState.Failed(Problem.TOO_LARGE)
        val connection = open(release.assetUrl)
        var session: ApkSession? = null
        return try {
            if (connection.responseCode != 200) return UpdateState.Failed(Problem.DOWNLOAD_FAILED, connection.responseCode.toString())
            session = installer.begin(release.sizeBytes)
            val digest = MessageDigest.getInstance("SHA-256")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes.takeIf { it > 0 }
            var received = 0L
            var reportedAt = 0L
            val buffer = ByteArray(64 * 1024)
            connection.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    if (received > MAX_DOWNLOAD_BYTES) {
                        session.abandon()
                        return UpdateState.Failed(Problem.TOO_LARGE)
                    }
                    digest.update(buffer, 0, count)
                    session.stream.write(buffer, 0, count)
                    if (received - reportedAt > 256 * 1024) {
                        reportedAt = received
                        flow.value = UpdateState.Downloading(release, total?.let { received.toFloat() / it })
                    }
                }
            }
            if (digest.digest().toHex() != release.sha256) {
                session.abandon()
                return UpdateState.Failed(Problem.BAD_CHECKSUM)
            }
            pending = release
            // Set before the commit, because the system's answer can arrive before this thread gets further.
            flow.value = UpdateState.AwaitingConfirmation(release)
            session.commit()
            null
        } catch (_: IOException) {
            session?.abandon()
            UpdateState.Failed(Problem.DOWNLOAD_FAILED)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            setRequestProperty("User-Agent", "manifold-hub/$currentVersion")
        }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        use {
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
                if (out.size() > limit) throw IOException("too large")
            }
        }
        return out.toByteArray()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        lateinit var instance: Updater
            private set

        fun register(updater: Updater) {
            instance = updater
        }
    }
}
