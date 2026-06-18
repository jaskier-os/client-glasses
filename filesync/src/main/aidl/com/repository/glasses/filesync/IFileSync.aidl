// IFileSync.aidl
package com.repository.glasses.filesync;

import com.repository.glasses.filesync.IFileSyncCallback;

interface IFileSync {
    void registerCallback(in IFileSyncCallback cb);
    void unregisterCallback(in IFileSyncCallback cb);

    /** hex sha256 over the sorted manifest. Two peers with equal hash are in sync. */
    String getStateHash();

    /** [{id, relPath, sha256, size, mtime}, ...] */
    String getManifestJson();

    /**
     * Create WiFi Direct group (glasses is group owner) and start HTTP server.
     * Returns JSON {ssid, passphrase, ip, port, deviceAddress}.
     * Idempotent: if already serving, returns current details (does NOT recreate group).
     */
    String openWifiDirectForSync();

    /** Tear down WiFi Direct group + stop HTTP server. Safe to call when IDLE. */
    void closeWifiDirect();

    /**
     * Authoritative delete of a manifest entry + its backing file.
     * Returns false if the file is currently being served over HTTP (phone should retry after CLOSE_WIFI).
     */
    boolean deleteFile(String fileId);

    /** Phone reports successful pull. Currently informational (reserved for future TTL/rotation). */
    void ack(String fileId);

    /**
     * Gate the sideload HTTP routes (POST /sideload/upload|exec|cleanup). When false the
     * routes return 403 and the staging dir (/data/local/tmp/sideload) is wiped immediately.
     * Set by the listener app from GlassesConfig.sideloadingEnabled.
     */
    void setSideloadEnabled(boolean enabled);
}
