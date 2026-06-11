# Rokid AR Lite -- DIY Firmware Builder & Overlay Reference

Scripts in this directory build a rooted `super_4.img` for the Rokid AR Lite
glasses and provide a two-tier file-overlay mechanism. No Magisk, no Xposed,
no vbmeta modification -- just binary patches to the stock system partition
plus a small init hook and an `LD_PRELOAD`'d library.

## What the rooted firmware gives you

1. **Root via adbd** -- `adb root` works, shell runs as uid 0 with SELinux
   context `u:r:su:s0`.
2. **SELinux permissive** -- flipped via a bootstat.rc binary patch.
3. **ADB debuggable** -- `ro.debuggable=1`, `ro.secure=0`, `ro.adb.secure=0`.
4. **Two-tier file overlay** -- bake into super_4 (Tier 1) or drop under
   `/data/local/diy-overlay/<abs-path>` for reboot-swap (Tier 2).
5. **`enter-edl` helper** -- `adb shell enter-edl` drops straight to Qualcomm
   9008 mode, so you can re-flash without test-points after the initial
   rooted flash.
6. **Patched PSoC touchpad driver** bound over `/vendor_dlkm/lib/modules/` at
   `on init`; continuous-position mode for the right-temple touchpad.
7. **Touchpad bring-up nudge** at every boot -- writes the sysfs knobs
   required to get the touchpad out of auto-power-off state (see
   `touchpad-nudge.sh` below).
8. **A2DP sink_conn uncapped from 2 to 64** -- via `libsinkconn_hook.so`
   preloaded into zygote; every Bluetooth app process gets a JNI hook that
   writes `A2dpSinkService.mMaxA2dpSinkConnections = 64` at runtime.
9. **Phase-3 wake-word stack** -- custom `sound_trigger.primary.neo.so` HAL
   + QNN runtime + three model `.bin` files baked under `/system/etc/sthal/`.
10. **rokid-touchpad-daemon** service running as root, grabbing
    `/dev/input/event1` and emitting daemon-filtered gestures on an
    embedded `uinput` virtual keyboard (`/dev/input/event2`).

`vbmeta` and `vbmeta_system` are **never** modified. The device's AVB
"orange" state (pre-unlocked by Rokid at manufacturing) tolerates the
dm-verity hashtree mismatch between our super_4 and stock vbmeta's
descriptor; the kernel warns and continues. Everything lives inside
`super_4.img` with the AVB hashtree footer regenerated after each patch.

## Files in this directory

- `root-firmware.sh` -- main builder. One-shot script that produces a
  flashable `super_4.img` from the stock one in `../yodaos-stock-full/`.
- `rawprogram_super4.xml` -- minimal qdl flash map (only GPT + super_4;
  leaves misc, vbmeta, boot, super_5 stock).
- `sinkconn-hook/` -- source + build for `libsinkconn_hook.so`. Has its
  own `build.sh` (NDK arm64 API 32) and `deploy.sh` (Tier-2 adb push).
- `OVERLAY-README.md` -- this file.
- `PROMPT-diy-overlay.txt` -- historical context, kept for reference.

After `root-firmware.sh` runs, the directory also contains:
- `super_4.img` -- the flashable output (848 MB, AVB hashtree regenerated).

Everything else (stock factory binaries, xbl_s_devprg_ns.melf, patch0.xml,
gpt_main0.bin, gpt_backup0.bin, stock super_4.img that we copy from) lives
in `../yodaos-stock-full/` -- that is the single source of truth for
unmodified factory images.

## Two-tier overlay architecture

### Tier 1 -- baked into super_4 (survives `/data` wipe, needs reflash)

`root-firmware.sh` injects the following into `/system/` inside super_4:

