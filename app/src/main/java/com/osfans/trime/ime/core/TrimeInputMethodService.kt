/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.actions.bamboo.AutoPair
import com.osfans.trime.actions.bamboo.BambooActions
import com.osfans.trime.actions.wenquxing.WenquxingStore
import com.osfans.trime.actions.wenquxing.WenquxingTips
import com.osfans.trime.actions.ocr.OcrCard
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.KeyValue
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateProvider
import com.osfans.trime.data.stats.InputStatsRepository
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.composition.CandidatesView
import com.osfans.trime.ime.composition.PreeditUi
import com.osfans.trime.ime.composition.TargetTextSnapshot
import com.osfans.trime.ime.hardware.HardwareStatusBar
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.link.SherpaSpeechClient
import com.osfans.trime.receiver.RimeIntentReceiver
import com.osfans.trime.util.any
import com.osfans.trime.util.findSectionFrom
import com.osfans.trime.util.forceShowSelf
import com.osfans.trime.util.monitorCursorAnchor
import com.osfans.trime.util.styledFloat
import com.osfans.trime.util.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.bitflags.hasFlag
import splitties.systemservices.clipboardManager
import splitties.systemservices.inputMethodManager
import timber.log.Timber

/** [輸入法][InputMethodService]主程序  */

private const val TEXT_SNAPSHOT_LIMIT = 20_000

open class TrimeInputMethodService : LifecycleInputMethodService() {
    private lateinit var rime: RimeSession
    private val jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    companion object {
        /**
         * The live service instance, or null while no input session exists.
         *
         * Used by the 苍竹 enhancements (`actions.bamboo.BambooActions`) to reach the
         * current editor. It is only a way to *find* the current session: nothing may
         * cache the InputConnection itself, and every consumer must re-read
         * `currentInputConnection`.
         */
        @Volatile
        var current: TrimeInputMethodService? = null
            private set
    }

    private val prefs = AppPrefs.defaultInstance()
    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    private lateinit var lastKnownConfig: Configuration
    private var inputView: InputView? = null

    /** 当前的 InputView（拍照识字窗口用它隐藏顶栏）。 */
    internal val currentInputView: InputView?
        get() = inputView
    private var candidatesView: CandidatesView? = null

    /** 让屏幕上那两份编码区都重算一次（提示块自己按当前状态决定画不画）。 */
    fun refreshCandidatesPreedit() {
        runCatching { currentInputView?.refreshPreedit() }
            .onFailure { android.util.Log.w("wenquxing", "refresh input preedit failed", it) }
        runCatching { candidatesView?.refreshPreedit() }
            .onFailure { android.util.Log.w("wenquxing", "refresh candidates preedit failed", it) }
    }
    /** 外接键盘的浮动状态条，只在硬件键盘连接时存在。 */
    private var hardwareStatusBar: HardwareStatusBar? = null
    private val navBarManager = NavigationBarManager()
    val inputDeviceManager = InputDeviceManager { useVirtualKeyboard, useCandidatesView ->
        postRimeJob {
            setCandidatePagingMode(useCandidatesView)
        }
        currentInputConnection?.monitorCursorAnchor(useCandidatesView)
        window.window?.let {
            navBarManager.evaluate(it, useVirtualKeyboard)
        }
        updateHardwareStatusBar(useVirtualKeyboard)
    }
    /**
     * 硬件键盘连接时浮出状态条，断开时收起。
     *
     * 状态条单独用 [android.widget.PopupWindow] 浮在屏幕上：硬件键盘下输入法窗口不显示，
     * 挂在 InputView 里会看不见。
     */
    private fun updateHardwareStatusBar(useVirtualKeyboard: Boolean) {
        if (useVirtualKeyboard) {
            hardwareStatusBar?.hide()
            return
        }
        val bar = hardwareStatusBar ?: HardwareStatusBar(this, rime).also { hardwareStatusBar = it }
        bar.show()
    }

    private val rimeIntentReceiver = RimeIntentReceiver()

    private var lastCommittedText: String = ""

    @Volatile
    var lastDismissedUri: String? = null

    private var composingText: String = ""
    private var cursorComposingText: String = ""

    private var cursorUpdateIndex = 0

    private val syncInputHandler = Handler(Looper.getMainLooper())
    private val syncInputUpdateRunnable = Runnable { refreshFloatingSyncInputNow() }

    private val recreateInputViewPrefs: Array<PreferenceDelegate<*>> = arrayOf(
        prefs.keyboard.hideInputBar,
        prefs.advanced.ignoreSystemGestureInsets,
        prefs.advanced.customGestureInsetHeight,
    )

    @Keep
    private val recreateInputViewListener =
        PreferenceDelegate.OnChangeListener<Any> { _, _ ->
            replaceInputView(ThemeManager.activeTheme)
        }

    @Keep
    private val recreateCandidatesViewListener =
        PreferenceDelegateProvider.OnChangeListener { key ->
            if (key == AppPrefs.Candidates.DISABLE_WINDOW_ON_LANDSCAPE) {
                inputDeviceManager.reapplyWindowMode(this)
                inputView?.updateInputBarVisibility()
            } else {
                replaceCandidateView(ThemeManager.activeTheme)
            }
        }

