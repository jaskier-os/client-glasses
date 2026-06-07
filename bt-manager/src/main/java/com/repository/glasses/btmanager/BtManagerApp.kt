package com.repository.glasses.btmanager

import android.app.Application
import android.util.Log

class BtManagerApp : Application() {

    companion object {
        private const val TAG = "BtMgr:Svc"
    }

    override fun onCreate() {
        Log.i(TAG, "event=app.onCreate.start")
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "event=app.fatal thread=${thread.name} err=${throwable.message}\n${throwable.stackTraceToString()}")
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "event=app.onCreate.done")
    }
}
