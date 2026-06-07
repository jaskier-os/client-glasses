cmake_minimum_required(VERSION 3.22.1)
project(glasses_native)

# WebRTC AECM library
file(GLOB AECM_SOURCES "aecm_lib/*.c")
add_library(aecm_lib STATIC ${AECM_SOURCES})
target_include_directories(aecm_lib PUBLIC ${CMAKE_SOURCE_DIR}/aecm_lib)
target_compile_definitions(aecm_lib PRIVATE
    WEBRTC_POSIX
    WEBRTC_LINUX
    WEBRTC_ANDROID
)

# JNI wrapper for AECM
add_library(AEC SHARED aecm_jni.c)
target_include_directories(AEC PRIVATE
    ${CMAKE_SOURCE_DIR}/aecm_lib
    ${CMAKE_SOURCE_DIR}
)
target_link_libraries(AEC aecm_lib log)

# ----- Native Opus encoder JNI wrapper (opus_jni) -----
# libopus 1.4 source is vendored under src/main/cpp/opus/ (xiph official release
# tarball, extracted in place). We consume it via add_subdirectory so the NDK
# toolchain builds libopus as a static C library with the same sysroot + ABI
# flags as opus_jni. Statically linked into libopus_jni.so to avoid any runtime
# dependency on the device's /vendor/lib64/libopus.so (unknown ABI / version).

set(OPUS_DIR ${CMAKE_SOURCE_DIR}/opus)

# Tell libopus's own CMakeLists.txt what to build:
#   - static only (libopus_jni.so rolls it in)
#   - no tests, no programs, no install step, no pkg-config / cmake config
#     modules (those try to write to CMAKE_INSTALL_PREFIX and are useless here)
#   - float API ON (VOIP app quality); fixed-point OFF (arm64 has FPU, float is
#     both smaller and faster for CELT+SILK on aarch64)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_FIXED_POINT OFF CACHE BOOL "" FORCE)
set(OPUS_ENABLE_FLOAT_API ON CACHE BOOL "" FORCE)
set(OPUS_HARDENING ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${OPUS_DIR} ${CMAKE_BINARY_DIR}/opus_build EXCLUDE_FROM_ALL)

add_library(opus_jni SHARED opus_jni.cpp)
target_include_directories(opus_jni PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${OPUS_DIR}/include
)

# Link libopus statically + log (for __android_log_print) + android (stub).
# The `opus` target is the static lib target exported by libopus/CMakeLists.txt;
# CMake resolves its INTERFACE include dirs automatically.
target_link_libraries(opus_jni PRIVATE opus log android)

# ----- ACD native JNI (DSP-side voice activity detection) -----
# Bridges Kotlin AcdNativeDetector to /vendor/lib64/libpalclient.so via dlopen
# (no link-time dependency on the vendor lib). PAL ACD lets the SoC DSP gate
# the AP's AudioIn wakelock so audioserver can power-collapse during silence.
# Uses the same pal/PalApi.h + PalDefs.h headers we ship under sthal/acd-probe
# (BSD-3-Clause-Clear, copied to cpp/include/pal/).
add_library(acd_native SHARED acd_native.cpp)
target_include_directories(acd_native PRIVATE
    ${CMAKE_SOURCE_DIR}/include
)
target_compile_features(acd_native PRIVATE cxx_std_17)
target_link_libraries(acd_native log dl)

# ----- HblVad JNI shim (low-power classical-DSP VAD) -----
# Bridges Kotlin HblVadDetector to /system/lib64/librokid_agc.so via dlopen.
# Drop-in replacement for silero_vad.onnx in WakeWordPipeline; ABI documented
# in /tmp/vadprobe/ABI_NOTES.md. Mode 6 = 16 kHz / 20 ms frames.
add_library(hbl_vad SHARED hbl_vad_jni.cpp)
target_compile_features(hbl_vad PRIVATE cxx_std_17)
target_link_libraries(hbl_vad log dl)

# ----- Beamform JNI (RT600 DSP mic array control via libaudigent.so) -----
# Bridges Kotlin BeamformController to the Rokid libaudigent.so via dlopen.
# libaudigent is extracted at runtime from RokidSpriteAssistServer.apk; no
# link-time dependency. Talks to the RT600 DSP chip over /dev/rt600_spidev
# (world-readable/writable, no root needed).
add_library(beamform_jni SHARED beamform_jni.c)
target_link_libraries(beamform_jni log dl)

# ----- CAE Beamform JNI (iFlytek CAE 4-mic beamformer via libcae.so) -----
# Bridges Kotlin TranslationFrontMicRecorder to /system/lib64/libcae.so via
# dlopen. Takes 6ch interleaved int16 (4 mic + 2 ref from 8ch AudioRecord),
# feeds to CAE engine, returns beamformed mono int16 output (beam 1).
add_library(cae_beamform_jni SHARED cae_beamform_jni.c)
target_link_libraries(cae_beamform_jni log dl)