    @Keep
    private val onThemeChangeListener =
        ThemeManager.OnThemeChangeListener {
            replaceInputViews(it)
        }

    @Keep
    private val onColorChangeListener =
        ColorManager.OnColorChangeListener {
            ContextCompat.getMainExecutor(this).execute {
                replaceInputViews(it)
            }
        }

    private fun invalidateInputViewColors(theme: Theme) {
        navBarManager.evaluate(window.window!!, inputDeviceManager.useVirtualKeyboard)
        inputView?.reloadColors()
        candidatesView?.invalidateAllColors()
        inputView?.updateEnterKeyLabel(currentInputEditorInfo)
    }

    private fun CandidatesView.invalidateAllColors() {
        invalidate()
    }

    private fun postJob(
        scope: CoroutineScope,
        block: suspend () -> Unit,
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        jobs.trySend(job)
        return job
    }

    /**
     * Post a rime operation to [jobs] to be executed
     *
     * Unlike `rime.runOnReady` or `rime.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postRimeJob] ensures that operations are executed sequentially.
     */
    fun postRimeJob(block: suspend RimeApi.() -> Unit) = postJob(rime.lifecycleScope) { rime.runOnReady(block) }

    fun postKeyJob(block: suspend RimeApi.() -> Unit) {
        rime.lifecycleScope.launch { rime.runOnReady(block) }
    }

    private suspend fun updateRimeOption(api: RimeApi) {
        try {
            api.setRuntimeOption("soft_cursor", prefs.keyboard.useSoftCursor.getValue()) // 軟光標
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun registerReceiver() {
        val intentFilter =
            IntentFilter().apply {
                addAction(RimeIntentReceiver.ACTION_DEPLOY)
                addAction(RimeIntentReceiver.ACTION_SYNC_USER_DATA)
            }
        ContextCompat.registerReceiver(
            this,
            rimeIntentReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCreate() {
        current = this
        BambooActions.init(this)
        WenquxingStore.ensureLoaded(this)
        rime = RimeDaemon.createSession(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            rime.run { messageFlow }.collect {
                handleRimeMessage(it)
            }
        }
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.init(resources.configuration)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        ColorManager.addOnChangedListener(onColorChangeListener)
        InputFeedbackManager.init(this)
        registerReceiver()
        super.onCreate()
        Timber.d("onCreate")
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        lastKnownConfig = Configuration(resources.configuration)
    }

    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.CommitTextMessage -> {
                if (!it.data.text.isNullOrEmpty()) {
                    if (OcrCard.isActive) {
                        // 拍照识字的确认卡片正在接管输出：写进卡片，不上屏、不记账
                        OcrCard.append(it.data.text)
                    } else {
                        commitText(it.data.text)
                    }
                }
            }

            is RimeMessage.InlinePreeditMessage -> {
                updateComposingText(it.data, it.cursorPos)
            }

            is RimeMessage.KeyMessage ->
                it.data.let msg@{
                    if (it.isVirtual) {
                        when (it.value.value) {
                            RimeKeyMapping.RimeKey_Return -> handleReturnKey()

                            else -> {
                                val keyCode = it.value.keyCode
                                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                                    // 确认卡片接管时，退格/回车改到卡片里，不发给目标 App
                                    if (OcrCard.isActive && keyCode == KeyEvent.KEYCODE_DEL) {
                                        OcrCard.backspace()
                                        return@msg
                                    }
                                    // 退格被回放给编辑器 = 真的删了屏幕上的字，账本要扣
                                    if (keyCode == KeyEvent.KEYCODE_DEL &&
                                        !it.modifiers.ctrl && !it.modifiers.alt && !it.modifiers.shift
                                    ) {
                                        BambooActions.onBackspaceReplayed()
                                    }
                                    // recognized keyCode
                                    // 空格是「模拟按键」插进编辑框的，不经过 commitText，账本在这里补记一次
                                    if (keyCode == KeyEvent.KEYCODE_SPACE &&
                                        !it.modifiers.ctrl && !it.modifiers.alt && !it.modifiers.meta
                                    ) {
                                        runCatching { BambooActions.onCommitted(" ") }
                                    }
                                    sendDownUpKeyEvent(
                                        keyCode,
                                        it.modifiers.metaState or meta(
                                            alt = it.modifiers.alt,
                                            shift = it.modifiers.shift,
                                            ctrl = it.modifiers.ctrl,
                                            meta = it.modifiers.meta,
                                        ),
                                    )
                                    if (it.modifiers.ctrl && keyCode == KeyEvent.KEYCODE_C) clearTextSelection()
                                } else {
                                    if (it.value.value > 0) {
                                        runCatching {
                                            commitText(Character.toString(it.value.value))
                                        }.getOrElse { t -> Timber.w(t, "Unhandled Virtual KeyEvent: $it") }
                                    } else {
                                        Timber.w("Unhandled Virtual KeyEvent: $it")
                                    }
                                }
                            }
                        }
                    } else {
                        val keyCode = it.value.keyCode
                        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            // recognized keyCode
                            val eventTime = SystemClock.uptimeMillis()
                            if (it.modifiers.release) {
                                sendUpKeyEvent(eventTime, keyCode, it.modifiers.metaState)
                            } else {
                                sendDownKeyEvent(eventTime, keyCode, it.modifiers.metaState)
                            }
                        } else {
                            if (!it.modifiers.release && it.value.value > 0) {
                                runCatching {
                                    commitText(Character.toString(it.value.value))
                                }.getOrElse { t -> Timber.w(t, "Unhandled Rime KeyEvent: $it") }
                            } else {
                                Timber.w("Unhandled Rime KeyEvent: $it")
                            }
                        }
                    }
                }

            is RimeMessage.DeployMessage -> {
                if (it.data == RimeMessage.DeployMessage.State.Success) {
                    ThemeManager.selectTheme(ThemeManager.prefs.selectedTheme.getValue(), forceReload = true)
                    inputDeviceManager.reapplyWindowMode(this)
                }
            }

            else -> {}
        }
    }

