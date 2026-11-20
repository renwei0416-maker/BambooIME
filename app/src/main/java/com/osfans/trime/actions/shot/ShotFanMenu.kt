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

    private val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66B0472A }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
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
        // 项数多时把弧开大一点，7 项挤在 140° 里会叠在一起
        val span = if (count >= 7) 160f else 140f
        return startAngle() + span * index / (count - 1)
    }

    /** 最后一项「退出」用关机图标 + 暗红底。 */
    private fun isExit(index: Int): Boolean = labels.getOrNull(index) == EXIT_LABEL

    private fun centerOf(index: Int): Pair<Float, Float> {
        val rad = Math.toRadians(angleOf(index).toDouble())
        val radius = if (index == selected) ringRadius + dp(6) else ringRadius
        return (cx + cos(rad).toFloat() * radius) to (cy + sin(rad).toFloat() * radius)
    }

    override fun onDraw(canvas: Canvas) {
        if (labels.isEmpty()) return

        // 只画「球心 → 当前选中项」之间的一小块楔形，整块扇形会把屏幕糊住
        if (selected >= 0) {
            drawWedge(canvas, selected)
        }

        for (index in labels.indices) {
            val (ix, iy) = centerOf(index)
            val isSelected = index == selected
            val radius = if (isSelected) selectedRadius else itemRadius
            itemPaint.color = when {
                isExit(index) -> EXIT_COLOR
                isSelected -> SELECTED_COLOR
                else -> 0xF7FFFFFF.toInt()
            }
            canvas.drawCircle(ix, iy, radius, itemPaint)
            if (isExit(index)) {
                drawPowerIcon(canvas, ix, iy, radius)
            } else {
                textPaint.color = if (isSelected) 0xFFFFFFFF.toInt() else 0xFF23332C.toInt()
                val baseline = iy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(labels[index], ix, baseline, textPaint)
            }
        }
    }

    /** 从球心到选中项的一条半透明楔形，跟着手指走。 */
    private fun drawWedge(canvas: Canvas, index: Int) {
        val angle = angleOf(index)
        val half = Math.toDegrees(kotlin.math.asin((itemRadius * 0.9f / ringRadius).toDouble())).toFloat()
        val radius = ringRadius - itemRadius * 0.9f
        val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val path = Path().apply {
            moveTo(cx, cy)
            arcTo(box, angle - half, half * 2f, false)
            close()
        }
        canvas.drawPath(path, wedgePaint)
    }

    /** 关机图标：大半圈 + 顶部一竖。 */
    private fun drawPowerIcon(canvas: Canvas, ix: Float, iy: Float, radius: Float) {
        iconPaint.strokeWidth = radius * 0.14f
        val r = radius * 0.46f
        canvas.drawArc(RectF(ix - r, iy - r, ix + r, iy + r), -60f, 300f, false, iconPaint)
        canvas.drawLine(ix, iy - r * 1.35f, ix, iy - r * 0.15f, iconPaint)
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

    private companion object {
        const val EXIT_LABEL = "退出"
        const val SELECTED_COLOR = 0xFFB0472A.toInt()
        const val EXIT_COLOR = 0xFF8E3B30.toInt()
    }
}
