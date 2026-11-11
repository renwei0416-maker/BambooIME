package com.osfans.trime.actions.ocr

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.osfans.trime.actions.bamboo.BambooActions
import com.osfans.trime.actions.handwriting.HandwritingLog
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.window.BoardWindowManager
import timber.log.Timber

/**
 * 拍照识字的「确认卡片」。
 *
 * 位置在输入法窗口内部、键盘的上方（由 [com.osfans.trime.ime.core.InputView] 挂载），
 * 所以卡片出现时键盘原样留在下面、整个输入法窗口变高，退出卡片后自动恢复。
 *
 * 卡片激活期间，[TrimeInputMethodService.commitText] 与 Rime 的提交消息都不再发给目标
 * App，而是写进这里的编辑框（和 CyIME 的 InputTarget 一个思路）——这样用户可以继续用
 * 键盘打字修改识别结果，改完再「上屏」。
 */
object OcrCard {
    /** 卡片是否激活；输出入口据此决定是否改道。 */
    @Volatile
    var isActive: Boolean = false
        private set

    /**
     * 窗口被收起 / 重建前先记下文本：`InputView.startInput()` 会据此把卡片放回来。
     *
     * 只有 [close]（收起）会清它，切 App、换输入框都留着——和手写板、识字面板一样能保持住。
     */
    @Volatile
    var pendingText: String? = null
        private set

    /** 「上屏」时临时置 true，让这一次 [TrimeInputMethodService.commitText] 真的发给 App。 */
    @Volatile
    var bypass: Boolean = false

    private var host: ViewGroup? = null

    private var edit: EditText? = null

    private var density = 2f

    private var built = false

    /** 建视图时用的不透明度；设置改了要重建，否则颜色不会变。 */
    private var builtOpacity = -1

    /** 卡片根视图；换宿主（切主题会重建 InputView）时要把它搬过去。 */
    private var card: View? = null

    fun bind(host: ViewGroup) {
        // 切主题会重建 InputView，宿主跟着换；旧宿主已经 detach，卡片必须搬过去，
        // 否则新宿主是个空壳、卡片永远出不来（而 isActive 又会吞掉键盘输入）。
        if (this.host !== host) {
            card?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
            built = false
            this.host = host
            HandwritingLog.d("card", "bind new host=${System.identityHashCode(host)} rebuild")
        }
        if (built && builtOpacity == cardOpacity()) return
        // 重建（比如改了不透明度）时旧卡片还挂在宿主上，必须先摘掉，否则会叠出两张
        card?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        build(host)
    }

    /** 显示卡片并填入文本。 */
    fun activate(text: String) {
        isActive = true
        pendingText = text
        val box = edit
        if (box != null) {
            box.setText(text)
            box.setSelection(box.text.length)
        }
        host?.visibility = View.VISIBLE
    }

    /**
     * 收起卡片视图（不再接管输出），但**保留** [pendingText]：
     * 输入法窗口被系统收起 / 重建时也会走到这里，文本得留到下次 startInput 放回去。
     */
    fun deactivate() {
        isActive = false
        bypass = false
        host?.visibility = View.GONE
        edit?.setText("")
    }

    fun text(): String = edit?.text?.toString().orEmpty()

    /**
     * 卡片不再是「当前状态」时清掉留底（面板接管、用户点「返回」/「收起」都算）。
     *
     * [deactivate] 保留文本是为了「窗口被系统收起、切 App」时能放回来；
     * 但用户主动离开卡片时不能留，否则下次 `startInput` 会把旧卡片顶回面板上。
     */
    fun clearPending() {
        pendingText = null
    }

    /** Rime 提交/候选上屏时调到这里：插入到光标处。 */
    fun append(text: String) {
        val box = edit ?: return
        if (text.isEmpty()) return
        val value = box.text ?: return
        val start = box.selectionStart.coerceIn(0, value.length)
        val end = box.selectionEnd.coerceIn(start, value.length)
        value.replace(start, end, text)
        box.setSelection((start + text.length).coerceIn(0, box.text.length))
    }

    fun newline() = append("\n")

    /** 退格：有选区删选区，否则删光标前一个码点（中文一个字一个）。 */
    fun backspace() {
        val box = edit ?: return
        val value = box.text ?: return
        val start = box.selectionStart
        val end = box.selectionEnd
        if (start != end) {
            val from = minOf(start, end).coerceAtLeast(0)
            val to = maxOf(start, end).coerceAtMost(value.length)
            value.delete(from, to)
            box.setSelection(from)
            return
        }
        if (start <= 0) return
        val from = value.toString().offsetByCodePoints(start, -1)
        value.delete(from, start)
        box.setSelection(from)
    }

    fun clear() {
        edit?.setText("")
    }

    fun moveCursor(forward: Boolean) {
        val box = edit ?: return
        val value = box.text ?: return
        val pos = box.selectionEnd.coerceIn(0, value.length)
        val plain = value.toString()
        val next =
            runCatching {
                if (forward) plain.offsetByCodePoints(pos, 1) else plain.offsetByCodePoints(pos, -1)
            }.getOrDefault(pos)
        box.setSelection(next.coerceIn(0, value.length))
    }

