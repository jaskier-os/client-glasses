package com.repository.glasses.listener.ui

data class JobDisplayItem(
    val id: String,
    val name: String,
    val prompt: String,
    val scheduledAt: Long,
    val status: String,
    val result: String?,
    val error: String?
)
