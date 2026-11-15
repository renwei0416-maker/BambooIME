// SPDX-FileCopyrightText: 2025 BambooIME
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.actions.wanxiang

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizePx
import com.osfans.trime.actions.bamboo.AiPanelWindow
import com.osfans.trime.actions.handwriting.HandwritingWindow
import com.osfans.trime.actions.ocr.OcrWindow
import com.osfans.trime.actions.ocr.ThemeContrast
import com.osfans.trime.data.packaging.SchemaPackageManager
import com.osfans.trime.data.packaging.SchemaPackStore
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import timber.log.Timber

/**
 * 键盘与方案面板（主题 token `BambooKeyPanel`，也可由「长按切换键」打开）。
 *
 * 为什么不用主题自带的切换弹窗：
 *  * 那个弹窗一格只能发一个按键动作，**跑不了我们的代码**——切不了方案、写不了
 *    [WanxiangPrefs.layout]、也没法显示状态；
 *  * 它固定 5 列、只能放一段文字或一个图标，一屏放不下所有入口。
 *
 * 这个面板一屏 4 列 × 3 行放完所有入口，点击一次把该做的事一起做完：
 * 切键盘 + 回写形态（[KeyboardWindow.switchKeyboard] 的 fromUser 分支会回写）、
 * 需要时切方案、然后收起回键盘。
 */
class KeyPanelWindow : BoardWindow.NoBarBoardWindow() {
    private val service: TrimeInputMethodService
        get() = TrimeInputMethodService.current
            ?: error("key panel: IME service is not available")

    private val windowManager: BoardWindowManager
        get() = BoardWindowManager.current
            ?: error("key panel: board window manager is not available")

    /** 最近挂上的键盘窗口（挂载时记一份，见 [KeyboardWindow.Companion.instance]）。 */
    private fun keyboard(): KeyboardWindow? = KeyboardWindow.instance

    private var density = 3f
    private var rootView: View? = null
    private var bodyBox: LinearLayout? = null
    private var statusView: TextView? = null
    private var baseTab: TextView? = null
    private var proTab: TextView? = null

    private var colorBack = 0xFF202420.toInt()
    private var colorKeyBg = 0xFF2B302B.toInt()
    private var colorKeyText = 0xFFF2F0E8.toInt()
    private var colorSub = 0xFF8A8A80.toInt()
    private var colorAccent = 0xFF6FAE8F.toInt()

    override fun onCreateView(): View {
        val created =
            runCatching { buildView() }
                .onFailure { Timber.e(it, "key panel: buildView failed") }
                .getOrElse { plainView() }
        rootView = created
        return created
    }

    override fun onAttached() {
        active = true
        rebuild()
    }

    override fun onDetached() {
        active = false
    }

    override fun toString(): String = "KeyPanelWindow"

    // ---------------------------------------------------------------- 视图

    private fun buildView(): View {
        density = context.resources.displayMetrics.density
        val gap = dp(4)

        fun color(key: String, fallback: Int): Int =
            runCatching { ColorManager.getColorOr(key, fallback) }
                .onFailure { Timber.w(it, "key panel: color '$key' unavailable") }
                .getOrDefault(fallback)

        colorBack = color("back_color", 0xFF202420.toInt())
        colorKeyBg = color("key_back_color", 0xFF2B302B.toInt())
        colorKeyText =
            ThemeContrast.readableOn(colorKeyBg, color("key_text_color", 0xFFF2F0E8.toInt()))
        colorSub =
            ThemeContrast.readableOn(
                colorBack,
                color("key_symbol_color", 0xFF8A8A80.toInt()),
                ThemeContrast.MIN_SUB,
            )
        colorAccent = color("hilited_key_back_color", 0xFF6FAE8F.toInt())

        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(gap, gap, gap, gap)
                background =
                    GradientDrawable().apply {
                        setColor(colorBack)
                        cornerRadius = dp(12).toFloat()
                    }
                clipToOutline = true
            }

