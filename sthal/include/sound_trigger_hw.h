// sthal/include/sound_trigger_hw.h
//
// Minimal self-contained re-declaration of AOSP's legacy SoundTrigger HAL ABI
// (struct shapes and function-pointer-table signatures). Required because
// building outside an AOSP tree we cannot pull `system/audio.h`,
// `hardware/hardware.h`, `system/sound_trigger.h` from platform.
//
// Reference (Apache-2.0):
//   platform/hardware/libhardware/include_all/hardware/hardware.h
//   platform/hardware/libhardware/include_all/hardware/sound_trigger.h
//   platform/system/media/audio/include/system/sound_trigger.h
//   platform/system/media/audio/include/system/audio.h
//
// These structs MUST remain binary-compatible with the AOSP upstream for the
// running Android platform version on the device (Rokid Neo = API 32). If the
// upstream ABI changes we update here.

#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// -----------------------------------------------------------------------------
// libhardware core
// -----------------------------------------------------------------------------

#define MAKE_TAG_CONSTANT(A, B, C, D) \
    (((A) << 24) | ((B) << 16) | ((C) << 8) | (D))

#define HARDWARE_MODULE_TAG MAKE_TAG_CONSTANT('H', 'W', 'M', 'T')
#define HARDWARE_DEVICE_TAG MAKE_TAG_CONSTANT('H', 'W', 'D', 'T')

#define HARDWARE_MODULE_API_VERSION(maj, min) (((maj) << 8) | (min))
#define HARDWARE_DEVICE_API_VERSION(maj, min) (((maj) << 8) | (min))

#define HAL_MODULE_INFO_SYM         HMI
#define HAL_MODULE_INFO_SYM_AS_STR  "HMI"

struct hw_module_t;
struct hw_module_methods_t;
struct hw_device_t;

typedef struct hw_module_methods_t {
    int (*open)(const struct hw_module_t* module,
                const char* id,
                struct hw_device_t** device);
} hw_module_methods_t;

typedef struct hw_module_t {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char* id;
    const char* name;
    const char* author;
    struct hw_module_methods_t* methods;
    void* dso;
    uint64_t reserved[32 - 7];
} hw_module_t;

typedef struct hw_device_t {
    uint32_t tag;           // 0x00 (4)
    uint32_t version;       // 0x04 (4)
    struct hw_module_t* module;  // 0x08 (8)
    // AOSP-canonical: reserved is uint32_t[12] = 48 bytes, NOT uint64_t[12].
    uint32_t reserved[12];  // 0x10-0x3f (48)
    int (*close)(struct hw_device_t* device);  // 0x40 (8)
    // Base hw_device_t ends at 0x48 (72 bytes).
    //
    // The QTI HIDL @2.3 shim expects the first sound_trigger_hw_device
    // function pointer (get_properties) at absolute struct offset 0x78
    // (verified from disassembly: SoundTriggerHw::getProperties reads
    // [mHwDevice + 0x78], doLoadSoundModel reads [mHwDevice + 0x80],
    // unloadSoundModel reads [mHwDevice + 0x88]). Between 0x48 and 0x78
    // is 0x30 = 48 bytes of unused padding. 6 uint64_t fills that exactly.
    uint64_t tail_padding[6];  // 0x48..0x77 (48)
} hw_device_t;

// -----------------------------------------------------------------------------
// SoundTrigger HAL module identity
// -----------------------------------------------------------------------------

#define SOUND_TRIGGER_HARDWARE_MODULE_ID              "sound_trigger"
#define SOUND_TRIGGER_HARDWARE_INTERFACE              "sound_trigger_hw_if"
#define SOUND_TRIGGER_HARDWARE_MODULE_ID_PRIMARY      "primary"

#define SOUND_TRIGGER_MODULE_API_VERSION_1_0          HARDWARE_MODULE_API_VERSION(1, 0)
#define SOUND_TRIGGER_MODULE_API_VERSION_CURRENT      SOUND_TRIGGER_MODULE_API_VERSION_1_0

