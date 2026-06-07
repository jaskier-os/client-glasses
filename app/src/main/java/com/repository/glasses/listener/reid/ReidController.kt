package com.repository.glasses.listener.reid

import android.os.SystemClock

/**
 * Core face recognition state machine.
 * Manages the full pipeline: camera -> face detection -> BT send -> result matching -> UI.
 */
class ReidController {

    companion object {
        private const val TAG = "ReidController"
        private const val RESEND_AREA_RATIO = 1.5f
        private const val PENDING_TIMEOUT_MS = 600_000L
        private const val STALE_FRAME_THRESHOLD = 20
        private const val MAX_VERIFIED_FACES = 50
    }

    data class PendingFace(
        val trackingId: Int,
        var thumbnailBase64: String,
        var thumbnailWidth: Int,
        var lastSentArea: Int,
        var bestArea: Int,
        val sentAtMs: Long,
        var lastSeenFrame: Int
    )

    data class VerifiedFace(
        val personUid: String,
        val displayName: String,
        var trackingId: Int,
        var thumbnailBase64: String,
        var thumbnailWidth: Int,
        var score: Float
    )

    interface BtSender {
        fun sendFace(trackingId: Int, webpBase64: String)
    }

    interface UiCallback {
        fun onFacesUpdated(verified: List<VerifiedFace>, pendingCount: Int)
        fun onStatsUpdated(frames: Int, faces: Int, fps: Double, pending: Int, verified: Int)
        fun onStatusChanged(status: String)
    }

    var btSender: BtSender? = null
    var uiCallback: UiCallback? = null
    var remoteLog: ((String) -> Unit)? = null
    /** Forwarded to ReidCameraCapturer so the camera-open lifecycle holds a
     *  bt-manager active session ("reid_streaming"). */
    var onActiveSessionEnter: (() -> Unit)? = null
    var onActiveSessionExit: (() -> Unit)? = null

    private val pendingFaces = mutableListOf<PendingFace>()
    private val verifiedFaces = mutableListOf<VerifiedFace>()
    // Merges that arrived before the face was added (source -> target)
    private val pendingMerges = mutableMapOf<String, String>()
    private var cameraCapturer: ReidCameraCapturer? = null
    @Volatile var isRunning = false
        private set

    fun start(context: android.content.Context) {
        if (isRunning) return
        isRunning = true
        pendingFaces.clear()
        pendingMerges.clear()

        log("Starting reid controller")
        uiCallback?.onStatusChanged("SCANNING")

        cameraCapturer = ReidCameraCapturer(context).apply {
            remoteLog = this@ReidController.remoteLog
            callback = cameraCallback
            onActiveSessionEnter = this@ReidController.onActiveSessionEnter
            onActiveSessionExit = this@ReidController.onActiveSessionExit
        }
        cameraCapturer?.start()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        log("Stopping reid controller")

        cameraCapturer?.stop()
        cameraCapturer = null
        uiCallback?.onStatusChanged("STOPPED")
    }

    fun onReidResult(trackingIdStr: String, recognized: Boolean, personUid: String, displayName: String, score: Float) {
        val trackingId = trackingIdStr.toIntOrNull() ?: return
        log("Reid result: tid=$trackingId recognized=$recognized uid=$personUid name=$displayName score=$score")

        if (!recognized) {
            // Keep in pending so the same trackingId isn't re-sent next frame
            emitUi()
            return
        }

        val pending = synchronized(pendingFaces) {
            pendingFaces.find { it.trackingId == trackingId }
        }
        val thumbB64 = pending?.thumbnailBase64 ?: ""
        val thumbW = pending?.thumbnailWidth ?: 0

        synchronized(pendingFaces) {
            pendingFaces.removeAll { it.trackingId == trackingId }
        }

        synchronized(verifiedFaces) {
            // Check if this UID was already merged before arrival
            val mergeTarget = pendingMerges.remove(personUid)
            val effectiveUid = mergeTarget ?: personUid
            if (mergeTarget != null) {
                log("Reid result: applying pending merge $personUid -> $effectiveUid")
            }

            val existing = verifiedFaces.find { it.personUid == effectiveUid }
            if (existing != null) {
                if (score > existing.score) {
                    if (thumbB64.isNotEmpty()) {
                        existing.thumbnailBase64 = thumbB64
                        existing.thumbnailWidth = thumbW
                    }
                    existing.score = score
                    existing.trackingId = trackingId
                }
            } else {
                if (verifiedFaces.size >= MAX_VERIFIED_FACES) {
                    verifiedFaces.removeAt(0)
                }
                verifiedFaces.add(VerifiedFace(effectiveUid, displayName, trackingId, thumbB64, thumbW, score))
            }
            Unit
        }

        emitUi()
    }

