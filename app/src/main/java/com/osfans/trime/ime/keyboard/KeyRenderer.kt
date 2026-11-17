/*
 * 按键「文字层」共享渲染器。
 *
 * 背景：主题样式编辑器的预览以前是自己抄了一套绘制逻辑（KeyPainter），
 * 结果 TOP/BOTTOM 基线、JUSTIFY 分栏、图标居中位置永远和真机差一点点，
 * 每修一处又冒出下一处。这里把 KeyView 里真正负责「文字/符号/提示/图标」
 * 的那部分原样搬出来，真机 KeyView 与设置页预览**调用同一份实现**。
 *
 * 只搬绘制，不碰触摸、输入、Rime、弹窗。颜色/字体/Drawable 由调用方通过
 * KeyRenderCtx 注入，因此草稿主题也能复用，不会污染正式主题的全局单例。
 */
package com.osfans.trime.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import com.osfans.trime.data.theme.ColorManager
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Typeface
import android.util.LruCache
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizePx
import com.osfans.trime.data.theme.model.TextKeyboard

/** 一次绘制所需的全部外部输入；颜色、字号缩放、字体缓存都由调用方提供。 */
class KeyRenderCtx(
    val context: Context,
    val canvas: Canvas,
    val key: Key,
    val keyboard: Keyboard,
    val width: Int,
    val height: Int,
    val paddingLeft: Int,
    val paddingTop: Int,
    val paddingRight: Int,
    val paddingBottom: Int,
    /** dp → px */
    val dp: (Float) -> Float,
    /** sp → px（含用户字体缩放） */
    val sp: (Float) -> Float,
    val textPaint: Paint,
    val symbolPaint: Paint,
    val richTextPaint: Paint,
    val iconCache: LruCache<String, IconicsDrawable>,
    val textColor: Int,
    val symbolColor: Int,
    val showSymbols: Boolean,
    val showHints: Boolean,
    val enterLabel: String,
    val schemaName: String,
    /** 来自 KeyboardView 的字号（sp） */
    val keyTextSizeSp: Float,
    val keyLongTextSizeSp: Float,
    val symbolTextSizeSp: Float,
    val hintTextSizeSp: Float,
    /** 颜色标识符 → ARGB（真机走 ColorManager，预览走草稿配色） */
    val colorResolver: (spec: String, fallback: Int) -> Int,
    /** 主题字体与对应粗体 */
    val baseTypeface: Typeface,
    val boldTypeface: Typeface,
)

object KeyRenderer {
    private const val SCHEMA_NAME = "schema_name"

    /** 绘制一个键的「文字层」：标签 → 顶部符号 → 底部提示。背景由调用方负责。 */
    /**
     * 按键「背景层」——与真机 [com.osfans.trime.ime.keyboard.KeyView.drawBackground]
     * 是同一份实现：真机 KeyView 与设置页预览都调这里。
     *
     * 只负责把 [Key.getBackgroundDrawable] 画到 bounds 上，并处理圆角/描边/阴影；
     * 颜色全部来自 Key 自己（即 ColorManager 当前指向的主题），调用方无需传色值。
     */
    fun renderBackground(
        canvas: Canvas,
        key: Key,
        keyboard: Keyboard,
        width: Int,
        height: Int,
        paddingLeft: Int,
        paddingTop: Int,
        paddingRight: Int,
        paddingBottom: Int,
        dp: (Float) -> Float,
    ) {
        val bg = key.getBackgroundDrawable() ?: return

        val uniform = key.roundCorner ?: keyboard.roundCorner
        fun r(value: Float?): Float = (value ?: uniform).takeIf { it > 0f } ?: 0f
        val tl = r(key.roundedCornerTopLeft)
        val tr = r(key.roundedCornerTopRight)
        val bl = r(key.roundedCornerBottomLeft)
        val br = r(key.roundedCornerBottomRight)

        val bounds = Rect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)

