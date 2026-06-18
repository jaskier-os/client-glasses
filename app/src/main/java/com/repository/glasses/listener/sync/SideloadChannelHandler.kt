package com.repository.glasses.listener.sync

import com.repository.glasses.listener.bt.GlassesBtClient
import com.repository.glasses.listener.config.GlassesConfig
import com.repository.glasses.tracing.GT
import org.json.JSONObject

/**
 * Glasses-side handler for the sideload-through-phone session on channel listener_sideload.
 *
 * This is a parallel, minimal handshake that opens/closes the WiFi Direct link for a deploy
 * session WITHOUT entangling with the photo-pull sync FSM (SyncChannelHandler / CH_SYNC). It
 * reuses the SAME filesync WifiDirectHost via FileSyncBridge so WiFi stays managed in exactly
 * one place.
 *
 * Wire (single JSON arg on CH_SIDELOAD):
 *   phone -> glasses: {"t":"OPEN_WIFI"}  | {"t":"CLOSE_WIFI"}
 *   glasses -> phone: {"t":"WIFI_READY","details":{ssid,passphrase,ip,port,deviceAddress}}
 *                     {"t":"WIFI_ERROR","reason":"..."}
 *                     {"t":"WIFI_CLOSED"}
 *
 * OPEN_WIFI is rejected (WIFI_ERROR reason="sideloading_disabled") unless
 * GlassesConfig.sideloadingEnabled is true.
 *
 * Ownership of WiFi events: both this handler and SyncChannelHandler observe the same
 * FileSyncBridge callbacks (openWifiDirectForSync is shared + idempotent). To avoid
 * cross-talk, this handler only forwards WiFi_READY/ERROR/CLOSED to the phone over
 * CH_SIDELOAD while it has an outstanding sideload OPEN it initiated (`awaitingOpen` /
 * `sessionOwned`). Pull-FSM-driven WiFi traffic still goes out on CH_SYNC as before.
 */
class SideloadChannelHandler(
    private val btClient: GlassesBtClient,
    private val fileSync: FileSyncBridge,
) {

    companion object {
        private const val TAG = "SideloadChannel"
    }

    var remoteLog: ((String) -> Unit)? = null

    // Single lock guarding the session-ownership flags below. The WiFi callbacks run on the
    // AIDL callback thread while onMessage runs on the BT receive thread; the check-then-set
    // on these flags must be atomic so an interleaved OPEN/CLOSE can't mis-route or drop a
    // WiFi event to the phone.
    private val lock = Any()
    // True from the moment we ask filesync to open WiFi for a sideload session until we
    // get the corresponding READY/ERROR. Gates which WiFi callbacks we forward.
    private var awaitingOpen: Boolean = false
    // True once a sideload-owned group is up; cleared on CLOSE/ERROR. Lets us forward the
    // eventual WIFI_CLOSED to the phone on CH_SIDELOAD.
    private var sessionOwned: Boolean = false

    private val fileSyncListener = object : FileSyncBridge.Listener {
        override fun onWifiDirectReady(detailsJson: String) {
            synchronized(lock) {
                if (!awaitingOpen && !sessionOwned) return
                awaitingOpen = false
                sessionOwned = true
            }
            remoteLog?.invoke("$TAG: WIFI_READY -> phone")
            val out = JSONObject().put("t", "WIFI_READY")
            out.put("details", try { JSONObject(detailsJson) } catch (_: Exception) { JSONObject() })
            btClient.sendSideload(out.toString())
        }
        override fun onWifiDirectError(reason: String) {
            synchronized(lock) {
                if (!awaitingOpen && !sessionOwned) return
                awaitingOpen = false
                sessionOwned = false
            }
            remoteLog?.invoke("$TAG: WIFI_ERROR reason=$reason -> phone")
            btClient.sendSideload(JSONObject().put("t", "WIFI_ERROR").put("reason", reason).toString())
        }
        override fun onWifiDirectClosed() {
            synchronized(lock) {
                if (!sessionOwned && !awaitingOpen) return
                awaitingOpen = false
                sessionOwned = false
            }
            remoteLog?.invoke("$TAG: WIFI_CLOSED -> phone")
            btClient.sendSideload(JSONObject().put("t", "WIFI_CLOSED").toString())
        }
    }

    init {
        fileSync.addListener(fileSyncListener)
    }

    /** Called from GlassesBtClient.Listener.onSideloadMessage. */
    fun onMessage(payloadJson: String) = GT.section("sideload.recv") {
        val t = try { JSONObject(payloadJson).optString("t", "") } catch (_: Exception) { "" }
        when (t) {
            "OPEN_WIFI" -> {
                if (!GlassesConfig.sideloadingEnabled) {
                    remoteLog?.invoke("$TAG: OPEN_WIFI rejected -- sideloading disabled")
                    btClient.sendSideload(
                        JSONObject().put("t", "WIFI_ERROR").put("reason", "sideloading_disabled").toString()
                    )
                    return@section
                }
                remoteLog?.invoke("$TAG: OPEN_WIFI")
                synchronized(lock) { awaitingOpen = true }
                // Reuse the shared, idempotent WiFi open. If a group is already up (e.g. a
                // concurrent pull session), filesync re-emits WIFI_READY which we forward.
                fileSync.openWifiDirectForSync()
            }
            "CLOSE_WIFI" -> {
                remoteLog?.invoke("$TAG: CLOSE_WIFI")
                // closeWifiDirect tears down WifiDirectHost; filesync stops the HTTP server
                // which wipes the sideload staging dir. The WIFI_CLOSED reply is emitted from
                // onWifiDirectClosed above.
                fileSync.closeWifiDirect()
            }
            else -> remoteLog?.invoke("$TAG: unknown t=$t")
        }
    }

    fun detach() {
        fileSync.removeListener(fileSyncListener)
    }
}
