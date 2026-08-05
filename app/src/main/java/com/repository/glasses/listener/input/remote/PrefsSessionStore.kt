package com.repository.glasses.listener.input.remote

import android.content.Context
import android.content.SharedPreferences

/**
 * [SessionStore] backed by SharedPreferences, so replay defence survives process death and reboot.
 *
 * Writes use `commit()`, not `apply()`. `apply()` returns before the value reaches disk, which would
 * leave exactly the crash window this store exists to close. The cost only lands on session adoption
 * and on the bounded sequence-reservation cadence, never per event.
 */
class PrefsSessionStore(context: Context) : SessionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun highestSid(sourceId: String): Long =
        prefs.getLong(sidKey(sourceId), SessionStore.NO_SID)

    override fun seqFloor(sourceId: String): Long = prefs.getLong(seqKey(sourceId), 0L)

    override fun adoptSession(sourceId: String, sid: Long, seqFloor: Long) {
        prefs.edit()
            .putLong(sidKey(sourceId), sid)
            .putLong(seqKey(sourceId), seqFloor)
            .commit()
    }

    override fun reserveSeq(sourceId: String, floor: Long) {
        if (RemoteInputRouter.seqDiff(floor, seqFloor(sourceId)) <= 0) return
        prefs.edit().putLong(seqKey(sourceId), floor).commit()
    }

    override fun forget(sourceId: String) {
        prefs.edit().remove(sidKey(sourceId)).remove(seqKey(sourceId)).commit()
    }

    private fun sidKey(sourceId: String) = "sid_$sourceId"
    private fun seqKey(sourceId: String) = "seq_$sourceId"

    companion object {
        private const val PREFS_NAME = "remote_input_sessions"
    }
}
