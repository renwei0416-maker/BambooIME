/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 截图界面的扇形菜单。
 *
 * 以球心为圆心，把菜单项沿一段圆弧向外散开；选中项会放大并把楔形从球心连过去，
 * 楔形越靠外越淡、末端贴着圆的边缘（内凹），所以看起来是「从球上长出来的一条条光带」。
 *
 * 关键行为：
 * - 朝向：球在屏幕右半边就朝左开，在左半边就朝右开，保证整体留在屏幕里。
 * - 放得下优先：项多时自动缩小半径和项直径（半径 88→70dp、直径 32→24dp），
 *   都缩到底还放不下才由调用方分层（[ShotActivity] 负责第二层的「更多」）。
 * - 标签最多 4 个字，字号按圆内可用宽度自动缩，不出圆。
 *
 * 触摸：按下后跟手更新选中项，松手时执行；滑回球心附近算取消。
 */
class ShotFanMenu(
    context: Context,
) : View(context) {
    /** 选中某项时回调（下标对应 [show] 传进来的列表）。 */
    var onSelect: ((Int) -> Unit)? = null

    /** 没有选中（滑回球心后松手）时回调。 */
    var onDismiss: (() -> Unit)? = null

    var isShowing: Boolean = false
        private set

    private var titles: List<String> = emptyList()
    private var centerX = 0f
    private var centerY = 0f
    private var selected = -1

    /** 当前实际使用的半径与项半径（随项数自动缩）。 */
    private var ringPx = 0f
    private var itemPx = 0f
    private var boostPx = 0f
    private var startAngle = 0f
    private var spanAngle = 0f

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val wedgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
        }
    private val iconPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
            isSubpixelText = true
        }
    private val wedgePath = Path()
    private val tmpRect = RectF()

    private fun dp(v: Float): Float = v * density

    /** 显示菜单。球心用屏幕坐标（本 View 铺满全屏）。 */
    fun show(
        cx: Float,
        cy: Float,
        labels: List<String>,
    ) {
        if (labels.isEmpty()) return
        titles = labels
        centerX = cx
        centerY = cy
        selected = -1
        isShowing = true
        visibility = VISIBLE
        computeLayout()
        invalidate()
    }

    fun hide() {
        isShowing = false
        selected = -1
        visibility = GONE
        titles = emptyList()
    }

    /**
     * 依据项数与可用空间算出半径、项半径与起始角。
     *
     * 顺序：先按「项数需要的弧长」定半径，再受屏幕边界约束压低半径，
     * 最后按压缩后的弧长反推项半径，保证相邻圆不粘连。
     */
    private fun computeLayout() {
        val n = titles.size
        spanAngle = if (n <= 6) 150f else 190f
        // 球在右半边朝左开，在左半边朝右开。
        val openLeft = centerX > width * 0.5f
        startAngle =
            if (openLeft) {
                180f - spanAngle / 2f
            } else {
                -spanAngle / 2f
            }

        val step = if (n > 1) Math.toRadians((spanAngle / (n - 1)).toDouble()) else 0.0
        var ring = dp(88f)
        var itemR = dp(16f)
        if (n > 1) {
            // 相邻两项不粘连所需的最小半径：chord = 2*ring*sin(step/2) >= 2*itemR + gap
            val need = (2 * itemR + dp(3f)) / (2 * sin(step / 2)).toFloat()
            ring = maxOf(ring, need)
        }
        // 屏幕边界：球心到四条边最近的距离，留出项半径与 10dp 余量。
        val maxRing =
            min(
                min(centerX, width - centerX),
                min(centerY, height - centerY),
            ) - itemR - dp(10f)
        if (maxRing > dp(40f) && ring > maxRing) ring = maxRing
        // 半径被压后重新算项半径，避免粘连；下限 12dp（直径 24dp）。
        if (n > 1) {
            val chord = 2 * ring * sin(step / 2).toFloat()
            itemR = min(itemR, (chord - dp(3f)) / 2f)
        }
        itemR = itemR.coerceIn(dp(12f), dp(16f))
        ringPx = ring.coerceAtLeast(dp(44f))
        itemPx = itemR
        boostPx = itemPx * 1.26f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isShowing || titles.isEmpty()) return
        val n = titles.size
        // 选中项：先画楔形（从球心到圆、越外越淡、末端内凹贴圆）。
        if (selected in 0 until n) drawWedge(canvas, selected)
        for (i in 0 until n) {
            val a = Math.toRadians(angleOf(i).toDouble())
            val cx = centerX + ringPx * cos(a).toFloat()
            val cy = centerY + ringPx * sin(a).toFloat()
            val picked = i == selected
            val r = if (picked) boostPx else itemPx
            val label = titles[i]
            itemPaint.color =
                when {
                    label == EXIT_LABEL -> EXIT_COLOR
                    picked -> SELECTED_COLOR
                    else -> 0xF7FFFFFF.toInt()
                }
            canvas.drawCircle(cx, cy, r, itemPaint)
            if (label == EXIT_LABEL) {
                drawPowerIcon(canvas, cx, cy, r * 0.52f)
            } else {
                drawLabel(canvas, cx, cy, r, label, picked)
            }
        }
    }

    /** 从球心到第 [index] 项的楔形：径向渐变 + 末端反向弧贴住圆。 */
    private fun drawWedge(
        canvas: Canvas,
        index: Int,
    ) {
        val n = titles.size
        val stepDeg = if (n > 1) spanAngle / (n - 1) else spanAngle
        val a0 = angleOf(index) - stepDeg / 2f
        val a1 = angleOf(index) + stepDeg / 2f
        val outer = ringPx - itemPx * 0.9f
        if (outer <= 0f) return

        wedgePaint.shader =
            RadialGradient(
                centerX,
                centerY,
                ringPx,
                intArrayOf(0x88B0472A.toInt(), 0x00B0472A),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )

        val path = wedgePath
        path.reset()
        path.moveTo(centerX, centerY)
        path.lineTo(
            centerX + outer * cos(Math.toRadians(a0.toDouble())).toFloat(),
            centerY + outer * sin(Math.toRadians(a0.toDouble())).toFloat(),
        )
        tmpRect.set(centerX - outer, centerY - outer, centerX + outer, centerY + outer)
        path.arcTo(tmpRect, a0, a1 - a0, false)
        // 末端：以项心为圆心、半径略大于项的圆弧反向切回来，形成内凹。
        val itemA = Math.toRadians(angleOf(index).toDouble())
        val icx = centerX + ringPx * cos(itemA).toFloat()
        val icy = centerY + ringPx * sin(itemA).toFloat()
        val arcR = itemPx * 1.12f
        tmpRect.set(icx - arcR, icy - arcR, icx + arcR, icy + arcR)
        path.arcTo(tmpRect, a1 + 180f, -(a1 - a0), false)
        path.close()
        canvas.drawPath(path, wedgePaint)
        wedgePaint.shader = null
    }

    /** 标签：最多 4 字，字号按圆内可用宽度自适应。 */
    private fun drawLabel(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        label: String,
        picked: Boolean,
    ) {
        if (label.isBlank()) return
        val avail = r * 2f * 0.84f
        var size = 10f * scaledDensity
        val fit = avail / label.length * 0.95f
        if (fit < size) size = fit
        size = size.coerceAtLeast(6.5f * scaledDensity)
        textPaint.textSize = size
        textPaint.color = if (picked) Color.WHITE else 0xFF2B2B2B.toInt()
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, cx, baseline, textPaint)
    }

    /** 「退出」用图标：一段缺口朝上的圆弧 + 顶部竖线。 */
    private fun drawPowerIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        iconPaint.strokeWidth = r * 0.22f
        tmpRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(tmpRect, -60f, 300f, false, iconPaint)
        canvas.drawLine(cx, cy - r * 1.15f, cx, cy - r * 0.15f, iconPaint)
    }

    private fun angleOf(index: Int): Float {
        val n = titles.size
        if (n <= 1) return startAngle + spanAngle / 2f
        return startAngle + spanAngle * index / (n - 1)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isShowing) return false
        val x = event.rawX
        val y = event.rawY - (rootViewTopOnScreen())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                selected = hitTest(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val hit = hitTest(x, y)
                val pick = selected
                selected = -1
                invalidate()
                if (hit >= 0 && hit == pick) onSelect?.invoke(hit) else onDismiss?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                selected = -1
                invalidate()
                onDismiss?.invoke()
                return true
            }
        }
        return true
    }

    /** 本 View 贴在窗口顶部，rawY 需要减去自身在屏幕上的 y。 */
    private fun rootViewTopOnScreen(): Float {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return loc[1].toFloat()
    }

    /** 命中：先判「滑回球心＝取消」，再取最近的圆（半径内 + 16dp 容差）。 */
    private fun hitTest(
        x: Float,
        y: Float,
    ): Int {
        if (hypot((x - centerX).toDouble(), (y - centerY).toDouble()) < dp(48f)) return -1
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in titles.indices) {
            val a = Math.toRadians(angleOf(i).toDouble())
            val cx = centerX + ringPx * cos(a).toFloat()
            val cy = centerY + ringPx * sin(a).toFloat()
            val d = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        val limit = (if (best >= 0 && best == selected) boostPx else itemPx) + dp(16f)
        return if (best >= 0 && abs(bestD) <= limit) best else -1
    }

    private fun toast(
        text: String,
    ) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val EXIT_LABEL = "退出"
        const val SELECTED_COLOR = 0xFFB0472A.toInt()
        const val EXIT_COLOR = 0xFF8E3B30.toInt()
    }
}
