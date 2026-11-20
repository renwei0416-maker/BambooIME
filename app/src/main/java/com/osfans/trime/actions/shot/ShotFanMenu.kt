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
 * - 固定键距：相邻两项圆心距恒为「项直径 + 8dp」，项少只出来一小段、项多顺着往一侧延长，
 *   不会像早先那样按角度把整段弧铺满（那就是「项少时散开」）。
 * - 放得下优先：项多时先加大半径、再加不下才缩小项直径（24dp 下限），
 *   仍放不下才由调用方分层（[ShotActivity] 负责第二层的「更多」）。
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
    /** 起始角（球在右＝180°，在左＝0°）、单向步进符号、每步角度。 */
    private var startAngle = 0f
    private var stepAngle = 0f

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val wedgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 圆球的白色描边：深色/同色背景上也能看清球的轮廓。 */
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 1.2f * density
        }
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

    /** 球被拖动时同步圆心，让整组跟着一起移动。 */
    fun moveTo(
        cx: Float,
        cy: Float,
    ) {
        if (!isShowing) return
        centerX = cx
        centerY = cy
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
        // 浮层窗口（常驻浮球）在 show() 时 View 还没测量，width/height 可能是 0；
        // 用屏幕尺寸兜底，否则边界判定会把整组误判为“超出屏幕”而把扇形转到球头顶。
        val availW = if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
        val availH = if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
        // 朝屏幕内侧开：球在右半边往左上方长，在左半边往右上方长。
        val openLeft = centerX > availW * 0.5f
        // 对称轴：球在右半边朝正左（180°），在左半边朝正右（0°）；整组以这条水平轴上下对称
        val axis = if (openLeft) 180f else 0f

        // 固定弦长：相邻两项圆心距 = 项直径 + 8dp，**不随项数变化**。
        // 项多时先加大半径；半径到顶还放不下才缩小项直径（24dp 下限）。
        var itemR = dp(16f)
        var ring = dp(88f)
        var step = 0f
        if (n > 1) {
            var guard = 0
            while (guard++ < 60) {
                val chord = 2 * itemR + dp(8f)
                val ratio = (chord / (2 * ring)).coerceIn(0f, 1f)
                step = Math.toDegrees((2 * Math.asin(ratio.toDouble()))).toFloat()
                if (step * (n - 1) <= 232f) break
                if (ring < dp(120f)) {
                    ring = (ring * 1.15f).coerceAtMost(dp(120f))
                } else {
                    itemR = (itemR - dp(0.5f)).coerceAtLeast(dp(12f))
                }
            }
        }
        ringPx = ring
        itemPx = itemR
        stepAngle = if (n > 1) step else 0f
        // 选中项放大 1.2 倍（题注里说的「变大」）
        boostPx = itemR * 1.20f

        // 先按对称轴摆放；最底下那一项会超出屏幕时，把整组沿弧整体上移（仍然保持对称）
        val halfSpan = stepAngle * (n - 1) / 2f
        val upSign = if (axis >= 90f) 1f else -1f
        startAngle = axis - halfSpan
        // 只有真正拿到高度（已测量）时才允许“整组上移”，否则宁可不动。
        if (height > 0 && !fitsInside(availW, availH)) {
            var shift = 0f
            while (shift <= 60f) {
                shift += 2f
                startAngle = axis - halfSpan + upSign * shift
                if (fitsInside(availW, availH)) break
            }
        }
    }

    /** 整组圆是否都落在屏幕内（留 8dp 余量）。 */
    private fun fitsInside(
        availW: Float,
        availH: Float,
    ): Boolean {
        val n = titles.size
        if (n == 0) return true
        val margin = dp(8f)
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (i in 0 until n) {
            val a = Math.toRadians(angleOf(i).toDouble())
            val x = centerX + ringPx * cos(a).toFloat()
            val y = centerY + ringPx * sin(a).toFloat()
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        return minX - itemPx >= margin &&
            maxX + itemPx <= availW - margin &&
            minY - itemPx >= margin &&
            maxY + itemPx <= availH - margin
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
                    else -> ITEM_COLOR
                }
            canvas.drawCircle(cx, cy, r, itemPaint)
            canvas.drawCircle(cx, cy, r, ringPaint)
            if (label == EXIT_LABEL) {
                drawPowerIcon(canvas, cx, cy, r * 0.52f)
            } else {
                drawLabel(canvas, cx, cy, r, label, picked)
            }
        }
    }

    /**
     * 从球心到第 [index] 项的楔形。
     *
     * 几何是「球心 → 项圆的两条切线 → 项圆近侧圆弧 → 回球心」：
     * 设 α = asin(r / R)（r 为放大后的项半径、R 为环半径），两条切点相对项心朝外方向 ±(90° - α)。
     * 这样直边正好与项圆相切、远端弧就是项圆自己的近侧弧，边缘完全贴合 —— 早先按「相邻项角度差」
     * 画会宽出 3dp 且反向弧接不上（被 close 拉成横切面），看起来就是“扇形没对准圆球”。
     */
    private fun drawWedge(
        canvas: Canvas,
        index: Int,
    ) {
        val ring = ringPx
        val r = boostPx
        if (ring <= r + 1f) return
        val alpha = Math.asin((r / ring).toDouble().coerceIn(-1.0, 1.0))
        val beta = Math.PI / 2 - alpha
        val a = Math.toRadians(angleOf(index).toDouble())
        val icx = centerX + ring * cos(a).toFloat()
        val icy = centerY + ring * sin(a).toFloat()

        val t1 = (a + beta).toFloat()
        val t2 = (a - beta).toFloat()
        val p1x = icx + r * cos(t1.toDouble()).toFloat()
        val p1y = icy + r * sin(t1.toDouble()).toFloat()

        val path = wedgePath
        path.reset()
        path.moveTo(centerX, centerY)
        path.lineTo(p1x, p1y)
        tmpRect.set(icx - r, icy - r, icx + r, icy + r)
        // 从 t1 沿项圆近侧弧扫到 t2（经过朝外的 a 方向），sweep 为负
        val startDeg = Math.toDegrees(t1.toDouble()).toFloat()
        val sweepDeg = -Math.toDegrees((t1 - t2).toDouble()).toFloat()
        path.arcTo(tmpRect, startDeg, sweepDeg, false)
        path.close()

        wedgePaint.shader =
            RadialGradient(
                centerX,
                centerY,
                ring,
                intArrayOf(0x88B0472A.toInt(), 0x00B0472A),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
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
        textPaint.color = Color.WHITE
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

    private fun angleOf(index: Int): Float = startAngle + stepAngle * index

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

        /** 未选中项：微信图标那个绿。 */
        const val ITEM_COLOR = 0xCC000000.toInt()
        const val SELECTED_COLOR = 0xFFB0472A.toInt()
        const val EXIT_COLOR = 0xFF8E3B30.toInt()
    }
}
