# SPDX-FileCopyrightText: 2026 BambooIME contributors
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 拍照识字（PP-OCRv5 det + rec 推理）。模型来自 PaddleOCR（Apache-2.0），
# 运行时直接复用 sherpa-onnx 已经下载好的 onnxruntime-android 1.31，
# 因此这里只做 C API 调用，不引入额外 AAR，APK 里的 native 体积不增加。

aux_source_directory(. BAMBOO_OCR_SOURCES)

add_library(bamboo_ocr SHARED ${BAMBOO_OCR_SOURCES})

target_include_directories(bamboo_ocr PRIVATE
    "${SHERPA_ONNXRUNTIME_INCLUDE_DIR}"
)

target_link_libraries(bamboo_ocr
    "${SHERPA_ONNXRUNTIME_LIB_DIR}/libonnxruntime.so"
    log
)
