// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.actions.ocr

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * 送识别之前先判断这一帧有没有意义（过暗、没细节直接丢弃），
 * 以及「实时文字要连续两帧一致才显示」的稳定性判定。
 *
 * 判定参数与 CyIME 的 `OcrFrameQuality` / `OcrPreviewStability` 一致。
 */
internal object OcrFrameQuality {
    enum class Verdict { READY, DARK, LOW_DETAIL }

    fun assess(bitmap: Bitmap): Verdict {
        val scale = minOf(1f, 480f / bitmap.width, 240f / bitmap.height)
        val sample =
            if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
        return try {
            val pixels = IntArray(sample.width * sample.height)
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
            assess(pixels, sample.width, sample.height)
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    fun assess(pixels: IntArray, width: Int, height: Int): Verdict {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val histogram = IntArray(256)
        val gray =
            IntArray(pixels.size) { index ->
                val color = pixels[index]
                ((77 * (color shr 16 and 255) + 150 * (color shr 8 and 255) + 29 * (color and 255)) shr 8)
                    .also { histogram[it]++ }
            }
        fun percentile(fraction: Float): Int {
            val rank = (pixels.size * fraction).toInt().coerceAtLeast(1)
            var sum = 0
            return histogram.indices.first {
                sum += histogram[it]
                sum >= rank
            }
        }
        // 忽略零星热噪点，同时保留深色底上的小字
        val low = percentile(.002f)
        val high = percentile(.998f)
        if (high < 40) return Verdict.DARK
        if (high - low < 24) return Verdict.LOW_DETAIL
        var edges = 0
        for (y in 1 until height) {
            for (x in 1 until width) {
                val index = y * width + x
                if (abs(gray[index] - gray[index - 1]) >= 20 || abs(gray[index] - gray[index - width]) >= 20) {
                    edges++
                }
            }
        }
        return if (edges < pixels.size * .003f) Verdict.LOW_DETAIL else Verdict.READY
    }
}

/** 实时文字连续两帧一致才算数；画面一变就立刻清空。 */
internal class OcrPreviewStability {
    private var previous = ""

    fun update(text: String): String {
        val next = text.trim()
        val stable = next.isNotEmpty() && next == previous
        previous = next
        return if (stable) next else ""
    }

    fun reset() {
        previous = ""
    }
}
