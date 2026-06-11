package com.repository.glasses.listener.power

import android.util.Log
import java.io.File

object PowerDaemonControl {
    private const val TAG = "PowerDaemonControl"

    // Both this app (UID u0_a*) and the daemon (root) can read+write here.
    // /data/local/tmp/ is shell-only on stock Android 12, so we cannot use
    // it from the app even though that would be the more conventional spot.
    private const val CONF_PATH = "/data/local/diy-overlay/glasses-power.conf"
    private const val LOCK_PATH = "/data/local/diy-overlay/glasses-power-daemon.lock"
    private const val DAEMON_NAME = "glasses-power-daemon"

    // Linux truncates /proc/<pid>/comm to 15 chars (TASK_COMM_LEN-1). The
    // first 15 chars of "glasses-power-daemon" is what we'll see in comm,
    // and we use it to confirm the PID-reuse case (the kernel may have
    // reassigned a PID we read from a stale lockfile to an unrelated app).
    private const val DAEMON_COMM = "glasses-power-d"

    fun writeConfig(
        screenTimeoutSec: Int,
        powerTimeoutMin: Int,
        foldHallNode: String = "/sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/hall",
        inputDevices: String = "/dev/input/event0,/dev/input/event1",
    ) {
        val body = buildString {
            appendLine("# auto-written by PowerDaemonControl")
            appendLine("screen_timeout_s=$screenTimeoutSec")
            appendLine("power_timeout_min=$powerTimeoutMin")
            appendLine("fold_hall_node=$foldHallNode")
            appendLine("input_devices=$inputDevices")
        }
        try {
            // Ensure parent dir exists. On a fresh reflash the daemon may
            // not have run yet to create /data/local/diy-overlay/, and the
            // app must not silently lose the first config write.
            val conf = File(CONF_PATH)
            conf.parentFile?.mkdirs()
            // Unlink any existing file before recreating. writeText() opens with
            // O_TRUNC, which fails with EACCES when the existing conf is owned by
            // a STALE app UID (the listener's UID changes across reinstalls, e.g.
            // u0_a18 -> u0_a28, and the old file is mode 0600). The diy-overlay
            // dir is 0777, so we can always unlink + recreate even when we can't
            // truncate-in-place. Without this, the first post-reinstall config
            // push silently fails and the daemon keeps its stale defaults.
            conf.delete()
            conf.writeText(body)
            Log.i(TAG, "wrote $CONF_PATH ($screenTimeoutSec s, $powerTimeoutMin min)")
        } catch (e: Exception) {
            Log.w(TAG, "failed to write $CONF_PATH: ${e.message}")
            return
        }
        sighupDaemon()
    }

    // Inotify on cfg_dir picks up CLOSE_WRITE from the writeText() above, so
    // SIGHUP is belt-and-braces. We still try it because on a freshly booted
    // device the daemon may have started before the cfg_dir existed (then
    // inotify watch was never armed); SIGHUP forces a reload regardless.
    private fun sighupDaemon() {
        val pid = findDaemonPid()
        if (pid <= 0) {
            Log.i(TAG, "daemon not running, config will be picked up on next start")
            return
        }
        try {
            Runtime.getRuntime().exec(arrayOf("/system/bin/kill", "-HUP", pid.toString())).waitFor()
            Log.i(TAG, "SIGHUP to pid=$pid")
        } catch (e: Exception) {
            Log.w(TAG, "sighup failed: ${e.message}")
        }
    }

    // pidof(1) on Android matches /proc/<pid>/cmdline's basename. That's
    // reliable when the daemon was launched via its full path, but the
    // lockfile is the authoritative source -- the daemon writes its own PID
    // there under flock, so it's correct even if pidof can't find it (e.g.
    // when the binary lives at a non-standard path or comm is truncated).
    private fun findDaemonPid(): Int {
        readPidFromLockfile().let { if (it > 0 && isOurDaemon(it)) return it }
        try {
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/pidof", DAEMON_NAME))
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor()
            if (out.isNotEmpty()) {
                // pidof can return multiple PIDs space-separated; take the first.
                val pid = out.split(' ', '\n').first().toIntOrNull() ?: 0
                if (pid > 0 && isOurDaemon(pid)) return pid
            }
        } catch (e: Exception) {
            Log.w(TAG, "pidof failed: ${e.message}")
        }
        return 0
    }

    private fun readPidFromLockfile(): Int = try {
        File(LOCK_PATH).readText().trim().toIntOrNull() ?: 0
    } catch (_: Exception) {
        0
    }

    // Confirms the PID currently runs our daemon binary, not an unrelated
    // process the kernel reassigned the PID to after the daemon exited.
    // Sending SIGHUP to a random app is harmless on Android (most apps
    // ignore it) but we still want to avoid the noise.
    private fun isOurDaemon(pid: Int): Boolean = try {
        val comm = File("/proc/$pid/comm").readText().trim()
        comm == DAEMON_COMM || comm == DAEMON_NAME
    } catch (_: Exception) {
        false
    }
}
