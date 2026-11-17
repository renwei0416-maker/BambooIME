/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.KeyEvent
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizePx
import com.osfans.trime.actions.bamboo.BambooTokens
import com.osfans.trime.actions.wanxiang.KeyPanelWindow
import com.osfans.trime.actions.wanxiang.WanxiangPrefs
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupAction
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.link.AsrkbVoiceHoldSessionController
import com.osfans.trime.util.sp
import splitties.dimensions.dp
import timber.log.Timber

@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class KeyView(
    context: Context,
    private val key: Key,
    private val keyboard: Keyboard,
    private val keyboardView: KeyboardView,
    private val keyboardActionListener: KeyboardActionListener,
) : GestureFrame(context) {

    private val service: TrimeInputMethodService
        get() = keyboardView.service

    private val popup: PopupDelegate
        get() = keyboardView.popup

    private val schemaDisplayName: String
        get() = RimeDaemon.getFirstSessionOrNull()?.run {
            statusCached.schemaName.ifEmpty {
                schemaCached.schemaId.takeIf { it.isNotEmpty() && it != ".default" } ?: ""
            }
        } ?: ""

    private val hookShiftArrow: Boolean by lazy {
        AppPrefs.defaultInstance().keyboard.hookShiftArrow.getValue()
    }
    private val asrkbAidlVoiceInputEnabled: Boolean
        get() = AppPrefs.defaultInstance().voiceInput.asrkbAidlVoiceInputEnabled.getValue()

    private val deletedTextBuffer = ArrayDeque<String>()

    private var keyPressed = false
    override fun isPressed(): Boolean = keyPressed

    private val asrkbVoiceHoldController by lazy {
        AsrkbVoiceHoldSessionController(
            service = service,
            showOverlay = { keyboardView.showVoiceOverlay() },
            startWave = { keyboardView.startVoiceOverlayWave() },
            updateAmplitude = { amplitude -> keyboardView.updateVoiceOverlayAmplitude(amplitude) },
            hideOverlay = { keyboardView.hideVoiceOverlay() },
            useAidl = { asrkbAidlVoiceInputEnabled },
        )
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val richTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    private var cachedTextBoldTypeface: Typeface? = null
    private var cachedTextBoldBase: Typeface? = null
    private var cachedSymbolBoldTypeface: Typeface? = null
    private var cachedSymbolBoldBase: Typeface? = null
    private var cachedRichTextBoldTypeface: Typeface? = null
    private var cachedRichTextBoldBase: Typeface? = null

    private val iconCache = object : LruCache<String, IconicsDrawable>(4) {}

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    private var boundsValid = false

    val bounds: Rect
        get() = cachedBounds.also {
            if (!boundsValid) updateBounds()
        }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { getLocationInWindow(it) }
        cachedBounds.set(x + key.extraWidthLeft, y, x + width - key.extraWidthRight, y + height)
        boundsValid = true
    }

    init {
        setWillNotDraw(false)
        isRepeatable = key.click?.isRepeatable ?: false
        isSlideCursor = key.click?.isSlideCursor ?: false
        isSlideDelete = key.click?.isSlideDelete ?: false
        hasLongPress = key.hasAction(KeyBehavior.LONG_CLICK)
        hasDouble = key.hasAction(KeyBehavior.DOUBLE_CLICK)
        hasLazyDouble = key.hasAction(KeyBehavior.LAZY_DOUBLE_CLICK)
        hasPopup = key.popup.isNotEmpty()
        hasSwipeAction = key.hasAction(KeyBehavior.SWIPE_UP) ||
            key.hasAction(KeyBehavior.SWIPE_DOWN) ||
            key.hasAction(KeyBehavior.SWIPE_LEFT) ||
            key.hasAction(KeyBehavior.SWIPE_RIGHT)

        onPress = {
            if (keyboard.firstPressedKeyIndex == -1) keyboard.firstPressedKeyIndex = id
            setPressedState(true)
            key.getCode(KeyBehavior.CLICK).let { keyboardActionListener.onPress(it) }
            showPopupPreview()
        }

        onRelease = { behavior, isFromLongPress ->
            Timber.d("KeyView release: label=${key.getLabel()}, behavior=$behavior, fromLongPress=$isFromLongPress")
            if (isFromLongPress && isVoiceLongPressAction(key.getAction(KeyBehavior.LONG_CLICK))) {
                asrkbVoiceHoldController.stopIfStarted()
                setPressedState(false)
                dismissPopupPreview()
                if (keyboard.firstPressedKeyIndex == id) keyboard.firstPressedKeyIndex = -1
            } else if (isFromLongPress) {
                if (hasPopup) {
                    val triggerAction = PopupAction.TriggerAction(id)
                    popup.listener.onPopupAction(triggerAction)
                    triggerAction.outAction?.let { action ->
                        keyboardActionListener.onAction(KeyAction(action))
                        dismissPopupPreview()
                    }
                    setPressedState(false)
                } else if (isRepeatable) {
                    if (!keyboardActionListener.shouldContinueRepeat()) {
                        cancelRepeat()
                    } else {
                        key.getAction(KeyBehavior.CLICK)?.let { processKeyAction(it, KeyBehavior.CLICK) }
                    }
                }
            } else {
                when (behavior) {
                    KeyBehavior.CLICK -> {
                        val pressedIdx = keyboard.firstPressedKeyIndex
                        val actionBehavior = if (pressedIdx != -1 && pressedIdx != id) KeyBehavior.COMBO else behavior
                        key.getAction(actionBehavior)?.let { processKeyAction(it, actionBehavior) }
                    }

                    KeyBehavior.DOUBLE_CLICK, KeyBehavior.LAZY_DOUBLE_CLICK,
                    KeyBehavior.SWIPE_UP, KeyBehavior.SWIPE_DOWN, KeyBehavior.SWIPE_LEFT, KeyBehavior.SWIPE_RIGHT,
                    ->
                        key.getAction(behavior)?.let { processKeyAction(it, behavior) }

                    else -> {}
                }

                setPressedState(false)
                dismissPopupPreview()
            }
            if (keyboard.firstPressedKeyIndex == id) keyboard.firstPressedKeyIndex = -1
        }

        onSwipe = { direction ->
            setPressedState(true)
            showPopupPreview(direction)
        }

        onSlide = { delta, _, _ ->
            if (isSlideCursor) {
                when {
                    delta > 0 -> keyboardActionListener.onAction(KeyAction("Right"))
                    delta < 0 -> keyboardActionListener.onAction(KeyAction("Left"))
                }
            } else if (isSlideDelete) {
                val ic = service.currentInputConnection
                when {
                    delta < 0 -> {
                        val beforeText = ic.getTextBeforeCursor(1, 0) ?: ""
                        if (beforeText.isNotEmpty()) {
                            deletedTextBuffer.addFirst(beforeText.toString())
                            ic.deleteSurroundingText(1, 0)
                        }
                    }

                    delta > 0 -> {
                        if (deletedTextBuffer.isNotEmpty()) {
                            ic.commitText(deletedTextBuffer.removeFirst(), 1)
                        }
                    }
                }
            }
        }

        onLongClick = {
            val longPressAction = key.getAction(KeyBehavior.LONG_CLICK)
            if (isVoiceLongPressAction(longPressAction)) {
                asrkbVoiceHoldController.start()
            } else if (key.popup.isNotEmpty() &&
                WanxiangPrefs.panelOnLongPress &&
                (key.getAction(KeyBehavior.CLICK)?.code == KeyEvent.KEYCODE_SWITCH_CHARSET ||
                    longPressAction?.code == KeyEvent.KEYCODE_SWITCH_CHARSET)
            ) {
                // 长按切换键：打开我们自己的「键盘与方案」面板（点一下把该做的都做完）。
                // 单击仍然走 onClick 的 ModeSwitch，不受影响。
                dismissPopupPreview()
                KeyPanelWindow.open()
            } else if (key.popup.isNotEmpty()) {
                dismissPopupPreview()
                showPopupKeyboard()
            } else if (hasLongPress) {
                longPressAction?.let {
                    processKeyAction(it, KeyBehavior.LONG_CLICK)
                    setPressedState(false)
                    dismissPopupPreview()
                }
            }
        }

        onMove = { x, y, isLongPress ->
            if (isLongPress && hasPopup) {
                popup.listener.onPopupAction(PopupAction.ChangeFocusAction(id, x, y))
            }
        }

        onCancel = {
            deletedTextBuffer.clear()
            asrkbVoiceHoldController.stopIfStarted()
            setPressedState(false)
            dismissPopupPreview()
        }
    }

    fun setPressedState(pressed: Boolean) {
        if (keyPressed != pressed) {
            keyPressed = pressed
            if (pressed) {
                key.onPressed()
            } else {
                key.onReleased()
            }
            setLayerType(LAYER_TYPE_HARDWARE, null)
            invalidate()
        }
    }

    /**
     * 长按是不是「按住说话」。
     *
     * 除了主题原有的 `VoiceAssist`（伪键码 KEYCODE_VOICE_ASSIST），也认我们自己的
     * 文本 token `BambooVoiceAi` —— 它解析出来 `code = 0`，如果不放宽，
     * 按下能开始录音（走 token），松手却没人停（松手那一支只认这个判定）。
     */
    private fun isVoiceLongPressAction(action: KeyAction?): Boolean =
        action?.code == KeyEvent.KEYCODE_VOICE_ASSIST ||
            action?.isBambooVoiceAiToken == true

    private fun processKeyAction(action: KeyAction, behavior: KeyBehavior) {
        Timber.d("processKeyAction: label=${key.getLabel()}, code=${action.code}, type=$behavior")

        if (action.isModifierKey) {
            keyboard.clickModifierKey(
                action.isShiftLock xor (behavior == KeyBehavior.LONG_CLICK),
                action.modifierKeyOnMask,
            )
            keyboardView.invalidateAllKeys()
            return
        }

        keyboardActionListener.onAction(action, key, behavior)

        val hookArrow = if (hookShiftArrow) {
            when (action.code) {
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> true
                else -> false
            }
        } else {
            false
        }

        if (!hookArrow) {
            if (keyboard.refreshModifier()) {
                keyboardView.invalidateAllKeys()
            }
        }
    }

    private fun showPopupKeyboard() {
        val popupKeys = key.popup
        if (popupKeys.isEmpty()) return

        popup.listener.onPopupAction(
            PopupAction.ShowKeyboardAction(id, popupKeys, bounds),
        )
    }

    private fun showPopupPreview(behavior: KeyBehavior = KeyBehavior.CLICK) {
        if (!keyboardView.popupOnKeyPress) return
        key.getPreviewText(behavior).takeIf { it.isNotEmpty() }?.let { previewText ->
            val context = if (previewText.isIconFont) {
                previewText
            } else {
                String(Character.toChars(previewText.codePointAt(0)))
            }
            popup.listener.onPopupAction(PopupAction.PreviewAction(id, context, bounds))
        }
    }

    private fun dismissPopupPreview() {
        popup.listener.onPopupAction(
            PopupAction.DismissAction(id),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        val desiredWidth = totalWidth + paddingLeft + paddingRight
        val desiredHeight = key.height + paddingTop + paddingBottom

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        boundsValid = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawBackground(canvas, key)

        // 文字层与设置页预览共用同一份实现，保证两边永远一致。
        KeyRenderer.renderText(
            KeyRenderCtx(
                context = context,
                canvas = canvas,
                key = key,
                keyboard = keyboard,
                width = width,
                height = height,
                paddingLeft = paddingLeft,
                paddingTop = paddingTop,
                paddingRight = paddingRight,
                paddingBottom = paddingBottom,
                dp = { dp(it) },
                sp = { sp(it) },
                textPaint = textPaint,
                symbolPaint = symbolPaint,
                richTextPaint = richTextPaint,
                iconCache = iconCache,
                textColor = key.getTextColor(),
                symbolColor = key.getSymbolColor(),
                showSymbols = keyboardView.showKeySymbols,
                showHints = keyboardView.showKeyHints,
                enterLabel = keyboardView.labelEnter,
                schemaName = schemaDisplayName,
                keyTextSizeSp = keyboardView.keyTextSize,
                keyLongTextSizeSp = keyboardView.keyLongTextSize,
                symbolTextSizeSp = keyboardView.symbolTextSize,
                hintTextSizeSp = keyboardView.hintTextSize,
                colorResolver = { spec, fallback -> resolveColor(spec, fallback) },
                baseTypeface = FontManager.getTypeface(),
                boldTypeface = boldTypefaceFor(textPaint),
            ),
        )
    }

    private var boldBase: Typeface? = null
    private var boldCached: Typeface? = null

    private fun boldTypefaceFor(paint: Paint): Typeface {
        val base = paint.typeface ?: FontManager.getTypeface()
        if (boldBase !== base || boldCached == null) {
            boldBase = base
            boldCached = Typeface.create(base, Typeface.BOLD)
        }
        return boldCached ?: Typeface.create(base, Typeface.BOLD)
    }

    private fun resolveColor(
        spec: String,
        fallback: Int,
    ): Int = try {
        ColorManager.getColor(spec)
    } catch (_: Exception) {
        try {
            Color.parseColor(if (spec.startsWith("#")) spec else "#$spec")
        } catch (_: Exception) {
            fallback
        }
    }

    private fun drawBackground(canvas: Canvas, k: Key) {
        // 背景层与设置页预览共用 KeyRenderer.renderBackground（同一份实现）。
        KeyRenderer.renderBackground(
            canvas = canvas,
            key = k,
            keyboard = keyboard,
            width = width,
            height = height,
            paddingLeft = paddingLeft,
            paddingTop = paddingTop,
            paddingRight = paddingRight,
            paddingBottom = paddingBottom,
            dp = { dp(it) },
        )
    }

}
