/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.ocr

import android.content.Context
import com.osfans.trime.util.BambooLog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * 相册识字的「对齐视图」：把选中的照片按矩阵显示，上面盖一个和相机一样的取景框。
 *
 * 交互（用户 m09603 确认全要）：
 * - 单指拖动
 * - 双指缩放
 * - 双指旋转
 * - 外部「转 90°」按钮（[rotateBy]）
 *
 * 裁切用「渲染」而不是「逆矩阵算包围盒」：把同一套矩阵画到一块与取景框同尺寸的
 * 位图上，再按 2 倍超采样取，这样旋转任意角度都不会多带进旁边的字。
 */
class OcrGalleryView(context: Context) : FrameLayout(context) {
    private val imageView = ImageView(context)
    private var bitmap: Bitmap? = null
    private val matrix = Matrix()

    private var single = false

    /** [restoreMatrix] 存下的待应用变换，等视图量好尺寸再盖上去。 */
    private var pendingMatrix: FloatArray? = null
    var singleFrameRatio = 0.34f

    var maskColor = 0xB3000000.toInt()
    var borderColor = 0xFFE8E4D8.toInt()
    var accentColor = 0xFF6FAE8F.toInt()
    var strokePx = 3f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tmpRect = RectF()
    private val tmpArray = FloatArray(9)

    // 手势状态
    private var dragging = false
    private var transforming = false
    private var lastX = 0f
    private var lastY = 0f
    private var startDistance = 0f
    private var startAngle = 0f
    private var focusX = 0f
    private var focusY = 0f

