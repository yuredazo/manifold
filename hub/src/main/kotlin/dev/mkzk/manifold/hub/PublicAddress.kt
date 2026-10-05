package dev.mkzk.manifold.hub

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val SERVICE = "https://api.ipify.org"
private const val TIMEOUT_MS = 6_000
private const val MAX_BYTES = 64
private val NUMERIC_ADDRESS = Regex("""[0-9]{1,3}(\.[0-9]{1,3}){3}|[0-9a-fA-F:]{2,45}""")

/** Asks an outside service which address this phone's traffic comes from. Blocks, so not for the main thread. Null when it cannot say. */
internal fun fetchPublicAddress(service: String = SERVICE): String? = try {
    val connection = (URL(service).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
    }
    try {
        if (connection.responseCode != 200) null else connection.inputStream.use { it.readNBytesLimited(MAX_BYTES + 1) }
            ?.trim()?.takeIf { NUMERIC_ADDRESS.matches(it) && (it.contains('.') || it.contains(':')) }
    } finally {
        connection.disconnect()
    }
} catch (_: IOException) {
    null
}

private fun java.io.InputStream.readNBytesLimited(limit: Int): String? {
    val bytes = ByteArray(limit)
    var filled = 0
    while (filled < limit) {
        val read = read(bytes, filled, limit - filled)
        if (read < 0) break
        filled += read
    }
    return if (filled >= limit) null else String(bytes, 0, filled, Charsets.US_ASCII)
}