#define SOUND_TRIGGER_DEVICE_API_VERSION_1_0          HARDWARE_DEVICE_API_VERSION(1, 0)
#define SOUND_TRIGGER_DEVICE_API_VERSION_1_1          HARDWARE_DEVICE_API_VERSION(1, 1)
#define SOUND_TRIGGER_DEVICE_API_VERSION_1_2          HARDWARE_DEVICE_API_VERSION(1, 2)
#define SOUND_TRIGGER_DEVICE_API_VERSION_1_3          HARDWARE_DEVICE_API_VERSION(1, 3)
// 1.3 is required by android.hardware.soundtrigger@2.3::ISoundTriggerHw. The
// HIDL @2.3 legacy shim rejects any HAL device advertising < 1.3 at startup
// with "wrong sound trigger hw device version 0102" and the middleware ends
// up with zero modules. Stay at 1.3 so we register as a usable module.
#define SOUND_TRIGGER_DEVICE_API_VERSION_CURRENT      SOUND_TRIGGER_DEVICE_API_VERSION_1_3

// -----------------------------------------------------------------------------
// AOSP sound trigger types (from system/media/audio/include/system/sound_trigger.h)
// -----------------------------------------------------------------------------

#define SOUND_TRIGGER_MAX_STRING_LEN 64
#define SOUND_TRIGGER_MAX_LOCALE_LEN 6
#define SOUND_TRIGGER_MAX_USERS      10
#define SOUND_TRIGGER_MAX_PHRASES    10

#define RECOGNITION_MODE_VOICE_TRIGGER        0x1
#define RECOGNITION_MODE_USER_IDENTIFICATION  0x2
#define RECOGNITION_MODE_USER_AUTHENTICATION  0x4
#define RECOGNITION_MODE_GENERIC_TRIGGER      0x8

#define RECOGNITION_STATUS_SUCCESS            0
#define RECOGNITION_STATUS_ABORT              1
#define RECOGNITION_STATUS_FAILURE            2
#define RECOGNITION_STATUS_GET_STATE_RESPONSE 3

#define SOUND_MODEL_STATUS_UPDATED            0

typedef enum {
    SOUND_MODEL_TYPE_UNKNOWN    = -1,
    SOUND_MODEL_TYPE_KEYPHRASE  = 0,
    SOUND_MODEL_TYPE_GENERIC    = 1,
} sound_trigger_sound_model_type_t;

typedef struct {
    uint32_t timeLow;
    uint16_t timeMid;
    uint16_t timeHiAndVersion;
    uint16_t clockSeq;
    uint8_t  node[6];
} audio_uuid_t;

typedef audio_uuid_t sound_trigger_uuid_t;
typedef int sound_model_handle_t;
typedef int audio_io_handle_t;
typedef uint32_t audio_devices_t;
typedef int audio_session_t;

struct sound_trigger_properties {
    char         implementor[SOUND_TRIGGER_MAX_STRING_LEN];
    char         description[SOUND_TRIGGER_MAX_STRING_LEN];
    unsigned int version;
    sound_trigger_uuid_t uuid;
    unsigned int max_sound_models;
    unsigned int max_key_phrases;
    unsigned int max_users;
    unsigned int recognition_modes;
    bool         capture_transition;
    unsigned int max_buffer_ms;
    bool         concurrent_capture;
    bool         trigger_in_event;
    unsigned int power_consumption_mw;
};

// -----------------------------------------------------------------------------
// v1.3 extended properties (AOSP system/media/audio/include/system/sound_trigger.h)
//
// The HIDL @2.3 service calls get_properties_extended(dev, &header). The header
// contains a version field and a pointer-free inline payload whose layout is:
//   - leading sound_trigger_properties (v1.0 layout, reused verbatim)
//   - trailing supported_model_arch (zero-terminated UTF-8 string)
//   - trailing audio_capabilities (bitmask)
//
// The AOSP definition packs these as a single struct
// `sound_trigger_properties_extended_1_3`. Its `header.size` must match
// sizeof(extended) so the framework can validate the payload.
// -----------------------------------------------------------------------------
#define SOUND_TRIGGER_PROPERTIES_EXTENDED_MAX_ARCH_LEN 64

