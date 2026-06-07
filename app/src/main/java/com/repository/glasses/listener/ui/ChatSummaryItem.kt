package com.repository.glasses.listener.ui

data class ChatSummaryItem(
    val id: String,
    val title: String,
    val relativeTime: String,
    val turnCount: Int,
    val isActive: Boolean,
    val deviceType: String = ""
)
