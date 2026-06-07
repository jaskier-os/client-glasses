package com.repository.glasses.filesync

import android.app.Application
import android.util.Log

class FileSyncApp : Application() {

    companion object {
        private const val TAG = "FileSync"
    }

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL ${thread.name}: ${throwable.message}\n${throwable.stackTraceToString()}")
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "FileSyncApp created")
    }
}
