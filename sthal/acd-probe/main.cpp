// acd-probe: minimal native ACD test program for the Rokid glasses.
//
// Goal: open a PAL_STREAM_ACD (=23) session against the SPEECH context
// (0x08001335) using vendor UUID 4e93281b-296e-4d73-9833-2710c3c7c1db, load
// /vendor/etc/models/acd/speech.eai as the sound model, set a recognition
// config that subscribes to context 0x08001335, and start recognition.
// Sleeps for 5 minutes printing a 30s heartbeat, then cleanly stops on
// SIGINT (or natural exit) and calls pal_deinit.
//
// PAL headers (PalDefs.h / PalApi.h) come from LineageOS arpal-lx
// branch audio-core.lnx.1.0 (BSD-3-Clause-Clear). The on-device
// /vendor/lib64/libpalclient.so is the source of truth for ABI; we dlopen
// it at runtime to avoid needing an import library when cross-building.
//
// Build: see CMakeLists.txt + build.sh in this directory.
// Deploy: adb push build/acd-probe /data/local/tmp/ && adb shell chmod
//   +x /data/local/tmp/acd-probe && adb shell /data/local/tmp/acd-probe
//
// Task 3 of /home/user/.claude/plans/you-are-an-orchestrator-temporal-lightning.md
// (Option 3 / Gate 4 — DSP-side VAD via PAL ACD).

#include "pal/PalDefs.h"
#include "pal/PalApi.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <cinttypes>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <signal.h>
#include <time.h>
#include <atomic>
#include <vector>
#include <string>
#include <dlfcn.h>

// -----------------------------------------------------------------------------
// libpalclient symbol bindings via dlopen/dlsym. We do NOT link the import
// library at build time, so the cross-build does not require a stub .so.
// -----------------------------------------------------------------------------

namespace pal {

using fn_init_t        = int32_t (*)(void);
using fn_deinit_t      = void    (*)(void);
using fn_open_t        = int32_t (*)(struct pal_stream_attributes *, uint32_t,
                                     struct pal_device *, uint32_t,
                                     struct modifier_kv *, pal_stream_callback,
                                     uint64_t, pal_stream_handle_t **);
using fn_close_t       = int32_t (*)(pal_stream_handle_t *);
using fn_start_t       = int32_t (*)(pal_stream_handle_t *);
using fn_stop_t        = int32_t (*)(pal_stream_handle_t *);
using fn_set_param_t   = int32_t (*)(pal_stream_handle_t *, uint32_t,
                                     pal_param_payload *);

static fn_init_t       p_init        = nullptr;
static fn_deinit_t     p_deinit      = nullptr;
static fn_open_t       p_open        = nullptr;
static fn_close_t      p_close       = nullptr;
static fn_start_t      p_start       = nullptr;
static fn_stop_t       p_stop        = nullptr;
static fn_set_param_t  p_set_param   = nullptr;

static void *handle = nullptr;

static bool resolve()
{
    handle = dlopen("libpalclient.so", RTLD_NOW);
    if (!handle) {
        // Fallback to absolute path under /vendor.
        handle = dlopen("/vendor/lib64/libpalclient.so", RTLD_NOW);
    }
    if (!handle) {
        fprintf(stderr, "[acd-probe] dlopen libpalclient.so failed: %s\n",
                dlerror());
        return false;
    }
#define LOAD(sym, type, name)                                                \
    do {                                                                     \
        sym = reinterpret_cast<type>(dlsym(handle, name));                   \
        if (!sym) {                                                          \
            fprintf(stderr, "[acd-probe] dlsym %s: %s\n", name, dlerror());  \
            return false;                                                    \
        }                                                                    \
    } while (0)

    LOAD(p_init,      fn_init_t,      "pal_init");
    LOAD(p_deinit,    fn_deinit_t,    "pal_deinit");
    LOAD(p_open,      fn_open_t,      "pal_stream_open");
    LOAD(p_close,     fn_close_t,     "pal_stream_close");
    LOAD(p_start,     fn_start_t,     "pal_stream_start");
    LOAD(p_stop,      fn_stop_t,      "pal_stream_stop");
    LOAD(p_set_param, fn_set_param_t, "pal_stream_set_param");
#undef LOAD
    return true;
}

} // namespace pal

