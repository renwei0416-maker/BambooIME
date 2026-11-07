# Download pre-built onnxruntime shared libraries for Android.
#
# Local-build notes (not upstream):
#   * This module is also picked up by sherpa-onnx's own `include(onnxruntime)`
#     because Windows is case-insensitive and CMAKE_MODULE_PATH lists this
#     directory first. All paths below are therefore anchored to CMAKE_BINARY_DIR
#     (the ABI build directory) instead of CMAKE_CURRENT_BINARY_DIR, so it does
#     not matter which scope includes the module.
#   * LYRAIME_DL_CACHE may point at a directory holding a pre-downloaded
#     onnxruntime-android-<version>.zip (used for offline builds).

if(NOT DEFINED SHERPA_ONNX_ONNXRUNTIME_VERSION)
  set(SHERPA_ONNX_ONNXRUNTIME_VERSION "1.31.0")
endif()

message(STATUS "ONNXRUNTIME_VERSION: ${SHERPA_ONNX_ONNXRUNTIME_VERSION}")

if(CMAKE_ANDROID_ARCH_ABI STREQUAL arm64-v8a)
  set(ONNXRUNTIME_ANDROID_ABI "arm64-v8a")
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL x86_64)
  set(ONNXRUNTIME_ANDROID_ABI "x86_64")
else()
  message(FATAL_ERROR "Unsupported Android ABI: ${CMAKE_ANDROID_ARCH_ABI}")
endif()

set(ONNXRUNTIME_URL "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v${SHERPA_ONNX_ONNXRUNTIME_VERSION}/onnxruntime-android-${SHERPA_ONNX_ONNXRUNTIME_VERSION}.zip")
set(ONNXRUNTIME_DIR "${CMAKE_BINARY_DIR}/onnxruntime-android-${SHERPA_ONNX_ONNXRUNTIME_VERSION}")
set(ONNXRUNTIME_ZIP "onnxruntime-android-${SHERPA_ONNX_ONNXRUNTIME_VERSION}.zip")
set(ONNXRUNTIME_ZIP_PATH "${CMAKE_BINARY_DIR}/${ONNXRUNTIME_ZIP}")

if(NOT EXISTS "${ONNXRUNTIME_ZIP_PATH}")
  if(DEFINED ENV{LYRAIME_DL_CACHE}
     AND EXISTS "$ENV{LYRAIME_DL_CACHE}/${ONNXRUNTIME_ZIP}")
    message(
      STATUS
        "Using cached onnxruntime ${SHERPA_ONNX_ONNXRUNTIME_VERSION} from $ENV{LYRAIME_DL_CACHE}"
    )
    file(REMOVE "${ONNXRUNTIME_ZIP_PATH}")
    file(COPY "$ENV{LYRAIME_DL_CACHE}/${ONNXRUNTIME_ZIP}"
         DESTINATION "${CMAKE_BINARY_DIR}")
  else()
    message(STATUS "Downloading onnxruntime from ${ONNXRUNTIME_URL}")
    file(DOWNLOAD "${ONNXRUNTIME_URL}" "${ONNXRUNTIME_ZIP_PATH}"
         EXPECTED_HASH
            SHA256=be178835b05a8e9439e8fa1729072e4f0437166fdaf3390ea76156ff1df6f56c
         SHOW_PROGRESS STATUS download_status)
    list(GET download_status 0 status_code)
    if(NOT status_code EQUAL 0)
      file(REMOVE "${ONNXRUNTIME_ZIP_PATH}")
      message(FATAL_ERROR "Failed to download onnxruntime: ${ONNXRUNTIME_URL}")
    endif()
  endif()
endif()

if(NOT EXISTS "${ONNXRUNTIME_DIR}/jni/${ONNXRUNTIME_ANDROID_ABI}/libonnxruntime.so")
  message(STATUS "Extracting onnxruntime from ${ONNXRUNTIME_ZIP_PATH}")
  file(ARCHIVE_EXTRACT INPUT "${ONNXRUNTIME_ZIP_PATH}" DESTINATION "${ONNXRUNTIME_DIR}")

  # Local port hardening: require the library for the ABI being built. The
  # upstream code fell back to any libonnxruntime.so found anywhere, which let a
  # half-extracted tree (e.g. only jni/x86_64 present) silently pass configure
  # and then fail much later with "onnxruntime_cxx_api.h file not found".
  if(NOT EXISTS "${ONNXRUNTIME_DIR}/jni/${ONNXRUNTIME_ANDROID_ABI}/libonnxruntime.so")
    message(FATAL_ERROR "libonnxruntime.so for ABI ${ONNXRUNTIME_ANDROID_ABI} not found in ${ONNXRUNTIME_DIR}: extraction of ${ONNXRUNTIME_ZIP} failed")
  endif()

  message(STATUS "onnxruntime extracted to ${ONNXRUNTIME_DIR}")
endif()

set(SHERPA_ONNXRUNTIME_LIB_DIR "${ONNXRUNTIME_DIR}/jni/${ONNXRUNTIME_ANDROID_ABI}" CACHE PATH "" FORCE)
set(SHERPA_ONNXRUNTIME_INCLUDE_DIR "${ONNXRUNTIME_DIR}/headers" CACHE PATH "" FORCE)

# Local-build note (not upstream): on Linux sherpa-onnx resolves `include(onnxruntime)`
# to its own cmake/onnxruntime-android-<arch>.cmake, which defines the imported
# target and the include path. On Windows the case-insensitive lookup finds this
# module instead, so the same target has to be created here, otherwise sherpa's
# sources fail with "onnxruntime_cxx_api.h file not found".
if(NOT TARGET onnxruntime)
  add_library(onnxruntime SHARED IMPORTED)
  set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${SHERPA_ONNXRUNTIME_LIB_DIR}/libonnxruntime.so"
    INTERFACE_INCLUDE_DIRECTORIES "${SHERPA_ONNXRUNTIME_INCLUDE_DIR}")
endif()

include_directories("${SHERPA_ONNXRUNTIME_INCLUDE_DIR}")

file(GLOB ONNXRUNTIME_LIB_FILES "${SHERPA_ONNXRUNTIME_LIB_DIR}/libonnxruntime*")
