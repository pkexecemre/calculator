cmake_minimum_required(VERSION 3.22)
project(localstt C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper)
set(GGML_DIR ${WHISPER_DIR}/ggml)

# whisper.cpp (encoder/decoder + mel) without the CLI examples.
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper_build)

add_library(localstt SHARED whisper_jni.cpp)
target_include_directories(localstt PRIVATE ${WHISPER_DIR}/include ${GGML_DIR}/include)
target_link_libraries(localstt PRIVATE whisper ggml log)
target_compile_options(localstt PRIVATE -O3 -fno-exceptions -fno-rtti)
