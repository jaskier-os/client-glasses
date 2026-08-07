cmake_minimum_required(VERSION 3.18.1)
project(qnn_denoise)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# We do NOT link the QNN backend .so at build time -- libQnnHtp.so / libQnnSystem.so
# are dlopen'd at runtime from the app's nativeLibraryDir. Only the headers are
# needed to compile.
add_library(qnn_denoise SHARED qnn_denoise.cpp)

target_include_directories(qnn_denoise PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/qnn-include)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(qnn_denoise
    ${log-lib}
    ${android-lib}
    dl)

# ----- SCRFD QNN face detector (Hexagon V73 NPU) -----
# Same QNN-C-API pattern as qnn_denoise, but a 9-output detection graph with
# letterbox preprocessing + stride-8/16/32 decode + NMS in C++. Lives in the
# capture process (not the listener) because this /data/app install can dlopen
# the vendor public lib libcdsprpc.so to reach the CDSP. jnigraphics gives us
# AndroidBitmap_lockPixels to read the input bitmap.
add_library(scrfd_qnn SHARED scrfd_qnn.cpp)
target_include_directories(scrfd_qnn PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/qnn-include)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(scrfd_qnn
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    dl)

# ----- GigaAM RNNT encoder (Hexagon V73 NPU) -----
# Log-mel front end + the GigaAM v3_e2e_rnnt conformer encoder (w8a16) through the
# raw QNN C API -- same dlopen-at-runtime pattern as qnn_denoise / scrfd_qnn. Lives
# in the capture process for the same reason they do: this install can dlopen
# libcdsprpc.so to reach the CDSP, while the privileged listener cannot. No
# jnigraphics here -- the input is PCM, not a bitmap.
add_library(gigaam_enc SHARED gigaam_enc.cpp)
target_include_directories(gigaam_enc PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/qnn-include)
target_link_libraries(gigaam_enc
    ${log-lib}
    ${android-lib}
    dl)
