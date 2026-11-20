package com.osfans.trime.actions.shot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * 截图界面的菜单球：关闭态是 80% 黑底 + 三条横线，展开后底色变青绿。
 */
class ShotBallView(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }

    /** 菜单是否已展开：影响底色。 */
    var opened: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val radius = minOf(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        bgPaint.color = if (opened) OPEN_COLOR else CLOSED_COLOR
        canvas.drawCircle(cx, cy, radius, bgPaint)
        linePaint.strokeWidth = radius * 0.15f
        val half = radius * 0.44f
        val gap = radius * 0.32f
        for (row in -1..1) {
            val y = cy + row * gap
            canvas.drawLine(cx - half, y, cx + half, y, linePaint)
        }
    }

    private companion object {
        const val CLOSED_COLOR = 0xCC000000.toInt()
        const val OPEN_COLOR = 0xFF178A7A.toInt()
    }
}