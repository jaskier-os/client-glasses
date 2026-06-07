// rokid-touchpad-daemon
// Listens to the PSoC touchpad via /dev/input/event1 (keycodes for
// touch-on / gesture terminators) and /dev/kmsg (pre_position / p_delta
// lines emitted by the Rokid,PSOC-TP-R driver during finger motion),
// and synthesizes fine-grained scroll-step and touch-released events
// on a new uinput virtual keyboard.
//
// Emits on the virtual device:
//   KEY_KP0 (82) scroll step forward   (Android KEYCODE_NUMPAD_0 = 144)
//   KEY_KP1 (79) scroll step backward  (Android KEYCODE_NUMPAD_1 = 145)
//   KEY_KP2 (80) touch released        (Android KEYCODE_NUMPAD_2 = 146)
//   KEY_KP3 (81) reserved for 2-finger forward  (unused in v1)
//   KEY_KP4 (75) reserved for 2-finger backward (unused in v1)
//
// These codes are chosen because Android's `KeyEvent` class defines
// `KEYCODE_NUMPAD_*` and `Generic.kl` ships the key 82..75 -> NUMPAD_0..4
// mapping on every Android device including Rokid's. KEY_KP0+ above 193
// does NOT have an Android KeyEvent mapping and falls to KEYCODE_UNKNOWN.

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <linux/i2c-dev.h>
#include <sys/stat.h>
// ATRACE for native binary: write directly to ftrace's trace_marker so we
// don't depend on libcutils gating ATRACE_TAG_APP via system properties.
//
// On this device the only working atrace categories are view/input/gfx/am/
// binder_driver -- the "app" category silently disables EVERYTHING (see
// AI/clients/glasses/CLAUDE.md, "Config gotchas"). And ATrace_beginSection
// (NDK <android/trace.h>) only emits when the global ATRACE_TAG_APP bit
// (0x10) is set in debug.atrace.tags.enableflags, which none of those five
// working categories sets. Without this bypass, native ATrace_* calls
// produce nothing in our traces.
//
// Direct trace_marker writes bypass all of that. Whenever ANY ftrace event
// is active (always true under the baseline perfetto config), trace_marker
// writes show up as slices owned by the writer process. Open the fd once,
// reuse it -- per-call overhead is one write() syscall.
static int tt_marker_fd = -1;
static void tt_marker_init(void) {
    tt_marker_fd = open("/sys/kernel/tracing/trace_marker", O_WRONLY | O_CLOEXEC);
    if (tt_marker_fd < 0) {
        tt_marker_fd = open("/sys/kernel/debug/tracing/trace_marker", O_WRONLY | O_CLOEXEC);
    }
    // If both fail, ATRACE wrappers become no-ops (still cheap).
}
static inline void tt_begin(const char *name) {
    if (tt_marker_fd < 0) return;
    char buf[128];
    int n = snprintf(buf, sizeof(buf), "B|%d|%s", (int)getpid(), name);
    if (n > 0) (void)write(tt_marker_fd, buf, (size_t)n);
}
static inline void tt_end(void) {
    if (tt_marker_fd < 0) return;
    (void)write(tt_marker_fd, "E", 1);
}
static inline void tt_int(const char *name, long long v) {
    if (tt_marker_fd < 0) return;
    char buf[160];
    int n = snprintf(buf, sizeof(buf), "C|%d|%s|%lld", (int)getpid(), name, v);
    if (n > 0) (void)write(tt_marker_fd, buf, (size_t)n);
}
#define TT_BEGIN(name) tt_begin(name)
#define TT_END()       tt_end()
#define TT_INT(name, v) tt_int((name), (long long)(v))

static const char *HW_INPUT = "/dev/input/event1";
static const char *KMSG_DEV = "/dev/kmsg";
static const char *UINPUT_DEV = "/dev/uinput";
static const char *UINPUT_NAME = "rokid-touchpad-virt";

// PSoC4000R chip on I2C bus 1 @ 0x08. The patched firmware (v2 in
// touchpad-daemon/firmware-patch/) makes pos1 byte appear at chip
// reg 0x08 with ~11-step granularity; reg 0x07 bit 0 = touch_active.
// We poll these directly to expose ABS_X to consumers, in addition
// to the kmsg-driven scroll keycodes the daemon already emits.
static const char *I2C_BUS  = "/dev/i2c-1";
#define PSOC_ADDR    0x08
#define REG_FLAGS    0x07   // bit 0 = touch_active
#define REG_POS1     0x08   // 0..100 firmware-quantized
#ifndef I2C_SLAVE_FORCE
#define I2C_SLAVE_FORCE 0x0706
#endif
// Activity-gated I2C polling: 100Hz when finger recently touched the pad,
// 5Hz when idle. Saves ~80% of daemon CPU off-head.
//
// Touch-start latency is bounded by the PSoC kernel driver's keycode IRQ
// (event1 path), which fires within one tap of finger-down regardless of
// our i2c sample rate. The 200ms idle interval is just a safety net for
// detecting a touch the keycode path missed.
#define I2C_ACTIVE_INTERVAL_MS  10   // 100 Hz when finger active
#define I2C_IDLE_INTERVAL_MS    200  // 5  Hz when no recent activity
#define I2C_IDLE_AFTER_MS       500  // gap before drop to idle rate

static long last_touch_activity_ms = 0;

static long now_ms(void);
static inline int current_i2c_interval_ms(long now) {
    return (now - last_touch_activity_ms < I2C_IDLE_AFTER_MS)
        ? I2C_ACTIVE_INTERVAL_MS : I2C_IDLE_INTERVAL_MS;
}