// -----------------------------------------------------------------------------
// Constants from on-device resourcemanager_neo_idp.xml acd_platform_info block.
// -----------------------------------------------------------------------------

// Vendor UUID 4e93281b-296e-4d73-9833-2710c3c7c1db
static const struct st_uuid kAcdVendorUuid = {
    0x4e93281b,
    0x296e,
    0x4d73,
    0x9833,
    { 0x27, 0x10, 0xc3, 0xc7, 0xc1, 0xdb }
};

// SPEECH context id from acd_platform_info -> context_id
static constexpr uint32_t kAcdContextSpeech = 0x08001335;

// Sound model file (Qualcomm EAI blob, ~16 KB on this device)
static constexpr const char *kSpeechEaiPath = "/vendor/etc/models/acd/speech.eai";

// -----------------------------------------------------------------------------
// Global state for SIGINT-driven cleanup.
// -----------------------------------------------------------------------------

static std::atomic<bool>     g_running{true};
static pal_stream_handle_t  *g_handle = nullptr;

static void on_sigint(int)
{
    g_running.store(false);
    // Re-arm default handler so a second ^C aborts immediately if cleanup hangs.
    signal(SIGINT, SIG_DFL);
    fprintf(stderr, "\n[acd-probe] SIGINT — winding down...\n");
}

// -----------------------------------------------------------------------------
// Stream callback (PAL EVENT_ID_DETECTION_ENGINE_GENERIC_INFO etc.).
// On detection PAL hands us a pal_st_recognition_event* payload via event_data.
// -----------------------------------------------------------------------------

static int32_t stream_cb(pal_stream_handle_t *stream_handle,
                         uint32_t event_id, uint32_t *event_data,
                         uint32_t event_data_size, uint64_t cookie)
{
    (void)stream_handle;
    (void)cookie;
    fprintf(stderr,
            "[acd-probe] CALLBACK event_id=0x%08x size=%u data=%p\n",
            event_id, event_data_size, (void *)event_data);

    if (event_data && event_data_size >= sizeof(struct pal_st_recognition_event)) {
        auto *ev = reinterpret_cast<struct pal_st_recognition_event *>(event_data);
        fprintf(stderr,
                "[acd-probe] DETECTED status=%d type=%d capture_avail=%d\n",
                ev->status, (int)ev->type, (int)ev->capture_available);
        // PAL ACD packs ACD-specific opaque data after the common header at
        // ev->data_offset (data_size bytes). First u32 is typically context_id,
        // second u32 confidence — print raw u32 dump for inspection.
        if (ev->data_size > 0 && ev->data_offset > 0) {
            uint8_t *base = reinterpret_cast<uint8_t *>(ev) + ev->data_offset;
            uint32_t words = ev->data_size / 4;
            fprintf(stderr, "[acd-probe] DETECTED context blob (%u bytes):", ev->data_size);
            for (uint32_t i = 0; i < words && i < 8; ++i) {
                uint32_t w;
                memcpy(&w, base + i * 4, 4);
                fprintf(stderr, " 0x%08x", w);
            }
            fprintf(stderr, "\n");
        }
    }
    return 0;
}

// -----------------------------------------------------------------------------
// File slurp helper.
// -----------------------------------------------------------------------------

static bool read_all(const char *path, std::vector<uint8_t> &out)
{
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        fprintf(stderr, "[acd-probe] open(%s) failed: %s\n", path, strerror(errno));
        return false;
    }
    struct stat st{};
    if (fstat(fd, &st) < 0) { close(fd); return false; }
    out.resize((size_t)st.st_size);
    ssize_t n = read(fd, out.data(), out.size());
    close(fd);
    if (n != (ssize_t)out.size()) {
        fprintf(stderr, "[acd-probe] short read on %s (%zd/%zu)\n",
                path, n, out.size());
        return false;
    }
    return true;
}

// -----------------------------------------------------------------------------
// Main.
// -----------------------------------------------------------------------------

