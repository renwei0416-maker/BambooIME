// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.actions.handwriting

/**
 * 手写识别的 JNI 入口（`libbamboo_handwriting.so`）。
 *
 * 推理本身在 C++ 里跑（onnxruntime C API），这里只做数据搬运。
 * 调用约定：**不要在 UI 线程调用 [nativeRun]**，一次推理几十毫秒到一两百毫秒。
 */
internal object HandwritingNative {
    private var loaded = false

    init {
        loaded = runCatching {
            // 先显式载入 onnxruntime（离线包和 sherpa 语音共用同一份），
            // 万一系统没按 DT_NEEDED 自动解析，这里能给出明确日志。
            runCatching { System.loadLibrary("onnxruntime") }
            System.loadLibrary("bamboo_handwriting")
            true
        }.getOrElse { false }
    }

    val isAvailable: Boolean get() = loaded

    external fun nativeInit(modelPath: String): Boolean

    /**
     * @param input 长度 200*5 的坐标序列（x, y, dx, dy, pen）
     * @param mask  长度 200，前 seqLen 个为 true
     * @return [类序号, 概率, 类序号, 概率, …]，失败返回 null
     */
    external fun nativeRun(input: FloatArray, mask: BooleanArray, topK: Int): FloatArray?

    external fun nativeRelease()
}
