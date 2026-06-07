// IFileSyncCallback.aidl
package com.repository.glasses.filesync;

interface IFileSyncCallback {
    void onManifestChanged(String newStateHash);
    void onWifiDirectReady(String detailsJson);
    void onWifiDirectClosed();
    void onWifiDirectError(String reason);
    void onFileDeleted(String fileId);
}
