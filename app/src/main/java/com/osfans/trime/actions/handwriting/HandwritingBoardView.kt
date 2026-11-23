// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.actions.handwriting

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 手写画布：记录笔画并画出来。
 *
 * 只负责“画”和“收点”，识别交给 [HandwritingWindow]；这样改笔刷样式不会碰到识别逻辑。
 */
@SuppressLint("ViewConstructor")
class HandwritingBoardView(context: Context) : View(context) {
    /** 每一笔是一串 (x, y) 像素坐标。 */
    private val strokes = ArrayList<ArrayList<Pair<Float, Float>>>()
    private var current: ArrayList<Pair<Float, Float>>? = null

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#1A1F1C")
        strokeWidth = dp(4f)
    }

    private val paper = Paint().apply { color = Color.parseColor("#FAF8F2") }

    /** 笔迹颜色（跟随主题文字色时由外面设置）。 */
    var inkColor: Int
        get() = ink.color
        set(value) {
            ink.color = value
            invalidate()
        }

    /** 画布底色（跟随主题按键底色时由外面设置）。 */
    var paperColor: Int
        get() = paper.color
        set(value) {
            paper.color = value
            invalidate()
        }

    /** 手指是否正按在画布上（识别前用它挡住“写到一半就开始识别”）。 */
    val isDrawing: Boolean
        get() = current != null

    /** 落笔前回调：用来取消上一次待触发的识别，否则上一笔的 500ms 计时会在新手写途中踩进来。 */
    var onStrokeStarted: (() -> Unit)? = null

    /** 抬笔后回调（笔画已入库）。 */
    var onStrokeFinished: (() -> Unit)? = null

    var penWidthDp: Float = 4f
        set(value) {
            field = value
            ink.strokeWidth = dp(value)
            invalidate()
        }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun undo(): Boolean {
        if (strokes.isEmpty()) return false
        strokes.removeAt(strokes.size - 1)
        invalidate()
        return true
    }

    fun clear() {
        strokes.clear()
        current = null
        invalidate()
    }

    val isEmpty: Boolean get() = strokes.isEmpty()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onStrokeStarted?.invoke()
                current = ArrayList<Pair<Float, Float>>().also {
                    it.add(event.x to event.y)
                    strokes.add(it)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val stroke = current ?: return true
                val last = stroke.lastOrNull()
                val x = event.x
                val y = event.y
                // 距离太近的点丢掉：既省内存，也避免 200 帧被噪声塞满
                if (last == null || hypot(x - last.first, y - last.second) > dp(1.5f)) {
                    stroke.add(x to y)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { if (it.isEmpty()) strokes.remove(it) }
                current = null
                invalidate()
                onStrokeFinished?.invoke()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paper)
        for (stroke in strokes) {
            if (stroke.size == 1) {
                val (x, y) = stroke[0]
                canvas.drawCircle(x, y, ink.strokeWidth / 2f, ink)
                continue
            }
            val path = Path()
            path.moveTo(stroke[0].first, stroke[0].second)
            for (i in 1 until stroke.size) {
                path.lineTo(stroke[i].first, stroke[i].second)
            }
            canvas.drawPath(path, ink)
        }
    }

    /** 给识别用的快照（复制一份，避免识别期间被 UI 线程改到）。 */
    fun snapshot(): List<List<Pair<Float, Float>>> = strokes.map { ArrayList(it) }
}
