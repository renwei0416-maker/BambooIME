/*
 * SPDX-FileCopyrightText: 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.HardwareKeyboardPrefs
import com.osfans.trime.ui.common.BambooClick
import com.osfans.trime.ui.common.BambooSwitch
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.rebuildPreferencesKeepingScroll

/**
 * 「外接键盘」：浮动候选条与状态条。只在接了硬件键盘时生效。
 */
class HardwareKeyboardFragment : PaddingPreferenceFragment() {
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
        val restorers = mutableListOf<() -> Unit>()

        screen.addRestoreAll(restorers)

        screen.addNote(
            title = "使用前提",
            summary = "要先在「候选窗口 → 显示候选词窗口」里选「根据输入设备而定」（或「输入设备」）。选「总是显示」时接了硬件键盘也会弹虚拟键盘，状态条和浮动候选条都不会出现。候选条的位置由「候选窗口 → 位置」决定，接键盘时建议选「跟随光标」。",
        )

        screen.addCategory("候选条", "接硬件键盘时浮在屏幕上，位置由「候选窗口 → 位置」决定")
        screen.addSeek(
            title = "候选个数",
            subtitle = "候选条上最多显示几个；方案一次最多给 6 个，超出部分翻页可见",
            min = 1,
            max = 6,
            step = 1,
            default = 4,
            current = HardwareKeyboardPrefs.candidateCount,
            unit = " 个",
            restorers = restorers,
        ) { HardwareKeyboardPrefs.candidateCount = it }

        screen.addSeek(
            title = "候选字号",
            subtitle = "候选字的字号，100% 即与主题一致；调小后候选框会跟着变窄",
            min = 40,
            max = 200,
            step = 5,
            default = 100,
            current = HardwareKeyboardPrefs.candidateTextScale,
            unit = "%",
            restorers = restorers,
        ) { HardwareKeyboardPrefs.candidateTextScale = it }

        screen.addSeek(
            title = "编码字号",
            subtitle = "候选条上方编码（打字母那串）的字号，100% 即与主题一致",
            min = 40,
            max = 200,
            step = 5,
            default = 100,
            current = HardwareKeyboardPrefs.preeditTextScale,
            unit = "%",
            restorers = restorers,
        ) { HardwareKeyboardPrefs.preeditTextScale = it }

        screen.addCategory("状态条", "显示输入状态，点一下就能切换")
        screen.addSwitch(
            title = "显示状态条",
            subtitle = "开（默认）＝接硬件键盘时浮出一条状态栏；关＝完全不显示",
            default = true,
            current = HardwareKeyboardPrefs.statusBarEnabled,
            restorers = restorers,
        ) { HardwareKeyboardPrefs.statusBarEnabled = it }

        screen.addSwitch(
            title = "中/英",
            subtitle = "显示当前中英状态，点这一项可切换",
            default = true,
            current = HardwareKeyboardPrefs.showAscii,
            restorers = restorers,
        ) { HardwareKeyboardPrefs.showAscii = it }

        screen.addSwitch(
            title = "CAPS",
            subtitle = "大写锁定时高亮。这个只能显示，改不了键盘状态",
            default = true,
            current = HardwareKeyboardPrefs.showCaps,
            restorers = restorers,
        ) { HardwareKeyboardPrefs.showCaps = it }

        screen.addSwitch(
            title = "全/半角",
            subtitle = "显示当前全角还是半角，点这一项可切换",
            default = true,
            current = HardwareKeyboardPrefs.showFullShape,
            restorers = restorers,
        ) { HardwareKeyboardPrefs.showFullShape = it }

        screen.addSwitch(
            title = "标点",
            subtitle = "显示当前是中文标点还是英文标点，点这一项可切换",
            default = true,
            current = HardwareKeyboardPrefs.showPunct,
            restorers = restorers,
        ) { HardwareKeyboardPrefs.showPunct = it }

        screen.addSwitch(
            title = "设置",
            subtitle = "状态条上放一个齿轮按钮，点了打开设置",
            default = true,
            current = HardwareKeyboardPrefs.showSettings,
            restorers = restorers,
        ) { HardwareKeyboardPrefs.showSettings = it }

