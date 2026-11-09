/*
 * SPDX-FileCopyrightText: 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.osfans.trime.actions.bamboo.BambooTokens
import com.osfans.trime.actions.handwriting.HandwritingWindow
import com.osfans.trime.actions.ocr.OcrWindow
import com.osfans.trime.ui.common.SettingsPaletteResolver

/**
 * 「按键动作调用」：整页纯文本，把全部动作名、用法和注意事项讲清楚。
 *
 * 内容与代码同源（[BambooTokens.fixed]、[HandwritingWindow.TOKEN]/[HandwritingWindow.TOKEN_NUMBER]/
 * [HandwritingWindow.TOKEN_SYMBOL]、[OcrWindow.TOKEN]），加新 token 时这里会立刻反映出来，
 * 只有「用法说明」是手写的。
 */
class BambooTokensFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // 这一页不是 Preference 列表，拿不到 PaddingPreferenceFragment 的染色，自己取一次
        val palette = SettingsPaletteResolver.resolve(context)
        val text =
            TextView(context).apply {
                text = buildText()
                textSize = 13f
                setTextIsSelectable(true) // 长按能复制
                setTextColor(palette.textPrimary)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(20), dp(12), dp(20), dp(28))
            }

        // 底部留出导航栏的高度：这个页面是纯文字，多留一点还能把最后一行滚到手指够得着的位置。
        val navBar = resources.getDimensionPixelSize(
            resources.getIdentifier("navigation_bar_height", "dimen", "android"),
        )
        return ScrollView(context).apply {
            setBackgroundColor(palette.background)
            addView(text, ViewGroup.LayoutParams(-1, -2))
            setPadding(0, 0, 0, navBar)
        }
    }

    private fun buildText(): String {
        val rows =
            BambooTokens.fixed.mapIndexed { index, name ->
                "%2d. %-16s %s".format(index + 1, name, usage(name))
            }
        val total = BambooTokens.fixed.size
        return buildString {
            appendLine("按键动作调用")
            appendLine()
            appendLine(
                "把这些名字写进主题键位的动作字段（click / long_click / swipe_left … / popup 列表都行），" +
                    "按下键就直接执行。不用再敲 qq/、tc; 这类字母码。",
            )
            appendLine()
            appendLine("名字是大小写不敏感的，但建议照抄。")
            appendLine()
            appendLine("──────────────────────────────")
            appendLine("一、回退、删除与 AI（${BambooTokens.PREFIX}* 系列，共 $total 个）")
            appendLine("──────────────────────────────")
            appendLine()
            rows.forEach {
                appendLine(it)
                appendLine()
            }
            appendLine("──────────────────────────────")
            appendLine("二、面板与功能入口（$PANEL_COUNT 个）")
            appendLine("──────────────────────────────")
            appendLine()
            appendLine("%2d. %-16s %s".format(total + 1, HandwritingWindow.TOKEN_NUMBER, PANEL_NUMBER_HELP))
            appendLine()
            appendLine("%2d. %-16s %s".format(total + 2, HandwritingWindow.TOKEN_SYMBOL, PANEL_SYMBOL_HELP))
            appendLine()
            appendLine("%2d. %-16s %s".format(total + 3, OcrWindow.TOKEN, OCR_HELP))
            appendLine()
            appendLine("──────────────────────────────")
            appendLine("三、手写板（$KEYBOARD_COUNT 个，它是键盘 ID，不是动作名）")
            appendLine("──────────────────────────────")
            appendLine()
            appendLine(
                "%2d. %-16s %s".format(
                    total + 4,
                    HandwritingWindow.TOKEN,
                    "切到手写板。写进 popup 列表里，就在键位弹窗中多加一行「手写」",
                ),
            )
            appendLine()
            appendLine("──────────────────────────────")
            appendLine("四、怎么写进主题")
            appendLine("──────────────────────────────")
            appendLine()
            appendLine("把一个动作换成 token，只改右边那个值：")
            appendLine()
            appendLine("  key(merge(S.backspace, key { swipe_right = \"BambooRevert\" }))")
            appendLine("  key(merge(S.symbol,   key { click = \"BambooSymbol\" }))")
            appendLine()
            appendLine("想在键位上多点几项，就放进 popup：")
            appendLine()
            appendLine("  popup = { \"BambooSymbol\", \"BambooOcr\", \"BambooSettings\" }")
            appendLine()
            appendLine("改完在输入法里重载主题（长按底栏最左键 → 重载主题）才生效。")
            appendLine()
            appendLine("──────────────────────────────")
            appendLine("五、注意事项")
            appendLine("──────────────────────────────")
            appendLine()
            appendLine("1. 键位字段用 text = \"Bamboo…\" 这种写法。send = 只发按键事件，token 不会生效。")
            appendLine()
            appendLine(
                "2. 要画图标：Material 图标写 'ic@名字'（注意带单引号，例如 " +
                    "'ic@symbol'）；主题自带图标字体用 \"\\u{F83A}\" 这种码位写法。",
            )
            appendLine()
            appendLine(
                "3. 「数字」「符号」两个面板从键盘键位唤起时，「返回」和「收起」都回到字母键盘；" +
                    "从手写画布的工具按钮进去时，返回到手写画布。这是故意的。",
            )
            appendLine()
            appendLine("4. 面板一打开就会清掉当前 Rime 编码（等同按 Esc），已经上屏的文字不受影响。")
            appendLine()
            appendLine("5. token 只在本输入法内部拦截，不会被目标 App 收到；但它依赖虚拟键盘上真有这个键。")
            appendLine()
            appendLine("6. 回退类动作依赖「账本」：只对经本输入法上屏的内容有效，别处粘贴进来的文字删不掉。")
        }.trimEnd()
    }

    private fun usage(name: String): String =
        when (name) {
            BambooTokens.REVERT -> "回退最近一笔上屏（等价敲 qq/）"
            BambooTokens.CLEAR -> "清空编辑区（光标前后全删）"
            BambooTokens.DELETE_LINE -> "删到行首；已在行首时并入上一行"
            BambooTokens.DELETE_WORD -> "删掉光标前的一个词"
            BambooTokens.AI_SEND -> "用当前 AI 模式发送（等价双击发送键）"
            BambooTokens.AI_CLIP -> "用当前 AI 模式发送剪贴板（等价 jt;）"
            BambooTokens.AI_OFF -> "退出 AI 模式（等价 tc;）"
            BambooTokens.SETTINGS -> "打开「回退与AI」设置页"
            else -> ""
        }

    private companion object {
        const val PANEL_COUNT = 3
        const val KEYBOARD_COUNT = 1
        const val PANEL_NUMBER_HELP = "打开「数字和符号」面板的第一页（数字）"
        const val PANEL_SYMBOL_HELP = "打开同一面板的符号页；面板页签可左右滑，共 22 类"
        const val OCR_HELP = "打开拍照识字（相机取景、相册识别）"
    }
}
