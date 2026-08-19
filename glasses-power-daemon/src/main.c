// glasses-power-daemon
//
// Rokid AR glasses power-management daemon. Runs as a native arm64 process
// on the glasses under /data/local/tmp. Responsibilities:
//
//   1. Screen-off after <screen_timeout_s> of input idleness (no key events
//      on the watched /dev/input/eventN devices). Wakes the screen back on
//      when any key event arrives after lock.
//   2. Power-off after <power_timeout_s> of continuous "folded" state
//      (hall-sensor / SW_LID). Unfolding disarms the timer.
//
// Configuration is read from a key=value file (default
// /data/local/diy-overlay/glasses-power.conf). SIGHUP or inotify on the file
// triggers a reload with a 30-second safety window during which no
// power-off actions fire.
//
// POSIX only, no external deps beyond linux/input.h.

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <dirent.h>
#include <libgen.h>
#include <sys/file.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/system_properties.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <linux/rtc.h>

// ATRACE shim: write directly to ftrace's trace_marker so we don't depend on
// libcutils gating ATRACE_TAG_APP via debug.atrace.tags.enableflags. The NDK
// <android/trace.h> path silently no-ops on this device because the working
// atrace categories (am/view/gfx/input/binder_driver) don't enable the APP
// tag, and adding the "app" category triggers the atrace-silently-disables-
// everything bug. Same workaround pattern as touchpad-daemon. Whenever ANY
// ftrace event is active (always true under the baseline perfetto config),
// trace_marker writes show up as slices owned by the writer process.
static int pwr_marker_fd = -1;
static void pwr_marker_init(void) {
    pwr_marker_fd = open("/sys/kernel/tracing/trace_marker", O_WRONLY | O_CLOEXEC);
    if (pwr_marker_fd < 0) {
        pwr_marker_fd = open("/sys/kernel/debug/tracing/trace_marker", O_WRONLY | O_CLOEXEC);
    }
}
static inline void pwr_begin(const char *name) {
    if (pwr_marker_fd < 0) return;
    char buf[128];
    int n = snprintf(buf, sizeof(buf), "B|%d|%s", (int)getpid(), name);
    if (n > 0) (void)write(pwr_marker_fd, buf, (size_t)n);
}
static inline void pwr_end(void) {
    if (pwr_marker_fd < 0) return;
    (void)write(pwr_marker_fd, "E", 1);
}
#define PWR_TRACE_BEGIN(name) pwr_begin(name)
#define PWR_TRACE_END()       pwr_end()

#ifndef SW_LID
#define SW_LID 0x00
#endif

// Config, time-sync, and lock drop-files live in /data/local/diy-overlay/
// because it's the only path with mode 0777 that both this daemon (root)
// and the listener app (UID u0_a*) can write+read. /data/local/tmp/ is
// shell-only on stock Android 12, so the app cannot drop config there
// even though the daemon could. The lockfile is also kept here so the
// app can read the daemon PID for SIGHUP without needing root.
#define DEFAULT_CONFIG_PATH "/data/local/diy-overlay/glasses-power.conf"
#define DEFAULT_LOG_PATH    "/data/local/tmp/glasses-power-daemon.log"
#define DEFAULT_LOCK_PATH   "/data/local/diy-overlay/glasses-power-daemon.lock"
#define TIME_SYNC_FILE      "glasses-time.sync"
#define WIFI_REQ_FILE       "glasses-wifi.req"
#define MAX_INPUT_DEVICES   8
#define LOG_ROTATE_BYTES    (1024L * 1024L)
#define SAFETY_WINDOW_MS    30000LL
#define FOLD_DEBOUNCE_MS    3000LL
#define SUSPEND_DELAY_MS    (3LL * 60 * 1000)  // 3 minutes
#define LED_ARM_FLAG_FILE  "glasses-led-battery-arm"  // basename in cfg_dir
#define LED_REASSERT_MS    5000LL
#define KILL_SWITCH_PROP    "persist.glasses.power_daemon.disable"
// 2020-01-01. Below this an epoch is a powered-up-from-nothing default (this
// hardware's RTC comes back at 1970), not a real time. Used both to decide the
// system clock is trustworthy enough to push into the RTC, and to detect an RTC
// that needs re-syncing.
#define RTC_SANE_MIN_EPOCH  1577836800L
// How often the main loop re-checks a stale RTC (the boot check is useless if
// the system clock was also wrong then).
#define RTC_RECHECK_MS      (60LL * 1000)

struct Config {
    int  screen_timeout_s;
    int  power_timeout_s;
    char fold_hall_node[PATH_MAX];
    char input_devices[512];
};

static struct Config g_cfg;
static volatile sig_atomic_t g_reload = 0;
static volatile sig_atomic_t g_stop   = 0;

static long long last_activity_ms    = 0;
static int       screen_on           = 1;
static int       fold_folded         = 0;
static long long fold_change_ms         = 0;  // last time raw-state differed from debounced
static long long suspend_armed_until_ms = 0;  // 0 = not armed; fires 1s after fold -> freeze
static long long shutdown_armed_until_ms = 0; // 0 = not armed; fires power_timeout_s after fold -> shutdown
// Time of the current fold edge (0 = unfolded). The unplug re-arm derives the
// shutdown deadline from this so plug/unplug cycles can't defer power-off.
static long long fold_since_ms       = 0;
// One-shot guard for the unplug re-arm. The mp2724 `online` node flaps during
// trickle charge at high SoC; without this, each flicker re-arms and the arm is
// then thrown away by the suspend-loop USB guard 3 min later, forever.
static int       charge_rearm_latched = 0;
static long long config_loaded_ms    = 0;
static unsigned  reload_counter      = 0;

static long long rtc_check_last_ms  = 0;  // last periodic stale-RTC re-check
// Latched when RTC_SET_TIME returns EPERM (driver built without allow-set-time).
// Permanent for this build, so stop retrying. Harmless: alarms are relative.
static int       rtc_set_unsupported = 0;

static long long led_last_assert_ms = 0;
static int       led_active         = 0;  // 1 = we currently own the LED
static int       led_color_pct_band = -1; // last-applied band for dedup (0 red /1 both /2 green)

// -------------------- utility helpers --------------------

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + (long long)ts.tv_nsec / 1000000LL;
}

static void log_line(const char *fmt, ...) {
    char tsbuf[32];
    time_t t = time(NULL);
    struct tm tm_now;
    localtime_r(&t, &tm_now);
    strftime(tsbuf, sizeof(tsbuf), "%Y-%m-%d %H:%M:%S", &tm_now);
    fprintf(stderr, "%s %d ", tsbuf, (int)getpid());
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
}

static void rotate_log_if_big(const char *log_path) {
    struct stat st;
    if (stat(log_path, &st) != 0) return;
    if (st.st_size < LOG_ROTATE_BYTES) return;
    char rot[PATH_MAX];
    snprintf(rot, sizeof(rot), "%s.1", log_path);
    // Best-effort: unlink old rotated log, then rename current.
    unlink(rot);
    if (rename(log_path, rot) != 0) {
        // Not fatal -- just proceed and append to existing log.
    }
}

static void set_defaults(struct Config *c) {
    c->screen_timeout_s  = 300;
    c->power_timeout_s = 3600;
    strncpy(c->fold_hall_node,
            "/sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/hall",
            sizeof(c->fold_hall_node) - 1);
    c->fold_hall_node[sizeof(c->fold_hall_node) - 1] = '\0';
    strncpy(c->input_devices,
            "/dev/input/event0,/dev/input/event1",
            sizeof(c->input_devices) - 1);
    c->input_devices[sizeof(c->input_devices) - 1] = '\0';
}

// Trim leading/trailing whitespace in-place.
static char *trim(char *s) {
    while (*s == ' ' || *s == '\t' || *s == '\r' || *s == '\n') ++s;
    size_t n = strlen(s);
    while (n > 0) {
        char c = s[n - 1];
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            s[--n] = '\0';
        } else {
            break;
        }
    }
    return s;
}

// Parse key=value config. Always leaves out with forgiving defaults populated.
// Returns 0 on success, -1 on any parse/open error (defaults still in place).
static int parse_config(const char *path, struct Config *out) {
    set_defaults(out);
    FILE *f = fopen(path, "r");
    if (!f) {
        log_line("parse_config: cannot open %s: %s (using defaults)", path, strerror(errno));
        return -1;
    }
    char line[1024];
    int had_error = 0;
    while (fgets(line, sizeof(line), f)) {
        // Strip comments.
        char *hash = strchr(line, '#');
        if (hash) *hash = '\0';
        char *t = trim(line);
        if (*t == '\0') continue;
        char *eq = strchr(t, '=');
        if (!eq) { had_error = 1; continue; }
        *eq = '\0';
        char *k = trim(t);
        char *v = trim(eq + 1);
        if (strcmp(k, "screen_timeout_s") == 0) {
            out->screen_timeout_s = atoi(v);
        } else if (strcmp(k, "power_timeout_s") == 0) {
            out->power_timeout_s = atoi(v);
        } else if (strcmp(k, "power_timeout_min") == 0) {
            out->power_timeout_s = atoi(v) * 60;
        } else if (strcmp(k, "fold_hall_node") == 0) {
            strncpy(out->fold_hall_node, v, sizeof(out->fold_hall_node) - 1);
            out->fold_hall_node[sizeof(out->fold_hall_node) - 1] = '\0';
        } else if (strcmp(k, "input_devices") == 0) {
            strncpy(out->input_devices, v, sizeof(out->input_devices) - 1);
            out->input_devices[sizeof(out->input_devices) - 1] = '\0';
        } else {
            // Unknown key -- ignore silently.
        }
    }
    fclose(f);
    return had_error ? -1 : 0;
}

static int open_evdev(const char *path) {
    int fd = open(path, O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        log_line("open_evdev: %s: %s", path, strerror(errno));
        return -1;
    }
    return fd;
}

static int open_sysfs(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        log_line("open_sysfs: %s: %s", path, strerror(errno));
        return -1;
    }
    return fd;
}

// Read sysfs file into a small buffer and parse as int 0/1 (first digit).
static int read_sysfs_int(int fd) {
    if (lseek(fd, 0, SEEK_SET) < 0) return -1;
    char buf[32];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    if (n <= 0) return -1;
    buf[n] = '\0';
    for (ssize_t i = 0; i < n; ++i) {
        if (buf[i] == '0') return 0;
        if (buf[i] == '1') return 1;
    }
    return -1;
}

