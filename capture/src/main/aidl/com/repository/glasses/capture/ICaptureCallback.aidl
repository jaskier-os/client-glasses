// ICaptureCallback.aidl
package com.repository.glasses.capture;

interface ICaptureCallback {
    void onPhotoTaken(String absPath, long sizeBytes);
    void onVideoStarted(String absPath);
    void onVideoPaused(String absPath);
    void onVideoResumed(String absPath);
    void onVideoStopped(String absPath, long durationMs, long sizeBytes);
    void onCaptureError(int code, String msg);

    /** Shared-camera frame delivered to a frame subscriber (ReID). JPEG bytes
     *  from the capture-owned camera session. oneway: frame delivery must never
     *  block the capture pipeline, and dropped frames are acceptable (the
     *  subscriber self-throttles and the next frame supersedes). */
    oneway void onFrame(in byte[] jpeg, int width, int height, int rotationDeg, long frameId);
}
