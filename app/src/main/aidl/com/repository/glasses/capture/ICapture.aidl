// ICapture.aidl
package com.repository.glasses.capture;

import com.repository.glasses.capture.ICaptureCallback;

interface ICapture {
    void registerCallback(in ICaptureCallback cb);
    void unregisterCallback(in ICaptureCallback cb);

    void warmUp();
    void takePhoto();
    void startVideo();
    void togglePauseVideo();
    void stopVideo();

    boolean isRecording();
    boolean isPaused();
}