// Battery-indicator LED. Direct sysfs is the ONLY path that can light multiple
// channels at once (stock lights_ctrl sendEvent is single-channel). Root-only.
#define LED_RED_NODE   "/sys/class/leds/red/brightness"
#define LED_GREEN_NODE "/sys/class/leds/green/brightness"

// Write a single 0..255 brightness to one LED node. Best-effort; logs on error.
static void led_write_node(const char *node, int val) {
    if (val < 0) val = 0;
    if (val > 255) val = 255;
    int fd = open(node, O_WRONLY);
    if (fd < 0) { log_line("led: open %s failed: %s", node, strerror(errno)); return; }
    char buf[8];
    int n = snprintf(buf, sizeof(buf), "%d", val);
    if (write(fd, buf, (size_t)n) < 0)
        log_line("led: write %s=%d failed: %s", node, val, strerror(errno));
    close(fd);
}

// Set the red+green pair (blue/white left untouched -- owned by capture privacy
// light + BT events, never by us).
static void led_set_rg(int red, int green) {
    led_write_node(LED_RED_NODE, red);
    led_write_node(LED_GREEN_NODE, green);
}

#define BATT_CAPACITY_NODE "/sys/class/power_supply/battery/capacity"
#define BATT_CHARGER_ONLINE "/sys/class/power_supply/mp2724-charger/online"

// Read integer battery percent 0..100, or -1 on failure.
static int read_battery_pct(void) {
    int fd = open(BATT_CAPACITY_NODE, O_RDONLY);
    if (fd < 0) return -1;
    char buf[16] = {0};
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    int v = atoi(buf);
    if (v < 0) v = 0;
    if (v > 100) v = 100;
    return v;
}

// 1 = charger cable connected, 0 = not, -1 = unknown.
// Uses the charger `online` node (cable-present), NOT `status`. The mp2724
// `status` string flaps between "Charging" and "Not charging" every ~1s during
// trickle/maintenance at high SoC, which would strobe the LED. `online` stays 1
// for the whole time the cable is plugged in regardless of charge-current state.
static int read_is_charging(void) {
    int fd = open(BATT_CHARGER_ONLINE, O_RDONLY);
    if (fd < 0) return -1;
    char buf[8] = {0};
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    return (buf[0] == '1') ? 1 : 0;
}

// Apply the battery color for the given percent. >=45 green, 15-45 both, <15 red.
static void led_apply_battery(int pct) {
    if (pct >= 45)      led_set_rg(0, 255);    // green
    else if (pct >= 15) led_set_rg(255, 255);  // green+red (both dots)
    else                led_set_rg(255, 0);    // red
}

// Read the app-written arm flag (single char '1'/'0') from cfg_dir.
// Returns 1=arm, 0=disarm, -1=missing/unreadable (treated as disarm by caller).
static int read_led_arm_flag(const char *cfg_dir) {
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/%s", cfg_dir, LED_ARM_FLAG_FILE);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    char c = 0;
    ssize_t n = read(fd, &c, 1);
    close(fd);
    if (n <= 0) return -1;
    if (c == '1') return 1;
    if (c == '0') return 0;
    return -1;
}

// Compute band 0=red(<15) 1=both(15-45) 2=green(>=45) for dedup.
static int led_band_for(int pct) {
    if (pct >= 45) return 2;
    if (pct >= 15) return 1;
    return 0;
}

// Evaluate arm flag + charging, drive/clear LED. Called every loop iteration.
static void led_tick(const char *cfg_dir, long long now) {
    // If the listener app crashes with the flag left at '1', the LED stays lit
    // until the cable is pulled -- this is intentional: the daemon re-checks the
    // charger `online` node every tick, so unplug always clears it, and a crashed
    // app on a charger is a benign "LED stuck green on a table" state.
    int arm = read_led_arm_flag(cfg_dir);
    int charging = (arm == 1) ? read_is_charging() : 0;
    int want = (arm == 1 && charging == 1);

    if (!want) {
        if (led_active) {
            led_set_rg(0, 0);
            led_active = 0;
            led_color_pct_band = -1;
            log_line("led: cleared (arm=%d charging=%d)", arm, charging);
        }
        return;
    }
    // Armed + charging. Re-assert every LED_REASSERT_MS, or immediately on band change.
    int pct = read_battery_pct();
    if (pct < 0) return;
    int band = led_band_for(pct);
    int due = (now - led_last_assert_ms) >= LED_REASSERT_MS;
    if (!led_active || band != led_color_pct_band || due) {
        led_apply_battery(pct);
        led_last_assert_ms = now;
        if (!led_active || band != led_color_pct_band)
            log_line("led: battery pct=%d band=%d", pct, band);
        led_active = 1;
        led_color_pct_band = band;
    }
}

// Read fold state from the kernel PSoC driver's authoritative vendor property
// vendor.rkd.glasses.is_spread ("1"=spread=UNFOLDED, "0"=folded). This is the
// same source the listener app's FoldPoll trusts. Returns 1=folded, 0=unfolded,
// or -1 when the property is empty/unknown (so callers keep their prior state).
//
// Why not the raw hall sysfs node: on this worn+docked hardware the hall line
// reads 1 in the normal UNFOLDED-worn posture, so the daemon's old
// "hall==1 => folded" polarity got stuck folded, armed suspend, failed to
// freeze over USB, and re-broadcast spurious folded=true on every resume --
// which tore down A2DP audio. is_spread has correct, system-led polarity.
#define FOLD_SPREAD_PROP "vendor.rkd.glasses.is_spread"
static int read_fold_from_spread(void) {
    char val[PROP_VALUE_MAX] = {0};
    int n = __system_property_get(FOLD_SPREAD_PROP, val);
    if (n <= 0) return -1;
    if (val[0] == '1') return 0; // spread => unfolded
    if (val[0] == '0') return 1; // not spread => folded
    return -1;
}

// Fire-and-forget exec. Log the argv before forking.
static void run_cmd(const char *const *argv) {
    if (!argv || !argv[0]) return;
    char joined[512];
    size_t off = 0;
    joined[0] = '\0';
    for (int i = 0; argv[i] != NULL && i < 16; ++i) {
        int w = snprintf(joined + off, sizeof(joined) - off,
                         "%s%s", i == 0 ? "" : " ", argv[i]);
        if (w < 0 || (size_t)w >= sizeof(joined) - off) break;
        off += (size_t)w;
    }
    log_line("exec: %s", joined);
    pid_t pid = fork();
    if (pid < 0) {
        log_line("fork failed: %s", strerror(errno));
        return;
    }
    if (pid == 0) {
        // Child.
        execvp(argv[0], (char *const *)argv);
        _exit(127);
    }
    // Parent: child is reaped by the main-loop zombie reaper.
    (void)pid;
}

// Check kill switch via `getprop` (fork+exec, read stdout).
// Returns 1 if disabled, 0 otherwise.
static int kill_switch_active(void) {
    int pipefd[2];
    if (pipe(pipefd) != 0) return 0;
    pid_t pid = fork();
    if (pid < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return 0;
    }
    if (pid == 0) {
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        close(pipefd[1]);
        execlp("getprop", "getprop", KILL_SWITCH_PROP, (char *)NULL);
        _exit(127);
    }
    close(pipefd[1]);
    char buf[64] = {0};
    ssize_t n = read(pipefd[0], buf, sizeof(buf) - 1);
    close(pipefd[0]);
    int status;
    waitpid(pid, &status, 0);
    if (n <= 0) return 0;
    buf[n] = '\0';
    // Trim.
    for (ssize_t i = 0; i < n; ++i) {
        if (buf[i] == '\n' || buf[i] == '\r') { buf[i] = '\0'; break; }
    }
    return strcmp(buf, "1") == 0 ? 1 : 0;
}

// -------------------- signal handlers --------------------

static void on_hup(int sig) { (void)sig; g_reload = 1; }
static void on_term(int sig) { (void)sig; g_stop = 1; }

// Defined further down with the other RTC helpers; used by apply_time_sync.
static void sync_rtc_from_system(const char *reason);
static long long read_rtc_epoch(void);

// Apply a time+timezone sync dropped by the glasses app at <dir>/glasses-time.sync.
// File format: "<epochMillis>\n<tzId>\n". We call clock_settime(CLOCK_REALTIME)
// and set persist.sys.timezone via `setprop`. The file is consumed (removed)
// after application so we don't re-apply a stale snapshot on every reload.
static void apply_time_sync(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) {
        log_line("time-sync: open(%s) failed: %s", path, strerror(errno));
        return;
    }
    long long epoch_ms = 0;
    char tz[64] = {0};
    int ok_epoch = (fscanf(f, "%lld", &epoch_ms) == 1);
    int ok_tz = (fscanf(f, " %63s", tz) == 1);
    fclose(f);
    if (!ok_epoch || epoch_ms <= 0) {
        log_line("time-sync: parse failed at %s (epoch_ms=%lld)", path, epoch_ms);
        unlink(path);
        return;
    }
    struct timespec ts;
    ts.tv_sec  = (time_t)(epoch_ms / 1000LL);
    ts.tv_nsec = (long)((epoch_ms % 1000LL) * 1000000LL);
    if (clock_settime(CLOCK_REALTIME, &ts) != 0) {
        log_line("time-sync: clock_settime failed: %s", strerror(errno));
    } else {
        log_line("time-sync: clock_settime ok (epoch_ms=%lld)", epoch_ms);
        // Push the corrected time into the RTC hardware too. Setting only
        // CLOCK_REALTIME leaves the RTC at whatever it powered up with, and
        // every wake alarm we arm is expressed in the RTC's time base -- so a
        // stale RTC silently disables fold-shutdown.
        sync_rtc_from_system("time-sync");
    }
    if (ok_tz && tz[0] != '\0') {
        pid_t pid = fork();
        if (pid == 0) {
            execl("/system/bin/setprop", "setprop", "persist.sys.timezone", tz, (char*)NULL);
            _exit(127);
        } else if (pid > 0) {
            int st = 0;
            waitpid(pid, &st, 0);
            log_line("time-sync: setprop persist.sys.timezone=%s (exit=%d)", tz, WEXITSTATUS(st));
        } else {
            log_line("time-sync: fork for setprop failed: %s", strerror(errno));
        }
    }
    if (unlink(path) != 0) {
        log_line("time-sync: unlink(%s) failed: %s", path, strerror(errno));
    }
}