        drawShadow(canvas, key, keyboard, bounds, tl, tr, bl, br, uniform, dp)

        if (bg is GradientDrawable) {
            if (tl == tr && tl == bl && tl == br && tl == uniform) {
                uniform.takeIf { it > 0f }?.let { bg.cornerRadius = dp(it) }
            } else {
                bg.cornerRadii = floatArrayOf(
                    dp(tl), dp(tl), dp(tr), dp(tr), dp(br), dp(br), dp(bl), dp(bl),
                )
            }
            (key.keyBorder ?: keyboard.keyBorder).takeIf { it > 0 }?.let {
                bg.setStroke(dp(it.toFloat()).toInt(), key.keyBorderColorValue)
            }
        }

        bg.bounds = bounds
        bg.draw(canvas)
    }

    /** 阴影绘制（原 KeyView.drawKeyShadow 原样搬入）。 */
    private fun drawShadow(
        canvas: Canvas,
        key: Key,
        keyboard: Keyboard,
        bounds: Rect,
        tl: Float,
        tr: Float,
        bl: Float,
        br: Float,
        uniform: Float,
        dp: (Float) -> Float,
    ) {
        val radius = key.keyShadowRadius ?: keyboard.keyShadowRadius
        if (radius <= 0f) return
        val dir = key.keyShadowDirection ?: keyboard.keyShadowDirection ?: return
        if (dir.isEmpty()) return

        val color = ColorManager.getColor("key_shadow_color")
        val shadowDp = dp(radius)
        val (dx, dy) = Keyboard.shadowDirectionToOffset(dir, shadowDp)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.setShadowLayer(shadowDp, dx, dy, color)
            it.color = Color.argb(1, 0, 0, 0)
        }
        val shadowRect = RectF(bounds)

        if (tl == tr && tl == bl && tl == br && tl == uniform && uniform > 0f) {
            canvas.drawRoundRect(shadowRect, dp(uniform), dp(uniform), shadowPaint)
        } else {
            val radii = floatArrayOf(
                dp(tl), dp(tl), dp(tr), dp(tr), dp(br), dp(br), dp(bl), dp(bl),
            )
            val path = Path().apply { addRoundRect(shadowRect, radii, Path.Direction.CW) }
            canvas.drawPath(path, shadowPaint)
        }
    }

    fun renderText(c: KeyRenderCtx) {
        val key = c.key
        val segments = key.getLabelSegments()
            .let { segs ->
                if (segs.firstOrNull()?.text == "enter_labels") {
                    listOf(TextKeyboard.LabelSegment(text = c.enterLabel))
                } else {
                    segs
                }
            }
            .map { seg -> if (seg.text == SCHEMA_NAME) seg.copy(text = c.schemaName) else seg }

        if (segments.isNotEmpty()) {
            drawLabelSegments(c, segments)
        }
        if (key.symbolLabel.isNotEmpty()) {
            drawSymbolSegments(c, key.symbolLabel, TextKeyboard.VerticalAlign.TOP)
        }
        if (key.hint.isNotEmpty()) {
            drawSymbolSegments(c, key.hint, TextKeyboard.VerticalAlign.BOTTOM)
        }
    }

    private fun drawSymbolSegments(
        c: KeyRenderCtx,
        segments: List<TextKeyboard.LabelSegment>,
        defaultVerticalAlign: TextKeyboard.VerticalAlign,
    ) {
        val isTop = defaultVerticalAlign == TextKeyboard.VerticalAlign.TOP
        if (isTop && !c.showSymbols) return
        if (!isTop && !c.showHints) return

        val textSize = c.sp(
            if (isTop) {
                c.key.symbolTextSize.takeIf { it > 0f } ?: c.symbolTextSizeSp
            } else {
                c.key.hintTextSize.takeIf { it > 0f } ?: c.hintTextSizeSp
            },
        )
        val offsetX = if (isTop) c.key.keySymbolOffsetX else c.key.keyHintOffsetX
        val offsetY = if (isTop) c.key.keySymbolOffsetY else c.key.keyHintOffsetY

        drawSegmentsImpl(
            c, segments, textSize, textSize.toInt(), c.symbolColor,
            offsetX, offsetY, defaultVerticalAlign, c.symbolPaint,
        )
    }

    private fun drawLabelSegments(c: KeyRenderCtx, segments: List<TextKeyboard.LabelSegment>) {
        val plainText = segments.joinToString("") { it.text }
        val textSize = c.sp(
            c.key.keyTextSize.takeIf { it > 0 }
                ?: if (plainText.length > 1) c.keyLongTextSizeSp else c.keyTextSizeSp,
        )
        drawSegmentsImpl(
            c, segments, textSize, textSize.toInt(), c.textColor,
            c.key.keyTextOffsetX, c.key.keyTextOffsetY,
            TextKeyboard.VerticalAlign.CENTER, c.textPaint,
        )
    }

    private fun drawSegmentsImpl(
        c: KeyRenderCtx,
        segments: List<TextKeyboard.LabelSegment>,
        textSize: Float,
        iconSize: Int,
        textColor: Int,
        offsetX: Float,
        offsetY: Float,
        defaultVerticalAlign: TextKeyboard.VerticalAlign,
        paint: Paint,
    ) {
        if (segments.isEmpty()) return

        val lines = segmentsToKeyLines(segments)

        paint.apply {
            color = textColor
            this.textSize = textSize
            typeface = c.baseTypeface
            clearShadowLayer()
        }

        val positionMode = when (defaultVerticalAlign) {
            TextKeyboard.VerticalAlign.TOP -> PositionMode.TOP
            TextKeyboard.VerticalAlign.BOTTOM -> PositionMode.BOTTOM
            else -> PositionMode.CENTER
        }

        val inset = ((c.key.roundCorner ?: c.keyboard.roundCorner).takeIf { it > 0f }?.let { c.dp(it) } ?: 0f) * 1 / 2f

        val (centerX, linePositions) = calculateTextPosition(
            c, lines, offsetX, offsetY, positionMode, paint.fontMetrics, inset,
        )
        drawRichText(c, lines, centerX, linePositions, paint, offsetX, offsetY, defaultVerticalAlign, inset, textSize, iconSize, textColor)
    }

    private fun segmentsToKeyLines(segments: List<TextKeyboard.LabelSegment>): List<KeyLine> {
        val lines = mutableListOf<KeyLine>()
        var current = mutableListOf<KeySegment>()

        fun buildLine(): KeyLine {
            val maxScale = current.maxOfOrNull { it.scale ?: 1.0f } ?: 1.0f
            return KeyLine(current.joinToString("") { it.text }, current.toList(), maxScale)
        }

        for (seg in segments) {
            val parts = seg.text.split("\n")
            for (i in parts.indices) {
                if (i > 0) {
                    lines.add(buildLine())
                    current = mutableListOf()
                }
                if (parts[i].isEmpty()) continue
                current.add(
                    KeySegment(
                        text = parts[i],
                        colorKey = seg.color,
                        scale = seg.scale,
                        bold = seg.bold,
                        horizontalAlign = seg.align ?: TextKeyboard.Align.CENTER,
                        verticalAlign = seg.valign,
                    ),
                )
            }
        }
        lines.add(buildLine())
        return lines.ifEmpty { listOf(KeyLine("", emptyList())) }
    }

    private fun calculateTextPosition(
        c: KeyRenderCtx,
        lines: List<KeyLine>,
        offsetX: Float,
        offsetY: Float,
        mode: PositionMode,
        fm: Paint.FontMetrics,
        inset: Float = 0f,
    ): Pair<Float, List<Pair<Float, Float>>> {
        val baseLineHeight = fm.descent - fm.ascent
        val out = mutableListOf<Pair<Float, Float>>()

        when (mode) {
            PositionMode.TOP -> {
                var y = c.paddingTop - fm.top + c.sp(offsetY)
                lines.forEach { line ->
                    val h = baseLineHeight * line.maxScale
                    out.add(Pair(y, h))
                    y += h
                }
            }

            PositionMode.BOTTOM -> {
                var y = c.height - c.paddingBottom - fm.bottom + c.sp(offsetY)
                for (i in lines.indices.reversed()) {
                    val line = lines[i]
                    val h = baseLineHeight * line.maxScale
                    out.add(Pair(y, h))
                    y -= h
                }
                out.reverse()
            }

            PositionMode.CENTER -> {
                val total = lines.map { baseLineHeight * it.maxScale }.sum()
                val centerY = (c.height - c.paddingTop - c.paddingBottom) / 2f + c.paddingTop
                val adjust = -(fm.ascent + fm.descent) / 2f
                var y = centerY + adjust + c.sp(offsetY)
                y -= (total - baseLineHeight) / 2
                lines.forEach { line ->
                    val h = baseLineHeight * line.maxScale
                    out.add(Pair(y + (h - baseLineHeight) / 2, h))
                    y += h
                }
            }
        }

        val centerX = (c.width - c.paddingLeft - c.paddingRight) / 2f + c.paddingLeft + c.sp(offsetX)
        return Pair(centerX, out)
    }

    private fun drawRichText(
        c: KeyRenderCtx,
        lines: List<KeyLine>,
        x: Float,
        linePositions: List<Pair<Float, Float>>,
        paint: Paint,
        offsetX: Float,
        offsetY: Float,
        defaultVerticalAlign: TextKeyboard.VerticalAlign,
        inset: Float,
        baseTextSize: Float,
        iconSize: Int,
        textColor: Int,
    ) {
        paint.textAlign = Paint.Align.CENTER
        lines.forEachIndexed { index, line ->
            val (y, _) = linePositions[index]
            drawKeyLine(c, line, x, y, paint, offsetX, offsetY, defaultVerticalAlign, inset, baseTextSize, iconSize, textColor)
        }
    }

    private fun String.isCjkPunctuation(): Boolean {
        if (length != 1) return false
        val ch = this[0]
        return ch in '\u3000'..'\u303F' || ch in '\uFF00'..'\uFF0F' || ch in '\uFF1A'..'\uFF1F' || ch == '\u00B7'
    }

    private fun visualCenterCorrect(paint: Paint, text: String): Float {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val glyphCenter = paint.measureText(text) / 2f
        return glyphCenter - (bounds.width() / 2f + bounds.left.toFloat())
    }

    private fun drawKeyLine(
        c: KeyRenderCtx,
        line: KeyLine,
        x: Float,
        y: Float,
        basePaint: Paint,
        offsetX: Float,
        offsetY: Float,
        defaultVerticalAlign: TextKeyboard.VerticalAlign,
        inset: Float,
        baseTextSize: Float,
        iconSize: Int,
        textColor: Int,
    ) {
        if (line.segments.isEmpty()) {
            basePaint.textAlign = Paint.Align.CENTER
            val text = line.text
            val cx = if (text.isCjkPunctuation()) x + visualCenterCorrect(basePaint, text) else x
            c.canvas.drawText(text, cx, y, basePaint)
            return
        }

        val rp = c.richTextPaint
        val fm = basePaint.fontMetrics
        val baseAscent = fm.ascent
        val baseDescent = fm.descent
        val baseTypeface = basePaint.typeface ?: c.baseTypeface
        val boldTypeface = c.boldTypeface

        val leftGroup = mutableListOf<KeySegment>()
        val centerGroup = mutableListOf<KeySegment>()
        val rightGroup = mutableListOf<KeySegment>()
        val justifyGroup = mutableListOf<KeySegment>()
        for (seg in line.segments) {
            when (seg.horizontalAlign) {
                TextKeyboard.Align.LEFT -> leftGroup.add(seg)
                TextKeyboard.Align.RIGHT -> rightGroup.add(seg)
                TextKeyboard.Align.JUSTIFY -> justifyGroup.add(seg)
                else -> centerGroup.add(seg)
            }
        }

        fun isIcon(seg: KeySegment) = seg.text.startsWith("ic@")

        fun measure(group: List<KeySegment>): Float = group.sumOf { seg ->
            if (isIcon(seg)) {
                iconSize.toDouble()
            } else {
                rp.textSize = seg.scale?.let { baseTextSize * it } ?: baseTextSize
                rp.typeface = if (seg.bold) boldTypeface else baseTypeface
                rp.measureText(seg.text).toDouble()
            }
        }.toFloat()

        val leftWidth = measure(leftGroup)
        val centerWidth = measure(centerGroup)
        val rightWidth = measure(rightGroup)

        val paddedLeft = c.paddingLeft.toFloat() + c.sp(offsetX) + inset
        val paddedRight = (c.width - c.paddingRight).toFloat() + c.sp(offsetX) - inset

        val leftStart = paddedLeft
        val rightStart = paddedRight - rightWidth

        val centerCjkCorrect = if (centerGroup.size == 1) {
            val t = centerGroup[0].text
            if (t.isCjkPunctuation()) visualCenterCorrect(basePaint, t) else 0f
        } else {
            0f
        }
        val centerStart = paddedLeft + (paddedRight - paddedLeft - centerWidth) / 2f + centerCjkCorrect

        fun vAlign(seg: KeySegment) = seg.verticalAlign ?: defaultVerticalAlign

        fun drawGroup(group: List<KeySegment>, startX: Float) {
            var currentX = startX
            val justifyCount = group.count { vAlign(it) == TextKeyboard.VerticalAlign.JUSTIFY }
            var justifyIndex = 0
            group.forEach { seg ->
                val va = vAlign(seg)
                if (isIcon(seg)) {
                    val cmdName = seg.text.replace("ic@", "cmd_")
                    val centerY = when (va) {
                        TextKeyboard.VerticalAlign.TOP -> c.paddingTop + inset + iconSize / 2f + c.sp(offsetY)
                        TextKeyboard.VerticalAlign.BOTTOM -> c.height - c.paddingBottom - inset - iconSize / 2f + c.sp(offsetY)
                        TextKeyboard.VerticalAlign.JUSTIFY -> {
                            val top = c.paddingTop + inset
                            val bottom = c.height - c.paddingBottom - inset
                            val portion = (bottom - top).coerceAtLeast(0f) / justifyCount
                            top + portion * justifyIndex + portion / 2f + c.sp(offsetY)
                        }
                        else -> (c.height - c.paddingTop - c.paddingBottom) / 2f + c.paddingTop + c.sp(offsetY)
                    }
                    if (va == TextKeyboard.VerticalAlign.JUSTIFY) justifyIndex++
                    drawIconAt(c, cmdName, iconSize, textColor, currentX + iconSize / 2f, centerY)
                    currentX += iconSize
                } else {
                    rp.color = basePaint.color
                    rp.textSize = baseTextSize
                    rp.typeface = basePaint.typeface
                    rp.textAlign = Paint.Align.LEFT
                    seg.colorKey?.let { rp.color = c.colorResolver(it, basePaint.color) }

                    val scale = seg.scale ?: 1f
                    rp.textSize = baseTextSize * scale
                    val sa = baseAscent * scale
                    val sd = baseDescent * scale

                    val adjustedY = when (seg.verticalAlign) {
                        null -> when (defaultVerticalAlign) {
                            TextKeyboard.VerticalAlign.TOP -> y + inset + fm.top - sa
                            TextKeyboard.VerticalAlign.BOTTOM -> y - inset + fm.bottom - sd
                            TextKeyboard.VerticalAlign.JUSTIFY -> {
                                val top = c.paddingTop + inset
                                val bottom = c.height - c.paddingBottom - inset
                                val portion = (bottom - top).coerceAtLeast(0f) / justifyCount
                                val h = sd - sa
                                val centerY = top + portion * justifyIndex + (portion - h) / 2f
                                centerY - (sa + sd) / 2f + c.sp(offsetY)
                            }
                            else -> y + (baseAscent + baseDescent - sa - sd) / 2
                        }
                        TextKeyboard.VerticalAlign.TOP -> c.paddingTop + inset - sa + c.sp(offsetY)
                        TextKeyboard.VerticalAlign.BOTTOM -> c.height - c.paddingBottom - inset - sd + c.sp(offsetY)
                        TextKeyboard.VerticalAlign.JUSTIFY -> {
                            val top = c.paddingTop + inset
                            val bottom = c.height - c.paddingBottom - inset
                            val portion = (bottom - top).coerceAtLeast(0f) / justifyCount
                            val h = sd - sa
                            val centerY = top + portion * justifyIndex + (portion - h) / 2f
                            centerY - (sa + sd) / 2f + c.sp(offsetY)
                        }
                        else -> (c.height - c.paddingTop - c.paddingBottom) / 2f + c.paddingTop - (sa + sd) / 2f + c.sp(offsetY)
                    }

                    if (seg.verticalAlign == TextKeyboard.VerticalAlign.JUSTIFY ||
                        (seg.verticalAlign == null && defaultVerticalAlign == TextKeyboard.VerticalAlign.JUSTIFY)
                    ) {
                        justifyIndex++
                    }

                    if (seg.bold) rp.typeface = boldTypeface

                    c.canvas.drawText(seg.text, currentX, adjustedY, rp)
                    currentX += rp.measureText(seg.text)
                }
            }
        }

        val spareStart = paddedLeft + leftWidth
        val spareEnd = paddedRight - rightWidth
        if (justifyGroup.isNotEmpty()) {
            val portion = (spareEnd - spareStart) / justifyGroup.size
            justifyGroup.forEachIndexed { i, seg ->
                val w = if (isIcon(seg)) {
                    iconSize.toFloat()
                } else {
                    rp.textSize = seg.scale?.let { baseTextSize * it } ?: baseTextSize
                    rp.typeface = if (seg.bold) boldTypeface else baseTypeface
                    rp.measureText(seg.text)
                }
                drawGroup(listOf(seg), spareStart + portion * i + (portion - w) / 2f)
            }
        }

        drawGroup(leftGroup, leftStart)
        drawGroup(centerGroup, centerStart)
        drawGroup(rightGroup, rightStart)
    }

    private fun drawIconAt(
        c: KeyRenderCtx,
        cmdName: String,
        size: Int,
        color: Int,
        centerX: Float,
        centerY: Float,
    ) {
        val half = size / 2
        val icon = c.iconCache[cmdName] ?: IconicsDrawable(c.context, cmdName).apply {
            respectFontBounds = false
            sizePx = size
            iconOffsetYPx = 2
        }.also { c.iconCache.put(cmdName, it) }

        icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        icon.setBounds(
            (centerX - half).toInt(),
            (centerY - half).toInt(),
            (centerX + half).toInt(),
            (centerY + half).toInt(),
        )
        icon.draw(c.canvas)
    }
}

private data class KeySegment(
    val text: String,
    val colorKey: String?,
    val scale: Float?,
    val bold: Boolean,
    val horizontalAlign: TextKeyboard.Align = TextKeyboard.Align.CENTER,
    val verticalAlign: TextKeyboard.VerticalAlign? = null,
)

private data class KeyLine(
    val text: String,
    val segments: List<KeySegment>,
    val maxScale: Float = 1.0f,
)

private enum class PositionMode { TOP, CENTER, BOTTOM }
