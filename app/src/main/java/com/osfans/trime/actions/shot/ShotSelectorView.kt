package com.osfans.trime.actions.shot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.osfans.trime.util.BambooLog

/**
 * 截图选区：四种手势作用在同一块画布上。
 *
 *  - 四角 24dp 热区：对角缩放
 *  - 四边中点 24dp 热区：单边缩放
 *  - 框内：整体平移
 *  - 框外：重新拉一个框
 *
 * 双击框内＝全屏/还原。尺寸标签只在交互时出现，停手 1.5 秒后淡出。
 */
class ShotSelectorView(context: Context) : View(context) {

    var onSelectionChanged: ((Rect) -> Unit)? = null

    private var bmp: Bitmap? = null
    private val sel = RectF()
    private var hasSel = false
    private var showLabel = false

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dimPaint = Paint().apply { color = 0xB0000000.toInt() }
    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2)
            color = 0xFFFFD479.toInt()
        }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD479.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(13)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }

    private val handleR = dp(11)
    private val minSize = dp(24)

    private val modeNone = 0
    private val modeNew = 1
    private val modePan = 2
    private val modeCorner = 3
    private val modeEdge = 4
    private var mode = modeNone
    private var corner = -1
    private var edge = -1

    private var downX = 0f
    private var downY = 0f
    private val downSel = RectF()
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val hideLabelRunnable = Runnable {
        showLabel = false
        invalidate()
    }

    fun setBitmap(bitmap: Bitmap) {
        bmp = bitmap
        if (!hasSel) {
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            val bw = w * 0.8f
            val bh = h * 0.22f
            sel.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
            hasSel = true
        }
        notifyChanged()
        invalidate()
    }

    /** 恢复成进入时的默认框：居中 80% 宽、22% 高。 */
    fun setDefault() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val sw = w * 0.8f
        val sh = h * 0.22f
        val left = (w - sw) / 2f
        val top = (h - sh) / 2f
        sel.set(left, top, left + sw, top + sh)
        showLabelForAWhile()
        notifyChanged()
        invalidate()
    }

    fun selectAll() {
        sel.set(0f, 0f, width.toFloat(), height.toFloat())
        hasSel = true
        notifyChanged()
        invalidate()
    }

    fun clearSelection() {
        hasSel = false
        invalidate()
    }

    fun isFullSelection(): Boolean =
        hasSel && sel.left <= dp(1) && sel.top <= dp(1) && sel.right >= width - dp(1) && sel.bottom >= height - dp(1)

    /** 把屏幕坐标的选区换算成位图坐标；太小视为没选。 */
    fun selectionInBitmap(): Rect? {
        val b = bmp ?: return null
        if (!hasSel || width <= 0 || height <= 0) return null
        val r = RectF(sel)
        r.left = r.left.coerceIn(0f, width.toFloat())
        r.right = r.right.coerceIn(0f, width.toFloat())
        r.top = r.top.coerceIn(0f, height.toFloat())
        r.bottom = r.bottom.coerceIn(0f, height.toFloat())
        if (r.width() < dp(8) || r.height() < dp(8)) return null
        val sx = b.width.toFloat() / width
        val sy = b.height.toFloat() / height
        val out =
            Rect(
                (r.left * sx).toInt().coerceIn(0, b.width - 1),
                (r.top * sy).toInt().coerceIn(0, b.height - 1),
                (r.right * sx).toInt().coerceIn(1, b.width),
                (r.bottom * sy).toInt().coerceIn(1, b.height),
            )
        if (out.width() < 4 || out.height() < 4) return null
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bmp ?: return
        canvas.drawBitmap(b, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)
        if (!hasSel) return

        canvas.drawRect(0f, 0f, width.toFloat(), sel.top, dimPaint)
        canvas.drawRect(0f, sel.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, sel.top, sel.left, sel.bottom, dimPaint)
        canvas.drawRect(sel.right, sel.top, width.toFloat(), sel.bottom, dimPaint)

        canvas.drawRoundRect(sel, dp(2), dp(2), borderPaint)
        val c = handleR
        canvas.drawCircle(sel.left, sel.top, c, handlePaint)
        canvas.drawCircle(sel.right, sel.top, c, handlePaint)
        canvas.drawCircle(sel.right, sel.bottom, c, handlePaint)
        canvas.drawCircle(sel.left, sel.bottom, c, handlePaint)

        if (!showLabel) return
        val sx = (b.width.toFloat() / width).coerceAtLeast(0.01f)
        val sy = (b.height.toFloat() / height).coerceAtLeast(0.01f)
        val text = ((sel.width() * sx).toInt()).toString() + " × " + ((sel.height() * sy).toInt()).toString()
        val tw = labelPaint.measureText(text)
        val pad = dp(6)
        val th = labelPaint.textSize + pad
        var lx = sel.left
        var ly = sel.bottom + dp(6)
        if (ly + th > height) ly = sel.bottom - th - dp(6)
        if (lx + tw + pad * 2 > width) lx = width - tw - pad * 2
        val bg = RectF(lx, ly, lx + tw + pad * 2, ly + th)
        canvas.drawRoundRect(bg, dp(4), dp(4), labelBgPaint)
        canvas.drawText(text, lx + pad, ly + th - pad, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downSel.set(sel)
                mode = modeNone
                corner = -1
                edge = -1

                val now = SystemClock.uptimeMillis()
                val isDoubleTap =
                    now - lastTapAt < 320 &&
                        Math.abs(event.x - lastTapX) < dp(40) &&
                        Math.abs(event.y - lastTapY) < dp(40) &&
                        hasSel && sel.contains(event.x, event.y)
                lastTapAt = now
                lastTapX = event.x
                lastTapY = event.y
                if (isDoubleTap) {
                    if (isFullSelection()) {
                        val w = width.toFloat()
                        val h = height.toFloat()
                        val bw = w * 0.8f
                        val bh = h * 0.22f
                        sel.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
                    } else {
                        sel.set(0f, 0f, width.toFloat(), height.toFloat())
                    }
                    hasSel = true
                    mode = modeNone
                    notifyChanged()
                    showLabelForAWhile()
                    invalidate()
                    return true
                }

                if (hasSel) {
                    // 命中半径随选框一起缩：框到最小时，固定的 24dp 角点 + 26dp 边条会把整个框吃满，
                    // 「框内平移」永远轮不到（用户实测：缩到最小后横竖都拖不动）。这里改成按短边折算，
                    // 并且已经到最小尺寸的那一轴不再接受缩放命中，直接把中间让给平移。
                    val c = minOf(dp(24), sel.width() / 4f, sel.height() / 4f).coerceAtLeast(dp(8))
                    val atMinW = sel.width() <= minSize + dp(2)
                    val atMinH = sel.height() <= minSize + dp(2)
                    val mx = (sel.left + sel.right) / 2f
                    val my = (sel.top + sel.bottom) / 2f
                    when {
                        Math.abs(event.x - sel.left) < c && Math.abs(event.y - sel.top) < c -> {
                            mode = modeCorner; corner = 0
                        }
                        Math.abs(event.x - sel.right) < c && Math.abs(event.y - sel.top) < c -> {
                            mode = modeCorner; corner = 1
                        }
                        Math.abs(event.x - sel.right) < c && Math.abs(event.y - sel.bottom) < c -> {
                            mode = modeCorner; corner = 2
                        }
                        Math.abs(event.x - sel.left) < c && Math.abs(event.y - sel.bottom) < c -> {
                            mode = modeCorner; corner = 3
                        }
                        !atMinW && Math.abs(event.x - sel.left) < c && Math.abs(event.y - my) < c -> {
                            mode = modeEdge; edge = 0
                        }
                        !atMinH && Math.abs(event.y - sel.top) < c && Math.abs(event.x - mx) < c -> {
                            mode = modeEdge; edge = 1
                        }
                        !atMinW && Math.abs(event.x - sel.right) < c && Math.abs(event.y - my) < c -> {
                            mode = modeEdge; edge = 2
                        }
                        !atMinH && Math.abs(event.y - sel.bottom) < c && Math.abs(event.x - mx) < c -> {
                            mode = modeEdge; edge = 3
                        }
                        sel.contains(event.x, event.y) -> {
                            mode = modePan
                        }
                    }
                }
                if (mode == modeNone) {
                    mode = modeNew
                    hasSel = true
                    sel.set(event.x, event.y, event.x, event.y)
                }
                showLabelForAWhile()
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (!hasSel) return true
                when (mode) {
                    modeNew -> {
                        sel.left = Math.min(downX, event.x)
                        sel.right = Math.max(downX, event.x)
                        sel.top = Math.min(downY, event.y)
                        sel.bottom = Math.max(downY, event.y)
                        if (sel.width() < minSize) {
                            if (event.x >= downX) sel.right = sel.left + minSize else sel.left = sel.right - minSize
                        }
                        if (sel.height() < minSize) {
                            if (event.y >= downY) sel.bottom = sel.top + minSize else sel.top = sel.bottom - minSize
                        }
                    }
                    modePan -> {
                        var dx = event.x - downX
                        var dy = event.y - downY
                        dx = dx.coerceIn(-downSel.left, width - downSel.right)
                        dy = dy.coerceIn(-downSel.top, height - downSel.bottom)
                        sel.set(downSel.left + dx, downSel.top + dy, downSel.right + dx, downSel.bottom + dy)
                    }
                    modeCorner -> {
                        when (corner) {
                            0 -> { sel.left = Math.min(event.x, sel.right - minSize); sel.top = Math.min(event.y, sel.bottom - minSize) }
                            1 -> { sel.right = Math.max(event.x, sel.left + minSize); sel.top = Math.min(event.y, sel.bottom - minSize) }
                            2 -> { sel.right = Math.max(event.x, sel.left + minSize); sel.bottom = Math.max(event.y, sel.top + minSize) }
                            else -> { sel.left = Math.min(event.x, sel.right - minSize); sel.bottom = Math.max(event.y, sel.top + minSize) }
                        }
                    }
                    modeEdge -> {
                        when (edge) {
                            0 -> sel.left = Math.min(event.x, sel.right - minSize)
                            1 -> sel.top = Math.min(event.y, sel.bottom - minSize)
                            2 -> sel.right = Math.max(event.x, sel.left + minSize)
                            else -> sel.bottom = Math.max(event.y, sel.top + minSize)
                        }
                    }
                }
                clampToScreen()
                showLabelForAWhile()
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (hasSel) {
                    clampToScreen()
                    if (sel.width() < dp(8) || sel.height() < dp(8)) {
                        hasSel = false
                    } else {
                        notifyChanged()
                    }
                    showLabelForAWhile()
                }
                mode = modeNone
                invalidate()
            }
        }
        return true
    }

    private fun clampToScreen() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (sel.left < 0f) sel.offset(-sel.left, 0f)
        if (sel.top < 0f) sel.offset(0f, -sel.top)
        if (sel.right > w) sel.offset(w - sel.right, 0f)
        if (sel.bottom > h) sel.offset(0f, h - sel.bottom)
    }

    private fun showLabelForAWhile() {
        showLabel = true
        removeCallbacks(hideLabelRunnable)
        postDelayed(hideLabelRunnable, 1500L)
    }

    private fun notifyChanged() {
        val r = selectionInBitmap() ?: return
        BambooLog.shot.d("sel", "bitmap rect=" + r.toShortString())
        onSelectionChanged?.invoke(r)
    }

    private fun dp(v: Int): Float = v * resources.displayMetrics.density

    private fun sp(v: Int): Float = v * resources.displayMetrics.scaledDensity
}