// Handle a WiFi enable/disable request dropped by the listener app.
// File contents: "1"=enable, "0"=disable. Our app can't toggle WiFi directly
// because Android 10+ requires platform-signed apps for WifiManager.setWifiEnabled,
// and `svc wifi enable` only works from shell uid. This daemon runs as root
// so it can run the same `svc` from init context.
static void apply_wifi_request(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) {
        log_line("wifi-req: open(%s) failed: %s", path, strerror(errno));
        return;
    }
    int want = -1;
    fscanf(f, "%d", &want);
    fclose(f);
    if (want != 0 && want != 1) {
        log_line("wifi-req: bad value (want=%d) at %s", want, path);
        unlink(path);
        return;
    }
    const char *verb = (want == 1) ? "enable" : "disable";
    pid_t pid = fork();
    if (pid == 0) {
        execl("/system/bin/svc", "svc", "wifi", verb, (char*)NULL);
        _exit(127);
    } else if (pid > 0) {
        int st = 0;
        waitpid(pid, &st, 0);
        log_line("wifi-req: svc wifi %s (exit=%d)", verb, WEXITSTATUS(st));
    } else {
        log_line("wifi-req: fork for svc failed: %s", strerror(errno));
    }
    if (unlink(path) != 0) {
        log_line("wifi-req: unlink(%s) failed: %s", path, strerror(errno));
    }
}

// -------------------- input device handling --------------------

// Split comma-separated list in out (max MAX_INPUT_DEVICES entries).
// Returns count. Writes paths into `paths_out[i]` (caller owns pointers into src).
static int split_input_devices(char *src, const char *paths_out[MAX_INPUT_DEVICES]) {
    int count = 0;
    char *p = src;
    while (*p && count < MAX_INPUT_DEVICES) {
        while (*p == ' ' || *p == '\t' || *p == ',') ++p;
        if (!*p) break;
        paths_out[count++] = p;
        while (*p && *p != ',') ++p;
        if (*p == ',') { *p = '\0'; ++p; }
    }
    return count;
}

// -------------------- main --------------------

static void screen_lock_broadcast(const char *reason) {
    // Deterministic lock-only via the app's ScreenOffAccessibilityService.
    // The service calls performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN), which
    // cannot accidentally wake a locked screen the way `input keyevent 26`
    // would.
    const char *argv[] = {
        "/system/bin/am", "broadcast",
        "-a", "com.repository.glasses.listener.ACTION_LOCK_SCREEN",
        "-p", "com.repository.glasses.listener",
        NULL
    };
    run_cmd(argv);
    log_line("screen lock broadcast (%s)", reason);
}

// Push the daemon's screen_timeout_s into Android's
// Settings.System.SCREEN_OFF_TIMEOUT (in ms) so PowerManager's display-sleep
// matches the daemon's idle-lock window. Without this, Android's stock
// timeout (often 5-15 s on this device) wins long before the daemon's
// ACTION_LOCK_SCREEN broadcast fires, defeating the user's configured value.
// We're root here, so `settings put system` succeeds without WRITE_SETTINGS.
static void apply_screen_off_timeout(int seconds) {
    if (seconds <= 0) return;
    char ms_buf[32];
    snprintf(ms_buf, sizeof(ms_buf), "%lld", (long long)seconds * 1000LL);
    const char *argv[] = {
        "/system/bin/settings", "put", "system",
        "screen_off_timeout", ms_buf,
        NULL
    };
    run_cmd(argv);
}

// Sync the daemon's power_timeout_s into Rokid's Settings.System
// "rkd_shutdown_timeout" (in minutes). RokidSysConfig's ShutdownSettings
// reads this value and arms its own fold-shutdown AlarmManager timer.
// Without this, RokidSysConfig uses a hardcoded 20-min default that races
// (and wins) against the daemon's own suspend-then-shutdown loop.
// Value 0 = disabled (no fold shutdown from SysCfg side).
static void apply_rkd_shutdown_timeout(int power_timeout_s) {
    // Convert seconds to minutes for the Rokid setting.
    // If our timeout is 0 (disabled), set rkd to 0 (disabled) too.
    // Otherwise add a 1-min margin so SysCfg never fires before the daemon.
    int minutes;
    if (power_timeout_s <= 0) {
        minutes = 0;
    } else {
        minutes = (power_timeout_s / 60) + 1;
    }
    char min_buf[16];
    snprintf(min_buf, sizeof(min_buf), "%d", minutes);
    const char *argv[] = {
        "/system/bin/settings", "put", "system",
        "rkd_shutdown_timeout", min_buf,
        NULL
    };
    run_cmd(argv);
    log_line("rkd_shutdown_timeout set to %d min (power_timeout_s=%d)", minutes, power_timeout_s);
}

// Packages that receive ACTION_FOLD_CHANGED. Each needs its OWN am invocation:
// `am broadcast -p <pkg>` sets Intent.setPackage(), which restricts delivery to
// receivers in that ONE package, so a single -p can never fan out. bt-manager is
// listed because its FoldGate must drop A2DP/HFP on fold -- while the SLIMbus TX
// port stays open the kernel refuses s2idle ("Abort: Some devices failed to
// suspend", btfm_num_ports_open: 1). Rokid's own ACTION_LEG_STATUS_CHANGED is not
// a reliable substitute: it comes from PsensorObserver, which stock firmware ships
// latched off via enforce_psensor.
static const char *const FOLD_BROADCAST_PKGS[] = {
    "com.repository.glasses.listener",
    "com.repository.glasses.btmanager",
};

static void fold_broadcast(int folded) {
    for (size_t i = 0;
         i < sizeof(FOLD_BROADCAST_PKGS) / sizeof(FOLD_BROADCAST_PKGS[0]);
         ++i) {
        const char *argv[] = {
            "/system/bin/am", "broadcast",
            "-a", "com.repository.glasses.listener.ACTION_FOLD_CHANGED",
            "-p", FOLD_BROADCAST_PKGS[i],
            "--ez", "folded", folded ? "true" : "false",
            NULL
        };
        run_cmd(argv);
    }
    log_line("fold broadcast folded=%d", folded);
}

#define CRASH_LOG_DIR   "/data/local/tmp/crash-logs"
#define TOMBSTONE_DIR   "/data/tombstones"
#define MARKER_FILE     CRASH_LOG_DIR "/.last_saved_ts"
#define MAX_CRASH_LOGS  10

static void save_tombstones(void) {
    mkdir(CRASH_LOG_DIR, 0755);

    // Read marker (last-saved epoch seconds)
    long long last_saved = 0;
    FILE *mf = fopen(MARKER_FILE, "r");
    if (mf) { fscanf(mf, "%lld", &last_saved); fclose(mf); }

    DIR *d = opendir(TOMBSTONE_DIR);
    if (!d) { log_line("tombstones: opendir failed: %s", strerror(errno)); return; }

    long long newest = last_saved;
    int saved = 0;
    struct dirent *ent;
    while ((ent = readdir(d)) != NULL) {
        if (strncmp(ent->d_name, "tombstone_", 10) != 0) continue;
        if (strstr(ent->d_name, ".pb")) continue;

        char src[PATH_MAX];
        snprintf(src, sizeof(src), "%s/%s", TOMBSTONE_DIR, ent->d_name);
        struct stat st;
        if (stat(src, &st) != 0) continue;
        long long mod = (long long)st.st_mtime;
        if (mod <= last_saved) continue;

        // Format timestamp for filename
        struct tm tm;
        localtime_r(&st.st_mtime, &tm);
        char ts[32];
        strftime(ts, sizeof(ts), "%Y-%m-%d_%H-%M-%S", &tm);

        char dst[PATH_MAX];
        snprintf(dst, sizeof(dst), "%s/%s_%s", CRASH_LOG_DIR, ts, ent->d_name);

        // Copy file
        int sfd = open(src, O_RDONLY);
        if (sfd < 0) continue;
        int dfd = open(dst, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (dfd < 0) { close(sfd); continue; }
        char buf[4096];
        ssize_t n;
        while ((n = read(sfd, buf, sizeof(buf))) > 0) write(dfd, buf, n);
        close(sfd);
        close(dfd);
        saved++;
        if (mod > newest) newest = mod;
    }
    closedir(d);

    if (newest > last_saved) {
        mf = fopen(MARKER_FILE, "w");
        if (mf) { fprintf(mf, "%lld\n", newest); fclose(mf); }
    }
    if (saved > 0) log_line("tombstones: saved %d crash logs", saved);

    // Prune old logs (keep MAX_CRASH_LOGS newest)
    d = opendir(CRASH_LOG_DIR);
    if (!d) return;
    struct { char name[256]; time_t mtime; } files[64];
    int count = 0;
    while ((ent = readdir(d)) != NULL && count < 64) {
        if (ent->d_name[0] == '.') continue;
        char path[PATH_MAX];
        snprintf(path, sizeof(path), "%s/%s", CRASH_LOG_DIR, ent->d_name);
        struct stat st;
        if (stat(path, &st) != 0) continue;
        strncpy(files[count].name, ent->d_name, 255);
        files[count].name[255] = '\0';
        files[count].mtime = st.st_mtime;
        count++;
    }
    closedir(d);
    // Sort by mtime descending (simple bubble)
    for (int i = 0; i < count - 1; i++)
        for (int j = i + 1; j < count; j++)
            if (files[j].mtime > files[i].mtime) {
                __typeof__(files[0]) tmp = files[i];
                files[i] = files[j];
                files[j] = tmp;
            }
    for (int i = MAX_CRASH_LOGS; i < count; i++) {
        char path[PATH_MAX];
        snprintf(path, sizeof(path), "%s/%s", CRASH_LOG_DIR, files[i].name);
        unlink(path);
    }
}

static void write_sysfs(const char *path, const char *val) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) { log_line("sysfs: open(%s) failed: %s", path, strerror(errno)); return; }
    write(fd, val, strlen(val));
    close(fd);
}

// Check whether a USB cable is connected via the MP2724 charger IC extcon.
// Returns 1 if USB VBUS is present, 0 otherwise.
#define USB_EXTCON_PATH "/sys/devices/platform/soc/a90000.i2c/i2c-1/1-003f/extcon/extcon4/state"
static int usb_cable_connected(void) {
    int fd = open(USB_EXTCON_PATH, O_RDONLY);
    if (fd < 0) return 0;  // can't read -> assume not connected
    char buf[64] = {0};
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    // Format: "USB=1\n" or "USB=0\n"
    return strstr(buf, "USB=1") != NULL ? 1 : 0;
}

