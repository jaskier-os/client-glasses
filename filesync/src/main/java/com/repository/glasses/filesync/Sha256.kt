package com.repository.glasses.filesync

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

private const val TAG = "FSync:Hash"

object Sha256 {
    fun ofFile(file: File): String {
        val startMs = System.currentTimeMillis()
        Log.i(TAG, "ofFile: enter path=${file.absolutePath} size=${file.length()}")
        val md = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        FileInputStream(file).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
                totalBytes += n
            }
        }
        val hex = md.digest().toHex()
        val durMs = System.currentTimeMillis() - startMs
        Log.i(TAG, "ofFile: exit path=${file.name} bytes=$totalBytes durMs=$durMs")
        return hex
    }

    fun ofString(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(s.toByteArray(Charsets.UTF_8))
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(this.size * 2)
        for (b in this) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
