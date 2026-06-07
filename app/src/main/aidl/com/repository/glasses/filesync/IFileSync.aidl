// IFileSync.aidl
package com.repository.glasses.filesync;

import com.repository.glasses.filesync.IFileSyncCallback;

interface IFileSync {
    void registerCallback(in IFileSyncCallback cb);
    void unregisterCallback(in IFileSyncCallback cb);

    String getStateHash();
    String getManifestJson();

    String openWifiDirectForSync();
    void closeWifiDirect();

    boolean deleteFile(String fileId);
    void ack(String fileId);
}
