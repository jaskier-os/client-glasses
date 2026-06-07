package com.repository.glasses.capture

import android.app.Application
import android.util.Log

class CaptureApp : Application() {

    companion object {
        private const val TAG = "Capture"
    }

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL ${thread.name}: ${throwable.message}\n${throwable.stackTraceToString()}")
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "CaptureApp created")
    }
}