| Path                                                                | Purpose                                   |
|---------------------------------------------------------------------|-------------------------------------------|
| `/system/bin/diy-overlay.sh`                                        | Tier-2 bind-mount walker (post-fs-data)   |
| `/system/bin/enter-edl`                                             | Userspace EDL trigger                     |
| `/system/bin/touchpad-nudge.sh`                                     | PSoC sysfs writes on every boot           |
| `/system/bin/rokid-touchpad-daemon`                                 | Gesture daemon binary                     |
| `/system/etc/init/diy-overlay.rc`                                   | All our init hooks (see below)            |
| `/system/etc/init/set-a2dp-sink-conn.rc`                            | `setprop persist.vendor.bt.a2dp.sink_conn 64` |
| `/system/etc/init/hw/init.zygote64_32.rc` **(patched)**             | Adds `setenv LD_PRELOAD /system/lib64/libsinkconn_hook.so` to the zygote service |
| `/system/lib/modules/psoc_ts_drv_right.ko`                          | Patched PSoC driver (bind-mounted at init over `/vendor_dlkm/`) |
| `/system/lib64/libsinkconn_hook.so`                                 | Zygote-wide LD_PRELOAD hook               |
| `/system/lib64/hw/sound_trigger.primary.neo.so`                     | Custom sthal (bind-mounted over `/vendor/lib64/hw/`) |
| `/system/lib64/libQnn{Htp,System,HtpV73Stub}.so`                    | QNN AI runtime ARM libs                   |
| `/system/lib/rfsa/adsp/libQnnHtpV73Skel.so`                         | QNN AI runtime DSP library                |
| `/system/etc/sthal/models/{melspectrogram,embedding_model,sireneviy}.bin` | Wake-word models                          |
| `/system/etc/permissions/privapp-permissions-com.repository.glasses.listener.xml` | Listener privapp permission grants |
| `/system/priv-app/com.repository.glasses.listener/listener.apk`    | 1-byte stub; real APK comes from Tier 2  |

Tier-1 changes require a super_4 reflash via qdl. Do this once per
architectural change (new init service, new stub target, new rc rule).

### Tier 2 -- `/data/local/diy-overlay/<abs-path>` (reboot-swap)

Drop any file under that path on the running device and a bind-mount
covers the target at the next boot. Target must already exist in `/system`
(bind-mount requires a mountpoint). `diy-overlay.rc` handles this in two
ways:

- **Static init-level binds** for paths that need to be overlaid before
  specific early-boot consumers read them. Currently:
  - `/system/lib64/libsinkconn_hook.so` (needed before zygote dlopens it)
  - `/system/priv-app/com.repository.glasses.listener/listener.apk` (need
    this visible to PMS package scan).
- **Generic `diy-overlay.sh` walker** at post-fs-data for everything else.
  Walks `/data/local/diy-overlay/` and bind-mounts each regular file.

Typical dev-iteration cycle:
```bash
# Rebuild + push the hook library without reflashing super
bash sinkconn-hook/build.sh
bash sinkconn-hook/deploy.sh    # push to /data/local/diy-overlay/... + reboot
```

## Building + flashing

Prerequisites (see the checklist at the top of `root-firmware.sh`). All
paths are overridable via env vars.

```bash
cd /media/user/Lobotomite/Repository/Recon/rokid-docs/yodaos-root-full

# 1. Build the hook library.
bash sinkconn-hook/build.sh

# 2. Build the rooted super_4.img. Pulls all inputs from
#    ../yodaos-stock-full/ and the glasses-client module trees, injects
#    everything, regenerates the AVB hashtree, and emits super_4.img +
#    rawprogram_super4.xml in cwd.
./root-firmware.sh

# 3. Put the device in EDL.
adb shell enter-edl      # if rooted already
# otherwise: short the EDL test-point while powering on

# 4. Flash via qdl.
sudo qdl --storage emmc \
    ../yodaos-stock-full/xbl_s_devprg_ns.melf \
    rawprogram_super4.xml \
    ../yodaos-stock-full/patch0.xml

# 5. Device reboots itself. After boot_completed:
adb shell 'cat /sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/pa_en' # expect 1
adb shell 'logcat -d -s sinkconn_hook:V'                               # hook events

# 6. Restore the Tier-2 content (factory flash wipes /data once):
#    - Real listener APK (the 1-byte stub in super is just a mountpoint)
#    - Anything else you previously had under /data/local/diy-overlay/
```

## What `root-firmware.sh` does, in order

1. **Validates inputs** -- stock super_4, sinkconn-hook .so, Phase-3
   artefacts (HAL, QNN libs, wake-word models, privapp xml), touchpad-
   daemon build, patched psoc .ko. Downloads `avbtool.py` if missing.
2. **Stages the overlay payload** under a tmp dir -- overlay engine scripts,
   enter-edl, touchpad-nudge, our init.rc fragments.
3. **Extracts system.img** from stock super_4 (first 848 MB).
4. **Binary-patches build.prop + bootstat.rc** (root, adb, SELinux).
5. **Detects `/system` vs `/` prefix** inside the image via debugfs.
6. **Dumps + patches `init.zygote64_32.rc`** to add `setenv LD_PRELOAD`
   on the zygote service.
7. **Deletes bloat** (BasicDreams, CertInstaller, ManagedProvisioning, etc.)
   to free ~30 MB for the Phase-3 + hook bake. Fonts and framework
   components are never touched.
8. **Injects** everything via a single debugfs session + sets modes.
9. **Verifies** every file exists at expected size.
10. **Sets SELinux xattrs** -- `u:object_r:system_file:s0` for scripts +
    rc files, `u:object_r:system_lib_file:s0` for .so libs. Wrong labels
    here bootloop the device; the script checks critical ones.
