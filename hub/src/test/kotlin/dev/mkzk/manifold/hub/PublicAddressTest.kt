package dev.mkzk.manifold.hub

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicAddressTest {
    private var status = 200
    private var body = ""
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    private val url get() = "http://127.0.0.1:${server.localPort}/"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (input.readLine().orEmpty().isNotEmpty()) Unit
                        val bytes = body.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } catch (_: java.io.IOException) {
                    // The test closed the server.
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    @Test
    fun anAddressTheServiceReturnsIsPassedOn() {
        body = "203.0.113.7\n"

        assertEquals("203.0.113.7", fetchPublicAddress(url))
    }

    @Test
    fun anIpv6AddressIsPassedOn() {
        body = "2001:db8::7"

        assertEquals("2001:db8::7", fetchPublicAddress(url))
    }

    @Test
    fun anythingThatIsNotAnAddressIsRefused() {
        body = "<html>blocked</html>"
        assertNull(fetchPublicAddress(url))

        body = "abc"
        assertNull(fetchPublicAddress(url))

        body = "1".repeat(200)
        assertNull(fetchPublicAddress(url))
    }

    @Test
    fun anErrorStatusIsRefused() {
        status = 500
        body = "203.0.113.7"

        assertNull(fetchPublicAddress(url))
    }

    @Test
    fun aServiceThatCannotBeReachedGivesNull() {
        val dead = url
        server.close()

        assertNull(fetchPublicAddress(dead))
    }
}
