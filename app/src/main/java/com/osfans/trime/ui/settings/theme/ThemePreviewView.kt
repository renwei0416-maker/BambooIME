/*
 * SPDX-FileCopyrightText: 2026 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 主题预览：用真实的 Keyboard（纯数据类，不依赖输入法服务）算几何与文字，
 * 文字层直接复用真机的 [com.osfans.trime.ime.keyboard.KeyRenderer]，保证两边永远一致。
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
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import com.mikepenz.iconics.IconicsDrawable
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.TextKeyboard
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
        invalidate()
    }

    fun refresh() = invalidate()

    /** 自证用：当前解析出的关键颜色，显示在顶栏方便对照真机。 */
    fun colorSummary(): String {
        val s = currentScheme()
        val back = colorOf(s, "back_color", 0)
        val text = colorOf(s, "key_text_color", 0)
        return "scheme=${s?.id ?: "无"} back=${hex(back)} text=${hex(text)}"
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
        cacheTextKeys(selfConfig)
        val kb = Keyboard(context, theme, selfConfig)
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

    /** 屏幕坐标 → 内容坐标 → 命中的键下标（spacer 已被排除在 keys 之外）。 */
    // ---------------------------------------------------------------- 绘制状态

    private var drawScale = 1f
    private var drawTx = 0f
    private var drawTy = 0f

    private val backgroundPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

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

    private val backPaint = Paint().apply { isAntiAlias = true }

    private val borderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
    }

    private val errorPaint = Paint().apply {
        isAntiAlias = true
        textSize = 11f * density
    }

    private var errorText: String = ""


    private val iconCache = LruCache<String, IconicsDrawable>(16)

    private var boldBase: Typeface? = null
    private var boldCache: Typeface? = null

    /** 屏幕坐标 → 内容坐标 → 命中的键下标（spacer 已不在 keys 内）。 */
    private fun hitIndex(x: Float, y: Float): Int? {
        val kb = keyboard ?: return null
        if (drawScale <= 0f) return null
        val cx = (x - drawTx) / drawScale
        val cy = (y - drawTy) / drawScale
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
            errorText = (e::class.java.simpleName + ": " + (e.message ?: ""))
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
        val kb = keyboard
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val scheme = currentScheme()
        backgroundPaint.color = colorOf(scheme, "back_color", 0xFF1F2023.toInt())
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        if (kb == null || kb.keys.isEmpty() || kb.height <= 0) {
            canvas.drawText("没有可预览的键盘（$keyboardName）", w / 2f, h / 2f, hintPaint)
            return
        }

        val kbWidth = kb.minWidth.coerceAtLeast(1).toFloat()
        val kbHeight = kb.height.coerceAtLeast(1).toFloat()
        val scale = when (mode) {
            Mode.FIT -> minOf(w / kbWidth, h / kbHeight)
            Mode.CROP -> maxOf(w / kbWidth, h / kbHeight)
        }
        drawScale = scale
        drawTx = (w - kbWidth * scale) / 2f
        drawTy = (h - kbHeight * scale) / 2f

        val fonts = style.fonts
        val typeface = FontManager.getTypeface()
        if (boldBase !== typeface) {
            boldBase = typeface
            boldCache = Typeface.create(typeface, Typeface.BOLD)
        }

        canvas.save()
        canvas.translate(drawTx, drawTy)
        canvas.scale(scale, scale)

        val gapH = style.horizontalGap * kb.horizontalGapScale
        val gapV = kb.verticalGap

        kb.keys.forEachIndexed { index, key ->
            val pressed = pressedIndex == index
            val pal = paletteFor(index, scheme, pressed)

            canvas.save()
            canvas.translate((key.x - key.extraWidthLeft).toFloat(), key.y.toFloat())

            val kw = key.width + key.extraWidthLeft + key.extraWidthRight
            val kh = key.height
            val padL = (gapH / 2f).toInt() + key.extraWidthLeft
            val padT = if (key.edgeFlags and Keyboard.EDGE_TOP != 0) {
                maxOf(kb.keyboardPaddingTop, gapV / 2)
            } else {
                gapV / 2
            }
            val padR = (gapH / 2f).toInt() + key.extraWidthRight
            val padB = if (key.edgeFlags and Keyboard.EDGE_BOTTOM == 0) gapV / 2 else 0

            val rect = RectF(padL.toFloat(), padT.toFloat(), (kw - padR).toFloat(), (kh - padB).toFloat())
            val radius = dp(style.roundCorner.toFloat())
                .coerceIn(0f, minOf(rect.width(), rect.height()) / 2f)
            backPaint.color = pal.back
            canvas.drawRoundRect(rect, radius, radius, backPaint)
            val borderWidth = key.keyBorder ?: kb.keyBorder
            if (borderWidth > 0f) {
                borderPaint.strokeWidth = dp(borderWidth.toFloat())
                borderPaint.color = pal.border
                canvas.drawRoundRect(rect, radius, radius, borderPaint)
            }

            KeyRenderer.renderText(
                KeyRenderCtx(
                    context = context,
                    canvas = canvas,
                    key = key,
                    keyboard = kb,
                    width = kw,
                    height = kh,
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
                    textColor = pal.text,
                    symbolColor = pal.symbol,
                    showSymbols = true,
                    showHints = true,
                    enterLabel = "回车",
                    schemaName = "方案",
                    keyTextSizeSp = fonts.key_size,
                    keyLongTextSizeSp = fonts.key_long_size.takeIf { it > 0f } ?: fonts.key_size,
                    symbolTextSizeSp = fonts.symbol_size.takeIf { it > 0f } ?: fonts.key_size,
                    hintTextSizeSp = fonts.hint_size.takeIf { it > 0f } ?: fonts.key_size,
                    colorResolver = { spec, fallback -> resolveColor(spec, scheme) ?: fallback },
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
                    dp(style.roundCorner.toFloat()),
                    dp(style.roundCorner.toFloat()),
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

        canvas.restore()
    }

    private fun dp(v: Float): Float = v * density

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private fun hex(c: Int): String = String.format("#%08X", c)

    /**
     * 与真机 [com.osfans.trime.data.base.ColorManager] 同构的取色顺序：
     * 当前配色 → 主题 fallback_colors → 内置兜底。
     * **不再跨配色搜**（真机没有这条路，跨配色会把别的配色值顶上来）。
     */
    private fun colorOf(scheme: ColorScheme?, key: String, fallback: Int): Int {
        val raw = scheme?.colors?.get(key)
            ?: ThemeManager.activeTheme.fallbackColors[key]
            ?: BuiltinFallback.colors[key]
        if (raw.isNullOrEmpty()) return fallback
        return runCatching {
            if (raw.startsWith("#")) Color.parseColor(raw) else Color.parseColor("#$raw")
        }.getOrDefault(fallback)
    }

    /** 解析一个颜色值：`#RRGGBB` / `0xRRGGBB` / 配色里的色键名（如 `zmu_q`）。 */
    private fun resolveColor(raw: String?, scheme: ColorScheme?): Int? {
        if (raw.isNullOrEmpty()) return null
        if (raw.startsWith("#") || raw.startsWith("0x", true)) {
            return runCatching {
                if (raw.startsWith("#")) Color.parseColor(raw) else Color.parseColor("#" + raw.substring(2))
            }.getOrNull()
        }
        val named = scheme?.colors?.get(raw) ?: ThemeManager.activeTheme.fallbackColors[raw]
        return named?.let { resolveColor(it, null) }
    }

    private data class KeyColors(
        val back: Int,
        val text: Int,
        val symbol: Int,
        val border: Int,
    )

    private fun paletteFor(index: Int, scheme: ColorScheme?, pressed: Boolean): KeyColors {
        val tk = textKeys.getOrNull(index)
        val back = resolveColor(if (pressed) tk?.hlKeyBackColor else null, scheme)
            ?: resolveColor(tk?.keyBackColor, scheme)
            ?: colorOf(scheme, if (pressed) "hilited_key_back_color" else "key_back_color", 0xFF33363B.toInt())
        // 按下时若配色没有 hilited 值，就退回到正常色
        val backFinal = if (pressed) {
            resolveColor(tk?.hlKeyBackColor, scheme) ?: colorOf(scheme, "hilited_key_back_color", back)
        } else {
            back
        }
        val text = resolveColor(if (pressed) tk?.hlKeyTextColor else null, scheme)
            ?: resolveColor(tk?.keyTextColor, scheme)
            ?: colorOf(scheme, "key_text_color", 0xFFE8EAED.toInt())
        val textFinal = if (pressed) {
            resolveColor(tk?.hlKeyTextColor, scheme) ?: colorOf(scheme, "hilited_key_text_color", text)
        } else {
            text
        }
        val symbol = resolveColor(if (pressed) tk?.hlKeySymbolColor else null, scheme)
            ?: resolveColor(tk?.keySymbolColor, scheme)
            ?: colorOf(scheme, "key_symbol_color", textFinal)
        val symbolFinal = if (pressed) {
            resolveColor(tk?.hlKeySymbolColor, scheme) ?: colorOf(scheme, "hilited_key_symbol_color", symbol)
        } else {
            symbol
        }
        val border = resolveColor(tk?.keyBorderColor, scheme)
            ?: colorOf(scheme, "key_border_color", backFinal)
        return KeyColors(backFinal, textFinal, symbolFinal, border)
    }

    private var textKeys: List<TextKeyboard.TextKey> = emptyList()

    private fun cacheTextKeys(tk: TextKeyboard) {
        textKeys = tk.rows.flatMap { it.keys }.filter { !it.spacer }
    }

    init {
        rawKeyboard()?.let { cacheTextKeys(it) }
    }

    private object BuiltinFallback {
        val colors: Map<String, String> = mapOf(
            "key_back_color" to "33363b",
            "key_text_color" to "e8eaed",
            "key_symbol_color" to "9aa0a6",
            "hilited_key_back_color" to "4a4e55",
            "hilited_key_text_color" to "ffffff",
            "hilited_key_symbol_color" to "d0d4d9",
            "candidate_back_color" to "1f2023",
            "candidate_text_color" to "e8eaed",
            "back_color" to "1f2023",
        )
    }
}
