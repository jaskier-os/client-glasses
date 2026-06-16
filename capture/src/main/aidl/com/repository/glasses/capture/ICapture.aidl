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
}
