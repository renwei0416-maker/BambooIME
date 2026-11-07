/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.R
import com.osfans.trime.actions.handwriting.HandwritingPrefs
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.addPreference

/**
 * 「手写」设置页。
 *
 * 配置存在独立的 SharedPreferences（[HandwritingPrefs]）里，改完立即生效，
 * 下次打开手写板时按新值重建界面，不需要部署方案、不需要重启输入法。
 */
class HandwritingSettingsFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        rebuildScreen()
    }

    private fun rebuildScreen() {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory("识别", "抬笔之后多久出候选、一次给几个") {
                addSeek(
                    "停顿时长",
                    "抬笔后等待 ${HandwritingPrefs.pauseMs} 毫秒再识别；太短会把连笔切断，太长显得卡",
                    100,
                    3000,
                    50,
                    HandwritingPrefs.pauseMs,
                ) { value ->
                    HandwritingPrefs.pauseMs = value
                    rebuildScreen()
                }
                addSeek(
                    "候选个数",
                    "候选条上最多显示多少个字（当前 ${HandwritingPrefs.maxCandidates}）",
                    4,
                    12,
                    1,
                    HandwritingPrefs.maxCandidates,
                ) { value ->
                    HandwritingPrefs.maxCandidates = value
                    rebuildScreen()
                }
                addSwitch("显示拼音", "在候选右侧显示读音，字号比字小一号", HandwritingPrefs.showPinyin) {
                    HandwritingPrefs.showPinyin = it
                }

            }

            addCategory("书写", "笔画粗细") {
                addSeek(
                    "笔宽",
                    "笔画粗细 ${HandwritingPrefs.penWidth} dp",
                    1,
                    16,
                    1,
                    HandwritingPrefs.penWidth,
                ) { value ->
                    HandwritingPrefs.penWidth = value
                    rebuildScreen()
                }
            }

            addCategory("配色", "手写区跟不跟主题走") {
                addSwitch(
                    "跟随主题配色",
                    "关掉后用内置的中性配色，不受主题影响",
                    HandwritingPrefs.themeColors,
                ) {
                    HandwritingPrefs.themeColors = it
                    rebuildScreen()
                }
                addRow(
                    "手写区配色",
                    when (HandwritingPrefs.inkMode) {
                        HandwritingPrefs.INK_DARK -> "固定深色（深底浅笔）"
                        HandwritingPrefs.INK_LIGHT -> "固定浅色（浅底深笔）"
                        else -> "跟随主题（对比度不足时自动翻转纸色）"
                    },
                ) {
                    chooseInkMode()
                }
            }

            addCategory("模型", "当前使用的手写识别模型") {
                addRow("手写模型", modelSummary()) {
                    // 模型管理（内置 / 下载 / 导入 / 删除）在下一步加入
                }
            }
        }
    }

    private fun modelSummary(): String =
        when (HandwritingPrefs.activeVersion) {
            "v1.0" -> "ochwpro v1.0 · 内置"
            else -> "ochwpro v1.1 · 内置"
        }

    private fun chooseInkMode() {
        val labels = arrayOf(
            "跟随主题（推荐）",
            "固定深色",
            "固定浅色",
        )
        val values = arrayOf(
            HandwritingPrefs.INK_THEME,
            HandwritingPrefs.INK_DARK,
            HandwritingPrefs.INK_LIGHT,
        )
        val checked = values.indexOf(HandwritingPrefs.inkMode).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("手写区配色")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                HandwritingPrefs.inkMode = values[which]
                dialog.dismiss()
                rebuildScreen()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun PreferenceScreen.addCategory(
        title: String,
        subtitle: String,
        block: PreferenceCategory.() -> Unit,
    ) {
        val category = BambooCategoryPreference(requireContext()).apply {
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

    /** 纯文字条目：显式保留图标位，否则文字会比其他行更靠左。 */
    private fun PreferenceScreen.addRow(
        title: String,
        subtitle: String,
        onClick: (() -> Unit)? = null,
    ) {
        val pref = Preference(requireContext()).apply {
            key = "row_$title"
            this.title = title
            summary = subtitle
            isIconSpaceReserved = true
            isPersistent = false
        }
        if (onClick != null) pref.setOnPreferenceClickListener { onClick(); true }
        addPreference(pref)
    }

    private fun PreferenceScreen.addSwitch(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val pref = SwitchPreferenceCompat(requireContext()).apply {
            key = "sw_$title"
            this.title = title
            summary = subtitle
            isChecked = checked
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                onChange(value as Boolean)
                true
            }
        }
        addPreference(pref)
    }

    private fun PreferenceScreen.addSeek(
        title: String,
        subtitle: String,
        min: Int,
        max: Int,
        step: Int,
        current: Int,
        onChange: (Int) -> Unit,
    ) {
        addPreference(
            Preference(requireContext()).apply {
                key = "seek_$title"
                this.title = title
                summary = subtitle
                setOnPreferenceClickListener {
                    val values = (min..max step step).toList()
                    val labels = values.map { it.toString() }.toTypedArray()
                    AlertDialog.Builder(requireContext())
                        .setTitle(title)
                        .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                            onChange(values[which])
                            dialog.dismiss()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
            },
        )
    }
}
