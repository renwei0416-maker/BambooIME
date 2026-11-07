// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.actions.ocr

/**
 * 拍照识别的 native 入口。
 *
 * 推理在 [bamboo_ocr] 里用 onnxruntime 的 C API 做（模型 PP-OCRv5，Apache-2.0），
 * 运行时复用 APK 里已有的 `libonnxruntime.so`，所以没有额外的 AAR。
 */
internal object OcrNative {
    init {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("bamboo_ocr")
    }

    /** 加载 det/rec 两个模型；返回是否成功。 */
    external fun nativeInit(detPath: String, recPath: String): Boolean

    /** 传字典（第 0 项留空给 CTC blank）。 */
    external fun nativeSetAlphabet(chars: Array<String>): Boolean

    /**
     * 识别一张 RGB 图（[pixels] 是三字节一像素的 RGB 数据，长度 = width*height*3）。
     * 返回按行拼接的文本；没认出来时返回 null。
     */
    external fun nativeRecognize(pixels: ByteArray, width: Int, height: Int): String?

    external fun nativeRelease()
}
