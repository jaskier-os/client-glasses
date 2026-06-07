# acd-probe / CMakeLists.txt
#
# Standalone NDK build for a tiny native ACD test program. Runs on the Rokid
# glasses (arm64-v8a, Android 12 / API 31). libpalclient.so is loaded at
# runtime via dlopen so we do NOT need an import library at link time.
#
# Build:
#   bash build.sh        # convenience wrapper
# or manually:
#   cmake -S . -B build \
#       -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#       -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31
#   cmake --build build -j

cmake_minimum_required(VERSION 3.18)
project(acd_probe LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_executable(acd-probe main.cpp)

target_include_directories(acd-probe PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# We dlopen libpalclient.so at runtime; only need libdl + liblog at link time.
target_link_libraries(acd-probe PRIVATE dl log)

target_compile_options(acd-probe PRIVATE
    -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers
    -ffunction-sections -fdata-sections -fno-exceptions -fno-rtti
)

# PIE is required on Android.
set_target_properties(acd-probe PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    LINK_FLAGS "-pie -Wl,--gc-sections"
)
