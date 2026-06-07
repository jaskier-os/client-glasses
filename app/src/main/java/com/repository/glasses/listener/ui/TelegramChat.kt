package com.repository.glasses.listener.ui

data class TelegramChat(
    val chatId: String,
    val title: String,
    val lastMessage: String,
    val lastMessageDate: String,
    val lastMessageSender: String,
    val unreadCount: Int,
    val chatType: String,  // "user", "group", "channel"
    val isForum: Boolean = false,
    val isOnline: Boolean = false,
    val lastSeen: String? = null,
    val avatar: String? = null
)