static void power_off(void) {
    const char *argv[] = { "/system/bin/svc", "power", "shutdown", NULL };
    run_cmd(argv);
    log_line("power off after fold");
}

// Prepare the system for freeze. Call once before the suspend loop.
// IMPORTANT: we do NOT stop system_suspend HAL. Stopping it leaves the
// HAL in a broken state after restart (autosuspend_init fails with
// "Permission denied" on /sys/power/state), which causes system_server
// to crash with DEAD_OBJECT on the next disableAutoSuspend() HIDL call.
// system_suspend's wakeup_count thread only writes "mem" when
// PowerManager explicitly requests deep sleep, which doesn't happen
// during our fold-triggered freeze sequence.
static void suspend_prepare(void) {
    int fd;

    // Force DWC3 USB into runtime suspend (sets in_lpm=1).
    write_sysfs("/sys/devices/platform/soc/a600000.ssusb/power/autosuspend_delay_ms", "0");
    write_sysfs("/sys/devices/platform/soc/a600000.ssusb/power/control", "auto");
    usleep(500000);
    log_line("suspend: DWC3 autosuspend triggered");

    // Release kernel wakelocks
    FILE *wl = fopen("/sys/power/wake_lock", "r");
    if (wl) {
        char buf[256];
        if (fgets(buf, sizeof(buf), wl)) {
            char *tok = strtok(buf, " \t\n");
            while (tok) {
                fd = open("/sys/power/wake_unlock", O_WRONLY);
                if (fd >= 0) { write(fd, tok, strlen(tok)); close(fd); }
                tok = strtok(NULL, " \t\n");
            }
        }
        fclose(wl);
    }
    log_line("suspend: wakelocks released");
}

// Restore system after suspend loop ends.
static void suspend_teardown(void) {
    // Restore DWC3 USB autosuspend to default (allows normal USB plug handling)
    write_sysfs("/sys/devices/platform/soc/a600000.ssusb/power/autosuspend_delay_ms", "1000");
    log_line("suspend: DWC3 autosuspend restored");
}

// Set RTC alarm to wake from freeze after `seconds` seconds.
// Read the RTC's OWN notion of epoch seconds. This is NOT the same clock as
// time(NULL): CLOCK_REALTIME gets corrected by NTP / our time-sync file, while
// the RTC hardware only advances from whatever it was last set to. On this
// device the RTC backup rail loses state, so it comes up at 1970 and stays
// there -- observed since_epoch=761865 (1970-01-09) against a system clock
// reading 2026. Returns -1 if unreadable.
static long long read_rtc_epoch(void) {
    int fd = open("/sys/class/rtc/rtc0/since_epoch", O_RDONLY);
    if (fd < 0) return -1;
    char buf[32] = {0};
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    errno = 0;
    char *end = NULL;
    long long v = strtoll(buf, &end, 10);
    // A freshly-reset RTC legitimately reads 0, so only NEGATIVE is invalid.
    // Treating 0 as unreadable would needlessly block arming: the alarm is
    // armed RELATIVE to this value, so 0 + seconds is perfectly serviceable.
    if (errno != 0 || end == buf || v < 0) return -1;
    return v;
}

// Try to push the current system time into the RTC hardware.
//
// EXPECTED TO FAIL ON THIS HARDWARE -- kept as best-effort only. The rtc-pm8xxx
// driver rejects RTC_SET_TIME with EPERM unless its devicetree node carries
// `allow-set-time`, and this device's node does not (verified: /proc/device-tree
// .../pm8150_rtc/ contains only compatible/interrupts/name/phandle/reg/reg-names).
// SELinux is Permissive and we are root, so this is a kernel/DT block, not a
// permissions one; even /system/bin/hwclock -w fails the same way. Fixing it
// would require reflashing the kernel DTB.
//
// That is fine: set_rtc_alarm() arms alarms RELATIVE to the RTC's own
// since_epoch, so a permanently-1970 RTC still produces correct wakeups
// (verified on-device: a +30s alarm fired, pm8xxx_rtc_alarm IRQ incremented).
// This function stays because it costs nothing, logs the real reason once, and
// will silently start working on any unit whose DT does allow it.
static void sync_rtc_from_system(const char *reason) {
    if (rtc_set_unsupported) return;  // driver refuses; see note above
    time_t sys_now = time(NULL);
    if (sys_now < RTC_SANE_MIN_EPOCH) {
        log_line("rtc-sync: system clock not yet valid (%ld), skipping (%s)",
                 (long)sys_now, reason);
        return;
    }
    struct tm tm_utc;
    if (!gmtime_r(&sys_now, &tm_utc)) {
        log_line("rtc-sync: gmtime_r failed (%s)", reason);
        return;
    }
    // The RTC is always UTC; the kernel applies no timezone to it.
    struct rtc_time rt = {0};
    rt.tm_sec  = tm_utc.tm_sec;
    rt.tm_min  = tm_utc.tm_min;
    rt.tm_hour = tm_utc.tm_hour;
    rt.tm_mday = tm_utc.tm_mday;
    rt.tm_mon  = tm_utc.tm_mon;
    rt.tm_year = tm_utc.tm_year;

    int fd = open("/dev/rtc0", O_RDWR);
    if (fd < 0) {
        log_line("rtc-sync: open(/dev/rtc0) failed: %s (%s)", strerror(errno), reason);
        return;
    }
    if (ioctl(fd, RTC_SET_TIME, &rt) != 0) {
        // A refusal here is a permanent property of this kernel build (the DT
        // node lacks allow-set-time), not a transient error, so latch it and
        // stop retrying/logging forever. This device reports EACCES
        // ("Permission denied"); EPERM is the other spelling drivers use, and
        // ENOTTY covers a driver with no set_time op at all. Alarms are armed
        // relative to the RTC, so nothing depends on this succeeding.
        if (errno == EACCES || errno == EPERM || errno == ENOTTY)
            rtc_set_unsupported = 1;
        log_line("rtc-sync: RTC_SET_TIME failed: %s (%s)%s", strerror(errno), reason,
                 rtc_set_unsupported ? " -- driver does not support setting the RTC;"
                                       " relying on relative alarms (no further retries)"
                                     : "");
        close(fd);
        return;
    }
    close(fd);
    log_line("rtc-sync: RTC set to %ld (%s); rtc_epoch now %lld",
             (long)sys_now, reason, read_rtc_epoch());
}

// Arm the wake alarm. Skew between the RTC and CLOCK_REALTIME is the whole
// hazard here: /sys/class/rtc/rtc0/wakealarm is interpreted in the RTC's OWN
// time base, so writing time(NULL)+seconds into an RTC stuck at 1970 arms an
// alarm ~56 years out that never fires. The device then freezes until some
// other wakeup source happens to fire -- observed as a single 165490s (45.9h)
// freeze that blew straight past a 604s shutdown deadline, which is why
// fold-shutdown never ran. Always compute the deadline from the RTC's own
// clock, and never fall back to time(NULL).
// Returns 1 if an alarm is genuinely armed, 0 otherwise. Callers MUST NOT
// freeze on a 0 return when they depend on a timed wake -- see suspend_loop.
static int set_rtc_alarm(int seconds) {
    if (seconds <= 0) return 0;
    char buf[32];

    // Clear any previous alarm FIRST, so a stale one can never survive the
    // unreadable-RTC path below and fire at an unexpected time.
    int fd = open("/sys/class/rtc/rtc0/wakealarm", O_WRONLY);
    if (fd >= 0) { write(fd, "0\n", 2); close(fd); }

    long long rtc_now = read_rtc_epoch();
    if (rtc_now < 0) {
        // Rate-limited: the caller retries this every ~5s while polling.
        static unsigned skip_log = 0;
        if ((skip_log++ % 60) == 0)
            log_line("suspend: RTC alarm SKIPPED (since_epoch unreadable)");
        return 0;
    }
    long long wake = rtc_now + seconds;

    // Set new alarm, in the RTC's own time base.
    snprintf(buf, sizeof(buf), "%lld\n", wake);
    fd = open("/sys/class/rtc/rtc0/wakealarm", O_WRONLY);
    if (fd < 0) {
        log_line("suspend: RTC alarm open failed: %s", strerror(errno));
        return 0;
    }
    ssize_t w = write(fd, buf, strlen(buf));
    close(fd);
    if (w < 0) {
        log_line("suspend: RTC alarm write failed: %s", strerror(errno));
        return 0;
    }
    log_line("suspend: RTC alarm in %ds (rtc_epoch %lld, sys_epoch %ld)",
             seconds, wake, (long)(time(NULL) + seconds));
    return 1;
}

// Enter freeze. Blocks until wakeup (PSoC unfold or RTC alarm).
// Returns the wall-clock duration (seconds) the device was actually frozen.
// A value < 2 means the kernel likely aborted suspend (device EBUSY, etc.).
static int do_freeze(void) {
    log_line("suspend: entering freeze");
    time_t before = time(NULL);
    int fd = open("/sys/power/state", O_WRONLY);
    if (fd >= 0) {
        ssize_t w = write(fd, "freeze\n", 7);
        close(fd);
        if (w < 0) {
            log_line("suspend: freeze write failed: %s", strerror(errno));
            return 0;
        }
    } else {
        log_line("suspend: failed to open /sys/power/state: %s", strerror(errno));
        return 0;
    }
    time_t after = time(NULL);
    int elapsed = (int)(after - before);
    log_line("suspend: woke up (frozen %ds)", elapsed);
    return elapsed;
}

/**
 * Suspend-then-shutdown loop. Called when fold suspend timer fires.
 * Freezes the device. If still folded when shutdown_armed_until_ms
 * expires (via RTC alarm wake), shuts down. Otherwise resumes normally.
 *
 * Returns: 0 = unfolded (resume), 1 = shutdown initiated.
 */
