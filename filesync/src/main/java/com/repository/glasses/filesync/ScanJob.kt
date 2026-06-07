package com.repository.glasses.filesync

import android.os.Environment
import android.util.Log
import com.repository.glasses.tracing.GT
import java.io.File

/**
 * Boot-time reconciliation between the persisted manifest and the actual /sdcard/DCIM/Repository/
 * directory. Adds new files (sha256'd on the spot), drops missing ones, preserves unchanged entries.
 */
object ScanJob {

    private const val TAG = "FSync:Scan"

    val rootDir: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Repository")

    fun ensureRoot(): File {
        val r = rootDir
        if (!r.exists()) r.mkdirs()
        return r
    }

    /**
     * Reconciles `manifest` against on-disk state. Mutates in place. Returns a summary string for logs.
     */
    fun reconcile(manifest: Manifest): String = GT.section("fs.scan.reconcile") {
        val startMs = System.currentTimeMillis()
        Log.i(TAG, "reconcile: enter")
        val root = ensureRoot()
        val onDisk = root.listFiles { f -> f.isFile && f.name.isMediaFile() }?.toList() ?: emptyList()
        val onDiskRelPaths = onDisk.map { it.name }.toSet()

        // 1) drop manifest entries whose backing file is gone
        val dropped = manifest.snapshot().filter { it.relPath !in onDiskRelPaths }
        for (d in dropped) manifest.remove(d.id)

        // 2) add / refresh entries for on-disk files
        val existingByRel = manifest.snapshot().associateBy { it.relPath }
        var added = 0
        var refreshed = 0
        for (f in onDisk) {
            val prior = existingByRel[f.name]
            if (prior != null && prior.size == f.length() && prior.mtime == f.lastModified()) continue
            val sha = try { Sha256.ofFile(f) } catch (e: Exception) {
                Log.w(TAG, "sha256 failed for ${f.name}: ${e.message}"); continue
            }
            val kind = if (f.name.startsWith("VID_")) "video" else "photo"
            manifest.upsert(root, f.absolutePath, sha, f.length(), f.lastModified(), kind)
            if (prior == null) added++ else refreshed++
        }
        val durMs = System.currentTimeMillis() - startMs
        val msg = "reconcile: exit added=$added refreshed=$refreshed dropped=${dropped.size} onDisk=${onDisk.size} total=${manifest.snapshot().size} durMs=$durMs"
        Log.i(TAG, msg)
        return msg
    }

    private fun String.isMediaFile(): Boolean =
        endsWith(".jpg", ignoreCase = true) ||
        endsWith(".jpeg", ignoreCase = true) ||
        endsWith(".mp4", ignoreCase = true)
}
