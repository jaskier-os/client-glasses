package com.repository.glasses.headlockoverlay.ui

object MockPanels {
    val PANELS: List<Panel> = listOf(
        Panel(
            yawDeg = 0f, pitchDeg = 0f,
            title = "12:47",
            lines = listOf("Tue 12 Jul", "Clear \u00b7 18\u00b0C", "3 unread"),
            role = PanelRole.CENTER,
        ),
        Panel(
            yawDeg = 0f, pitchDeg = 20f,
            title = "Notifications",
            lines = listOf("Anna: lunch?", "Build #412 passed", "Battery 80%"),
            role = PanelRole.PRIMARY,
        ),
        Panel(
            yawDeg = 0f, pitchDeg = -20f,
            title = "Status",
            lines = listOf("Battery 80%", "Wi-Fi: Home", "BT: Glasses"),
            role = PanelRole.PRIMARY,
        ),
        Panel(
            yawDeg = -30f, pitchDeg = 0f,
            title = "Menu",
            lines = listOf("Camera", "Gallery", "Settings", "Assistant"),
            role = PanelRole.PRIMARY,
        ),
        Panel(
            yawDeg = 30f, pitchDeg = 0f,
            title = "Now Playing",
            lines = listOf("Miles Davis", "So What", "2:31 / 9:22"),
            role = PanelRole.PRIMARY,
        ),
        Panel(
            yawDeg = -60f, pitchDeg = 25f,
            title = "Calendar",
            lines = listOf("10:00 Standup", "14:00 1:1", "No more today"),
            role = PanelRole.SECONDARY,
        ),
        Panel(
            yawDeg = 60f, pitchDeg = 25f,
            title = "Weather",
            lines = listOf("Now 18\u00b0C", "Hi 21 / Lo 12", "Rain 10%"),
            role = PanelRole.SECONDARY,
        ),
        Panel(
            yawDeg = -60f, pitchDeg = -25f,
            title = "Fitness",
            lines = listOf("4,210 steps", "Goal 62%", "HR 68"),
            role = PanelRole.SECONDARY,
        ),
        Panel(
            yawDeg = 60f, pitchDeg = -25f,
            title = "Messages",
            lines = listOf("Anna (2)", "Team (5)", "Mom (1)"),
            role = PanelRole.SECONDARY,
        ),
        Panel(
            yawDeg = -90f, pitchDeg = 0f,
            title = "Far Left",
            lines = listOf("Edge menu", "Turn hard left", "Item A / B / C"),
            role = PanelRole.FAR,
        ),
        Panel(
            yawDeg = 90f, pitchDeg = 0f,
            title = "Far Right",
            lines = listOf("Edge info", "Turn hard right", "Details here"),
            role = PanelRole.FAR,
        ),
    )
}
