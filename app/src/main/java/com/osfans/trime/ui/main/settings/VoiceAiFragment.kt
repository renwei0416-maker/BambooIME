/*
 * SPDX-FileCopyrightText: 2026 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.osfans.trime.actions.bamboo.VoiceAiPrefs
import com.osfans.trime.ui.common.BambooClick
import com.osfans.trime.ui.common.BambooSwitch
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.rebuildPreferencesKeepingScroll
import com.osfans.trime.util.addPreference

/**
 * 「语音发AI」设置页。
 *
 * 说的是这件事：按住空格说话 → 识别完 → 如果已经进了某个 AI 模式，就把这段文字
 * 连同当前模式一起发给 AI，结果**追加**在原文后面；没进模式就和以前一样只上屏。
 *
 * 配置存在独立的 SharedPreferences（[VoiceAiPrefs]），改完立即生效。
 */
class VoiceAiFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        rebuildPreferencesKeepingScroll { buildScreen() }
    }

    private fun buildScreen() {
        val resetters = mutableListOf<() -> Unit>()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(requireContext()).apply {
                addRestoreAll(
                    resetters,
                    "把发送时机、提示词、失败处理、超时与字数限制全部恢复成出厂值",
                )

                addCategory("发送", "什么时候把语音识别到的文字交给 AI") {
                    addChoiceSetting(
                        title = "发送时机",
                        summary = "语音识别结束后要不要发；「仅已进 AI 模式」＝没进模式时和以前完全一样",
                        labels = SEND_LABELS,
                        default = VoiceAiPrefs.SEND_WHEN_ARMED,
                        current = VoiceAiPrefs.sendMode,
                        resetters = resetters,
                    ) { VoiceAiPrefs.sendMode = it }

                    addSwitchSetting(
                        title = "同时上屏识别到的文字",
                        summary = "开（默认）＝原文先上屏，AI 结果追加在后面，你能对照看原文；关＝只留 AI 结果",
                        default = true,
                        current = VoiceAiPrefs.commitSource,
                        resetters = resetters,
                    ) { VoiceAiPrefs.commitSource = it }
                }

                addCategory("提示词", "在模式提示词前面再叠一段「语音纠错」") {
                    addSwitchSetting(
                        title = "叠加语音纠错提示",
                        summary = "开（默认）＝先让模型按上下文还原你说的话，再按当前模式处理；关＝只用模式自己的提示词",
                        default = true,
                        current = VoiceAiPrefs.stackFixPrompt,
                        resetters = resetters,
                    ) { VoiceAiPrefs.stackFixPrompt = it }

                    addPromptSetting(resetters)
                }

                addCategory("失败与限制", "AI 没返回时怎么办、什么样的不发") {
                    addSwitchSetting(
                        title = "AI 失败时上屏原文",
                        summary = "开（默认）＝请求失败也把识别到的文字留在编辑框里，不至于白说一遍",
                        default = true,
                        current = VoiceAiPrefs.commitOnFail,
                        resetters = resetters,
                    ) { VoiceAiPrefs.commitOnFail = it }

                    addSliderSetting(
                        title = "超时",
                        summary = "等多久没返回就按失败处理",
                        min = 5,
                        max = 60,
                        step = 1,
                        default = 20,
                        current = VoiceAiPrefs.timeoutSec,
                        unit = " 秒",
                        resetters = resetters,
                    ) { VoiceAiPrefs.timeoutSec = it }

                    addSliderSetting(
                        title = "太短不发",
                        summary = "识别结果少于这个字数就不发（避免「嗯」「啊」这种也去请求）",
                        min = 0,
                        max = 20,
                        step = 1,
                        default = 2,
                        current = VoiceAiPrefs.minChars,
                        unit = " 字",
                        resetters = resetters,
                    ) { VoiceAiPrefs.minChars = it }

                    addSliderSetting(
                        title = "太长截断",
                        summary = "超过这个字数只发前面一段，防止把一整段话塞进去",
                        min = 50,
                        max = 2000,
                        step = 50,
                        default = 500,
                        current = VoiceAiPrefs.maxChars,
                        unit = " 字",
                        resetters = resetters,
                    ) { VoiceAiPrefs.maxChars = it }
                }

                addCategory("提示与说明", "这条功能在哪些情况下不起作用") {
                    addSwitchSetting(
                        title = "显示处理状态",
                        summary = "开（默认）＝发出和失败都弹一句提示，能看出 AI 那一步有没有真的发生",
                        default = true,
                        current = VoiceAiPrefs.showStatus,
                        resetters = resetters,
                    ) { VoiceAiPrefs.showStatus = it }

                    addRow("按住空格说话", "语音还是原来那条链路（内置识别或外部识别服务），这里只接管「识别完之后」")
                    addRow("进了文曲星时只上屏", "文曲星不发 AI 请求，语音进来只把文字放上去，不打扰它")
                    addRow("没进任何模式", "按上面「发送时机」：仅已进模式时什么都不做，和以前一样")
                    addRow("语音后端选了别的输入法", "那种情况文字由对方的输入法上屏，我们既不发送也不提示")
                }
            }
    }

    // ---------------------------------------------------------------- 行构造

    private fun PreferenceScreen.addRestoreAll(
        resetters: MutableList<() -> Unit>,
        detail: String,
    ) {
        addPreference(
            Preference(requireContext()).apply {
                title = "恢复本页默认"
                summary = detail
                isIconSpaceReserved = false
                isPersistent = false
                setOnPreferenceClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("恢复本页默认")
                        .setMessage("本页所有设置都会恢复成默认值，确定吗？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("恢复") { _, _ ->
                            resetters.forEach { runCatching { it() } }
                            rebuildPreferencesKeepingScroll { buildScreen() }
                        }.show()
                    true
                }
            },
        )
    }

    private fun PreferenceScreen.addCategory(
        title: String,
        subtitle: String,
        block: PreferenceGroup.() -> Unit,
    ) {
        val header =
            BambooCategoryPreference(requireContext()).apply {
                layoutResource = com.osfans.trime.R.layout.preference_bamboo_category
                isIconSpaceReserved = false
                isPersistent = false
                setTitle(title)
                setSubtitle(subtitle)
            }
        addPreference(header)
        header.block()
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

    private fun PreferenceGroup.addSwitchSetting(
        title: String,
        summary: String,
        default: Boolean,
        current: Boolean,
        resetters: MutableList<() -> Unit>,
        onChange: (Boolean) -> Unit,
    ) {
        val pref =
            BambooSwitch(requireContext()).apply {
                this.title = title
                this.summary = summary
                isChecked = current
                isIconSpaceReserved = false
                isPersistent = false
                setOnPreferenceChangeListener { _, newValue ->
                    onChange(newValue as Boolean)
                    true
                }
            }
        val restore = {
            onChange(default)
            pref.isChecked = default
        }
        pref.onRestore = restore
        resetters.add(restore)
        addPreference(pref)
    }

    private fun PreferenceGroup.addChoiceSetting(
        title: String,
        summary: String,
        labels: Array<String>,
        default: Int,
        current: Int,
        resetters: MutableList<() -> Unit>,
        onPick: (Int) -> Unit,
    ) {
        val summaryOf = { index: Int -> summary + "（当前：" + labels[index.coerceIn(0, labels.lastIndex)] + "）" }
        val pref =
            BambooClick(requireContext()).apply {
                this.title = title
                this.summary = summaryOf(current)
                isIconSpaceReserved = false
                isPersistent = false
                setOnPreferenceClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle(title)
                        .setSingleChoiceItems(labels, current.coerceIn(0, labels.lastIndex)) { dialog, which ->
                            onPick(which)
                            this.summary = summaryOf(which)
                            dialog.dismiss()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            }
        val restore = {
            onPick(default)
            pref.summary = summaryOf(default)
        }
        pref.onRestore = restore
        resetters.add(restore)
        addPreference(pref)
    }

    private fun PreferenceGroup.addSliderSetting(
        title: String,
        summary: String,
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
                this.title = title
                this.description = summary
                this.unit = unit
                this.defaultValue = default.coerceIn(min, max)
                this.min = min
                this.max = max
                seekBarIncrement = step
                showSeekBarValue = false
                isIconSpaceReserved = false
                isPersistent = false
                this.value = current.coerceIn(min, max)
                refreshSummary()
                setOnPreferenceChangeListener { _, newValue ->
                    val now = (newValue as? Int) ?: return@setOnPreferenceChangeListener false
                    onChange(now)
                    true
                }
            }
        val restore = {
            pref.restoreDefault()
            onChange(pref.value)
        }
        pref.onRestore = restore
        resetters.add(restore)
        addPreference(pref)
    }

    /** 多行编辑语音纠错提示词。 */
    private fun PreferenceGroup.addPromptSetting(resetters: MutableList<() -> Unit>) {
        val summaryOf = { text: String -> "当前 " + text.length + " 字，点这里编辑" }
        // 对话框回调里要改 summary，而 pref 还在自己的初始化过程中，
        // 所以先留个引用，创建完再补上（回调是后来的点击才执行，不受影响）。
        var prefRef: BambooClick? = null
        val pref =
            BambooClick(requireContext()).apply {
                title = "语音纠错提示词"
                summary = summaryOf(VoiceAiPrefs.fixPrompt)
                isIconSpaceReserved = false
                isPersistent = false
                setOnPreferenceClickListener {
                    val input =
                        EditText(requireContext()).apply {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            minLines = 6
                            gravity = android.view.Gravity.TOP or android.view.Gravity.START
                            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
                            setText(VoiceAiPrefs.fixPrompt)
                            setSelection(text.length)
                        }
                    val wrap =
                        LinearLayout(requireContext()).apply {
                            val pad = (12 * resources.displayMetrics.density).toInt()
                            setPadding(pad, pad / 2, pad, 0)
                            addView(input)
                        }
                    AlertDialog.Builder(requireContext())
                        .setTitle("语音纠错提示词")
                        .setMessage("这段会拼在当前 AI 模式的提示词前面。识别到的文字本身作为「用户消息」单独发送，不会和提示词混在一起。")
                        .setView(wrap)
                        .setNeutralButton("恢复默认") { _, _ ->
                            VoiceAiPrefs.fixPrompt = VoiceAiPrefs.DEFAULT_FIX_PROMPT
                            prefRef?.summary = summaryOf(VoiceAiPrefs.fixPrompt)
                        }
                        .setNegativeButton("取消", null)
                        .setPositiveButton("保存") { _, _ ->
                            val text = input.text.toString().trim()
                            if (text.isEmpty()) {
                                Toast.makeText(requireContext(), "不能为空", Toast.LENGTH_SHORT).show()
                            } else {
                                VoiceAiPrefs.fixPrompt = text
                                prefRef?.summary = summaryOf(text)
                            }
                        }
                        .show()
                    true
                }
            }
        prefRef = pref
        val restore = {
            VoiceAiPrefs.fixPrompt = VoiceAiPrefs.DEFAULT_FIX_PROMPT
            pref.summary = summaryOf(VoiceAiPrefs.DEFAULT_FIX_PROMPT)
        }
        pref.onRestore = restore
        resetters.add(restore)
        addPreference(pref)
    }

    private companion object {
        val SEND_LABELS = arrayOf("仅已进 AI 模式", "总是（未进模式用默认模式）", "关闭")
    }
}
