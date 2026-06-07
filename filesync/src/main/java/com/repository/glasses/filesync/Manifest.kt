package com.repository.glasses.filesync

import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * In-memory manifest keyed by fileId. Thread safety is the caller's responsibility
 * (FileSyncService holds a Mutex around all Manifest mutations).
 */
class Manifest {

    private val entries = LinkedHashMap<String, FileEntry>()

    fun snapshot(): List<FileEntry> = entries.values.toList()

    fun get(id: String): FileEntry? = entries[id]

    fun findByAbsPath(rootDir: File, absPath: String): FileEntry? {
        val rel = toRelPath(rootDir, absPath) ?: return null
        return entries.values.firstOrNull { it.relPath == rel }
    }

    /**
     * Upserts by absolute path + content. If a prior entry with the same relPath exists,
     * its id is preserved and content fields are updated. Returns the entry actually stored.
     */
    fun upsert(
        rootDir: File,
        absPath: String,
        sha256: String,
        size: Long,
        mtime: Long,
        kind: String,
    ): FileEntry {
        val rel = toRelPath(rootDir, absPath)
            ?: throw IllegalArgumentException("path outside root: $absPath")
        val existing = entries.values.firstOrNull { it.relPath == rel }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val e = FileEntry(id = id, relPath = rel, sha256 = sha256, size = size, mtime = mtime, kind = kind)
        entries[id] = e
        return e
    }

    fun remove(id: String): FileEntry? = entries.remove(id)

    fun clear() { entries.clear() }

    fun replaceAll(fresh: Collection<FileEntry>) {
        entries.clear()
        for (e in fresh) entries[e.id] = e
    }

    /** Hex sha256 over sorted "id\tsha256\tsize\n" lines -- deterministic under add/remove. */
    fun stateHash(): String {
        if (entries.isEmpty()) return Sha256.ofString("")
        val sorted = entries.values.sortedBy { it.id }
        val sb = StringBuilder()
        for (e in sorted) {
            sb.append(e.id).append('\t').append(e.sha256).append('\t').append(e.size).append('\n')
        }
        return Sha256.ofString(sb.toString())
    }

    fun toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (e in entries.values) arr.put(e.toJson())
        return arr
    }

    private fun toRelPath(root: File, absPath: String): String? {
        val rootPath = root.absolutePath
        if (!absPath.startsWith(rootPath)) return null
        var rel = absPath.substring(rootPath.length)
        if (rel.startsWith(File.separator)) rel = rel.substring(1)
        return rel
    }
}