    init {
        setWillNotDraw(false)
        imageView.scaleType = ImageView.ScaleType.MATRIX
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setBitmap(value: Bitmap) {
        bitmap = value
        imageView.setImageBitmap(value)
        fitCenter()
        invalidate()
    }

    /** 把当前缩放/旋转/平移导出成 9 个 float，供视图重建后还原。 */
    fun snapshotMatrix(): FloatArray = FloatArray(9).also { matrix.getValues(it) }

    /**
     * 还原 [snapshotMatrix] 导出的变换。
     *
     * 视图尺寸此时可能还是 0（刚重建），所以先存下来，等 [onSizeChanged] 摆好初始位置后再盖上去。
     */
    fun restoreMatrix(values: FloatArray?) {
        if (values == null || values.size != 9 || values.all { it == 0f }) return
        if (width > 0 && height > 0) {
            matrix.setValues(values)
            imageView.imageMatrix = matrix
            pendingMatrix = null
            invalidate()
        } else {
            pendingMatrix = values
        }
    }

    fun clear() {
        bitmap = null
        imageView.setImageDrawable(null)
        matrix.reset()
        invalidate()
    }

    fun hasImage(): Boolean = bitmap != null

    fun setSingleMode(value: Boolean) {
        single = value
        invalidate()
    }

    /** 图片放进视图并居中；进来就是「整张可见」，不够大再由用户自己捏。 */
    private fun fitCenter() {
        val bmp = bitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) {
            // 还没测量完，等 onSizeChanged 再摆
            return
        }
        val scale = min(vw / bmp.width, vh / bmp.height)
        val dx = (vw - bmp.width * scale) / 2f
        val dy = (vh - bmp.height * scale) / 2f
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)
        imageView.imageMatrix = matrix
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        BambooLog.ocr.d("ocr", "gallery canvas " + w + "x" + h + " (was " + oldw + "x" + oldh + ")")
        if (oldw == 0 && oldh == 0) {
            fitCenter()
            pendingMatrix?.let {
                matrix.setValues(it)
                imageView.imageMatrix = matrix
                pendingMatrix = null
                invalidate()
            }
        }
    }

    /** 「转 90°」：绕视图中心转，转完仍然落在视图里。 */
    fun rotateBy(degrees: Float) {
        if (bitmap == null) return
        matrix.postRotate(degrees, width / 2f, height / 2f)
        imageView.imageMatrix = matrix
        invalidate()
    }

    /** 取景框在视图里的矩形（和相机的归一化常量一致）。 */
    fun frameRect(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        return if (single) {
            val side = (h * singleFrameRatio).coerceAtMost(w)
            val left = (w - side) / 2f
            val top = (h - side) / 2f
            RectF(left, top, left + side, top + side)
        } else {
            OcrFrame.rect(false, w, h, OcrFrame.topReserved(resources.displayMetrics.density))
        }
    }

    /**
     * 把取景框里的内容渲染成一张位图（2 倍超采样，让引擎拿到比屏幕更细的像素）。
     */
    fun cropToFrame(): Bitmap? {
        val bmp = bitmap ?: return null
        val frame = frameRect()
        if (frame.width() < 8f || frame.height() < 8f) return null
        // 屏幕上图像是被缩小显示的（竖图缩得更狠）。若按屏幕像素裁，字号只剩几个像素，
        // 引擎必然认不出，所以这里换算回「原图像素」再渲染。
        val values = FloatArray(9)
        matrix.getValues(values)
        val viewScale = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]).coerceAtLeast(0.01f)
        val wanted = (1f / viewScale).coerceIn(1f, MAX_SOURCE_SCALE)
        val cap = MAX_OUTPUT_SIDE / maxOf(frame.width(), frame.height(), 1f)
        val outScale = min(wanted, maxOf(1f, cap))
        val widthOut = (frame.width() * outScale).toInt().coerceIn(16, MAX_OUTPUT_SIDE)
        val heightOut = (frame.height() * outScale).toInt().coerceIn(16, MAX_OUTPUT_SIDE)
        val out =
            runCatching { Bitmap.createBitmap(widthOut, heightOut, Bitmap.Config.ARGB_8888) }
                .getOrNull() ?: return null
        val canvas = Canvas(out)
        val render = Matrix(matrix)
        render.postTranslate(-frame.left, -frame.top)
        render.postScale(outScale, outScale)
        runCatching { canvas.drawBitmap(bmp, render, imagePaint) }
            .onFailure { return null }
        return out
    }

    /**
     * 遮罩与取景框必须画在照片**上面**。
     *
     * 照片是子 View（ImageView），在 onDraw 之后才绘制，所以画在 onDraw 里会被照片整块盖住。
     * 改到 dispatchDraw：先让 super 画完子 View，再叠加遮罩与框。
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val frame = frameRect()
        // 框外压暗：上下两块 + 左右两条（单字模式时框是正方形，四块都要）
        paint.color = maskColor
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, paint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, paint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, paint)

        // 描边
        paint.color = borderColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokePx
        canvas.drawRect(frame, paint)

        // 四角短线
        paint.color = accentColor
        paint.strokeWidth = strokePx * 2.5f
        val len = (frame.width().coerceAtMost(frame.height()) * 0.10f).coerceIn(12f, 40f)
        canvas.drawLine(frame.left, frame.top, frame.left + len, frame.top, paint)
        canvas.drawLine(frame.left, frame.top, frame.left, frame.top + len, paint)
        canvas.drawLine(frame.right, frame.top, frame.right - len, frame.top, paint)
        canvas.drawLine(frame.right, frame.top, frame.right, frame.top + len, paint)
        canvas.drawLine(frame.left, frame.bottom, frame.left + len, frame.bottom, paint)
        canvas.drawLine(frame.left, frame.bottom, frame.left, frame.bottom - len, paint)
        canvas.drawLine(frame.right, frame.bottom, frame.right - len, frame.bottom, paint)
        canvas.drawLine(frame.right, frame.bottom, frame.right, frame.bottom - len, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                transforming = false
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    dragging = false
                    transforming = true
                    startDistance = distanceOf(event)
                    startAngle = angleOf(event)
                    focusX = (event.getX(0) + event.getX(1)) / 2f
                    focusY = (event.getY(0) + event.getY(1)) / 2f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (transforming && event.pointerCount >= 2) {
                    val distance = distanceOf(event)
                    val angle = angleOf(event)
                    if (startDistance > 1f) {
                        matrix.postScale(
                            (distance / startDistance).coerceIn(0.5f, 2f),
                            (distance / startDistance).coerceIn(0.5f, 2f),
                            focusX,
                            focusY,
                        )
                    }
                    val deltaAngle = normalizeAngle(angle - startAngle)
                    if (abs(deltaAngle) > 0.01f) matrix.postRotate(deltaAngle, focusX, focusY)
                    startDistance = distance
                    startAngle = angle
                    imageView.imageMatrix = matrix
                    invalidate()
                } else if (dragging) {
                    matrix.postTranslate(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                    imageView.imageMatrix = matrix
                    invalidate()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起一指后继续拖动，不要跳一下
                if (event.pointerCount <= 2) {
                    transforming = false
                    dragging = true
                    val keep = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(keep)
                    lastY = event.getY(keep)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                transforming = false
            }
        }
        return true
    }

    private fun distanceOf(event: MotionEvent): Float =
        hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private fun angleOf(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    /** 把角度差收进 -180..180，避免跨越时抖一大圈。 */
    private fun normalizeAngle(angle: Float): Float {
        var value = angle
        while (value > 180f) value -= 360f
        while (value < -180f) value += 360f
        return value
    }

    companion object {
        private const val FRAME_LEFT = 0.05f
        private const val FRAME_TOP = 0.32f
        private const val FRAME_RIGHT = 0.95f
        private const val FRAME_BOTTOM = 0.68f
        /** 最多把原图放大到几倍（再大只是插值，模型用不上）。 */
    private const val MAX_SOURCE_SCALE = 6f

    /** 裁切结果的长边上限，防止一张图切出几十 MB 的位图。 */
    private const val MAX_OUTPUT_SIDE = 2048
    }
}