static int suspend_loop(void) {
    // Check USB VBUS before touching system_suspend. When a USB cable is
    // connected, the DWC3 controller blocks kernel freeze with EBUSY.
    // Stopping system_suspend HAL in this state leaves it dead for the
    // entire retry window (~6s), during which any display state change
    // (touch-to-wake) crashes system_server via HIDL DEAD_OBJECT.
    if (usb_cable_connected()) {
        log_line("suspend: USB cable connected, skipping freeze (would fail)");
        return 0;
    }

    suspend_prepare();

    // Use wall clock (CLOCK_REALTIME) for shutdown deadline since
    // CLOCK_MONOTONIC stops during freeze.
    time_t shutdown_epoch = 0;
    if (shutdown_armed_until_ms > 0) {
        long long remain_ms = shutdown_armed_until_ms - now_ms();
        if (remain_ms > 0) {
            shutdown_epoch = time(NULL) + (time_t)(remain_ms / 1000);
        } else {
            // Deadline already elapsed. Reachable via the unplug re-arm, which
            // anchors the deadline to the original fold edge -- unplugging after
            // power_timeout_s of folded-on-charger lands here. Set the epoch in
            // the past (not 0, which means "no deadline") so the loop's
            // remain_s <= 0 branch fires the shutdown immediately instead of
            // freezing forever with the deadline silently dropped.
            shutdown_epoch = time(NULL) - 1;
        }
    }

    int consecutive_failures = 0;
    unsigned poll_iterations = 0;  // rate-limits the no-RTC-alarm poll log
    int unknown_rounds = 0;        // consecutive poll rounds with no readable fold state

    while (1) {
        time_t wall_now = time(NULL);
        int remain_s = (shutdown_epoch > 0) ? (int)(shutdown_epoch - wall_now) : 0;

        if (shutdown_epoch > 0 && remain_s <= 0) {
            log_line("suspend: shutdown timeout reached while folded");
            suspend_teardown();
            power_off();
            return 1;
        }

        // Set RTC alarm to wake for the shutdown check.
        //
        // If a shutdown deadline exists but the alarm could NOT be armed, do
        // NOT freeze. Freezing without a timed wake source leaves only a
        // physical unfold to end it, so the deadline is silently never enforced
        // and the glasses sit frozen long past their power-off time (the 45.9h
        // freeze that motivated this fix). Instead stay awake and poll: burns
        // battery, but keeps the shutdown reachable, and the loop still exits
        // promptly on unfold. Bounded by power_timeout_s, after which we power
        // off -- which is the outcome the user actually wants.
        if (shutdown_epoch > 0 && !set_rtc_alarm(remain_s)) {
            // Rate-limited: this branch repeats every ~5s for up to
            // power_timeout_s, and set_rtc_alarm already logs each failure.
            if ((poll_iterations++ % 60) == 0) {
                log_line("suspend: no RTC alarm could be armed; polling instead of "
                         "freezing (shutdown in %ds)", remain_s);
            }
            // A cable arriving mid-poll must abort exactly like the freeze
            // path does, otherwise we would keep polling (and eventually power
            // off) while the user is charging.
            if (usb_cable_connected()) {
                log_line("suspend: USB connected during poll, aborting");
                suspend_teardown();
                return 0;
            }
            // Re-check fold here: skipping do_freeze() also skips the post-wake
            // fold re-read below, so without this an unfold during the wait
            // would go unnoticed until the deadline. Poll in 1s steps rather
            // than one sleep(5) so an unfold is noticed promptly -- the device
            // is awake anyway, so responsiveness costs nothing here.
            // Require a CONFIRMED unfold (== 0), not merely "not folded".
            // Exiting here returns 0, and the caller unconditionally clears
            // shutdown_armed_until_ms -- so treating a transient unreadable
            // property (-1) as an unfold would silently discard the power-off
            // deadline. Re-read a few times and only bail on a real 0; an
            // unknown reading just keeps polling, which is harmless because the
            // deadline still applies and a genuine unfold is caught next pass.
            int unfolded = 0, saw_known = 0;
            for (int i = 0; i < 5; ++i) {
                sleep(1);
                int sp = read_fold_from_spread();
                if (sp >= 0) saw_known = 1;
                if (sp == 0) { unfolded = 1; break; }
            }
            if (unfolded) {
                suspend_teardown();
                return 0;
            }
            // Bail out if fold state has been UNKNOWN for the whole window,
            // repeatedly. is_spread only reads -1 when the property is absent
            // or empty, so this means the fold signal is genuinely gone rather
            // than flaky -- and continuing would eventually power off a device
            // whose fold state we cannot confirm. Staying awake is recoverable;
            // powering off in the user's hand is not. A single unknown round is
            // tolerated so ordinary flakiness does not abandon the deadline.
            unknown_rounds = saw_known ? 0 : (unknown_rounds + 1);
            if (unknown_rounds >= 12) {  // ~60s of no readable fold state
                log_line("suspend: fold state unreadable for %d poll rounds; "
                         "abandoning shutdown deadline rather than powering off blind",
                         unknown_rounds);
                suspend_teardown();
                return 0;
            }
            continue;  // top of loop re-evaluates remain_s and powers off when due
        }

        // Freeze (blocks until PSoC unfold or RTC alarm)
        int frozen_s = do_freeze();

        // A real freeze lasts at least a few seconds (RTC alarm minimum).
        // If we returned in < 2s, the kernel aborted suspend (EBUSY). The two
        // causes here are: (a) a USB cable got plugged mid-loop, or (b) an
        // active kernel wakeup source (hal_bluetooth_lock while BT audio/HFP is
        // connected) is still held. We do NOT abort: the device stays awake and
        // drains to a battery shutdown if we give up. Instead keep retrying for
        // the whole fold window -- the wakelock releases once BT goes idle (or
        // bt-manager drops A2DP/HFP on fold), and the next freeze succeeds.
        // Re-run suspend_prepare() each retry to re-release transient wakelocks.
        if (frozen_s < 2) {
            consecutive_failures++;
            // A USB cable plugged mid-loop makes freeze impossible (DWC3 blocks
            // LPM). Bail cleanly -- matches the entry guard.
            if (usb_cable_connected()) {
                log_line("suspend: USB connected mid-loop after %d freeze failures, aborting",
                         consecutive_failures);
                suspend_teardown();
                return 0;
            }
            // Escalating backoff (2s, 4s, 6s ... capped at 30s) so we don't spin
            // hot while a wakelock is held, but still retry often enough to catch
            // the moment it releases.
            int backoff_s = consecutive_failures * 2;
            if (backoff_s > 30) backoff_s = 30;
            if ((consecutive_failures % 5) == 1) {
                log_line("suspend: freeze EBUSY x%d (wakelock held); retrying, backoff=%ds",
                         consecutive_failures, backoff_s);
            }
            usleep((useconds_t)backoff_s * 1000000);
            // Re-release wakelocks that may have appeared since the last attempt.
            suspend_prepare();
        } else {
            consecutive_failures = 0;
        }

        // Woke up -- wait for sensors to stabilize, then read fold state from
        // the authoritative is_spread property (correct polarity) multiple
        // times to avoid stale reads from a half-resumed I2C bus. Using the raw
        // hall level here was the bug: on worn+docked hardware it reads 1 while
        // unfolded, so the daemon thought it was still folded forever.
        usleep(500000);
        int still_folded = 1;
        for (int check = 0; check < 3; check++) {
            int sp = read_fold_from_spread();
            still_folded = (sp == 1); // unknown(-1) or unfolded(0) => not folded
            if (!still_folded) break;
            usleep(200000);
        }
        log_line("suspend: woke, folded=%d remain=%ds",
                 still_folded,
                 shutdown_epoch > 0 ? (int)(shutdown_epoch - time(NULL)) : -1);

        if (!still_folded) {
            suspend_teardown();
            return 0;
        }
        // Still folded -- RTC alarm woke us. Loop back to check shutdown timeout.
    }
}

static void self_test(const char *config_path) {
    log_line("self-test: start");
    if (access(config_path, R_OK) != 0) {
        log_line("self-test: config not readable: %s (%s)", config_path, strerror(errno));
    } else {
        log_line("self-test: config ok: %s", config_path);
    }
    // Fold node.
    if (strncmp(g_cfg.fold_hall_node, "/dev/input/", 11) == 0) {
        int fd = open(g_cfg.fold_hall_node, O_RDONLY | O_NONBLOCK);
        if (fd < 0) log_line("self-test: fold evdev open failed: %s (%s)",
                             g_cfg.fold_hall_node, strerror(errno));
        else { log_line("self-test: fold evdev ok: %s", g_cfg.fold_hall_node); close(fd); }
    } else {
        int fd = open(g_cfg.fold_hall_node, O_RDONLY);
        if (fd < 0) log_line("self-test: fold sysfs open failed: %s (%s)",
                             g_cfg.fold_hall_node, strerror(errno));
        else { log_line("self-test: fold sysfs ok: %s", g_cfg.fold_hall_node); close(fd); }
    }
    // Input devices.
    char tmp[512];
    strncpy(tmp, g_cfg.input_devices, sizeof(tmp) - 1);
    tmp[sizeof(tmp) - 1] = '\0';
    const char *paths[MAX_INPUT_DEVICES] = {0};
    int n = split_input_devices(tmp, paths);
    for (int i = 0; i < n; ++i) {
        int fd = open(paths[i], O_RDONLY | O_NONBLOCK);
        if (fd < 0) log_line("self-test: input open failed: %s (%s)", paths[i], strerror(errno));
        else { log_line("self-test: input ok: %s", paths[i]); close(fd); }
    }
    log_line("self-test: done");
}

