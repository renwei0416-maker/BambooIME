// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.actions.ocr

import android.content.Context
import android.graphics.Bitmap
import timber.log.Timber

/**
 * 拍照识别的 Kotlin 侧封装：加载模型、把 Bitmap 转成 PPT 用的 RGB 缓冲、调用 native。
 *
 * 线程：`recognize()` 会阻塞（det + rec 推理，几十到几百毫秒），只能在后台线程调。
 * 模型只加载一次，之后常驻；[release] 用于低内存时释放。
 */
object OcrRecognizer {
    private val lock = Any()

    @Volatile
    private var ready = false

    private var signature: String? = null

    /** 最近一次失败的原因，给 UI 提示用。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 是否可用（模型已加载或可以加载）。 */
    fun isReady(): Boolean = ready

    /**
     * 确保模型已加载。多次调用只真正加载一次；模型文件变了会重新加载。
     */
    fun ensureReady(context: Context): Boolean =
        synchronized(lock) {
            val entry =
                try {
                    OcrModelStore.ensureInstalled(context)
                } catch (error: Throwable) {
                    Timber.w(error, "ocr: install models failed")
                    lastError = "模型文件缺失"
                    return false
                }
            if (!entry.ready) {
                lastError = "模型文件缺失"
                return false
            }
            val sign = "${entry.det.absolutePath}|${entry.det.length()}|${entry.rec.length()}"
            if (ready && sign == signature) return true

            if (ready) {
                runCatching { OcrNative.nativeRelease() }
                ready = false
            }
            val alphabet =
                try {
                    // 第 0 项留给 CTC blank。字典文件共 18383 行 + 末尾换行，
                    // 模型输出 18385 类 = 1(blank) + 18384，所以必须按 "\n" 切分、
                    // **保留末尾那个空行**；用 readLines() 会把它丢掉，少一类，
                    // C++ 侧类别校验不过就直接返回空。
                    arrayListOf("").apply {
                        addAll(entry.chars.readText(Charsets.UTF_8).split("\n"))
                    }.toTypedArray()
                } catch (error: Throwable) {
                    Timber.w(error, "ocr: read alphabet failed")
                    lastError = "字典读取失败"
                    return false
                }
            if (alphabet.size < 2) {
                lastError = "字典为空"
                return false
            }
            val ok =
                runCatching {
                    OcrNative.nativeInit(entry.det.absolutePath, entry.rec.absolutePath) &&
                        OcrNative.nativeSetAlphabet(alphabet)
                }.getOrElse { error ->
                    Timber.w(error, "ocr: native init failed")
                    false
                }
            if (!ok) {
                lastError = "识别模型加载失败"
                return false
            }
            signature = sign
            ready = true
            lastError = null
            Timber.d("ocr: models ready, ${alphabet.size} chars")
            true
        }

    /**
     * 识别一张图（已经在调用方裁好取景框）。返回按行拼接的文本，没认出来返回 null。
     */
    fun recognize(bitmap: Bitmap): String? {
        if (!ready) {
            lastError = "识别模型未就绪"
            return null
        }
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val rgb = ByteArray(width * height * 3)
        var index = 0
        for (color in argb) {
            rgb[index++] = (color shr 16).toByte()
            rgb[index++] = (color shr 8).toByte()
            rgb[index++] = color.toByte()
        }
        return runCatching { OcrNative.nativeRecognize(rgb, width, height) }
            .getOrElse { error ->
                Timber.w(error, "ocr: recognize failed")
                lastError = "识别失败"
                null
            }
    }

    fun release() =
        synchronized(lock) {
            if (!ready) return
            runCatching { OcrNative.nativeRelease() }
            ready = false
            signature = null
        }
}