// QTI expects the header to occupy 16 bytes (0x00-0x0f), with `base` starting
// at offset 0x10. AOSP-canonical header is 8 bytes (version + size) but the
// QTI HIDL @2.3 shim verified via disassembly reads the base at offset 0x10,
// supported_model_arch at 0xc4, audio_capabilities at 0x104. That implies an
// 8-byte padding gap after the version/size pair.
struct sound_trigger_properties_header {
    unsigned int version;   // offset 0x00 -- SOUND_TRIGGER_DEVICE_API_VERSION_1_3
    unsigned int size;      // offset 0x04 -- sizeof(sound_trigger_properties_extended_1_3)
    unsigned int reserved_08; // offset 0x08 -- padding to match QTI layout
    unsigned int reserved_0c; // offset 0x0c -- padding to match QTI layout
};

struct sound_trigger_properties_extended_1_3 {
    struct sound_trigger_properties_header header;                 // 0x00-0x0f (16 B)
    struct sound_trigger_properties        base;                   // 0x10-0xc3 (180 B)
    char                                   supported_model_arch[SOUND_TRIGGER_PROPERTIES_EXTENDED_MAX_ARCH_LEN]; // 0xc4-0x103 (64 B)
    unsigned int                           audio_capabilities;     // 0x104 (4 B)
};

struct sound_trigger_sound_model {
    sound_trigger_sound_model_type_t type;
    sound_trigger_uuid_t             uuid;
    sound_trigger_uuid_t             vendor_uuid;
    unsigned int                     data_size;
    unsigned int                     data_offset;
};

struct sound_trigger_phrase {
    unsigned int id;
    unsigned int recognition_mode;
    unsigned int num_users;
    unsigned int users[SOUND_TRIGGER_MAX_USERS];
    char         locale[SOUND_TRIGGER_MAX_LOCALE_LEN];
    char         text[SOUND_TRIGGER_MAX_STRING_LEN];
};

struct sound_trigger_phrase_sound_model {
    struct sound_trigger_sound_model common;
    unsigned int num_phrases;
    struct sound_trigger_phrase phrases[SOUND_TRIGGER_MAX_PHRASES];
};

struct sound_trigger_generic_sound_model {
    struct sound_trigger_sound_model common;
};

struct sound_trigger_model_event {
    int status;
    sound_model_handle_t model;
    unsigned int data_size;
    unsigned int data_offset;
};

struct sound_trigger_phrase_recognition_extra {
    unsigned int id;
    unsigned int recognition_modes;
    unsigned int confidence_level;
    unsigned int num_levels;
    // AOSP: struct sound_trigger_confidence_level levels[SOUND_TRIGGER_MAX_USERS];
    // Omitted here: levels never read by our path; if framework validates size
    // we expand. Keep it POD-aligned.
    struct {
        unsigned int user_id;
        unsigned int level;
    } levels[SOUND_TRIGGER_MAX_USERS];
};

struct sound_trigger_recognition_config {
    audio_io_handle_t capture_handle;
    audio_devices_t   capture_device;
    bool              capture_requested;
    unsigned int      num_phrases;
    struct sound_trigger_phrase_recognition_extra phrases[SOUND_TRIGGER_MAX_PHRASES];
    unsigned int      data_size;
    unsigned int      data_offset;
};

struct __attribute__((aligned(8))) sound_trigger_recognition_event {
    int                              status;
    sound_trigger_sound_model_type_t type;
    sound_model_handle_t             model;
    bool                             capture_available;
    int                              capture_session;
    int                              capture_delay_ms;
    int                              capture_preamble_ms;
    bool                             trigger_in_data;
    uint32_t                         audio_format_pad; // audio_config_t placeholder
    uint32_t                         sample_rate;
    uint32_t                         channel_mask;
    uint32_t                         format;
    unsigned int                     data_size;
    unsigned int                     data_offset;
};

struct sound_trigger_generic_recognition_event {
    struct sound_trigger_recognition_event common;
};

struct sound_trigger_phrase_recognition_event {
    struct sound_trigger_recognition_event common;
    unsigned int num_phrases;
    struct sound_trigger_phrase_recognition_extra phrase_extras[SOUND_TRIGGER_MAX_PHRASES];
};

