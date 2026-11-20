package com.osfans.trime.actions.shot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 截图界面的环形菜单：以菜单球为圆心散开若干圆形选项。
 *
 * 选项沿一段圆弧排布，手指滑到哪项哪项变大（"滚动变大"）；松手执行，
 * 滑回圆心附近取消。命中判定同时看角度和距离，避免点偏命中邻项。
 *
 * 圆心在屏幕左半边时朝向右侧展开，在右半边时朝左侧展开，避免被屏幕边缘裁掉。
 */
class ShotFanMenu(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = value * density

    private val itemRadius = dp(23)
    private val selectedRadius = dp(28)
    private val ringRadius = dp(132)

    private val fanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1F178A7A }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).coerceAtLeast(1f)
        color = 0x33178A7A
    }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12f * resources.displayMetrics.scaledDensity
    }

    private var labels: List<String> = emptyList()
    private var cx = 0f
    private var cy = 0f
    private var selected = -1

    /** 选中某项：参数是 0 起的下标。 */
    var onSelect: ((Int) -> Unit)? = null

    /** 松手时没选中任何项（滑回球心）。 */
    var onDismiss: (() -> Unit)? = null

    fun show(centerX: Float, centerY: Float, titles: List<String>) {
        labels = titles
        cx = centerX
        cy = centerY
        selected = -1
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(120L).start()
        invalidate()
    }

    fun hide() {
        visibility = GONE
        selected = -1
        invalidate()
    }

    val isShowing: Boolean get() = visibility == VISIBLE

    private fun startAngle(): Float = when {
        cx > width * 0.66f -> 130f
        cx < width * 0.34f -> 410f
        else -> 200f
    }

    private fun angleOf(index: Int): Float {
        val count = labels.size
        if (count <= 1) return startAngle()
        val span = 140f
        return startAngle() + span * index / (count - 1)
    }

    private fun centerOf(index: Int): Pair<Float, Float> {
        val rad = Math.toRadians(angleOf(index).toDouble())
        val radius = if (index == selected) ringRadius + dp(6) else ringRadius
        return (cx + cos(rad).toFloat() * radius) to (cy + sin(rad).toFloat() * radius)
    }

    override fun onDraw(canvas: Canvas) {
        if (labels.isEmpty()) return
        val first = angleOf(0)
        val last = angleOf(labels.size - 1)
        val radius = ringRadius + itemRadius
        val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        val path = Path().apply {
            moveTo(cx, cy)
            arcTo(box, minOf(first, last), abs(last - first), false)
            close()
        }
        canvas.drawPath(path, fanPaint)
        canvas.drawArc(box, minOf(first, last), abs(last - first), false, ringPaint)

        for (index in labels.indices) {
            val (ix, iy) = centerOf(index)
            val isSelected = index == selected
            itemPaint.color = if (isSelected) 0xFF178A7A.toInt() else 0xF7FFFFFF.toInt()
            canvas.drawCircle(ix, iy, if (isSelected) selectedRadius else itemRadius, itemPaint)
            textPaint.color = if (isSelected) 0xFFFFFFFF.toInt() else 0xFF23332C.toInt()
            val baseline = iy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(labels[index], ix, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (labels.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val hit = hitTest(event.x, event.y)
                if (hit != selected) {
                    selected = hit
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val hit = hitTest(event.x, event.y)
                if (hit >= 0) onSelect?.invoke(hit) else onDismiss?.invoke()
                selected = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                selected = -1
                onDismiss?.invoke()
                invalidate()
                return true
            }
        }
        return false
    }

    /** 命中：先看离圆心距离（滑回球心=取消），再看哪一项最近（角度+距离）。 */
    private fun hitTest(x: Float, y: Float): Int {
        val distance = hypot(x - cx, y - cy)
        if (distance < dp(48)) return -1
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (index in labels.indices) {
            val (ix, iy) = centerOf(index)
            val d = hypot(x - ix, y - iy)
            if (d < bestDistance) {
                bestDistance = d
                best = index
            }
        }
        return if (bestDistance <= itemRadius + dp(16)) best else -1
    }
}
