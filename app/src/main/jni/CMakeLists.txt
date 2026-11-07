# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.18.0)

project(lyraime VERSION 5.5.0)

# for reproducible build
add_link_options("LINKER:--hash-style=both,--build-id=none")

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

# general options
option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)

# ---- shared: yaml-cpp (used by both rime and lua theme) ----
option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory(yaml-cpp)

# ---- lua55 (lua theme) ----
file(GLOB LUA55_SOURCES "${CMAKE_SOURCE_DIR}/lua55/*.c")
list(FILTER LUA55_SOURCES EXCLUDE REGEX "/(lua|luac)\\.c$")
add_library(lua55 STATIC ${LUA55_SOURCES})
target_include_directories(lua55 PUBLIC "${CMAKE_SOURCE_DIR}/lua55")

# ---- rime (glog, snappy, leveldb, marisa, opencc, boost, rime, librime_jni) ----
include(Boost)

option(WITH_GFLAGS "Use gflags" OFF)
option(WITH_GTEST "Use googletest" OFF)
option(WITH_UNWIND "Enable libunwind support" OFF)
# Force-disable unwind for NDK r29 compatibility.
set(WITH_UNWIND none CACHE STRING "" FORCE)
add_subdirectory(librime/deps/glog)
target_compile_options(
  glog_internal PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

# enable compress for leveldb
option(SNAPPY_BUILD_TESTS "" OFF)
option(SNAPPY_BUILD_BENCHMARKS "" OFF)
option(SNAPPY_INSTALL "" OFF)
add_subdirectory(librime-plugins/snappy)

set(HAVE_SNAPPY TRUE)
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
add_subdirectory(librime-plugins/leveldb)

add_subdirectory(librime/deps/marisa-trie)
target_compile_options(
  marisa PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(LIBMARISA marisa)
option(USE_SYSTEM_MARISA "" ON)
add_subdirectory(OpenCC)
include(OpenccWorkarounds)

set(X11Keysym "${CMAKE_SOURCE_DIR}/librime/include")
include_directories("${X11Keysym}")

# Local-build fix (not upstream): BUILD_SHARED_LIBS is forced to ON further down
# for sherpa-onnx, so the value persists in CMakeCache.txt. On a *re-configure*
# that made librime build its shared flavour, and Rime.cmake then failed with
#   CMake Error at cmake/Rime.cmake:28 (target_compile_options):
#     Cannot specify compile options for target "rime-static" which is not built
#     by this project.
# Reset it before including Rime so every configure builds the static library
# that librime_jni links against.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(Rime)

add_subdirectory(librime_jni)

# ---- lua_theme_jni ----
add_subdirectory(lua_theme_jni)

# ---- sherpa-onnx (voice ASR with optional QNN) ----
# Download the onnxruntime shared libs up front so that libonnxruntime.so is
# always available locally for pre-packaging into the APK.
include(OnnxRuntime)

# Point sherpa-onnx's cmake module to the downloaded onnxruntime shared libs
set(ENV{SHERPA_ONNXRUNTIME_LIB_DIR} "${SHERPA_ONNXRUNTIME_LIB_DIR}")
set(ENV{SHERPA_ONNXRUNTIME_INCLUDE_DIR} "${SHERPA_ONNXRUNTIME_INCLUDE_DIR}")

set(SHERPA_ONNX_ENABLE_PYTHON OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_CHECK OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PORTAUDIO OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_WEBSOCKET OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_GPU OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_BINARY OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TTS OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_JNI ON CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_C_API ON CACHE BOOL "" FORCE)
set(SHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

# Local-build fix (not upstream): the original unquoted form
#   if(CMAKE_ANDROID_ARCH_ABI STREQUAL arm64-v8a AND (DEFINED ENV{QNN_SDK_ROOT}
#      OR NOT QNN_SDK_ROOT STREQUAL ""))
# evaluates to TRUE even when QNN_SDK_ROOT is completely unset, because
# `NOT QNN_SDK_ROOT` is parsed as a nil variable negation and the trailing
# `STREQUAL ""` then compares booleans. That made a CPU-only build (no QNN SDK,
# qnnSdkRoot/qnnVariant removed from gradle.properties) abort in
# sherpa-onnx/CMakeLists.txt:435 with "Please specify the installation directory
# of the QNN SDK toolkit". Quoting both operands restores the intended meaning.
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" AND (DEFINED ENV{QNN_SDK_ROOT}
   OR NOT "${QNN_SDK_ROOT}" STREQUAL ""))
  if(NOT DEFINED ENV{QNN_SDK_ROOT} AND NOT "${QNN_SDK_ROOT}" STREQUAL "")
    set(ENV{QNN_SDK_ROOT} "${QNN_SDK_ROOT}")
  endif()
  set(SHERPA_ONNX_ENABLE_QNN ON CACHE BOOL "" FORCE)
else()
  set(SHERPA_ONNX_ENABLE_QNN OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(sherpa-onnx)

# ---- bamboo handwriting (ONNX recognition for the handwriting keyboard) ----
# 必须放在 sherpa-onnx 之后：它用 include(OnnxRuntime) 提供的
# SHERPA_ONNXRUNTIME_INCLUDE_DIR / SHERPA_ONNXRUNTIME_LIB_DIR。
add_subdirectory(bamboo_handwriting)

# ---- bamboo ocr (PP-OCRv5 det + rec for the camera text scanner) ----
# 同上，复用 sherpa-onnx 下载的 onnxruntime（C API）。
add_subdirectory(bamboo_ocr)
