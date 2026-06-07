package com.repository.glasses.filesync

import org.json.JSONObject

data class FileEntry(
    val id: String,
    val relPath: String,
    val sha256: String,
    val size: Long,
    val mtime: Long,
    val kind: String,   // "photo" | "video"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("relPath", relPath)
        put("sha256", sha256)
        put("size", size)
        put("mtime", mtime)
        put("kind", kind)
    }

    companion object {
        fun fromJson(o: JSONObject): FileEntry = FileEntry(
            id = o.getString("id"),
            relPath = o.getString("relPath"),
            sha256 = o.getString("sha256"),
            size = o.getLong("size"),
            mtime = o.getLong("mtime"),
            kind = o.optString("kind", "photo"),
        )
    }
}
