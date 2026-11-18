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
import com.osfans.trime.data.prefs.MemoryPrefs
import com.osfans.trime.util.memory.IdleReclaimer
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

        screen.addBambooCategory("键盘", "换输入框时用不用恢复你上次的键盘") {
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
            BambooClick(context).apply {
                key = "reset_first_hint"
                title = "重新显示首次提示"
                summary = "把「还没配好」那张提示卡的状态清掉，切回键盘会再弹一次"
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    WanxiangPrefs.resetHint()
                    toast("已重置，切回键盘会再弹一次")
                    refreshScreen()
                    true
                }
            },
        )
    
        buildMemorySection(screen)
}

    /** 内存占用：空闲后释放语音/手写/识图三个模型。 */
    private fun buildMemorySection(screen: PreferenceScreen) {
        val context = requireContext()
        screen.addBambooCategory(
            "内存占用",
            "只影响语音、手写、拍照识字这三个模型；方案词库和主题不受影响",
        ) {}

        screen.addPreference(
            BambooSwitch(context).apply {
                key = "memory_idle_release"
                title = "空闲后释放模型"
                isChecked = MemoryPrefs.idleRelease
                summary =
                    if (MemoryPrefs.idleRelease) {
                        "开（默认）＝一段时间不用就把模型从内存里放掉，下次用再加载"
                    } else {
                        "关＝一直常驻内存（随点随用，但占用更多）"
                    }
                isIconSpaceReserved = false
                setOnPreferenceChangeListener { _, newValue ->
                    MemoryPrefs.idleRelease = newValue as Boolean
                    refreshScreen()
                    true
                }
            },
        )

        val keepAlive =
            ValueSliderPreference(context).apply {
                key = "memory_keep_alive"
                title = "保留时长"
                description = "空闲多久才释放模型"
                unit = " 分钟"
                min = MemoryPrefs.KEEP_ALIVE_MIN
                max = MemoryPrefs.KEEP_ALIVE_MAX
                seekBarIncrement = 1
                defaultValue = MemoryPrefs.KEEP_ALIVE_DEFAULT
                isPersistent = false
                isEnabled = MemoryPrefs.idleRelease
                value = MemoryPrefs.keepAliveMinutes
                refreshSummary()
                setOnPreferenceChangeListener { _, newValue ->
                    MemoryPrefs.keepAliveMinutes = newValue as Int
                    refreshSummary()
                    true
                }
            }
        keepAlive.onRestore = {
            MemoryPrefs.keepAliveMinutes = MemoryPrefs.KEEP_ALIVE_DEFAULT
            keepAlive.value = MemoryPrefs.KEEP_ALIVE_DEFAULT
            keepAlive.refreshSummary()
        }
        screen.addPreference(keepAlive)

        screen.addPreference(
            BambooSwitch(context).apply {
                key = "memory_voice_pin"
                title = "语音模型常驻"
                isChecked = MemoryPrefs.voicePinned
                summary =
                    if (MemoryPrefs.voicePinned) {
                        "开＝语音不参与空闲释放（下次提住就能说，代价是常驻内存）"
                    } else {
                        "关（默认）＝语音也按上面的保留时长释放"
                    }
                isIconSpaceReserved = false
                setOnPreferenceChangeListener { _, newValue ->
                    MemoryPrefs.voicePinned = newValue as Boolean
                    refreshScreen()
                    true
                }
            },
        )

        screen.addPreference(
            BambooClick(context).apply {
                key = "memory_release_now"
                title = "立即释放一次"
                summary = heldSummary()
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    val done = IdleReclaimer.releaseAll(ignorePinned = true)
                    toast(if (done.isEmpty()) "当前没有需要释放的模型" else "已释放：" + done.joinToString("、"))
                    refreshScreen()
                    true
                }
            },
        )

        screen.addPreference(
            BambooClick(context).apply {
                key = "memory_status"
                title = "模型占用（估算）"
                summary = "语音 ≈ 180 MB · 识图 ≈ 95 MB · 手写 ≈ 40 MB（仅加载后占用）"
                isIconSpaceReserved = false
                isSelectable = false
            },
        )
    }

    /** 当前被持有的模型，给「立即释放」那行做说明。 */
    private fun heldSummary(): String {
        val held = IdleReclaimer.heldLabels()
        return if (held.isEmpty()) "当前没有加载任何模型" else "当前持有：" + held.joinToString("、")
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun refreshScreen() {
        val screen = preferenceScreen ?: return
        screen.removeAll()
        buildScreen(screen)
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