int main(int argc, char **argv)
{
    (void)argc; (void)argv;
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IOLBF, 0);

    fprintf(stderr, "[acd-probe] start; pid=%d\n", (int)getpid());
    signal(SIGINT, on_sigint);
    signal(SIGTERM, on_sigint);

    // Hard watchdog: SIGALRM kills the process after 7 min, in case any pal_*
    // call wedges on a binder/DSP deadlock and the SIGINT handler can't run.
    alarm(7 * 60);

    if (!pal::resolve()) return 2;

    // --- 1) pal_init ---------------------------------------------------------
    int32_t rc = pal::p_init();
    fprintf(stderr, "[acd-probe] pal_init -> %d\n", rc);
    if (rc != 0) return 3;

    // --- 2) pal_stream_open(PAL_STREAM_ACD, vendor_uuid, LPI=true) -----------
    struct pal_stream_attributes attr{};
    attr.type      = PAL_STREAM_ACD;
    attr.direction = PAL_AUDIO_INPUT;
    attr.flags     = (pal_stream_flags_t)0;
    // ACD detection itself does not stream PCM to userspace; media config
    // mirrors the LPI capture profile in resourcemanager_neo_idp.xml
    // (SINGLE_MIC_16KHZ_16BIT_HEADSET_LPI).
    attr.in_media_config.sample_rate     = 16000;
    attr.in_media_config.bit_width       = 16;
    attr.in_media_config.aud_fmt_id      = PAL_AUDIO_FMT_PCM_S16_LE;
    attr.in_media_config.ch_info.channels = 1;
    attr.in_media_config.ch_info.ch_map[0] = PAL_CHMAP_CHANNEL_FL;

    struct pal_device dev{};
    dev.id = PAL_DEVICE_IN_HANDSET_VA_MIC;
    // Device backend rate is fixed by resourcemanager_neo_idp.xml lines 307-320:
    //   <in-device id=PAL_DEVICE_IN_HANDSET_VA_MIC back_end=CODEC_DMA-LPAIF_VA-TX-0
    //              channels=1 samplerate=48000 bit_width=16/>
    // The ACD module itself runs at 16 kHz internally (per <stream_config>
    // QC_ACD param sample_rate=16000), but the PCM front-end / AGM PCM
    // hw_params is driven by the device config, not the stream's media config.
    // Passing 16000 here yields a GKV lookup miss in AGM and a zero param
    // that triggers the cfi mul-overflow in agm_pcm_hw_params.
    dev.config.sample_rate = 48000;
    dev.config.bit_width   = 16;
    dev.config.aud_fmt_id  = PAL_AUDIO_FMT_PCM_S16_LE;
    dev.config.ch_info.channels = 1;
    dev.config.ch_info.ch_map[0] = PAL_CHMAP_CHANNEL_FL;

    rc = pal::p_open(&attr, 1, &dev, 0, nullptr, stream_cb, 0, &g_handle);
    fprintf(stderr, "[acd-probe] pal_stream_open -> %d handle=%p\n",
            rc, (void *)g_handle);
    if (rc != 0 || !g_handle) { pal::p_deinit(); return 4; }

    // --- 3) Load sound model: PAL_PARAM_ID_LOAD_SOUND_MODEL ------------------
    std::vector<uint8_t> eai;
    if (!read_all(kSpeechEaiPath, eai)) {
        pal::p_close(g_handle); pal::p_deinit(); return 5;
    }
    fprintf(stderr, "[acd-probe] loaded %s (%zu bytes)\n",
            kSpeechEaiPath, eai.size());

    // Payload layout: [pal_st_sound_model header][raw EAI bytes].
    size_t header_sz = sizeof(struct pal_st_sound_model);
    size_t total_sz  = header_sz + eai.size();
    std::vector<uint8_t> sm_buf(total_sz, 0);
    auto *sm = reinterpret_cast<struct pal_st_sound_model *>(sm_buf.data());
    sm->type        = PAL_SOUND_MODEL_TYPE_GENERIC;
    sm->vendor_uuid = kAcdVendorUuid;
    sm->data_size   = (uint32_t)eai.size();
    sm->data_offset = (uint32_t)header_sz;
    memcpy(sm_buf.data() + header_sz, eai.data(), eai.size());

    // pal_param_payload wraps the buffer.
    std::vector<uint8_t> pp_buf(sizeof(pal_param_payload) + total_sz, 0);
    auto *pp = reinterpret_cast<pal_param_payload *>(pp_buf.data());
    pp->payload_size = (uint32_t)total_sz;
    memcpy(pp->payload, sm_buf.data(), total_sz);

    rc = pal::p_set_param(g_handle, PAL_PARAM_ID_LOAD_SOUND_MODEL, pp);
    fprintf(stderr, "[acd-probe] set_param(LOAD_SOUND_MODEL) -> %d\n", rc);
    if (rc != 0) { pal::p_close(g_handle); pal::p_deinit(); return 6; }

    // --- 4) Recognition config: subscribe to context 0x08001335 (SPEECH) -----
    // ACD uses opaque per-stream-type config blob. The well-known shape for
    // PAL ACD is { uint32_t num_contexts; struct { uint32_t id; uint32_t
    // threshold; uint32_t step; } ctxs[]; }. Threshold/step values come from
    // the platform XML defaults; pass zeros to use defaults.
    struct acd_ctx { uint32_t id; uint32_t threshold; uint32_t step; };
    struct acd_cfg_blob {
        uint32_t num_contexts;
        struct acd_ctx ctx0;
    } acd_cfg = { 1, { kAcdContextSpeech, 0, 0 } };

    size_t rc_hdr_sz  = sizeof(struct pal_st_recognition_config);
    size_t rc_data_sz = sizeof(acd_cfg);
    size_t rc_total   = rc_hdr_sz + rc_data_sz;
    std::vector<uint8_t> rc_buf(rc_total, 0);
    auto *rcfg = reinterpret_cast<struct pal_st_recognition_config *>(rc_buf.data());
    rcfg->capture_handle    = -1;
    rcfg->capture_device    = PAL_DEVICE_IN_HANDSET_VA_MIC;
    rcfg->capture_requested = false;
    rcfg->num_phrases       = 0;
    rcfg->callback          = nullptr; // events come via stream_cb
    rcfg->cookie            = nullptr;
    rcfg->data_size         = (uint32_t)rc_data_sz;
    rcfg->data_offset       = (uint32_t)rc_hdr_sz;
    memcpy(rc_buf.data() + rc_hdr_sz, &acd_cfg, rc_data_sz);

    std::vector<uint8_t> pp2_buf(sizeof(pal_param_payload) + rc_total, 0);
    auto *pp2 = reinterpret_cast<pal_param_payload *>(pp2_buf.data());
    pp2->payload_size = (uint32_t)rc_total;
    memcpy(pp2->payload, rc_buf.data(), rc_total);

    rc = pal::p_set_param(g_handle, PAL_PARAM_ID_RECOGNITION_CONFIG, pp2);
    fprintf(stderr, "[acd-probe] set_param(RECOGNITION_CONFIG ctx=0x%08x) -> %d\n",
            kAcdContextSpeech, rc);
    if (rc != 0) { pal::p_close(g_handle); pal::p_deinit(); return 7; }

    // --- 5) pal_stream_start -------------------------------------------------
    rc = pal::p_start(g_handle);
    fprintf(stderr, "[acd-probe] pal_stream_start -> %d\n", rc);
    if (rc != 0) { pal::p_close(g_handle); pal::p_deinit(); return 8; }

    fprintf(stderr,
        "[acd-probe] ACD running. Watch for 'StreamACD', 'configureLpi', "
        "'island' in logcat/dmesg. Talk into the mic to trigger SPEECH.\n");

    // --- 6) Heartbeat loop, 5 min total --------------------------------------
    constexpr int kRunSeconds       = 5 * 60;
    constexpr int kHeartbeatSeconds = 30;
    time_t t0 = time(nullptr);
    int next_hb = kHeartbeatSeconds;
    while (g_running.load()) {
        sleep(1);
        time_t now = time(nullptr);
        int elapsed = (int)(now - t0);
        if (elapsed >= kRunSeconds) break;
        if (elapsed >= next_hb) {
            fprintf(stderr, "[acd-probe] heartbeat t=%ds (still listening)\n", elapsed);
            next_hb += kHeartbeatSeconds;
        }
    }

    // --- 7) Clean shutdown ---------------------------------------------------
    fprintf(stderr, "[acd-probe] stopping stream...\n");
    rc = pal::p_stop(g_handle);
    fprintf(stderr, "[acd-probe] pal_stream_stop -> %d\n", rc);
    rc = pal::p_close(g_handle);
    fprintf(stderr, "[acd-probe] pal_stream_close -> %d\n", rc);
    pal::p_deinit();
    fprintf(stderr, "[acd-probe] pal_deinit done. bye.\n");
    return 0;
}