        screen.addSeek(
            title = "状态条大小",
            subtitle = "整条的大小，100% 为默认大小；130% 以上更容易点到",
            min = 70,
            max = 150,
            step = 5,
            default = 100,
            current = HardwareKeyboardPrefs.statusBarScale,
            unit = "%",
            restorers = restorers,
        ) { HardwareKeyboardPrefs.statusBarScale = it }

        screen.addSwitch(
            title = "默认收起",
            subtitle = "开＝状态条只显示中/英和一格三角，点三角再展开；也可以直接在状态条上切换，状态会记住",
            default = false,
            current = HardwareKeyboardPrefs.statusBarCollapsed,
            restorers = restorers,
        ) { HardwareKeyboardPrefs.statusBarCollapsed = it }

        screen.addRow(
            title = "重置状态条位置",
            subtitle = "把状态条挪回右上角；平时直接拖动它就行",
            restorers = restorers,
            restore = {},
        ) {
            HardwareKeyboardPrefs.statusBarX = Float.NaN
            HardwareKeyboardPrefs.statusBarY = Float.NaN
            refresh()
        }
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
        restorers: MutableList<() -> Unit>,
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
        restorers.add(restore)
        addPreference(pref)
    }

    private fun PreferenceScreen.addRestoreAll(restorers: List<() -> Unit>) {
        addPreference(
            Preference(requireContext()).apply {
                key = "restore_all"
                title = "恢复本页默认"
                summary = "把本页 ${restorers.size} 项设置恢复成出厂值"
                setOnPreferenceClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("恢复本页默认？")
                        .setMessage("本页设置会恢复成出厂值，其他设置不受影响。")
                        .setPositiveButton("恢复") { _: DialogInterface, _: Int ->
                            restorers.forEach { it() }
                            refresh()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            },
        )
    }

    private fun PreferenceScreen.addCategory(
        title: String,
        subtitle: String,
    ) {
        addPreference(
            BambooCategoryPreference(requireContext()).apply {
                key = "category_$title"
                this.title = title
                layoutResource = R.layout.preference_bamboo_category
                setSubtitle(subtitle)
            },
        )
    }

    private fun PreferenceScreen.addSwitch(
        title: String,
        subtitle: String,
        default: Boolean,
        current: Boolean,
        restorers: MutableList<() -> Unit>,
        onChange: (Boolean) -> Unit,
    ) {
        val apply: (Boolean) -> Unit = { value ->
            onChange(value)
            refresh()
        }
        addPreference(
            BambooSwitch(requireContext()).apply {
                key = "hw_${title.hashCode()}"
                this.title = title
                this.summary = subtitle
                isChecked = current
                isIconSpaceReserved = false
                onRestore = { apply(default) }
                restorers += { onChange(default) }
                setOnPreferenceChangeListener { _, newValue ->
                    apply(newValue as Boolean)
                    true
                }
            },
        )
    }

    private fun PreferenceScreen.addRow(
        title: String,
        subtitle: String,
        restorers: MutableList<() -> Unit>,
        restore: () -> Unit,
        onClick: () -> Unit,
    ) {
        addPreference(
            BambooClick(requireContext()).apply {
                key = "hw_${title.hashCode()}"
                this.title = title
                this.summary = subtitle
                isIconSpaceReserved = false
                onRestore = {
                    restore()
                    refresh()
                }
                restorers += restore
                setOnPreferenceClickListener {
                    onClick()
                    true
                }
            },
        )
    }

    /** 只作说明，不可点，也不进恢复列表。 */
    private fun PreferenceScreen.addNote(
        title: String,
        summary: String,
    ) {
        addPreference(
            Preference(requireContext()).apply {
                key = "hw_note_${title.hashCode()}"
                this.title = title
                this.summary = summary
                isPersistent = false
                isIconSpaceReserved = false
            },
        )
    }

    private fun refresh() {
        rebuildPreferencesKeepingScroll {
            preferenceScreen.removeAll()
            buildScreen(preferenceScreen)
        }
    }
}
