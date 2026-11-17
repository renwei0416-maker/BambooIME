/*
 * SPDX-FileCopyrightText: 2026 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settings.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * 自绘取色器：上面一条横向色相条，下面一个饱和度/明度方块。
 *
 * 只负责颜色本身（HSV），透明度由外面的滑条单独控制，
 * 因为主题配色的 alpha 有时是 0（完全透明），用取色器调 alpha 会很别扭。
 */
class ColorPickerView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onColorChanged: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val hueBarHeight = 22f * density
    private val gap = 10f * density

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33FFFFFF
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
    }
    private val ringShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        color = 0x66000000
    }

    private var hue = 0f      // 0..360
    private var sat = 0f      // 0..1
    private var value = 0f    // 0..1
    private var alpha = 255

    private val hueRect = RectF()
    private val svRect = RectF()

    private var hueShader: Shader? = null
    private var svHueShader: Shader? = null
    private var svWhiteShader: Shader? = null
    private var svBlackShader: Shader? = null

    /** ARGB；设置时保留 HSV，只更新 alpha。 */
    var color: Int
        get() = Color.HSVToColor(alpha, floatArrayOf(hue, sat, value))
        set(v) {
            alpha = Color.alpha(v)
            val hsv = FloatArray(3)
            Color.colorToHSV(v, hsv)
            hue = hsv[0]
            sat = hsv[1]
            value = hsv[2]
            buildShaders()
            invalidate()
        }

    var alphaValue: Int
        get() = alpha
        set(v) {
            alpha = v.coerceIn(0, 255)
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val square = (w * 0.6f).coerceAtLeast(120f * density)
        val h = (hueBarHeight + gap + square).roundToInt() + (8f * density).roundToInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        hueRect.set(0f, 0f, w.toFloat(), hueBarHeight)
        val top = hueBarHeight + gap
        val square = (w * 0.6f).coerceAtLeast(120f * density)
        svRect.set(0f, top, w.toFloat(), (top + square).coerceAtMost(h.toFloat()))
        buildShaders()
    }

    private fun buildShaders() {
        if (width == 0) return
        val colors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED,
        )
        hueShader = LinearGradient(0f, 0f, hueRect.width(), 0f, colors, null, Shader.TileMode.CLAMP)
        svHueShader = LinearGradient(
            svRect.left, svRect.top, svRect.right, svRect.top,
            Color.HSVToColor(floatArrayOf(hue, 1f, 1f)), Color.WHITE,
            Shader.TileMode.CLAMP,
        )
        svWhiteShader = LinearGradient(
            svRect.left, svRect.top, svRect.right, svRect.top,
            0xFFFFFFFF.toInt(), 0x00FFFFFF,
            Shader.TileMode.CLAMP,
        )
        svBlackShader = LinearGradient(
            svRect.left, svRect.top, svRect.left, svRect.bottom,
            0x00000000, 0xFF000000.toInt(),
            Shader.TileMode.CLAMP,
        )
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        // 色相条
        fill.shader = hueShader
        canvas.drawRoundRect(hueRect, hueBarHeight / 2f, hueBarHeight / 2f, fill)
        fill.shader = null
        canvas.drawRoundRect(hueRect, hueBarHeight / 2f, hueBarHeight / 2f, border)
        val hx = hue / 360f * hueRect.width()
        canvas.drawCircle(hx.coerceIn(6f, hueRect.width() - 6f), hueRect.centerY(), 7f * density, ringShadow)
        canvas.drawCircle(hx.coerceIn(6f, hueRect.width() - 6f), hueRect.centerY(), 7f * density, ring)

        // 饱和度/明度方块：底色是当前色相的全饱和色，左白右透，上透下黑
        fill.shader = svHueShader
        canvas.drawRect(svRect, fill)
        fill.shader = svWhiteShader
        canvas.drawRect(svRect, fill)
        fill.shader = svBlackShader
        canvas.drawRect(svRect, fill)
        fill.shader = null
        canvas.drawRect(svRect, border)

        val px = svRect.left + sat * svRect.width()
        val py = svRect.top + (1f - value) * svRect.height()
        canvas.drawCircle(px, py, 7f * density, ringShadow)
        canvas.drawCircle(px, py, 7f * density, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                handleTouch(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleTouch(x: Float, y: Float) {
        if (y <= hueRect.bottom + gap / 2f) {
            hue = ((x / hueRect.width()).coerceIn(0f, 1f) * 359.9f)
        } else {
            sat = ((x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
            value = 1f - ((y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
        }
        buildShaders()
        invalidate()
        onColorChanged?.invoke(color)
    }
}
