// IFileSyncCallback.aidl
package com.repository.glasses.filesync;

interface IFileSyncCallback {
    /** Fired whenever the manifest changes (new file, deleted file, boot rescan). */
    void onManifestChanged(String newStateHash);

    /** WiFi Direct group formed and HTTP server up. detailsJson matches openWifiDirectForSync return. */
    void onWifiDirectReady(String detailsJson);

    /** WiFi Direct torn down. */
    void onWifiDirectClosed();

    /** WiFi Direct could not be opened for a concrete reason (e.g. "wifi_disabled",
     *  "p2p_disable_timeout", "group_formation_timeout", "create_group_failed").
     *  Callers must NOT interpret this as a transient close -- retrying hot will
     *  produce the same error. Fix the underlying condition first. */
    void onWifiDirectError(String reason);

    /** Confirmation of deleteFile(). */
    void onFileDeleted(String fileId);
}
