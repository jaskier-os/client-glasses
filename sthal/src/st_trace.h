// sthal/src/st_trace.h
//
// Direct ftrace trace_marker writer used in place of <android/trace.h>.
//
// On the Rokid Neo glasses (Android 12) the libandroid ATrace_beginSection
// path silently no-ops because debug.atrace.tags.enableflags doesn't include
// the APP tag, and adding the "app" atrace category to the perfetto config
// triggers the known atrace-silently-disables-everything bug. Same workaround
// pattern as touchpad-daemon: open /sys/kernel/tracing/trace_marker once,
// write "B|pid|name" and "E" framing strings. Whenever any ftrace event is
// active (always true under the baseline perfetto config) these writes show
// up as slices owned by the writer process.
//
// Slice names are prefixed with "st." so HAL-owned slices filter cleanly in
// Perfetto / TraceProcessor alongside the app-side gt.* slices.

#pragma once

#include <fcntl.h>
#include <stdio.h>
#include <sys/types.h>
#include <unistd.h>

namespace sthal {

inline int& st_marker_fd() {
    static int fd = -1;
    return fd;
}

inline void st_marker_init() {
    int& fd = st_marker_fd();
    if (fd >= 0) return;
    fd = ::open("/sys/kernel/tracing/trace_marker", O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        fd = ::open("/sys/kernel/debug/tracing/trace_marker", O_WRONLY | O_CLOEXEC);
    }
}

inline void st_begin(const char* name) {
    int fd = st_marker_fd();
    if (fd < 0) {
        st_marker_init();
        fd = st_marker_fd();
        if (fd < 0) return;
    }
    char buf[128];
    int n = snprintf(buf, sizeof(buf), "B|%d|%s", (int)getpid(), name);
    if (n > 0) (void)::write(fd, buf, (size_t)n);
}

inline void st_end() {
    int fd = st_marker_fd();
    if (fd < 0) return;
    (void)::write(fd, "E", 1);
}

struct StAtraceScope {
    explicit StAtraceScope(const char* name) { st_begin(name); }
    ~StAtraceScope() { st_end(); }
    StAtraceScope(const StAtraceScope&) = delete;
    StAtraceScope& operator=(const StAtraceScope&) = delete;
};

} // namespace sthal

#define ST_TRACE_CONCAT_INNER(a, b) a##b
#define ST_TRACE_CONCAT(a, b) ST_TRACE_CONCAT_INNER(a, b)
#define ST_TRACE(name) ::sthal::StAtraceScope ST_TRACE_CONCAT(_st_at_, __LINE__)(name)
