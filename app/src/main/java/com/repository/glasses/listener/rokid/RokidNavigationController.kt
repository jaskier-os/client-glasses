package com.repository.glasses.listener.rokid

import android.content.Context
import android.content.Intent
import org.json.JSONObject

class RokidNavigationController(
    private val context: Context,
    private val bridge: RokidServiceBridge
) {

    companion object {
        const val TYPE_DRIVE = 0
        const val TYPE_WALK = 1
        const val TYPE_RIDE = 2
    }

    var remoteLog: ((String) -> Unit)? = null

    fun startNavigation(destination: String, naviType: Int = TYPE_DRIVE) {
        remoteLog?.invoke("[RokidNavigation] Starting navigation: destination=$destination naviType=$naviType")

        bridge.sendSceneControl("navigation", true)

        try {
            Intent().apply {
                setClassName(
                    "com.rokid.os.sprite.launcher",
                    "com.rokid.os.sprite.launcher.page.navigation.NavigationOverseaPageActivity"
                )
                putExtra("destination", destination)
                putExtra("naviType", naviType)
                putExtra("locPermissionTip", "")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.let { context.startActivity(it) }
            remoteLog?.invoke("[RokidNavigation] NavigationOverseaPageActivity launched")
        } catch (e: Exception) {
            remoteLog?.invoke("[RokidNavigation] Failed to launch NavigationOverseaPageActivity: ${e.message}")
        }

        val navJson = JSONObject().apply {
            put("destination", destination)
            put("naviType", naviType)
            put("totalDistance", 0)
            put("locPermissionTip", "")
        }.toString()
        bridge.sendGattMessage("Nav", "Nav_Start", navJson)
    }

    fun stopNavigation() {
        remoteLog?.invoke("[RokidNavigation] Stopping navigation")
        bridge.sendGattMessage("Nav", "Nav_Stop", "")
        bridge.sendSceneControl("navigation", false)
    }
}