typedef void (*recognition_callback_t)(struct sound_trigger_recognition_event* event,
                                       void* cookie);
typedef void (*sound_model_callback_t)(struct sound_trigger_model_event* event,
                                       void* cookie);

// -----------------------------------------------------------------------------
// The HAL device we publish
// -----------------------------------------------------------------------------

struct sound_trigger_hw_device {
    struct hw_device_t common;

    int (*get_properties)(const struct sound_trigger_hw_device* dev,
                          struct sound_trigger_properties* properties);

    int (*load_sound_model)(const struct sound_trigger_hw_device* dev,
                            struct sound_trigger_sound_model* sound_model,
                            sound_model_callback_t callback,
                            void* cookie,
                            sound_model_handle_t* handle);

    int (*unload_sound_model)(const struct sound_trigger_hw_device* dev,
                              sound_model_handle_t handle);

    int (*start_recognition)(const struct sound_trigger_hw_device* dev,
                             sound_model_handle_t handle,
                             const struct sound_trigger_recognition_config* config,
                             recognition_callback_t callback,
                             void* cookie);

    int (*stop_recognition)(const struct sound_trigger_hw_device* dev,
                            sound_model_handle_t handle);

    // 1.1+ (offset 0xa0 in QTI layout)
    int (*stop_all_recognitions)(const struct sound_trigger_hw_device* dev);

    // -------------------------------------------------------------------------
    // QTI @2.3-impl.so vtable layout (verified via disassembly):
    //
    //   0x78  get_properties              (loaded in SoundTriggerHw::getProperties)
    //   0x80  load_sound_model            (doLoadSoundModel)
    //   0x88  unload_sound_model          (unloadSoundModel)
    //   0x90  start_recognition           (startRecognition v2.0/2.1)
    //   0x98  stop_recognition            (stopRecognition)
    //   0xa0  stop_all_recognitions       (stopAllRecognitions)
    //   0xa8  get_model_state             (getModelState)
    //   0xb0  set_parameter               (setParameter)
    //   0xb8  get_parameter               (getParameter)
    //   0xc0  query_parameter             (queryParameter)
    //   0xc8  get_properties_extended     (getProperties_2_3)
    //   0xd0  start_recognition_extended  (startRecognition_2_3)
    //
    // with hw_device_t padded to 0x78 (tag+ver+module+reserved[12]+close+6*uint64).
    // -------------------------------------------------------------------------

    // 1.2+ (offset 0xa8 in QTI layout)
    int (*get_model_state)(const struct sound_trigger_hw_device* dev,
                           sound_model_handle_t handle);

    // 1.3+ (offset 0xb0 in QTI layout)
    int (*set_parameter)(const struct sound_trigger_hw_device* dev,
                         sound_model_handle_t handle,
                         int param,
                         int value);

    // 1.3+ (offset 0xb8 in QTI layout)
    int (*get_parameter)(const struct sound_trigger_hw_device* dev,
                         sound_model_handle_t handle,
                         int param,
                         int* value);

    // 1.3+ (offset 0xc0 in QTI layout)
    int (*query_parameter)(const struct sound_trigger_hw_device* dev,
                           sound_model_handle_t handle,
                           int param,
                           void* range);

    // 1.3+ (offset 0xc8 in QTI layout). Return pointer to a HAL-owned
    // persistent `sound_trigger_properties_extended_1_3` whose first 4 bytes
    // are the version field (must be >= 0x103). NULL means "not supported".
    const struct sound_trigger_properties_header*
    (*get_properties_extended)(const struct sound_trigger_hw_device* dev);

    // 1.3+ (offset 0xd0 in QTI layout). The 2.3-extended startRecognition
    // path with model parameters; we don't use them so forward to the 1.0
    // start_recognition.
    int (*start_recognition_extended)(const struct sound_trigger_hw_device* dev,
                                      sound_model_handle_t handle,
                                      const struct sound_trigger_recognition_config* cfg,
                                      recognition_callback_t cb,
                                      void* cookie);
};

#ifdef __cplusplus
} // extern "C"
#endif