// Scaling: effective_step = step_size_slow when finger moves slowly, down to
// step_size_fast for fast flicks. Velocity inferred from time between kmsg
// samples (higher kmsg rate = faster motion).
static int step_size_slow = 40;    // units of travel per scroll step (slow)
static int step_size_fast = 7;     // units of travel per scroll step (fast)
static int slow_gap_ms = 400;      // gap between kmsg samples considered slow
static int fast_gap_ms = 80;       // gap considered fast
static int release_timeout_ms = 1000;
static int release_cooldown_ms = 300;
static int debug = 1;         // default ON -- log everything
static volatile int running = 1;

// Monotonic relative timestamp for log prefixes.
static long start_ms = 0;
char g_event_path[64] = {0};   // path of our uinput's /dev/input/eventN, set in setup_uinput
static void evlog(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
#define LOGP(...)  do { fprintf(stderr, "[%6ldms] ", now_ms() - start_ms); fprintf(stderr, __VA_ARGS__); fflush(stderr); } while (0)
#define LOG(...)   do { if (debug) { LOGP(__VA_ARGS__); } } while (0)

static const char *key_name(uint16_t code) {
    switch (code) {
    case KEY_UP:        return "KEY_UP";
    case KEY_DOWN:      return "KEY_DOWN";
    case KEY_LEFT:      return "KEY_LEFT";
    case KEY_RIGHT:     return "KEY_RIGHT";
    case KEY_ENTER:     return "KEY_ENTER";
    case KEY_BACK:      return "KEY_BACK";
    case KEY_DASHBOARD: return "KEY_DASHBOARD";
    case KEY_PROG1:     return "KEY_PROG1";
    case KEY_PROG2:     return "KEY_PROG2";
    case KEY_PROG3:     return "KEY_PROG3";
    case KEY_F13:       return "KEY_F13";
    case KEY_F14:       return "KEY_F14";
    case KEY_KP0:       return "KEY_KP0";
    case KEY_KP1:       return "KEY_KP1";
    case KEY_KP2:       return "KEY_KP2";
    }
    return "KEY_?";
}

static void on_signal(int sig) { (void)sig; running = 0; }

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static int setup_uinput(void) {
    int fd = open(UINPUT_DEV, O_WRONLY | O_NONBLOCK);
    if (fd < 0) { perror("open /dev/uinput"); return -1; }

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) goto fail;
    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) goto fail;
    if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) goto fail;
    const int keys[] = {
        // Our synthetic scroll keycodes
        KEY_KP0, KEY_KP1, KEY_KP2, KEY_KP3, KEY_KP4,
        // Passthrough keycodes (we grab the hw device and re-emit these here
        // so Android still gets taps / double-taps / real long-press).
        KEY_ENTER, KEY_BACK, KEY_DASHBOARD,
        KEY_PROG1, KEY_PROG2, KEY_PROG3, KEY_F13, KEY_F14,
        KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT,
        // Continuous-position pseudo-button — set when finger is on pad.
        BTN_TOUCH,
    };
    for (unsigned i = 0; i < sizeof(keys)/sizeof(keys[0]); ++i) {
        if (ioctl(fd, UI_SET_KEYBIT, keys[i]) < 0) goto fail;
    }
    // ABS_X axis for raw chip position (0..100 from firmware).
    if (ioctl(fd, UI_SET_ABSBIT, ABS_X) < 0) goto fail;

    struct uinput_abs_setup ab;
    memset(&ab, 0, sizeof(ab));
    ab.code = ABS_X;
    ab.absinfo.minimum = 0;
    ab.absinfo.maximum = 100;
    ab.absinfo.fuzz    = 0;
    ab.absinfo.flat    = 0;
    ab.absinfo.resolution = 100;

    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    us.id.bustype = BUS_VIRTUAL;
    us.id.vendor  = 0x524B;   // 'RK'
    us.id.product = 0x5450;   // 'TP'
    us.id.version = 1;
    strncpy(us.name, UINPUT_NAME, UINPUT_MAX_NAME_SIZE - 1);
    if (ioctl(fd, UI_DEV_SETUP, &us) < 0) goto fail;
    if (ioctl(fd, UI_ABS_SETUP, &ab) < 0) goto fail;
    if (ioctl(fd, UI_DEV_CREATE) < 0) goto fail;

    // chmod the resulting /dev/input/eventN so the listener app (not in
    // group `input`) can open it for reading. UI_GET_SYSNAME gives us
    // the /sys/devices/virtual/input/inputN path; the event node lives
    // alongside as eventM. We resolve via /proc/bus/input/devices since
    // that's simpler and works on every Android kernel we ship.
    // Find our event node by name in /proc/bus/input/devices and chmod
    // 0666 so the listener app (not in group `input`) can read it.
    // Settle briefly because /proc/bus/input/devices updates async after
    // UI_DEV_CREATE.
    for (int attempt = 0; attempt < 20; ++attempt) {
        FILE *f = fopen("/proc/bus/input/devices", "r");
        if (!f) { usleep(50 * 1000); continue; }
        char line[512];
        int matched = 0;
        char chmod_path[64] = {0};
        while (fgets(line, sizeof(line), f)) {
            if (strstr(line, "Name=\"rokid-touchpad-virt\"")) {
                matched = 1;
            } else if (matched && strncmp(line, "H: Handlers=", 12) == 0) {
                char *evp = strstr(line, "event");
                if (evp) {
                    int n = atoi(evp + 5);
                    snprintf(chmod_path, sizeof(chmod_path), "/dev/input/event%d", n);
                }
                break;
            } else if (line[0] == '\n' || line[0] == '\0') {
                matched = 0;
            }
        }
        fclose(f);
        if (chmod_path[0]) {
            // Remember the path so the main loop can re-chmod periodically
            // — on some cold boots udev or another agent re-applies mode
            // 0660 after our initial chmod, leaving the listener locked
            // out with EACCES. A periodic re-chmod is the simplest robust
            // recovery.
            extern char g_event_path[64];
            snprintf(g_event_path, 64, "%s", chmod_path);
            if (chmod(chmod_path, 0666) == 0) {
                fprintf(stderr, "[touchpad] chmod 0666 %s\n", chmod_path);
                evlog("CHMOD 0666 %s\n", chmod_path);
            } else {
                fprintf(stderr, "[touchpad] chmod %s: %s\n", chmod_path, strerror(errno));
                evlog("CHMOD FAIL %s: %s\n", chmod_path, strerror(errno));
            }
            break;
        }
        usleep(50 * 1000);
    }

    fprintf(stderr, "[touchpad] uinput ready: %s (KP0..KP2 scroll keys + ABS_X 0..100 + BTN_TOUCH)\n", UINPUT_NAME);
    return fd;
