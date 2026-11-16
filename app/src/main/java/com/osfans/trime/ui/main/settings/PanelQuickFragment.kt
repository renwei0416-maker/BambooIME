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
import android.widget.Toast
import android.widget.FrameLayout

/**
 * 「其它扩展功能设置」：面板行为、键盘形态与几个零散开关。
 *
 * 这些不属于「万象拼音」（方案包、编码方案那些），也不属于某个具体模块，
 * 单独放一页；以后面板上加东西也往这里添。
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

        screen.addBambooCategory("键盘形态", "打完字换输入框时用哪个键盘") {
            addPreference(
                BambooClick(context).apply {
                    key = "keyboard_layout"
                    title = "键盘形态"
                    summary = layoutLabel()
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        pickLayout()
                        true
                    }
                },
            )
            addPreference(
                BambooSwitch(context).apply {
                    key = "restore_keyboard"
                    title = "进输入框时恢复键盘"
                    isChecked = WanxiangPrefs.restoreKeyboard
                    summary =
                        if (WanxiangPrefs.restoreKeyboard) {
                            "开：换输入框后还停在上面选的形态（以及你上次手动切的键盘）"
                        } else {
                            "关：不干预，手动切了就一直手动"
                        }
                    isIconSpaceReserved = false
                    setOnPreferenceChangeListener { _, newValue ->
                        WanxiangPrefs.restoreKeyboard = newValue as Boolean
                        refreshScreen()
                        true
                    }
                },
            )
        }

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

    private fun layoutLabel(): String =
        when (WanxiangPrefs.layout) {
            WanxiangPrefs.LAYOUT_18 -> "18 键"
            WanxiangPrefs.LAYOUT_14 -> "14 键"
            WanxiangPrefs.LAYOUT_9 -> "九宫格（9 键）"
            else -> "全键盘（26 键）"
        }

    private fun pickLayout() {
        val layouts =
            intArrayOf(
                WanxiangPrefs.LAYOUT_26,
                WanxiangPrefs.LAYOUT_18,
                WanxiangPrefs.LAYOUT_14,
                WanxiangPrefs.LAYOUT_9,
            )
        val labels = arrayOf("全键盘（26 键）", "18 键", "14 键", "九宫格（9 键，固定全拼）")
        val checked = layouts.indexOfFirst { it == WanxiangPrefs.layout }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("键盘形态")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                WanxiangPrefs.layout = layouts[which]
                // 明确改了形态就以它为准，丢掉「上次手动切的键盘」
                WanxiangPrefs.lastKeyboard = ""
                WanxiangPrefs.sanitize()
                if (layouts[which] == WanxiangPrefs.LAYOUT_9) {
                    Toast.makeText(requireContext(), "九键固定全拼：已把编码改成全拼、关掉辅助码和模糊音", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
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
