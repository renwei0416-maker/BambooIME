package com.osfans.trime.actions.ocr

import android.graphics.RectF

/**
 * 取景框几何的唯一来源。
 *
 * 相机遮罩、相册画布、裁切（[OcrWindow.cropFrame] / [OcrGalleryView.cropToFrame]）、
 * 提示文字的位置全部走这里，避免「框画在这、裁切按那」的不一致。
 */
object OcrFrame {
    /** 顶部让位：右上角倍数胶囊 / 相册「转 90°」按钮占掉的高度（px）。 */
    fun topReserved(density: Float): Float = 44f * density

    /**
     * @param single true = 单字模式的居中方格（边长 = 短边 × singleFrameRatio）
     * @param width 取景区宽度（px）
     * @param height 取景区高度（px）
     * @param topReservedPx 顶部要空出来的高度（px），整段框不会压到胶囊
     */
    fun rect(single: Boolean, width: Float, height: Float, topReservedPx: Float): RectF {
        if (width <= 0f || height <= 0f) return RectF()
        if (single) {
            val side = minOf(width, height) * OcrPrefs.singleFrameRatio
            val cx = width / 2f
            val cy = height / 2f
            return RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        }
        val fw = (width * (OcrPrefs.frameWidth / 100f)).coerceIn(1f, width)
        val fh = (height * (OcrPrefs.frameHeight / 100f)).coerceIn(1f, height)
        val left = (width - fw) / 2f
        val centered = (height - fh) / 2f + height * (OcrPrefs.frameOffset / 100f)
        // 不能顶到倍数胶囊所在的那一条；框太高时退回顶部对齐
        val minTop = topReservedPx.coerceAtLeast(0f).coerceAtMost(maxOf(0f, height - fh))
        val top = centered.coerceIn(minTop, maxOf(minTop, height - fh))
        return RectF(left, top, left + fw, top + fh)
    }
}
