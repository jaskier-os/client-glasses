package com.repository.glasses.filesync

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the manifest as JSON next to the media directory.
 * File: <root>/.filesync_manifest.json
 */
class ManifestStore(private val rootDir: File) {

    companion object {
        private const val TAG = "ManifestStore"
        private const val FILE_NAME = ".filesync_manifest.json"
    }

    private val file: File get() = File(rootDir, FILE_NAME)

    fun load(): List<FileEntry> {
        val f = file
        if (!f.exists()) return emptyList()
        return try {
            val text = f.readText(Charsets.UTF_8)
            val root = JSONObject(text)
            val arr = root.getJSONArray("entries")
            val out = ArrayList<FileEntry>(arr.length())
            for (i in 0 until arr.length()) out.add(FileEntry.fromJson(arr.getJSONObject(i)))
            out
        } catch (e: Exception) {
            Log.w(TAG, "load failed, starting empty: ${e.message}")
            emptyList()
        }
    }

    fun save(entries: Collection<FileEntry>) {
        val arr = JSONArray()
        for (e in entries) arr.put(e.toJson())
        val root = JSONObject().apply {
            put("version", 1)
            put("entries", arr)
        }
        val tmp = File(rootDir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString(2), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
