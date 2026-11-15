/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.actions.bamboo.BambooPrefs
import com.osfans.trime.actions.glossary.GlossaryPrefs
import com.osfans.trime.actions.wanxiang.WanxiangPrefs
import com.osfans.trime.ui.common.BambooClick
import com.osfans.trime.ui.common.BambooSwitch
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout

/**
 * 「面板与快捷」：功能面板的行为，以及几个可以打在编码里的快捷码。
 *
 * 这些设置不属于「万象拼音」（方案包、编码方案那些），单独放一页，
 * 以后面板上加东西也往这里添。
 */
class PanelQuickFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).also {
            buildScreen(it)
        }
    }

    private fun buildScreen(screen: PreferenceScreen) {
        val context = requireContext()

        screen.addBambooCategory("面板", "长按「切换键盘」那个键弹什么") {}
        screen.addPreference(
            BambooSwitch(context).apply {
                key = "panel_long_press"
                title = "长按切换键打开面板"
                isChecked = WanxiangPrefs.panelOnLongPress
                summary =
                    if (WanxiangPrefs.panelOnLongPress) {
                        "开（默认）＝长按中英键弹「键盘与方案」；单击仍是切中英"
                    } else {
                        "关＝长按还弹主题自带的那排键盘"
                    }
                isIconSpaceReserved = false
                setOnPreferenceChangeListener { _, newValue ->
                    WanxiangPrefs.panelOnLongPress = newValue as Boolean
                    refreshScreen()
                    true
                }
            },
        )
        screen.addPreference(
            BambooSwitch(context).apply {
                key = "panel_actions"
                title = "面板显示功能入口"
                isChecked = WanxiangPrefs.panelShowActions
                summary = "第三排：手写、拍照、AI 模式、外语学习、剪贴板"
                isIconSpaceReserved = false
                setOnPreferenceChangeListener { _, newValue ->
                    WanxiangPrefs.panelShowActions = newValue as Boolean
                    true
                }
            },
        )

        screen.addBambooCategory("快捷码", "打在编码里、以分号结束即生效") {}
        screen.addPreference(
            BambooClick(context).apply {
                key = "glossary_code"
                title = "外语学习开关码"
                summary = "当前 ${GlossaryPrefs.code}：打一遍开、再打一遍关"
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    editCode()
                    true
                }
            },
        )
        screen.addPreference(
            BambooClick(context).apply {
                key = "codes_overview"
                title = "全部快捷码"
                summary = "看看现在都能打哪些码"
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showCodes()
                    true
                }
            },
        )
        screen.addPreference(
            Preference(context).apply {
                key = "codes_note"
                title = ""
                summary =
                    "面板里点一下「外语学习」也能开关，效果和打这份码一样。"
                isIconSpaceReserved = false
                isSelectable = false
            },
        )
    }

    private fun refreshScreen() {
        val screen = preferenceScreen ?: return
        screen.removeAll()
        buildScreen(screen)
    }

    private fun editCode() {
        val ctx = requireContext()
        val input =
            EditText(ctx).apply {
                setText(GlossaryPrefs.code)
                setSelection(text.length)
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "wy;"
            }
        val box =
            FrameLayout(ctx).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
        AlertDialog.Builder(ctx)
            .setTitle("外语学习开关码")
            .setMessage("打完这一串（含结尾分号）就会开/关外语学习；不要和 AI 模式码、文曲星码、退出码撞车。")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                GlossaryPrefs.code = input.text.toString()
                refreshScreen()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCodes() {
        val lines =
            buildList {
                add("外语学习：${GlossaryPrefs.code}")
                add("文曲星：${BambooPrefs.wenquxingCode}")
                add("退出（一键收工）：${BambooPrefs.aiExitCode}")
                add("发送剪贴板：${BambooPrefs.aiClipCode}")
                BambooPrefs.aiModes.forEach { add("AI·${it.name}：${it.code}") }
            }
        AlertDialog.Builder(requireContext())
            .setTitle("全部快捷码")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 带副标题的分类（与其它设置页同一个写法）。 */
    private fun PreferenceScreen.addBambooCategory(
        title: String,
        subtitle: String,
        block: PreferenceCategory.() -> Unit,
    ) {
        val category =
            BambooCategoryPreference(requireContext()).apply {
                layoutResource = R.layout.preference_bamboo_category
                isIconSpaceReserved = false
                isPersistent = false
                key = title
                setTitle(title)
                setSubtitle(subtitle)
            }
        addPreference(category)
        category.block()
    }
}
