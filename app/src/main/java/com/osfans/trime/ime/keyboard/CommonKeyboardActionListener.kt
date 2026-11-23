/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import com.osfans.trime.actions.wenquxing.WenquxingTips
import com.osfans.trime.util.BambooLog

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.actions.bamboo.BambooActions
import com.osfans.trime.actions.bamboo.BambooTokens
import com.osfans.trime.core.KeyModifier
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeKeyEvent
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.actions.handwriting.HandwritingWindow
import com.osfans.trime.actions.bamboo.AiPanelWindow
import com.osfans.trime.actions.ocr.OcrWindow
import com.osfans.trime.actions.wanxiang.KeyPanelWindow
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.dialog.EnabledSchemaPickerDialog
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.symbol.LiquidData
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.KeyboardPickerDialog
import com.osfans.trime.ui.main.settings.SoundEffectPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.AppUtils
import com.osfans.trime.util.buildIntentFromAction
import com.osfans.trime.util.buildIntentFromArgument
import com.osfans.trime.util.customFormatDateTime
import com.osfans.trime.util.isAsciiPrintable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.systemservices.clipboardManager
import splitties.systemservices.inputMethodManager
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

class CommonKeyboardActionListener {
    private val di = InputDependencyManager.getInstance().di

    private val context: Context by di.instance()
    private val service: TrimeInputMethodService by di.instance()
    private val rime: RimeSession by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private val keyboardWindow: KeyboardWindow by di.instance()
    private val liquidWindow: LiquidWindow by di.instance()

    /** 手写板窗口：主题弹窗里选中 Handwriting 时才创建。 */
    private val handwritingWindow: HandwritingWindow get() = HandwritingWindow.instance
    private val ocrWindow: OcrWindow get() = OcrWindow.instance
    private val aiPanelWindow: AiPanelWindow get() = AiPanelWindow.instance
    private val keyPanelWindow: KeyPanelWindow get() = KeyPanelWindow.instance
    private val inputBarDelegate: InputBarDelegate by di.instance()

    private val prefs = AppPrefs.defaultInstance()

    private val keycodeCache = ConcurrentHashMap<String, Int>()

    @Volatile
    var shouldCancelRepeat: Boolean = false

    private var shouldReleaseKey: Boolean = false

    private fun showDialog(dialog: suspend (RimeApi) -> Dialog) {
        rime.launchOnReady { api ->
            service.lifecycleScope.launch {
                service.showDialog(dialog(api))
            }
        }
    }

    private fun showThemePicker() {
        showDialog { api ->
            ThemePickerDialog.build(service.lifecycleScope, context) {
                api.commitComposition()
            }
        }
    }

    private fun showKeyboardPicker() {
        showDialog { api ->
            KeyboardPickerDialog.build(
                scope = service.lifecycleScope,
                context = context,
                afterConfirm = { api.commitComposition() },
                onSelectKeyboard = { id -> keyboardWindow.switchKeyboard(id, fromUser = true) },
            )
        }
    }

    private fun showColorPicker() {
        showDialog { api ->
            ColorPickerDialog.build(service.lifecycleScope, context) {
                api.commitComposition()
            }
        }
    }

    private fun showSoundEffectPicker() {
        showDialog {
            SoundEffectPickerDialog.build(service.lifecycleScope, context)
        }
    }

    private fun showEnabledSchemaPicker() {
        showDialog { api ->
            EnabledSchemaPickerDialog.build(api, service.lifecycleScope, context)
        }
    }

    private fun expandActiveText(input: String): String = if (input.matches(PLACEHOLDER_PATTERN)) {
        input.format(
            service.getActiveText(1),
            service.getActiveText(2),
            service.getActiveText(3),
            service.getActiveText(4),
        )
    } else {
        input
    }

