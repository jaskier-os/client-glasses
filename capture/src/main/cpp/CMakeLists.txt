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
