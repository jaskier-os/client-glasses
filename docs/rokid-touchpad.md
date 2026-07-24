# Rokid Glasses Touchpad -- Hardware & Driver Reference

Reference for the capacitive touch sensor on the right temple of the Rokid Glasses glasses. Captured from a rooted device (Magisk, fw 144) via ADB + sysfs probing on 2026-04-19.

## Hardware

- **Chip:** Cypress PSoC 4000R microcontroller (device-tree node: `psoc4000R@08`)
- **Bus:** I2C-1, address `0x08`
- **IRQ GPIO:** 388
- **SWD debug pins:** powered (`swd_vdd = 1`) -- hardware JTAG/SWD recovery is available
- **Firmware version:** 144 (both chip and code side, from `program_fw`)
- **Role:** The PSoC runs vendor firmware that scans capacitive electrodes, classifies gestures internally, and emits discrete keycodes. Raw coordinates do not leave the chip by default.

## Linux Driver

- **Module:** `/vendor_dlkm/lib/modules/psoc_ts_drv_right.ko` (also listed as `psoc_ts_drv_right` under `/sys/module/`)
- **Driver name:** `Rokid,PSOC-TP-R` (bound at `/sys/bus/i2c/drivers/Rokid,PSOC-TP-R/1-0008/`)
- **Input device name:** `psoc-tp-right` (also `ROKID,PSOC-TP-R` in `/proc/bus/input/devices`)
- **Event node:** `/dev/input/event1`
- **Sysfs base:** `/sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/`

## Currently Emitted Input Events

The registered input device exposes **only `EV_KEY`** (no `EV_ABS`, no `EV_REL`). Emitted keycodes:

| Keycode | Android KeyEvent | Gesture (observed) |
|---|---|---|
| `KEY_UP` (103) | `KEYCODE_DPAD_UP` (19) | swipe up (1-finger) |
| `KEY_DOWN` (108) | `KEYCODE_DPAD_DOWN` (20) | swipe down (1-finger) |
| `KEY_LEFT` (105) | `KEYCODE_DPAD_LEFT` (21) | swipe back (1-finger) |
| `KEY_RIGHT` (106) | `KEYCODE_DPAD_RIGHT` (22) | swipe forward (1-finger) |
| `KEY_ENTER` (28) | `KEYCODE_DPAD_CENTER` (23) / `KEYCODE_ENTER` (66) | tap |
| `KEY_BACK` (158) | `KEYCODE_BACK` (4) | double-tap (Rokid default) |
| `KEY_F13` (183) | `KEYCODE_F3` (290) | two-finger gesture (exact mapping unverified) |
| `KEY_F14` (184) | `KEYCODE_F4` (291) | two-finger gesture (exact mapping unverified) |
| `KEY_PROG1` (148) | `KEYCODE_PROG_RED` (183) | two-finger gesture (exact mapping unverified) |
| `KEY_PROG2` (149) | `KEYCODE_PROG_GREEN` (184) | two-finger gesture (exact mapping unverified) |
| `KEY_PROG3` (202) | `KEYCODE_PROG_YELLOW` (185) | two-finger gesture (exact mapping unverified) |
| `KEY_DASHBOARD` (204) | no direct mapping | two-finger gesture (exact mapping unverified) |

Verify with:
```bash
adb shell getevent -lp /dev/input/event1
adb shell getevent -lt /dev/input/event1   # live stream while touching
```

## Sysfs Attributes (current state on fw 144)

All under `/sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/`. Writable as root.

| Attribute | Current | Notes |
|---|---|---|
| `auto_startup` | `0` | Firmware auto-start flag |
| `deep_sleep` | `0` | Force chip into deep-sleep |
| `enforce_hall` | `0` | Hall-sensor-driven sleep when temples fold |
| `hall` | `1` | Hall sensor state (temples-open) |
| `enforce_psensor` | `1` | Proximity-sensor-driven sleep when off-head |
| `psensor` / `psensor0` / `psensor1` | `1` / `0` / `0` | Proximity sensor readings |
| `proximity_tuning` / `proximity1_tuning` | `0` | Proximity tuning params |
| `low_power` | `1` | Low-power mode flag |
| `pa_en` | `0` | Unknown (power-amp enable?) |
| `program_fw` | `fw_version_chip = 144 fw_version_code = 144` | **DO NOT WRITE arbitrary data -- brick risk.** Reading reports firmware version. |
| `slider_nu` | `0` | **Likely: number of active sliders.** Capacitive chip terminology -- a "slider" reports 1D continuous position. Flipping to non-zero may enable continuous position reporting. |
| `slider_tuning` | `0` | Slider-mode tuning parameters |
| `thres_pct` | `3` | Touch threshold percentage |
| `touch_evt_disable` | `0` | Disables cooked keycode emission if set |
| `touch_evt_reverse` | `0` | Reverses swipe direction (LEFT<->RIGHT etc.) |
| `touch_ft` | `0x80` | Feature flags bitmap (only bit 7 set) -- other bits probably enable alternate reporting modes |
| `two_finger_click_en` | `1` | Two-finger tap recognition enabled |
| `two_finger_flick_en` | `1` | Two-finger swipe recognition enabled |
| `uart_en` | `0` | **Enables PSoC debug UART.** Typical Cypress pattern: UART streams raw capacitive signal + finger position when on. |
| `swd_vdd` | `1` | SWD programmer voltage on |
| `extcon/` | -- | External-connector subsystem link |
| `wakeup/` | -- | Power wakeup handling |
| `input/input1/` | -- | Registered input device |

## Accessing Raw Coordinates (research)

The PSoC firmware tracks finger position internally but does not expose it through `/dev/input/event1`. Three plausible paths to raw data, in ascending order of intrusiveness:

1. **Sysfs slider mode.** Write `1` (or possibly firmware-specific value) to `slider_nu` and/or `slider_tuning`. If the driver's probe path re-registers the input device with `EV_ABS` axes, continuous position becomes available through standard `getevent`. Lowest risk, first thing to try.
2. **Feature-flag register.** `touch_ft = 0x80` -- other bits may enable raw-coord reporting. Sweep bit values while watching `getevent` and new `/dev/input/event*` nodes.
3. **UART debug channel.** Set `uart_en = 1`. Check for a new `/dev/ttyHS*` / `/dev/ttyMSM*` node or a line in `/proc/bus/input/devices`. The raw stream format is vendor-specific; decode by inspecting driver module strings (`strings psoc_ts_drv_right.ko`).
4. **Direct I2C.** Unbind driver (`echo 1-0008 > /sys/bus/i2c/drivers/Rokid,PSOC-TP-R/unbind`), then use `i2cget`/`i2cset`/`i2cdump` on bus 1 addr 0x08. Reverse the command set from the .ko module.

`i2cdetect -y 1` shows `0x08` as `UU` (held by driver). `i2cdetect`, `i2cget`, `i2cset`, `i2cdump` are already installed on the glasses.

## Brick Risk -- Do NOT Touch

- **`program_fw`**: writing to this reflashes the PSoC firmware. Corrupt write or interruption mid-flash = permanent dead touchpad, recoverable only via SWD. Do not write unless deliberately reflashing with a validated binary.
- **Blind I2C writes to unknown registers at addr 0x08**: Cypress chips commonly have a bootloader-unlock command sequence; hitting it accidentally can persist across reboots and soft-brick the touchpad.
- All other sysfs writes are reversible (reboot restores defaults).

## Related Tools on Device

```bash
adb shell which i2cdetect i2cget i2cset i2cdump getevent
# all present under /system/bin/
```
