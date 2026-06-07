package com.repository.glasses.capture

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileNamer {
    private val FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    val rootDir: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Repository")

    fun ensureRoot(): File {
        val r = rootDir
        if (!r.exists()) r.mkdirs()
        return r
    }

    fun photoFile(now: Long = System.currentTimeMillis()): File =
        File(ensureRoot(), "IMG_${FMT.format(Date(now))}.jpg")

    fun videoFile(now: Long = System.currentTimeMillis()): File =
        File(ensureRoot(), "VID_${FMT.format(Date(now))}.mp4")
}