11. **Regenerates the AVB hashtree footer** with the stock salt so the
    kernel's dm-verity init still accepts system as valid.
12. **Writes** the patched system.img back into super_4.img (no resize).
13. **Emits** `rawprogram_super4.xml` -- the minimal qdl flash map.

## Runtime init hooks (diy-overlay.rc)

```
on init
    # Patched drivers bound over /vendor paths.
    mount none /system/lib/modules/psoc_ts_drv_right.ko \
               /vendor_dlkm/lib/modules/psoc_ts_drv_right.ko bind
    mount none /system/lib64/hw/sound_trigger.primary.neo.so \
               /vendor/lib64/hw/sound_trigger.primary.neo.so bind
    # Hook lib Tier-2 swap (bind is no-op if the path is absent).
    mount none /data/local/diy-overlay/system/lib64/libsinkconn_hook.so \
               /system/lib64/libsinkconn_hook.so bind

on post-fs-data
    # Listener APK real-content bind.
    mount none /data/local/diy-overlay/system/priv-app/com.repository.glasses.listener/listener.apk \
               /system/priv-app/com.repository.glasses.listener/listener.apk bind
    # Generic Tier-2 walker.
    exec -- /system/bin/sh /system/bin/diy-overlay.sh
    # PSoC touchpad nudge (sysfs writes needed every boot).
    exec -- /system/bin/sh /system/bin/touchpad-nudge.sh

service rokid-touchpad-daemon /system/bin/rokid-touchpad-daemon
    class core
    user root
    group root input
    seclabel u:r:su:s0
```

## The A2DP sink_conn uncap (sinkconn-hook)

`Bluetooth.apk`'s `A2dpSinkService` caps simultaneous A2DP sink connections
at 2 via `mMaxA2dpSinkConnections = Math.min(prop, 2)`. We can't resign
the APK (Rokid's platform key is unavailable -- breaks sharedUserId and
signature-level framework permissions). So we hook at runtime.

- `libsinkconn_hook.so` is loaded via `setenv LD_PRELOAD` on the zygote
  service. LD_PRELOAD is inherited across fork, so every zygote-spawned
  app process has the hook library resident. (Bionic init's keyword is
  `setenv`, not `environment` -- the latter is silently ignored.)
- The library interposes `prctl(int, ...)`. Android's Zygote specialize
  calls `prctl(PR_SET_NAME, <app-short-name>)` shortly after fork, AFTER
  UID drop / SELinux transition / cap drops -- a safe point to spawn a
  worker thread. (`pthread_atfork` child handlers that create threads
  race the specialize path and brick zygote.)
