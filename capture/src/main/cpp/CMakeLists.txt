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
