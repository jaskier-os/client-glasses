# PalTest ACD Probe — Findings & Invocation Plan (Task 2 / Gate 4)

## TL;DR

**The on-device `/vendor/bin/PalTest` cannot open a `PAL_STREAM_ACD` stream.**
It is the upstream Qualcomm reference build of `test/PalTest_main.c` +
`test/PalUsecaseTest.c` and only implements `case PAL_STREAM_ULTRASOUND` in its
switch — every other usecase id falls through to `"unkown uasecase"` (sic) with
`-EINVAL`. Running `PalTest 23` will print that error and exit. Therefore
PalTest cannot validate Gate 4 by itself; we must build our own native ACD test
program (Task 3 in plan) or pivot to another tool.

The rest of this doc captures the evidence and the *intended* invocation in
case the binary is rebuilt or replaced.

---

## 1. Binary identification

```
adb -s <GLASSES_SERIAL> pull /vendor/bin/PalTest /tmp/PalTest
file /tmp/PalTest
# ELF 64-bit LSB pie executable, ARM aarch64, dynamically linked, stripped, 15296 bytes
```

Linked against `libpalclient.so` and uses only:
`pal_stream_open`, `pal_stream_set_param`, `pal_stream_start`,
`pal_stream_stop`, `pal_stream_close`, `calloc`, `signal`.

Strings in `.rodata` (full set):
```
Stream started succefully
unkown uasecase
Stream Opened succesfully
-T
Not enough arguments
Usage : 'PalTest usecaseId -T <time>'
setParams failed
Usage for timer : PalTest UsecaseId -T <time>
Usage for Nontimer: PalTest UsecaseId
Please enter valid usecaseId
Exit StopAndCloseUltrasound
Error:Failed to Start UPD
Enter OpenAndStartUsecase
pal_stream_stop failed
openAndstartusecase failed
-help
Enter StopAndCloseUsecase
Event Detected : Invalid event %d
Enter S to start the usecase or C to close the usecase
Enter C to close the usecase
Error:Failed to open UPD stream
Event Detected : Near event received
Exit OpenAndStartUsecase
Please enter valid sleep time
Event Detected : Far event received
pal_stream_close failed
```

Every string maps 1:1 to `PalUsecaseTest.c` UPD/ULTRASOUND code path. There is
no ACD branch, no LPI flag, no model-path argument, no `-st`/`-ut` long options.

## 2. Reference source

- Repo: `https://git.codelinaro.org/clo/le/platform/vendor/qcom/opensource/arpal-lx`
- Branch: `audio-core.lnx.1.0`
- Files:
  - `test/PalTest_main.c` (133 lines) — argv parser
  - `test/PalUsecaseTest.c` (164 lines) — usecase switch
  - `test/PalUsecaseTest.h`
  - `inc/PalDefs.h` — `pal_stream_type_t` enum

Argv parser (`PalTest_main.c`):
```c
usecase_id = atoi(argv[1]);          // single integer; numeric stream type
// optional: -T <seconds> for timed run
```

Usecase switch (`PalUsecaseTest.c`):
```c
int32_t OpenAndStartUsecase(int usecase_type) {
    pal_stream_type_t usecase = (pal_stream_type_t)usecase_type;
    switch (usecase) {
         case PAL_STREAM_ULTRASOUND:           // <-- ONLY case implemented
             status = setup_usecase_ultrasound();
             ...
         default:
             fprintf(stdout, "unkown uasecase\n");
             status = -EINVAL;
    }
}
```

## 3. Stream type integer values (`PalDefs.h`)

| Enum | Int |
|---|---|
| `PAL_STREAM_LOW_LATENCY` | 1 |
| `PAL_STREAM_VOICE_RECOGNITION` | 10 |
| `PAL_STREAM_VOICE_UI` | 17 |
| **`PAL_STREAM_ACD`** | **23** |
| `PAL_STREAM_ULTRASOUND` | 26 |

## 4. Intended invocation (will fail on this binary)

If a rebuilt PalTest with an ACD branch were on the device, the call would be:

```bash
# Run as root (vendor binary, talks to libpalclient -> audioserver via binder)
adb -s <GLASSES_SERIAL> shell "su 0 /vendor/bin/PalTest 23 -T 30"
#                                                       ^^  ^^^^^
#                                                       |   |
#                                                       |   timed run, 30s
#                                                       PAL_STREAM_ACD
```

Expected (hypothetical, not what current binary does):
- `Enter OpenAndStartUsecase`
- `Stream Opened succesfully`
- `Stream started succefully`
- 30 s of LPI-engaged DSP island operation
- `Enter StopAndCloseUsecase` / `Exit OpenAndStartUsecase`

**Actual output of the on-device binary for `PalTest 23`:**
```
Enter OpenAndStartUsecase
unkown uasecase
Exit OpenAndStartUsecase
openAndstartusecase failed
```

## 5. Privilege

- Binary: `-rwxr-xr-x root:shell` at `/vendor/bin/PalTest`
- Adb shell on these glasses runs as **uid=0 root, SELinux Permissive** (verified)
- No `su` wrapper needed; plain `adb shell PalTest 23` works for argv parsing.
- Actual `pal_stream_open` may still require `audioserver` SELinux context; if
  it ENOENT/EPERMs, wrap with `runcon u:r:platform_app:s0` or run from
  `system_server` context — but with Permissive set this is unlikely.

