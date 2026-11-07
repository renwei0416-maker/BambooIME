package com.osfans.trime.actions.bamboo

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.RimeApi
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 苍竹输入法的「回退 + AI 模式」内核。零 Lua、零方案改动。
 *
 * 行为对齐 rime-actions（`actions.lua`）：
 *  * 回退触发码（默认 `qq/`）**只删最近一笔上屏**，受「最多删除字符数」上限约束；
 *    删完按编辑器实际删掉的码点数扣账（[RevertLedger]）。
 *  * AI 模式：打一个模式码进入（`ai;` / `eg;` / `rs;` …），进入后**一直保持**；
 *    再打同一个码退出，打别的模式码直接切换（对应 `ai_toggle`）。
 *  * 模式内：双击发送键（默认 `/`）发「拼写候选」或「上次上屏」；
 *    按剪贴板码（默认 `jt;`）发剪贴板内容；按退出码（默认 `tc;`）退出。
 *  * 发送时可选择**不把拼写候选上屏**（默认开启），只上屏 AI 的结果。
 *
 * 线程：`handleKey` 在按键协程里（此时仍未进 librime），HTTP 在 IO，
 * 结果上屏回主线程；结果回来先比对 editorEpoch，换过编辑器就丢弃。
 */
object BambooActions {
    private const val DEFAULT_MODE_NAME = "default"

    /** 一次最多取/发多少字符，防止把整篇文章发出去。 */
    private const val MAX_SEND_CHARS = 4000

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null

    /** 每次进入新编辑器 +1；AI 结果回来时比对，过期就丢。 */
    @Volatile private var editorEpoch: Int = 0

    /** 当前的 AI 模式；null = 不在 AI 模式。进入后一直保持，直到显式退出。 */
    @Volatile private var armed: AiMode? = null

    private var lastSendTapAt = 0L
    private var inFlight = false

    fun init(context: Context) {
        appContext = context.applicationContext
        BambooPrefs.init(context)
    }

    /**
     * 换编辑器。**不清 AI 模式**（模式要一直保持），只递增 epoch 用来丢弃过期结果。
     */
    fun onEditorStart(editorInfo: EditorInfo?, restarting: Boolean = false) {
        editorEpoch++
        lastSendTapAt = 0
        // 换了 App / 换了输入框（不是同一输入框重启）就清账：
        // 光标前方已经不是我们记的那段字了，继续删会误删别人的内容。
        if (!restarting || isSensitive(editorInfo)) RevertLedger.clear()
    }

    val isArmed: Boolean get() = armed != null

    val armedName: String? get() = armed?.name

    /** 输入法服务重启时用。 */
    fun reset() {
        armed = null
        lastSendTapAt = 0
        inFlight = false
    }

    // ---------------------------------------------------------------- 记账

