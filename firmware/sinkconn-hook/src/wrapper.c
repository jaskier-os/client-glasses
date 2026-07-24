/*
 * app_process64 wrapper for Rokid Glasses glasses.
 *
 * Bind-mounted over /system/bin/app_process64 by the DIY overlay. On every
 * exec (i.e. every Zygote spawn) it dlopen()'s libsinkconn_hook.so then execve()'s
 * the renamed stock binary at /system/bin/app_process64.real.
 *
 * Hook library load is strictly non-fatal: a missing, corrupt, or disabled
 * hook must never prevent zygote from starting (that would brick boot).
 *
 * Safety gate: property "persist.sys.sinkconn_hook.enabled" = "0" skips dlopen.
 */

#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/system_properties.h>
#include <unistd.h>

#define REAL_APP_PROCESS  "/system/bin/app_process64.real"
#define HOOK_LIB          "/system/lib64/libsinkconn_hook.so"
#define GATE_PROP         "persist.sys.sinkconn_hook.enabled"

static int hook_enabled(void) {
    char buf[PROP_VALUE_MAX];
    int n = __system_property_get(GATE_PROP, buf);
    if (n <= 0) return 1;                    /* default enabled */
    return !(n == 1 && buf[0] == '0');
}

int main(int argc, char **argv, char **envp) {
    (void)argc;
    if (hook_enabled()) {
        void *h = dlopen(HOOK_LIB, RTLD_NOW | RTLD_GLOBAL);
        if (!h) {
            fprintf(stderr, "[sinkconn_wrapper] dlopen %s failed: %s\n",
                    HOOK_LIB, dlerror());
            /* non-fatal, continue */
        }
    }

    execve(REAL_APP_PROCESS, argv, envp);

    /* execve only returns on failure -- this is fatal */
    fprintf(stderr, "[sinkconn_wrapper] execve %s failed: %s\n",
            REAL_APP_PROCESS, strerror(errno));
    return 127;
}
