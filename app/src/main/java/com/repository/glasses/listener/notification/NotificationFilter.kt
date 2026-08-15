package com.repository.glasses.listener.notification

/**
 * Content-based rules for notifications relayed from the phone, kept out of
 * ListenerService so they can be exercised without a device.
 */
object NotificationFilter {

    /**
     * True for the placeholder Telegram posts while its notification content is
     * hidden behind the app lock: the sender is the app name itself and the body
     * is the generic "New message". There is no chat, no author and no text, so
     * lighting the waveguide for it costs battery and tells the wearer nothing
     * they can act on.
     *
     * Matched trimmed and case-insensitively because the phone relays whatever
     * the NotificationListenerService handed it, verbatim.
     */
    fun isContentlessTelegramPlaceholder(sender: String, text: String): Boolean =
        sender.trim().equals("Telegram", ignoreCase = true) &&
            text.trim().equals("New message", ignoreCase = true)
}
