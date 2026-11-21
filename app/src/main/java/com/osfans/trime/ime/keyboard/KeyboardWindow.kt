// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import com.osfans.trime.actions.wanxiang.WanxiangKeyboard
import com.osfans.trime.actions.wanxiang.WanxiangPrefs
import com.osfans.trime.R
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dynamic.DynamicController
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.sidebar.SidebarInputController
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.ResidentWindow
import com.osfans.trime.util.isLandscape
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kodein.di.instance
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber
import com.osfans.trime.util.BambooLog

class KeyboardWindow :
    BoardWindow.NoBarBoardWindow(),
    ResidentWindow,
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by di.instance()
    private val theme: Theme by di.instance()
    private val rime: RimeSession by di.instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by di.instance()
    private val popup: PopupDelegate by di.instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by di.instance()
    private val inputBarDelegate: InputBarDelegate by di.instance()

    private val cursorCapsMode: Int
        get() =
            service.currentInputEditorInfo.run {
                if (inputType != InputType.TYPE_NULL) {
                    service.currentInputConnection?.getCursorCapsMode(inputType) ?: 0
                } else {
                    0
                }
            }

    private val _currentKeyboardHeight =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val currentKeyboardHeight = _currentKeyboardHeight.asSharedFlow()

    private lateinit var keyboardView: FrameLayout

    companion object : ResidentWindow.Key {
        /**
         * 最近一次挂上的键盘窗口。
         *
         * 给「键盘与方案」面板用：那里需要调 [switchKeyboard]，但 `by di.instance()` 拿到的是
         * `LazyDelegate`，且缓存的是**建类那一刻**的依赖世代（换主题后失效过）。挂载时记一份，
         * 面板直接读，既是最新的、也不用碰依赖注入。
         */
        @Volatile
        var instance: KeyboardWindow? = null
            private set

        lateinit var currentKeyboard: Keyboard
        var sidebarController: SidebarInputController? = null
            private set
        var dynamicController: DynamicController? = null
            private set
    }

    private var sidebarController: SidebarInputController? = null
    private var dynamicController: DynamicController? = null

    override val key: ResidentWindow.Key
        get() = KeyboardWindow

    private val appPrefs = AppPrefs.defaultInstance()
    private val internalPrefs = appPrefs.internal
    private val expandKeypressAreaPref = appPrefs.keyboard.expandKeypressArea
    private val presetKeyboardIds get() = ThemeManager.keyboardNames
    private var initializeKeyboardId = internalPrefs.initializeKeyboardId.getValue()
    private val keyboardSourceMap = mutableMapOf<String, String>()
    private var currentKeyboardId = ""
    private var lastKeyboardId = ""
    private var lastLockKeyboardId = ""
    private var tempAsciiMode: Boolean? = null
    private val cachedKeyboards = mutableMapOf<String, Pair<Keyboard, KeyboardView>>()
    private val currentKeyboard: Keyboard? get() = cachedKeyboards[currentKeyboardId]?.first
    internal val currentKeyboardView: KeyboardView? get() = cachedKeyboards[currentKeyboardId]?.second

    /** 当前键盘 id：键盘与方案面板要标出「现在用的是哪个」。 */
    val currentId: String get() = currentKeyboardId

    /** 主题里注册的键盘 id：面板判断某个键盘在当前主题里存不存在。 */
    val presetIds: Collection<String> get() = presetKeyboardIds

    private val keyboardActionListener = commonKeyboardActionListener.listener

    @Keep
    private val onExpandKeypressAreaChangeListener =
        PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
            refreshKeyboards(true)
        }

    private val onKeyboardViewLayoutChangeListener =
        View.OnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && KeyboardPending.allowedWidth != width) {
                val isPortrait = !context.resources.configuration.isLandscape()
                KeyboardPending.lastIsPortrait = isPortrait
                KeyboardPending.containerWidth = width
                KeyboardPending.allowedWidth = width
                v.post { refreshKeyboards() }
            }
        }

    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        keyboardView.addOnLayoutChangeListener(onKeyboardViewLayoutChangeListener)

        restoreKeyboardSourceMap()
        // 使用记忆的键盘ID，否则根据方案匹配
        val targetKeyboardId = initializeKeyboardId.takeIf {
            it.isNotEmpty() && presetKeyboardIds.contains(it)
        } ?: ".default"

        attachKeyboard(evalKeyboard(targetKeyboardId))
        return keyboardView
    }

    private fun selectKeyboardConfig(name: String): TextKeyboard? {
        val config = ThemeManager.getKeyboard(name) ?: ThemeManager.getKeyboard("default")
        val importPreset = config?.importPreset
        if (!importPreset.isNullOrEmpty()) {
            return selectKeyboardConfig(importPreset)
        }
        return config
    }

    private fun getKeyboardConfig(name: String): TextKeyboard? {
        val cached = resolvedConfigCache[name]
        if (cached != null) return cached
        val config = selectKeyboardConfig(name)
        resolvedConfigCache[name] = config
        return config
    }

    private val resolvedConfigCache = mutableMapOf<String, TextKeyboard?>()

    /** 重新按当前 id 建一次键盘（改了影响高度的偏好后调用）。 */
    fun reloadCurrentKeyboard() {
        runCatching { attachKeyboard(evalKeyboard(currentKeyboardId)) }
            .onFailure { Timber.w(it, "reloadCurrentKeyboard failed") }
    }

    private fun attachKeyboard(target: String) {
        currentKeyboardId = target
        lastKeyboardId = target

        val rawConfig = getKeyboardConfig(target)
        // 「键盘与方案」面板里的「数字行」开关：首行是纯数字就丢掉，并按行数比缩总高
        val hider = NumberRowHider.apply(rawConfig, WanxiangPrefs.hideNumberRow)
        if (hider.scale != 1f) {
            Timber.i(
                "hide number row: rows %d -> %d, scale %.3f",
                rawConfig?.rows?.size ?: 0, hider.config?.rows?.size ?: 0, hider.scale,
            )
        }
        val keyboard =
            currentKeyboard ?: Keyboard(context, theme, hider.config, heightScale = hider.scale)

        val newSidebarController =
            if (keyboard.isSidebarMode) {
                sidebarController?.destroy()
                sidebarController?.clear()
                SidebarInputController(rime, keyboard.sidebarLayout).also { sidebarController = it }
            } else {
                sidebarController?.destroy()
                sidebarController?.clear()
                sidebarController = null
                null
            }
        KeyboardWindow.sidebarController = newSidebarController

        val newDynamicController =
            if (dynamicController == null) {
                DynamicController(rime) { switchKeyboard(it) }.also {
                    it.originalKeyboard = keyboard.dynamicOriginal
                    it.isDynamicMode = keyboard.isDynamicMode
                    dynamicController = it
                    Timber.d("dynamic controller created: original=${it.originalKeyboard}, mode=${it.isDynamicMode}")
                }
            } else {
                dynamicController!!.apply {
                    originalKeyboard = keyboard.dynamicOriginal
                    isDynamicMode = keyboard.isDynamicMode
                }
                Timber.d("dynamic controller reused: original=${dynamicController!!.originalKeyboard}, mode=${dynamicController!!.isDynamicMode}")
                dynamicController
            }
        KeyboardWindow.dynamicController = newDynamicController

        val view = currentKeyboardView ?: KeyboardView(
            context,
            theme,
            keyboard,
            popup,
            service,
            keyboardActionListener,
            enterKeyDisplay,
            newSidebarController,
        )

        if (currentKeyboard == null) {
            cachedKeyboards[target] = keyboard to view
            keyboard.lastAsciiMode = keyboard.asciiMode
        } else if (keyboard.isSidebarMode) {
            view.updateSidebarController(newSidebarController)
        }

        keyboard.also {
            _currentKeyboardHeight.tryEmit(it.keyboardHeight + view.bottomShadowExtent)
            if (it.isLock) lastLockKeyboardId = target
            dispatchCapsState(it::setShifted)

            val currentMode = rime.run { statusCached }.isAsciiMode
            val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode

            if (currentMode != targetMode) {
                service.postRimeJob {
                    commitComposition()
                    setRuntimeOption("ascii_mode", targetMode)
                }
            }

            KeyboardWindow.currentKeyboard = it
        }

        view.let {
            keyboardView.apply {
                removeAllViews()
                if (rawConfig?.navbar == true) {
                    inputBarDelegate.navBar.attach(
                        title = rawConfig?.name ?: target,
                        onCloseClick = { service.requestHideSelf(0) },
                        onBackClick = { switchKeyboard(".previous") },
                    )
                } else {
                    inputBarDelegate.navBar.detach()
                }
                add(it, lParams(matchParent, matchParent))
            }
        }
    }

    /**
     * 把我们自己的方案按用户选过的键盘形态恢复回来。
     *
     * `smartMatchKeyboard()` 认不出 18/14/26 键的区别（都是同一个方案 id），
     * 所以每次重建都会掉回 default；这里在它之后再补一次。
     */
    /** 用户主动切键盘后，把形态写回 prefs（只在我们自己的方案下生效）。 */
    private fun syncWanxiangLayout(keyboardId: String) {
        val schemaId = runCatching { rime.run { statusCached }.schemaId }.getOrNull()
        if (schemaId.isNullOrEmpty() || !WanxiangKeyboard.owns(schemaId)) return
        WanxiangKeyboard.rememberLayout(keyboardId)
        WanxiangKeyboard.rememberKeyboard(keyboardId)
    }

    /**
     * 按设置里记的形态算出该用哪个键盘；不该干预时返回 null。
     *
     * 注意：这里只做计算、不切换，调用方自行决定在哪一步切，
     * 避免「先切默认、再纠正」造成两次异步切换互相覆盖（26/18 来回跳的根因）。
     */
    private fun wanxiangKeyboardIdOrNull(): String? {
        if (!WanxiangPrefs.isReady() || !WanxiangPrefs.restoreKeyboard) return null
        val schemaId = runCatching { rime.run { statusCached }.schemaId }.getOrNull() ?: return null
        if (schemaId.isEmpty()) return null
        WanxiangKeyboard.rememberedKeyboard(presetKeyboardIds)?.let { return it }
        val layout = WanxiangPrefs.layout
        val target = WanxiangKeyboard.keyboardIdOf(schemaId, layout) ?: return null
        if (target !in presetKeyboardIds) {
            BambooLog.wx.d("restore", "skip: $target not in theme (${presetKeyboardIds.size} ids)")
            return null
        }
        return target
    }

    /** onAttached 用：这一步没有别的切换在排队，可以直接切。 */
    private fun restoreWanxiangKeyboard() {
        val target = wanxiangKeyboardIdOrNull() ?: return
        if (target == currentKeyboardId) {
            BambooLog.wx.d("restore", "skip: already $target")
            return
        }
        BambooLog.wx.d("restore", "attach -> $target (was $currentKeyboardId)")
        switchKeyboard(target, fromUser = false)
    }

    private fun smartMatchKeyboard(): String {
        for (id in presetKeyboardIds) {
            if (id == "default" || id.startsWith(".")) continue
            val option = "_keyboard_$id"
            val enabled = runCatching { rime.run { getRuntimeOption(option) } }.getOrDefault(false)
            if (enabled) return id
        }

        // 主题的布局中包含方案id，直接采用
        val currentSchema = rime.run { statusCached }.schemaId
        if (presetKeyboardIds.contains(currentSchema)) {
            return currentSchema
        }
        val alphabet = rime.run { schemaCached }.alphabet
        val layout =
            when {
                alphabet.all { it.isLetter() } -> "qwerty"

                // 包含 26 个字母
                alphabet.all { it.isLetter() || ",./;".any(it::equals) } -> "qwerty_"

                // 包含 26 个字母和,./;
                alphabet.all { it.isLetterOrDigit() } -> "qwerty0"

                // 包含 26 个字母和数字键
                else -> "default"
            }
        return if (presetKeyboardIds.contains(layout)) layout else "default"
    }

    private fun persistKeyboardSourceMap() {
        val serialized = keyboardSourceMap.entries
            .joinToString(",") { "${it.key}:${it.value}" }
        internalPrefs.previousKeyboardIds.setValue(serialized)
    }

    private fun restoreKeyboardSourceMap() {
        val serialized = internalPrefs.previousKeyboardIds.getValue()
        serialized.takeIf { it.isNotEmpty() }?.split(",")
            ?.mapNotNull { entry ->
                entry.split(":").takeIf { it.size == 2 }?.let { (target, source) ->
                    target to source
                }?.takeIf { (target, source) ->
                    target in presetKeyboardIds && source in presetKeyboardIds
                }
            }?.toMap(keyboardSourceMap)
    }

    private fun evalKeyboard(id: String): String {
        val currentIdx = presetKeyboardIds.indexOfFirst { currentKeyboardId == it }
        val dot =
            when (id) {
                ".default" -> smartMatchKeyboard()

                ".prior" -> presetKeyboardIds.getOrNull(currentIdx - 1) ?: currentKeyboardId

                ".next" -> presetKeyboardIds.getOrNull(currentIdx + 1) ?: currentKeyboardId

                ".last" -> lastKeyboardId

                ".previous" -> keyboardSourceMap[currentKeyboardId] ?: currentKeyboardId

                ".last_lock" -> lastLockKeyboardId

                ".ascii" -> {
                    var ascii = currentKeyboard?.asciiKeyboard
                    if (ascii.isNullOrEmpty()) {
                        ascii = lastLockKeyboardId
                    }
                    if (presetKeyboardIds.contains(ascii)) ascii else currentKeyboardId
                }

                else -> {
                    id.ifEmpty {
                        if (currentKeyboard?.isLock == true) currentKeyboardId else lastLockKeyboardId
                    }
                }
            }
        var final = dot.ifEmpty { smartMatchKeyboard() }

        // 主题里的「返回」键大多写死回主题默认键盘（26 键）。用户在「键盘与方案」里选过
        // 别的布局（例如 18 键）时，从数字 / 符号面板返回不能悄悄把他换回默认布局。
        // keyboardSourceMap 记着「当前这个面板是从哪个键盘进来的」，此时以用户的选择为准。
        val sourceKeyboard = keyboardSourceMap[currentKeyboardId]
        if (
            !sourceKeyboard.isNullOrEmpty() &&
            sourceKeyboard != final &&
            final == smartMatchKeyboard() &&
            presetKeyboardIds.contains(sourceKeyboard)
        ) {
            final = sourceKeyboard
        }

        // 记忆最终键盘ID（排除横屏键盘）
        if (final != currentKeyboardId) {
            internalPrefs.initializeKeyboardId.setValue(final)
        }

        // 切换到横屏布局
        if (service.isLandscapeMode()) {
            val landscape =
                ThemeManager.getKeyboard(final)?.landscapeKeyboard ?: ""
            if (landscape.isNotEmpty() && presetKeyboardIds.contains(landscape)) final = landscape
        }
        return final
    }

    private fun refreshSidebarForReopen() {
        sidebarController?.destroy()
        sidebarController?.clear()
        val newController = SidebarInputController(rime, currentKeyboard!!.sidebarLayout).also { sidebarController = it }
        KeyboardWindow.sidebarController = newController
        currentKeyboardView?.updateSidebarController(newController)
    }

    fun switchKeyboard(to: String, fromUser: Boolean = false) {
        val target = evalKeyboard(to)
        ContextCompat.getMainExecutor(service).execute {
            if (cachedKeyboards.containsKey(target)) {
                if (target == currentKeyboardId) {
                    if (currentKeyboard?.isSidebarMode == true) {
                        refreshSidebarForReopen()
                    }
                    if (dynamicController == null) {
                        val newController = DynamicController(rime) { switchKeyboard(it) }
                        newController.originalKeyboard = currentKeyboard!!.dynamicOriginal
                        newController.isDynamicMode = currentKeyboard!!.isDynamicMode
                        dynamicController = newController
                        KeyboardWindow.dynamicController = newController
                    }
                    return@execute
                }
            }
            // 保存上一个键盘ID，用于来源键盘回退（如果不是返回类操作且不是重新弹出则记录）
            if (to.isNotEmpty() && to !in setOf(".previous", ".last_lock")) {
                keyboardSourceMap[target] = currentKeyboardId
                persistKeyboardSourceMap()
            }
            val currentConfig = getKeyboardConfig(currentKeyboardId)
            val targetConfig = getKeyboardConfig(target)
            if (currentConfig != null && targetConfig != null &&
                currentConfig.isStructurallyIdenticalTo(targetConfig)
            ) {
                cachedKeyboards.remove(currentKeyboardId)?.let { kv ->
                    cachedKeyboards[target] = kv
                }
                currentKeyboardId = target
                lastKeyboardId = target
                currentKeyboard?.refreshKeyBehaviors(targetConfig)
                currentKeyboardView?.invalidateAllKeys()
                if (fromUser) syncWanxiangLayout(target)
                return@execute
            }
            if (fromUser) syncWanxiangLayout(target)
            cachedKeyboards.remove(target)
            currentKeyboardView?.onDetach()
            currentKeyboard?.lastAsciiMode = rime.run { statusCached }.isAsciiMode
            attachKeyboard(target)
        }
        Timber.d("Switched to keyboard: $target")
    }

    fun refreshKeyboards(isAll: Boolean = false) {
        val id = currentKeyboardId.ifEmpty { return }
        currentKeyboardView?.onDetach()
        currentKeyboard?.lastAsciiMode = rime.run { statusCached }.isAsciiMode
        if (isAll) {
            cachedKeyboards.clear()
            resolvedConfigCache.clear()
        } else {
            cachedKeyboards.remove(id)
        }
        attachKeyboard(id)
    }

    fun invalidateAllCachedKeyColors() {
        cachedKeyboards.values.forEach { (kbd, view) ->
            kbd.invalidateAllKeyColors()
            view.invalidateAllKeys()
        }
    }

    fun showAsrkbVoiceOverlay() {
        currentKeyboardView?.showVoiceOverlay()
    }

    fun hideAsrkbVoiceOverlay() {
        currentKeyboardView?.hideVoiceOverlay()
    }

    fun startAsrkbVoiceOverlayWave() {
        currentKeyboardView?.startVoiceOverlayWave()
    }

    fun updateAsrkbVoiceOverlayAmplitude(amplitude: Float) {
        currentKeyboardView?.updateVoiceOverlayAmplitude(amplitude)
    }

    override fun onStartInput(info: EditorInfo) {
        val targetKeyboard =
            when (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) {
                EditorInfo.IME_FLAG_FORCE_ASCII -> ".ascii"

                else -> {
                    when (info.inputType and InputType.TYPE_MASK_CLASS) {
                        InputType.TYPE_CLASS_NUMBER,
                        InputType.TYPE_CLASS_PHONE,
                        InputType.TYPE_CLASS_DATETIME,
                        -> "number"

                        InputType.TYPE_CLASS_TEXT -> {
                            when (info.inputType and InputType.TYPE_MASK_VARIATION) {
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                                -> ".ascii"

                                else -> ""
                            }
                        }

                        else -> ""
                    }
                }
            }
        // 我们自己的方案下，直接把「设置里记的形态」当成目标，避免先切 default 再纠正
        val wanxiangTarget = wanxiangKeyboardIdOrNull()
        val effectiveTarget = if (wanxiangTarget != null) wanxiangTarget else targetKeyboard
        switchKeyboard(effectiveTarget)
        val isAsciiMode = rime.run { statusCached }.isAsciiMode
        if (targetKeyboard == ".ascii" || targetKeyboard == "number") {
            if (tempAsciiMode == null) {
                tempAsciiMode = isAsciiMode
            }
            if (!isAsciiMode) {
                service.postRimeJob { setRuntimeOption("ascii_mode", true) }
            }
        } else {
            tempAsciiMode?.let { saved ->
                if (isAsciiMode != saved) {
                    service.postRimeJob { setRuntimeOption("ascii_mode", saved) }
                }
                tempAsciiMode = null
            } ?: currentKeyboard?.let {
                if (theme.generalStyle.resetAsciiModeOnFocusChange) {
                    val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode
                    if (isAsciiMode != targetMode) {
                        service.postRimeJob { setRuntimeOption("ascii_mode", targetMode) }
                    }
                }
            }
        }
    }

    private fun dispatchCapsState(setShift: (Boolean, Boolean) -> Unit) {
        val status = rime.run { statusCached }
        // TODO: 启用自动首句大写后，点击方向键时，保持Shift锁定状态功能将无法生效
        if (theme.generalStyle.autoCaps && status.isAsciiMode && currentKeyboardView?.isCapsOn == false) {
            setShift(false, cursorCapsMode != 0)
        }
    }

    override fun onKeyAppearanceUpdate(composing: Boolean, menu: Boolean, paging: Boolean) {
        if (!rime.run { statusCached }.isAsciiMode) {
            currentKeyboard?.appearanceStateKeys?.forEach { key ->
                currentKeyboardView?.invalidateKeyByIndex(key.index)
            }
        }
    }

    override fun onSelectionUpdate(
        start: Int,
        end: Int,
    ) {
        dispatchCapsState { on, shifted ->
            currentKeyboard?.setShifted(on, shifted)?.let { if (it) currentKeyboardView?.invalidateAllKeys() }
        }
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        switchKeyboard(wanxiangKeyboardIdOrNull() ?: ".default")
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        val option = value.option
        when {
            option.startsWith("_keyboard_") -> {
                val target = option.removePrefix("_keyboard_")
                if (target.isNotEmpty()) {
                    switchKeyboard(target)
                }
            }

            option.startsWith("_key_") -> {
                val what = option.removePrefix("_key_")
                if (what.isNotEmpty() && value.value) {
                    commonKeyboardActionListener
                        .listener
                        .onAction(KeyActionManager.getAction(what))
                }
            }

            option == "_hide_key_symbol" -> {
                currentKeyboardView?.showKeySymbols = !value.value
            }

            option == "_hide_key_hint" -> {
                currentKeyboardView?.showKeyHints = !value.value
            }
        }
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onAttached() {
        instance = this
        expandKeypressAreaPref.registerOnChangeListener(onExpandKeypressAreaChangeListener)
        if (sidebarController == null && currentKeyboard?.isSidebarMode == true) {
            val newController = SidebarInputController(rime, currentKeyboard!!.sidebarLayout)
            sidebarController = newController
            KeyboardWindow.sidebarController = newController
            currentKeyboardView?.updateSidebarController(newController)
        }
        if (dynamicController == null) {
            val newController = DynamicController(rime) { switchKeyboard(it) }
            newController.originalKeyboard = currentKeyboard!!.dynamicOriginal
            newController.isDynamicMode = currentKeyboard!!.isDynamicMode
            dynamicController = newController
            KeyboardWindow.dynamicController = newController
            Timber.d("dynamic controller recreated in onAttached: original=${newController.originalKeyboard}, mode=${newController.isDynamicMode}")
        }
        val config = getKeyboardConfig(currentKeyboardId)
        if (config?.navbar == true) {
            inputBarDelegate.navBar.attach(
                title = config.name,
                onCloseClick = { service.requestHideSelf(0) },
                onBackClick = { switchKeyboard(".previous") },
            )
        }
        restoreWanxiangKeyboard()
    }

    override fun onDetached() {
        expandKeypressAreaPref.unregisterOnChangeListener(onExpandKeypressAreaChangeListener)
        inputBarDelegate.navBar.detach()
        dynamicController?.reset()
        currentKeyboardView?.onDetach()
        inputBarDelegate.stopAsrkbVoiceFromToolbar()
    }

    fun setHorizontalGapScale(scale: Float) {
        val target = scale.coerceIn(0.5f, 1f)
        currentKeyboard?.let {
            if (kotlin.math.abs(it.horizontalGapScale - target) < 0.01f) return
            it.horizontalGapScale = target
        }
        refreshKeyboards()
    }
}
