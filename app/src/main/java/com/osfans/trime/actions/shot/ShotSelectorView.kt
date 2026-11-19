/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 冻结画面 + 手指拖框。
 *
 * 自己画位图（而不是用 ImageView）是为了让「屏幕坐标 → 位图像素」的换算只有一个地方：
 * 视图宽高与位图宽高不同时按等比缩小绘制，选框用同一套比例换回像素，取像素永远不会错位。
 */
@SuppressLint("ViewConstructor")
class ShotSelectorView(
    context: Context,
) : View(context) {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var bitmap: Bitmap? = null

    private val dimPaint = Paint().apply { color = 0xB0000000.toInt() }
    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            color = 0xFFFFD479.toInt()
        }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD479.toInt() }
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(13f)
        }

    private val sel = RectF()
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    /** 选区变化（像素坐标）。 */
    var onSelectionChanged: ((Rect) -> Unit)? = null

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        invalidate()
    }

    fun selectAll() {
        sel.set(0f, 0f, width.toFloat(), height.toFloat())
        dragging = false
        notifyChanged()
        invalidate()
    }

    fun clearSelection() {
        sel.setEmpty()
        notifyChanged()
        invalidate()
    }

    /** 选区换成位图像素坐标；空选区返回 null。 */
    fun selectionInBitmap(): Rect? {
        val bmp = bitmap ?: return null
        if (sel.width() < dp(8f) || sel.height() < dp(8f)) return null
        val sx = bmp.width / width.toFloat()
        val sy = bmp.height / height.toFloat()
        val r =
            Rect(
                (sel.left * sx).toInt().coerceIn(0, bmp.width - 1),
                (sel.top * sy).toInt().coerceIn(0, bmp.height - 1),
                (sel.right * sx).toInt().coerceIn(1, bmp.width),
                (sel.bottom * sy).toInt().coerceIn(1, bmp.height),
            )
        return if (r.width() < 2 || r.height() < 2) null else r
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), bitmapPaint)
        if (sel.isEmpty) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            return
        }
        // 选区外压暗：四条矩形，避免用 clip 造成边缘锯齿
        canvas.drawRect(0f, 0f, width.toFloat(), sel.top, dimPaint)
        canvas.drawRect(0f, sel.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, sel.top, sel.left, sel.bottom, dimPaint)
        canvas.drawRect(sel.right, sel.top, width.toFloat(), sel.bottom, dimPaint)
        canvas.drawRect(sel, borderPaint)
        val h = dp(3f)
        for (p in listOf(sel.left to sel.top, sel.right to sel.top, sel.left to sel.bottom, sel.right to sel.bottom)) {
            canvas.drawCircle(p.first, p.second, h * 2.5f, handlePaint)
        }
        val px = selectionInBitmap()
        if (px != null) {
            val text = "${px.width()} × ${px.height()}"
            val y = if (sel.bottom + dp(18f) < height) sel.bottom + dp(18f) else sel.bottom - dp(8f)
            canvas.drawText(text, sel.left + dp(2f), y, labelPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                dragging = true
                sel.set(startX, startY, startX, startY)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                sel.set(
                    minOf(startX, event.x),
                    minOf(startY, event.y),
                    maxOf(startX, event.x),
                    maxOf(startY, event.y),
                )
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    notifyChanged()
                    invalidate()
                }
            }
        }
        return true
    }

    private fun notifyChanged() {
        onSelectionChanged?.invoke(selectionInBitmap() ?: Rect())
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
