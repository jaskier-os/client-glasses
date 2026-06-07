package com.repository.glasses.listener.bt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-AG contact cache: number -> display name. Used to surface caller names on
 * incoming HFP calls (HFP itself only carries the number).
 *
 * Storage: filesDir/contacts/<agMac>.json plus a sibling .meta with the source hash.
 * On AG reconnect the phone announces a hash; if it differs (or no cache exists)
 * the glasses request the full list and overwrite the cache.
 */
class ContactsCache(private val ctx: Context) {

    private val dir: File by lazy {
        File(ctx.filesDir, "contacts").apply { mkdirs() }
    }

    @Volatile private var currentAgMac: String = ""
    private val map = ConcurrentHashMap<String, String>() // normalizedNumber -> displayName

    /**
     * Compare on-disk hash against the AG-provided hash. If different (or cache
     * missing), the caller should request a full sync. Returns true if cache is
     * already up to date.
     */
    @Synchronized
    fun isFresh(agMac: String, agHash: String): Boolean {
        val meta = metaFile(agMac)
        val have = if (meta.exists()) meta.readText().trim() else ""
        val ok = have.isNotEmpty() && have == agHash
        if (ok && agMac != currentAgMac) loadIntoMemory(agMac)
        return ok
    }

    /** Replace the cache for [agMac] with [json] (an array of {n,d} objects). */
    @Synchronized
    fun replace(agMac: String, agHash: String, json: String) {
        val parsed = parse(json)
        listFile(agMac).writeText(json)
        metaFile(agMac).writeText(agHash)
        currentAgMac = agMac
        map.clear()
        map.putAll(parsed)
    }

    /** Look up a caller-ID display name for [rawNumber]. Empty if not found. */
    fun lookup(rawNumber: String): String {
        if (rawNumber.isEmpty()) return ""
        val n = normalize(rawNumber)
        if (n.isEmpty()) return ""
        // Exact match first.
        map[n]?.let { return it }
        // Suffix match (handles +country-code vs national format).
        val tail = if (n.length >= 7) n.takeLast(7) else n
        for ((k, v) in map) {
            if (k.endsWith(tail)) return v
        }
        return ""
    }

    /** Pre-load the cache for the given AG into memory if it exists. */
    @Synchronized
    fun loadIntoMemory(agMac: String) {
        currentAgMac = agMac
        map.clear()
        val f = listFile(agMac)
        if (!f.exists()) return
        try {
            map.putAll(parse(f.readText()))
        } catch (_: Exception) {}
    }

    private fun parse(json: String): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val n = o.optString("n").orEmpty()
                val d = o.optString("d").orEmpty()
                if (n.isNotEmpty() && d.isNotEmpty()) out[n] = d
            }
        } catch (_: Exception) {}
        return out
    }

    private fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for ((i, ch) in raw.withIndex()) {
            if (ch == '+' && i == 0) sb.append('+')
            else if (ch in '0'..'9') sb.append(ch)
        }
        return sb.toString()
    }

    private fun safeKey(agMac: String): String = agMac.replace(":", "").replace("/", "_")
    private fun listFile(agMac: String) = File(dir, "${safeKey(agMac)}.json")
    private fun metaFile(agMac: String) = File(dir, "${safeKey(agMac)}.meta")
}
