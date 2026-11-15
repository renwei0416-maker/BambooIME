/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import com.osfans.trime.actions.bamboo.AiPanelWindow
import com.osfans.trime.util.BambooLog
import com.osfans.trime.actions.handwriting.HandwritingWindow
import com.osfans.trime.actions.ocr.OcrWindow
import com.osfans.trime.actions.wanxiang.KeyPanelWindow
import com.osfans.trime.actions.ocr.OcrCard
import com.osfans.trime.actions.wanxiang.SchemaHintCard
import com.osfans.trime.actions.wanxiang.WanxiangPrefs
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.packaging.SchemaPackStore
import com.osfans.trime.util.AppUtils
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcaster
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.PreeditDelegate
import com.osfans.trime.ime.composition.PreeditUi
import com.osfans.trime.ime.composition.SyncInputBox
import com.osfans.trime.ime.composition.SyncInputTextFormatter
import com.osfans.trime.ime.composition.TargetTextSnapshot
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import timber.log.Timber
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import kotlin.math.abs

/**
 * Successor of the old InputRoot
 */
@SuppressLint("ViewConstructor")
class InputView(
    service: TrimeInputMethodService,
    rime: RimeSession,
    theme: Theme,
) : BaseInputView(service, rime, theme) {
    private val keyboardBackground =
        imageView {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    private val placeholderListener = OnClickListener { }

    private val leftPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val rightPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val bottomPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val updateWindowViewHeightJob: Job

    private val inputDepMgr = InputDependencyManager.initialize(this, themedContext, theme, service, rime)
    private val di = inputDepMgr.di
    private val broadcaster: InputBroadcaster by di.instance()
    private val popup: PopupDelegate by di.instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by di.instance()
    private val preedit: PreeditDelegate by di.instance()

    /** 让编码区重算一次（提示块自己按当前状态决定画不画）。 */
    fun refreshPreedit() = runCatching { preedit.refresh() }

    private val windowManager: BoardWindowManager by di.instance()
    private val inputBar: InputBarDelegate by di.instance()
    private val keyboardWindow: KeyboardWindow by di.instance()
    private val liquidWindow: LiquidWindow by di.instance()

    private val syncInputBox =
        SyncInputBox(context, theme).apply {
            visibility = View.GONE
            onMoveCursor = { offset -> service.setTargetSelection(offset, offset) }
            onSelectRange = { start, end -> service.setTargetSelection(start, end) }
        }

    private val inlinePreeditMode by AppPrefs.defaultInstance().general.inlinePreeditMode
    private val appPrefs = AppPrefs.defaultInstance()
    private val floatingSyncInput by appPrefs.keyboard.floatingSyncInput
    private val effectiveWindowMode get() = service.inputDeviceManager.effectiveWindowMode
    private val candidatesMode by AppPrefs.defaultInstance().candidates.mode

    @Keep
    private val floatingSyncInputListener =
        PreferenceDelegate.OnChangeListener<Boolean> { _, enabled ->
            if (!enabled) {
                hideSyncInputBox()
            } else if (isEffectiveFloating) {
                service.scheduleFloatingSyncInputUpdate(0L)
            }
        }

    private val keyboardSidePadding = theme.generalStyle.keyboardPadding
    private val keyboardSidePaddingLandscape = theme.generalStyle.keyboardPaddingLand
    private val keyboardBottomPadding = theme.generalStyle.keyboardPaddingBottom
    private val keyboardBottomPaddingLandscape = theme.generalStyle.keyboardPaddingLandBottom

    private val keyboardSidePaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardSidePaddingLandscape else keyboardSidePadding
            return dp(value)
        }

    private var lastAppearanceState = Triple(false, false, false)

    private fun broadcastKeyAppearanceUpdate() {
        val composing = rime.run { statusCached.isComposing }
        val hasMenu = rime.run { hasMenu }
        val paging = rime.run { paging }
        val current = Triple(composing, hasMenu, paging)
        if (current != lastAppearanceState) {
            lastAppearanceState = current
            broadcaster.onKeyAppearanceUpdate(current.first, current.second, current.third)
        }
    }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardBottomPaddingLandscape else keyboardBottomPadding
            return dp(value)
        }

    var isFloating = false
        private set

    private val isLandscapeOrientation: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val isEffectiveFloating: Boolean
        get() = isFloating

    var isOneHanded = false
        private set

    private var oneHandOnRight = true

    private var oneHandResizeStartWidth = 0
    private var lastOneHandTouchX = 0f
    private var oneHandDragging = false
    private var lastOneHandGapRefreshAt = 0L
    private val oneHandGapRefreshIntervalMs = 80L
    private val oneHandTouchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    private val internalPrefs = appPrefs.internal

    private var oneHandOnRightPortrait by internalPrefs.oneHandOnRightPortrait
    private var oneHandOnRightLandscape by internalPrefs.oneHandOnRightLandscape
    private var oneHandWidthPx by internalPrefs.oneHandWidthPx

    private var floatingWidthPx by internalPrefs.floatingKeyboardWidth
    private var floatingHeightPx by internalPrefs.floatingKeyboardHeight
    private var floatingXPortrait by internalPrefs.floatingKeyboardXPortrait
    private var floatingYPortrait by internalPrefs.floatingKeyboardYPortrait
    private var floatingXLandscape by internalPrefs.floatingKeyboardXLandscape
    private var floatingYLandscape by internalPrefs.floatingKeyboardYLandscape

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private var floatingResizeStartWidth = 0
    private var floatingResizeStartHeight = 0
    private var floatingResizeStartTranslationX = 0f
    private var lastResizeTouchX = 0f
    private var lastResizeTouchY = 0f

    private val minFloatingWidthPx: Int
        get() = dp(180).coerceAtMost(resources.displayMetrics.widthPixels)

    private val maxFloatingWidthPx: Int
        get() = resources.displayMetrics.widthPixels.coerceAtLeast(minFloatingWidthPx)

    private val minFloatingHeightPx: Int
        get() = dp(100).coerceAtMost(resources.displayMetrics.heightPixels)

    private val maxFloatingHeightPx: Int
        get() = (resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(minFloatingHeightPx)

    private val floatingCornerRadiusPx: Int
        get() = dp(10)

    private val keyboardOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) return
            val radius = if (isEffectiveFloating) floatingCornerRadiusPx.toFloat() else 0f
            outline.setRoundRect(0, 0, width, height, radius)
        }
    }

    private fun resolveFloatingWidth(): Int {
        val stored = floatingWidthPx.takeIf { it > 0 } ?: run {
            val default = (resources.displayMetrics.widthPixels * 0.8).toInt()
            floatingWidthPx = default.coerceIn(minFloatingWidthPx, maxFloatingWidthPx)
            floatingWidthPx
        }
        floatingWidthPx = stored.coerceIn(minFloatingWidthPx, maxFloatingWidthPx)
        return floatingWidthPx
    }

    private val defaultKeyboardHeightPx: Int
        get() {
            val keyboard = runCatching { KeyboardWindow.currentKeyboard }.getOrNull()
            if (keyboard != null && keyboard.height > 0) {
                return keyboard.height
            }
            var h = theme.generalStyle.keyboardHeight
            if (context.isLandscapeMode()) {
                val land = theme.generalStyle.keyboardHeightLand
                if (land > 0) h = land
            }
            return dp(h)
        }

    private fun resolveFloatingHeight(): Int {
        val stored = floatingHeightPx.takeIf { it > 0 } ?: run {
            floatingHeightPx = defaultKeyboardHeightPx.coerceIn(minFloatingHeightPx, maxFloatingHeightPx)
            floatingHeightPx
        }
        floatingHeightPx = stored.coerceIn(minFloatingHeightPx, maxFloatingHeightPx)
        return floatingHeightPx
    }

    private fun getStoredFloatingPosition(): Pair<Int, Int> = if (isLandscapeOrientation) {
        floatingXLandscape to floatingYLandscape
    } else {
        floatingXPortrait to floatingYPortrait
    }

    private fun saveFloatingPosition(x: Int, y: Int) {
        if (isLandscapeOrientation) {
            floatingXLandscape = x
            floatingYLandscape = y
        } else {
            floatingXPortrait = x
            floatingYPortrait = y
        }
    }

    val keyboardView: ConstraintLayout

    /** OCR 确认卡片的挂载点：在顶栏与键盘之间，平时 GONE。 */
    private lateinit var ocrCardHost: LinearLayout

    /** 「打不出字」提示卡的宿主：和拍照卡片同一种覆盖层，贴在编码框上沿。 */
    private lateinit var schemaHintHost: LinearLayout

    private fun handleColor(): Int {
        val base = ContextCompat.getColor(context, R.color.lavender)
        return (base and 0x00FFFFFF) or (0xA0 shl 24)
    }

    private fun createHandleDrawable(
        radius: Float? = null,
        color: Int = handleColor(),
        shape: Int = GradientDrawable.OVAL,
    ): GradientDrawable {
        val r = radius ?: dp(3).toFloat()
        return GradientDrawable().apply {
            this.shape = shape
            setSize(dp(6), dp(6))
            cornerRadius = r
            setColor(color)
        }
    }

    private val floatingLeftHandle =
        view(::View) {
            visibility = View.GONE
            setOnTouchListener { v, event ->
                if (!isFloating) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        floatingResizeStartWidth = resolveFloatingWidth()
                        floatingResizeStartTranslationX = keyboardView.translationX
                        lastResizeTouchX = event.rawX
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.isPressed = true
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val delta = (event.rawX - lastResizeTouchX).toInt()
                        val newWidth =
                            (floatingResizeStartWidth - delta).coerceIn(minFloatingWidthPx, maxFloatingWidthPx)
                        floatingWidthPx = newWidth
                        keyboardView.translationX =
                            (floatingResizeStartTranslationX + floatingResizeStartWidth) - newWidth
                        updateFloatingOverlayTranslation()
                        applyFloatingWidth()
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        v.isPressed = false
                        saveFloatingPosition(
                            keyboardView.translationX.toInt(),
                            keyboardView.translationY.toInt(),
                        )
                        true
                    }

                    else -> false
                }
            }
        }

    private val floatingRightHandle =
        view(::View) {
            visibility = View.GONE
            // TODO: Sidebar text disappears when resizing width in floating mode,
            //  because KeyboardWindow.refreshKeyboards() is triggered on every layout change.
            setOnTouchListener { v, event ->
                if (!isFloating) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        floatingResizeStartWidth = resolveFloatingWidth()
                        lastResizeTouchX = event.rawX
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.isPressed = true
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val delta = (event.rawX - lastResizeTouchX).toInt()
                        floatingWidthPx =
                            (floatingResizeStartWidth + delta).coerceIn(minFloatingWidthPx, maxFloatingWidthPx)
                        applyFloatingWidth()
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        v.isPressed = false
                        saveFloatingPosition(
                            keyboardView.translationX.toInt(),
                            keyboardView.translationY.toInt(),
                        )
                        true
                    }

                    else -> false
                }
            }
        }

    private val floatingBottomHandle =
        view(::View) {
            visibility = View.GONE
            setOnTouchListener { v, event ->
                if (!isEffectiveFloating) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        floatingResizeStartHeight = resolveFloatingHeight()
                        lastResizeTouchY = event.rawY
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.isPressed = true
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val delta = (event.rawY - lastResizeTouchY).toInt()
                        floatingHeightPx =
                            (floatingResizeStartHeight + delta).coerceIn(minFloatingHeightPx, maxFloatingHeightPx)
                        applyFloatingHeight()
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        v.isPressed = false
                        saveFloatingPosition(
                            keyboardView.translationX.toInt(),
                            keyboardView.translationY.toInt(),
                        )
                        true
                    }

                    else -> false
                }
            }
        }

    private val adjustableHandle =
        view(::View) {
            visibility = View.GONE
            setOnTouchListener { v, event ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        v.isPressed = true
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastTouchX
                        val dy = event.rawY - lastTouchY
                        keyboardView.translationX += dx
                        keyboardView.translationY += dy
                        clampFloatingPosition()
                        updateFloatingOverlayTranslation()
                        updateHandlePosition()
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        v.isPressed = false
                        saveFloatingPosition(
                            keyboardView.translationX.toInt(),
                            keyboardView.translationY.toInt(),
                        )
                        true
                    }

                    else -> false
                }
            }
        }

    private val oneHandHandle =
        view(::View) {
            visibility = View.GONE
            setOnClickListener {
                if (!isDockedOneHandMode) return@setOnClickListener
                switchOneHandSide()
            }
            setOnTouchListener { v, event ->
                if (!isDockedOneHandMode) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        oneHandResizeStartWidth = resolveOneHandWidth()
                        lastOneHandTouchX = event.rawX
                        oneHandDragging = false
                        lastOneHandGapRefreshAt = 0L
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.isPressed = true
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val delta = event.rawX - lastOneHandTouchX
                        if (!oneHandDragging && abs(delta) > oneHandTouchSlop) {
                            oneHandDragging = true
                        }
                        if (oneHandDragging) {
                            val target = if (oneHandOnRight) {
                                oneHandResizeStartWidth - delta.toInt()
                            } else {
                                oneHandResizeStartWidth + delta.toInt()
                            }
                            oneHandWidthPx = target.coerceIn(minOneHandWidthPx, maxOneHandWidthPx)
                            applyOneHandWidth()
                            updateOneHandGapScale()
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        v.isPressed = false
                        if (!oneHandDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                            v.performClick()
                        } else if (oneHandDragging) {
                            updateOneHandGapScale(force = true)
                        }
                        oneHandDragging = false
                        true
                    }

                    else -> false
                }
            }
        }

    private val isDockedOneHandMode: Boolean
        get() = isOneHanded && !isFloating

    private val minOneHandWidthPx: Int
        get() = dp(180).coerceAtMost(resources.displayMetrics.widthPixels)

    private val maxOneHandWidthPx: Int
        get() = resources.displayMetrics.widthPixels.coerceAtLeast(minOneHandWidthPx)

    private fun resolveOneHandWidth(): Int {
        val stored = oneHandWidthPx.takeIf { it > 0 } ?: run {
            val default = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            oneHandWidthPx = default.coerceIn(minOneHandWidthPx, maxOneHandWidthPx)
            oneHandWidthPx
        }
        oneHandWidthPx = stored.coerceIn(minOneHandWidthPx, maxOneHandWidthPx)
        return oneHandWidthPx
    }

    private fun getStoredOneHandSide(): Boolean = if (isLandscapeOrientation) {
        oneHandOnRightLandscape
    } else {
        oneHandOnRightPortrait
    }

    private fun saveOneHandSide(onRight: Boolean) {
        if (isLandscapeOrientation) {
            oneHandOnRightLandscape = onRight
        } else {
            oneHandOnRightPortrait = onRight
        }
    }

    private fun switchOneHandSide() {
        oneHandOnRight = !oneHandOnRight
        saveOneHandSide(oneHandOnRight)
        if (!isDockedOneHandMode) return
        updateKeyboardSize()
        syncOneHandHandleUi(bringToFront = true)
        requestLayout()
    }

    private fun applyOneHandWidth() {
        if (!isDockedOneHandMode) return
        updateKeyboardSize()
        updateOneHandHandlePosition()
        requestLayout()
    }

    private fun updateOneHandGapScale(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastOneHandGapRefreshAt < oneHandGapRefreshIntervalMs) {
            return
        }
        lastOneHandGapRefreshAt = now
        if (!isDockedOneHandMode) {
            keyboardWindow.setHorizontalGapScale(1f)
            return
        }
        val containerWidth = keyboardView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val scale = resolveOneHandWidth().toFloat() / containerWidth.toFloat()
        keyboardWindow.setHorizontalGapScale(scale)
    }

    private fun updateOneHandHandleAppearance() {
        val color = handleColor()
        val background = createHandleDrawable(dp(10).toFloat(), color, GradientDrawable.RECTANGLE)
        val iconRes = if (oneHandOnRight) {
            R.drawable.ic_baseline_keyboard_arrow_left_24
        } else {
            R.drawable.ic_baseline_keyboard_arrow_right_24
        }
        val icon = ContextCompat.getDrawable(context, iconRes)?.mutate()
        if (icon == null) {
            oneHandHandle.background = background
            return
        }
        icon.setTint(color)
        val inset = dp(4)
        val drawable = LayerDrawable(arrayOf(background, icon)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
        oneHandHandle.background = drawable
    }

    private fun updateOneHandHandlePosition() {
        val safeGap = dp(6)
        oneHandHandle.updateLayoutParams<LayoutParams> {
            topToTop = windowManager.view.id
            bottomToBottom = windowManager.view.id
            if (oneHandOnRight) {
                startToStart = ConstraintLayout.LayoutParams.UNSET
                endToEnd = ConstraintLayout.LayoutParams.UNSET
                startToEnd = ConstraintLayout.LayoutParams.UNSET
                endToStartOf(windowManager.view)
                marginStart = 0
                marginEnd = safeGap
            } else {
                startToStart = ConstraintLayout.LayoutParams.UNSET
                endToEnd = ConstraintLayout.LayoutParams.UNSET
                endToStart = ConstraintLayout.LayoutParams.UNSET
                startToEndOf(windowManager.view)
                marginStart = safeGap
                marginEnd = 0
            }
        }
    }

    private fun updateOneHandHandleVisibility() {
        oneHandHandle.visibility = if (isOneHanded && !isFloating) View.VISIBLE else View.GONE
    }

    private fun syncOneHandHandleUi(bringToFront: Boolean = false) {
        updateOneHandHandleAppearance()
        updateOneHandHandlePosition()
        updateOneHandHandleVisibility()
        if (bringToFront) {
            oneHandHandle.bringToFront()
        }
    }

    init {
        // MUST call before any operation
        inputDepMgr.start()

        isOneHanded = rime.run { getRuntimeOption("_one_hand_mode") }
        oneHandOnRight = getStoredOneHandSide()

        isFloating = rime.run { getRuntimeOption("_floating_keyboard") }

        windowManager.cacheResidentWindow(keyboardWindow, createView = true)
        windowManager.cacheResidentWindow(liquidWindow)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        keyboardBackground.imageDrawable = ColorManager.getDrawable("keyboard_background")

        ocrCardHost =
            LinearLayout(context).apply {
                id = View.generateViewId()
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }

        schemaHintHost =
            LinearLayout(context).apply {
                id = View.generateViewId()
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }

        keyboardView =
            constraintLayout {
                id = View.generateViewId()
                isMotionEventSplittingEnabled = true
                add(
                    keyboardBackground,
                    lParams(matchParent, matchParent) {
                        startOfParent()
                        endOfParent()
                        topOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    inputBar.view,
                    lParams(matchParent, dp(inputBar.themedHeight)) {
                        topOfParent()
                        centerHorizontally()
                    },
                )
                add(
                    leftPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        startOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    rightPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        endOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    windowManager.view,
                    lParams {
                        below(inputBar.view)
                        above(bottomPaddingSpace)
                    },
                )
                add(
                    bottomPaddingSpace,
                    lParams {
                        startToEndOf(leftPaddingSpace)
                        endToStartOf(rightPaddingSpace)
                        bottomOfParent()
                    },
                )
                add(
                    oneHandHandle,
                    lParams(dp(16), dp(44)) {
                        topToTop = windowManager.view.id
                        bottomToBottom = windowManager.view.id
                        startToEndOf(windowManager.view)
                    },
                )
            }

        updateWindowViewHeightJob =
            service.lifecycleScope.launch {
                keyboardWindow.currentKeyboardHeight.collect {
                    windowManager.view.updateLayoutParams {
                        height = if (isEffectiveFloating) resolveFloatingHeight() else it
                    }
                    if (isEffectiveFloating) {
                        applyKeyboardViewScale()
                    } else {
                        keyboardWindow.currentKeyboardView?.apply {
                            scaleY = 1f
                            scaleX = 1f
                        }
                    }
                }
            }

        updateKeyboardSize()

        preedit.ui.root.id = View.generateViewId()
        add(
            // 宽度交给整行：root 里躺着「编码行 + 提示块」两个子 View，若这里是 wrapContent，
            // 父宽度会退化成编码行的宽度（短编码就只有一百多像素），提示块是 matchParent，
            // 第二轮测量就被夹成那么宽，长一点的外语释义只能在窄条里疯狂折行。
            preedit.ui.root,
            // ConstraintLayout 里 matchParent 等于「由两侧约束决定」：只给 start 不给 end 时，
            // 它会退回按内容收缩（日志里 rootW 一直等于编码行宽度就是这么来的）。
            // 两头都钉住才是真正的整行。
            lParams(matchParent, wrapContent) {
                above(keyboardView)
                startOfParent()
                endOfParent()
            },
        )

        add(
            syncInputBox,
            lParams(wrapContent, wrapContent) {
                startOfParent()
                bottomToTop = preedit.ui.root.id
            },
        )

        // 拍照识字的确认卡片：独立覆盖层，底边贴在编码框上沿。
        // 不参与键盘 / 候选栏 / 编码框的约束链，所以不会改变它们的位置与高度。
        add(
            ocrCardHost,
            lParams(matchParent, wrapContent) {
                bottomToTop = preedit.ui.root.id
                startOfParent()
                endOfParent()
            },
        )

        add(
            schemaHintHost,
            lParams(matchParent, wrapContent) {
                bottomToTop = preedit.ui.root.id
                startOfParent()
                endOfParent()
            },
        )

        add(
            keyboardView,
            lParams(matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
            },
        )

        keyboardView.clipToOutline = true
        keyboardView.outlineProvider = keyboardOutlineProvider

        add(
            floatingLeftHandle,
            lParams(dp(10), dp(10)) {
                startOfParent()
                topOfParent()
            },
        )
        add(
            floatingRightHandle,
            lParams(dp(10), dp(10)) {
                startOfParent()
                topOfParent()
            },
        )
        add(
            floatingBottomHandle,
            lParams(dp(10), dp(10)) {
                startOfParent()
                topOfParent()
            },
        )
        add(
            adjustableHandle,
            lParams(dp(24), dp(24)) {
                startOfParent()
                topOfParent()
            },
        )
        updateFloatingHandlesVisibility()

        add(
            popup.root,
            lParams(matchParent, matchParent) {
                centerInParent()
            },
        )

        inputBar.view.setOnTouchListener { v, event ->
            if (!isFloating) return@setOnTouchListener false
            v.parent?.requestDisallowInterceptTouchEvent(true)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastTouchX
                    val dy = event.rawY - lastTouchY
                    keyboardView.translationX += dx
                    keyboardView.translationY += dy
                    clampFloatingPosition()
                    updateFloatingOverlayTranslation()
                    updateHandlePosition()
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    saveFloatingPosition(
                        keyboardView.translationX.toInt(),
                        keyboardView.translationY.toInt(),
                    )
                    true
                }

                else -> false
            }
        }

        appPrefs.keyboard.floatingSyncInput.registerOnChangeListener(floatingSyncInputListener)
        applyFloatingLayout()
    }

    private fun updateKeyboardSize() {
        if (isEffectiveFloating) {
            val marginPx = dp(2)
            val bottomPx = dp(2)
            bottomPaddingSpace.visibility = View.VISIBLE
            bottomPaddingSpace.updateLayoutParams {
                height = bottomPx
            }
            leftPaddingSpace.visibility = View.VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = marginPx
            }
            rightPaddingSpace.visibility = View.VISIBLE
            rightPaddingSpace.updateLayoutParams {
                width = marginPx
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = ConstraintLayout.LayoutParams.UNSET
                endToEnd = ConstraintLayout.LayoutParams.UNSET
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
            inputBar.view.setPadding(marginPx, 0, marginPx, 0)
            return
        }

        bottomPaddingSpace.visibility = View.VISIBLE
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }

        if (isDockedOneHandMode) {
            val containerWidth = keyboardView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val oneHandWidth = resolveOneHandWidth().coerceAtMost(containerWidth)
            val remaining = (containerWidth - oneHandWidth).coerceAtLeast(0)

            leftPaddingSpace.visibility = View.VISIBLE
            rightPaddingSpace.visibility = View.VISIBLE
            if (oneHandOnRight) {
                leftPaddingSpace.updateLayoutParams {
                    width = remaining
                }
                rightPaddingSpace.updateLayoutParams {
                    width = 0
                }
            } else {
                leftPaddingSpace.updateLayoutParams {
                    width = 0
                }
                rightPaddingSpace.updateLayoutParams {
                    width = remaining
                }
            }

            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = ConstraintLayout.LayoutParams.UNSET
                endToEnd = ConstraintLayout.LayoutParams.UNSET
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
            inputBar.view.setPadding(
                if (oneHandOnRight) remaining else 0,
                0,
                if (oneHandOnRight) 0 else remaining,
                0,
            )
            syncOneHandHandleUi()
            updateHandlePosition()
            updateOneHandGapScale()
            return
        }

        val sidePadding = keyboardSidePaddingPx
        if (sidePadding == 0) {
            leftPaddingSpace.visibility = View.GONE
            rightPaddingSpace.visibility = View.GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = ConstraintLayout.LayoutParams.UNSET
                endToStart = ConstraintLayout.LayoutParams.UNSET
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = View.VISIBLE
            rightPaddingSpace.visibility = View.VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = ConstraintLayout.LayoutParams.UNSET
                endToEnd = ConstraintLayout.LayoutParams.UNSET
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        inputBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    fun startInput(
        info: EditorInfo,
        restarting: Boolean = false,
    ) {
        updateEnterKeyLabel(info)
        broadcaster.onStartInput(info)
        if (!restarting) {
            // 刚才停在手写板的话，这里要把它挂回来，而不是无脑切回键盘，
            // 否则切 App / 换输入框手写状态就丢了。
            // 注意：已经在手写板上时不要再 attach 一次——重复挂载会重建视图。
            // 刚才停在识字卡片上：窗口重建后先把卡片放回去（键盘仍在下面，卡片浮在上面）
            val cardText = OcrCard.pendingText
            val wantHandwriting = HandwritingWindow.active
            // 相册选图时输入法会被系统收起，回来后要把「拍」的面板接回来（图上还留着），
            // 否则会落到字母键盘，用户得再点一次「拍」。
            val wantOcr = OcrWindow.shouldRestore()
            // 刚才停在 AI 模式面板上：窗口重建后也要接回来，别掉回字母键盘。
            // 面板自己不保存状态，onAttached 里会按当前模式重画。
            val wantAiPanel = AiPanelWindow.active
            // 刚才停在「键盘与方案」面板上：窗口重建后同样接回来。
            val wantKeyPanel = KeyPanelWindow.active && !wantAiPanel
            // 卡片与面板互斥：卡片在的时候底下必须是键盘（卡片是浮在键盘上方的覆盖层），
            // 所以卡片优先——不能拿「面板没挂」当条件，否则切 App 会把面板顶到卡片底下。
            if (!wantHandwriting && cardText != null) {
                showOcrCard(cardText)
            } else if (!wantHandwriting && wantOcr && !windowManager.isAttached(OcrWindow.instance)) {
                windowManager.attachWindow(OcrWindow.instance)
            } else if (!wantHandwriting && !wantOcr && wantAiPanel &&
                !windowManager.isAttached(AiPanelWindow.instance)
            ) {
                windowManager.attachWindow(AiPanelWindow.instance)
            } else if (wantHandwriting && !windowManager.isAttached(HandwritingWindow.instance)) {
                windowManager.attachWindow(HandwritingWindow.instance)
            } else if (!wantHandwriting && !wantOcr && !wantAiPanel && wantKeyPanel &&
                !windowManager.isAttached(KeyPanelWindow.instance)
            ) {
                windowManager.attachWindow(KeyPanelWindow.instance)
            } else if (!wantHandwriting && !wantOcr && !wantAiPanel) {
                windowManager.attachWindow(KeyboardWindow)
            }
        }
    }

    fun updateEnterKeyLabel(info: EditorInfo) {
        enterKeyDisplay.updateLabelOnEditorInfo(info)
    }

    fun updateInputBarVisibility() {
        inputBar.updateVisibility()
    }

    /**
     * 显示拍照识字的确认卡片。
     *
     * 卡片是输入法窗口里的独立覆盖层，底边贴在编码框上沿，浮在键盘上方、盖住 App 底部；
     * 候选栏、功能键排、键盘的位置与高度都不变，卡片里的编码框位置也不变。
     */
    /** 提示卡是否正显示（Service 算 touchableRegion 时要看它）。 */
    val isSchemaHintVisible: Boolean
        get() = ::schemaHintHost.isInitialized && schemaHintHost.visibility == View.VISIBLE

    /**
     * 当前没有任何可用方案（`Rime.isEmpty()`）时，弹一次「打不出字」提示卡。
     *
     * 只弹一次：用户点过任何一个出口后 [WanxiangPrefs.hintState] 就不为 0，之后不再打扰。
     */
    fun maybeShowSchemaHint() {
        if (WanxiangPrefs.hintState != WanxiangPrefs.HINT_NONE) return
        if (schemaHintHost.visibility == View.VISIBLE) return
        val hasPackage =
            SchemaPackStore.isInstalled(SchemaPackStore.BASE_ID) ||
                SchemaPackStore.isInstalled(SchemaPackStore.PRO_ID)
        SchemaHintCard.bind(
            host = schemaHintHost,
            hasPackage = hasPackage,
            onGoWizard = {
                dismissSchemaHint(WanxiangPrefs.HINT_DISMISSED)
                runCatching { AppUtils.launchMainToWanxiangWizard(context) }
            },
            onOpenFolder = { openUserDir() },
            onOpenPackageList = {
                dismissSchemaHint(WanxiangPrefs.HINT_DISMISSED)
                runCatching { AppUtils.launchMainToPackageList(context) }
            },
            onSelfConfigConfirmed = {
                dismissSchemaHint(WanxiangPrefs.HINT_SELF_CONFIG)
            },
        )
        schemaHintHost.visibility = View.VISIBLE
        // 卡片在窗口「可见区」之外，必须让系统重算 insets，否则点击会穿透到下面的 App
        runCatching { TrimeInputMethodService.current?.updateInputViewShown() }
    }

    private fun dismissSchemaHint(state: Int) {
        WanxiangPrefs.hintState = state
        schemaHintHost.removeAllViews()
        schemaHintHost.visibility = View.GONE
        runCatching { TrimeInputMethodService.current?.updateInputViewShown() }
    }

    private fun openUserDir() {
        val dir = DataManager.userDataBaseDir
        if (!dir.exists()) dir.mkdirs()
        runCatching {
            val uri =
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    dir,
                )
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "resource/folder")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure {
            val manager =
                context.getSystemService(android.content.ClipboardManager::class.java)
            runCatching {
                manager?.setPrimaryClip(
                    android.content.ClipData.newPlainText("path", dir.absolutePath),
                )
            }
            android.widget.Toast.makeText(
                context,
                "路径已复制：" + dir.absolutePath,
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun showOcrCard(text: String) {
        BambooLog.core.d(
            "card",
            "show len=" + text.length + " host=" + System.identityHashCode(ocrCardHost) +
                " attached=" + ocrCardHost.isAttachedToWindow +
                " children=" + ocrCardHost.childCount,
        )
        windowManager.attachWindow(KeyboardWindow)
        OcrCard.bind(ocrCardHost)
        // 兜底：卡片视图没能挂进宿主时不要激活，否则键盘输入会被吞进看不见的编辑框
        if (ocrCardHost.childCount == 0) {
            BambooLog.core.d("card", "show aborted: no card view")
            return
        }
        ocrCardHost.visibility = View.VISIBLE
        OcrCard.activate(text)
        requestLayout()
        // 卡片在默认触摸区之外，需要让系统重算 insets 才能点到
        requestApplyInsets()
        ocrCardHost.post {
            BambooLog.core.d(
                "card",
                "shown active=" + OcrCard.isActive +
                    " attached=" + ocrCardHost.isAttachedToWindow +
                    " children=" + ocrCardHost.childCount,
            )
            logOcrCardLayout("shown")
            requestApplyInsets()
        }
        // 延时自检：800ms 后卡片还没显示出来，就记一条，便于真机上定位是谁把它收掉了
        ocrCardHost.postDelayed(
            {
                BambooLog.core.d(
                    "card",
                    "verify vis=" + ocrCardHost.visibility +
                        " active=" + OcrCard.isActive +
                        " children=" + ocrCardHost.childCount +
                        " attached=" + ocrCardHost.isAttachedToWindow +
                        " kbAttached=" + windowManager.isAttached(keyboardWindow),
                )
            },
            800L,
        )
    }

    /** 记录卡片布局坐标，便于在真机上定位「浮标跑出屏幕」这类问题。 */
    private fun logOcrCardLayout(stage: String) {
        runCatching {
            val root = rootView ?: return@runCatching
            fun pos(v: View): String {
                val p = IntArray(2)
                v.getLocationOnScreen(p)
                return "${p[0]},${p[1]} ${v.width}x${v.height} v=${v.visibility}"
            }
            BambooLog.core.d(
                "card",
                "$stage window=${pos(root)} sync=${pos(syncInputBox)} preedit=${pos(preedit.ui.root)} " +
                    "bar=${pos(inputBar.view)} card=${pos(ocrCardHost)} " +
                    "board=${pos(windowManager.view)} kb=${pos(keyboardView)}",
            )
        }
    }

    /** 收起拍照识字的确认卡片，回到正常键盘。 */
    fun hideOcrCard() {
        OcrCard.deactivate()
        ocrCardHost.visibility = View.GONE
        requestLayout()
        requestApplyInsets()
        ocrCardHost.post {
            logOcrCardLayout("hidden")
            requestApplyInsets()
        }
    }

    /**
     * 临时隐藏顶栏（光标栏 + 工具条），给「拍照识字」相机面板这类需要整块空间的窗口用。
     * 只被 OcrWindow 在挂载/卸载时调用；[suppressed] 为 false 时恢复成主题要求的状态。
     */
    fun setInputBarSuppressed(suppressed: Boolean) {
        if (suppressed) {
            inputBar.view.visibility = View.GONE
        } else {
            inputBar.view.visibility = View.VISIBLE
            inputBar.updateVisibility()
        }
    }

    fun updateSyncInputBox(snapshot: TargetTextSnapshot?) {
        if (!isEffectiveFloating || !floatingSyncInput || snapshot == null || snapshot.text.isEmpty()) {
            hideSyncInputBox()
            return
        }
        val display = SyncInputTextFormatter.format(snapshot)
        if (display.text.isEmpty()) {
            hideSyncInputBox()
            return
        }
        syncInputBox.update(display)
        syncInputBox.visibility = View.VISIBLE
        syncInputBox.translationX = keyboardView.translationX
        syncInputBox.translationY = keyboardView.translationY
        requestApplyInsets()
        syncInputBox.post { this@InputView.requestApplyInsets() }
    }

    fun hideSyncInputBox() {
        if (syncInputBox.visibility == View.VISIBLE) {
            syncInputBox.visibility = View.GONE
            syncInputBox.clear()
            requestApplyInsets()
        }
    }

    fun reloadColors() {
        keyboardBackground.imageDrawable = ColorManager.getDrawable("keyboard_background")
        keyboardWindow.invalidateAllCachedKeyColors()
    }

    override fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                broadcaster.onRimeSchemaUpdated(it.data)

                windowManager.attachWindow(KeyboardWindow)
            }

            is RimeMessage.OptionMessage -> {
                broadcaster.onRimeOptionUpdated(it.data)

                if (it.data.option == "_liquid_keyboard") {
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(LiquidWindow)
                        liquidWindow.setDataByIndex(0)
                    }
                }

                if (it.data.option == "_one_hand_mode") {
                    toggleOneHandMode()
                }

                if (it.data.option == "_floating_keyboard") {
                    val floating = rime.run { getRuntimeOption("_floating_keyboard") }
                    if (floating != isFloating) {
                        isFloating = floating
                        if (floating && isOneHanded) {
                            isOneHanded = false
                        }
                        popup.dismissAll()
                        applyFloatingLayout()
                    }
                    keyboardWindow.currentKeyboardView?.invalidateAllKeys()
                }
            }

            is RimeMessage.CompositionMessage -> {
                val data = if (effectiveWindowMode == PopupCandidatesMode.ALWAYS_SHOW) {
                    CompositionProto()
                } else {
                    it.data
                }
                broadcaster.onCompositionUpdate(data)
            }

            is RimeMessage.CandidateMenuMessage -> {
                broadcaster.onCandidateMenuUpdate(it.data)
            }

            is RimeMessage.CandidateListMessage -> {
                val data = if (effectiveWindowMode == PopupCandidatesMode.ALWAYS_SHOW) {
                    RimeMessage.CandidateListMessage.Data()
                } else {
                    it.data
                }
                broadcaster.onCandidateListUpdate(data)
            }

            else -> {}
        }
        broadcastKeyAppearanceUpdate()
    }

    fun updateSelection(
        start: Int,
        end: Int,
    ) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean = inputBar.handleInlineSuggestions(response)

    fun stopVoiceRecognition() {
        inputBar.stopAsrkbVoiceFromToolbar()
    }

    fun toggleOneHandMode() {
        popup.dismissAll()
        if (isFloating) {
            saveFloatingPosition(
                keyboardView.translationX.toInt(),
                keyboardView.translationY.toInt(),
            )
            isFloating = false
            applyDockedLayout()
        }
        isOneHanded = !isOneHanded
        if (isOneHanded) {
            resolveOneHandWidth()
        }
        updateFloatingHandlesVisibility()
        updateOneHandHandleVisibility()
        updateKeyboardSize()
        updateOneHandGapScale(force = true)
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        appPrefs.keyboard.floatingSyncInput.unregisterOnChangeListener(floatingSyncInputListener)
        // cancel the notification job and clear all broadcast receivers,
        // implies that InputView should not be attached again after detached.
        updateWindowViewHeightJob.cancel()
        popup.root.removeAllViews()
        inputBar.stopAsrkbVoiceFromToolbar()
        // 输入法窗口被收起/切换时，卡片必须失活：否则 commitText 的改道会让键盘打不出字
        OcrCard.deactivate()
        inputDepMgr.stop()
        super.onDetachedFromWindow()
    }

    private fun updateFloatingOverlayTranslation() {
        preedit.ui.root.translationX = keyboardView.translationX
        preedit.ui.root.translationY = keyboardView.translationY
        syncInputBox.translationX = keyboardView.translationX
        syncInputBox.translationY = keyboardView.translationY
    }

    private fun applyFloatingLayout() {
        val params = keyboardView.layoutParams as ConstraintLayout.LayoutParams
        if (isEffectiveFloating) {
            params.width = resolveFloatingWidth()
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.UNSET
            keyboardView.layoutParams = params

            layoutParams?.height = matchParent

            applyFloatingHeight()

            bottomPaddingSpace.updateLayoutParams<LayoutParams> {
                bottomMargin = 0
            }

            val (storedX, storedY) = getStoredFloatingPosition()
            if (storedX != -1 && storedY != -1) {
                keyboardView.translationX = storedX.toFloat()
                keyboardView.translationY = storedY.toFloat()
            } else if (keyboardView.translationX == 0f && keyboardView.translationY == 0f) {
                keyboardView.translationX = (resources.displayMetrics.widthPixels * 0.1).toFloat()
                keyboardView.translationY = (resources.displayMetrics.heightPixels * 0.6).toFloat()
            }

            updateHandlePosition()
            keyboardView.post {
                clampFloatingPosition()
                updateHandlePosition()
            }

            preedit.ui.root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                width = 0
                startToStart = keyboardView.id
                endToEnd = keyboardView.id
                bottomToTop = keyboardView.id
                topToBottom = ConstraintLayout.LayoutParams.UNSET
                topToTop = ConstraintLayout.LayoutParams.UNSET
                bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            }
            syncInputBox.updateLayoutParams<ConstraintLayout.LayoutParams> {
                width = 0
                startToStart = keyboardView.id
                endToEnd = keyboardView.id
                bottomToTop = preedit.ui.root.id
                topToBottom = ConstraintLayout.LayoutParams.UNSET
                topToTop = ConstraintLayout.LayoutParams.UNSET
                bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            }
            updateFloatingOverlayTranslation()
        } else {
            applyDockedLayout()
        }
        updateKeyboardSize()
        if (!isEffectiveFloating) {
            requestApplyInsets()
        }
        updateHandlePosition()
        updateFloatingHandlesVisibility()
        updateOneHandHandleVisibility()
        keyboardView.invalidateOutline()
        requestLayout()
        if (isEffectiveFloating) {
            service.scheduleFloatingSyncInputUpdate(0L)
        } else {
            hideSyncInputBox()
        }
    }

    private fun applyDockedLayout() {
        keyboardWindow.currentKeyboardView?.apply {
            scaleY = 1f
            scaleX = 1f
        }

        val params = keyboardView.layoutParams as ConstraintLayout.LayoutParams
        params.matchConstraintMaxWidth = ConstraintLayout.LayoutParams.UNSET
        params.matchConstraintMinWidth = ConstraintLayout.LayoutParams.UNSET
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        params.topToTop = ConstraintLayout.LayoutParams.UNSET
        params.width = matchParent
        params.startToEnd = ConstraintLayout.LayoutParams.UNSET
        params.endToStart = ConstraintLayout.LayoutParams.UNSET
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        keyboardView.layoutParams = params
        keyboardView.translationX = 0f
        keyboardView.translationY = 0f

        preedit.ui.root.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = wrapContent
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.UNSET
            bottomToTop = keyboardView.id
            topToBottom = ConstraintLayout.LayoutParams.UNSET
            topToTop = ConstraintLayout.LayoutParams.UNSET
            bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        }
        preedit.ui.root.translationX = 0f
        preedit.ui.root.translationY = 0f
        syncInputBox.visibility = View.GONE
        syncInputBox.translationX = 0f
        syncInputBox.translationY = 0f

        keyboardView.invalidateOutline()
    }

    private fun applyFloatingWidth() {
        keyboardView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = resolveFloatingWidth()
        }
        keyboardView.invalidateOutline()
        requestLayout()
        updateHandlePosition()
    }

    private fun applyFloatingHeight() {
        val targetHeight = resolveFloatingHeight()
        windowManager.view.updateLayoutParams {
            height = targetHeight
        }
        applyKeyboardViewScale()
        keyboardView.invalidateOutline()
        requestLayout()
        updateHandlePosition()
    }

    private fun applyKeyboardViewScale() {
        val targetHeight = resolveFloatingHeight()
        val keyboard = runCatching { KeyboardWindow.currentKeyboard }.getOrNull()
        val kv = keyboardWindow.currentKeyboardView
        if (keyboard != null && kv != null && targetHeight > 0) {
            val layoutHeight = keyboard.keyboardHeight
            if (layoutHeight > 0) {
                kv.scaleY = targetHeight.toFloat() / layoutHeight.toFloat()
                kv.pivotY = 0f
            }
        }
    }

    private fun updateFloatingHandlesVisibility() {
        if (isEffectiveFloating) {
            floatingLeftHandle.visibility = View.VISIBLE
            floatingRightHandle.visibility = View.VISIBLE
            floatingBottomHandle.visibility = View.VISIBLE
            adjustableHandle.visibility = View.VISIBLE
        } else {
            floatingLeftHandle.visibility = View.GONE
            floatingRightHandle.visibility = View.GONE
            floatingBottomHandle.visibility = View.GONE
            adjustableHandle.visibility = View.GONE
        }
    }

    private fun updateHandlePosition() {
        if (!isFloating) return

        val kX = keyboardView.translationX
        val kY = keyboardView.translationY
        val kWidth = resolveFloatingWidth()
        val kHeight = if (keyboardView.height > 0) {
            keyboardView.height
        } else {
            resolveFloatingHeight() + dp(inputBar.themedHeight) + dp(2)
        }

        val handleThickness = dp(6)
        val handleLength = dp(48)
        val touchPadding = dp(12)
        val viewThickness = handleThickness + touchPadding * 2
        val viewLength = handleLength + touchPadding * 2

        floatingLeftHandle.translationX = kX - viewThickness / 2
        floatingLeftHandle.translationY = kY + (kHeight - viewLength) / 2
        val leftDrawable = createHandleDrawable(shape = GradientDrawable.RECTANGLE)
        floatingLeftHandle.background = InsetDrawable(
            leftDrawable,
            touchPadding,
            touchPadding,
            touchPadding,
            touchPadding,
        )
        floatingLeftHandle.updateLayoutParams {
            width = viewThickness
            height = viewLength
        }

        floatingRightHandle.translationX = kX + kWidth - viewThickness / 2
        floatingRightHandle.translationY = kY + (kHeight - viewLength) / 2
        val rightDrawable = createHandleDrawable(shape = GradientDrawable.RECTANGLE)
        floatingRightHandle.background = InsetDrawable(
            rightDrawable,
            touchPadding,
            touchPadding,
            touchPadding,
            touchPadding,
        )
        floatingRightHandle.updateLayoutParams {
            width = viewThickness
            height = viewLength
        }

        floatingBottomHandle.translationX = kX + (kWidth - viewLength) / 2
        floatingBottomHandle.translationY = kY + kHeight - viewThickness / 2
        val bottomDrawable = createHandleDrawable(shape = GradientDrawable.RECTANGLE)
        floatingBottomHandle.background = InsetDrawable(
            bottomDrawable,
            touchPadding,
            touchPadding,
            touchPadding,
            touchPadding,
        )
        floatingBottomHandle.updateLayoutParams {
            width = viewLength
            height = viewThickness
        }

        val moveHandleSize = dp(24)
        adjustableHandle.translationX = kX + (kWidth - moveHandleSize) / 2
        adjustableHandle.translationY = kY - moveHandleSize - dp(8)
        val moveBgDrawable = createHandleDrawable(moveHandleSize / 2f)
        val moveIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_baseline_move_handle_cross_24)?.mutate()
        val finalDrawable = if (moveIconDrawable != null) {
            moveIconDrawable.setTint(handleColor())
            val inset = dp(4)
            val ld = LayerDrawable(arrayOf(moveBgDrawable, moveIconDrawable))
            ld.setLayerInset(1, inset, inset, inset, inset)
            ld
        } else {
            moveBgDrawable
        }
        adjustableHandle.background = finalDrawable
        adjustableHandle.updateLayoutParams {
            width = moveHandleSize
            height = moveHandleSize
        }
    }

    private fun clampFloatingPosition() {
        if (!isEffectiveFloating) return
        val containerWidth = if (width > 0) width else resources.displayMetrics.widthPixels
        val containerHeight = if (height > 0) height else resources.displayMetrics.heightPixels
        val keyboardWidth = if (keyboardView.width > 0) keyboardView.width else resolveFloatingWidth()
        val keyboardHeight = if (keyboardView.height > 0) {
            keyboardView.height
        } else {
            resolveFloatingHeight() + dp(inputBar.themedHeight) + dp(2) + keyboardBottomPaddingPx
        }

        val maxX = (containerWidth - keyboardWidth).coerceAtLeast(0)
        val maxY = (containerHeight - keyboardHeight).coerceAtLeast(0)
        val clampedX = keyboardView.translationX.coerceIn(0f, maxX.toFloat())
        val clampedY = keyboardView.translationY.coerceIn(0f, maxY.toFloat())

        if (clampedX != keyboardView.translationX || clampedY != keyboardView.translationY) {
            keyboardView.translationX = clampedX
            keyboardView.translationY = clampedY
        }
        updateFloatingOverlayTranslation()
    }

    /**
     * 拍照识字确认卡片的触摸区 = 键盘矩形 ∪ 卡片矩形。
     *
     * 卡片是浮在键盘上方的覆盖层，落在窗口「可见区」之外，默认触摸会穿透到 App；
     * 卡片激活时由 [TrimeInputMethodService.onComputeInsets] 把这块并进 touchableRegion。
     */
    fun getOcrCardTouchRegion(outRegion: Region) {
        val rect = Rect()
        keyboardView.getHitRect(rect)
        if (ocrCardHost.visibility == View.VISIBLE) {
            val cardRect = Rect()
            ocrCardHost.getHitRect(cardRect)
            rect.union(cardRect)
        }
        outRegion.set(rect)
    }

    /**
     * 虚拟键盘下「键盘 + 编码行 + 提示块」的合并触摸区。
     *
     * 提示块浮在键盘上方，落在窗口可见区之外；不并进来，手指按在那儿会被
     * 系统判成点到了下面的 App（表现为滑动变成滑编辑区）。
     */
    fun getInputTouchRegion(outRegion: Region) {
        val rect = Rect()
        keyboardView.getHitRect(rect)
        if (preedit.ui.root.visibility == View.VISIBLE) {
            val preeditRect = Rect()
            preedit.ui.root.getHitRect(preeditRect)
            rect.union(preeditRect)
        }
        // 提示块（文曲星 / 外语学习）浮在键盘上方、会随展开/收起变高变矮。
        // 它可见时直接把窗口上半区整体并进来（top 归零），
        // 这样高度变化不用重新通知系统算 insets，也不会出现「新长出来那截点不到」。
        if (PreeditUi.tipsTouchable) {
            rect.top = 0
        }
        // 「打不出字」提示卡也浮在键盘上方，不并进来就点不到（会穿透给下面的 App）
        if (isSchemaHintVisible) {
            val hintRect = Rect()
            schemaHintHost.getHitRect(hintRect)
            rect.union(hintRect)
        }
        if (ocrCardHost.visibility == View.VISIBLE) {
            val cardRect = Rect()
            ocrCardHost.getHitRect(cardRect)
            rect.union(cardRect)
        }
        outRegion.set(rect)
    }

    fun getFloatingKeyboardRegion(outRegion: Region) {
        if (!isEffectiveFloating) return
        val rect = Rect()

        keyboardView.getHitRect(rect)

        if (preedit.ui.root.visibility == View.VISIBLE) {
            val preeditRect = Rect()
            preedit.ui.root.getHitRect(preeditRect)
            rect.union(preeditRect)
        }

        if (syncInputBox.visibility == View.VISIBLE) {
            val syncRect = Rect()
            syncInputBox.getHitRect(syncRect)
            rect.union(syncRect)
        }

        if (floatingRightHandle.visibility == View.VISIBLE) {
            val handleRect = Rect()
            floatingRightHandle.getHitRect(handleRect)
            rect.union(handleRect)
        }

        if (floatingBottomHandle.visibility == View.VISIBLE) {
            val handleRect = Rect()
            floatingBottomHandle.getHitRect(handleRect)
            rect.union(handleRect)
        }

        if (adjustableHandle.visibility == View.VISIBLE) {
            val handleRect = Rect()
            adjustableHandle.getHitRect(handleRect)
            rect.union(handleRect)
        }

        outRegion.set(rect)
    }
}
