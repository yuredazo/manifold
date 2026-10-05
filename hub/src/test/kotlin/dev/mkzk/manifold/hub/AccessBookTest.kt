package dev.mkzk.manifold.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessBookTest {

    private val chat = Owner(uid = 10_010, packageName = "app.chat", label = "Chat", certSha256 = "aaaa")

    private fun book(stored: String? = null, saved: MutableList<String> = mutableListOf()) =
        AccessBook(stored) { saved += it }

    @Test
    fun anUnknownAppIsAsked() {
        assertEquals(Access.ASK, book().decide(chat))
    }

    @Test
    fun aSeenAppIsListedButStillAsked() {
        val book = book()
        book.see(chat)

        assertEquals(Access.ASK, book.decide(chat))
        assertEquals(listOf("app.chat"), book.apps.value.map { it.packageName })
    }

    @Test
    fun aDecisionAppliesWhileTheCertificateStaysTheSame() {
        val book = book()
        book.see(chat)
        book.set("app.chat", Access.ALLOWED)

        assertEquals(Access.ALLOWED, book.decide(chat))
        book.set("app.chat", Access.BLOCKED)
        assertEquals(Access.BLOCKED, book.decide(chat))
    }

    @Test
    fun aDifferentCertificateMakesTheHubAskAgain() {
        val book = book()
        book.see(chat)
        book.set("app.chat", Access.ALLOWED)

        val resigned = chat.copy(certSha256 = "bbbb")
        book.see(resigned)

        assertEquals(Access.ASK, book.decide(resigned))
        assertTrue(book.apps.value.single().certificateChanged)

        book.set("app.chat", Access.ALLOWED)
        assertEquals(Access.ALLOWED, book.decide(resigned))
        assertFalse(book.apps.value.single().certificateChanged)
    }

    @Test
    fun decisionsSurviveARestart() {
        val saved = mutableListOf<String>()
        val first = book(saved = saved)
        first.see(chat)
        first.set("app.chat", Access.BLOCKED)

        val second = book(stored = saved.last())

        assertEquals(Access.BLOCKED, second.decide(chat))
        assertEquals("Chat", second.apps.value.single().label)
    }

    @Test
    fun aLabelCannotBreakTheStoredFormat() {
        val saved = mutableListOf<String>()
        val first = book(saved = saved)
        first.see(chat.copy(label = "Evil\tName\nsecond line"))
        first.set("app.chat", Access.ALLOWED)

        val second = book(stored = saved.last())

        assertEquals(1, second.apps.value.size)
        assertEquals(Access.ALLOWED, second.decide(chat))
    }

    @Test
    fun brokenLinesAreIgnored() {
        val stored = "garbage\napp.chat\tNOPE\taaaa\taaaa\tChat\napp.ok\tALLOWED\taaaa\taaaa\tOk"

        val book = book(stored)

        assertEquals(listOf("app.ok"), book.apps.value.map { it.packageName })
    }

    @Test
    fun seeingTheSameAppAgainDoesNotWriteAgain() {
        val saved = mutableListOf<String>()
        val book = book(saved = saved)

        book.see(chat)
        book.see(chat)

        assertEquals(1, saved.size)
    }

    @Test
    fun removingAnAppForgetsItsDecisionAndTheStoredCopy() {
        val saved = mutableListOf<String>()
        val book = book(saved = saved)
        book.see(chat)
        book.set("app.chat", Access.ALLOWED)

        book.remove("app.chat")

        assertEquals(Access.ASK, book.decide(chat))
        assertTrue(book.apps.value.isEmpty())
        assertFalse(saved.last().contains("app.chat"))
    }
}