    val listener by lazy {
        object : KeyboardActionListener {
            override fun onPress(keyEventCode: Int) {
                shouldCancelRepeat = false
                InputFeedbackManager.run {
                    keyPressSound(keyEventCode)
                }
                service.lifecycleScope.launch(Dispatchers.Default) {
                    InputFeedbackManager.keyPressSpeak(keyEventCode)
                }
            }

            override fun onRelease(keyEventCode: Int) {
                if (shouldReleaseKey) {
                    val name = KeyCode.codeToKeyName(keyEventCode) ?: "VoidSymbol"
                    val value = keycodeCache.getOrPut(name) { RimeKeyEvent.getKeycodeByName(name) }
                    if (value != RimeKeyMapping.RimeKey_VoidSymbol) {
                        service.postKeyJob {
                            processKey(value, KeyModifier.Release.modifier)
                        }
                    }
                }
            }

            override fun onAction(action: KeyAction, key: Key?, behavior: KeyBehavior) {
                // 文曲星：命中诗词时，逗号/句号改成上屏对应的正文
                // 虚拟键盘的标点键是「提交文本」型（code = 0），所以要按字符再认一次
                val tipsResult =
                    if (action.code != 0) {
                        WenquxingTips.consumeKey(action.code)
                    } else {
                        WenquxingTips.consumeKeyText(action.getText(KeyboardWindow.currentKeyboard))
                    }
                when (val key = tipsResult) {
                    is WenquxingTips.KeyResult.Commit -> {
                        WenquxingTips.clear()
                        service.postKeyJob { clearComposition() }
                        service.commitText(key.text)
                        return
                    }
                    WenquxingTips.KeyResult.Rotated -> {
                        // 只换了显示顺序，没有上屏：重绘一次编码行让新顺序可见
                        service.refreshCandidatesPreedit()
                        return
                    }
                    WenquxingTips.KeyResult.None -> Unit
                }

                // 主题弹窗里的「手写」入口：占住键盘区。
                // 必须放在 commit / getText 分支之前，否则 "Handwriting" 会被当成普通文字上屏。
                if (action.code == 0 &&
                    HandwritingWindow.isToken(action.getText(KeyboardWindow.currentKeyboard))
                ) {
                    // 进画布要用手写模型：先把「只是开面板」的登记清掉，
                    // 否则上一次面板剩下的跳过计数会把这次加载也跳掉。
                    handwritingWindow.clearPanelOnly()
                    if (windowManager.isAttached(handwritingWindow)) {
                        windowManager.attachWindow(keyboardWindow)
                    } else {
                        windowManager.attachWindow(handwritingWindow)
                    }
                    return
                }

                // 主题 token：BambooNumber / BambooSymbol —— 从键盘直接唤起「数字和符号」面板，
                // 与手写画布进面板的区别是返回/收起都回键盘。同样要建视图，不能进 postKeyJob。
                if (action.code == 0) {
                    HandwritingWindow.panelTokenPage(action.getText(KeyboardWindow.currentKeyboard))
                        ?.let { page ->
                            // 先登记「只是开面板」，再挂窗口：挂窗口会走 buildView/onAttached，
                            // 它们据此跳过手写模型加载。顺序反过来就会白加载一次模型。
                            handwritingWindow.requestPanelOnly(page)
                            if (!windowManager.isAttached(handwritingWindow)) {
                                windowManager.attachWindow(handwritingWindow)
                            }
                            handwritingWindow.openPanelStandalone(page)
                            return
                        }
                }

                // 苍竹 token：主题里可直接把某个动作绑成 BambooRevert / BambooAiSend / BambooAi1 …
                // 同样是 code==0 的纯文本，必须放在 commit / getText 分支之前，否则会被当成文字上屏。
                // 拍照识字入口：同样是 code==0 的纯文本，放在 token 分发之前，
                // 因为它要建视图（不能在 rime 线程上跑 postKeyJob）。
                if (action.code == 0 &&
                    OcrWindow.isToken(action.getText(KeyboardWindow.currentKeyboard))
                ) {
                    if (windowManager.isAttached(ocrWindow)) {
                        windowManager.attachWindow(keyboardWindow)
                    } else {
                        windowManager.attachWindow(ocrWindow)
                    }
                    return
                }

                // 键盘与方案面板：同样要建视图，放在通用 token 分发之前
                if (action.code == 0 &&
                    KeyPanelWindow.isToken(action.getText(KeyboardWindow.currentKeyboard))
                ) {
                    if (windowManager.isAttached(keyPanelWindow)) {
                        windowManager.attachWindow(keyboardWindow)
                    } else {
                        windowManager.attachWindow(keyPanelWindow)
                    }
                    return
                }

                // AI 模式面板：同样是 code==0 的纯文本，而且同样要建视图
                // （不能在 rime 线程上跑 postKeyJob），所以放在通用 token 分发之前。
                if (action.code == 0 &&
                    AiPanelWindow.isToken(action.getText(KeyboardWindow.currentKeyboard))
                ) {
                    if (windowManager.isAttached(aiPanelWindow)) {
                        windowManager.attachWindow(keyboardWindow)
                    } else {
                        windowManager.attachWindow(aiPanelWindow)
                    }
                    return
                }

                if (action.code == 0) {
                    val raw = action.getText(KeyboardWindow.currentKeyboard)
                    val token = BambooTokens.match(raw)
                    if (token != null) {
                        BambooLog.core.d("tips", "dispatch token=$token")
                        service.postKeyJob { BambooActions.handleToken(service, this, token) }
                        return
                    }
                    // 主题绑了苍竹名字、但 APK 是旧版时不认识这个名字，会当普通文字上屏。
                    // 打一条日志，排查「绑了没反应」时一眼能看出来是版本不匹配。
                    if (raw.startsWith(BambooTokens.PREFIX, ignoreCase = true)) {
                        BambooLog.core.d("tips", "unknown token=$raw（APK 与主题版本不一致？）")
                    }
                }

                val shouldHandle = when {
                    action.commit.isNotEmpty() -> {
                        val commitText = action.commit
                        service.postKeyJob {
                            commitComposition(appendText = commitText)
                        }
                        false
                    }

                    KeyboardWindow.currentKeyboard.let { keyboard ->
                        action.getText(keyboard).isNotEmpty()
                    } -> {
                        onText(action.getText(KeyboardWindow.currentKeyboard))
                        false
                    }

                    else -> true
                }

                if (shouldHandle) {
                    when (action.code) {
                        KeyEvent.KEYCODE_SWITCH_CHARSET -> handleSwitchCharset(action)

                        KeyEvent.KEYCODE_EISU -> {
                            if (KeyboardWindow.currentKeyboard.isDynamicMode) {
                                KeyboardWindow.dynamicController?.reset()
                            }
                            keyboardWindow.switchKeyboard(action.select, fromUser = true)
                        }

                        KeyEvent.KEYCODE_LANGUAGE_SWITCH -> handleLanguageSwitch(action)

                        KeyEvent.KEYCODE_FUNCTION -> handleFunctionCommand(action)

                        KeyEvent.KEYCODE_SETTINGS -> handleSettings(action)

                        KeyEvent.KEYCODE_PROG_GREEN -> showKeyboardPicker()

                        KeyEvent.KEYCODE_PROG_RED -> showColorPicker()

                        KeyEvent.KEYCODE_MENU -> showEnabledSchemaPicker()

                        KeyEvent.KEYCODE_VOICE_ASSIST -> switchToVoiceInputMethod()

                        else -> handleDefaultKeyAction(action)
                    }
                }

                val sc = KeyboardWindow.dynamicController
                if (sc != null) {
                    val isSwipe = behavior == KeyBehavior.SWIPE_UP ||
                        behavior == KeyBehavior.SWIPE_DOWN ||
                        behavior == KeyBehavior.SWIPE_LEFT ||
                        behavior == KeyBehavior.SWIPE_RIGHT
                    val skip = action.code in setOf(
                        KeyEvent.KEYCODE_SWITCH_CHARSET,
                        KeyEvent.KEYCODE_EISU,
                        KeyEvent.KEYCODE_LANGUAGE_SWITCH,
                        KeyEvent.KEYCODE_FUNCTION,
                        KeyEvent.KEYCODE_SETTINGS,
                        KeyEvent.KEYCODE_PROG_GREEN,
                        KeyEvent.KEYCODE_PROG_RED,
                        KeyEvent.KEYCODE_MENU,
                        KeyEvent.KEYCODE_VOICE_ASSIST,
                    ) || action.code == KeyEvent.KEYCODE_DEL ||
                        action.code == KeyEvent.KEYCODE_SPACE ||
                        action.code == KeyEvent.KEYCODE_ENTER ||
                        action.code == KeyEvent.KEYCODE_TAB ||
                        action.code == KeyEvent.KEYCODE_DPAD_LEFT ||
                        action.code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                        action.code == KeyEvent.KEYCODE_MOVE_HOME ||
                        action.code == KeyEvent.KEYCODE_MOVE_END ||
                        isSwipe
                    if (!skip) {
                        key?.dynamicTarget?.let { sc.onInput(it) }
                    }
                    if (!sc.isEmpty && KeyboardWindow.currentKeyboard.isDynamicMode &&
                        action.code in setOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER)
                    ) {
                        sc.reset()
                        keyboardWindow.switchKeyboard(sc.originalKeyboard)
                    }
                }
            }

            private fun handleSwitchCharset(action: KeyAction) {
                val option = action.toggle.ifEmpty { return }

                rime.launchOnReady { api ->
                    service.lifecycleScope.launch {
                        val isEnabled = api.getRuntimeOption(option)
                        val isComposing = api.statusCached.isComposing
                        api.setRuntimeOption(option, !isEnabled)
                        if (option == "ascii_mode" && isComposing) {
                            api.getRawInput().takeIf { it.isNotEmpty() }?.let {
                                service.commitText(it)
                                api.clearComposition()
                            }
                        }
                    }
                }
            }

            private fun handleLanguageSwitch(action: KeyAction) {
                when {
                    action.select == ".next" -> service.switchToNextIme()
                    action.select.isNotEmpty() -> service.switchToPrevIme()
                    else -> inputMethodManager.showInputMethodPicker()
                }
            }

            private fun handleFunctionCommand(action: KeyAction) {
                val arg = expandActiveText(action.option)

                when (action.command) {
                    "liquid_keyboard" -> handleLiquidKeyboard(arg)

                    "menu_keyboard" -> windowManager.attachWindow(SwitchOptionWindow())

                    "clipboard_window" -> handleClipboardWindow(arg)

                    "set_color_scheme" -> handleColorScheme(arg)

                    "set_theme" -> handleTheme(arg)

                    "set_schema" -> handleSetSchema(arg)

                    "broadcast" -> service.sendBroadcast(Intent(arg))

                    "clipboard" -> handleClipboard()

                    "commit" -> service.commitText(arg)

                    "date" -> service.commitText(customFormatDateTime(arg))

                    "run" -> handleRunCommand(arg)

                    "apply" -> handleApplyCommand(arg)

                    "share_text" -> service.shareText()

                    "select_candidate" -> handleSelectCandidate(arg)

                    "sidebar_clear" -> {
                        KeyboardWindow.sidebarController?.clear()
                        rime.launchOnReady { api ->
                            service.lifecycleScope.launch {
                                api.clearComposition()
                            }
                        }
                    }

                    "dynamic_clear" -> {
                        KeyboardWindow.dynamicController?.clear()
                        rime.launchOnReady { api ->
                            service.lifecycleScope.launch {
                                api.clearComposition()
                            }
                        }
                    }

                    else -> handleIntentAction(action.command, arg)
                }
            }

            private fun handleLiquidKeyboard(arg: String) {
                // for compatibility
                if (arg == "剪贴" || arg == "clipboard") {
                    windowManager.attachWindow(ClipboardWindow())
                    return
                }
                val liquidTagList = LiquidData.getTagList()
                val index = liquidTagList.indexOfFirst { tag ->
                    tag.id == arg || runCatching {
                        LiquidData.Type.valueOf(arg.uppercase())
                    }.getOrNull() == tag.type
                }

                if (index >= 0) {
                    windowManager.attachWindow(LiquidWindow)
                    liquidWindow.setDataByIndex(index)
                } else {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            private fun handleClipboardWindow(arg: String) {
                val category = when (arg.toIntOrNull()) {
                    0 -> com.osfans.trime.data.db.ClipboardCategory.All
                    1 -> com.osfans.trime.data.db.ClipboardCategory.Favorites
                    else -> com.osfans.trime.data.db.ClipboardCategory.All
                }
                windowManager.attachWindow(ClipboardWindow(category))
            }

            private fun handleColorScheme(arg: String) {
                ThemeManager.activeTheme.colorSchemes
                    .find { it.id == arg }
                    ?.let { ColorManager.setColorScheme(it) }
            }

            private fun handleSetSchema(arg: String) {
                rime.launchOnReady { api ->
                    api.selectSchemaCrossPackage(arg)
                }
            }

            private fun handleTheme(arg: String) {
                if (arg == $$"$reload") {
                    ThemeManager.selectTheme(ThemeManager.prefs.selectedTheme.getValue(), forceReload = true)
                } else {
                    ThemeManager.getAllThemes()
                        .find { it.name.equals(arg, ignoreCase = true) }?.let {
                            ThemeManager.selectTheme(it.configId)
                        }
                }
            }

            private fun handleClipboard() {
                clipboardManager.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(service)
                    ?.let { service.commitText(it.toString()) }
            }

            private fun handleRunCommand(arg: String) {
                buildIntentFromArgument(arg)?.let { intent ->
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
                    service.startActivity(intent)
                }
            }

            private fun handleApplyCommand(arg: String) {
                when (arg) {
                    "DEPLOY" -> {
                        Timber.i("try to start maintenance via command ...")
                        rime.launchOnReady { api -> api.deploy() }
                    }

                    "RESTART_RIME" -> {
                        Timber.i("try to restart rime via command ...")
                        RimeDaemon.restartRime()
                    }

                    "SYNC_USER_DATA" -> {
                        Timber.i("try to sync rime user data via command ...")
                        rime.launchOnReady { api -> api.syncUserData() }
                    }

                    "UPDATE_CONFIG" -> {
                        Timber.i("try to update rime config via command ...")
                        rime.launchOnReady { api ->
                            api.updateConfig()
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    else -> Timber.w("Unknown apply method: $arg")
                }
            }

            private fun handleIntentAction(command: String, arg: String) {
                buildIntentFromAction(command, arg)?.let { intent ->
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
                    service.startActivity(intent)
                }
            }

            private fun handleSelectCandidate(arg: String) {
                val index = arg.toIntOrNull() ?: return
                rime.launchOnReady { api ->
                    service.lifecycleScope.launch {
                        api.selectCandidate(index, false)
                    }
                }
            }

            private fun handleSettings(action: KeyAction) {
                when (action.option) {
                    "theme" -> showThemePicker()
                    "color" -> showColorPicker()
                    "schema" -> AppUtils.launchMainToSchemaList(context)
                    "sound" -> showSoundEffectPicker()
                    "about" -> AppUtils.launchMainToAbout(service)
                    else -> AppUtils.launchMainActivity(service)
                }
            }

            private fun switchToVoiceInputMethod() {
                inputBarDelegate.startVoiceHoldSession()
            }

            private fun handleDefaultKeyAction(action: KeyAction) {
                val shouldHookShiftKey = when {
                    prefs.keyboard.hookShiftSpace.getValue() && action.code == KeyEvent.KEYCODE_SPACE -> true
                    prefs.keyboard.hookShiftNum.getValue() && action.code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> true
                    prefs.keyboard.hookShiftSymbol.getValue() && action.code in KeyEvent.KEYCODE_GRAVE..KeyEvent.KEYCODE_SLASH -> true
                    prefs.keyboard.hookShiftSymbol.getValue() && action.code in setOf(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD) -> true
                    else -> false
                }

                if (action.modifier == 0 && KeyboardWindow.currentKeyboard.isOnlyShiftOn && shouldHookShiftKey) {
                    onKey(action.code, 0)
                    return
                }

                val modifier = when {
                    action.modifier == 0 -> KeyboardWindow.currentKeyboard.modifier

                    (action.modifier and KeyEvent.META_CTRL_ON) != 0 && isNavigationKey(action.code) ->
                        action.modifier or KeyboardWindow.currentKeyboard.modifier

                    else -> action.modifier
                }

                onKey(action.code, modifier)
            }

            private fun isNavigationKey(keyCode: Int): Boolean = keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_MOVE_HOME ||
                keyCode == KeyEvent.KEYCODE_MOVE_END

            override fun onKey(
                keyEventCode: Int,
                metaState: Int,
            ) {
                shouldReleaseKey = false

                val sidebarC = KeyboardWindow.sidebarController
                if (sidebarC != null && KeyboardWindow.currentKeyboard.isSidebarMode) {
                    val keyChar =
                        when {
                            keyEventCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                                (keyEventCode - KeyEvent.KEYCODE_0 + '0'.code).toChar()
                            }

                            keyEventCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                                val base = (keyEventCode - KeyEvent.KEYCODE_A + 'a'.code).toChar()
                                if (metaState and KeyEvent.META_SHIFT_ON != 0) {
                                    base.uppercaseChar()
                                } else {
                                    base
                                }
                            }

                            else -> null
                        }
                    when {
                        keyChar != null -> {
                            sidebarC.onKeyChar(keyChar.toString())
                        }

                        keyEventCode == KeyEvent.KEYCODE_DEL -> {
                            if (sidebarC.onBackspace()) {
                                sidebarC.updateRimeInput()
                                return
                            }
                        }

                        sidebarC.isSegmentKeyCode(keyEventCode) -> {
                            if (sidebarC.onSegmentKey()) {
                                return
                            }
                        }
                    }
                }

                val dc = KeyboardWindow.dynamicController

                val name = KeyCode.codeToKeyName(keyEventCode) ?: "VoidSymbol"
                val value = keycodeCache.getOrPut(name) { RimeKeyEvent.getKeycodeByName(name) }
                val m = if (keyEventCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_EQUALS) {
                    metaState or KeyEvent.META_NUM_LOCK_ON
                } else {
                    metaState
                }
                val modifiers = KeyModifiers.fromMetaState(m).modifiers
                service.postKeyJob {
                    if (service.hookKeyboard(keyEventCode, m)) {
                        Timber.d("handleKey: hook")
                        return@postKeyJob
                    }

                    // 苍竹：回退触发码 / AI 模式。刻意放在 processKey 之前——
                    // 这样终结键（`/`、`;`）既不进 rime 的组合，也不会被回放上屏。
                    if (runCatching { BambooActions.handleKey(service, this, keyEventCode, m) }.getOrDefault(false)) {
                        shouldReleaseKey = true
                        return@postKeyJob
                    }

                    val isDel = keyEventCode == KeyEvent.KEYCODE_DEL
                    val isNavKey = keyEventCode in setOf(
                        KeyEvent.KEYCODE_TAB,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_MOVE_HOME,
                        KeyEvent.KEYCODE_MOVE_END,
                    )
                    val lenBefore = getRawInput().length

                    dc?.isKeyProcessing = true

                    val handled = processKey(value, modifiers)
                    val lenAfter = getRawInput().length

                    shouldCancelRepeat = isDel && lenBefore > 0 && lenAfter == 0

                    dc?.isKeyProcessing = false

                    if (dc != null) {
                        if (isDel) {
                            if (lenAfter < lenBefore) {
                                val deleted = lenBefore - lenAfter
                                ContextCompat.getMainExecutor(service).execute { dc.onDelete(deleted) }
                            }
                        } else if (lenAfter < lenBefore) {
                            val committed = lenBefore - lenAfter
                            ContextCompat.getMainExecutor(service).execute { dc.trimCommitted(committed) }
                        } else if (isNavKey) {
                            val caretPos = getCaretPos()
                            val keyIdx = dc.keyIndexFromCaret(caretPos)
                            Timber.d("handleKey: nav key, caretPos=$caretPos -> keyIdx=$keyIdx")
                            ContextCompat.getMainExecutor(service).execute { dc.onCursorMoved(keyIdx) }
                        } else if (lenAfter > lenBefore) {
                            val charDiff = lenAfter - lenBefore
                            ContextCompat.getMainExecutor(service).execute { dc.recordCharCount(charDiff) }
                        }
                    }
                    Timber.d("handleKey: lenBefore=$lenBefore, lenAfter=$lenAfter, isDel=$isDel")

                    if (handled) {
                        shouldReleaseKey = true
                        Timber.d("handleKey: processKey")
                        return@postKeyJob
                    }
                    if (AppUtils.launchKeyCategory(service, keyEventCode)) {
                        Timber.d("handleKey: openCategory")
                        return@postKeyJob
                    }
                    // other special cases
                    if (keyEventCode == KeyEvent.KEYCODE_BACK) {
                        service.requestHideSelf(0)
                    }
                    shouldReleaseKey = false
                }
            }

            override fun onText(input: String) {
                if (input.isEmpty()) return
                Timber.d("onText: $input")

                val escaped = input.replace("{}", "{braceleft}{braceright}")
                val values = buildList {
                    var i = 0
                    while (i < escaped.length) {
                        val value = when (val match = TEXT_INPUT_PATTERN.matchEntire(escaped.substring(i))) {
                            match if (match != null) -> match.groupValues[1]
                            else -> escaped[i].toString()
                        }
                        add(value)
                        i += value.length
                    }
                }

                service.postKeyJob {
                    for (value in values) {
                        when {
                            value.run { startsWith('{') && endsWith('}') } -> {
                                val token = value.removeSurrounding("{", "}")
                                onAction(KeyActionManager.getAction(token))
                            }

                            !value[0].isAsciiPrintable() -> {
                                // Commit through Rime's message flow so the symbol is
                                // ordered after the composition commit on the main thread.
                                commitComposition(appendText = value)
                            }

                            else -> simulateKeySequence(value)
                        }
                    }
                }

                shouldReleaseKey = false
            }

            override fun shouldContinueRepeat(): Boolean = !shouldCancelRepeat
        }
    }

    companion object {
        /**
         * Regex for combined key events.
         * group(1) captures either:
         *   - a plain prefix (optionally preceded by {Escape}) from the left branch,
         *   - or a standalone {xxx} block from the right branch.
         * The trailing .* consumes the rest of the input without affecting group(1).
         */
        private val TEXT_INPUT_PATTERN = """^((?:\{Escape\})?[^{}]+|\{[^{}]+\}).*$""".toRegex()

        private val PLACEHOLDER_PATTERN = Regex(".*(%([1-4]\\$)?s).*")
    }
}