- When the new process name matches `droid.bluetoo*` (Android renames
  `com.android.bluetooth` to last-15-chars `droid.bluetooth`), we spawn
  a worker thread. It resolves `JNI_GetCreatedJavaVMs` via
  `dlsym(RTLD_DEFAULT)` (NDK can't link libart directly), attaches to
  the VM, polls `ActivityThread.getApplication().getClassLoader()` until
  ready, `Class.forName("...A2dpSinkService", cl)`, then
  `SetStaticIntField("mMaxA2dpSinkConnections", 64)`. Re-arms every 5 s
  for 5 min to catch `A2dpSinkService.start()` re-runs that would reset
  the field.
- Works on every boot, every BT-process restart, every BT-adapter cycle.
  No APK resigning, no framework.jar modification, no vbmeta touch,
  no Magisk.

See `sinkconn-hook/src/hook.c` for the implementation.

## The touchpad-nudge

Even with the patched PSoC driver loaded, the kernel driver's
proximity-based auto-power-off will mute the touchpad after ~5 s of
off-head state. The driver exposes a set of sysfs knobs under
`/sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008/` that control its
state machine. Writing the right sequence forces the driver into active
scan mode:

```
auto_startup=1      # enable auto-scan
low_power=0         # disable low-power gating
deep_sleep=0        # disable sleep
pa_en=1             # power amp on
enforce_psensor=1   # arm proximity state machine
sleep 1             # let the state transition settle
enforce_psensor=0   # release -- driver now in active scan
```

`touchpad-nudge.sh` performs this sequence and is triggered at
post-fs-data by `diy-overlay.rc`. Persistent across reboots; no user
action required.

If you're debugging touchpad issues: `grep psoc /proc/interrupts` should
show a steadily increasing IRQ count when you tap. If it's stuck at 1 or
2, the nudge didn't take effect -- re-run it manually:
```bash
adb shell /system/bin/touchpad-nudge.sh
```

## enter-edl (software EDL trigger)

After the first rooted flash the helper lives at `/system/bin/enter-edl`.
It writes the Qualcomm download cookie to whichever of these kernel knobs
exist on the device, then reboots:

- `/sys/module/msm_poweroff/parameters/download_mode`
- `/sys/kernel/dload/emmc_dload`
- `/sys/devices/soc0/select_image`
- `/proc/sys/kernel/reboot_mode`

On the Rokid AR Lite (kernel 5.10) only `select_image` and `emmc_dload`
currently exist. Reboot target `reboot edl` with fallback to plain `reboot`.

```bash
adb shell enter-edl
```

## What's NOT touched (and why)

| Partition / file          | Why not                                                                                      |
|---------------------------|----------------------------------------------------------------------------------------------|
| `vbmeta` / `vbmeta_system`| ABL's `avb_slot_verify()` is strict on the header signature even in orange state -- instant-brick with EDL-only recovery. |
| `misc`                    | Zeroing it destroyed A/B slot state in an earlier session; leave stock.                      |
| `super_5`                 | Contains `system_ext`. Previously patched to delete `BluetoothDsDaService.apk` (shared-UID concern when we were resigning Bluetooth.apk); no longer needed since we hook at runtime. |
| `boot` / `dtbo` / `vendor_boot` | AVB hash descriptors in vbmeta; orange state tolerates a mismatch but avoid churn.     |
| Stock Bluetooth.apk       | Platform-signed; resigning breaks sharedUserId + framework signature-level permissions.       |
| `libbluetooth_qti_jni.so` | Earlier attempts to byte-patch the JNI native-method count contributed to a brick; keep stock. |

## Troubleshooting

**`rmmod psoc_ts_drv_right` reboots the device instantly.**  Rokid's
service stack watches the touchpad input and triggers reboot when it
disappears. The only safe way to swap the driver is at boot via the
init-stage bind-mount described above.

**Touchpad IRQ count stuck at 1, no events from finger taps.**  Run
`adb shell /system/bin/touchpad-nudge.sh` manually and tap again. If that
fixes it, the auto-nudge at post-fs-data may have run before the PSoC
driver fully probed -- check `dmesg | grep psoc` timing.

**`adb disable-verity` followed by reboot lands in fastboot, not Android.**
Rokid's fastboot is a stub -- `flash` and all `oem *` commands return
"unknown command", and there's no software path from fastboot to EDL.
Recovery: put the device in EDL via `enter-edl` or test-point short, then
re-flash via qdl.

**`fastboot oem edl` / `fastboot reboot edl` don't work.**  Use `adb shell
enter-edl` from Android instead.

**qdl says "Waiting for EDL device" but doesn't detect.**  Usually
`ModemManager` winning the enumeration race and sending AT commands to
the EDL port. `sudo systemctl stop ModemManager`. Secondary: wrong USB
cable, or USB hub between PC and glasses.

**qdl exits 1 immediately with no output even though lsusb shows 9008.**
USB stack soft-hung from repeated Sahara resets. Physical unplug/replug
fixes it. Do NOT try programmatic `usbreset` or `unbind`/`bind` -- those
can leave the port in a worse state.

**BT process crashes with `linker_phdr.cpp:193 strtab_size_` after flash.**
Transient, retries recover. This is a framework-level fragility exposed
by `LD_PRELOAD` injection into every zygote child. The hook writes the
sink_conn field on the first successful BT start; subsequent strtab
crashes don't affect the already-written field.

**Listener app (`com.repository.glasses.listener`) not starting.**  The
super contains only a 1-byte stub at
`/system/priv-app/com.repository.glasses.listener/listener.apk`. The real
APK must live under `/data/local/diy-overlay/system/priv-app/com.repository.glasses.listener/listener.apk`
for the post-fs-data bind-mount to overlay it before PMS scan. Push it
from the host:
```bash
adb push AI/clients/glasses/app/build/outputs/apk/debug/app-debug.apk \
    /data/local/diy-overlay/system/priv-app/com.repository.glasses.listener/listener.apk
adb reboot
```

## Safety rules (learned the hard way)

- **Never flash or modify vbmeta / vbmeta_system.** Bootloader verifies
  them; any change instant-bricks.
- **Never zero the misc partition.** Destroys A/B slot state.
- **Never `rmmod psoc_ts_drv_right` on a running system.**  Always use the
  init-stage bind-mount.
- **Never programmatically reset USB.** If qdl can't see the 9008 device,
  physical unplug/replug the cable.
- **Never flash untested boot images to the active A/B slot.** Use the
  inactive slot first so you can `set_active` back on bootloop.
- **vbmeta stays stock. Always.**
