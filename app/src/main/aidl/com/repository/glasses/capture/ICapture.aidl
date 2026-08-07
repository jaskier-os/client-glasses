// ICapture.aidl
package com.repository.glasses.capture;

import com.repository.glasses.capture.ICaptureCallback;

interface ICapture {
    void registerCallback(in ICaptureCallback cb);
    void unregisterCallback(in ICaptureCallback cb);

    /** Open camera + capture session + run 3A warmup so the next takePhoto()
     *  skips cold-open latency (~3s on Rokid HAL). Idempotent. Pool stays
     *  hot for WARM_IDLE_MS after each call. Call on bind + on a periodic
     *  ping to keep first-shot latency under 3s. */
    void warmUp();

    /** Take a single photo. No-op if currently recording (recording owns camera). */
    void takePhoto();

    /** Start video recording. No-op if already recording. */
    void startVideo();

    /** Toggle pause/resume. Only valid while recording. */
    void togglePauseVideo();

    /** Stop recording (finalizes file). No-op if not recording. */
    void stopVideo();

    boolean isRecording();
    boolean isPaused();

    /** Authoritative recording state derived from the live camera session
     *  (recorder surface present), NOT the VideoRecorder boolean. Use this for
     *  the FN-button toggle so a desynced/wedged isRecording()==false cannot
     *  cause a second startVideo() instead of a stop. */
    boolean isRecordingActive();

    /** Capture ONE correctly-exposed still for ReID using the known-good RAW
     *  burst -> demosaic recipe (RawStillCapturer), then deliver the upright
     *  JPEG to [cb] via onFrame(jpeg, w, h, rotationDeg=0, frameId). Rotation is
     *  baked into the pixels, so rotationDeg is always 0 (consumers must NOT
     *  rotate again). On failure, [cb].onCaptureError is invoked. The listener
     *  drives this PERIODICALLY; the capture serializes through CameraSession's
     *  exclusive device borrow so it never races video/photo. */
    void captureReidFrame(in ICaptureCallback cb);

    /** Run the SCRFD-10G face detector (Hexagon V73 NPU) on a ReID JPEG and
     *  return face boxes. The capture process hosts the QNN HTP detector because
     *  it is a /data/app install whose namespace can reach the CDSP (libcdsprpc),
     *  unlike the privileged listener. Returns a flat int[]: [count, then per
     *  face x0,y0,x1,y1] in JPEG pixel coordinates. Returns [0] (no faces) if the
     *  NPU is unavailable, so the listener can fall back to its ML Kit CPU path.
     *  Synchronous (~11ms on HTP); the listener calls this off its worker thread. */
    int[] detectFaces(in byte[] jpeg);

    /** Transcribe ONE complete utterance with the on-glasses GigaAM RNNT model
     *  (conformer encoder w8a16 on the Hexagon V73 NPU, RNNT decoder+joint on CPU).
     *  Hosted here for the same reason as detectFaces: only this process's linker
     *  namespace reaches the CDSP via libcdsprpc.
     *
     *  @param pcm16leMono16k little-endian int16 mono 16 kHz PCM, ONE utterance, at
     *         most 384000 bytes (12 s). Longer input is refused with null rather
     *         than truncated. Capture splits it into 5 s encoder windows itself and
     *         banks the text, so the caller sees one call and one string.
     *  @param lang BCP-47-ish tag; only Russian is supported. Anything else -> null.
     *  @param utteranceId caller-allocated monotonic id, echoed in logs so a late
     *         result from a timed-out call can be correlated and discarded.
     *  @return the final transcript; "" when the model ran and heard nothing (an
     *         EXPLICIT empty final -- the caller's blank-is-cancel contract depends
     *         on it being distinguishable from failure); null when local STT is
     *         UNAVAILABLE (model missing, init failed, wrong language, payload too
     *         large, NPU busy) and the caller must fall back to the remote path.
     *  Synchronous, ~1.0-1.6 s for a 5 s utterance. MUST be called off the caller's
     *  main thread. */
    String transcribeUtterance(in byte[] pcm16leMono16k, String lang, long utteranceId);

    /** True when the context binary is present and validated against the model
     *  manifest, i.e. a transcribeUtterance call would attempt local inference.
     *  Cheap: does NOT load the model. */
    boolean isSttAvailable();

    /** Hint that an utterance is imminent: begin loading the encoder context on a
     *  background thread if it is not resident. Cold load is ~21 s, so without this
     *  the first utterance after a cold start always falls back to remote.
     *  Idempotent, non-blocking. No-op while video owns the NPU. */
    oneway void prepareStt();

    /** Release the encoder context and the ONNX decoder now. Idempotent. A
     *  transcribe already in flight completes first. reason is logged. */
    oneway void releaseStt(String reason);

    /** Start the silent rPPG YUV stream + per-frame ROI pipeline; samples arrive via
     *  ICaptureCallback.onRppgSamples. No-op if already running or if recording/photo
     *  owns the camera (the stream yields). */
    void startRppg();
    /** Stop the rPPG stream. */
    void stopRppg();
}
