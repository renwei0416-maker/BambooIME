// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.actions.handwriting

import android.content.Context
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 手写识别管线：笔画预处理 → 原生推理 → 类序号映射成汉字。
 *
 * 预处理配方与上游 ochwpro / CyIME 保持一致（逐行对齐），改这里会让识别率明显变化，
 * 动之前先用同一张字对比新旧 top-5。
 */
object HandwritingRecognizer {
    private const val FIXED_LEN = 200
    private const val FEAT_DIM = 5
    private const val POINTS_PER_STROKE = 8
    private const val MAX_POINTS_CURSIVE = 40
    private const val CURSIVE_CORNERS = 5
    private const val CORNERS_PER_8_POINTS = 4
    private const val CORNER_TOLERANCE = 0.05f

    data class Candidate(val text: String, val score: Float)

    @Volatile
    private var chars: List<String> = emptyList()

    @Volatile
    private var loadedVersion: String? = null

    val isReady: Boolean get() = HandwritingNative.isAvailable && chars.isNotEmpty()

    val isEngineAvailable: Boolean get() = HandwritingNative.isAvailable

    /** 加载某个版本的字表并初始化推理会话。 */
    @Synchronized
    fun load(context: Context, version: String): Boolean {
        if (!HandwritingNative.isAvailable) {
            Timber.w("handwriting: native library unavailable")
            return false
        }
        if (loadedVersion == version && chars.isNotEmpty()) return true
        if (!HandwritingModelStore.ensureInstalled(context, version)) {
            Timber.w("handwriting: model %s not installed", version)
            return false
        }
        val entry = HandwritingModelStore.entry(context, version)
        val list = runCatching { parseCharIndex(entry.chars) }
            .onFailure { Timber.w(it, "handwriting: parse char index failed") }
            .getOrNull()
        if (list.isNullOrEmpty()) return false
        val ok = HandwritingNative.nativeInit(entry.model.absolutePath)
        if (!ok) {
            Timber.w("handwriting: nativeInit failed for %s", entry.model)
            return false
        }
        chars = list
        loadedVersion = version
        Timber.i("handwriting: ready, %d classes (model %s)", list.size, version)
        return true
    }

    @Synchronized
    fun release() {
        runCatching { HandwritingNative.nativeRelease() }
        chars = emptyList()
        loadedVersion = null
    }

