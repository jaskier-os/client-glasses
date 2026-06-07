package com.repository.glasses.listener.ui

data class TelegramMessage(
    val id: Int,
    val sender: String,
    val text: String,
    val date: String,
    val isOutgoing: Boolean = false,
    val chatId: String = "",
    val sendStatus: String = "",  // "sending", "sent", "read", "failed"
    val imageBase64: String? = null
)
