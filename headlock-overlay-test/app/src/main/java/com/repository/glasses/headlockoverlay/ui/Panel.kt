package com.repository.glasses.headlockoverlay.ui

enum class PanelRole { CENTER, PRIMARY, SECONDARY, FAR }

data class Panel(
    val yawDeg: Float,
    val pitchDeg: Float,
    val title: String,
    val lines: List<String>,
    val role: PanelRole,
)
