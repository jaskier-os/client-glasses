package com.repository.glasses.listener.sync

import com.repository.glasses.listener.bt.BleWakeEvent
import com.repository.glasses.listener.bt.BtManagerBridge
import com.repository.glasses.listener.bt.GlassesBtClient
import com.repository.glasses.tracing.GT

/**
 * Glue between FileSyncBridge (AIDL client to filesync APK) and GlassesBtClient (RFCOMM to phone).
 *
 * Glasses-side side of the sync protocol on channel listener_sync.
 * Messages (msgType, sessionId, payload...):
 *   HELLO       -- either side greets. payload=[stateHash]
 *   HELLO_ACK   -- reply with own hash.  payload=[stateHash]
 *   GET_MANIFEST -- phone asks for manifest
 *   MANIFEST    -- glasses replies, payload=[jsonArray]
 *   OPEN_WIFI   -- phone asks glasses to open WiFi Direct group
 *   WIFI_READY  -- glasses announces group details, payload=[detailsJson]
 *   CLOSE_WIFI  -- tear down (either side)
 *   PULL_DONE   -- phone informs of pulled file, payload=[fileId]
 *   DELETE      -- phone requests authoritative delete, payload=[fileId]
 *   DELETED     -- glasses confirms, payload=[fileId]
 *   DELETE_DENIED -- glasses refused (file mid-stream), payload=[fileId]
 *
 * This handler is the passive arm. It does NOT own the FSM (glasses is always reactive).
 * It dispatches incoming phone frames to FileSyncBridge and emits outgoing frames on
 * manifest/wifi events.
 */
