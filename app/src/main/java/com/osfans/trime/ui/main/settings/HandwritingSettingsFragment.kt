/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.osfans.trime.ui.common.BambooSwitch
import com.osfans.trime.ui.common.rebuildPreferencesKeepingScroll
import com.osfans.trime.R
import com.osfans.trime.actions.handwriting.HandwritingPrefs
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.addPreference
import com.osfans.trime.ui.common.BambooClick

/**
 * 「手写」设置页。
 *
 * 配置存在独立的 SharedPreferences（[HandwritingPrefs]）里，改完立即生效，
 * 下次打开手写板时按新值重建界面，不需要部署方案、不需要重启输入法。
 *
 * 每项后面跟一条「↺ 恢复默认」，页首有「恢复本页默认」。
 */
class HandwritingSettingsFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        rebuildScreen()
    }

    /** 重建整页（保持滚动位置，避免改一项就跳回顶部）。 */
    private fun rebuildScreen() {
        rebuildPreferencesKeepingScroll { buildScreen() }
    }

    private fun buildScreen() {
        val resetters = mutableListOf<() -> Unit>()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(requireContext()).apply {
                addRestoreAll(resetters)

                addCategory("识别", "抬笔之后多久出候选、一次给几个") {
                    addSeek(
                        "停顿时长",
                        "抬笔后等待多久再识别；太短会把连笔切断，太长显得卡",
                        100,
                        3000,
                        50,
                        default = 500,
                        current = HandwritingPrefs.pauseMs,
                        unit = " 毫秒",
                        resetters = resetters,
                    ) { value ->
                        // 拖动过程中不重建页面（会跳回顶部），说明行由滑块自己刷新
                        HandwritingPrefs.pauseMs = value
                    }
                    addSeek(
                        "候选个数",
                        "候选条上最多显示多少个字",
                        4,
                        12,
                        1,
                        default = 12,
                        current = HandwritingPrefs.maxCandidates,
                        unit = " 个",
                        resetters = resetters,
                    ) { value ->
                        HandwritingPrefs.maxCandidates = value
                    }
                    addSwitch(
                        "显示拼音",
                        "在候选右侧显示读音，字号比字小一号",
                        default = true,
                        current = HandwritingPrefs.showPinyin,
                        resetters = resetters,
                    ) {
                        HandwritingPrefs.showPinyin = it
                    }
                }

                addCategory("书写", "笔画粗细") {
                    addSeek(
                        "笔宽",
                        "笔画粗细",
                        1,
                        16,
                        1,
                        default = 3,
                        current = HandwritingPrefs.penWidth,
                        unit = " dp",
                        resetters = resetters,
                    ) { value ->
                        HandwritingPrefs.penWidth = value
                    }
                }

                addCategory("配色", "手写区跟不跟主题走") {
                    addSwitch(
                        "跟随主题配色",
                        "关掉后用内置的中性配色，不受主题影响",
                        default = true,
                        current = HandwritingPrefs.themeColors,
                        resetters = resetters,
                    ) {
                        HandwritingPrefs.themeColors = it
                    }
                    val inkRow =
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
                    val restoreInk = {
                        HandwritingPrefs.inkMode = HandwritingPrefs.INK_THEME
                        rebuildScreen()
                    }
                    (inkRow as? BambooClick)?.onRestore = restoreInk
                    resetters.add(restoreInk)
                }

                addCategory("模型", "当前使用的手写识别模型") {
                    addRow("手写模型", modelSummary()) {}
                }
            }
    }

    private fun modelSummary(): String =
        when (HandwritingPrefs.activeVersion) {
            "v1.0" -> "ochwpro v1.0 · 内置"
            else -> "ochwpro v1.1 · 内置"
        }

    private fun chooseInkMode() {
        val labels =
            arrayOf(
                "跟随主题（推荐）",
                "固定深色",
                "固定浅色",
            )
        val values =
            arrayOf(
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
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 页首的「恢复本页默认」。 */
    private fun PreferenceScreen.addRestoreAll(resetters: MutableList<() -> Unit>) {
        addPreference(
            Preference(requireContext()).apply {
                key = "restore_all"
                title = "恢复本页默认"
                summary = "把停顿时长、候选个数、拼音、笔宽、配色全部恢复成出厂值"
                isIconSpaceReserved = true
                isPersistent = false
                setOnPreferenceClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("恢复本页默认")
                        .setMessage("本页所有设置都会恢复成默认值，确定吗？")
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton("恢复") { _, _ ->
                            resetters.forEach { runCatching { it() } }
                            rebuildScreen()
                        }.show()
                    true
                }
            },
        )
    }

    private fun PreferenceScreen.addCategory(
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

    /** 纯文字条目：显式保留图标位，否则文字会比其他行更靠左。 */
    private fun PreferenceScreen.addRow(
        title: String,
        subtitle: String,
        onClick: (() -> Unit)? = null,
    ): Preference {
        val pref =
            Preference(requireContext()).apply {
                key = "row_$title"
                this.title = title
                summary = subtitle
                isIconSpaceReserved = true
                isPersistent = false
            }
        if (onClick != null) pref.setOnPreferenceClickListener { onClick(); true }
        addPreference(pref)
        return pref
    }

    private fun PreferenceScreen.addSwitch(
        title: String,
        subtitle: String,
        default: Boolean,
        current: Boolean,
        resetters: MutableList<() -> Unit>,
        onChange: (Boolean) -> Unit,
    ) {
        val pref =
            BambooSwitch(requireContext()).apply {
                key = "sw_$title"
                this.title = title
                summary = subtitle
                isChecked = current
                isPersistent = false
                setOnPreferenceChangeListener { _, value ->
                    onChange(value as Boolean)
                    true
                }
            }
        // 坑：`pref.isChecked = x` 是程序赋值，不会触发 OnPreferenceChangeListener，
        // 必须显式 onChange 写 prefs 再同步视图，否则界面与生效值脱钩。
        val restore = {
            onChange(default)
            pref.isChecked = default
        }
        pref.onRestore = restore
        resetters.add(restore)
        addPreference(pref)
    }

    private fun PreferenceScreen.addSeek(
        title: String,
        subtitle: String,
        min: Int,
        max: Int,
        step: Int,
        default: Int,
        current: Int,
        unit: String,
        resetters: MutableList<() -> Unit>,
        onChange: (Int) -> Unit,
    ) {
        val pref =
            ValueSliderPreference(requireContext()).apply {
                key = "seek_$title"
                this.title = title
                description = subtitle
                this.unit = unit
                this.min = min
                this.max = max
                seekBarIncrement = step
                defaultValue = default
                isPersistent = false
                refreshSummary()
                setOnPreferenceChangeListener { _, newValue ->
                    onChange(newValue as Int)
                    true
                }
            }
        pref.value = current.coerceIn(min, max)
        val restore = {
            if (pref.value == default) onChange(default) else pref.restoreDefault()
        }
        pref.onRestore = restore
        resetters.add(restore)
        addPreference(pref)
    }
}