    private fun parseCharIndex(file: File): List<String> {
        val json = JSONObject(file.readText().trimStart('\uFEFF'))
        val arr = json.optJSONArray("chars")
        if (arr != null) {
            return (0 until arr.length()).map { arr.optString(it, "") }
        }
        // 兼容 {"char_index": {汉字: 序号}} 与 {汉字: 序号} 两种旧格式
        val obj = json.optJSONObject("char_index") ?: json
        val indexed = LinkedHashMap<Int, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            indexed[obj.optInt(key, -1)] = key
        }
        val max = indexed.keys.maxOrNull() ?: return emptyList()
        val out = MutableList(max + 1) { "" }
        indexed.forEach { (idx, ch) -> if (idx >= 0) out[idx] = ch }
        return out
    }

    /**
     * 识别一组笔画。坐标用画布像素即可，内部会做归一化。
     */
    fun recognize(strokes: List<List<Pair<Float, Float>>>, topK: Int): List<Candidate> {
        if (!isReady || topK <= 0) return emptyList()
        val simplified = simplifyStrokesCursive(strokes)
        if (simplified.isEmpty()) return emptyList()
        val (seq, len) = strokesToSequence(simplified)
        if (len == 0) return emptyList()
        val raw = HandwritingNative.nativeRun(seq, buildMask(len), min(chars.size, topK * 5))
            ?: return emptyList()
        val table = chars
        val out = ArrayList<Candidate>(topK)
        val seen = HashSet<String>()
        var i = 0
        while (i + 1 < raw.size && out.size < topK) {
            val idx = raw[i].toInt()
            val score = raw[i + 1]
            i += 2
            if (idx < 0 || idx >= table.size) continue
            val text = table[idx]
            if (text.isEmpty() || !seen.add(text)) continue
            out.add(Candidate(text, score))
        }
        return out
    }

    // ---- 预处理（与上游一致） ----

    private fun simplifyStrokesCursive(
        strokes: List<List<Pair<Float, Float>>>,
    ): List<List<Pair<Float, Float>>> {
        val all = strokes.flatten()
        if (all.isEmpty()) return emptyList()
        val width = all.maxOf { it.first } - all.minOf { it.first }
        val height = all.maxOf { it.second } - all.minOf { it.second }
        val size = max(max(width, height), 1f)
        val out = strokes.map { stroke ->
            val corners = if (stroke.size > 2) cornerCount(stroke, CORNER_TOLERANCE * size) else stroke.size
            if (corners <= CURSIVE_CORNERS) {
                sample(stroke, POINTS_PER_STROKE)
            } else {
                val groups = ceil((corners - 1).toFloat() / CORNERS_PER_8_POINTS).toInt()
                sample(stroke, min(MAX_POINTS_CURSIVE, POINTS_PER_STROKE * groups))
            }
        }
        return if (out.sumOf { it.size } > FIXED_LEN) strokes.map { sample(it, POINTS_PER_STROKE) } else out
    }

    private fun sample(stroke: List<Pair<Float, Float>>, k: Int): List<Pair<Float, Float>> {
        if (stroke.size <= k || k <= 1) return stroke
        val step = (stroke.size - 1).toFloat() / (k - 1)
        return (0 until k).map { stroke[(it * step).roundToInt().coerceIn(0, stroke.size - 1)] }
    }

    /** Douglas-Peucker 保留点数（含两端）。 */
    private fun cornerCount(stroke: List<Pair<Float, Float>>, eps: Float): Int {
        if (stroke.size < 3) return stroke.size
        val keep = BooleanArray(stroke.size)
        keep[0] = true
        keep[stroke.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to stroke.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val (ax, ay) = stroke[a]
            val (bx, by) = stroke[b]
            val vx = bx - ax
            val vy = by - ay
            val len = hypot(vx, vy).takeIf { it > 0f } ?: 1f
            var best = -1f
            var at = -1
            for (i in a + 1 until b) {
                val (px, py) = stroke[i]
                val d = abs(vx * (py - ay) - vy * (px - ax)) / len
                if (d > best) {
                    best = d
                    at = i
                }
            }
            if (best > eps && at > 0) {
                keep[at] = true
                stack.addLast(a to at)
                stack.addLast(at to b)
            }
        }
        return keep.count { it }
    }

    /** 拼成 200×5 的序列：x, y, dx, dy, pen_down（每笔最后一个点 pen=0）。 */
    private fun strokesToSequence(
        strokes: List<List<Pair<Float, Float>>>,
    ): Pair<FloatArray, Int> {
        val points = ArrayList<Pair<Float, Float>>()
        val pen = ArrayList<Float>()
        for (stroke in strokes) {
            for (p in stroke) {
                points.add(p)
                pen.add(1f)
            }
            if (pen.isNotEmpty()) pen[pen.size - 1] = 0f
        }
        val total = points.size
        val seq = FloatArray(FIXED_LEN * FEAT_DIM)
        if (total == 0) return seq to 0

        val minX = points.minOf { it.first }
        val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }
        val rangeX = max(maxX - minX, 1f)
        val rangeY = max(maxY - minY, 1f)

        val len = min(total, FIXED_LEN)
        for (i in 0 until len) {
            val (x, y) = points[i]
            val base = i * FEAT_DIM
            seq[base] = (x - minX) / rangeX
            seq[base + 1] = (y - minY) / rangeY
            if (i > 0) {
                val (px, py) = points[i - 1]
                seq[base + 2] = (x - px) / rangeX
                seq[base + 3] = (y - py) / rangeY
            }
            seq[base + 4] = pen[i]
        }
        return seq to len
    }

    private fun buildMask(len: Int): BooleanArray {
        val mask = BooleanArray(FIXED_LEN)
        for (i in 0 until min(len, FIXED_LEN)) mask[i] = true
        return mask
    }
}
