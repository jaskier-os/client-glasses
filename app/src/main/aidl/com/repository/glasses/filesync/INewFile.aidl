// INewFile.aidl
package com.repository.glasses.filesync;

interface INewFile {
    boolean notifyNew(String absPath, String sha256, long sizeBytes, String kind);
}
