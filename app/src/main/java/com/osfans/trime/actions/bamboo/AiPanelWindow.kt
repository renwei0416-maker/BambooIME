// SPDX-FileCopyrightText: 2025 BambooIME
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.actions.bamboo

import android.graphics.PorterDuff
import com.osfans.trime.util.BambooLog
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizePx
import com.osfans.trime.actions.glossary.GlossaryPrefs
import com.osfans.trime.actions.wenquxing.WenquxingPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.actions.ocr.ThemeContrast
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import timber.log.Timber

/**
 * AI 模式面板（主题 token `BambooAiPanel`）。
 *
 * 目的：把所有 AI 模式和文曲星模式摆成按钮，点一下就进，不用记触发码。
 *
 * 设计要点：
 *  * 进入/退出**复用** [BambooActions] 里触发码那条通路（`enterAiMode` / `enterTipsMode` /
 *    `exitArmedMode`），保证「面板点进去的」和「打码进去的」是完全同一个状态；
 *  * 点当前已激活的那一项 = 退出（与「同码退出」一致）；
 *  * 选完就收起回键盘——进模式是为了接着打字，不是停在面板上；
 *  * 按钮 = 图标 + 名字 + 触发码小字；用户自建模式统一用一个默认图标；
 *  * 文曲星没启用时那个按钮不显示，不留点了没反应的死按钮。
 */
class AiPanelWindow : BoardWindow.NoBarBoardWindow() {
    private val service: TrimeInputMethodService
        get() = TrimeInputMethodService.current
            ?: error("ai panel: IME service is not available")

    private val windowManager: BoardWindowManager
        get() = BoardWindowManager.current
            ?: error("ai panel: board window manager is not available")

    private var density = 3f
    private var rootView: View? = null
    private var gridBox: LinearLayout? = null
    private var statusView: TextView? = null
    private var exitButton: ExitButton? = null
    private var glossaryRow: View? = null

    private var colorBack = 0xFF202420.toInt()
    private var colorKeyBg = 0xFF2B302B.toInt()
    private var colorKeyText = 0xFFF2F0E8.toInt()
    private var colorSub = 0xFF8A8A80.toInt()
    private var colorAccent = 0xFF6FAE8F.toInt()

    override fun onCreateView(): View {
        BambooLog.ai.d("aipanel", "onCreateView")
        val created =
            runCatching { buildView() }
                .onFailure {
                    Timber.e(it, "ai panel: buildView failed")
                    BambooLog.ai.w("aipanel", "buildView threw", it)
                }.getOrElse { plainView() }
        rootView = created
        return created
    }

    override fun onAttached() {
        BambooLog.ai.d("aipanel", "onAttached")
        active = true
        // 每次挂上都按当前状态重画：从别的窗口回来时模式可能已经变了
        rebuild()
    }

    override fun onDetached() {
        BambooLog.ai.d("aipanel", "onDetached")
        active = false
    }

    override fun toString(): String = "AiPanelWindow"

    // ---------------------------------------------------------------- 视图