    private fun replaceInputView(theme: Theme): InputView {
        val newInputView = InputView(this, rime, theme)
        setInputView(newInputView)
        inputDeviceManager.setInputView(newInputView)
        inputView = newInputView
        scheduleFloatingSyncInputUpdate(0L)
        return newInputView
    }

    private fun replaceCandidateView(theme: Theme): CandidatesView {
        val newCandidatesView = CandidatesView(this, rime, theme)
        contentView.removeView(candidatesView)
        contentView.addView(newCandidatesView)
        inputDeviceManager.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        if (decorLocationUpdated) {
            candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
        } else {
            workaroundNullCursorAnchorInfo()
        }
        return newCandidatesView
    }

    private fun replaceInputViews(theme: Theme) {
        navBarManager.evaluate(window.window!!, inputDeviceManager.useVirtualKeyboard)
        replaceInputView(theme)
        replaceCandidateView(theme)
        inputView?.updateEnterKeyLabel(currentInputEditorInfo)
    }

    override fun onDestroy() {
        if (current === this) current = null
        InputFeedbackManager.destroy()
        InputStatsRepository.flushAsync()
        syncInputHandler.removeCallbacks(syncInputUpdateRunnable)
        inputView = null
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        ColorManager.removeOnChangedListener(onColorChangeListener)
        super.onDestroy()
        unregisterReceiver(rimeIntentReceiver)
        RimeDaemon.destroySession(javaClass.name)
    }