int main(int argc, char **argv) {
    const char *config_path = DEFAULT_CONFIG_PATH;
    int do_self_test = 0;

    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--config") == 0 && i + 1 < argc) {
            config_path = argv[++i];
        } else if (strcmp(argv[i], "--self-test") == 0) {
            do_self_test = 1;
        } else {
            // Unknown args: ignore silently, matching the spec.
        }
    }
    if (getenv("GLASSES_POWER_DAEMON_SELFTEST")) do_self_test = 1;

    // Ensure persistent logging regardless of how we were launched.
    // When init starts us, stderr goes to /dev/null. Redirect it to
    // the log file so we always have visibility into fold/suspend events.
    rotate_log_if_big(DEFAULT_LOG_PATH);
    if (!freopen(DEFAULT_LOG_PATH, "a", stderr)) {
        // Can't log the failure (stderr is broken), just continue.
    }

    // Singleton: acquire an exclusive non-blocking lock on a sentinel file.
    // If another instance already holds it, bail out immediately. This
    // prevents duplicate daemons from racing on fold/time-sync/input events
    // (which causes spurious double BT toggles, etc.). The parent dir is
    // created up-front (mode 0777) so the lockfile is reachable on a fresh
    // reflash where /data/local/diy-overlay/ doesn't exist yet.
    {
        char lockdir_buf[PATH_MAX];
        strncpy(lockdir_buf, DEFAULT_LOCK_PATH, sizeof(lockdir_buf) - 1);
        lockdir_buf[sizeof(lockdir_buf) - 1] = '\0';
        const char *lockdir = dirname(lockdir_buf);
        if (mkdir(lockdir, 0777) != 0 && errno != EEXIST) {
            log_line("mkdir(%s) failed: %s", lockdir, strerror(errno));
        } else {
            chmod(lockdir, 0777);
        }
        int lock_fd = open(DEFAULT_LOCK_PATH, O_RDWR | O_CREAT | O_CLOEXEC, 0666);
        if (lock_fd < 0) {
            log_line("lock: open(%s) failed: %s (continuing anyway)", DEFAULT_LOCK_PATH, strerror(errno));
        } else if (flock(lock_fd, LOCK_EX | LOCK_NB) != 0) {
            // Either another instance holds the lock (EWOULDBLOCK) or the
            // filesystem doesn't support flock (EOPNOTSUPP, EINVAL). In both
            // cases we cannot guarantee singleton behaviour, so bail out --
            // letting two daemons race produces double fold/lock-screen
            // broadcasts, which is worse than not running at all. A brief
            // sleep dampens any tight init respawn loop in the EWOULDBLOCK
            // case where the previous instance's fd table hasn't been reaped.
            log_line("lock: flock failed: %s; exiting", strerror(errno));
            sleep(1);
            return 0;
        } else {
            char pidbuf[32];
            int n = snprintf(pidbuf, sizeof(pidbuf), "%d\n", (int)getpid());
            if (ftruncate(lock_fd, 0) != 0) {
                log_line("lock: ftruncate failed: %s", strerror(errno));
            }
            ssize_t w = write(lock_fd, pidbuf, (size_t)n);
            if (w != n) {
                log_line("lock: write pid failed (wrote %zd of %d): %s",
                         w, n, strerror(errno));
            }
            // Make the lockfile readable by the listener app so it can pick
            // up the daemon's PID for SIGHUP without needing root.
            fchmod(lock_fd, 0666);
            // Intentionally leak lock_fd for the lifetime of the process so the
            // lock stays held until we exit.
        }
    }

    // Open trace_marker fd once so PWR_TRACE_BEGIN/END can emit slices.
    pwr_marker_init();

    // Ignore SIGPIPE so closed fds (e.g. from child exec) don't kill us.
    signal(SIGPIPE, SIG_IGN);

    struct sigaction sa_hup;
    memset(&sa_hup, 0, sizeof(sa_hup));
    sa_hup.sa_handler = on_hup;
    sigaction(SIGHUP, &sa_hup, NULL);

    struct sigaction sa_term;
    memset(&sa_term, 0, sizeof(sa_term));
    sa_term.sa_handler = on_term;
    sigaction(SIGTERM, &sa_term, NULL);
    sigaction(SIGINT,  &sa_term, NULL);

    PWR_TRACE_BEGIN("pwr.boot.parse_config");
    parse_config(config_path, &g_cfg);
    PWR_TRACE_END();
    config_loaded_ms = now_ms();
    last_activity_ms = now_ms();

    // PSoC extcon latch policy:
    //   enforce_psensor = 1 -- LATCH the wear (JIG/psensor) channel high so
    //     stock Rokid PsensorObserver (com.rokid.sysconfig) never sees wear
    //     transitions. Otherwise it plays an earcon (sound effects 18/56) and
    //     calls PowerManager.wakeUp("psensor") on every on/off-head event,
    //     toggling the screen. We do not use is_take_on at all (deprecated);
    //     fold is the sole off-head signal, so suppressing wear is free.
    //   enforce_hall = 0 -- UNLATCH the fold (DOCK/hall) channel so the PSoC
    //     driver keeps emitting real extcon uevents for fold/unfold, which
    //     drives is_spread + ACTION_LEG_STATUS_CHANGED (FoldGate, suspend, etc.).
    {
        PWR_TRACE_BEGIN("pwr.boot.latch");
        struct { const char *node; const char *val; } latch[] = {
            { "/sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/enforce_psensor", "1\n" },
            { "/sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/enforce_hall",    "0\n" },
        };
        for (size_t i = 0; i < sizeof(latch)/sizeof(latch[0]); ++i) {
            int fd = open(latch[i].node, O_WRONLY);
            if (fd < 0) {
                log_line("latch: open(%s) failed: %s", latch[i].node, strerror(errno));
                continue;
            }
            ssize_t w = write(fd, latch[i].val, 2);
            close(fd);
            log_line("latch: %s <- %c (%s)", latch[i].node, latch[i].val[0],
                     w == 2 ? "ok" : "err");
        }
        PWR_TRACE_END();
    }

    log_line("startup cfg screen=%d power=%d fold=%s inputs=%s",
             g_cfg.screen_timeout_s, g_cfg.power_timeout_s,
             g_cfg.fold_hall_node, g_cfg.input_devices);

    // Save any tombstones from a previous crash (runs as root, can read /data/tombstones)
    PWR_TRACE_BEGIN("pwr.boot.save_tombstones");
    save_tombstones();
    PWR_TRACE_END();

    // Push the configured screen-off timeout into Android's PowerManager
    // settings. This is the actual mechanism that makes the screen sleep --
    // the daemon's own ACTION_LOCK_SCREEN broadcast fires at the same
    // threshold but on a Rokid Glasses (no lock screen) it's effectively a
    // no-op. The fold-power-off path is unaffected: when folded, the daemon
    // arms power_timeout_s independently, and the immediate fold-lock
    // broadcast still fires.
    PWR_TRACE_BEGIN("pwr.boot.apply_screen_off_timeout");
    apply_screen_off_timeout(g_cfg.screen_timeout_s);
    apply_rkd_shutdown_timeout(g_cfg.power_timeout_s);
    PWR_TRACE_END();

    // Compute cfg_dir / cfg_base from config_path. Used below by both the
    // cold-boot time-sync scan and the inotify reload watch -- both must
    // agree on the same directory.
    char cfg_copy[PATH_MAX];
    strncpy(cfg_copy, config_path, sizeof(cfg_copy) - 1);
    cfg_copy[sizeof(cfg_copy) - 1] = '\0';
    char cfg_dir_buf[PATH_MAX];
    strncpy(cfg_dir_buf, config_path, sizeof(cfg_dir_buf) - 1);
    cfg_dir_buf[sizeof(cfg_dir_buf) - 1] = '\0';
    const char *cfg_dir = dirname(cfg_dir_buf);
    const char *cfg_base = basename(cfg_copy);

    // Apply any pending time-sync left by the app (e.g. during a cold boot
    // where the app wrote the file before this daemon was running).
    {
        PWR_TRACE_BEGIN("pwr.boot.cold_drop_files");
        char tspath0[PATH_MAX];
        snprintf(tspath0, sizeof(tspath0), "%s/%s", cfg_dir, TIME_SYNC_FILE);
        struct stat ts_st;
        if (stat(tspath0, &ts_st) == 0) {
            PWR_TRACE_BEGIN("pwr.boot.apply_time_sync");
            apply_time_sync(tspath0);
            PWR_TRACE_END();
        }
        char wpath0[PATH_MAX]; struct stat w_st;
        snprintf(wpath0, sizeof(wpath0), "%s/%s", cfg_dir, WIFI_REQ_FILE);
        if (stat(wpath0, &w_st) == 0) {
            PWR_TRACE_BEGIN("pwr.boot.apply_wifi_request");
            apply_wifi_request(wpath0);
            PWR_TRACE_END();
        }
        PWR_TRACE_END();
    }

    // Repair a stale RTC at boot, independently of the time-sync file.
    //
    // apply_time_sync() consumes (unlinks) its file, so it only runs when the
    // app has just dropped a fresh one -- it cannot be relied on to fix an RTC
    // that reset later. This device's RTC backup rail loses state on a full
    // drain and comes back at 1970, which silently breaks every wake alarm and
    // therefore fold-shutdown. Check on every start and re-sync whenever the
    // RTC is implausible while the system clock is good (NTP/app already fixed
    // it). Cheap, idempotent, and the thing that makes the fix self-healing
    // rather than one-shot.
    {
        PWR_TRACE_BEGIN("pwr.boot.rtc_check");
        long long rtc_now = read_rtc_epoch();
        time_t sys_now = time(NULL);
        if (rtc_now < 0) {
            log_line("rtc-check: since_epoch unreadable");
        } else if (rtc_now < RTC_SANE_MIN_EPOCH) {
            log_line("rtc-check: RTC stale (epoch %lld < %ld), system=%ld -> re-syncing",
                     rtc_now, (long)RTC_SANE_MIN_EPOCH, (long)sys_now);
            sync_rtc_from_system("boot-stale");
        } else {
            // Both clocks look sane; correct any large drift so alarm deadlines
            // stay accurate. Small skew is harmless and not worth a write.
            long long skew = (long long)sys_now - rtc_now;
            if (skew < 0) skew = -skew;
            if (sys_now >= RTC_SANE_MIN_EPOCH && skew > 120) {
                log_line("rtc-check: RTC drift %llds (rtc=%lld sys=%ld) -> re-syncing",
                         skew, rtc_now, (long)sys_now);
                sync_rtc_from_system("boot-drift");
            } else {
                log_line("rtc-check: RTC ok (epoch %lld, skew %llds)", rtc_now, skew);
            }
        }
        PWR_TRACE_END();
    }

    if (do_self_test) self_test(config_path);

    // Ensure cfg_dir exists with world-writable mode so the listener app
    // (UID u0_a*) can drop config + time-sync files. We're root, so this is
    // safe; harmless if the dir already exists. Without this, a fresh
    // reflash leaves /data/local/diy-overlay/ missing and inotify_add_watch
    // below fails, leaving the daemon stuck on built-in defaults forever.
    // (The lockfile section above mkdir's the same dir for itself; this is
    // a redundant safeguard for the case where DEFAULT_CONFIG_PATH and
    // DEFAULT_LOCK_PATH ever diverge.)
    if (mkdir(cfg_dir, 0777) != 0 && errno != EEXIST) {
        log_line("mkdir(%s) failed: %s", cfg_dir, strerror(errno));
    } else {
        chmod(cfg_dir, 0777);
    }

    // Inotify on parent dir of config. Re-trigger reload on CLOSE_WRITE/MOVED_TO.
    int inotify_fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (inotify_fd < 0) {
        log_line("inotify_init1 failed: %s (config reload disabled)", strerror(errno));
    } else {
        int wd = inotify_add_watch(inotify_fd, cfg_dir,
                                   IN_CLOSE_WRITE | IN_MOVED_TO);
        if (wd < 0) {
            log_line("inotify_add_watch(%s) failed: %s", cfg_dir, strerror(errno));
            close(inotify_fd);
            inotify_fd = -1;
        }
    }

    // Open input devices.
    PWR_TRACE_BEGIN("pwr.boot.open_devices");
    char inputs_buf[512];
    strncpy(inputs_buf, g_cfg.input_devices, sizeof(inputs_buf) - 1);
    inputs_buf[sizeof(inputs_buf) - 1] = '\0';
    const char *input_paths[MAX_INPUT_DEVICES] = {0};
    int input_count = split_input_devices(inputs_buf, input_paths);
    int input_fds[MAX_INPUT_DEVICES];
    for (int i = 0; i < input_count; ++i) {
        input_fds[i] = open_evdev(input_paths[i]);
    }

    // Open fold source.
    int fold_fd = -1;
    int fold_is_evdev = 0;
    if (strncmp(g_cfg.fold_hall_node, "/dev/input/", 11) == 0) {
        fold_is_evdev = 1;
        fold_fd = open(g_cfg.fold_hall_node, O_RDONLY | O_NONBLOCK);
        if (fold_fd < 0) {
            log_line("fold evdev open failed: %s (%s)",
                     g_cfg.fold_hall_node, strerror(errno));
        }
    } else {
        // Keep the hall fd open purely as a POLLPRI wakeup hint so we re-sample
        // promptly on a hardware edge. The authoritative fold VALUE always comes
        // from the is_spread vendor property (correct, system-led polarity), not
        // the raw hall level -- on worn+docked hardware hall reads 1 in the
        // normal UNFOLDED posture, which is the inversion that caused the daemon
        // to get stuck "folded" and spuriously tear down A2DP audio.
        fold_fd = open_sysfs(g_cfg.fold_hall_node);
        int sp = read_fold_from_spread();
        if (sp == 0 || sp == 1) {
            fold_folded = sp;
            // Booting already folded produces NO fold edge (fold_raw_last is
            // seeded equal to fold_folded below), so the edge handler never runs
            // and never stamps fold_since_ms. Stamp it here to keep the
            // invariant "fold_since_ms > 0 iff folded" -- the unplug re-arm
            // derives the shutdown deadline from it and would otherwise disarm
            // fold-shutdown entirely until a full unfold+refold cycle.
            if (fold_folded) fold_since_ms = now_ms();
            log_line("fold initial=%d (is_spread)", fold_folded);
        } else {
            // is_spread is empty: glasses booted already-unfolded with no extcon
            // uevent, so PsensorObserver never set the property. Seed it to the
            // safe default (unfolded=spread) so the app's FoldPoll has a value to
            // read instead of sitting on null until the first physical fold edge.
            // NOTE: never seed "folded" here -- the old code derived the seed from
            // the wrong-polarity hall level and corrupted is_spread to "0" on a
            // unfolded boot, which is exactly the bug we're removing.
            fold_folded = 0;
            const char *sp_argv[] = {
                "/system/bin/setprop", FOLD_SPREAD_PROP, "1", NULL
            };
            run_cmd(sp_argv);
            log_line("fold initial=0 (is_spread empty; seeded is_spread=1)");
        }
    }

    // Track a last-observed raw fold state for debounce.
    int fold_raw_last = fold_folded;

    // Guarantee a known-off LED at startup. If a previous instance was SIGKILLed
    // while the battery LED was lit, its exit-clear never ran; led_active starts 0
    // here, so the disarm branch in led_tick would skip clearing a physically-lit
    // LED until an arm->disarm cycle. One unconditional clear closes that gap.
    led_set_rg(0, 0);

    PWR_TRACE_END(); // pwr.boot.open_devices

    // Build pollfd[] dynamically.
    struct pollfd pfds[1 + MAX_INPUT_DEVICES + 1];
    while (!g_stop) {
        int nfds = 0;
        int idx_inotify = -1, idx_fold = -1;
        int idx_inputs[MAX_INPUT_DEVICES];
        for (int i = 0; i < MAX_INPUT_DEVICES; ++i) idx_inputs[i] = -1;

        if (inotify_fd >= 0) {
            pfds[nfds].fd = inotify_fd;
            pfds[nfds].events = POLLIN;
            idx_inotify = nfds++;
        }
        for (int i = 0; i < input_count; ++i) {
            if (input_fds[i] < 0) continue;
            pfds[nfds].fd = input_fds[i];
            pfds[nfds].events = POLLIN;
            idx_inputs[i] = nfds++;
        }
        if (fold_fd >= 0) {
            pfds[nfds].fd = fold_fd;
            pfds[nfds].events = fold_is_evdev ? POLLIN : POLLPRI;
            idx_fold = nfds++;
        }

        int rc = poll(pfds, nfds, 1000);
        if (rc < 0) {
            if (errno == EINTR) {
                // fall through to signal handling
            } else {
                log_line("poll failed: %s", strerror(errno));
                break;
            }
        }

        // Reap any children that exited since the last iteration so they don't
        // linger as zombies. run_cmd() intentionally does not reap its own
        // child -- this loop covers them all.
        while (waitpid(-1, NULL, WNOHANG) > 0) { }

        long long now = now_ms();

        // ---- reload handling (signal or inotify) ----
        int should_reload = 0;
        if (g_reload) { g_reload = 0; should_reload = 1; }
        if (idx_inotify >= 0 && (pfds[idx_inotify].revents & POLLIN)) {
            char buf[4096]
                __attribute__((aligned(__alignof__(struct inotify_event))));
            ssize_t n;
            while ((n = read(inotify_fd, buf, sizeof(buf))) > 0) {
                const char *p = buf;
                while (p + sizeof(struct inotify_event) <= buf + n) {
                    const struct inotify_event *ev = (const struct inotify_event *)p;
                    if (p + sizeof(struct inotify_event) + ev->len > buf + n) break;
                    if (ev->len > 0 && strcmp(ev->name, cfg_base) == 0) {
                        should_reload = 1;
                    }
                    if (ev->len > 0 && strcmp(ev->name, TIME_SYNC_FILE) == 0) {
                        char tspath[PATH_MAX];
                        snprintf(tspath, sizeof(tspath), "%s/%s", cfg_dir, TIME_SYNC_FILE);
                        apply_time_sync(tspath);
                    }
                    if (ev->len > 0 && strcmp(ev->name, WIFI_REQ_FILE) == 0) {
                        char wpath[PATH_MAX];
                        snprintf(wpath, sizeof(wpath), "%s/%s", cfg_dir, WIFI_REQ_FILE);
                        apply_wifi_request(wpath);
                    }
                    if (ev->len > 0 && strcmp(ev->name, LED_ARM_FLAG_FILE) == 0) {
                        led_tick(cfg_dir, now_ms());
                    }
                    p += sizeof(struct inotify_event) + ev->len;
                }
            }
        }
        if (should_reload) {
            PWR_TRACE_BEGIN("pwr.event.reload_config");
            struct Config newcfg;
            parse_config(config_path, &newcfg);
            // Swap in the new config.
            g_cfg = newcfg;
            config_loaded_ms = now_ms();
            reload_counter++;
            log_line("ACK hup=%u cfg screen=%d power=%d",
                     reload_counter, g_cfg.screen_timeout_s,
                     g_cfg.power_timeout_s);
            apply_screen_off_timeout(g_cfg.screen_timeout_s);
            apply_rkd_shutdown_timeout(g_cfg.power_timeout_s);
            // Note: we intentionally do NOT re-open input/fold fds here. A full
            // restart is needed to re-pick device paths. The orchestrator owns
            // lifecycle; this keeps the hot-reload simple and race-free.
            PWR_TRACE_END(); // pwr.event.reload_config
        }

        // ---- input event drain ----
        for (int i = 0; i < input_count; ++i) {
            if (idx_inputs[i] < 0) continue;
            if (!(pfds[idx_inputs[i]].revents & POLLIN)) continue;
            struct input_event evs[32];
            for (;;) {
                ssize_t n = read(input_fds[i], evs, sizeof(evs));
                if (n <= 0) break;
                int count = (int)(n / (ssize_t)sizeof(struct input_event));
                for (int k = 0; k < count; ++k) {
                    // Any input-event activity counts (key down or key up, etc).
                    (void)evs[k];
                    last_activity_ms = now_ms();
                    // The kernel/input stack wakes the display on real key
                    // events. We just track that the screen is awake again
                    // so the idle-lock gate re-arms.
                    screen_on = 1;
                }
            }
        }

        // ---- fold source drain ----
        // Authoritative fold VALUE is always vendor.rkd.glasses.is_spread
        // (kernel PSoC driver, correct polarity). The hall fd (when sysfs) is
        // only a POLLPRI wakeup hint so we re-sample the property promptly on a
        // hardware edge; we never trust its raw level. The evdev SW_LID branch
        // is kept for hardware that genuinely exposes a lid switch.
        int fold_raw_now = fold_folded; // default: unchanged
        if (fold_is_evdev) {
            if (idx_fold >= 0 && (pfds[idx_fold].revents & POLLIN)) {
                struct input_event evs[16];
                for (;;) {
                    ssize_t n = read(fold_fd, evs, sizeof(evs));
                    if (n <= 0) break;
                    int count = (int)(n / (ssize_t)sizeof(struct input_event));
                    for (int k = 0; k < count; ++k) {
                        if (evs[k].type == EV_SW && evs[k].code == SW_LID) {
                            fold_raw_now = evs[k].value ? 1 : 0;
                        }
                    }
                }
            }
        } else {
            // Sysfs path (the production config). Drain the POLLPRI edge if the
            // driver emitted one, then read the authoritative property value.
            if (idx_fold >= 0 && (pfds[idx_fold].revents & (POLLIN | POLLPRI))) {
                (void)read_sysfs_int(fold_fd); // clear the edge latch
            }
            int sp = read_fold_from_spread();
            if (sp == 0 || sp == 1) fold_raw_now = sp;
        }

        // Debounce: raw must differ from current debounced value for >= 3s
        // before we flip.
        if (fold_raw_now != fold_raw_last) {
            fold_raw_last = fold_raw_now;
            fold_change_ms = now;
        }
        if (fold_raw_last != fold_folded &&
            fold_change_ms != 0 &&
            (now - fold_change_ms) >= FOLD_DEBOUNCE_MS) {
            PWR_TRACE_BEGIN("pwr.event.fold_change");
            int prev = fold_folded;
            fold_folded = fold_raw_last;
            log_line("fold=%d", fold_folded);
            if (prev == 0 && fold_folded == 1) {
                // Immediate actions: lock screen, disable BT (via app).
                PWR_TRACE_BEGIN("pwr.event.fold_armed");
                screen_lock_broadcast("fold");
                screen_on = 0;
                last_activity_ms = now;
                fold_broadcast(1);
                // Only arm fold-suspend when NOT on the charger. Freeze can never
                // succeed while the cable is in (DWC3 EBUSY), so arming just spins
                // the suspend retry loop every ~180s; each abort+resume used to
                // re-latch fold and re-broadcast folded=true (A2DP teardown) and
                // re-assert screen_on=0 (idle-lock wedged on). Charging via the
                // authoritative charger `online` node (read_is_charging()).
                if (read_is_charging() == 1) {
                    suspend_armed_until_ms = 0;
                    shutdown_armed_until_ms = 0;
                    log_line("fold armed: on charger, suspend NOT armed (freeze would fail)");
                } else {
                    suspend_armed_until_ms = now + SUSPEND_DELAY_MS;
                    if (g_cfg.power_timeout_s > 0)
                        shutdown_armed_until_ms = now + (long long)g_cfg.power_timeout_s * 1000LL;
                    else
                        shutdown_armed_until_ms = 0;
                    log_line("fold armed: suspend in %lldms, shutdown in %ds",
                             SUSPEND_DELAY_MS, g_cfg.power_timeout_s);
                }
                // Deadline for fold-shutdown, anchored to THIS fold edge. The
                // unplug re-arm below restores shutdown_armed_until_ms from this
                // instead of recomputing "now + power_timeout_s", so repeated
                // plug/unplug cycles while folded cannot keep pushing power-off
                // into the future. Cleared on unfold.
                fold_since_ms = now;
                PWR_TRACE_END();
            } else if (prev == 1 && fold_folded == 0) {
                PWR_TRACE_BEGIN("pwr.event.fold_disarmed");
                suspend_armed_until_ms = 0;
                shutdown_armed_until_ms = 0;
                fold_since_ms = 0;
                charge_rearm_latched = 0;
                fold_broadcast(0);
                log_line("fold disarmed");
                PWR_TRACE_END();
            }
            PWR_TRACE_END(); // pwr.event.fold_change
        }

        // ---- per-iteration safety + action checks ----
        int disabled = kill_switch_active();
        int within_safety = (now - config_loaded_ms) < SAFETY_WINDOW_MS;

        // Battery-indicator LED (charging + app says still). The arm flag lives
        // in the inotify-watched config dir alongside the .conf.
        led_tick(cfg_dir, now);

        // Periodic RTC repair. The boot check can't fix anything if the system
        // clock was ALSO wrong at that moment (cold boot before NTP or before
        // the app drops a time-sync). Re-check occasionally so the RTC gets
        // repaired as soon as the system clock becomes trustworthy, instead of
        // staying broken until the next reboot. Only acts while the RTC is
        // implausible and the system clock is sane, so steady state is one
        // cheap sysfs read per interval.
        if (now - rtc_check_last_ms >= RTC_RECHECK_MS) {
            rtc_check_last_ms = now;
            if (!rtc_set_unsupported) {
                long long rtc_now = read_rtc_epoch();
                if (rtc_now >= 0 && rtc_now < RTC_SANE_MIN_EPOCH &&
                    time(NULL) >= RTC_SANE_MIN_EPOCH) {
                    log_line("rtc-check: RTC still stale (epoch %lld), system clock now valid",
                             rtc_now);
                    sync_rtc_from_system("periodic");
                }
            }
        }

        // Idle screen-lock.
        if (!disabled && screen_on &&
            g_cfg.screen_timeout_s > 0 &&
            (now - last_activity_ms) > (long long)g_cfg.screen_timeout_s * 1000LL) {
            PWR_TRACE_BEGIN("pwr.event.idle_lock");
            screen_lock_broadcast("idle");
            screen_on = 0;
            // Avoid re-entering the lock branch until input arrives.
            last_activity_ms = now;
            PWR_TRACE_END();
        }

        // Re-arm when the charger is unplugged while already folded.
        //
        // The arm decision at the fold edge is made ONCE, and it deliberately
        // skips arming while charging (freeze can never succeed with the cable
        // in). Without this branch, "fold on the charger, then unplug" left the
        // device armed for nothing and awake forever, because re-arming
        // otherwise requires a fresh unfold->fold transition. Polling charge
        // state here is what makes the unplug edge actionable.
        // Both nodes are checked because they can disagree: read_is_charging()
        // reads the mp2724 charger `online` node while usb_cable_connected()
        // reads the USB extcon, and a data-only cable can show USB=1 with
        // online=0. Re-arming on charger-state alone would then hand
        // suspend_loop() a cable it must skip, and it would return immediately
        // and re-arm every iteration -- a hot spin. Requiring both to agree that
        // the cable is out keeps the re-arm a genuine one-shot per unplug.
        //
        // `== 0` (not `!= 1`) deliberately: an unreadable node returns -1, and
        // this branch then declines to re-arm. That is the safe direction here --
        // the fold-edge arm above remains the primary path and treats -1 as
        // not-charging, so a broken node cannot cost us suspend entirely.
        //
        // This cannot busy-loop. suspend_loop() returns 0 only when (a) USB was
        // present at entry, (b) USB appeared mid-loop, or (c) the device was
        // genuinely unfolded. (c) clears fold_folded, and (a)/(b) are excluded by
        // the usb_cable_connected() term -- so the branch stays quiet until an
        // actual unplug, then fires exactly once (it sets suspend_armed_until_ms
        // non-zero, which is its own guard).
        // Only poll the charger/USB nodes while folded -- unfolded, the result is
        // unused and this runs every second.
        int cable_out = fold_folded &&
                        (read_is_charging() == 0) && !usb_cable_connected();
        // Re-latch as soon as the cable is back, so the next unplug re-arms.
        if (!cable_out) charge_rearm_latched = 0;

        if (fold_folded && suspend_armed_until_ms == 0 &&
            cable_out && !charge_rearm_latched) {
            charge_rearm_latched = 1;
            suspend_armed_until_ms = now + SUSPEND_DELAY_MS;
            // Anchor the shutdown deadline to the ORIGINAL fold edge, never to
            // `now`. Recomputing it here would let a plug/unplug cycle restart
            // the countdown and defer power-off indefinitely. A deadline already
            // in the past stays armed (<= now) so the suspend loop fires the
            // shutdown check immediately rather than skipping it.
            if (g_cfg.power_timeout_s > 0 && fold_since_ms > 0)
                shutdown_armed_until_ms =
                    fold_since_ms + (long long)g_cfg.power_timeout_s * 1000LL;
            else
                shutdown_armed_until_ms = 0;
            log_line("fold re-armed after unplug: suspend in %lldms, shutdown in %llds",
                     SUSPEND_DELAY_MS,
                     shutdown_armed_until_ms > 0
                         ? (shutdown_armed_until_ms - now) / 1000LL : -1LL);
        }

        // Fold-triggered suspend (3 min after fold).
        if (!disabled && !within_safety &&
            suspend_armed_until_ms > 0 && now >= suspend_armed_until_ms) {
            PWR_TRACE_BEGIN("pwr.event.suspend");
            int did_shutdown = suspend_loop();
            suspend_armed_until_ms = 0;
            if (!did_shutdown) {
                // suspend_loop returned 0 either because it resumed on a real
                // unfold OR because it skipped the freeze (USB cable present).
                // The USB-skip path does NOT re-check fold, so re-read the
                // authoritative is_spread here to decide the true state.
                shutdown_armed_until_ms = 0;
                if (read_fold_from_spread() == 1) {
                    // Still physically folded (cable just masked the freeze).
                    // Don't declare unfolded; just leave suspend disarmed. The
                    // charging gate above means we won't re-arm while on charger.
                    last_activity_ms = now_ms();
                    log_line("resumed from suspend, still folded (suspend skipped); staying folded");
                } else {
                    // Genuinely unfolded. CRITICAL: also reset the debounce
                    // bookkeeping. Previously only fold_folded was cleared while
                    // fold_raw_last stayed 1 and fold_change_ms stayed elapsed, so
                    // the next loop iteration saw raw(1) != debounced(0) past the
                    // window and IMMEDIATELY re-latched fold=1 -- a self-
                    // perpetuating ~180s loop that tore down A2DP and wedged the
                    // screen on. Reset raw_last/change_ms to the unfolded baseline.
                    fold_folded = 0;
                    fold_raw_last = 0;
                    fold_change_ms = 0;
                    // This path clears fold state directly instead of going
                    // through the fold-disarm branch, so it must reset the
                    // unplug-re-arm bookkeeping too. Leaving fold_since_ms set
                    // would anchor the NEXT fold's shutdown deadline to this
                    // stale, already-elapsed edge (immediate power-off); leaving
                    // the latch set would suppress the next unplug re-arm.
                    fold_since_ms = 0;
                    charge_rearm_latched = 0;
                    last_activity_ms = now_ms();
                    // Real unfold: restore A2DP on the listener side and re-arm the
                    // idle screen-lock (screen_on was forced 0 at fold).
                    fold_broadcast(0);
                    screen_on = 1;
                    log_line("resumed from suspend, fold state reset to unfolded");
                }
            }
            PWR_TRACE_END();
        }
    }

    log_line("exit");
    led_set_rg(0, 0); // never leave the battery LED stuck on across a daemon restart
    if (inotify_fd >= 0) close(inotify_fd);
    for (int i = 0; i < input_count; ++i) {
        if (input_fds[i] >= 0) close(input_fds[i]);
    }
    if (fold_fd >= 0) close(fold_fd);
    return 0;
}
