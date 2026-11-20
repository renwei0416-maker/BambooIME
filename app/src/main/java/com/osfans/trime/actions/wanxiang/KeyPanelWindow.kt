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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizePx
import com.osfans.trime.actions.bamboo.AiPanelWindow
import com.osfans.trime.actions.glossary.GlossaryPrefs
import com.osfans.trime.actions.handwriting.HandwritingWindow
import com.osfans.trime.actions.ocr.OcrWindow
import com.osfans.trime.actions.ocr.ThemeContrast
import com.osfans.trime.data.packaging.PackageSchemaList
import com.osfans.trime.data.packaging.SchemaPackageManager
import com.osfans.trime.data.packaging.SchemaPackStore
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.actions.shot.ShotActions
import com.osfans.trime.actions.shot.ShotBallOverlay
import com.osfans.trime.actions.shot.ShotBallPrefs
import com.osfans.trime.actions.shot.ShotService
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
    private var t9Tab: TextView? = null
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

        // T9/Base/Pro 三段胶囊：点哪段就把**当前方案**切过去，不写任何偏好参数
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
        t9Tab = capsuleTab("T9") { switchSchema(SCHEMA_T9) }
        baseTab = capsuleTab("Base") { switchSchema(SCHEMA_BASE) }
        proTab = capsuleTab("Pro") { switchSchema(SCHEMA_PRO) }
        capsule.addView(t9Tab, LinearLayout.LayoutParams(dp(34), dp(24)))
        capsule.addView(baseTab, LinearLayout.LayoutParams(dp(40), dp(24)))
        capsule.addView(proTab, LinearLayout.LayoutParams(dp(34), dp(24)))
        header.addView(
            capsule,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(4) },
        )

        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, dp(34)))

        // 主区：4 列 × 3 行，正常一屏放得下；以后加东西再往上滑，不用改布局
        bodyBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll =
            ScrollView(context).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(bodyBox, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(2) },
        )

        // 底部「收起」：不占整行，宽度取 1/3 行宽、高度 29dp，居中。
        root.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(
                    bottomBar("收起") { leaveToKeyboard() },
                    LinearLayout.LayoutParams(0, dp(29), 1f),
                )
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(29)).apply { topMargin = dp(6) },
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
        // 状态行读**真实状态**，不用偏好拼：当前键盘取自 KeyboardWindow，
        // 当前方案取自引擎，用户自己的方案（rime_ice / luna_pinyin 等）也照实显示。
        val currentKb = keyboard()?.currentId.orEmpty()
        val currentSchema = RimeDaemon.currentSchemaId
        statusView?.text = buildString {
            append(keyboardLabel(currentKb))
            append(" · ")
            append(currentSchema.ifEmpty { "未选方案" })
        }
        val baseInstalled = SchemaPackStore.isInstalled(SchemaPackStore.BASE_ID)
        val proInstalled = SchemaPackStore.isInstalled(SchemaPackStore.PRO_ID)
        t9Tab?.let { styleTab(it, currentSchema == SCHEMA_T9, baseInstalled) }
        baseTab?.let { styleTab(it, currentSchema == SCHEMA_BASE, baseInstalled) }
        proTab?.let { styleTab(it, currentSchema == SCHEMA_PRO, proInstalled) }

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
                Cell("aai", "功能模式", "", "cmd_robot", Kind.ACTION, "ai"),
                Cell("aclip", "剪贴板", "", "cmd_clipboard_text", Kind.ACTION, "clipboard"),
                Cell("ashot", "截图", "", "cmd_monitor_screenshot", Kind.ACTION, "shot"),
                Cell("ashotball", "截图浮球", if (ShotBallPrefs.enabled) "已开启" else "", "cmd_monitor_screenshot", Kind.ACTION, "shot_ball"),
            )

        // 每行最多 4 个：多了自动换行，绝不挤成 5 个一行
        for (chunk in (row1 + row2 + if (WanxiangPrefs.panelShowActions) row3 else emptyList()).chunked(COLUMNS)) {
            addRow(box, chunk, current)
        }
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
        // 末尾不足 4 个时补占位，保证每格都是 1/4 宽 —— 否则最后一行只有一个格子会被拉成整行宽
        repeat(COLUMNS - cells.size) {
            row.addView(
                View(context),
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
                    "shot_ball" -> {
                        val ctx = service ?: return
                        if (!ShotService.isGranted(ctx)) {
                            toast("截图浮球需要无障碍权限：设置 → 辅助功能 → 无障碍 → 已下载的服务 → BambooIME → 打开「截图」")
                            ShotActions.openAccessibilitySettings(ctx)
                            return
                        }
                        val want = !ShotBallPrefs.enabled
                        ShotBallPrefs.enabled = want
                        if (want) ShotBallOverlay.show() else ShotBallOverlay.hide()
                        toast(if (want) "截图浮球已开启" else "截图浮球已关闭")
                        rebuild()
                    }
                    "shot" -> {
                        // 截图是独立功能：先离开面板并收起键盘，由 ShotActions 负责抓屏与显示框选界面
                        leaveToKeyboard()
                        ShotActions.start(service)
                    }
                    "glossary" -> {
                        GlossaryPrefs.enabled = !GlossaryPrefs.enabled
                        toast(if (GlossaryPrefs.enabled) "已开启外语学习" else "已关闭外语学习")
                        rebuild()
                    }
                    else -> toast("还没接这个入口")
                }
            }
        }
    }

    /**
     * 切**当前方案**（不是切偏好）。
     *
     * 只做三件事：激活方案所在的包 → 把该包的 `schema_list` 写成只剩这个方案 → 部署。
     * 编码方式/键盘形态/辅助码一律不写：那些归「设置向导」，这里只切当前用哪个方案。
     */
    private fun switchSchema(schema: String) {
        if (RimeDaemon.currentSchemaId == schema) {
            toast("当前就是这个方案")
            return
        }
        val pkg =
            if (schema == SCHEMA_PRO) SchemaPackStore.PRO_ID else SchemaPackStore.BASE_ID
        if (!SchemaPackStore.isInstalled(pkg)) {
            toast("您没有部署当前方案，进「设置向导」中可部署。")
            return
        }
        toast("正在切到 $schema，部署中…")
        runCatching {
            SchemaPackageManager.setActivePackageId(pkg)
            if (!PackageSchemaList.write(pkg, listOf(schema))) {
                toast("写方案列表失败：检查 Documents 目录是否可写")
                return
            }
            RimeDaemon.restartRime(fullCheck = false)
        }.onFailure { Timber.w(it, "key panel: switch schema failed") }
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

    /** 键盘 id → 显示名；认不出就直接显示 id，绝不假装成我们的键盘。 */
    private fun keyboardLabel(id: String): String =
        when (id) {
            "" -> "未显示键盘"
            "default" -> "26 键"
            "wanxiang_t9" -> "九宫格"
            "wanxiang_18key" -> "18 键"
            "wanxiang_14key" -> "14 键"
            "qijian" -> "四排七键"
            "xiaohe" -> "小鹤"
            "wanxiang_english" -> "English"
            else -> id
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

    /** 底部动作条：酒红底、居中、图标在左文字在右。 */
    private fun bottomBar(
        label: String,
        onClick: () -> Unit,
    ): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background =
                GradientDrawable().apply {
                    setColor(0xFF8E3B30.toInt())
                    cornerRadius = dp(9).toFloat()
                }
            addView(
                ImageView(context).apply {
                    val d =
                        runCatching {
                            IconicsDrawable(context, "cmd_chevron_down").apply {
                                sizePx = dp(18)
                                colorFilter = PorterDuffColorFilter(0xFFF6F2EA.toInt(), PorterDuff.Mode.SRC_IN)
                            }
                        }.getOrNull()
                    if (d != null) setImageDrawable(d) else visibility = View.GONE
                },
                LinearLayout.LayoutParams(dp(18), dp(18)),
            )
            addView(
                TextView(context).apply {
                    text = label
                    setTextColor(0xFFF6F2EA.toInt())
                    textSize = 13f
                    includeFontPadding = false
                    setPadding(dp(6), 0, 0, 0)
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
            )
            setOnClickListener { onClick() }
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

        /** 每行最多几格（多了自动换行）。 */
        const val COLUMNS = 4

        /** 当前方案（三个官方方案 id）。 */
        const val SCHEMA_T9 = "wanxiang_t9"
        const val SCHEMA_BASE = "wanxiang"
        const val SCHEMA_PRO = "wanxiang_pro"

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
