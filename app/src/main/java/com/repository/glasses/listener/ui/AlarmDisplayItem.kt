package com.repository.glasses.listener.ui

data class AlarmDisplayItem(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val title: String,
    val enabled: Boolean,
    val triggerTimeMillis: Long
)