    private fun handleReturnKey() {
        currentInputEditorInfo.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
                imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            ) {
                runCatching { BambooActions.onCommitted("\n") }
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                return
            }
            if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                currentInputConnection.performEditorAction(actionId)
                return
            }
            when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_NONE,
                -> {
                    runCatching { BambooActions.onCommitted("\n") }
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }

                else -> currentInputConnection.performEditorAction(action)
            }
        }
    }

    /**
     * https://github.com/fcitx5-android/fcitx5-android/blob/fe3a618c8fd18842305d2f8ec2880fcc67ec1679/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt#L523-#L547
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        postRimeJob { clearComposition() }
        val keyboardUiModeMask = ActivityInfo.CONFIG_KEYBOARD or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
            ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        val orientationChanged = lastKnownConfig.orientation != newConfig.orientation
        if (diff and keyboardUiModeMask != diff) {
            super.onConfigurationChanged(newConfig)
        }
        if (orientationChanged) {
            inputDeviceManager.reapplyWindowMode(this)
            inputView?.updateInputBarVisibility()
            if (prefs.keyboard.autoFloatLandscape.getValue()) {
                val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
                postRimeJob { setRuntimeOption("_floating_keyboard", isLandscape) }
            }
        }
        lastKnownConfig.setTo(newConfig)
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    private fun updateDecorLocation() {
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] =
            if (inputDeviceManager.useVirtualKeyboard) {
                inputViewLocation[1].toFloat()
            } else {
                contentView.height.toFloat()
            }
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    private val anchorPosition = RectF()

    private fun workaroundNullCursorAnchorInfo() {
        anchorPosition.set(0f, contentSize[1], 0f, contentSize[1])
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val bounds = info.getCharacterBounds(0)
        // update anchorPosition
        if (bounds == null) {
            // composing is disabled in target app or trime settings
            // use the position of the insertion marker instead
            anchorPosition.top = info.insertionMarkerTop
            anchorPosition.left = info.insertionMarkerHorizontal
            anchorPosition.bottom = info.insertionMarkerBottom
            anchorPosition.right = info.insertionMarkerHorizontal
        } else {
            // for different writing system (e.g. right to left languages),
            // we have to calculate the correct RectF
            val horizontal = if (candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            anchorPosition.top = bounds.top
            anchorPosition.left = horizontal
            anchorPosition.bottom = bounds.bottom
            anchorPosition.right = horizontal
        }
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            workaroundNullCursorAnchorInfo()
            return
        }
        info.matrix.mapRect(anchorPosition)
        val (dX, dY) = decorLocation
        anchorPosition.offset(-dX, -dY)
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        Timber.d("onUpdateSelection: old=($oldSelStart,$oldSelEnd) new=($newSelStart,$newSelEnd) candidates=($candidatesStart,$candidatesEnd) composing='$composingText'")
        cursorUpdateIndex += 1
        handleCursorUpdate(newSelStart, newSelEnd, candidatesStart, candidatesEnd, cursorUpdateIndex)
        inputView?.updateSelection(newSelStart, newSelEnd)
        scheduleFloatingSyncInputUpdate()
    }

    private fun handleCursorUpdate(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        updateIndex: Int,
    ) {
        if (newSelStart != newSelEnd) return
        if (candidatesStart == candidatesEnd) return
        if (newSelStart in candidatesStart..candidatesEnd) {
            val position = newSelStart - candidatesStart
            if (composingText == cursorComposingText) {
                KeyboardWindow.dynamicController?.onCursorMoved(position)
            }
            cursorComposingText = composingText
            if (position != composingText.length) {
                postRimeJob {
                    if (updateIndex != cursorUpdateIndex) return@postRimeJob
                    Timber.d("handleCursorUpdate: move rime cursor to $position")
                    moveCursorPos(position)
                }
            }
        } else {
            Timber.d("handleCursorUpdate: clear composition")
            cursorComposingText = ""
            KeyboardWindow.dynamicController?.clear()
            postRimeJob {
                clearComposition()
            }
        }
    }

    private val inputViewLocation = intArrayOf(0, 0)

    override fun onComputeInsets(outInsets: Insets) {
        val inputView = this.inputView
        if (inputView?.isFloating == true) {
            outInsets.contentTopInsets = inputView.height
            outInsets.visibleTopInsets = inputView.height
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            inputView.getFloatingKeyboardRegion(outInsets.touchableRegion)
            return
        }

        if (inputDeviceManager.useVirtualKeyboard) {
            inputView?.keyboardView?.getLocationInWindow(inputViewLocation)
            outInsets.apply {
                contentTopInsets = inputViewLocation[1]
                visibleTopInsets = inputViewLocation[1]
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
            // 拍照识字的确认卡片浮在键盘上方（窗口可见区之外），默认触摸区不覆盖它，
            // 触摸会穿透到下面的 App；卡片激活时改成「键盘 ∪ 卡片」的区域。
            // 提示块也浮在键盘上方，同样要并进触摸区，
            // 否则按在提示块上的拖动会穿透给下面的 App（表现为在滑编辑区）。
            if (OcrCard.isActive || PreeditUi.tipsTouchable || inputView?.isSchemaHintVisible == true) {
                outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                inputView?.getInputTouchRegion(outInsets.touchableRegion)
            }
        } else {
            val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onCreateInputView(): View? {
        Timber.d("onCreateInputView")
        replaceInputViews(ThemeManager.activeTheme)
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        val inputArea = contentView.findViewById<FrameLayout>(android.R.id.inputArea)
        inputArea.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(
        win: Window,
        isFullscreen: Boolean,
        isCandidatesOnly: Boolean,
    ) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onStartInput(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        composingText = ""
        cursorComposingText = ""
        runCatching { com.osfans.trime.actions.wenquxing.WenquxingTips.clear() }
        // 换了编辑框：让外语学习提示块把展开态退掉（编号只增不减，用于跨实例判定）。
        PreeditUi.sessionToken++
        BambooActions.onEditorStart(attribute, restarting)
        Timber.d("onStartInput: restarting=$restarting")
        val isNullType = attribute.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        postRimeJob {
            if (restarting) {
                // when input restarts in the same editor, clear previous composition
                clearComposition()
            }
            setNullInputType(isNullType)
        }
    }

    private val inlineSuggestions by prefs.general.inlineSuggestions

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!inlineSuggestions || !inputDeviceManager.useVirtualKeyboard) return null
        return InlineSuggestions.createRequest(this)
    }

    @SuppressLint("NewApi")
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inputDeviceManager.useVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onStartInputView(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        Timber.d("onStartInputView: restarting=$restarting")
        InputFeedbackManager.startInput()
        postRimeJob {
            updateRimeOption(this)
            // 没有任何可用方案时，弹一次「打不出字」提示卡（只弹一次）
            val empty = runCatching { isEmpty() }.getOrDefault(false)
            if (empty) {
                lifecycleScope.launch {
                    runCatching { inputView?.maybeShowSchemaHint() }
                }
            }
        }
        val (useVirtualKeyboard, useCandidatesView) =
            inputDeviceManager.evaluateOnStartInputView(attribute, this)
        if (useVirtualKeyboard) {
            inputView?.startInput(attribute, restarting)
            scheduleFloatingSyncInputUpdate(0L)
        }
        if (useCandidatesView) {
            if (currentInputConnection?.monitorCursorAnchor() != true) {
                if (!decorLocationUpdated) {
                    updateDecorLocation()
                }
                workaroundNullCursorAnchorInfo()
            }
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // 离开编辑框：外语学习提示块不再保留展开态。
        PreeditUi.sessionToken++
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // 输入法窗口被隐藏（收起键盘 / 切后台）：同样视为换了会话。
        PreeditUi.sessionToken++
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // 窗口收起：展开态不再保留。
        PreeditUi.sessionToken++
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        inputView?.dismissCandidateActionMenu()
        KeyboardWindow.sidebarController?.clear()
        decorLocationUpdated = false
        inputDeviceManager.onFinishInputView()
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        composingText = ""
        cursorComposingText = ""
        postRimeJob {
            clearComposition()
        }
        SherpaSpeechClient.forceStop()
        inputView?.stopVoiceRecognition()
        InputFeedbackManager.finishInput()
        InputStatsRepository.flushAsync()
        cancelFloatingSyncInputUpdate()
    }

    fun commitText(text: String) {
        // 任何上屏（空格、回车、点候选、我们自己的键）都要把文曲星提示清掉
        runCatching { com.osfans.trime.actions.wenquxing.WenquxingTips.clear() }
        // 确认卡片接管时，文本先写进卡片（「上屏」按钮会临时 bypass）
        if (OcrCard.isActive && !OcrCard.bypass) {
            OcrCard.append(text)
            return
        }
        val ic = currentInputConnection ?: return

        // 成对符号自动补全：只接手「单个字符、当前不在组句」的提交，其余原样走下面的老逻辑。
        val pairAction =
            if (composingText.isEmpty()) {
                runCatching { AutoPair.plan(ic, text, currentInputEditorInfo) }
                    .getOrDefault(AutoPair.Action.Pass)
            } else {
                AutoPair.Action.Pass
            }
        if (pairAction is AutoPair.Action.SkipRight) {
            // 光标后面已经是对称的右半边：只把光标挪过去，不重复输入，也不记账
            val caret = absoluteCaret(ic)
            moveCaretTo(ic, if (caret >= 0) caret + 1 else -1, 1)
            scheduleFloatingSyncInputUpdate()
            return
        }
        val insert = pairAction as? AutoPair.Action.Insert
        val committed = insert?.text ?: text
        // 绝对光标位必须在提交「之前」读：提交之后再读可能拿到旧值，算错就会把光标放到别处
        val caretBefore = if (insert != null) absoluteCaret(ic) else -1

        // 记账放在这个唯一漏斗里：引擎提交、手写、拍照、剪贴板、文曲星、主题里的 commit 键
        // 最终都走这里，qq/ 回退才能删到它们。
        runCatching { BambooActions.onCommitted(committed) }
            .onFailure { Timber.w(it, "commit: ledger record failed") }

        // when composing text equals commit content, finish composing text as-is
        if (composingText.isNotEmpty() && composingText == text) {
            ic.finishComposingText()
        } else {
            ic.commitText(committed, 1)
        }
        if (insert != null) {
            // 光标要停在「左半边之后」：插入起点 + 左半边长度（即整串长度减去要退的格数）
            val caretTarget =
                if (caretBefore >= 0) caretBefore + insert.text.length - insert.caretBack else -1
            moveCaretTo(ic, caretTarget, -insert.caretBack)
            // 有的编辑框会在 commit 之后异步把光标推到末尾，延时确认一次
            settleCaret(
                ic,
                caretTarget,
                if (caretBefore >= 0) caretBefore + insert.text.length else -1,
            )
        }
        lastCommittedText = committed
        composingText = ""
        cursorComposingText = ""
        InputFeedbackManager.textCommitSpeak(committed)
        scheduleFloatingSyncInputUpdate()
    }

    /**
     * 成对符号补全用：读编辑框的绝对光标位置（文档坐标）。
     *
     * 读不到、或者当前是「一段选区」而不是单一光标时返回 -1 —— 这种情况下调用方不做补全，
     * 因为没法保证光标能停到正确的位置，宁可不补也不要把光标放到别处。
     */
    private fun absoluteCaret(ic: InputConnection): Int =
        runCatching {
            val req = ExtractedTextRequest().apply { token = 0 }
            val et = ic.getExtractedText(req, 0) ?: return@runCatching -1
            if (et.selectionStart < 0 || et.selectionStart != et.selectionEnd) return@runCatching -1
            // 坐标口径与 clearTextSelection/移到下一段那两处保持一致
            et.startOffset + et.selectionStart
        }.getOrDefault(-1)

    /**
     * 成对符号补全用：把光标移到绝对位置 [absolute]。
     *
     * 主路径是 [InputConnection.setSelection] 绝对定位 —— 方向键事件（DPAD_LEFT/RIGHT）在大量
     * 自绘输入框里会被直接丢掉，1.x 版本就是踩了这个坑（符号补上了、光标没回中间）。
     * 只有在拿不到绝对位置时才退回方向键：[steps] 正数右移、负数左移。
     */
    private fun moveCaretTo(
        ic: InputConnection,
        absolute: Int,
        steps: Int,
    ) {
        if (absolute >= 0) {
            val ok = runCatching { ic.setSelection(absolute, absolute) }.getOrDefault(false)
            Timber.d("autopair: setSelection($absolute) accepted=$ok")
            return
        }
        val count = if (steps >= 0) steps else -steps
        val code = if (steps >= 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(count) { runCatching { sendDownUpKeyEvents(code) } }
        Timber.d("autopair: no absolute caret, dpad fallback code=$code x$count")
    }

    /**
     * 成对符号补全用：延时再确认一次光标位置。
     *
     * 有些编辑框在 `commitText` 之后会异步把光标挪到文本末尾，把刚才的定位覆盖掉。
     * 这里只在「光标恰好停在 [ifLostAt]」时才补设一次 —— 也就是确认我们的定位被吃掉了，
     * 并且用户在这几十毫秒内没有继续打字；否则什么都不做，不抢用户的光标。
     * 绝对定位是幂等的，重复设同一个位置没有副作用。
     */
    private fun settleCaret(
        ic: InputConnection,
        target: Int,
        ifLostAt: Int,
    ) {
        if (target < 0 || ifLostAt < 0) return
        Handler(Looper.getMainLooper()).postDelayed(
            {
                runCatching {
                    if (absoluteCaret(ic) == ifLostAt) {
                        val ok = ic.setSelection(target, target)
                        Timber.d("autopair: settleCaret -> $target accepted=$ok")
                    }
                }
            },
            80L,
        )
    }

    fun commitImage(
        uri: Uri,
        mimeType: String,
        updateClipboard: Boolean = true,
    ) {
        val ic = currentInputConnection ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var success = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                    val desc = ClipDescription("Image", arrayOf(mimeType))
                    val info = InputContentInfo(uri, desc)
                    success = ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
                }
                if (!success) {
                    if (updateClipboard) {
                        lastDismissedUri = uri.toString()
                        val clipData = ClipData.newRawUri("Image", uri)
                        clipboardManager.setPrimaryClip(clipData)
                    }
                    success = ic.performContextMenuAction(android.R.id.paste)
                }
                if (!success) {
                    withContext(Dispatchers.Main) {
                        toast(R.string.image_paste_failed)
                    }
                } else {
                    lastCommittedText = uri.toString()
                    composingText = ""
                    cursorComposingText = ""
                    withContext(Dispatchers.Main) {
                        InputFeedbackManager.textCommitSpeak(uri.toString())
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to paste image from URI")
                withContext(Dispatchers.Main) {
                    toast(R.string.image_paste_failed)
                }
            }
        }
    }

    /**
     * Constructs a meta state integer flag which can be used for setting the `metaState` field when sending a KeyEvent
     * to the input connection. If this method is called without a meta modifier set to true, the default value `0` is
     * returned.
     *
     * @param ctrl Set to true to enable the CTRL meta modifier. Defaults to false.
     * @param alt Set to true to enable the ALT meta modifier. Defaults to false.
     * @param shift Set to true to enable the SHIFT meta modifier. Defaults to false.
     *
     * @return An integer containing all meta flags passed and formatted for use in a [KeyEvent].
     */
    fun meta(
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        sym: Boolean = false,
    ): Int {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (meta) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (sym) metaState = metaState or KeyEvent.META_SYM_ON
        return metaState
    }

    private fun sendDownKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    private fun sendUpKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    /**
     * Same as [InputMethodService.sendDownUpKeyEvents] but also allows to set meta state.
     *
     * @param keyEventCode The key code to send, use a key code defined in Android's [KeyEvent].
     * @param metaState Flags indicating which meta keys are currently pressed.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun sendDownUpKeyEvent(
        keyEventCode: Int,
        metaState: Int = meta(),
    ): Boolean {
        val eventTime = SystemClock.uptimeMillis()
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }
        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        return true
    }

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        val keyVal = KeyValue.fromKeyEvent(event)
        if (keyVal.value != RimeKeyMapping.RimeKey_VoidSymbol) {
            val modifiers = KeyModifiers.fromKeyEvent(event)
            postRimeJob {
                processKey(keyVal, modifiers, isVirtual = false)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    var onSearchKeyListener: ((KeyEvent) -> Boolean)? = null
    var onSearchCommitListener: ((String) -> Boolean)? = null

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (onSearchKeyListener?.invoke(event) == true) return true
        hardwareStatusBar?.onKeyEvent(event)
        // BambooIME hook: 物理按键不经过 CommonKeyboardActionListener，这里补同一道判定。
        // 命中就吞掉执行（进模式 / 发送 / 上屏），没命中把这次按键原样补发给方案，不丢字。
        if (event.repeatCount == 0 && BambooActions.mightBeShortcut(keyCode, event.metaState)) {
            val shortcutKey = KeyValue.fromKeyEvent(event)
            val shortcutModifiers = KeyModifiers.fromKeyEvent(event)
            postKeyJob {
                val handled =
                    runCatching {
                        BambooActions.handleKey(this@TrimeInputMethodService, this, keyCode, event.metaState)
                    }.getOrDefault(false)
                if (handled) return@postKeyJob
                // BambooIME hook: 文曲星命中时，上屏键/轮换键要在主线程处理（上屏、重绘编码行）。
                ContextCompat.getMainExecutor(this@TrimeInputMethodService).execute {
                    when (val tipsKey = WenquxingTips.consumeKey(keyCode)) {
                        is WenquxingTips.KeyResult.Commit -> {
                            WenquxingTips.clear()
                            postKeyJob { clearComposition() }
                            commitText(tipsKey.text)
                        }
                        WenquxingTips.KeyResult.Rotated -> {
                            refreshCandidatesPreedit()
                        }
                        WenquxingTips.KeyResult.None -> {
                            postKeyJob { processKey(shortcutKey, shortcutModifiers, isVirtual = false) }
                        }
                    }
                }
            }
            return true
        }
        if (inputDeviceManager.evaluateOnKeyDown(event, this)) {
            decorLocationUpdated = false
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = forwardKeyEvent(event) || super.onKeyUp(keyCode, event)

    // Added in API level 14, deprecated in 29
    // it's needed because editors still use it even on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        inputDeviceManager.evaluateOnViewClicked(this)
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceManager.evaluateOnUpdateEditorToolType(toolType, this)
    }

    fun switchToPrevIme() {
        try {
            switchToPreviousInputMethod()
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the previous IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun switchToNextIme() {
        try {
            switchToNextInputMethod(false)
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the next IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun shareText(): Boolean {
        val ic = currentInputConnection ?: return false
        val cs = ic.getSelectedText(0)
        if (cs == null) ic.performContextMenuAction(android.R.id.selectAll)
        return ic.performContextMenuAction(android.R.id.shareText)
    }

    /** 編輯操作 */
    fun hookKeyboard(
        code: Int,
        mask: Int,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        // 没按下 Ctrl 键
        if (!mask.hasFlag(KeyEvent.META_CTRL_ON)) {
            return false
        }

        if (prefs.keyboard.hookCtrlZY.getValue()) {
            when (code) {
                KeyEvent.KEYCODE_Y -> return ic.performContextMenuAction(android.R.id.redo)
                KeyEvent.KEYCODE_Z -> return ic.performContextMenuAction(android.R.id.undo)
            }
        }

        when (code) {
            KeyEvent.KEYCODE_A -> {
                // 全选
                return if (prefs.keyboard.hookCtrlA.getValue()) {
                    ic.performContextMenuAction(android.R.id.selectAll)
                } else {
                    false
                }
            }

            KeyEvent.KEYCODE_X -> {
                // 剪切
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionStart != et.selectionEnd) return ic.performContextMenuAction(android.R.id.cut)
                    }
                }
                Timber.w("hookKeyboard cut fail")
                return false
            }

            KeyEvent.KEYCODE_C -> {
                // 复制
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionStart != et.selectionEnd) {
                            ic.performContextMenuAction(android.R.id.copy).also { result ->
                                if (result) {
                                    clearTextSelection()
                                }
                                return result
                            }
                        }
                    }
                }
                Timber.w("hookKeyboard copy fail")
                return false
            }

            KeyEvent.KEYCODE_V -> {
                // 粘贴
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et == null) {
                        Timber.d("hookKeyboard paste, et == null, try commitText")
                        val clipboardText = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)
                        if (ic.commitText(clipboardText, 1)) {
                            return true
                        }
                    } else if (ic.performContextMenuAction(android.R.id.paste)) {
                        return true
                    }
                    Timber.w("hookKeyboard paste fail")
                }
                return false
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (prefs.keyboard.hookCtrlLR.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionEnd)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (prefs.keyboard.hookCtrlLR.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionStart, true)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
        }
        return false
    }

    fun clearTextSelection() {
        val ic = currentInputConnection ?: return
        val etr = ExtractedTextRequest().apply { token = 0 }
        val et = currentInputConnection.getExtractedText(etr, 0)
        et?.let {
            if (it.selectionStart != it.selectionEnd) {
                ic.setSelection(it.selectionEnd, it.selectionEnd)
            }
        }
    }

    internal fun updateComposingText(text: String, cursorPos: Int = 1) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composingText.isNotEmpty() || text.isNotEmpty()) {
            if (!ic.getSelectedText(0).isNullOrEmpty()) {
                ic.deleteSurroundingText(1, 0)
            }
            ic.setComposingText(text, 1)
            if (text.isEmpty()) {
                ic.finishComposingText()
            }
        }
        composingText = text
        ic.endBatchEdit()
        scheduleFloatingSyncInputUpdate()
    }

    fun scheduleFloatingSyncInputUpdate(delayMs: Long = 50L) {
        syncInputHandler.removeCallbacks(syncInputUpdateRunnable)
        if (inputView?.isFloating != true || !prefs.keyboard.floatingSyncInput.getValue()) {
            inputView?.hideSyncInputBox()
            return
        }
        syncInputHandler.postDelayed(syncInputUpdateRunnable, delayMs)
    }

    fun cancelFloatingSyncInputUpdate() {
        syncInputHandler.removeCallbacks(syncInputUpdateRunnable)
        inputView?.hideSyncInputBox()
    }

    private fun refreshFloatingSyncInputNow() {
        val view = inputView ?: return
        if (!view.isFloating || !prefs.keyboard.floatingSyncInput.getValue()) {
            view.hideSyncInputBox()
            return
        }
        view.updateSyncInputBox(extractTargetTextSnapshot())
    }

    private fun extractTargetTextSnapshot(): TargetTextSnapshot? {
        val ic = currentInputConnection ?: return null
        val editorInfo = currentInputEditorInfo ?: return null
        val inputType = editorInfo.inputType
        if ((inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL) return null
        if (isPasswordInputType(inputType)) return null

        val request = ExtractedTextRequest().apply { token = 0 }
        val extracted = runCatching { ic.getExtractedText(request, 0) }.getOrNull()
        if (extracted?.text != null) {
            return TargetTextSnapshot(
                text = extracted.text.toString(),
                startOffset = extracted.startOffset,
                selectionStart = extracted.selectionStart,
                selectionEnd = extracted.selectionEnd,
                editable = true,
                truncatedBefore = extracted.partialStartOffset >= 0 || extracted.startOffset > 0,
                truncatedAfter = extracted.partialEndOffset >= 0,
            )
        }

        val before = runCatching { ic.getTextBeforeCursor(TEXT_SNAPSHOT_LIMIT, 0) }.getOrNull()?.toString().orEmpty()
        val after = runCatching { ic.getTextAfterCursor(TEXT_SNAPSHOT_LIMIT, 0) }.getOrNull()?.toString().orEmpty()
        val selected = runCatching { ic.getSelectedText(0) }.getOrNull()?.toString().orEmpty()
        if (before.isEmpty() && after.isEmpty() && selected.isEmpty()) return null

        return TargetTextSnapshot(
            text = before + selected + after,
            startOffset = 0,
            selectionStart = before.length,
            selectionEnd = before.length + selected.length,
            editable = before.length < TEXT_SNAPSHOT_LIMIT,
            truncatedBefore = before.length >= TEXT_SNAPSHOT_LIMIT,
            truncatedAfter = after.length >= TEXT_SNAPSHOT_LIMIT,
        )
    }

    fun setTargetSelection(
        start: Int,
        end: Int,
    ) {
        val ic = currentInputConnection ?: return
        if (!currentInputEditorInfo.isEditableTextInput()) return

        val from = minOf(start, end).coerceAtLeast(0)
        val to = maxOf(start, end).coerceAtLeast(0)
        val applySelection = {
            runCatching {
                ic.beginBatchEdit()
                ic.setSelection(from, to)
            }.onFailure {
                Timber.w(it, "setTargetSelection failed")
            }
            runCatching { ic.endBatchEdit() }
            Unit
        }

        if (rime.run { statusCached.isComposing } || composingText.isNotEmpty()) {
            rime.launchOnReady { api ->
                api.commitComposition()
                withContext(Dispatchers.Main) { applySelection() }
            }
        } else {
            applySelection()
        }
    }

    private fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

            else -> false
        }
    }

    private fun EditorInfo?.isEditableTextInput(): Boolean {
        val type = this?.inputType ?: return false
        return (type and InputType.TYPE_MASK_CLASS) != InputType.TYPE_NULL
    }

    fun getActiveText(type: Int): String {
        val rimeComposition = rime.run { compositionCached }
        val selected = currentInputConnection?.getSelectedText(0)?.toString()
        val commitPreview = rimeComposition.commitTextPreview
        val preedit = rimeComposition.preedit ?: ""
        val beforeCursor = getTextAroundCursor(1024, before = true) ?: ""
        val afterCursor = getTextAroundCursor(before = false) ?: ""
        val lastCommitted = lastCommittedText

        return sequenceOf(
            when (type) {
                2 -> preedit
                3 -> selected
                4 -> beforeCursor
                1 -> lastCommitted
                else -> null
            },
            commitPreview,
            selected,
            lastCommitted,
            beforeCursor,
            afterCursor,
        )
            .firstOrNull { it?.isNotEmpty() == true } ?: ""
    }

    private fun getTextAroundCursor(
        initialStep: Int = 1024,
        before: Boolean,
    ): String? {
        val ic = currentInputConnection ?: return null
        var step = initialStep
        while (true) {
            val text = (if (before) ic.getTextBeforeCursor(step, 0) else ic.getTextAfterCursor(step, 0)) ?: return ""
            if (text.length < step) return text.toString()
            step *= 2
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            )
            it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
        }
        dialog.setOnDismissListener {
            showingDialog = null
        }
        dialog.show()
        showingDialog = dialog
    }
}