    fun onReidMerge(sourcePersonId: String, targetPersonId: String, targetDisplayName: String) {
        log("Reid merge: $sourcePersonId -> $targetPersonId ($targetDisplayName)")
        synchronized(verifiedFaces) {
            val source = verifiedFaces.find { it.personUid == sourcePersonId }
            val target = verifiedFaces.find { it.personUid == targetPersonId }
            if (source != null) {
                if (target != null) {
                    // Both exist -- keep best score, remove source
                    if (source.score > target.score) {
                        target.thumbnailBase64 = source.thumbnailBase64
                        target.thumbnailWidth = source.thumbnailWidth
                        target.score = source.score
                    }
                    verifiedFaces.remove(source)
                } else {
                    // Only source exists -- relabel it as target
                    verifiedFaces.remove(source)
                    verifiedFaces.add(VerifiedFace(
                        targetPersonId,
                        targetDisplayName,
                        source.trackingId,
                        source.thumbnailBase64,
                        source.thumbnailWidth,
                        source.score
                    ))
                }
            }
            if (source == null) {
                // Merge arrived before the face -- remember it
                if (pendingMerges.size >= 100) pendingMerges.clear()
                pendingMerges[sourcePersonId] = targetPersonId
                log("Reid merge: deferred (source not in face list yet)")
            }
        }
        emitUi()
    }

    private val cameraCallback = object : ReidCameraCapturer.Callback {
        override fun onFacesDetected(faces: List<ReidCameraCapturer.DetectedFace>, frameCount: Int, fps: Double) {
            val now = SystemClock.elapsedRealtime()

            for (face in faces) {
                val tid = face.trackingId
                val area = face.faceArea

                // Check if this is a verified face
                val verified = synchronized(verifiedFaces) {
                    verifiedFaces.find { it.trackingId == tid }
                }

                if (verified != null) {
                    // Already verified -- resend if area improved significantly
                    val pending = synchronized(pendingFaces) {
                        pendingFaces.find { it.trackingId == tid }
                    }
                    val lastSent = pending?.lastSentArea ?: area
                    if (area > (lastSent * RESEND_AREA_RATIO).toInt()) {
                        log("REID_SEND tid=$tid area=$area imgLen=${face.webpBase64.length} resend=verified")
                        btSender?.sendFace(tid, face.webpBase64)
                        synchronized(verifiedFaces) {
                            verified.thumbnailBase64 = face.thumbnailBase64
                            verified.thumbnailWidth = face.thumbnailWidth
                        }
                        synchronized(pendingFaces) {
                            if (pending != null) {
                                pending.lastSentArea = area
                                pending.bestArea = area
                                pending.lastSeenFrame = frameCount
                                pending.thumbnailBase64 = face.thumbnailBase64
                                pending.thumbnailWidth = face.thumbnailWidth
                            } else {
                                pendingFaces.add(PendingFace(tid, face.thumbnailBase64, face.thumbnailWidth, area, area, now, frameCount))
                            }
                        }
                    } else {
                        synchronized(pendingFaces) { pending?.lastSeenFrame = frameCount }
                    }
                    continue
                }

                // Check if in pending
                val pending = synchronized(pendingFaces) {
                    pendingFaces.find { it.trackingId == tid }
                }

                if (pending != null) {
                    synchronized(pendingFaces) {
                        pending.lastSeenFrame = frameCount
                        if (area > pending.bestArea) {
                            pending.thumbnailBase64 = face.thumbnailBase64
                            pending.thumbnailWidth = face.thumbnailWidth
                            pending.bestArea = area
                        }
                        // Re-send if area improved significantly
                        if (area > (pending.lastSentArea * RESEND_AREA_RATIO).toInt()) {
                            log("REID_SEND tid=$tid area=$area imgLen=${face.webpBase64.length} resend=pending")
                            btSender?.sendFace(tid, face.webpBase64)
                            pending.lastSentArea = area
                        }
                    }
                } else {
                    // New face -- send via BT and add to pending
                    log("REID_SEND tid=$tid area=$area imgLen=${face.webpBase64.length} new=true")
                    btSender?.sendFace(tid, face.webpBase64)
                    synchronized(pendingFaces) {
                        pendingFaces.add(PendingFace(tid, face.thumbnailBase64, face.thumbnailWidth, area, area, now, frameCount))
                    }
                }
            }

            // Clean stale pending faces
            synchronized(pendingFaces) {
                pendingFaces.removeAll { p ->
                    val timedOut = (now - p.sentAtMs) > PENDING_TIMEOUT_MS
                    val notSeen = (frameCount - p.lastSeenFrame) > STALE_FRAME_THRESHOLD
                    timedOut || notSeen
                }
            }

            val pendingCount = synchronized(pendingFaces) { pendingFaces.size }
            val verifiedCount = synchronized(verifiedFaces) { verifiedFaces.size }
            uiCallback?.onStatsUpdated(frameCount, faces.size, fps, pendingCount, verifiedCount)
            emitUi()
        }

        override fun onNoFaces(frameCount: Int, fps: Double) {
            val pendingCount = synchronized(pendingFaces) { pendingFaces.size }
            val verifiedCount = synchronized(verifiedFaces) { verifiedFaces.size }
            uiCallback?.onStatsUpdated(frameCount, 0, fps, pendingCount, verifiedCount)
        }

        override fun onStatusChanged(status: String) {
            uiCallback?.onStatusChanged(status)
        }
    }

    private fun emitUi() {
        val verified = synchronized(verifiedFaces) { verifiedFaces.toList() }
        val pendingCount = synchronized(pendingFaces) { pendingFaces.size }
        uiCallback?.onFacesUpdated(verified, pendingCount)
    }

    private fun log(msg: String) {
        remoteLog?.invoke("[$TAG] $msg")
    }
}