class SyncChannelHandler(
    private val btClient: GlassesBtClient,
    private val fileSync: FileSyncBridge,
    private val btManagerBridge: BtManagerBridge,
) {

    companion object {
        private const val TAG = "SyncChannel"
        // Wear-independent wake throttle. The phone routes any RFCOMM_REQUEST (0x10)
        // to a reconnect-signal semaphore release, which is idempotent and cheap, but
        // each notify costs a BLE write so coalesce bursts of manifest changes (e.g.
        // boot rescan finding a directory of opus archives) into one wake.
        private const val WAKE_THROTTLE_MS = 30_000L
        // Empty manifest = nothing for phone to pull. Don't wake.
        private const val EMPTY_MANIFEST_HASH_PREFIX = "e3b0c44298fc"  // sha256("")
    }

    var remoteLog: ((String) -> Unit)? = null

    /** Last session id we've seen from the phone. Echoed on all replies. */
    @Volatile private var lastSessionId: String = "0"

    @Volatile private var lastWakeNanos: Long = 0L

    private val fileSyncListener = object : FileSyncBridge.Listener {
        override fun onManifestChanged(newStateHash: String) {
            // BLE wake the phone so it (re)opens RFCOMM and drives a sync handshake.
            // Required because filesync must work while glasses are off-head: in that
            // state ProfileAutoConnector skips A2DP/HFP autoconnect, the BR/EDR ACL
            // can drop, and the phone's RFCOMM client only attempts a connect on a
            // wake event or outbound demand. Without this nudge, files captured
            // off-head sit in the manifest until the wearer puts the glasses back on.
            maybeWakePhoneForSync(newStateHash)
            // Unsolicited HELLO: always use sessionId="0". Phone-side FSM is coded to accept
            // sessionId="0" on any state as an invitation to (re)start. Using `lastSessionId`
            // here would race with the phone rotating sessionIds and could be silently dropped.
            sendHello("0")
        }
        override fun onWifiDirectReady(detailsJson: String) {
            // WIFI_READY is async after OPEN_WIFI; phone may have rotated sessions via
            // abort/retry in the meantime. Use "0" so the phone's session-gate accepts it
            // as unsolicited rather than dropping a stale id.
            btClient.sendSync("WIFI_READY", "0", detailsJson)
        }
        override fun onWifiDirectClosed() {
            // Same reasoning as WIFI_READY -- the close-ack may land post-session-rotation.
            btClient.sendSync("CLOSE_WIFI", "0")
        }
        override fun onWifiDirectError(reason: String) {
            // Terminal error: phone FSM must abort with the reason and back off.
            // Sending BEFORE the follow-up CLOSE_WIFI so the phone sees the real cause.
            remoteLog?.invoke("$TAG: WIFI_ERROR out reason=$reason")
            btClient.sendSync("WIFI_ERROR", "0", reason)
        }
        override fun onFileDeleted(fileId: String) {
            // DELETED is a direct ack to a phone-originated DELETE: echoing the session id is
            // safe because the phone hasn't rotated between sending DELETE and receiving the
            // reply (DELETE is synchronous on the RFCOMM queue).
            btClient.sendSync("DELETED", lastSessionId, fileId)
        }
    }

    init {
        fileSync.addListener(fileSyncListener)
    }

    /** Called from GlassesBtClient.Listener.onSyncMessage. */
    fun onMessage(msgType: String, sessionId: String, payload: List<String>) = GT.section("sync.recv.$msgType") {
        lastSessionId = sessionId
        when (msgType) {
            "HELLO" -> {
                val phoneHash = payload.getOrElse(0) { "" }
                val ours = fileSync.getStateHash()
                remoteLog?.invoke("$TAG: HELLO phone=${phoneHash.take(10)} ours=${ours.take(10)}")
                btClient.sendSync("HELLO_ACK", sessionId, ours)
            }
            "HELLO_ACK" -> {
                // Informational on glasses side -- we don't drive the FSM.
                remoteLog?.invoke("$TAG: HELLO_ACK received")
            }
            "GET_MANIFEST" -> {
                val json = fileSync.getManifestJson()
                remoteLog?.invoke("$TAG: sending MANIFEST (${json.length} chars)")
                btClient.sendSync("MANIFEST", sessionId, json)
            }
            "OPEN_WIFI" -> {
                remoteLog?.invoke("$TAG: OPEN_WIFI requested")
                fileSync.openWifiDirectForSync()
                // Actual WIFI_READY arrives via fileSyncListener.onWifiDirectReady
            }
            "CLOSE_WIFI" -> {
                remoteLog?.invoke("$TAG: CLOSE_WIFI requested")
                fileSync.closeWifiDirect()
            }
            "PULL_DONE" -> {
                // informational -- reserved for future TTL tracking
            }
            "DELETE" -> {
                val fileId = payload.getOrElse(0) { "" }
                if (fileId.isEmpty()) return@section
                val ok = fileSync.deleteFile(fileId)
                if (!ok) {
                    remoteLog?.invoke("$TAG: DELETE denied for $fileId (mid-stream or missing)")
                    btClient.sendSync("DELETE_DENIED", sessionId, fileId)
                }
                // On success, DELETED is emitted via fileSyncListener.onFileDeleted
            }
            else -> {
                remoteLog?.invoke("$TAG: unknown msgType=$msgType")
            }
        }
    }

    /** Called when BT (re)connects so the phone sees our current state. */
    fun onBtConnected() = GT.section("sync.bt_connected") {
        // Use "0" (unsolicited) rather than the stale lastSessionId from a prior BT session.
        // The phone always accepts sessionId="0" as a fresh greeting.
        sendHello("0")
    }

    private fun sendHello(sessionId: String) {
        val hash = fileSync.getStateHash()
        remoteLog?.invoke("$TAG: HELLO out hash=${hash.take(12)} session=$sessionId")
        btClient.sendSync("HELLO", sessionId, hash)
    }

    private fun maybeWakePhoneForSync(stateHash: String) {
        if (stateHash.startsWith(EMPTY_MANIFEST_HASH_PREFIX)) return
        val now = System.nanoTime()
        val elapsedMs = (now - lastWakeNanos) / 1_000_000L
        if (lastWakeNanos != 0L && elapsedMs < WAKE_THROTTLE_MS) {
            remoteLog?.invoke("$TAG: wake skipped (throttle, lastWake=${elapsedMs}ms ago)")
            return
        }
        val ok = try {
            btManagerBridge.notifyPhone(BleWakeEvent.RFCOMM_REQUEST, now)
        } catch (t: Throwable) {
            remoteLog?.invoke("$TAG: wake notifyPhone threw: ${t.message}")
            false
        }
        if (ok) {
            lastWakeNanos = now
            remoteLog?.invoke("$TAG: wake phone for sync (hash=${stateHash.take(12)})")
        } else {
            remoteLog?.invoke("$TAG: wake phone failed (no subscriber? bt off?)")
        }
    }

    fun detach() {
        fileSync.removeListener(fileSyncListener)
    }
}
