# sthal/CMakeLists.txt — standalone NDK build for sound_trigger.primary.neo.so
#
# Stand-alone invocation (no Gradle, no AOSP tree):
#
#   mkdir -p build && cd build
#   cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a \
#         -DANDROID_PLATFORM=android-31 \
#         ..
#   cmake --build .
#
# Produces build/sound_trigger.primary.neo.so. Deploy via deploy.sh (gated).

cmake_minimum_required(VERSION 3.22.1)
project(sthal C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT ANDROID)
    message(FATAL_ERROR "This module only builds against the Android NDK toolchain.")
endif()

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "sthal supports arm64-v8a only (got ${ANDROID_ABI})")
endif()

# Vendored tinyalsa (static, PIC). MicReader links against this so we do not
# depend on whatever libtinyalsa the device ships.
include(${CMAKE_CURRENT_SOURCE_DIR}/third_party/tinyalsa_static.cmake)

add_library(sound_trigger.primary.neo SHARED
    src/sound_trigger_hw.cpp
    src/qnn_runtime.cpp
    src/qnn_graph.cpp
    src/mic_reader.cpp
)

# QNN SDK headers: resolved via QNN_SDK_ROOT. Can be a CMake cache entry
# (-DQNN_SDK_ROOT=...) or taken from the environment. The QAIRT SDK ships its
# C headers under ${QNN_SDK_ROOT}/include/QNN -- no pre-built .so is linked at
# compile time, we dlopen all backend libs at runtime. See src/qnn_runtime.cpp.
if(NOT DEFINED QNN_SDK_ROOT)
    if(DEFINED ENV{QNN_SDK_ROOT})
        set(QNN_SDK_ROOT $ENV{QNN_SDK_ROOT})
    endif()
endif()
if(NOT QNN_SDK_ROOT OR NOT EXISTS "${QNN_SDK_ROOT}/include/QNN/QnnInterface.h")
    message(FATAL_ERROR
        "QNN_SDK_ROOT not set or headers missing. "
        "Expected ${QNN_SDK_ROOT}/include/QNN/QnnInterface.h. "
        "Set QNN_SDK_ROOT env var or -DQNN_SDK_ROOT=... to the QAIRT SDK root.")
endif()

target_include_directories(sound_trigger.primary.neo
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/src
        ${QNN_SDK_ROOT}/include/QNN
)

target_compile_options(sound_trigger.primary.neo
    PRIVATE
        -Wall -Wextra -Wformat=2 -Wshadow
        -Werror=return-type
        -fvisibility=hidden
        -fno-rtti
        -fno-exceptions
        -ffunction-sections
        -fdata-sections
)

target_link_options(sound_trigger.primary.neo
    PRIVATE
        -Wl,--gc-sections
        -Wl,--as-needed
        -Wl,-z,defs
)

target_link_libraries(sound_trigger.primary.neo
    PRIVATE
        tinyalsa_static  # vendored tinyalsa (sthal/third_party/tinyalsa)
        log              # android log
        dl               # dlopen for QNN
)

# Force output name to match the HAL load convention (no lib prefix).
set_target_properties(sound_trigger.primary.neo PROPERTIES
    PREFIX ""
    OUTPUT_NAME "sound_trigger.primary.neo"
)
