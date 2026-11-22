/*
 * SPDX-FileCopyrightText: 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppearancePrefs
import com.osfans.trime.ui.common.BambooClick
import com.osfans.trime.ui.common.BambooSwitch
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.SettingsPaletteResolver
import com.osfans.trime.ui.common.rebuildPreferencesKeepingScroll

/**
 * 「外观」：设置界面是否跟随键盘主题配色。
 *
 * 只影响设置界面本身，键盘配色仍由主题决定。
 */
class BambooAppearanceFragment : PaddingPreferenceFragment() {
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
        screen.addCategory("设置页外观", "决定设置页用固定的浅/深配色；强调色可以单独跟随键盘主题")

        val followSwitch =
            BambooSwitch(context).apply {
                key = "appearance_follow_theme"
                title = "跟随键盘主题配色"
                isChecked = AppearancePrefs.followTheme
                summary =
                    if (AppearancePrefs.followTheme) {
                        "开＝强调色也一起跟随键盘主题（底色与文字始终固定）"
                    } else {
                        "关（默认）＝固定浅/深配色，只有强调色从键盘主题取"
                    }
                isIconSpaceReserved = false
                onRestore = {
                    AppearancePrefs.followTheme = false
                    refresh()
                }
                restorers += { AppearancePrefs.followTheme = false }
                setOnPreferenceChangeListener { _, newValue ->
                    AppearancePrefs.followTheme = newValue as Boolean
                    refresh()
                    true
                }
            }
        screen.addPreference(followSwitch)

        val modeRow =
            BambooClick(context).apply {
                key = "appearance_force_mode"
                title = "配色"
                summary = describeMode()
                isVisible = !AppearancePrefs.followTheme
                isIconSpaceReserved = false
                onRestore = {
                    AppearancePrefs.forceMode = AppearancePrefs.MODE_DARK
                    refresh()
                }
                restorers += { AppearancePrefs.forceMode = AppearancePrefs.MODE_DARK }
                setOnPreferenceClickListener {
                    chooseMode()
                    true
                }
            }
        screen.addPreference(modeRow)

        val inspect =
            BambooClick(context).apply {
                key = "appearance_inspect"
                title = "检查当前取色"
                summary = "看每个颜色取自主题哪个键、对比度多少，以及哪里做了兜底"
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showInspectDialog()
                    true
                }
            }
        screen.addPreference(inspect)
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
            PreferenceCategory(requireContext()).apply {
                key = "category_$title"
                this.title = title
                summary = subtitle
                layoutResource = R.layout.preference_bamboo_category
            },
        )
    }

    private fun describeMode(): String =
        when (AppearancePrefs.forceMode) {
            AppearancePrefs.MODE_LIGHT -> "浅色"
            AppearancePrefs.MODE_DARK -> "深色（默认）"
            else -> "跟随系统"
        }

    private fun chooseMode() {
        val modes =
            arrayOf(
                AppearancePrefs.MODE_THEME to "跟随系统",
                AppearancePrefs.MODE_LIGHT to "浅色",
                AppearancePrefs.MODE_DARK to "深色",
            )
        val labels = modes.map { it.second }.toTypedArray()
        val checked = modes.indexOfFirst { it.first == AppearancePrefs.forceMode }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("配色")
            .setSingleChoiceItems(labels, checked) { dialog: DialogInterface, which: Int ->
                AppearancePrefs.forceMode = modes[which].first
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showInspectDialog() {
        val context = requireContext()
        val palette = SettingsPaletteResolver.resolve(context)
        val density = resources.displayMetrics.density

        fun dp(value: Int) = (value * density).toInt()

        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), 0)
            }
        container.addView(
            TextView(context).apply {
                text =
                    if (palette.followTheme) {
                        "跟随键盘主题：开"
                    } else {
                        "跟随键盘主题：关（强制${if (palette.isDark) "深" else "浅"}色）"
                    }
                setTextColor(palette.textPrimary)
                textSize = 14f
                setPadding(0, 0, 0, dp(8))
            },
        )
        for (role in palette.roles) {
            val row =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
            row.addView(
                View(context).apply {
                    setBackgroundColor(role.color)
                    layoutParams =
                        LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                            marginEnd = dp(10)
                        }
                },
            )
            row.addView(
                TextView(context).apply {
                    text =
                        buildString {
                            append("${role.name}  #%06X".format(role.color and 0xFFFFFF))
                            append("　对比度 %.2f".format(role.contrast))
                            append("\n来源：${role.source}")
                            if (role.note.isNotEmpty()) append("（${role.note}）")
                        }
                    setTextColor(palette.textSecondary)
                    textSize = 12f
                },
            )
            container.addView(row)
        }

        val scroll = ScrollView(context).apply { addView(container) }
        AlertDialog.Builder(context)
            .setTitle("当前取色")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun refresh() {
        val host = activity
        if (host != null) {
            // 工具栏与窗口底色挂在 Activity 上，重建一次最省事
            host.recreate()
            return
        }
        rebuildPreferencesKeepingScroll {
            preferenceScreen.removeAll()
            buildScreen(preferenceScreen)
        }
    }
}
