// ICaptureCallback.aidl
package com.repository.glasses.capture;

interface ICaptureCallback {
    void onPhotoTaken(String absPath, long sizeBytes);
    void onVideoStarted(String absPath);
    void onVideoPaused(String absPath);
    void onVideoResumed(String absPath);
    void onVideoStopped(String absPath, long durationMs, long sizeBytes);
    void onCaptureError(int code, String msg);
}
