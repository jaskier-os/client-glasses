# Minimal static-library CMake for tinyalsa, usable under the Android NDK.
#
# Upstream tinyalsa's CMakeLists.txt invokes scripts/version.sh via
# execute_process and enables plugins/examples/utils. We don't need any of
# that — we want one archive to link into sound_trigger.primary.neo.so.
#
# Input: TINYALSA_SRC_DIR (path to the upstream source tree).
# Output: target `tinyalsa_static` (static lib, PIC) with PUBLIC include
# directory ${TINYALSA_SRC_DIR}/include.

if(NOT DEFINED TINYALSA_SRC_DIR)
    set(TINYALSA_SRC_DIR ${CMAKE_CURRENT_LIST_DIR}/tinyalsa)
endif()

if(NOT EXISTS ${TINYALSA_SRC_DIR}/include/tinyalsa/pcm.h)
    message(FATAL_ERROR
        "tinyalsa source not found at ${TINYALSA_SRC_DIR}. "
        "Expected upstream checkout (v2.0.0) under sthal/third_party/tinyalsa.")
endif()

add_library(tinyalsa_static STATIC
    ${TINYALSA_SRC_DIR}/src/pcm.c
    ${TINYALSA_SRC_DIR}/src/pcm_hw.c
    ${TINYALSA_SRC_DIR}/src/mixer.c
    ${TINYALSA_SRC_DIR}/src/mixer_hw.c
    ${TINYALSA_SRC_DIR}/src/limits.c
)

# Plugin-related sources (pcm_plugin.c, mixer_plugin.c, snd_card_plugin.c)
# require TINYALSA_USES_PLUGINS. We don't enable plugin loading, so the
# pcm_plugin.c stubs still need to be present because pcm.c references them.
# Upstream guards with TINYALSA_USES_PLUGINS — when unset, the plugin APIs
# degrade to rejecting plugin URIs. We therefore include them but do NOT
# define TINYALSA_USES_PLUGINS.
target_sources(tinyalsa_static PRIVATE
    ${TINYALSA_SRC_DIR}/src/pcm_plugin.c
    ${TINYALSA_SRC_DIR}/src/mixer_plugin.c
    ${TINYALSA_SRC_DIR}/src/snd_card_plugin.c
)

set_target_properties(tinyalsa_static PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    C_STANDARD 99
    C_STANDARD_REQUIRED ON
    C_EXTENSIONS ON
    # Hide tinyalsa's exported symbols from the final sound_trigger.primary.neo.so
    # dynamic symbol table. The HAL's only required export is HAL_MODULE_INFO_SYM
    # ("HMI"); tinyalsa's pcm_* / mixer_* symbols leaking into the .dynsym
    # of the HAL would be an ABI footgun for any future caller that dlopens
    # our .so alongside a different libtinyalsa. C_VISIBILITY_PRESET /
    # CXX_VISIBILITY_PRESET force -fvisibility=hidden at compile time;
    # VISIBILITY_INLINES_HIDDEN scrubs inline-template instantiations from
    # the dynamic table too.
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

target_include_directories(tinyalsa_static
    PUBLIC
        ${TINYALSA_SRC_DIR}/include
    PRIVATE
        ${TINYALSA_SRC_DIR}/src
)

# Upstream leans on _GNU_SOURCE for strdup/strtok_r etc. NDK bionic is fine
# with that. Silence the few warnings the upstream sources emit under -Wall.
target_compile_definitions(tinyalsa_static PRIVATE _GNU_SOURCE=1)
target_compile_options(tinyalsa_static PRIVATE
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-implicit-fallthrough
    -Wno-missing-field-initializers
)