fail:
    perror("uinput setup");
    close(fd);
    return -1;
}

// One round-trip i2c read of one byte at `reg`. Returns 0..255 on
// success, -1 on error.
static int i2c_read1(int fd, uint8_t reg) {
    if (write(fd, &reg, 1) != 1) return -1;
    uint8_t v;
    if (read(fd, &v, 1) != 1) return -1;
    return v;
}

static int setup_i2c(void) {
    int fd = open(I2C_BUS, O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "open %s: %s (continuing without i2c — ABS_X disabled)\n",
                I2C_BUS, strerror(errno));
        return -1;
    }
    if (ioctl(fd, I2C_SLAVE_FORCE, PSOC_ADDR) < 0) {
        fprintf(stderr, "I2C_SLAVE_FORCE 0x%02x: %s\n", PSOC_ADDR, strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

// Forward decl for emit() defined later in the file.
static void emit(int fd, uint16_t type, uint16_t code, int32_t value);

// Forward decls for session-state vars defined later (used by tick_i2c
// to signal motion to the PROG1 grace logic and the idle watchdog).
static long last_activity_ms;
static int  motion_detected;

// State for velocity-interpolated ABS_X output. The chip emits new pos
// values at IRQ-quantized boundaries (~11-step). Between samples we
// extrapolate at fixed velocity (clamped to one HW step lookahead) so
// consumers see smooth 1-step motion.
static int    i2c_prev_touch     = -1;
static int    i2c_last_hw_pos    = -1;
static int    i2c_emitted_pos    = -1;
static int    i2c_touch_down_pos = -1;
// Forward decl; defined later with other tunables.
static int    i2c_motion_threshold;

static void emit_abs_pos(int uinput_fd, int touch, int pos, long now);
static void release_touch(int uinput_fd, const char *reason);
static int  touch_active;
static long pending_prog1_at;

// Polled once per main-loop tick (active/idle gated). Reads chip's flag &
// pos1 over I2C-dev, emits BTN_TOUCH + ABS_X with extrapolation.
static void tick_i2c(int i2c_fd, int uinput_fd) {
    if (i2c_fd < 0) return;
    TT_BEGIN("tt.i2c.tick");
    TT_BEGIN("tt.i2c.read");
    int flags = i2c_read1(i2c_fd, REG_FLAGS);
    int pos   = i2c_read1(i2c_fd, REG_POS1);
    TT_END();
    if (flags < 0 || pos < 0) { TT_END(); return; }
    int touch = flags & 0x01;
    long now = now_ms();
    if (touch == 1) {
        last_touch_activity_ms = now;
        // Keep the session alive against the idle watchdog while the finger
        // is still on the pad. Without this, a stationary hold gets killed
        // by release_timeout_ms before custom_long_press_ms can fire KP3.
        last_activity_ms = now;
    }
    TT_BEGIN("tt.i2c.emit_abs");
    emit_abs_pos(uinput_fd, touch, pos, now);
    TT_END();
    TT_END();
}

static void emit_abs_pos(int uinput_fd, int touch, int pos, long now) {
    // Emit BTN_TOUCH transitions, then raw HW pos only. Velocity-based
    // extrapolation was removed because it caused visible "jumping":
    // when the finger paused or reversed, the extrapolator pushed up to
    // ±11 units past the true position, then snapped back when the next
    // HW sample contradicted it. App-side smoothing is the right place
    // for any visual interpolation.
    if (touch != i2c_prev_touch) {
        emit(uinput_fd, EV_KEY, BTN_TOUCH, touch ? 1 : 0);
        emit(uinput_fd, EV_SYN, SYN_REPORT, 0);
        evlog("BTN_TOUCH=%d hw_pos=%d\n", touch, pos);
        if (debug) LOG("ABS      BTN_TOUCH=%d (hw_pos=%d)\n", touch, pos);
        i2c_prev_touch = touch;
        if (touch) {
            i2c_last_hw_pos = pos;
            i2c_emitted_pos = pos;
            i2c_touch_down_pos = pos;
            emit(uinput_fd, EV_ABS, ABS_X, pos);
            emit(uinput_fd, EV_SYN, SYN_REPORT, 0);
            evlog("ABS_X=%d (down)\n", pos);
        } else {
            i2c_last_hw_pos = -1;
            i2c_emitted_pos = -1;
            i2c_touch_down_pos = -1;
            // Do NOT call release_touch here. The i2c chip momentarily glitches
            // BTN_TOUCH to 0 during sustained holds (observed: pos jumps to 1
            // for one tick, then back to true pos). Trusting it killed the
            // session and prevented the KEY_KP3 long-press timer from firing.
            // Real release is signalled by the firmware's terminator keycodes
            // (KEY_ENTER / KEY_UP / KEY_DOWN / KEY_BACK) or the idle watchdog.
        }
        (void)now;
        return;
    }
    if (!touch) return;

    if (pos != i2c_emitted_pos) {
        emit(uinput_fd, EV_ABS, ABS_X, pos);
        emit(uinput_fd, EV_SYN, SYN_REPORT, 0);
        evlog("ABS_X=%d\n", pos);
        if (debug) LOG("ABS HW   pos=%d\n", pos);
        i2c_last_hw_pos = pos;
        i2c_emitted_pos = pos;
        int delta_from_down = (i2c_touch_down_pos >= 0) ? abs(pos - i2c_touch_down_pos) : -1;
        evlog("I2C_DELTA pos=%d down=%d delta=%d threshold=%d motion=%d\n",
              pos, i2c_touch_down_pos, delta_from_down, i2c_motion_threshold, motion_detected);
        if (i2c_touch_down_pos >= 0 && delta_from_down > i2c_motion_threshold) {
            if (!motion_detected) {
                evlog("MOTION_DETECTED via i2c (delta=%d > %d)\n", delta_from_down, i2c_motion_threshold);
            }
            motion_detected = 1;
            last_activity_ms = now_ms();
        }
    }
    (void)now;
}

static uint64_t tt_uinput_writes = 0;
static void emit(int fd, uint16_t type, uint16_t code, int32_t value) {
    TT_BEGIN("tt.uinput.write");
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    ssize_t wr = write(fd, &ev, sizeof(ev));
    if (wr < 0) LOG("emit write FAILED: type=%u code=%u val=%d errno=%s\n",
                    type, code, value, strerror(errno));
    tt_uinput_writes++;
    TT_INT("tt.uinput.events_total", (int64_t)tt_uinput_writes);
    TT_END();
}

// Fire a keycode as DOWN -> SYN -> UP -> SYN
static void emit_key(int fd, uint16_t code) {
    emit(fd, EV_KEY, code, 1);
    emit(fd, EV_SYN, SYN_REPORT, 0);
    emit(fd, EV_KEY, code, 0);
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

// Always-on rolling event log so we can debug pill jumps / freezes
// retroactively. One line per BTN_TOUCH / EV_ABS emit, with monotonic
// timestamp. Rotated at 1 MB to .1 so we keep ~last few minutes worth.
static FILE *evlog_fp = NULL;
static long  evlog_check_at_ms = 0;
static const char *EVLOG_PATH  = "/sdcard/touchpad-events.log";
static const char *EVLOG_PREV  = "/sdcard/touchpad-events.log.1";
static const long  EVLOG_LIMIT = 1L * 1024 * 1024;
static void evlog(const char *fmt, ...) {
    long now = now_ms();
    if (evlog_fp == NULL || now - evlog_check_at_ms > 1000) {
        evlog_check_at_ms = now;
        struct stat st;
        if (evlog_fp != NULL && stat(EVLOG_PATH, &st) == 0 && st.st_size > EVLOG_LIMIT) {
            fclose(evlog_fp); evlog_fp = NULL;
            rename(EVLOG_PATH, EVLOG_PREV);
        }
        if (evlog_fp == NULL) {
            evlog_fp = fopen(EVLOG_PATH, "a");
            if (evlog_fp) setvbuf(evlog_fp, NULL, _IOLBF, 0);
        }
    }
    if (!evlog_fp) return;
    va_list ap;
    fprintf(evlog_fp, "[%6ldms] ", now - start_ms);
    va_start(ap, fmt);
    vfprintf(evlog_fp, fmt, ap);
    va_end(ap);
}

// Runtime gate for KP0/KP1 scroll-key emission. When the file
// /sdcard/rokid-touchpad-keys-off exists, accumulate_motion()
// skips emit. Used by the glasses app while it is consuming raw
// EV_ABS positions for tab-bar drag (TAB_NAV mode) -- NUMPAD scroll
// keycodes would otherwise flood Android's input dispatcher at
// ~100 Hz and stall the UI thread. We stat() at most every 50 ms so
// the per-event cost is negligible.
static int keys_suppressed(void) {
    static long  cached_at_ms = 0;
    static int   cached_value = 0;
    long now = now_ms();
    if (now - cached_at_ms < 50) return cached_value;
    cached_at_ms = now;
    struct stat st;
    cached_value = (stat("/sdcard/rokid-touchpad-keys-off", &st) == 0) ? 1 : 0;
    return cached_value;
}

// Touch-session state (touch_active and pending_prog1_at are tentatively
// declared above so emit_abs_pos can reach them; defined here with init).
static long last_activity_ms = 0;
static long last_kmsg_ms     = 0;   // time of last pre_position sample
static long cooldown_until_ms = 0;   // ignore kmsg lines until this timestamp
static int  steps_emitted    = 0;   // tally for debug
static int  residual         = 0;   // signed sub-threshold motion across kmsg samples
static int  motion_detected  = 0;   // set when any motion signal fires in this session
static int  long_press_grace_ms = 1500; // wait N ms after KEY_PROG1 to see if motion arrives
// Time-based long-press: fire KEY_KP3 when finger has been on the pad for
// custom_long_press_ms with no motion. Independent of firmware KEY_PROG1.
static long touch_start_ms       = 0;
static int  long_press_fired     = 0;
static int  custom_long_press_ms = 800;
// absolute pos delta from touch-down required to register as motion (rejects sensor jitter on stationary finger)
static int  i2c_motion_threshold = 4;

// Keys we proxy through from the hardware input device to the virtual uinput
// (so other consumers of the touchpad continue to see them), excluding ones
// we suppress because they misfire on slow drags.
static void proxy_key(int uinput_fd, uint16_t code) {
    emit(uinput_fd, EV_KEY, code, 1);
    emit(uinput_fd, EV_SYN, SYN_REPORT, 0);
    emit(uinput_fd, EV_KEY, code, 0);
    emit(uinput_fd, EV_SYN, SYN_REPORT, 0);
}

// Linearly interpolate between step_size_slow and step_size_fast based on
// the time gap since the previous kmsg sample. Smaller gap (faster finger)
// -> smaller effective step -> more scroll events emitted.
static int current_step_size(long gap_ms) {
    if (gap_ms <= fast_gap_ms) return step_size_fast;
    if (gap_ms >= slow_gap_ms) return step_size_slow;
    // linear: at gap=fast we return step_fast, at gap=slow we return step_slow
    long range = slow_gap_ms - fast_gap_ms;
    long pos   = gap_ms - fast_gap_ms;
    return step_size_fast + (int)((step_size_slow - step_size_fast) * pos / range);
}

static void release_touch(int uinput_fd, const char *reason) {
    if (!touch_active) return;
    LOG("RELEASE  reason=%-10s steps_emitted=%d residual=%d -> emit KEY_KP2, cooldown=%dms\n",
        reason, steps_emitted, residual, release_cooldown_ms);
    emit_key(uinput_fd, KEY_KP2);
    touch_active     = 0;
    steps_emitted    = 0;
    residual         = 0;
    last_kmsg_ms     = 0;
    pending_prog1_at = 0;
    last_activity_ms = 0;
    touch_start_ms   = 0;
    long_press_fired = 0;
    i2c_touch_down_pos = -1;
    cooldown_until_ms = now_ms() + release_cooldown_ms;
}

static void handle_hw_event(int uinput_fd, const struct input_event *ev) {
    // Log EVERY event from /dev/input/event1 so we can see firing patterns.
    LOG("HW       type=%-5u code=%-5u(%s) value=%d\n",
        ev->type, ev->code, ev->type == EV_KEY ? key_name(ev->code) : "--",
        ev->value);

    if (ev->type != EV_KEY) return;
    if (ev->value != 1) return;   // only DOWN transitions

    switch (ev->code) {
    case KEY_DASHBOARD:
        LOG("SESSION  touch-on (KEY_DASHBOARD) -> open session + proxy\n");
        evlog("SESSION_START dashboard\n");
        touch_active      = 1;
        steps_emitted     = 0;
        residual          = 0;
        last_kmsg_ms      = 0;
        motion_detected   = 0;
        pending_prog1_at  = 0;
        cooldown_until_ms = 0;
        last_activity_ms  = now_ms();
        touch_start_ms    = now_ms();
        long_press_fired  = 0;
        proxy_key(uinput_fd, KEY_DASHBOARD);
        break;
    case KEY_LEFT:
    case KEY_RIGHT:
        // These fire mid-swipe as the firmware classifies. We DON'T proxy
        // them -- our scroll-step keys (KP0/KP1) carry the motion info.
        LOG("SESSION  intermediate %s (motion confirmed, not proxied)\n", key_name(ev->code));
        if (!motion_detected) {
            evlog("MOTION_DETECTED via hw_key %s\n", key_name(ev->code));
        }
        motion_detected  = 1;
        last_activity_ms = now_ms();
        break;
    case KEY_UP:
    case KEY_DOWN:
        // Final swipe classification. Not proxied to avoid double-counting.
        LOG("SESSION  terminator %s -> release (not proxied)\n", key_name(ev->code));
        release_touch(uinput_fd, "terminator");
        break;
    case KEY_PROG1:
        // The firmware fires KEY_PROG1 as soon as the finger has been on the
        // pad for ~500ms, BEFORE it has had a chance to classify a slow
        // drag. If motion is detected at any point during touch (before
        // PROG1 or during the 1500ms grace), cancel; otherwise proxy as a
        // real long-press.
        if (motion_detected) {
            LOG("SESSION  KEY_PROG1 dropped (motion already seen)\n");
        } else {
            pending_prog1_at = now_ms() + long_press_grace_ms;
            LOG("SESSION  KEY_PROG1 deferred %dms (motion-flag check at expiry)\n", long_press_grace_ms);
        }
        // NOTE: do NOT release_touch here. The session must stay open for
        // the grace window so the motion-flag check can run at expiry.
        break;
    case KEY_ENTER:
    case KEY_BACK:
    case KEY_PROG2:
    case KEY_PROG3:
    case KEY_F13:
    case KEY_F14:
        // Tap, double-tap, two-finger gestures -- proxy through and close.
        LOG("SESSION  proxy %s -> release\n", key_name(ev->code));
        proxy_key(uinput_fd, ev->code);
        release_touch(uinput_fd, "terminator");
        break;
    default:
        break;
    }
}

// Queue up motion for the pacing tick to drain.
// Accumulate motion into `residual`; emit one scroll keycode per effective
// step_size units (which depends on how fast the finger is moving).
static void accumulate_motion(int uinput_fd, int direction, int magnitude, long gap_ms) {
    if (magnitude <= 0) return;
    int eff_step = current_step_size(gap_ms);
    if (keys_suppressed()) {
        // App is consuming raw ABS_X; don't flood input dispatcher with KP keys.
        residual = 0;
        last_activity_ms = now_ms();
        evlog("KP_SUPPRESS dir=%+d mag=%d (app drag active)\n", direction, magnitude);
        return;
    }
    residual += direction * magnitude;
    int emitted_fwd = 0, emitted_back = 0;
    while (residual >=  eff_step) { emit_key(uinput_fd, KEY_KP0); residual -= eff_step; ++emitted_fwd; }
    while (residual <= -eff_step) { emit_key(uinput_fd, KEY_KP1); residual += eff_step; ++emitted_back; }
    if (emitted_fwd || emitted_back) {
        evlog("KP fwd=%d back=%d step=%d gap=%ldms\n",
              emitted_fwd, emitted_back, eff_step, gap_ms);
    }
    int emitted = emitted_fwd + emitted_back;
    if (emitted > 0) {
        steps_emitted += emitted;
        LOG("EMIT     gap=%ldms eff_step=%d %dx KEY_KP0 (fwd) %dx KEY_KP1 (back) residual=%d total=%d\n",
            gap_ms, eff_step, emitted_fwd, emitted_back, residual, steps_emitted);
    } else {
        LOG("EMIT     gap=%ldms eff_step=%d sub-threshold mag=%d dir=%+d residual=%d\n",
            gap_ms, eff_step, magnitude, direction, residual);
    }
    last_activity_ms = now_ms();
}

static void handle_kmsg_line(int uinput_fd, const char *line) {
    TT_BEGIN("tt.kmsg.parse");
    // Log any psoc-related line for visibility.
    if (strstr(line, "PSOC-TP") || strstr(line, "psoc_ts_") ||
        strstr(line, "pre_position") || strstr(line, "p_delta") ||
        strstr(line, "slider_")) {
        // Find payload after the first ';' (kmsg format: "<prio>,<seq>,<ts>,<flag>;<text>")
        const char *payload = strchr(line, ';');
        payload = payload ? payload + 1 : line;
        LOG("KMSG     %s\n", payload);
    }

    // Parse the payload: "pre_position = %d position = %d p_delta = %d"
    const char *p = strstr(line, "pre_position = ");
    if (!p) { TT_END(); return; }
    int pre = 0, pos = 0, delta = 0;
    if (sscanf(p, "pre_position = %d position = %d p_delta = %d",
               &pre, &pos, &delta) != 3) {
        LOG("KMSG     parse FAILED\n");
        TT_END();
        return;
    }

    if (!touch_active) {
        if (now_ms() < cooldown_until_ms) {
            LOG("KMSG     pre=%d pos=%d delta=%d DROPPED (cooldown, %ldms left)\n",
                pre, pos, delta, cooldown_until_ms - now_ms());
            TT_END();
            return;
        }
        LOG("KMSG     pre=%d pos=%d delta=%d DROPPED (no session)\n", pre, pos, delta);
        TT_END();
        return;
    }

    long now = now_ms();
    long gap_ms = last_kmsg_ms == 0 ? slow_gap_ms : (now - last_kmsg_ms);
    last_kmsg_ms = now;

    int direction = (pos > pre) ? +1 : -1;
    int magnitude = delta >= 0 ? delta : -delta;
    if (!motion_detected) {
        evlog("MOTION_DETECTED via kmsg pre=%d pos=%d delta=%d\n", pre, pos, delta);
    }
    motion_detected = 1;   // any kmsg line = motion, KEY_PROG1 should be suppressed
    LOG("KMSG     pre=%d pos=%d delta=%d dir=%+d mag=%d gap=%ldms\n",
        pre, pos, delta, direction, magnitude, gap_ms);
    TT_END();  // close tt.kmsg.parse before motion processing
    TT_BEGIN("tt.input.process");
    accumulate_motion(uinput_fd, direction, magnitude, gap_ms);
    TT_END();
}

// Read as many lines as are currently available; preserve any partial
// line in `buf` for the next call.
static int kmsg_buf_len = 0;
static char kmsg_buf[8192];

static void drain_kmsg(int kmsg_fd, int uinput_fd) {
    TT_BEGIN("tt.kmsg.drain");
    int lines_processed = 0;
    for (;;) {
        int space = (int)sizeof(kmsg_buf) - 1 - kmsg_buf_len;
        if (space <= 0) { kmsg_buf_len = 0; space = sizeof(kmsg_buf) - 1; }
        ssize_t n = read(kmsg_fd, kmsg_buf + kmsg_buf_len, space);
        if (n <= 0) {
            if (n < 0 && errno == EPIPE) {
                // Kernel ring-buffer overflow: just continue.
                continue;
            }
            break;
        }
        kmsg_buf_len += (int)n;
        kmsg_buf[kmsg_buf_len] = '\0';

        // Process complete lines
        char *start = kmsg_buf;
        for (;;) {
            char *nl = memchr(start, '\n', (size_t)(kmsg_buf + kmsg_buf_len - start));
            if (!nl) break;
            *nl = '\0';
            handle_kmsg_line(uinput_fd, start);
            start = nl + 1;
            lines_processed++;
        }
        // Slide any partial tail to the beginning
        int remaining = (int)(kmsg_buf + kmsg_buf_len - start);
        if (remaining > 0) memmove(kmsg_buf, start, (size_t)remaining);
        kmsg_buf_len = remaining;
    }
    TT_INT("tt.kmsg.lines_per_drain", lines_processed);
    if (lines_processed > 0) last_touch_activity_ms = now_ms();
    TT_END();
}

static void drain_hw(int hw_fd, int uinput_fd) {
    TT_BEGIN("tt.hw.drain");
    int total = 0;
    struct input_event evs[32];
    for (;;) {
        ssize_t n = read(hw_fd, evs, sizeof(evs));
        if (n <= 0) break;
        int count = (int)(n / (ssize_t)sizeof(struct input_event));
        for (int i = 0; i < count; ++i) {
            TT_BEGIN("tt.hw.event");
            handle_hw_event(uinput_fd, &evs[i]);
            TT_END();
        }
        total += count;
    }
    TT_INT("tt.hw.events_per_drain", total);
    if (total > 0) last_touch_activity_ms = now_ms();
    TT_END();
}

static void usage(const char *argv0) {
    fprintf(stderr,
        "usage: %s [options]\n"
        "  --step-slow N       units/step when finger is slow (default %d)\n"
        "  --step-fast N       units/step when finger is fast (default %d)\n"
        "  --slow-ms N         kmsg gap considered slow (default %d)\n"
        "  --fast-ms N         kmsg gap considered fast (default %d)\n"
        "  --timeout-ms N      watchdog release timeout in ms (default %d)\n"
        "  --cooldown-ms N     ignore kmsg lines for N ms after release (default %d)\n"
        "  --quiet             suppress per-event logging\n",
        argv0, step_size_slow, step_size_fast, slow_gap_ms, fast_gap_ms,
        release_timeout_ms, release_cooldown_ms);
}

int main(int argc, char **argv) {
    static struct option opts[] = {
        { "step-slow",    required_argument, 0, 'S' },
        { "step-fast",    required_argument, 0, 'F' },
        { "slow-ms",      required_argument, 0, 'l' },
        { "fast-ms",      required_argument, 0, 'f' },
        { "timeout-ms",   required_argument, 0, 't' },
        { "cooldown-ms",  required_argument, 0, 'c' },
        { "quiet",        no_argument,       0, 'q' },
        { "help",         no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };
    int c;
    while ((c = getopt_long(argc, argv, "S:F:l:f:t:c:qh", opts, NULL)) != -1) {
        switch (c) {
        case 'S': step_size_slow = atoi(optarg); break;
        case 'F': step_size_fast = atoi(optarg); break;
        case 'l': slow_gap_ms = atoi(optarg); break;
        case 'f': fast_gap_ms = atoi(optarg); break;
        case 't': release_timeout_ms = atoi(optarg); break;
        case 'c': release_cooldown_ms = atoi(optarg); break;
        case 'q': debug = 0; break;
        case 'h': usage(argv[0]); return 0;
        default:  usage(argv[0]); return 2;
        }
    }
    if (step_size_slow < 1) step_size_slow = 1;
    if (step_size_fast < 1) step_size_fast = 1;
    if (release_timeout_ms < 50) release_timeout_ms = 50;

    tt_marker_init();

    signal(SIGINT,  on_signal);
    signal(SIGTERM, on_signal);
    // adb shell HUPs the process group when the shell session ends; ignore it
    // so we keep running after "adb shell nohup ... &" returns.
    signal(SIGHUP,  SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    // Detach from the adb shell session's controlling terminal / process group.
    setsid();

    int uinput_fd = setup_uinput();
    if (uinput_fd < 0) return 1;

    int hw_fd = open(HW_INPUT, O_RDONLY | O_NONBLOCK);
    if (hw_fd < 0) {
        fprintf(stderr, "open %s: %s\n", HW_INPUT, strerror(errno));
        return 1;
    }
    // EVIOCGRAB: take exclusive ownership of the hardware input device.
    // Android InputReader will NOT see any of event1's events -- only what we
    // synthesize on the uinput virtual device. This is how we suppress the
    // misfiring KEY_PROG1 (AI-assistant trigger) during slow drags.
    //
    // On boot this can race: if some other process (or a previous daemon
    // instance whose fd isn't fully torn down) already holds an exclusive
    // grab, ioctl returns EBUSY. Retry with backoff up to ~2 seconds rather
    // than silently running un-grabbed -- partial grabs cause every raw
    // hw event to leak to Android in addition to our synthesized ones.
    int grab_ok = 0;
    for (int attempt = 0; attempt < 40; ++attempt) {
        if (ioctl(hw_fd, EVIOCGRAB, 1) == 0) { grab_ok = 1; break; }
        if (errno != EBUSY) break;            // only retry on EBUSY
        struct timespec ts = { .tv_sec = 0, .tv_nsec = 50 * 1000000L };
        nanosleep(&ts, NULL);
    }
    if (!grab_ok) {
        fprintf(stderr,
                "EVIOCGRAB failed: %s (Android will still see raw events). "
                "Another process is holding an exclusive grab on event1.\n",
                strerror(errno));
    } else {
        LOGP("GRAB     /dev/input/event1 acquired exclusively\n");
    }

    int kmsg_fd = open(KMSG_DEV, O_RDONLY | O_NONBLOCK);
    if (kmsg_fd < 0) {
        fprintf(stderr, "open %s: %s\n", KMSG_DEV, strerror(errno));
        return 1;
    }
    // Seek to end so we only see new messages.
    lseek(kmsg_fd, 0, SEEK_END);

    // Open /dev/i2c-1 to poll the chip's pos1 byte directly. This runs
    // in parallel with the kmsg-driven scroll-key path; the two are
    // independent and don't fight (i2c-dev acquires the bus per
    // transaction; the kernel driver retries on EBUSY).
    int i2c_fd = setup_i2c();

    start_ms = now_ms();
    LOGP("START    step_slow=%d step_fast=%d slow_ms=%d fast_ms=%d timeout_ms=%d cooldown_ms=%d\n",
         step_size_slow, step_size_fast, slow_gap_ms, fast_gap_ms,
         release_timeout_ms, release_cooldown_ms);
    LOGP("START    virtual device: %s   hw input: %s   kmsg: %s   i2c: %s\n",
         UINPUT_NAME, HW_INPUT, KMSG_DEV,
         i2c_fd >= 0 ? I2C_BUS : "(disabled)");

    struct pollfd pfds[2];
    pfds[0].fd = hw_fd;   pfds[0].events = POLLIN;
    pfds[1].fd = kmsg_fd; pfds[1].events = POLLIN;

    long next_i2c_tick_ms = now_ms();

    while (running) {
        // Cap poll timeout so we wake up in time to service a deferred
        // KEY_PROG1 if one is pending. Also cap to the i2c poll period
        // so we tick the i2c-direct ABS_X path on schedule.
        int timeout = touch_active ? release_timeout_ms : -1;
        if (pending_prog1_at) {
            long remaining = pending_prog1_at - now_ms();
            if (remaining <= 0) timeout = 0;
            else if (timeout < 0 || remaining < timeout) timeout = (int)remaining;
        }
        if (touch_active && touch_start_ms && !long_press_fired && !motion_detected) {
            long remaining = touch_start_ms + custom_long_press_ms - now_ms();
            if (remaining <= 0) timeout = 0;
            else if (timeout < 0 || remaining < timeout) timeout = (int)remaining;
        }
        if (i2c_fd >= 0) {
            long remaining = next_i2c_tick_ms - now_ms();
            if (remaining <= 0) timeout = 0;
            else if (timeout < 0 || remaining < timeout) timeout = (int)remaining;
        }
        TT_INT("tt.poll.timeout_ms", timeout);
        TT_BEGIN("tt.poll");
        int rc = poll(pfds, 2, timeout);
        TT_END();
        if (rc < 0) {
            if (errno == EINTR) continue;
            perror("poll");
            break;
        }
        TT_INT("tt.poll.rc", rc);
        if (pfds[0].revents & POLLIN) drain_hw(hw_fd, uinput_fd);
        if (pfds[1].revents & POLLIN) drain_kmsg(kmsg_fd, uinput_fd);

        // i2c-direct tick: poll chip pos1 + touch flag, emit ABS_X /
        // BTN_TOUCH with velocity interpolation between chip IRQs.
        // Runs at activity-gated cadence (10ms active / 200ms idle).
        if (i2c_fd >= 0 && now_ms() >= next_i2c_tick_ms) {
            tick_i2c(i2c_fd, uinput_fd);
            long now = now_ms();
            int iv = current_i2c_interval_ms(now);
            next_i2c_tick_ms = now + iv;
            TT_INT("tt.i2c.interval_ms", iv);
        }

        // Watchdog: re-chmod our /dev/input/eventN to 0666 in case udev
        // or another agent reset its mode after our setup_uinput() chmod.
        // Aggressive (every 1s) for the first 30s after start, then once
        // per 30s as a safety net.
        {
            static long next_chmod_ms = 0;
            long now = now_ms();
            if (g_event_path[0] && now >= next_chmod_ms) {
                struct stat st;
                if (stat(g_event_path, &st) == 0 && (st.st_mode & 0666) != 0666) {
                    if (chmod(g_event_path, 0666) == 0) {
                        evlog("CHMOD WATCHDOG fixed %s\n", g_event_path);
                    }
                }
                long uptime = now - start_ms;
                next_chmod_ms = now + (uptime < 30000 ? 1000 : 30000);
            }
        }

        // Deferred long-press: proxy KEY_PROG1 iff no motion appeared during
        // the grace window. Motion during the wait cancels the proxy.
        if (pending_prog1_at && now_ms() >= pending_prog1_at) {
            TT_BEGIN("tt.prog1.defer");
            if (motion_detected) {
                LOG("SESSION  KEY_PROG1 CANCELLED (motion detected during grace)\n");
                pending_prog1_at = 0;
                release_touch(uinput_fd, "prog1-cancel-motion");
            } else {
                LOG("SESSION  KEY_PROG1 proxied (no motion during grace, hold confirmed)\n");
                proxy_key(uinput_fd, KEY_PROG1);
                pending_prog1_at = 0;
                release_touch(uinput_fd, "prog1-confirmed");
            }
            TT_END();
        }

        // Time-based long-press: fire KEY_KP3 once per session when finger
        // has been on the pad for custom_long_press_ms with no motion.
        // Periodic state dump during touch (diagnostic)
        if (touch_active) {
            static long last_state_log_ms = 0;
            if (now_ms() - last_state_log_ms > 250) {
                evlog("STATE touch_active=%d touch_start_ms=%ld elapsed=%ld long_press_fired=%d motion_detected=%d threshold=%d\n",
                      touch_active, touch_start_ms,
                      touch_start_ms ? (now_ms() - touch_start_ms) : -1,
                      long_press_fired, motion_detected, custom_long_press_ms);
                last_state_log_ms = now_ms();
            }
        }
        if (touch_active && touch_start_ms && !long_press_fired && !motion_detected &&
            now_ms() - touch_start_ms >= custom_long_press_ms) {
            LOG("SESSION  custom long-press fires KEY_KP3 (no motion for %dms)\n",
                custom_long_press_ms);
            evlog("KP3_FIRE held=%ldms threshold=%d\n",
                  now_ms() - touch_start_ms, custom_long_press_ms);
            proxy_key(uinput_fd, KEY_KP3);
            long_press_fired = 1;
        }
        // Diagnostic: when motion blocks the long-press, record it once per session
        if (touch_active && touch_start_ms && !long_press_fired && motion_detected) {
            static long last_block_log_ms = 0;
            if (now_ms() - last_block_log_ms > 250) {
                evlog("KP3_BLOCKED held=%ldms motion_detected=1\n",
                      now_ms() - touch_start_ms);
                last_block_log_ms = now_ms();
            }
        }

        // Idle watchdog: if nothing new happened for release_timeout_ms,
        // close the session (no terminator keycode was emitted).
        if (touch_active &&
            last_activity_ms &&
            now_ms() - last_activity_ms > release_timeout_ms) {
            release_touch(uinput_fd, "watchdog");
        }
    }

    LOGP("SHUTDOWN signal received, tearing down uinput\n");
    ioctl(uinput_fd, UI_DEV_DESTROY);
    close(uinput_fd);
    close(hw_fd);
    close(kmsg_fd);
    if (i2c_fd >= 0) close(i2c_fd);
    return 0;
}
