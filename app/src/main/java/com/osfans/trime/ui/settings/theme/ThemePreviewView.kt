/*
 * SPDX-FileCopyrightText: 2026 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 主题预览：用真实的 Keyboard（纯数据类，不依赖输入法服务）算几何与文字，
 * 背景层与文字层都复用真机的 [com.osfans.trime.ime.keyboard.KeyRenderer]，保证两边永远一致。
 * 颜色/图片/阴影/描边全部来自草稿 [com.osfans.trime.data.theme.ThemeResourceResolver]
 * （与真机 ColorManager 同一套取色/取图回退链，但绑定草稿主题，不污染全局单例）。
 *
 * 交互与真机同形：按下变色、移出取消、抬起选中该键（供「按键」页签编辑）、长按显示
 * key.popup 的只读气泡。**不接任何输入逻辑**。
 */
package com.osfans.trime.ui.settings.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import com.mikepenz.iconics.IconicsDrawable
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemeResourceResolver
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.KeyRenderCtx
import com.osfans.trime.ime.keyboard.KeyRenderer
import com.osfans.trime.ime.keyboard.Keyboard

class ThemePreviewView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { FIT, CROP }

    var mode: Mode = Mode.FIT
        set(value) {
            field = value
            invalidate()
        }

    /** 点选某个键后回调（供「按键」页签编辑）。 */
    var onKeyTapped: ((KeyPath) -> Unit)? = null

    /** 当前选中的键（高亮一圈）。 */
    var selectedKey: KeyPath? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var draft: ThemeDraft? = null
    private var style: GeneralStyle = ThemeManager.activeTheme.generalStyle
    private var schemeId: String = ThemeManager.activeTheme.colorSchemes.firstOrNull()?.id ?: "default"
    private var keyboardName: String = "default"

    private var keyboard: Keyboard? = null
    /** 与 keyboard.keys 一一对应的 (行, 列) 定位。 */
    private var keyPaths: List<KeyPath> = emptyList()

    /** 草稿主题取色/取图器（与 ColorManager 同源，但绑定草稿 theme+scheme）。 */
    private var resolver: ThemeResourceResolver? = null

    private val density = resources.displayMetrics.density
    private val referenceWidth: Int
        get() {
            val pad = ThemeManager.activeTheme.generalStyle.keyboardPadding
            return (resources.displayMetrics.widthPixels - 2 * pad).coerceAtLeast(320)
        }

    fun setDraft(draft: ThemeDraft) {
        this.draft = draft
        this.style = draft.style
        this.schemeId = draft.schemeId
        this.keyboardName = draft.keyboardName
        rebuild()
    }

    fun setKeyboard(name: String) {
        if (keyboardName == name) return
        keyboardName = name
        draft?.keyboardName = name
        rebuild()
    }

    fun setScheme(id: String) {
        if (schemeId == id) return
        schemeId = id
        draft?.schemeId = id
        // resolver 缓存了配色，切换配色需重建
        rebuild()
    }

    fun refresh() = invalidate()

    /** 自证用：当前解析出的关键颜色，显示在顶栏方便对照真机。 */
    fun colorSummary(): String {
        val s = currentScheme()
        val r = resolver
        val back = r?.getColorOr("back_color", 0) ?: 0
        val text = r?.getColorOr("key_text_color", 0) ?: 0
        val cols = s?.colors?.size ?: 0
        val fb = ThemeManager.activeTheme.fallbackColors.size
        return "scheme=${s?.id ?: "无"} cols=$cols fb=$fb back=${hex(back)} text=${hex(text)}"
    }

    fun firstLetterKeyIndex(): Int? {
        val keys = keyboard?.keys ?: return null
        keys.forEachIndexed { index, key ->
            val text = runCatching { key.getLabelSegments().joinToString("") { it.text } }.getOrNull()
            if (text != null && text.length == 1 && text[0].isLetter()) return index
        }
        return null
    }

    private fun currentScheme(): ColorScheme? {
        val d = draft ?: return ThemeManager.activeTheme.colorSchemes.firstOrNull()
        return d.schemes.firstOrNull { it.id == d.schemeId } ?: d.schemes.firstOrNull()
    }

    private fun draftTheme(): Theme {
        val base = ThemeManager.activeTheme
        val schemes = draft?.schemes?.toList() ?: base.colorSchemes
        return base.copy(generalStyle = style, colorSchemes = schemes)
    }

    private fun rawKeyboard(): TextKeyboard? {
        ThemeManager.getKeyboard(keyboardName)?.let { return it }
        ThemeManager.getKeyboard("default")?.let { return it }
        return ThemeManager.activeTheme.presetKeyboards.values.firstOrNull()
    }

    private fun rebuild() {
        val theme = draftTheme()
        val raw = rawKeyboard()
        val selfConfig = draft?.patchedKeyboard(raw ?: return) ?: return
        val scheme = currentScheme() ?: ColorScheme("default", emptyMap())
        lastTheme = theme
        resolver = ThemeResourceResolver(theme, scheme)
        val kb = Keyboard(context, theme, selfConfig, resolver!!)
        kb.recalculateLayout(referenceWidth)
        keyboard = kb
        keyPaths = buildKeyPaths(selfConfig)
        invalidate()
    }

    /** rows.flatMap{keys}.filter{!spacer} 与 Keyboard.keys 顺序一致（Keyboard.kt:367/508）。 */
    private fun buildKeyPaths(tk: TextKeyboard): List<KeyPath> {
        val out = ArrayList<KeyPath>()
        tk.rows.forEachIndexed { r, row ->
            row.keys.forEachIndexed { c, key ->
                if (!key.spacer) out.add(KeyPath(keyboardName, r, c))
            }
        }
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (keyboard == null) rebuild()
    }

    // ------------------------------------------------------------------ 触摸

    private var pressedIndex: Int? = null
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var popupIndex: Int? = null
    private val longPressRunnable = Runnable {
        val index = pressedIndex ?: return@Runnable
        longPressed = true
        popupIndex = index
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val kb = keyboard ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = hitIndex(event.x, event.y) ?: return false
                pressedIndex = index
                downX = event.x
                downY = event.y
                longPressed = false
                popupIndex = null
                postDelayed(longPressRunnable, 400L)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val moved = kotlin.math.hypot(event.x - downX, event.y - downY)
                if (moved > 12f * density && !longPressed) {
                    removeCallbacks(longPressRunnable)
                }
                val index = hitIndex(event.x, event.y)
                if (index != pressedIndex) {
                    pressedIndex = index
                    popupIndex = null
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                val upIndex = pressedIndex
                val isLong = longPressed
                pressedIndex = null
                popupIndex = null
                if (!isLong && event.actionMasked == MotionEvent.ACTION_UP && upIndex != null) {
                    val path = keyPaths.getOrNull(upIndex)
                    if (path != null) {
                        selectedKey = path
                        onKeyTapped?.invoke(path)
                    }
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ---------------------------------------------------------------- 绘制状态

    private var drawScale = 1f
    private var drawTx = 0f
    private var drawTy = 0f

    /** 宽度定标下整块内容（编码行+候选行+键盘+底部留白）的真实像素高度，供外层撑高预览框。 */
    var neededHeightPx = 0f
        private set

    /** 供外层把预览框撑到内容真实高度（dp）；尚未绘制时返回 0。 */
    fun neededHeightDp(): Int =
        if (neededHeightPx <= 0f) 0 else (neededHeightPx / resources.displayMetrics.density).toInt()

    private val hintPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFF7B72.toInt()
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }

    private val textPaint = Paint().apply { isAntiAlias = true }
    private val symbolPaint = Paint().apply { isAntiAlias = true }
    private val richTextPaint = Paint().apply { isAntiAlias = true }

    private val selectPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = 0xFFE8C87A.toInt()
    }

    private val popupTextPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFE8EAED.toInt()
        textSize = 13f * density
    }

    private val popupBgPaint = Paint().apply { isAntiAlias = true }

    private val errorPaint = Paint().apply {
        isAntiAlias = true
        textSize = 11f * density
    }

    // ---- 候选栏 / 编码行：尺寸与颜色全部来自 Theme，只有示意文本是常量 ----
    private val preeditPaint = Paint().apply { isAntiAlias = true }
    private val candTextPaint = Paint().apply { isAntiAlias = true }
    private val candCommentPaint = Paint().apply { isAntiAlias = true }
    private val candLabelPaint = Paint().apply { isAntiAlias = true }
    private val candBgPaint = Paint().apply { isAntiAlias = true }

    /** 最近一次 rebuild 用的 Theme（含 preedit 等模块），供各层取尺寸/颜色。 */
    private var lastTheme: Theme? = null

    /** 按键区在预览画布里的 y 偏移（theme px）= 候选栏高 + 编码行高。 */
    private var contentTop = 0f

    /** 取色：解析失败就用兜底，绝不让预览因缺键而中断。 */
    private fun colorOr(key: String, fallback: Int): Int =
        runCatching { resolver?.getColor(key) ?: fallback }.getOrDefault(fallback)

    /** 候选栏高度：与真机 `InputBarDelegate.themedHeight` 同源（candidate_view_height + comment_height，dp）。 */
    private fun candidateBarHeight(): Float =
        dp((style.candidateViewHeight + style.commentHeight).toFloat())

    /** 编码行高度：用真实字体度量算（includeFontPadding 默认开启 → top..bottom）。 */
    private fun preeditHeight(): Float {
        val t = lastTheme ?: ThemeManager.activeTheme
        preeditPaint.textSize = sp(t.preedit.foreground.fontSize)
        val fm = preeditPaint.fontMetrics
        return (fm.bottom - fm.top).coerceAtLeast(1f)
    }

    private val iconCache = LruCache<String, IconicsDrawable>(16)

    private var boldBase: Typeface? = null
    private var boldCache: Typeface? = null

    /** 屏幕坐标 → 内容坐标 → 命中的键下标（spacer 已不在 keys 内）。 */
    private fun hitIndex(x: Float, y: Float): Int? {
        val kb = keyboard ?: return null
        if (drawScale <= 0f) return null
        val cx = (x - drawTx) / drawScale
        // 画布里按键区在候选栏 + 编码行之下
        val cy = (y - drawTy) / drawScale - contentTop
        kb.keys.forEachIndexed { index, key ->
            val left = (key.x - key.extraWidthLeft).toFloat()
            val right = left + key.width + key.extraWidthLeft + key.extraWidthRight
            val top = key.y.toFloat()
            val bottom = top + key.height
            if (cx >= left && cx <= right && cy >= top && cy <= bottom) return index
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 任何绘制异常都画到屏幕上，避免「预览一片空白但不知道原因」。
        runCatching { drawPreview(canvas) }.onFailure { e ->
            drawError(canvas, e)
        }
    }

    private fun drawError(canvas: Canvas, e: Throwable) {
        val msg = "预览绘制失败：" + (e.message ?: e::class.java.simpleName)
        errorPaint.color = 0xFFFF6B6B.toInt()
        canvas.drawText(msg.take(60), 8f * density, 18f * density, errorPaint)
        var y = 34f
        e.stackTrace.take(4).forEach { el ->
            canvas.drawText(
                (el.className.substringAfterLast('.') + "." + el.methodName + ":" + el.lineNumber).take(52),
                8f * density,
                y * density,
                errorPaint,
            )
            y += 14f
        }
    }

    private fun drawPreview(canvas: Canvas) {
        val kb = keyboard ?: return
        val resolver = this.resolver ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 键盘整体背景：与真机 InputView.keyboardBackground 同源
        val kbBg = resolver.getDrawable("keyboard_background")
        if (kbBg != null) {
            kbBg.setBounds(0, 0, width, height)
            kbBg.draw(canvas)
        } else {
            canvas.drawColor(resolver.getColor("keyboard_back_color"))
        }

        if (kb.keys.isEmpty() || kb.height <= 0) {
            canvas.drawText("没有可预览的键盘（$keyboardName）", w / 2f, h / 2f, hintPaint)
            return
        }

        val kbWidth = kb.minWidth.coerceAtLeast(1).toFloat()
        val kbHeight = kb.height.coerceAtLeast(1).toFloat()

        // 预览画布 = 候选栏 + 编码行 + 键盘 + 键盘下留白，四段高度全部来自 Theme
        val candH = candidateBarHeight()
        val preeditH = preeditHeight()
        val bottomPad = dp(style.keyboardPaddingBottom.toFloat())
        contentTop = candH + preeditH
        val fullHeight = contentTop + kbHeight + bottomPad

        // 与真机一致：宽度恒定＝预览宽，高度只随内容变，绝不反向压缩宽度/字号。
        val scale = when (mode) {
            Mode.FIT -> w / kbWidth
            Mode.CROP -> 1f
        }
        val scaledHeight = fullHeight * scale
        neededHeightPx = scaledHeight
        drawScale = scale
        drawTx = (w - kbWidth * scale) / 2f
        // 内容比框矮则垂直居中；比框高则顶对齐，超出部分由外层裁剪
        drawTy = if (scaledHeight < h) (h - scaledHeight) / 2f else 0f

        val fonts = style.fonts
        // 与真机同源：enter_labels 由 EnterKeyDisplayDelegate 选，预览按 default 取（可能是 ic@xxx 图标）
        val enterLabelText = style.enterLabel.default.takeIf { it.isNotBlank() } ?: "Enter"
        val typeface = FontManager.getTypeface()
        if (boldBase !== typeface) {
            boldBase = typeface
            boldCache = Typeface.create(typeface, Typeface.BOLD)
        }

        canvas.save()
        canvas.translate(drawTx, drawTy)
        canvas.scale(scale, scale)

        // 与真机同序：编码行在上、候选行在下（PreeditUi 在候选栏之上）
        drawPreeditRow(canvas, kbWidth, 0f, preeditH)
        drawCandidateBar(canvas, kbWidth, preeditH, candH)

        canvas.save()
        canvas.translate(0f, contentTop)

        kb.keys.forEachIndexed { index, key ->
            key.setPressed(pressedIndex == index)

            // 与真机 KeyboardView 完全一致的 padding（含阴影留白）
            val (sLeft, sTop, sRight, sBottom) = shadowExtents(key, kb)
            val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
            val viewWidth = totalWidth + sLeft + sRight
            val viewHeight = key.height + sTop + sBottom

            val padL = (kb.horizontalGap * kb.horizontalGapScale / 2f).toInt() + key.extraWidthLeft + sLeft
            val padT = if (key.edgeFlags and Keyboard.EDGE_TOP != 0) {
                maxOf(kb.keyboardPaddingTop, kb.verticalGap / 2) + sTop
            } else {
                kb.verticalGap / 2
            }
            val padR = (kb.horizontalGap * kb.horizontalGapScale / 2f).toInt() + key.extraWidthRight + sRight
            val padB = if (key.edgeFlags and Keyboard.EDGE_BOTTOM == 0) kb.verticalGap / 2 else sBottom

            canvas.save()
            canvas.translate((key.x - key.extraWidthLeft - sLeft).toFloat(), (key.y - sTop).toFloat())

            KeyRenderer.renderBackground(
                canvas,
                key,
                kb,
                viewWidth,
                viewHeight,
                padL,
                padT,
                padR,
                padB,
            ) { dp(it) }

            KeyRenderer.renderText(
                KeyRenderCtx(
                    context = context,
                    canvas = canvas,
                    key = key,
                    keyboard = kb,
                    width = viewWidth,
                    height = viewHeight,
                    paddingLeft = padL,
                    paddingTop = padT,
                    paddingRight = padR,
                    paddingBottom = padB,
                    dp = { dp(it) },
                    sp = { sp(it) },
                    textPaint = textPaint,
                    symbolPaint = symbolPaint,
                    richTextPaint = richTextPaint,
                    iconCache = iconCache,
                    textColor = key.getTextColor(),
                    symbolColor = key.getSymbolColor(),
                    showSymbols = true,
                    showHints = true,
                    enterLabel = enterLabelText,
                    schemaName = "方案",
                    keyTextSizeSp = fonts.key_size,
                    keyLongTextSizeSp = fonts.key_long_size.takeIf { it > 0f } ?: fonts.key_size,
                    symbolTextSizeSp = fonts.symbol_size.takeIf { it > 0f } ?: fonts.key_size,
                    hintTextSizeSp = fonts.hint_size.takeIf { it > 0f } ?: fonts.key_size,
                    colorResolver = { spec, fallback ->
                        try {
                            resolver.getColor(spec)
                        } catch (_: Exception) {
                            try {
                                Color.parseColor(if (spec.startsWith("#")) spec else "#$spec")
                            } catch (_: Exception) {
                                fallback
                            }
                        }
                    },
                    baseTypeface = typeface,
                    boldTypeface = boldCache ?: typeface,
                ),
            )
            canvas.restore()
        }

        selectedKey?.let { sel ->
            val index = keyPaths.indexOfFirst { it == sel }
            if (index >= 0) {
                val key = kb.keys[index]
                val left = (key.x - key.extraWidthLeft).toFloat() + 1f
                val top = key.y + 1f
                val right = left + key.width + key.extraWidthLeft + key.extraWidthRight - 2f
                val bottom = top + key.height - 2f
                selectPaint.strokeWidth = dp(2f)
                canvas.drawRoundRect(
                    RectF(left, top, right, bottom),
                    dp(style.roundCorner),
                    dp(style.roundCorner),
                    selectPaint,
                )
            }
        }

        popupIndex?.let { index ->
            val key = kb.keys.getOrNull(index)
            if (key != null) {
                val labels = runCatching { key.popup }.getOrNull()?.takeIf { it.isNotEmpty() }
                val text = labels?.joinToString("  ")?.replace("ic@", "")
                if (!text.isNullOrBlank()) {
                    val cx = key.x + key.width / 2f
                    val boxTop = (key.y - dp(34f)).coerceAtLeast(0f)
                    val boxW = popupTextPaint.measureText(text) + dp(20f)
                    val boxH = dp(28f)
                    val box = RectF(cx - boxW / 2f, boxTop, cx + boxW / 2f, boxTop + boxH)
                    popupBgPaint.color = 0xFF3C4043.toInt()
                    canvas.drawRoundRect(box, dp(8f), dp(8f), popupBgPaint)
                    canvas.drawText(
                        text,
                        box.left + dp(10f),
                        box.centerY() - (popupTextPaint.ascent() + popupTextPaint.descent()) / 2f,
                        popupTextPaint,
                    )
                }
            }
        }

        canvas.restore() // contentTop 平移
        canvas.restore() // 整体缩放
    }

    /**
     * 候选栏：高度 = candidate_view_height + comment_height；配色全部走配色方案，
     * 结构照真机（序号 + 候选词 + 注释，首项高亮）。示意文本固定，其余不写死。
     */
    private fun drawCandidateBar(canvas: Canvas, widthPx: Float, topPx: Float, heightPx: Float) {
        if (heightPx <= 0f) return
        val fonts = style.fonts
        val res = resolver
        // 真机候选栏背景：先看 candidate_background 是否解析成图片，否则用颜色
        val bgDrawable = res?.let { runCatching { it.getDrawable("candidate_background") }.getOrNull() }
        if (bgDrawable != null) {
            bgDrawable.setBounds(0, topPx.toInt(), widthPx.toInt(), (topPx + heightPx).toInt())
            bgDrawable.draw(canvas)
        } else {
            candBgPaint.color = colorOr(
                "candidate_background",
                colorOr("candidate_back_color", colorOr("back_color", 0xFF1F2023.toInt())),
            )
            canvas.drawRect(0f, topPx, widthPx, topPx + heightPx, candBgPaint)
        }

        val items = CANDIDATE_SAMPLES
        val padH = dp(style.candidatePadding.toFloat())
        val gap = dp(style.candidateSpacing.toFloat())
        val radius = dp(style.candidateCornerRadius)

        candTextPaint.textSize = sp(fonts.candidate_size)
        candCommentPaint.textSize =
            sp(if (fonts.comment_size > 0f) fonts.comment_size else fonts.candidate_size * 0.6f)
        candLabelPaint.textSize =
            sp(if (fonts.candidate_label_size > 0f) fonts.candidate_label_size else fonts.candidate_size * 0.7f)

        val textColor = colorOr("candidate_text_color", colorOr("text_color", 0xFFE3E6EB.toInt()))
        val commentColor = colorOr("comment_text_color", textColor)
        val labelColor = colorOr("label_color", commentColor)
        val hiBack = colorOr("hilited_candidate_back_color", colorOr("back_color", 0xFF2D4A77.toInt()))
        val hiText = colorOr("hilited_candidate_text_color", colorOr("hilited_text_color", textColor))

        // 候选之间的竖线：candidate_separator_color_unrolled → candidate_separator_color → border_color
        val sepColor = colorOr(
            "candidate_separator_color_unrolled",
            colorOr("candidate_separator_color", colorOr("border_color", 0x00000000)),
        )
        val sepPaint = Paint().apply {
            color = sepColor
            strokeWidth = dp(1f).coerceAtLeast(1f)
            isAntiAlias = false
        }
        var drawn = 0
        var x = padH
        val fm = candTextPaint.fontMetrics
        val baseline = topPx + (heightPx - (fm.bottom - fm.top)) / 2f - fm.top
        items.forEachIndexed { i, (word, comment) ->
            val wordW = candTextPaint.measureText(word)
            val commentW = candCommentPaint.measureText(comment)
            val labelText = (i + 1).toString()
            val labelW = if (style.candidateLabel) candLabelPaint.measureText(labelText) + dp(4f) else 0f
            val itemW = labelW + wordW + if (comment.isNotEmpty()) dp(4f) + commentW else 0f
            val right = (x + itemW + padH).coerceAtMost(widthPx)
            if (right - x <= 0f) return@forEachIndexed

            // 除第一项外，每项左侧画一条竖线（与真机候选分隔线同源）
            if (drawn > 0) {
                val sx = x - gap / 2f
                canvas.drawLine(sx, topPx + dp(4f), sx, topPx + heightPx - dp(4f), sepPaint)
            }
            if (i == 0) {
                candBgPaint.color = hiBack
                canvas.drawRoundRect(
                    RectF(x, topPx + dp(2f), right, topPx + heightPx - dp(2f)),
                    radius,
                    radius,
                    candBgPaint,
                )
            }
            drawn++
            var tx = x + (if (i == 0) 0f else padH * 0.2f)
            if (labelW > 0f) {
                candLabelPaint.color = if (i == 0) hiText else labelColor
                canvas.drawText(labelText, tx, baseline, candLabelPaint)
                tx += labelW
            }
            candTextPaint.color = if (i == 0) hiText else textColor
            canvas.drawText(word, tx, baseline, candTextPaint)
            tx += wordW
            if (comment.isNotEmpty()) {
                candCommentPaint.color = if (i == 0) hiText else commentColor
                canvas.drawText(comment, tx + dp(4f), baseline, candCommentPaint)
            }
            x = right + gap
        }
    }

    /**
     * 编码行（预编辑）：背景 text_back_color × preedit.alpha，字号 preedit.foreground.fontSize，
     * 左右内缩 preedit.horizontalPadding；示意编码固定。
     */
    private fun drawPreeditRow(canvas: Canvas, widthPx: Float, topPx: Float, heightPx: Float) {
        if (heightPx <= 0f) return
        val t = lastTheme ?: ThemeManager.activeTheme
        val back = colorOr("text_back_color", 0x00000000)
        val alpha = (t.preedit.alpha * 255).toInt().coerceIn(0, 255)
        // 不用 ColorUtils：直接按 alpha 重算 ARGB，避免多一个依赖
        candBgPaint.color = (back and 0x00FFFFFF) or (alpha shl 24)
        // 真机 PreeditDelegate：cornerRadii 只圆右上角（LTR），半径 preedit.top_end_radius
        val topEnd = dp(t.preedit.topEndRadius)
        if (topEnd > 0f) {
            val path = Path()
            val r = minOf(topEnd, heightPx, widthPx)
            path.addRoundRect(
                RectF(0f, topPx, widthPx, topPx + heightPx),
                floatArrayOf(0f, 0f, r, r, 0f, 0f, 0f, 0f),
                Path.Direction.CW,
            )
            canvas.drawPath(path, candBgPaint)
        } else {
            canvas.drawRect(0f, topPx, widthPx, topPx + heightPx, candBgPaint)
        }

        preeditPaint.textSize = sp(t.preedit.foreground.fontSize)
        preeditPaint.color = colorOr("text_color", 0xFF000000.toInt())
        preeditPaint.typeface = FontManager.getTypeface()
        val fm = preeditPaint.fontMetrics
        val baseline = topPx + (heightPx - (fm.bottom - fm.top)) / 2f - fm.top
        canvas.drawText(PREEDIT_SAMPLE, dp(t.preedit.horizontalPadding.toFloat()), baseline, preeditPaint)
    }

    /** 与真机 KeyboardView 一致的阴影留白计算，返回 (sLeft, sTop, sRight, sBottom)。 */
    private fun shadowExtents(key: Key, kb: Keyboard): IntArray {
        var sLeft = 0
        var sTop = 0
        var sRight = 0
        var sBottom = 0
        val shadowRadius = key.keyShadowRadius ?: kb.keyShadowRadius
        if (shadowRadius > 0f) {
            val dir = key.keyShadowDirection ?: kb.keyShadowDirection
            if (!dir.isNullOrEmpty()) {
                val shadowDp = dp(shadowRadius)
                val (dx, dy) = Keyboard.shadowDirectionToOffset(dir, shadowDp)
                if (key.edgeFlags and Keyboard.EDGE_LEFT != 0) {
                    sLeft = (shadowDp - dx).coerceAtLeast(0f).toInt()
                }
                if (key.edgeFlags and Keyboard.EDGE_TOP != 0) {
                    sTop = (shadowDp - dy).coerceAtLeast(0f).toInt()
                }
                if (key.edgeFlags and Keyboard.EDGE_RIGHT != 0) {
                    sRight = (shadowDp + dx).coerceAtLeast(0f).toInt()
                }
                if (key.edgeFlags and Keyboard.EDGE_BOTTOM != 0) {
                    sBottom = (shadowDp + dy).coerceAtLeast(0f).toInt()
                }
            }
        }
        return intArrayOf(sLeft, sTop, sRight, sBottom)
    }

    private companion object {
        /** 候选栏示意数据（固定的词 + 拼音），只用于看样式，不参与输入。 */
        val CANDIDATE_SAMPLES = listOf(
            "好" to "hǎo",
            "号" to "hào",
            "郝" to "hào",
            "浩" to "hào",
        )

        /** 编码行示意编码。 */
        const val PREEDIT_SAMPLE = "ni'hao"
    }

    private fun dp(v: Float): Float = v * density

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private fun hex(c: Int): String = String.format("#%08X", c)
}
