// rokid-touchpad-i2c — direct-I2C continuous-position daemon.
//
// Polls the PSoC4000R touchpad chip at I2C addr 0x08 directly via
// /dev/i2c-1, pulling pos1 (reg 0x08) and the touch-active flag
// (reg 0x07 bit 0). Emits a uinput device with EV_ABS/ABS_X +
// BTN_TOUCH so consumers see continuous position rather than the
// kernel driver's discrete keycodes.
//
// Made possible by the firmware patch (image_1v9, flash row 0xEA):
// the chip's pos1 byte at reg 0x08 now reflects the slider centroid
// updated every IRQ (~30-100 Hz) with ~11-step granularity (down
// from the stock 21-step). With ~100 Hz polling here we capture
// every IRQ-aligned update.
//
// I2C-bus contention with the kernel driver: tolerable. Each round
// trip is <1 ms; even at 100 Hz we use ~10% of the bus. The kernel
// driver re-tries on EBUSY and reports keycodes regardless.
//
// Compile via touchpad-daemon/build.sh (NDK arm64 API 32). Deploy
// either via super_4 bake or Tier-2 overlay
// (/data/local/diy-overlay/system/bin/rokid-touchpad-i2c) plus an
// init service entry.
//
// Coexistence with rokid-touchpad-daemon: that daemon grabs
// /dev/input/event1 exclusively to filter keycodes. THIS daemon
// touches neither event1 nor kmsg -- only /dev/i2c-1 and uinput.
// Run both at once: rokid-touchpad-daemon emits scroll-step
// keycodes; rokid-touchpad-i2c emits ABS_X for finer position UX.

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <linux/input.h>
#include <linux/i2c-dev.h>
#include <linux/uinput.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define I2C_BUS              "/dev/i2c-1"
#define PSOC_ADDR            0x08
#define REG_FLAGS            0x07   // bit 0 = touch_active
#define REG_POS1             0x08   // 0..100 firmware-quantized
#define UINPUT_DEV           "/dev/uinput"
#define UINPUT_NAME          "rokid-touchpad-i2c"

#define DEFAULT_POLL_US      10000  // 100 Hz

#ifndef I2C_SLAVE_FORCE
#define I2C_SLAVE_FORCE      0x0706
#endif

#define ABS_X_MIN            0
#define ABS_X_MAX            100

static volatile int running = 1;
static int           debug   = 0;

static void on_signal(int s) { (void)s; running = 0; }

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

// Read one byte from chip register `reg`. Returns the byte 0..255 on
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
        fprintf(stderr, "open %s: %s\n", I2C_BUS, strerror(errno));
        return -1;
    }
    // Force-bind even though psoc_ts_drv_right.ko already owns the addr.
    if (ioctl(fd, I2C_SLAVE_FORCE, PSOC_ADDR) < 0) {
        fprintf(stderr, "I2C_SLAVE_FORCE 0x%02x: %s\n", PSOC_ADDR, strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static int setup_uinput(void) {
    int fd = open(UINPUT_DEV, O_WRONLY | O_NONBLOCK);
    if (fd < 0) { perror("open /dev/uinput"); return -1; }

    if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) goto fail;
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) goto fail;
    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) goto fail;
    if (ioctl(fd, UI_SET_ABSBIT, ABS_X) < 0) goto fail;
    if (ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH) < 0) goto fail;

    struct uinput_abs_setup ab;
    memset(&ab, 0, sizeof(ab));
    ab.code = ABS_X;
    ab.absinfo.minimum    = ABS_X_MIN;
    ab.absinfo.maximum    = ABS_X_MAX;
    ab.absinfo.fuzz       = 0;
    ab.absinfo.flat       = 0;
    ab.absinfo.resolution = ABS_X_MAX;
    if (ioctl(fd, UI_ABS_SETUP, &ab) < 0) goto fail;

    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    us.id.bustype = BUS_VIRTUAL;
    us.id.vendor  = 0x524B;   // 'RK'
    us.id.product = 0x5450;   // 'TP'
    us.id.version = 2;
    strncpy(us.name, UINPUT_NAME, UINPUT_MAX_NAME_SIZE - 1);
    if (ioctl(fd, UI_DEV_SETUP, &us) < 0) goto fail;
    if (ioctl(fd, UI_DEV_CREATE) < 0) goto fail;

    fprintf(stderr, "[touchpad-i2c] uinput up: %s (ABS_X 0..%d, BTN_TOUCH)\n",
            UINPUT_NAME, ABS_X_MAX);
    return fd;
fail:
    perror("uinput setup");
    close(fd);
    return -1;
}

static void emit(int fd, uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) != sizeof(ev)) {
        if (debug) fprintf(stderr, "[err] write input_event %u/%u/%d: %s\n",
                          type, code, value, strerror(errno));
    }
}

static void usage(const char *p) {
    fprintf(stderr,
        "usage: %s [options]\n"
        "  --poll-us N    poll period in microseconds (default %d = 100Hz)\n"
        "  --debug        log every ABS_X / BTN_TOUCH event\n"
        "  --help\n", p, DEFAULT_POLL_US);
}

