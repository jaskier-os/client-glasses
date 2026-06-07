// INewFile.aidl
package com.repository.glasses.filesync;

/**
 * Exported binder used by the capture app after it finishes writing a file.
 * kind = "photo" | "video".
 * Returns true when the manifest entry is persisted (caller may unbind).
 */
interface INewFile {
    boolean notifyNew(String absPath, String sha256, long sizeBytes, String kind);
}
