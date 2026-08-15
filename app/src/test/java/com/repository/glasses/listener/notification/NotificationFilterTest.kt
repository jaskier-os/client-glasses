package com.repository.glasses.listener.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {

    private fun drop(sender: String, text: String) =
        NotificationFilter.isContentlessTelegramPlaceholder(sender, text)

    @Test
    fun `drops the locked-Telegram placeholder`() {
        assertTrue(drop("Telegram", "New message"))
    }

    @Test
    fun `matches regardless of case and surrounding whitespace`() {
        assertTrue(drop("telegram", "new message"))
        assertTrue(drop("TELEGRAM", "NEW MESSAGE"))
        assertTrue(drop("  Telegram ", " New message  "))
    }

    @Test
    fun `keeps a real message from a person`() {
        assertFalse(drop("Alice", "see you at 8"))
    }

    @Test
    fun `keeps a real message whose text merely mentions a new message`() {
        assertFalse(drop("Alice", "New message from the landlord"))
    }

    @Test
    fun `keeps a genuine Telegram-sender message with real content`() {
        // Telegram's own service announcements are worth showing.
        assertFalse(drop("Telegram", "Login code: 12345"))
    }

    @Test
    fun `keeps the placeholder text from another app`() {
        assertFalse(drop("Signal", "New message"))
    }

    @Test
    fun `does not drop on empty input`() {
        assertFalse(drop("", ""))
    }
}