int main(int argc, char **argv) {
    int poll_us = DEFAULT_POLL_US;

    static struct option opts[] = {
        { "poll-us", required_argument, 0, 'p' },
        { "debug",   no_argument,       0, 'd' },
        { "help",    no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };
    int c;
    while ((c = getopt_long(argc, argv, "p:dh", opts, NULL)) != -1) {
        switch (c) {
        case 'p': poll_us = atoi(optarg); break;
        case 'd': debug = 1; break;
        case 'h': usage(argv[0]); return 0;
        default:  usage(argv[0]); return 2;
        }
    }
    if (poll_us < 1000) poll_us = 1000;

    signal(SIGINT,  on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGHUP,  SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    setsid();

    int i2c = setup_i2c();
    if (i2c < 0) return 1;
    int ui = setup_uinput();
    if (ui < 0) { close(i2c); return 1; }

    long start_ = now_ms();
    fprintf(stderr, "[touchpad-i2c] up. poll=%dus uinput=%s "
            "(velocity-interpolated 1-step output)\n", poll_us, UINPUT_NAME);

    int  prev_touch       = -1;
    int  last_hw_pos      = -1;          // last raw chip pos1 (11-step)
    long last_hw_pos_ms   = 0;
    int  prev_hw_pos      = -1;
    long prev_hw_pos_ms   = 0;
    int  emitted_pos      = -1;          // last value emitted via uinput

    // Velocity: positions/ms between two HW samples. Used to extrapolate
    // intermediate ABS_X events between IRQ-aligned hardware updates.
    double velocity_pps   = 0.0;         // positions per millisecond

    while (running) {
        int flags = i2c_read1(i2c, REG_FLAGS);
        int pos   = i2c_read1(i2c, REG_POS1);
        long now = now_ms();
        if (flags < 0 || pos < 0) {
            usleep(5000);
            continue;
        }
        int touch = flags & 0x01;

        if (touch != prev_touch) {
            emit(ui, EV_KEY, BTN_TOUCH, touch ? 1 : 0);
            emit(ui, EV_SYN, SYN_REPORT, 0);
            if (debug) fprintf(stderr, "[%6ldms] BTN_TOUCH=%d (hw_pos=%d)\n",
                              now - start_, touch, pos);
            prev_touch     = touch;
            // On touch-down: snap state to current pos, no extrapolation.
            if (touch) {
                last_hw_pos    = pos;
                last_hw_pos_ms = now;
                prev_hw_pos    = pos;
                prev_hw_pos_ms = now;
                emitted_pos    = pos;
                emit(ui, EV_ABS, ABS_X, pos);
                emit(ui, EV_SYN, SYN_REPORT, 0);
                velocity_pps = 0.0;
            } else {
                // touch-up — clear state
                last_hw_pos = -1;
                emitted_pos = -1;
                velocity_pps = 0.0;
            }
        }

        if (!touch) {
            usleep(poll_us);
            continue;
        }

        // Detect raw HW step (chip emitted a new IRQ-aligned 11-step value)
        if (pos != last_hw_pos) {
            // Recompute velocity from the last two HW samples.
            long dt_ms = now - last_hw_pos_ms;
            if (dt_ms > 0 && dt_ms < 500) {
                double v = (double)(pos - last_hw_pos) / (double)dt_ms;
                // Light EMA smoothing so noise doesn't whiplash.
                velocity_pps = 0.5 * velocity_pps + 0.5 * v;
            } else {
                velocity_pps = 0.0;
            }
            prev_hw_pos    = last_hw_pos;
            prev_hw_pos_ms = last_hw_pos_ms;
            last_hw_pos    = pos;
            last_hw_pos_ms = now;
            // Snap emitted pos to actual chip value at every HW sample so
            // we never drift away from ground truth.
            if (emitted_pos != pos) {
                emit(ui, EV_ABS, ABS_X, pos);
                emit(ui, EV_SYN, SYN_REPORT, 0);
                if (debug) fprintf(stderr, "[%6ldms] HW    pos=%d v=%.3fp/ms\n",
                                  now - start_, pos, velocity_pps);
                emitted_pos = pos;
            }
        } else {
            // No new HW sample. Extrapolate using current velocity.
            long elapsed = now - last_hw_pos_ms;
            int target = last_hw_pos + (int)(velocity_pps * (double)elapsed + 0.5);
            // Clamp toward the next-likely HW step (don't overshoot).
            int max_extrap = (velocity_pps > 0) ? last_hw_pos + 11
                                                 : last_hw_pos - 11;
            if (velocity_pps > 0 && target > max_extrap) target = max_extrap;
            if (velocity_pps < 0 && target < max_extrap) target = max_extrap;
            if (target < ABS_X_MIN) target = ABS_X_MIN;
            if (target > ABS_X_MAX) target = ABS_X_MAX;
            if (target != emitted_pos) {
                emit(ui, EV_ABS, ABS_X, target);
                emit(ui, EV_SYN, SYN_REPORT, 0);
                if (debug) fprintf(stderr, "[%6ldms] EXTRA pos=%d (hw=%d v=%.3f)\n",
                                  now - start_, target, last_hw_pos, velocity_pps);
                emitted_pos = target;
            }
        }

        usleep(poll_us);
    }

    fprintf(stderr, "[touchpad-i2c] shutdown\n");
    ioctl(ui, UI_DEV_DESTROY);
    close(ui);
    close(i2c);
    return 0;
}
