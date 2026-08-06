# appsud -- root command daemon

Native root daemon for the DIY-rooted Rokid glasses. Every privileged operation the apps
perform -- writes into `/system`, `pm install`, runtime grants, reboot, arbitrary shell -- goes
through here. Source: `src/appsud.c`, built by `build.sh`, installed to `/system/bin/appsud`.

## Why a daemon and not setuid

Zygote-spawned app processes run with an EMPTY capability bounding set (`CapBnd=0`). A
setuid-root binary can change uid to 0 but can never regain `CAP_SETUID`/`CAP_SETGID`, so
`setresgid()` fails with EPERM and real root stays unreachable from an app. Magisk and friends
use a root daemon + socket for exactly this reason; so do we. Do not "simplify" this into a
setuid binary -- it cannot work.

`appsud` is launched by init (`diy-overlay.rc`, `service appsud`, `seclabel su:s0`) so it is a
real root process with full capabilities. It listens on an **abstract unix socket**
(`@rokid_appsud`) and runs each request through `/system/bin/sh -c`. Clients connect with
`LocalSocket`.

Because it is an init service (class core) it auto-respawns. Being init-managed also means a new
build needs either a reflash or the DIY overlay: push to
`/data/local/diy-overlay/system/bin/appsud` and reboot -- the bind-mount overlay picks it up at
post-fs-data. See the root CLAUDE.md "DIY Firmware Overlay" section.

## Who calls it

- **filesync** (`FileHttpServer.kt`, `ExecJob`) -- the sideload `/sideload/exec*` routes. This is
  the path that gives a desktop root shell on the glasses with no USB cable; see the root
  CLAUDE.md "Sideloading" section for the two transports (LAN, and the orchestrator).
- Anything else needing privileged work from an app uid.

Output is streamed back as frames, so long-running commands work; the caller drains
incrementally rather than waiting for exit. filesync buffers into rolling 16 MiB stdout/stderr
buffers and hands the desktop base64 so a multibyte character split across two polls is never
corrupted.

## Security posture

There is no authentication beyond the abstract socket and the sideload enable flag. The device
single-user-trusts its on-device callers by design. Any app that can reach `@rokid_appsud` has
root, so do not widen access without thinking about what else runs on the device.