        // 顶部：标题 + 当前状态 + Base/Pro 胶囊 + 收起
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, 0, 0)
            }
        header.addView(
            TextView(context).apply {
                text = "键盘与方案"
                setTextColor(colorKeyText)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        statusView =
            TextView(context).apply {
                textSize = 10f
                includeFontPadding = false
                setTextColor(colorSub)
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                setPadding(dp(6), 0, dp(4), 0)
            }
        header.addView(statusView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // Base/Pro 两段胶囊：装不装 Pro 决定能不能点，省一格位置
        val capsule =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background =
                    GradientDrawable().apply {
                        setColor(colorKeyBg)
                        cornerRadius = dp(8).toFloat()
                    }
                setPadding(dp(2), dp(2), dp(2), dp(2))
                clipToOutline = true
            }
        baseTab = capsuleTab("Base") { switchPackage(SchemaPackStore.BASE_ID) }
        proTab = capsuleTab("Pro") { switchPackage(SchemaPackStore.PRO_ID) }
        capsule.addView(baseTab, LinearLayout.LayoutParams(dp(40), dp(24)))
        capsule.addView(proTab, LinearLayout.LayoutParams(dp(36), dp(24)))
        header.addView(
            capsule,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(4) },
        )

        header.addView(
            smallButton("收起") { leaveToKeyboard() },
            LinearLayout.LayoutParams(WRAP_CONTENT, dp(26)),
        )
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, dp(34)))

        // 主区：4 列 × 3 行，**不滚动**——用户要求一屏看到所有入口
        bodyBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            bodyBox,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) },
        )
        return root
    }

    private fun plainView(): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(0xFF202420.toInt()) }
            addView(
                TextView(context).apply {
                    text = "面板没能画出来，点下面回键盘"
                    setTextColor(0xFFF2F0E8.toInt())
                    textSize = 14f
                },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
            )
            addView(
                smallButton("返回键盘") { leaveToKeyboard() },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(34)),
            )
        }

    private data class Cell(
        val id: String,
        val title: String,
        val sub: String,
        val icon: String,
        val kind: Kind,
        /** 键盘格子放 keyboardId，功能格子放动作名。 */
        val arg: String = "",
    )

    private enum class Kind { KEYBOARD, ACTION }

    private fun rebuild() {
        val box = bodyBox ?: return
        val pkg = WanxiangCustom.activePackage()
        val isPro = WanxiangCustom.isPro(pkg)
        statusView?.text = buildString {
            append(if (isPro) "Pro" else "Base")
            append(" · ").append(layoutName(WanxiangPrefs.layout))
            append(" · ").append(if (WanxiangPrefs.encode == WanxiangPrefs.ENCODE_SHUANGPIN) "双拼" else "全拼")
            if (WanxiangPrefs.aux != WanxiangPrefs.AUX_NONE) append(" · 辅助码")
        }
        baseTab?.let { styleTab(it, !isPro, SchemaPackStore.isInstalled(SchemaPackStore.BASE_ID)) }
        proTab?.let { styleTab(it, isPro, SchemaPackStore.isInstalled(SchemaPackStore.PRO_ID)) }

        box.removeAllViews()
        val current = keyboard()?.currentId.orEmpty()

        val row1 =
            listOf(
                Cell("k26", "26 键", "", "cmd_keyboard", Kind.KEYBOARD, "default"),
                Cell("kt9", "九宫格", "固定全拼", "cmd_dialpad", Kind.KEYBOARD, "wanxiang_t9"),
                Cell("k18", "18 键", "/18jian", "cmd_keyboard", Kind.KEYBOARD, "wanxiang_18key"),
                Cell("k14", "14 键", "/14jian", "cmd_keyboard", Kind.KEYBOARD, "wanxiang_14key"),
            )
        val row2 =
            listOf(
                Cell("kqj", "四排七键", "", "cmd_view_grid", Kind.KEYBOARD, "qijian"),
                Cell("kxh", "小鹤", "", "cmd_bird", Kind.KEYBOARD, "xiaohe"),
                Cell("ken", "English", "", "cmd_alphabetical_variant", Kind.KEYBOARD, "wanxiang_english"),
                Cell("asym", "符号", "", "cmd_symbol", Kind.ACTION, "symbol"),
            )
        val row3 =
            listOf(
                Cell("ahw", "手写", "", "cmd_draw", Kind.ACTION, "handwriting"),
                Cell("aocr", "拍照", "", "cmd_camera", Kind.ACTION, "ocr"),
                Cell("aai", "AI 模式", "", "cmd_robot", Kind.ACTION, "ai"),
                Cell("aclip", "剪贴板", "", "cmd_clipboard_text", Kind.ACTION, "clipboard"),
            )

        addRow(box, row1, current)
        addRow(box, row2, current)
        if (WanxiangPrefs.panelShowActions) addRow(box, row3, current)
    }

    private fun addRow(
        box: LinearLayout,
        cells: List<Cell>,
        current: String,
    ) {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        for (cell in cells) {
            row.addView(
                cellView(cell, cell.kind == Kind.KEYBOARD && cell.arg == current),
                LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                },
            )
        }
        box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun cellView(
        item: Cell,
        active: Boolean,
    ): View {
        val cell =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(2), dp(3), dp(2), dp(3))
                background =
                    GradientDrawable().apply {
                        setColor(if (active) colorAccent else colorKeyBg)
                        cornerRadius = dp(9).toFloat()
                    }
            }

        // 一行「图标 + 名字 + 勾」：勾放右侧，当前项一眼可见
        val top =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        val iconView =
            ImageView(context).apply {
                val drawable =
                    runCatching {
                        IconicsDrawable(context, item.icon).apply {
                            sizePx = dp(20)
                            colorFilter =
                                PorterDuffColorFilter(
                                    if (active) colorBack else colorKeyText,
                                    PorterDuff.Mode.SRC_IN,
                                )
                        }
                    }.getOrNull()
                if (drawable != null) setImageDrawable(drawable) else visibility = View.GONE
            }
        top.addView(iconView, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(3) })
        top.addView(
            TextView(context).apply {
                text = item.title
                textSize = 12f
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (active) colorBack else colorKeyText)
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 8, 12, 1, TypedValue.COMPLEX_UNIT_SP,
                )
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        if (active) {
            top.addView(
                TextView(context).apply {
                    text = "✓"
                    textSize = 12f
                    includeFontPadding = false
                    setTextColor(colorBack)
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(2) },
            )
        }
        cell.addView(top, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        if (item.sub.isNotEmpty()) {
            cell.addView(
                TextView(context).apply {
                    text = item.sub
                    textSize = 9f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = 1
                    setTextColor(if (active) colorBack else colorSub)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
        }

        cell.setOnClickListener { onTapped(item) }
        return cell
    }

    private fun onTapped(item: Cell) {
        when (item.kind) {
            Kind.KEYBOARD -> {
                val target = item.arg
                if (keyboard()?.presetIds?.contains(target) != true) {
                    toast("当前主题里没有这个键盘")
                    return
                }
                leaveToKeyboard()
                runCatching { KeyboardWindow.instance?.switchKeyboard(target, fromUser = true) }
                    .onFailure { Timber.w(it, "key panel: switchKeyboard failed") }
            }
            Kind.ACTION -> {
                when (item.arg) {
                    "symbol" -> {
                        if (!windowManager.isAttached(HandwritingWindow.instance)) {
                            windowManager.attachWindow(HandwritingWindow.instance)
                        }
                        HandwritingWindow.instance.openPanelStandalone(
                            com.osfans.trime.actions.handwriting.SymbolPages.PAGE_SYMBOL,
                        )
                    }
                    "handwriting" -> windowManager.attachWindow(HandwritingWindow.instance)
                    "ocr" -> windowManager.attachWindow(OcrWindow.instance)
                    "ai" -> windowManager.attachWindow(AiPanelWindow.instance)
                    "clipboard" -> windowManager.attachWindow(ClipboardWindow())
                    else -> toast("还没接这个入口")
                }
            }
        }
    }

    private fun switchPackage(target: String) {
        val name = if (target == SchemaPackStore.PRO_ID) "Pro" else "Base"
        if (!SchemaPackStore.isInstalled(target)) {
            toast("还没装 $name 词库：设置 → 万象拼音 → 方案包与下载")
            return
        }
        if (SchemaPackStore.isActive(target)) {
            toast("已经在用 $name")
            return
        }
        // Pro 就是「双拼 + 辅助码」，Base 不带辅助码：这里不做推断，就按这个固定语义
        WanxiangPrefs.aux =
            if (target == SchemaPackStore.PRO_ID) WanxiangPrefs.AUX_DIRECT else WanxiangPrefs.AUX_NONE
        WanxiangPrefs.sanitize()
        if (!WanxiangCustom.writeAll(target)) {
            toast("写配置失败，去设置页看看")
            return
        }
        SchemaPackageManager.setActivePackageId(target)
        toast("正在切到 $name，部署约 2–4 分钟")
        runCatching { RimeDaemon.restartRime(fullCheck = true) }
            .onFailure { Timber.w(it, "key panel: restart rime failed") }
        rebuild()
    }

    private fun styleTab(
        tab: TextView,
        selected: Boolean,
        installed: Boolean,
    ) {
        tab.setTextColor(
            when {
                !installed -> colorSub
                selected -> colorBack
                else -> colorKeyText
            },
        )
        tab.background =
            GradientDrawable().apply {
                setColor(
                    when {
                        selected -> colorAccent
                        else -> colorKeyBg
                    },
                )
                cornerRadius = dp(6).toFloat()
            }
        tab.alpha = if (installed) 1f else 0.5f
    }

    private fun capsuleTab(
        label: String,
        onTap: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun layoutName(layout: Int): String =
        when (layout) {
            WanxiangPrefs.LAYOUT_18 -> "18 键"
            WanxiangPrefs.LAYOUT_14 -> "14 键"
            WanxiangPrefs.LAYOUT_9 -> "九宫格"
            else -> "26 键"
        }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
            .onFailure { Timber.w(it, "key panel: toast failed") }
    }

    private fun smallButton(
        label: String,
        onTap: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(colorKeyText)
            setPadding(dp(9), 0, dp(9), 0)
            background =
                GradientDrawable().apply {
                    setColor(colorKeyBg)
                    cornerRadius = dp(8).toFloat()
                }
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun leaveToKeyboard() {
        active = false
        runCatching { windowManager.attachWindow(KeyboardWindow) }
            .onFailure { Timber.w(it, "key panel: back to keyboard failed") }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    companion object {
        /** 主题 preset 里写的文本（`text = "BambooKeyPanel"`）。 */
        const val TOKEN = "BambooKeyPanel"

        fun isToken(text: String): Boolean = text.equals(TOKEN, ignoreCase = true)

        val instance: KeyPanelWindow by lazy { KeyPanelWindow() }

        /** 面板是否「开着」——窗口重建后靠它把面板接回来。 */
        @Volatile
        var active: Boolean = false
            private set

        fun markInactive() {
            active = false
        }

        /** 由「长按切换键」调用：把面板挂到键盘区。 */
        fun open() {
            val wm = BoardWindowManager.current ?: return
            if (wm.isAttached(instance)) return
            runCatching { wm.attachWindow(instance) }
                .onFailure { Timber.w(it, "key panel: open failed") }
        }
    }
}