    fun copy() {
        val value = text()
        if (value.isEmpty()) {
            toast("没有内容")
            return
        }
        val service = TrimeInputMethodService.current ?: return
        val cm = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (cm == null) {
            toast("复制失败")
            return
        }
        runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("ocr", value)) }
            .onSuccess { toast("已复制") }
            .onFailure {
                Timber.w(it, "ocr: copy failed")
                toast("复制失败")
            }
    }

    /** 上屏：把卡片里的文字发给目标 App（这一次跳过改道），卡片保留可以继续改。 */
    fun commit() {
        val value = text()
        if (value.isEmpty()) {
            toast("没有内容")
            return
        }
        val service = TrimeInputMethodService.current ?: return
        bypass = true
        try {
            service.commitText(value)
        } finally {
            bypass = false
        }
        toast("已上屏")
    }

    /** 「返回」：收起卡片，回到相机面板继续识别（原来叫「重拍」）。 */
    fun retake() {
        val manager = BoardWindowManager.current ?: return
        // 「返回」= 离开卡片、回到面板（相册来的回相册，相机来的回相机），
        // 文本不再留着，否则切 App 回来会把卡片顶回面板上。
        clearPending()
        TrimeInputMethodService.current?.currentInputView?.hideOcrCard()
        // 告诉面板：这次挂载是卡片返回，别把整段/单字压回整段
        runCatching { OcrWindow.markReturnToFrame() }
            .onFailure { Timber.w(it, "ocr: mark return failed") }
        runCatching { manager.attachWindow(OcrWindow.instance) }
            .onFailure { Timber.w(it, "ocr: retake failed") }
    }

    /**
     * 收起：回到正常键盘，并结束这次识字会话。
     *
     * 不清会话的话，下次进「拍」会被 restore 分支直接拉回上次的相册照片界面。
     */
    fun close() {
        // 用户主动收起 = 会话结束，文本不再保留（否则下次进输入框卡片又冒出来）
        pendingText = null
        TrimeInputMethodService.current?.currentInputView?.hideOcrCard()
        runCatching { OcrWindow.instance.resetSession() }
            .onFailure { Timber.w(it, "ocr card: reset session failed") }
    }

    // ---------------------------------------------------------------- 视图

    private fun build(parent: ViewGroup) {
        built = true
        val dm = parent.resources.displayMetrics
        density = dm.density

        fun color(key: String, fallback: Int): Int =
            runCatching { ColorManager.getColorOr(key, fallback) }
                .onFailure { Timber.w(it, "ocr card: color '$key' unavailable") }
                .getOrDefault(fallback)

        val back = color("back_color", 0xFF202420.toInt())
        val keyBgRaw = color("key_back_color", 0xFF2B302B.toInt())
        val keyTextRaw = color("key_text_color", 0xFFF2F0E8.toInt())
        val accentRaw = color("hilited_key_back_color", 0xFF6FAE8F.toInt())
        // 主题配色可能让文字和底色糊在一起（图片键帽主题解析失败时尤其明显），做一次可读性兜底
        // 卡片会挡住 App 的输入框，按设置把三块底色做成半透明（文字色不动）
        val opacity = cardOpacity()
        builtOpacity = opacity

        fun tint(color: Int): Int =
            Color.argb((opacity * 255 / 100).coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

        val keyBg = keyBgRaw
        val keyText = ThemeContrast.readableOn(keyBgRaw, keyTextRaw)
        val accent = accentRaw
        // 卡片底色上也要看得清：标题按钮用的是 accent，正文用 keyText
        val onAccent = ThemeContrast.readableOn(accent, keyTextRaw)

        val root =
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = rounded(tint(back), 12)
                clipToOutline = true
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_HEIGHT_DP))
            }

        edit =
            EditText(parent.context).apply {
                setTextColor(keyText)
                textSize = 16f
                gravity = Gravity.TOP or Gravity.START
                background = rounded(tint(keyBg), 8)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                isSingleLine = false
                maxLines = 5
                minLines = 1
                isVerticalScrollBarEnabled = true
                // 卡片在输入法窗口里，不能让系统再弹一个软键盘
                showSoftInputOnFocus = false
                isFocusable = true
                isFocusableInTouchMode = true
            }
        root.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(
            buttonRow(
                parent.context,
                listOf(
                    "删字" to { backspace() },
                    "清空" to { clear() },
                    "←" to { moveCursor(false) },
                    "→" to { moveCursor(true) },
                ),
                tint(accent),
                onAccent,
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)),
        )

        root.addView(
            buttonRow(
                parent.context,
                listOf(
                    "返回" to { retake() },
                    "复制" to { copy() },
                    "上屏" to { commit() },
                    "收起" to { close() },
                ),
                tint(accent),
                onAccent,
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)),
        )

        parent.addView(root)
        card = root
        HandwritingLog.d(
            "card",
            "build host=${System.identityHashCode(parent)} children=" + parent.childCount,
        )
    }

    private fun buttonRow(
        context: android.content.Context,
        items: List<Pair<String, () -> Unit>>,
        bg: Int,
        fg: Int,
    ): LinearLayout {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        items.forEach { (label, action) ->
            val button =
                TextView(row.context).apply {
                    text = label
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(fg)
                    background = rounded(bg, 6)
                    isFocusable = false
                    setOnClickListener { action() }
                }
            val lp =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(2), dp(1), dp(2), dp(1))
                }
            row.addView(button, lp)
        }
        return row
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun cardOpacity(): Int = runCatching { OcrPrefs.cardOpacity }.getOrDefault(92)

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun toast(message: String) {
        runCatching {
            Toast.makeText(TrimeInputMethodService.current, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** 卡片高度：约 3 行文字 + 两行按钮。 */
    private const val CARD_HEIGHT_DP = 150

    private const val ROW_HEIGHT_DP = 30
}