    private fun buildView(): View {
        density = context.resources.displayMetrics.density
        val gap = dp(4)

        fun color(key: String, fallback: Int): Int =
            runCatching { ColorManager.getColorOr(key, fallback) }
                .onFailure { Timber.w(it, "ai panel: color '$key' unavailable") }
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

        // 顶部：标题 + 当前状态 + 退出当前模式 + 收起
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(2), dp(2), dp(2))
            }
        header.addView(
            TextView(context).apply {
                text = "功能模式"
                setTextColor(colorKeyText)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        statusView =
            TextView(context).apply {
                textSize = 12f
                includeFontPadding = false
                setTextColor(colorSub)
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                setPadding(dp(8), 0, dp(6), 0)
            }
        header.addView(statusView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // 「退出当前模式」：始终显示。没进模式时是灰的，但仍然可点（点了给提示），
        // 免得按钮时有时无、位置跳来跳去。
        exitButton =
            ExitButton().apply {
                setOnClickListener {
                    if (BambooActions.armedDisplayName == null) {
                        BambooLog.ai.d("aipanel", "exit tapped but no mode")
                        toast("当前未进入任何模式")
                        return@setOnClickListener
                    }
                    val was = BambooActions.exitArmedMode()
                    BambooLog.ai.d("aipanel", "exit mode was=$was")
                    leaveToKeyboard()
                }
            }
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 模式网格（可上下滑：模式多了也不裁）
        gridBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll =
            ScrollView(context).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(gridBox, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(2) },
        )

        // 退出当前模式：单独一整行（模式格子会越来越多，混在顶栏里挤不下）。
        root.addView(
            exitButton,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(38)).apply { topMargin = dp(4) },
        )

        // 「外语学习」是常驻开关（不是模式），单独一整行，点了就切换。
        glossaryRow = glossaryToggleRow()
        root.addView(
            glossaryRow,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(38)).apply { topMargin = dp(4) },
        )

        // 底部「收起」：和「键盘与方案」一致，截图那套太小的右上角按钮不用了。
        root.addView(
            bottomBar("收起") {
                BambooLog.ai.d("aipanel", "collapse -> keyboard")
                leaveToKeyboard()
            },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = dp(4) },
        )

        return root
    }

    /** 极简兜底：主题取色/图标都失败时也要能退出，不能把用户困在空面板里。 */
    private fun plainView(): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(0xFF202420.toInt()) }
            addView(
                TextView(context).apply {
                    text = "AI 模式面板没能画出来"
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

    private fun rebuild() {
        val box = gridBox ?: return
        val armedName = BambooActions.armedDisplayName
        val armedCode = BambooActions.armedCode
        // 顶部状态不写触发码（用户要求）：只写名字
        statusView?.text =
            if (armedName != null) "当前：$armedName" else "当前：未进入模式"
        exitButton?.applyState(armedName != null)
        refreshGlossaryRow()

        box.removeAllViews()

        val items = mutableListOf<Item>()
        for (m in BambooPrefs.aiModes) {
            items += Item(code = m.code, name = m.name, icon = iconOf(m.name), isTips = false)
        }
        if (WenquxingPrefs.enabled) {
            items +=
                Item(
                    code = BambooPrefs.wenquxingCode,
                    name = "文曲星",
                    icon = "cmd_book_open_page_variant",
                    isTips = true,
                )
        }

        var row: LinearLayout? = null
        var inRow = 0
        for (item in items) {
            if (inRow == 0) {
                row =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            val active = armedName != null && armedCode == item.code
            row!!.addView(
                modeCell(item, active),
                LinearLayout.LayoutParams(0, dp(78), 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                },
            )
            inRow++
            if (inRow == COLUMNS) inRow = 0
        }
        // 最后一行没排满时补空位，保证格子等宽（否则最后一行会被拉宽）
        if (inRow != 0 && row != null) {
            repeat(COLUMNS - inRow) {
                row.addView(
                    View(context),
                    LinearLayout.LayoutParams(0, dp(78), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    },
                )
            }
        }
    }

    private data class Item(
        val code: String,
        val name: String,
        val icon: String,
        val isTips: Boolean,
    )

    private fun modeCell(
        item: Item,
        active: Boolean,
    ): View {
        val cell =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(2), dp(4), dp(2), dp(4))
                background =
                    GradientDrawable().apply {
                        setColor(if (active) colorAccent else colorKeyBg)
                        cornerRadius = dp(9).toFloat()
                    }
            }

        val iconView =
            ImageView(context).apply {
                val drawable =
                    runCatching {
                        IconicsDrawable(context, item.icon).apply {
                            sizePx = dp(22)
                            colorFilter =
                                PorterDuffColorFilter(
                                    if (active) colorBack else colorKeyText,
                                    PorterDuff.Mode.SRC_IN,
                                )
                        }
                    }.getOrNull()
                if (drawable != null) {
                    setImageDrawable(drawable)
                } else {
                    // 字体里没有这个字形：不画图标，让下面的文字顶上（不留空白方块）
                    visibility = View.GONE
                }
            }
        cell.addView(iconView, LinearLayout.LayoutParams(dp(22), dp(22)))

        cell.addView(
            TextView(context).apply {
                text = item.name
                textSize = 12f
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (active) colorBack else colorKeyText)
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    8,
                    12,
                    1,
                    TypedValue.COMPLEX_UNIT_SP,
                )
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(1) },
        )

        cell.addView(
            TextView(context).apply {
                text = item.code
                textSize = 9f
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
                setTextColor(if (active) colorBack else colorSub)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        cell.setOnClickListener {
            BambooLog.ai.d(
                "aipanel",
                "tap code=${item.code} tips=${item.isTips} active=$active",
            )
            onModeTapped(item, active)
        }
        return cell
    }

    private fun onModeTapped(
        item: Item,
        active: Boolean,
    ) {
        // 点当前已激活的那一项 = 退出（与「同码退出」一致）
        if (active) {
            BambooActions.exitArmedMode()
        } else if (item.isTips) {
            if (!BambooActions.enterTipsMode()) return
        } else {
            if (!BambooActions.enterAiMode(item.code)) return
        }
        // 选完就收起回键盘：进模式是为了接着打字
        BambooLog.ai.d("aipanel", "now=${BambooActions.armedDisplayName ?: "off"}")
        leaveToKeyboard()
    }

    /**
     * 「退出当前模式」按钮：图标在左、文字在右，整体是一个按钮。
     *
     * 颜色**不跟主题**（固定暗红），因为浅色主题下跟主题色的对比度会不够、看起来像禁用；
     * 没进模式时变灰，但仍可点（点了给提示），保证按钮位置不跳动。
     */
    private inner class ExitButton : LinearLayout(context) {
        private val iconView = ImageView(context)
        private val labelView =
            TextView(context).apply {
                text = "退出当前模式"
                textSize = 12f
                includeFontPadding = false
                gravity = Gravity.CENTER
                maxLines = 1
            }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(10), 0, dp(12), 0)
            iconView.setPadding(0, 0, dp(4), 0)
            addView(iconView, LayoutParams(dp(16), dp(16), 0f))
            addView(labelView, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        fun applyState(inMode: Boolean) {
            background =
                GradientDrawable().apply {
                    setColor(if (inMode) EXIT_BG else EXIT_BG_IDLE)
                    cornerRadius = dp(8).toFloat()
                }
            val fg = if (inMode) EXIT_FG else colorSub
            labelView.setTextColor(fg)
            val drawable =
                runCatching {
                    IconicsDrawable(context, "cmd_power").apply {
                        sizePx = dp(16)
                        colorFilter = PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
                    }
                }.getOrNull()
            if (drawable != null) {
                iconView.setImageDrawable(drawable)
                iconView.visibility = View.VISIBLE
            } else {
                // 字体里没有这个字形：只留文字，不留空白方块
                iconView.visibility = View.GONE
            }
        }
    }

    private fun toast(msg: String) {
        runCatching {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }.onFailure { Timber.w(it, "ai panel: toast failed") }
    }

    /** 底部动作条：酒红底、居中、图标在左文字在右（两个面板统一）。 */
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
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(colorKeyText)
            setPadding(dp(10), 0, dp(10), 0)
            background =
                GradientDrawable().apply {
                    setColor(colorKeyBg)
                    cornerRadius = dp(8).toFloat()
                }
            isClickable = true
            setOnClickListener { onTap() }
        }

    /** 「外语学习」开关行：整行一个按钮，右侧写开/关。 */
    private fun glossaryToggleRow(): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background =
                GradientDrawable().apply {
                    setColor(colorKeyBg)
                    cornerRadius = dp(9).toFloat()
                }
            addView(
                ImageView(context).apply {
                    val d =
                        runCatching {
                            IconicsDrawable(context, "cmd_translate").apply {
                                sizePx = dp(18)
                                colorFilter = PorterDuffColorFilter(colorKeyText, PorterDuff.Mode.SRC_IN)
                            }
                        }.getOrNull()
                    if (d != null) setImageDrawable(d) else visibility = View.GONE
                },
                LinearLayout.LayoutParams(dp(18), dp(18)),
            )
            addView(
                TextView(context).apply {
                    text = "外语学习"
                    setTextColor(colorKeyText)
                    textSize = 13f
                    includeFontPadding = false
                    setPadding(dp(8), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
            addView(
                TextView(context).apply {
                    tag = "state"
                    setTextColor(colorSub)
                    textSize = 12f
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
            )
            setOnClickListener {
                GlossaryPrefs.enabled = !GlossaryPrefs.enabled
                toast(if (GlossaryPrefs.enabled) "已开启外语学习" else "已关闭外语学习")
                BambooLog.ai.d("aipanel", "glossary -> " + GlossaryPrefs.enabled)
                refreshGlossaryRow()
            }
        }

    private fun refreshGlossaryRow() {
        val row = glossaryRow ?: return
        (row as? android.view.ViewGroup)?.let { g ->
            (g.getChildAt(g.childCount - 1) as? TextView)?.text =
                if (GlossaryPrefs.enabled) "开" else "关"
        }
    }

    private fun leaveToKeyboard() {
        active = false
        runCatching { windowManager.attachWindow(KeyboardWindow) }
            .onFailure { Timber.w(it, "ai panel: back to keyboard failed") }
    }

    /**
     * 每个模式配一个图标。用户自建的（名字认不出来）统一用同一个默认图标，
     * 免得出现「一半有图标一半没有」的参差。
     *
     * 名字是图标字体里的**下划线**写法（`cmd_auto_fix`）。写成连字符不会报错，
     * 只是画不出来、自动退回纯文字——排查时很容易看漏。
     */
    private fun iconOf(name: String): String =
        when (name) {
            "默认" -> "cmd_robot"
            "润色" -> "cmd_auto_fix"
            "校对" -> "cmd_spellcheck"
            "英文翻译" -> "cmd_alphabetical"
            "日文翻译" -> "cmd_translate"
            "中文翻译" -> "cmd_ideogram_cjk"
            "扩写" -> "cmd_arrow_expand"
            "回复" -> "cmd_reply"
            "聊天" -> "cmd_chat_outline"
            else -> "cmd_star_four_points"
        }

    private fun dp(v: Int): Int = (v * density).toInt()

    companion object {
        /** 主题 preset 里写的文本（`text = "BambooAiPanel"`）。 */
        const val TOKEN = "BambooAiPanel"

        private const val COLUMNS = 4

        /** 「退出当前模式」固定暗红（不跟主题走，避免浅色主题下看起来像禁用）。 */
        private const val EXIT_BG = 0xFF8E3B30.toInt()

        /** 没进模式时的灰。 */
        private const val EXIT_BG_IDLE = 0xFF43433E.toInt()

        /** 暗红上的字色：米白，对比度足够。 */
        private const val EXIT_FG = 0xFFF6F2EA.toInt()

        fun isToken(text: String): Boolean = text.equals(TOKEN, ignoreCase = true)

        val instance: AiPanelWindow by lazy { AiPanelWindow() }

        /** 面板是否「开着」——切 App / 窗口重建后靠它把面板接回来。 */
        @Volatile
        var active: Boolean = false
            private set

        /** 换到别的窗口时清掉（否则重进输入法会被拽回空面板）。 */
        fun markInactive() {
            active = false
        }
    }
}