    /** 编辑器拒绝记录的场景：密码框。 */
    private fun isSensitive(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        val cls = editorInfo.inputType and EditorInfo.TYPE_MASK_CLASS
        if (cls != EditorInfo.TYPE_CLASS_TEXT) return false
        val variation = editorInfo.inputType and EditorInfo.TYPE_MASK_VARIATION
        return variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun sensitiveNow(): Boolean {
        val svc = TrimeInputMethodService.current ?: return false
        return isSensitive(svc.currentInputEditorInfo)
    }

    /** 上屏文本进账本。由 TIMS 的 commit 消息路径调用（主线程）。 */
    fun onCommitted(text: String?) {
        if (text.isNullOrEmpty()) return
        if (sensitiveNow()) return
        RevertLedger.record(text)
    }

    /** 用户手动按退格、rime 没消费、键被回放给编辑器时扣 1。 */
    fun onBackspaceReplayed() {
        RevertLedger.consume(1)
    }

    // ---------------------------------------------------------------- 按键

    /**
     * 在 `processKey` 之前调用。返回 true 表示这个键已被我们吃掉（不上屏、不进 librime）。
     */
    suspend fun handleKey(
        service: TrimeInputMethodService,
        api: RimeApi,
        keyCode: Int,
        metaState: Int,
    ): Boolean {
        if (!BambooPrefs.enabled) return false
        val ctrl = metaState and KeyEvent.META_CTRL_ON != 0
        val alt = metaState and KeyEvent.META_ALT_ON != 0
        val meta = metaState and KeyEvent.META_META_ON != 0
        val shift = metaState and KeyEvent.META_SHIFT_ON != 0
        if (ctrl || alt || meta || shift) {
            // 快捷组合键：命中就吞掉执行（不交给方案、也不给 App）
            if (BambooPrefs.aiEnabled) {
                if (armed == null && !BambooPrefs.aiFallbackDefault) return false
                val target = armed ?: defaultMode()
                if (target != null) {
                    if (BambooPrefs.aiUsesCombo &&
                        KeyCombo.matches(BambooPrefs.aiCombo, keyCode, metaState)
                    ) {
                        Timber.d("bamboo: combo send [%s] mode=%s", BambooPrefs.aiCombo, target.name)
                        aiSend(service, api, target, fromClipboard = false)
                        return true
                    }
                    if (BambooPrefs.clipComboEnabled &&
                        KeyCombo.matches(BambooPrefs.clipCombo, keyCode, metaState)
                    ) {
                        Timber.d("bamboo: combo clip [%s] mode=%s", BambooPrefs.clipCombo, target.name)
                        aiSend(service, api, target, fromClipboard = true)
                        return true
                    }
                }
            }
            return false
        }

        val ch = charOf(keyCode) ?: return false
        val raw = api.getRawInput()
        val mode = armed

        // ---- 拼写非空：先匹配触发码（前缀 + 终结键）----
        if (raw.isNotEmpty()) {
            // 1) 退出码：只在模式里有效
            if (mode != null && BambooPrefs.aiEnabled) {
                val exitCode = BambooPrefs.aiExitCode
                if (exitCode.length >= 2 && matches(exitCode, ch, raw)) {
                    api.clearComposition()
                    armed = null
                    lastSendTapAt = 0
                    toast("已退出 AI 模式")
                    Timber.d("bamboo: ai disarmed by exit code")
                    return true
                }
            }

            // 2) 剪贴板码：任何状态都能用。没进模式 → default 模式；进了模式 → 用它。
            //    都不改变当前模式状态。
            if (BambooPrefs.aiEnabled && BambooPrefs.clipCodeEnabled) {
                val clipCode = BambooPrefs.aiClipCode
                if (clipCode.length >= 2 && matches(clipCode, ch, raw)) {
                    val target = mode ?: defaultMode()
                    if (target == null || (mode == null && !BambooPrefs.aiFallbackDefault)) {
                        api.clearComposition()
                        toast("未进入 AI 模式，已忽略")
                        return true
                    }
                    api.clearComposition()
                    aiSend(service, api, target, fromClipboard = true)
                    Timber.d("bamboo: clipboard -> %s (mode=%s)", target.name, mode?.name ?: "off")
                    return true
                }
            }

            // 3) 模式码：同码退出、异码切换、没进则进入
            if (BambooPrefs.aiEnabled) {
                for (m in BambooPrefs.aiModes) {
                    if (!matches(m.code, ch, raw)) continue
                    api.clearComposition()
                    lastSendTapAt = 0
                    if (mode != null && m.code == mode.code) {
                        armed = null
                        toast("已退出 AI 模式")
                    } else {
                        armed = m
                        toast("AI：${m.name}（${m.code}）")
                    }
                    Timber.d("bamboo: ai code %s -> %s", m.code, armed?.name ?: "off")
                    return true
                }
            }

            // 4) 回退触发码（AI 模式下也照常可用）
            if (BambooPrefs.revertEnabled) {
                for (code in BambooPrefs.revertTriggers) {
                    if (matches(code, ch, raw)) {
                        api.clearComposition()
                        val deleted = revert(service)
                        Timber.d("bamboo: revert %s -> %d", code, deleted)
                        if (deleted <= 0) toast("没有可回退的内容")
                        return true
                    }
                }
            }
        }

        // ---- 双击触发（只在选了「双击按键」时生效）----
        //   没编码时：第一下就吞掉（不上屏、不留字符），第二下发送
        //   有编码时：第一下放行给方案（`/` 该干嘛干嘛），第二下吞掉并发送
        val now = SystemClock.elapsedRealtime()

        if (mode != null && BambooPrefs.aiEnabled && BambooPrefs.aiUsesDoubleTap) {
            val sendKey = BambooPrefs.aiSendKey.firstOrNull()
            if (sendKey != null && ch == sendKey) {
                val doubled =
                    lastSendTapAt != 0L && now - lastSendTapAt <= BambooPrefs.aiDoubleMs
                if (doubled) {
                    lastSendTapAt = 0
                    // 兜底：万一第一下已经作为文本落进编辑器，先删掉再发
                    if (raw.isEmpty() && lastCharBeforeCursor(service) == sendKey) {
                        deleteCodePoints(service, 1)
                        RevertLedger.consume(1)
                    }
                    aiSend(service, api, mode, fromClipboard = false)
                    return true
                }
                lastSendTapAt = now
                // 没编码 → 吞掉；有编码 → 交给方案
                return raw.isEmpty()
            }
        }

        lastSendTapAt = 0
        return false
    }



    /** 触发码语义：`qq/` = 前缀 `qq` + 终结键 `/`，且当前拼写正好是前缀。 */
    private fun matches(code: String, ch: Char, raw: String): Boolean {
        if (code.length < 2) return false
        if (code.last() != ch) return false
        return raw == code.dropLast(1)
    }

    // ---------------------------------------------------------------- 回退

    /**
     * 删掉**最近一笔**上屏（对齐原版 `fire_delete`：只取栈顶那一笔剩下的字数，
     * 受 max_backspaces 截断，删完按实际删除量扣账）。
     */
    private suspend fun revert(service: TrimeInputMethodService): Int {
        val left = RevertLedger.lastCodePoints()
        if (left <= 0) return 0
        val want = minOf(left, BambooPrefs.revertMaxCodePoints)
        val done = deleteCodePoints(service, want)
        if (done > 0) RevertLedger.consume(done)
        return done
    }

    /**
     * 从光标左侧删除 [count] 个码点，返回实际删掉的码点数。
     * 优先 `deleteSurroundingTextInCodePoints`（emoji/代理对按 1 个算），
     * 编辑器不支持时退回按 UTF-16 长度算的 `deleteSurroundingText`。
     */
    suspend fun deleteCodePoints(service: TrimeInputMethodService, count: Int): Int {
        if (count <= 0) return 0
        return withTimeoutOrNull(1500) {
            withContext(Dispatchers.Main) {
                val ic: InputConnection = service.currentInputConnection ?: return@withContext 0
                val window = count * 2 + 16
                val before = ic.getTextBeforeCursor(window, 0)?.toString().orEmpty()
                if (before.isEmpty()) return@withContext 0

                var ok = false
                ic.beginBatchEdit()
                try {
                    ok = runCatching { ic.deleteSurroundingTextInCodePoints(count, 0) }.getOrDefault(false)
                    if (!ok) {
                        // 退回：按「末尾 count 个码点占多少 UTF-16 单元」删
                        val cps = before.codePointCount(0, before.length)
                        val take = minOf(count, cps)
                        val start = if (take > 0) before.offsetByCodePoints(before.length, -take) else before.length
                        val utf16 = before.length - start
                        ok = runCatching { ic.deleteSurroundingText(utf16, 0) }.getOrDefault(false)
                    }
                } catch (e: Throwable) {
                    Timber.w(e, "delete failed")
                } finally {
                    runCatching { ic.endBatchEdit() }
                }

                val after = ic.getTextBeforeCursor(window, 0)?.toString().orEmpty()
                val beforeCp = before.codePointCount(0, before.length)
                val afterCp = after.codePointCount(0, after.length)
                (beforeCp - afterCp).coerceAtLeast(0)
            }
        } ?: 0
    }

    // ---------------------------------------------------------------- AI

    /**
     * 取「光标前最后一个结束符号之后」的文本（结束符号表来自设置，默认 `。？！!?`）。
     *
     * 取不到文本、或整段都没有结束符号时，退回「上一次上屏」——
     * 与 Windows 版的 `ai/sentence_marks` 行为一致。
     */
    private suspend fun sentenceBeforeCursor(service: TrimeInputMethodService): String {
        val before =
            withContext(Dispatchers.Main) {
                runCatching {
                    service.currentInputConnection
                        ?.getTextBeforeCursor(MAX_SEND_CHARS, 0)
                        ?.toString()
                }.getOrNull()
            }.orEmpty()
        if (before.isEmpty()) return RevertLedger.lastText()
        val tail = lastSentenceSegment(before, BambooPrefs.aiSentenceMarks)
        return tail.ifEmpty { RevertLedger.lastText() }
    }

    /**
     * 取「最后一句」：
     *  * 先去掉结尾的空格/换行，但**保留**结尾的标点（`？`、`。` 这些要一起发）；
     *  * 再跳过结尾连续的结束符号，找到最后一个真正的文字字符；
     *  * 从那个文字字符往前找最近的结束符号，它之后（含结尾标点）就是这一段。
     *
     * 例：`你好。今天几号？` → `今天几号？`；`我想去公园？` → `我想去公园？`；
     * 结尾只有符号没有文字（`。。？`）→ 空串，由调用方退回「上次上屏」。
     */
    private fun lastSentenceSegment(text: String, marks: String): String {
        val trimmed = text.trimEnd { it.isWhitespace() }
        if (trimmed.isEmpty()) return ""
        var lastText = trimmed.length - 1
        while (lastText >= 0 && marks.indexOf(trimmed[lastText]) >= 0) lastText--
        if (lastText < 0) return ""
        var cut = -1
        for (i in 0..lastText) if (marks.indexOf(trimmed[i]) >= 0) cut = i
        val segment = if (cut >= 0) trimmed.substring(cut + 1) else trimmed
        return segment.trimStart { it.isWhitespace() }
    }

    /** 光标前一个字符（用于双击发送时清理第一下打出来的字符）。 */
    private suspend fun lastCharBeforeCursor(service: TrimeInputMethodService): Char? =
        withContext(Dispatchers.Main) {
            runCatching {
                service.currentInputConnection
                    ?.getTextBeforeCursor(1, 0)
                    ?.toString()
                    ?.lastOrNull()
            }.getOrNull()
        }

    /** `jt;` 在没进任何模式时落到 default 模式（按内置键认，改了显示名仍认得）。 */
    private fun defaultMode(): AiMode? =
        BambooPrefs.aiModes.let { modes ->
            modes.firstOrNull { it.key == DEFAULT_MODE_NAME }
                ?: modes.firstOrNull { it.name == DEFAULT_MODE_NAME }
                ?: modes.firstOrNull()
        }

    /** 发送一次请求。
     *
     * @param fromClipboard true = 发剪贴板内容（`jt;`），false = 发拼写/上次上屏（双击 `/`）。
     */
    private fun aiSend(
        service: TrimeInputMethodService,
        api: RimeApi,
        mode: AiMode,
        fromClipboard: Boolean,
    ) {
        if (inFlight) {
            toast("AI 正在处理上一条")
            return
        }
        inFlight = true
        val epoch = editorEpoch
        val scope = service.lifecycleScope
        scope.launch {
            try {
                val text: String
                if (fromClipboard) {
                    val clip = readClipboard(service)
                    if (clip.isNullOrBlank()) {
                        toast("剪贴板里没有文本")
                        return@launch
                    }
                    text = clip
                } else {
                    // 拼写候选「会提交成什么」的文本；不上屏（除非用户关掉了丢弃开关）
                    val composition = runCatching { api.compositionCached }.getOrNull()
                    val preview = composition?.commitTextPreview?.takeIf { it.isNotBlank() }
                        ?: composition?.preedit?.takeIf { it.isNotBlank() }
                    val source = BambooPrefs.aiSource
                    when {
                        source != BambooPrefs.SOURCE_COMMITTED && preview != null -> {
                            if (BambooPrefs.aiDiscardComposition) {
                                api.clearComposition()
                            } else {
                                api.commitComposition()
                                RevertLedger.record(preview)
                            }
                            text = preview
                        }

                        source == BambooPrefs.SOURCE_COMPOSITION -> {
                            toast("没有正在拼写的内容")
                            return@launch
                        }

                        else -> {
                            // 非编码状态：只取「最后一个结束符号之后」那段，或上一次上屏
                            if (api.getRawInput().isNotEmpty()) api.clearComposition()
                            text =
                                if (BambooPrefs.aiSentenceOnly) {
                                    sentenceBeforeCursor(service)
                                } else {
                                    RevertLedger.lastText()
                                }
                        }
                    }
                }

                val sendText = text.trim()
                if (sendText.isEmpty()) {
                    toast("没有可发给 AI 的内容")
                    return@launch
                }
                toast("AI 请求中…")
                val result = AiClient.chat(prompt = mode.prompt, text = sendText)
                if (editorEpoch != epoch) {
                    Timber.i("bamboo: editor changed, drop ai result")
                    return@launch
                }
                result
                    .onSuccess { out ->
                        withContext(Dispatchers.Main) {
                            service.commitText(out)
                            RevertLedger.record(out)
                            if (BambooPrefs.aiSendAfter) service.commitText(" ")
                        }
                        // 模式保持：这里**不**清 armed
                        Timber.i("bamboo: ai result committed, mode kept: %s", mode.name)
                    }.onFailure { e ->
                        toast("AI 失败：${e.message ?: "未知错误"}")
                    }
            } catch (e: Throwable) {
                Timber.w(e, "ai send failed")
                toast("AI 失败：${e.message ?: "未知错误"}")
            } finally {
                inFlight = false
            }
        }
    }

    private suspend fun readClipboard(service: TrimeInputMethodService): String? =
        withContext(Dispatchers.Main) {
            runCatching {
                val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip ?: return@runCatching null
                if (clip.itemCount <= 0) return@runCatching null
                clip.getItemAt(0)?.coerceToText(service)?.toString()
            }.getOrNull()
        }

    /** 设置里改了「退出 AI 模式」之类时用。 */
    fun disarm() {
        armed = null
    }

    // ---------------------------------------------------------------- 小工具

    private fun toast(text: String) {
        mainHandler.post {
            val ctx = appContext ?: return@post
            runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
        }
    }

    /** 软键盘按键 → 字符。只覆盖触发码用得到的键。 */
    private fun charOf(keyCode: Int): Char? =
        when (keyCode) {
            KeyEvent.KEYCODE_SLASH -> '/'
            KeyEvent.KEYCODE_SEMICOLON -> ';'
            KeyEvent.KEYCODE_COMMA -> ','
            KeyEvent.KEYCODE_PERIOD -> '.'
            KeyEvent.KEYCODE_APOSTROPHE -> '\''
            KeyEvent.KEYCODE_MINUS -> '-'
            KeyEvent.KEYCODE_EQUALS -> '='
            KeyEvent.KEYCODE_GRAVE -> '`'
            KeyEvent.KEYCODE_LEFT_BRACKET -> '['
            KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'
            KeyEvent.KEYCODE_BACKSLASH -> '\\'
            KeyEvent.KEYCODE_SPACE -> ' '
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('a' + (keyCode - KeyEvent.KEYCODE_A))
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0))
            else -> null
        }
}
