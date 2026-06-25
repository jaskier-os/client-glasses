// ICaptureCallback.aidl
package com.repository.glasses.capture;

interface ICaptureCallback {
    void onPhotoTaken(String absPath, long sizeBytes);
    void onVideoStarted(String absPath);
    void onVideoPaused(String absPath);
    void onVideoResumed(String absPath);
    void onVideoStopped(String absPath, long durationMs, long sizeBytes);
    void onCaptureError(int code, String msg);

    /** Fired the instant the RAW burst is fully captured -- the scene no longer
     *  needs to be held still (demosaic/denoise run off the buffered frames).
     *  Drives the "photo taken, you can move now" checkmark in the preview UI.
     *  oneway: a UI hint, must never block the capture pipeline. */
    oneway void onShutterComplete();

    /** Shared-camera frame delivered to a frame subscriber (ReID). JPEG bytes
     *  from the capture-owned camera session. oneway: frame delivery must never
     *  block the capture pipeline, and dropped frames are acceptable (the
     *  subscriber self-throttles and the next frame supersedes). */
    oneway void onFrame(in byte[] jpeg, int width, int height, int rotationDeg, long frameId);

    /** rPPG forehead skin-color samples, ONE batch per processed frame. Arrays are
     *  parallel by face: trackingIds[i] has mean RGB at rgb[i*3..i*3+2]. tMs is the
     *  frame timestamp (elapsedRealtime ms). oneway: high-rate (~15/s), dropped
     *  batches acceptable (the next frame supersedes). */
    oneway void onRppgSamples(in long[] trackingIds, in float[] rgb, long tMs);
}
