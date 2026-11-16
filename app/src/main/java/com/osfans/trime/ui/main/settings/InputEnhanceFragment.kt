/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.actions.bamboo.AutoPairPrefs
import com.osfans.trime.ui.common.BambooSwitch
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.rebuildPreferencesKeepingScroll
import com.osfans.trime.util.addPreference

/**
 * 「输入增强」设置页。
 *
 * 目前只有「成对符号自动补全」一组；后续的候选英文注释也放这一页。
 * 配置存在独立的 SharedPreferences（[AutoPairPrefs]）里，改完立即生效，不用部署方案、不用重启输入法。
 */
class InputEnhanceFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        rebuildScreen()
    }

    private fun rebuildScreen() {
        rebuildPreferencesKeepingScroll { buildScreen() }
    }

    private fun buildScreen() {
        val resetters = mutableListOf<() -> Unit>()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(requireContext()).apply {
                addRestoreAll(resetters)

                addCategory("成对符号", "打左半边自动补右半边，光标停在中间") {
                    addSwitch(
                        "自动补全",
                        "开＝打「（」自动补上「）」，打「“」自动补上「”」；关＝完全交给你自己打",
                        default = false,
                        current = AutoPairPrefs.enabled,
                        resetters = resetters,
                    ) {
                        AutoPairPrefs.enabled = it
                    }
                    addSwitch(
                        "关掉重复的右半边",
                        "开（默认）＝打完内容再打「）」，若光标后面已经有一个「）」就只把光标挪过去，不会变成「（））」",
                        default = true,
                        current = AutoPairPrefs.skipRight,
                        resetters = resetters,
                    ) {
                        AutoPairPrefs.skipRight = it
                    }
                    addSwitch(
                        "引号也配对",
                        "开（默认）＝「」『』“”‘’和英文引号都配对；关＝只配对括号、书名号、中括号",
                        default = true,
                        current = AutoPairPrefs.quotes,
                        resetters = resetters,
                    ) {
                        AutoPairPrefs.quotes = it
                    }
                    addSwitch(
                        "引号左右轮替",
                        "开（默认）＝引号键像普通键盘那样自己左右交替，轮到左引号时照样补全；" +
                            "关＝永远只打左引号，靠补全补右半边",
                        default = true,
                        current = AutoPairPrefs.quoteAlternate,
                        resetters = resetters,
                    ) {
                        AutoPairPrefs.quoteAlternate = it
                    }
                }

                addCategory("说明", "什么情况下不会自动补") {
                    addRow(
                        "组句时不补",
                        "还在打拼音、光标前是本轮编码时不介入，标点交给 Rime 自己提交",
                    )
                    addRow(
                        "密码框不补",
                        "密码输入框里一律原样上屏",
                    )
                    addRow(
                        "整句上屏不补",
                        "一次提交多个字符时（点候选、剪贴板、AI 结果、拍照结果）不做补全",
                    )
                }
            }
    }

    private fun PreferenceScreen.addRestoreAll(resetters: MutableList<() -> Unit>) {
        addPreference(
            Preference(requireContext()).apply {
                key = "restore_all"
                title = "恢复本页默认"
                summary = "把成对符号的三项设置全部恢复成出厂值"
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
    ) {
        addPreference(
            Preference(requireContext()).apply {
                key = "row_$title"
                this.title = title
                summary = subtitle
                isIconSpaceReserved = true
                isPersistent = false
            },
        )
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
        // `pref.isChecked = x` 是程序赋值，不会触发 OnPreferenceChangeListener（它只响应用户点击），
        // 所以必须先 onChange 写 prefs，再同步视图，否则界面与生效值脱钩。
        val restore = {
            onChange(default)
            pref.isChecked = default
        }
        pref.onRestore = restore
        resetters.add(restore)
        addPreference(pref)
    }
}