## 6. Sound-model & LPI prerequisites (good news)

The ACD platform plumbing IS present on the device:

- **Models**: `/vendor/etc/models/acd/{event.eai, music.eai, speech.eai}`
  (115 KB / 16 KB / 16 KB — Qualcomm EAI ACD model blobs)
- **Platform XML** (`/vendor/etc/resourcemanager_*.xml`):
  ```xml
  <stream_type>PAL_STREAM_ACD</stream_type>
  <acd_platform_info>
      <param acd_enable="true" />
      <param support_nlpi_switch="true" />
      <param lpi_enable="true" />
      <capture_profile name="SINGLE_MIC_16KHZ_16BIT_HEADSET_LPI">
          <param snd_name="headset-va-mic-lpi" />
          ...
      </capture_profile>
  </acd_platform_info>
  ```
- ACDB calibration: `/vendor/etc/acdbdata/neo_idp*/...acdb`

So the DSP graph + sound-model assets exist. We just lack a userland tool that
calls `pal_stream_open(PAL_STREAM_ACD, …)` and feeds it the EAI model.

## 7. Confirmation logcat / dmesg signatures (for any future ACD test)

When a real ACD stream opens with LPI engaged, watch:

```bash
# logcat — PAL + AGM
adb -s <GLASSES_SERIAL> shell logcat -b all -c
adb -s <GLASSES_SERIAL> shell logcat -b all | \
    grep -iE 'StreamACD|pal_stream_open|PAL_STREAM_ACD|acd_session|LPI|low_power_island|nlpi_switch|configure_lpi|setupSessionDevice'

# Look for these positive markers:
#   StreamACD: open: ... type=23
#   StreamACD: setLPI: 1
#   ResourceManager: configureLpi: enable=1
#   ACDPlatformInfo: acd_enable=true lpi_enable=true
#   AGM: graph open ... LPI subgraph
#   spf: island enter / island_aon
```

```bash
# dmesg — DSP / island wakelock
adb -s <GLASSES_SERIAL> shell "dmesg -w" | \
    grep -iE 'island|aoss|aosd|lpass_aon|q6audio.*lpi|spf.*island'

# Positive markers:
#   lpass_aon: island enabled
#   aoss: subsystem suspended
#   q6audio_lpi: enter LPI mode
```

```bash
# AP-suspend evidence (Gate 4 actual signal):
adb -s <GLASSES_SERIAL> shell 'cat /sys/kernel/debug/suspend_stats; cat /sys/power/suspend_stats/success'
adb -s <GLASSES_SERIAL> shell 'cat /sys/power/wake_lock; cat /sys/power/wake_unlock'
# After ACD opens with LPI, AP should be allowed to enter S2idle/S2RAM while
# LPASS island stays alive. wake_lock should NOT contain audio_io / audioserver.
```

## 8. Caveat — sound model loading

`PAL_STREAM_ACD` is a detection stream; it normally requires:
1. `pal_stream_open()` with `pal_stream_attributes` of type ACD
2. `pal_stream_set_param(PAL_PARAM_ID_LOAD_SOUND_MODEL, …)` pointing at one of
   the `.eai` blobs in `/vendor/etc/models/acd/`
3. `pal_stream_set_param(PAL_PARAM_ID_RECOGNITION_CONFIG, …)` to register a
   detection callback
4. `pal_stream_start()`

Without step 2, `pal_stream_start` may succeed but the DSP graph won't transition
to island-LPI (no model = no detection workload to keep alive). So whether
"opening a stream without a loaded sound model engages aosd" depends on whether
PAL configures the LPI subgraph at *open* time (likely on this build, given
`lpi_enable="true"` is global) or only when a model is loaded.

The custom test program (Task 3) MUST load `event.eai` to make the test
meaningful for Gate 4.

## 9. Conclusion / next step

- PalTest is **not usable** for Gate 4 validation. Do not try `PalTest 23`.
- Proceed to Task 3 (build native ACD test program) directly. The reference
  source above (`PalUsecaseTest.c` `setup_usecase_ultrasound`) gives the exact
  template — just substitute `PAL_STREAM_ACD` and add a
  `PAL_PARAM_ID_LOAD_SOUND_MODEL` `pal_stream_set_param` call pointing at
  `/vendor/etc/models/acd/event.eai`.
- The logcat / dmesg / suspend_stats grep patterns in §7 are reusable for
  whatever ACD opener we end up writing.

## 10. Quick-reference commands

```bash
# Confirm PalTest is the limited variant (no ACD)
adb -s <GLASSES_SERIAL> shell strings /vendor/bin/PalTest | grep -i acd
# (expect empty output — confirmed)

# Check models present
adb -s <GLASSES_SERIAL> shell ls -la /vendor/etc/models/acd/

# Check ACD platform info active
adb -s <GLASSES_SERIAL> shell 'grep -A2 acd_platform_info /vendor/etc/resourcemanager_*.xml | head -30'
```
