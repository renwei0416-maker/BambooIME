package com.osfans.trime.actions.bamboo

import android.content.ClipboardManager
import com.osfans.trime.util.BambooLog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.actions.glossary.GlossaryDecorator
import com.osfans.trime.actions.glossary.GlossaryPrefs
import com.osfans.trime.actions.tips.TipBlocks
import com.osfans.trime.actions.wenquxing.WenquxingPrefs
import com.osfans.trime.actions.wenquxing.WenquxingTips
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeApi
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import android.content.Intent
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.NavigationRoute
import kotlinx.coroutines.Dispatchers
import org.kodein.di.instance
import kotlinx.coroutines.delay
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
        VoiceAiPrefs.init(context)
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

    /** 当前是不是停在文曲星模式（不是 AI 模式）。 */
    val armedIsTips: Boolean get() = armed?.key == TIPS_KEY

    /** 面板顶部显示的「当前」名字：文曲星单独显示，其余用模式名。 */
    val armedDisplayName: String?
        get() = armed?.let { if (it.key == TIPS_KEY) "文曲星" else it.name }

    /** 当前模式对应的触发码（面板高亮比对用）。 */
    val armedCode: String?
        get() = armed?.let { if (it.key == TIPS_KEY) BambooPrefs.wenquxingCode else it.code }

    // ---------------------------------------------------------------- 面板用的开关
    // 面板（AiPanelWindow）只调这三个入口，语义必须与「打触发码」完全一致，
    // 否则会出现「面板进的和码进的不是同一个状态」。改动这里请同步改 handleKey 里的模式码分支。

    /** 进入某个 AI 模式（等价于打它的触发码）。false = 该模式不可用（AI 总开关关着 / 码不存在）。 */
    fun enterAiMode(code: String): Boolean {
        if (!BambooPrefs.aiEnabled) {
            toast("AI 模式未开")
            return false
        }
        val m = BambooPrefs.aiModes.firstOrNull { it.code == code }
        if (m == null) {
            Timber.w("bamboo: panel enter unknown code %s", code)
            return false
        }
        armed = m
        lastSendTapAt = 0
        toast("AI：${m.name}（${m.code}）")
        Timber.d("bamboo: panel enter ai %s", m.code)
        return true
    }

    /** 进入文曲星模式（等价于打 mj;）。false = 文曲星没启用。 */
    fun enterTipsMode(): Boolean {
        if (!WenquxingPrefs.enabled) {
            toast("文曲星未启用")
            return false
        }
        armed = tipsMode()
        lastSendTapAt = 0
        WenquxingTips.clear()
        toast("文曲星（${BambooPrefs.wenquxingCode}）")
        Timber.d("bamboo: panel enter tips")
        return true
    }

    /**
     * `tc;` 的完整语义：**一键收工**。
     *
     * 退出 `armed`（AI 模式或文曲星，二选一，天然唯一），并顺带关掉「外语学习」常驻开关。
     * 返回实际关掉了哪些名字（空 = 什么都没开）。
     */
    fun exitAll(): List<String> {
        val names = mutableListOf<String>()
        armed?.let { names += if (it.key == TIPS_KEY) "文曲星" else it.name }
        if (GlossaryPrefs.enabled) {
            GlossaryPrefs.enabled = false
            names += "外语学习"
        }
        if (names.isNotEmpty()) {
            armed = null
            lastSendTapAt = 0
            WenquxingTips.clear()
        }
        return names
    }

    /** 退出当前模式（AI 或文曲星都算）。返回 true = 原本确实在模式里。 */
    fun exitArmedMode(): Boolean {
        val current = armed ?: return false
        val wasTips = current.key == TIPS_KEY
        armed = null
        lastSendTapAt = 0
        WenquxingTips.clear()
        toast(if (wasTips) "已退出文曲星" else "已退出 AI 模式")
        Timber.d("bamboo: panel exit mode tips=%s", wasTips)
        return true
    }

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
        BambooLog.ai.d(
            "ledger",
            "record \"$text\" cp=${text.codePointCount(0, text.length)} total=${RevertLedger.size()}",
        )
    }

    /** 用户手动按退格、rime 没消费、键被回放给编辑器时扣 1。 */
    fun onBackspaceReplayed() {
        RevertLedger.consume(1)
        BambooLog.ai.d("ledger", "backspace replay -> consume 1, total=${RevertLedger.size()}")
    }

    /** 文曲星模式在 AiMode.key 上的标记。 */
    private const val TIPS_KEY = "wenquxing"

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
        // 「外语学习」开关码（默认 wy;）属于「输入增强」，与「回退与AI」总开关无关，
        // 所以必须放在下面那行总开关判断之前。
        run {
            val gCode = GlossaryPrefs.code
            val ch = charOf(keyCode)
            if (gCode.length >= 2 && ch != null) {
                val raw = api.getRawInput()
                if (raw.isNotEmpty() && matches(gCode, ch, raw)) {
                    api.clearComposition()
                    GlossaryPrefs.enabled = !GlossaryPrefs.enabled
                    toast(if (GlossaryPrefs.enabled) "已开启外语学习" else "已关闭外语学习")
                    Timber.d("bamboo: glossary code -> %s", GlossaryPrefs.enabled)
                    return true
                }
            }
        }
        if (!BambooPrefs.enabled) return false
        val ctrl = metaState and KeyEvent.META_CTRL_ON != 0
        val alt = metaState and KeyEvent.META_ALT_ON != 0
        val meta = metaState and KeyEvent.META_META_ON != 0
        val shift = metaState and KeyEvent.META_SHIFT_ON != 0
        if (ctrl || alt || meta || shift) {
            // 快捷组合键：命中就吞掉执行（不交给方案、也不给 App）。
            // 这两个开关只约束「组合键」本身；主题里的动作名（token）不受它们影响，见 handleToken。
            if (BambooPrefs.aiEnabled || armed?.key == TIPS_KEY) {
                if (BambooPrefs.aiComboEnabled &&
                    KeyCombo.matches(BambooPrefs.aiCombo, keyCode, metaState) &&
                    aiDispatch(service, api, fromClipboard = false)
                ) {
                    Timber.d("bamboo: combo send [%s]", BambooPrefs.aiCombo)
                    return true
                }
                if (BambooPrefs.clipComboEnabled &&
                    KeyCombo.matches(BambooPrefs.clipCombo, keyCode, metaState) &&
                    aiDispatch(service, api, fromClipboard = true)
                ) {
                    Timber.d("bamboo: combo clip [%s]", BambooPrefs.clipCombo)
                    return true
                }
            }
            return false
        }

        val ch = charOf(keyCode) ?: return false
        val raw = api.getRawInput()
        val mode = armed

        // ---- 拼写非空：先匹配触发码（前缀 + 终结键）----
        if (raw.isNotEmpty()) {
            // 1) 退出码（默认 tc;）：一键收工 —— 退出 AI 模式 / 文曲星，并关掉外语学习。
            //    armed 只有一个槽，所以「退哪个」天然唯一；外语学习是常驻开关，顺带关掉。
            run {
                val exitCode = BambooPrefs.aiExitCode
                val modeOk = mode != null && (BambooPrefs.aiEnabled || mode.key == TIPS_KEY)
                val glossaryOn = GlossaryPrefs.enabled
                if (exitCode.length >= 2 && (modeOk || glossaryOn) && matches(exitCode, ch, raw)) {
                    api.clearComposition()
                    val names = exitAll()
                    toast(
                        if (names.isEmpty()) {
                            "当前没有开启任何模式"
                        } else {
                            "已退出：" + names.joinToString("、")
                        },
                    )
                    Timber.d("bamboo: exit code -> %s", names.joinToString(","))
                    return true
                }
            }

            // 2) 剪贴板码：任何状态都能用。没进模式 → default 模式；进了模式 → 用它。
            //    都不改变当前模式状态。
            if (BambooPrefs.aiEnabled && BambooPrefs.clipCodeEnabled) {
                val clipCode = BambooPrefs.aiClipCode
                if (clipCode.length >= 2 && matches(clipCode, ch, raw)) {
                    // 打出来的码本身要丢掉，再走和组合键同一个出口
                    api.clearComposition()
                    if (!aiDispatch(service, api, fromClipboard = true)) {
                        toast("未进入 AI 模式，已忽略")
                    }
                    Timber.d("bamboo: clipboard code -> %s", armed?.name ?: "default")
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

            // 3b) 文曲星触发码：与模式码同一套语义（同码退出、异码切换、没进则进入）。
            //     进模式后按发送键（Ctrl+; / jt; / 主题 token）改为查词包，不发 AI。
            run {
                val code = BambooPrefs.wenquxingCode
                if (WenquxingPrefs.enabled && code.length >= 2 && matches(code, ch, raw)) {
                    val wasTips = mode?.key == TIPS_KEY
                    api.clearComposition()
                    lastSendTapAt = 0
                    WenquxingTips.clear()
                    if (wasTips) {
                        armed = null
                        toast("已退出文曲星")
                    } else {
                        armed = tipsMode()
                        toast("文曲星（$code）")
                    }
                    Timber.d("bamboo: tips code %s -> %s", code, if (armed == null) "off" else "on")
                    return true
                }
            }

            // 4) 回退触发码（AI 模式下也照常可用）
            if (BambooPrefs.revertEnabled) {
                for (code in BambooPrefs.revertTriggers) {
                    if (matches(code, ch, raw)) {
                        return doRevert(service, api, "code=\"$code\" raw=\"$raw\"")
                    }
                }
            }
        }

        // ---- 双击触发（只在选了「双击按键」时生效）----
        //   没编码时：第一下就吞掉（不上屏、不留字符），第二下发送
        //   有编码时：第一下放行给方案（`/` 该干嘛干嘛），第二下吞掉并发送
        val now = SystemClock.elapsedRealtime()

        if (mode != null && (BambooPrefs.aiEnabled || mode.key == TIPS_KEY) &&
            BambooPrefs.aiDoubleTapEnabled
        ) {
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



    // ---------------------------------------------------------------- 主题 token

    /**
     * 主题按键 token 的入口（`BambooRevert` / `BambooClear` / `BambooAiSend` …）。
     *
     * 由 `CommonKeyboardActionListener.onAction` 在识别出 token 之后调用，
     * **必须在 rime 线程**（`service.postKeyJob`）里跑，因为会清组合、读 RimeApi。
     *
     * 返回 true 表示已处理（调用方不应再按普通文字/按键处理）。
     */
    suspend fun handleToken(service: TrimeInputMethodService, api: RimeApi, token: String): Boolean {
        // 引号补全 token 属于「输入增强」，与「回退与AI」不是同一个功能模块，
        // 所以必须放在总开关之前：用户关掉 AI/回退后引号键照样能用。
        if (token == BambooTokens.QUOTE || token == BambooTokens.QUOTE_SINGLE) {
            val isDouble = token == BambooTokens.QUOTE
            // 「引号左右轮替」只决定走哪条路，AutoPair 的补全逻辑一行不动：
            //   关 → 提交一个普通左引号，AutoPair 照常补全，和别的成对符号完全一致；
            //   开 → 把原键还给引擎，跟键盘上那个引号键一模一样，由 librime 自己左右交替；
            //        轮到左引号时 AutoPair 命中补全，轮到右引号时走「关掉重复的右半边」。
            if (!AutoPairPrefs.quoteAlternate) {
                // 先把正在打的拼音上屏，再提交真正的左引号；
                // 右半边与光标居中由 AutoPair 在 commitText 里完成。
                api.commitComposition()
                service.commitText(if (isDouble) "\u201C" else "\u2018")
            } else {
                // 普通键盘上的那个引号键：交替交给引擎
                api.simulateKeySequence(if (isDouble) "\"" else "'")
            }
            return true
        }
        // 候选英文的 token 属于「输入增强」，同样不受「回退与AI」总开关影响。
        // 查不到英文时返回 true（吞键），否则用户按数字键下滑会莫名打出一个数字。
        val tipSlot = when (token) {
            BambooTokens.TIP -> 0
            else -> BambooTokens.TIP_SLOTS.indexOf(token).takeIf { it >= 0 }?.plus(1) ?: -1
        }
        if (tipSlot >= 0) {
            // 屏幕上显示哪一块就取哪一块：外语学习取第 N 个候选的英文，文曲星取圈号 N 的正文。
            val text =
                if (tipSlot == 0) {
                    TipBlocks.highlightedEnglish()
                } else {
                    TipBlocks.textAt(tipSlot)
                }
            if (text.isNullOrEmpty()) {
                // 落盘日志：主题绑了 token 但这一块没有第 N 条时会走到这里，排查时要看得见
                BambooLog.ai.d("tips", "token=$token block=${TipBlocks.current()} 没有第 $tipSlot 条，吞键")
                return true
            }
            // 只上屏内容，不要把中文候选一起提交：
            // commitComposition() 会「提交当前组句」= 先把第一候选上屏，再追加我们这句，
            // 结果是「中文候选 + 英文」两个都进编辑框。这里改成丢弃组句、只提交内容。
            api.clearComposition()
            service.commitText(text)
            BambooLog.ai.d("tips", "token=$token block=${TipBlocks.current()} -> $text")
            return true
        }
        // 「按住说话」属于语音链路，和「回退与AI」总开关无关（等价主题原来的 VoiceAssist）。
        // 正常长按时 KeyView 会先命中自己那一支、直接开始录音，根本走不到这里；
        // 这里是兜底：点按、外接键盘、或主题绑在别的 behavior 上时也能开始录音。
        if (token == BambooTokens.VOICE_AI) {
            runCatching {
                // Kodein 的 di.instance<T>() 返回 LazyDelegate，必须取一次真实例才能调方法
                val bar: InputBarDelegate by InputDependencyManager.getInstance().di.instance()
                bar.startVoiceHoldSession()
                BambooLog.ai.d("voice ai", "token voice ai -> startVoiceHoldSession")
            }.onFailure { Timber.w(it, "bamboo: token voice ai start failed") }
            return true
        }
        if (!BambooPrefs.enabled) return false
        when (token) {
            BambooTokens.REVERT ->
                // 与 `qq/` 完全同一份实现；是否生效只看主题有没有绑定，不看设置里的开关
                return doRevert(service, api, "token=BambooRevert")

            BambooTokens.CLEAR -> {
                // 先丢掉正在拼写的组合，再把编辑区清空（一次性调 InputConnection，不连发按键）
                api.clearComposition()
                val ok = EditorEdit.clearAll(service)
                toast(if (ok) "已清空" else "当前输入框不支持清空")
                Timber.d("bamboo: token clear -> %s", ok)
                return true
            }

            BambooTokens.DELETE_LINE -> {
                api.clearComposition()
                val ok = EditorEdit.deleteLine(service)
                toast(if (ok) "已删除整行" else "当前输入框不支持")
                Timber.d("bamboo: token deleteLine -> %s", ok)
                return true
            }

            BambooTokens.DELETE_WORD -> {
                api.clearComposition()
                val ok = EditorEdit.deleteWord(service)
                toast(if (ok) "已删除一个词" else "当前输入框不支持")
                Timber.d("bamboo: token deleteWord -> %s", ok)
                return true
            }

            BambooTokens.AI_SEND, BambooTokens.AI_CLIP -> {
                // 与组合键 Ctrl+; / Ctrl+Alt+; 完全等效：
                //  * 同一个出口 aiDispatch()，同样的目标模式、同样的发送内容；
                //  * 唯一区别是不受「快捷组合键」开关约束——主题绑了就一直有效。
                // 文曲星模式不吃 AI 总开关：和组合键入口保持同一条件
                if (!BambooPrefs.aiEnabled && armed?.key != TIPS_KEY) {
                    toast("AI 模式未开")
                    return true
                }
                // 没进模式且「未进模式时也发默认模式」关着 → 静默不动作（与组合键一致）。
                // 这里不预先清组合：清不清由 aiSend 按「丢弃组合」设置决定。
                aiDispatch(service, api, fromClipboard = token == BambooTokens.AI_CLIP)
                Timber.d("bamboo: token %s (mode=%s)", token, armed?.name ?: "default")
                return true
            }

            BambooTokens.AI_OFF -> {
                val names = exitAll()
                toast(
                    if (names.isEmpty()) {
                        "当前没有开启任何模式"
                    } else {
                        "已退出：" + names.joinToString("、")
                    },
                )
                return true
            }

            BambooTokens.GLOSSARY -> {
                GlossaryPrefs.enabled = !GlossaryPrefs.enabled
                toast(if (GlossaryPrefs.enabled) "已开启外语学习" else "已关闭外语学习")
                return true
            }

            BambooTokens.SETTINGS -> {
                val ctx = appContext
                if (ctx != null) {
                    mainHandler.post {
                        runCatching {
                            // AppUtils.launchMainToDest 是 private，这里按它的等价写法直接拉起设置页
                            val intent = Intent(ctx, MainActivity::class.java).apply {
                                action = Intent.ACTION_RUN
                                putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, NavigationRoute.BambooAction)
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                                )
                            }
                            ctx.startActivity(intent)
                        }.onFailure { Timber.w(it, "bamboo: open settings failed") }
                    }
                }
                return true
            }
        }

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
        BambooLog.ai.d(
            "revert",
            "start left=$left last=\"${RevertLedger.lastText()}\" " +
                "max=${BambooPrefs.revertMaxCodePoints} total=${RevertLedger.size()}",
        )
        if (left <= 0) return 0
        val want = minOf(left, BambooPrefs.revertMaxCodePoints)
        val done = deleteCodePoints(service, want)
        BambooLog.ai.d("revert", "delete want=$want done=$done")
        if (done > 0) RevertLedger.consume(done)
        BambooLog.ai.d("revert", "after total=${RevertLedger.size()}")
        return done
    }

    /**
     * 唯一的回退实现。
     *
     * `qq/` 触发码与主题里的 `BambooRevert` 都只调这里，行为完全一致：
     * 丢掉正在拼写的组合 → 退掉账本最近一笔 → 账本真的空了才提示。
     */
    private suspend fun doRevert(service: TrimeInputMethodService, api: RimeApi, source: String): Boolean {
        BambooLog.ai.d("revert", "trigger $source")
        api.clearComposition()
        val deleted = revert(service)
        Timber.d("bamboo: revert %s -> %d", source, deleted)
        if (deleted <= 0 && RevertLedger.size() == 0) toast("没有可回退的内容")
        return true
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
                if (before.isEmpty()) {
                    // 编辑器不提供光标前文本（部分 WebView / 自绘编辑器）。
                    // 无法核验，但删除指令仍然有效，按请求量记账。
                    BambooLog.ai.d("revert", "no before-text -> blind delete $count")
                    val blind = runCatching {
                        ic.beginBatchEdit()
                        val ok = runCatching { ic.deleteSurroundingTextInCodePoints(count, 0) }
                            .getOrDefault(false)
                        if (!ok) runCatching { ic.deleteSurroundingText(count, 0) }
                        ic.endBatchEdit()
                        true
                    }.getOrDefault(false)
                    return@withContext if (blind) count else 0
                }

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

                // `deleteSurroundingText*` 在编辑器侧是异步生效的，
                // 立刻回读拿到的往往还是旧文本（差值 0），会把账本卡死。
                // 先等一轮 IPC，再回读；仍量不到就信编辑器返回的 ok，按请求量记账。
                val beforeCp = before.codePointCount(0, before.length)
                var measured = 0
                if (ok) {
                    delay(60)
                    val after = ic.getTextBeforeCursor(window, 0)?.toString().orEmpty()
                    measured = beforeCp - after.codePointCount(0, after.length)
                }
                if (measured > 0) measured.coerceAtMost(count) else if (ok) count else 0
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

    /**
     * 「发一次 AI」的唯一出口：组合键（`Ctrl+;` / `Ctrl+Alt+;`）、字母码（`jt;`）、
     * 主题动作名（`BambooAiSend` / `BambooAiClip`）都走这里，避免三处各写一套判断而走偏。
     *
     * 语义固定为：
     *  * 已在某个模式 → 发给该模式；
     *  * 没进模式且开了「未进模式时也发默认模式」→ 发给默认模式；
     *  * 没进模式且该开关关着 → **什么都不做**，返回 false 由调用方决定是否放行按键。
     *
     * 这里**不**清组合：清不清由 [aiSend] 按「丢弃组合」设置决定。之前 token 发不出编码
     * 候选，就是因为调用方在进来之前先把组合清掉了。
     *
     * @return true 表示已经发起请求（按键应被吞掉）。
     */
    private fun aiDispatch(
        service: TrimeInputMethodService,
        api: RimeApi,
        fromClipboard: Boolean,
    ): Boolean {
        if (armed == null && !BambooPrefs.aiFallbackDefault) return false
        val target = armed ?: defaultMode() ?: return false
        aiSend(service, api, target, fromClipboard = fromClipboard)
        return true
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
                if (mode.key == TIPS_KEY) {
                    if (!WenquxingPrefs.enabled) {
                        toast("文曲星已在设置里关闭")
                        return@launch
                    }
                    // 发剪贴板那一条查剪贴板，其余查候选/光标前文本
                    val query = if (fromClipboard) readClipboard(service) else tipsQueryText(service, api)
                    if (query.isNullOrBlank()) {
                        toast("没有对应条目")
                        return@launch
                    }
                    // 锚点必须与显示层比对的值同源（PreeditUi 传的是 composition.preedit）
                    val preedit = runCatching { api.compositionCached.preedit }.getOrNull().orEmpty()
                    val count = WenquxingTips.show(query, preedit)
                    toast(if (count > 0) "共匹配 $count 条" else "没有对应条目")
                    // 引擎状态没变，靠 emitResponse 那条路刷不动编码行，直接让候选视图重绘。
                    // 没有编码行时（剪贴板查询、进模式时清过组合）用提示本身合成一行显示。
                    service.refreshCandidatesPreedit()
                    return@launch
                }
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

    /**
     * 文曲星查哪段文本：正在拼写就用首选候选；否则用光标前那句话，再退到刚上屏的那句。
     * 与 AI 取值同一个来源，只是不发给模型。
     */
    private suspend fun tipsQueryText(service: TrimeInputMethodService, api: RimeApi): String? {
        if (api.getRawInput().isNotEmpty()) {
            val first = runCatching { api.getCandidates(0, 1).firstOrNull()?.text }.getOrNull()
            if (!first.isNullOrBlank()) return first
        }
        val sentence = runCatching { sentenceBeforeCursor(service) }.getOrNull()
        if (!sentence.isNullOrBlank()) return sentence
        return RevertLedger.lastText().takeIf { it.isNotBlank() }
    }

    /** 文曲星模式是否已进入（供提示层判断「直接显示」要不要自动查）。 */
    fun isTipsArmed(): Boolean = armed?.key == TIPS_KEY

    /** 设置里改了使用模式时调用：把已进入的文曲星模式退掉。 */
    fun exitTipsMode() {
        if (armed?.key != TIPS_KEY) return
        armed = null
        runCatching { WenquxingTips.clear() }
        Timber.d("bamboo: tips mode exited by preference change")
    }

    /** 文曲星借用一个 AiMode 当状态位：只在 `key` 上区分，不查提示词、不发请求。 */
    private fun tipsMode(): AiMode = AiMode(BambooPrefs.wenquxingCode, "文曲星", "", TIPS_KEY)

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

    // ---------------------------------------------------------------- 语音发AI

    /**
     * 语音识别结束后的 AI 出口。由 [VoiceAi] 调用。
     *
     * 语义（已与用户确认）：
     *  * 没进任何模式 / 本页被关掉 / 进的是文曲星 → 返回 false，交回原来的「只上屏」流程；
     *  * 进了普通 AI 模式 → 发这段文字，模式保持不动；
     *  * 结果**追加**在原文后面，不删除也不替换原文。
     *
     * @return true 表示已经接管（调用方不要再自己上屏）。
     */
    internal fun dispatchVoiceText(
        service: TrimeInputMethodService,
        text: String,
    ): Boolean {
        val source = text.trim()
        if (source.isEmpty()) return false
        if (!VoiceAiPrefs.enabled) return false
        if (source.length < VoiceAiPrefs.minChars) return false

        val mode = armed ?: if (VoiceAiPrefs.alwaysSend) defaultMode() else null
        if (mode == null) return false
        // 文曲星不是「发请求」的模式：语音进来只上屏，不打扰它
        if (mode.key == TIPS_KEY) return false

        val send = if (source.length > VoiceAiPrefs.maxChars) source.take(VoiceAiPrefs.maxChars) else source
        return sendVoiceRequest(service, mode, send, source)
    }

    /**
     * 语音这条路的请求体：与 [aiSend] 的区别只有两点——
     *  * 文本由语音识别给定，不去读组合/剪贴板；
     *  * 失败时按设置可以把识别到的原文补上屏。
     */
    private fun sendVoiceRequest(
        service: TrimeInputMethodService,
        mode: AiMode,
        sendText: String,
        sourceText: String,
    ): Boolean {
        if (inFlight) {
            toast("AI 正在处理上一条")
            return false
        }
        inFlight = true
        val epoch = editorEpoch
        val prompt = stackedVoicePrompt(mode)
        val timeoutMs = VoiceAiPrefs.timeoutSec * 1000
        service.lifecycleScope.launch {
            try {
                if (VoiceAiPrefs.showStatus) toast("语音已发给「${mode.name}」，处理中…")
                val result = AiClient.chat(prompt = prompt, text = sendText, timeoutMs = timeoutMs)
                if (editorEpoch != epoch) {
                    Timber.i("bamboo: editor changed, drop voice ai result")
                    return@launch
                }
                result
                    .onSuccess { out ->
                        withContext(Dispatchers.Main) {
                            if (out.isNotEmpty()) {
                                if (BambooPrefs.aiSendAfter) service.commitText(" ")
                                service.commitText(out)
                                RevertLedger.record(out)
                            }
                        }
                        Timber.i("bamboo: voice ai result committed, mode kept: %s", mode.name)
                    }.onFailure { e ->
                        Timber.w(e, "bamboo: voice ai failed")
                        withContext(Dispatchers.Main) {
                            if (VoiceAiPrefs.commitOnFail) {
                                service.commitText(sourceText)
                                RevertLedger.record(sourceText)
                            }
                            toast("AI 失败：${e.message ?: "未知错误"}")
                        }
                    }
            } catch (e: Throwable) {
                Timber.w(e, "bamboo: voice ai send failed")
                withContext(Dispatchers.Main) {
                    toast("AI 失败：${e.message ?: "未知错误"}")
                }
            } finally {
                inFlight = false
            }
        }
        return true
    }

    /** 语音纠错提示词 + 模式提示词：两层都进 system，user 只放识别原文。 */
    private fun stackedVoicePrompt(mode: AiMode): String {
        if (!VoiceAiPrefs.stackFixPrompt) return mode.prompt
        val fix = VoiceAiPrefs.fixPrompt.trim()
        if (fix.isEmpty()) return mode.prompt
        val main = mode.prompt.trim()
        return if (main.isEmpty()) fix else "$fix\n\n$main"
    }

    // ---------------------------------------------------------------- 小工具

    private fun toast(text: String) {
        mainHandler.post {
            val ctx = appContext ?: return@post
            runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
        }
    }

    /** 软键盘按键 → 字符。只覆盖触发码用得到的键。 */
    /**
     * 物理键盘专用：判断这次按键有没有可能是苍竹的码或组合键。
     *
     * 物理按键走 [com.osfans.trime.ime.core.TrimeInputMethodService.onKeyDown] →
     * `forwardKeyEvent`，不经过虚拟键盘的 `CommonKeyboardActionListener`，所以要先筛一遍，
     * 只把可能的键交给 [handleKey]，其余按键保持原路径，不影响正常输入。
     */
    fun mightBeShortcut(keyCode: Int, metaState: Int): Boolean {
        if (!BambooPrefs.enabled) return false
        // 带 Ctrl/Alt/Meta 的一律交给 handleKey 判定（快捷组合键、双击发送都在那里面）。
        if (metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) != 0) {
            return true
        }
        // 只有 Shift 时不拦：Shift+; 是冒号，应当当普通字符打出去。
        if (metaState and KeyEvent.META_SHIFT_ON != 0) return false
        // 文曲星的「上屏 / 轮换」键（默认逗号、句号），键名跟着设置走。
        if (WenquxingTips.isTipsKey(keyCode)) return true
        val ch = charOf(keyCode) ?: return false
        // 空格是上屏键，不参与码的判定。
        if (ch == ' ') return false
        return ch in terminatorChars()
    }

    /** 各码的末字符集合，随设置实时变化；用于物理键盘的按键预筛。 */
    private fun terminatorChars(): Set<Char> {
        val out = HashSet<Char>(8)
        fun addCode(code: String?) {
            val last = code?.trim()?.lastOrNull() ?: return
            if (last != ' ') out.add(last)
        }
        for (mode in BambooPrefs.aiModes) addCode(mode.code)
        for (code in BambooPrefs.revertTriggers) addCode(code)
        addCode(BambooPrefs.aiClipCode)
        addCode(BambooPrefs.aiExitCode)
        addCode(BambooPrefs.wenquxingCode)
        return out
    }

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
