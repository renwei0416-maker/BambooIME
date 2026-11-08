/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.osfans.trime.ui.common.BambooSwitch
import com.osfans.trime.ui.common.rebuildPreferencesKeepingScroll
import com.osfans.trime.R
import com.osfans.trime.actions.ocr.OcrPrefs
import com.osfans.trime.actions.ocr.OcrRecognizer
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.addPreference

/**
 * 「拍照识字」设置页：取景、实时预览、识别阈值。
 *
 * 这些值存在 [OcrPrefs]（独立的 SharedPreferences `bamboo_ocr`）里，改完立即生效，
 * 三个阈值会当场下发给 C++ 引擎（`nativeSetThresholds`），不用重启输入法。
 *
 * 每一项的标题行最右侧有一个「↺」恢复默认按钮，页首还有「恢复本页默认」，都不动识别模型本身。
 */
class OcrSettingsFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        rebuildPreferencesKeepingScroll { buildScreen() }
    }

    /**
     * 重建整页。
     *
     * 「恢复默认」只改 SharedPreferences，页面上的开关/滑块/说明都是建页时按当时的值渲染的，
     * 所以恢复完必须重建一次，否则界面停在旧值上（看起来像没生效）。
     */
    private fun buildScreen() {
        val resetters = mutableListOf<() -> Unit>()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(requireContext()).apply {
                addBambooCategory("拍照识字", "相机取景、实时预览文字、识别结果的处理方式") {
                    addRestoreAll(resetters)

                    addSwitchSetting(
                        title = "边写边认（实时预览）",
                        summary = "开＝取景框里一稳定就把文字显示在提示行；关＝只在按「拍照」时识别",
                        default = true,
                        current = OcrPrefs.liveEnabled,
                        resetters = resetters,
                    ) { OcrPrefs.liveEnabled = it }

                    addSliderSetting(
                        title = "实时识别间隔",
                        summary = "两次识别之间至少隔多久；调小更跟手但更耗电",
                        min = 250,
                        max = 2000,
                        step = 50,
                        default = 550,
                        current = OcrPrefs.liveIntervalMs,
                        unit = " 毫秒",
                        resetters = resetters,
                    ) { OcrPrefs.liveIntervalMs = it }

                    addSliderSetting(
                        title = "卡片不透明度",
                        summary = "确认卡片浮在键盘上方会挡住 App 的输入框；调低能透出底下的内容",
                        min = 30,
                        max = 100,
                        step = 5,
                        default = 75,
                        current = OcrPrefs.cardOpacity,
                        resetters = resetters,
                    ) { OcrPrefs.cardOpacity = it }

                    addSliderSetting(
                        title = "提示背景不透明度",
                        summary = "识别结果压在画面上时底下这层黑的浓度；调到 0 就是纯文字，靠描边阴影保证可读",
                        min = 0,
                        max = 100,
                        step = 5,
                        default = 60,
                        current = OcrPrefs.hintAlpha,
                        resetters = resetters,
                    ) { OcrPrefs.hintAlpha = it }

                    addSliderSetting(
                        title = "识别框宽度",
                        summary = "整段模式取景框的宽度（占预览宽度的比例）；窄一点能少收进旁边的字",
                        min = 20,
                        max = 100,
                        step = 5,
                        default = 80,
                        current = OcrPrefs.frameWidth,
                        resetters = resetters,
                    ) { OcrPrefs.frameWidth = it }

                    addSliderSetting(
                        title = "识别框高度",
                        summary = "整段模式取景框的高度（占预览高度的比例）；太矮容易切掉字的上下一半",
                        min = 5,
                        max = 60,
                        step = 1,
                        default = 30,
                        current = OcrPrefs.frameHeight,
                        resetters = resetters,
                    ) { OcrPrefs.frameHeight = it }

                    addSliderSetting(
                        title = "识别框上下位置",
                        summary = "整段模式下取景框相对屏幕中间的上下偏移：向左＝上移、向右＝下移，最高不越过右上角的倍数胶囊",
                        min = -25,
                        max = 25,
                        step = 5,
                        default = -10,
                        current = OcrPrefs.frameOffset,
                        unit = "",
                        resetters = resetters,
                    ) { OcrPrefs.frameOffset = it }

                    addSliderSetting(
                        title = "单字框大小",
                        summary = "单字模式的方框边长（占预览高度的百分比）；越小越严，越大越好对准",
                        min = 20,
                        max = 50,
                        step = 1,
                        default = 32,
                        current = (OcrPrefs.singleFrameRatio * 100).toInt(),
                        resetters = resetters,
                    ) { OcrPrefs.singleFrameRatio = it / 100f }

                    addSliderSetting(
                        title = "识别置信度",
                        summary = "整行文字的平均置信度低于这个值就丢弃；调高更严（少乱码、可能漏字）",
                        min = 30,
                        max = 90,
                        step = 5,
                        default = 50,
                        current = (OcrPrefs.confidence * 100).toInt(),
                        resetters = resetters,
                    ) {
                        OcrPrefs.confidence = it / 100f
                        OcrRecognizer.applyThresholds()
                    }

                    addSliderSetting(
                        title = "检测门槛",
                        summary = "文字区域概率低于这个值就不算文字；画面干净时调低、杂乱时调高",
                        min = 10,
                        max = 70,
                        step = 5,
                        default = 30,
                        current = (OcrPrefs.detThreshold * 100).toInt(),
                        resetters = resetters,
                    ) {
                        OcrPrefs.detThreshold = it / 100f
                        OcrRecognizer.applyThresholds()
                    }

                    addSliderSetting(
                        title = "区域质量门槛",
                        summary = "候选文字框的平均概率低于这个值就丢弃；配合上一项一起调",
                        min = 10,
                        max = 90,
                        step = 5,
                        default = 50,
                        current = (OcrPrefs.boxScore * 100).toInt(),
                        resetters = resetters,
                    ) {
                        OcrPrefs.boxScore = it / 100f
                        OcrRecognizer.applyThresholds()
                    }
                }
            }
    }

    private fun PreferenceScreen.addBambooCategory(
        title: String,
        subtitle: String,
        block: PreferenceGroup.() -> Unit,
    ) {
        val header =
            BambooCategoryPreference(context).apply {
                layoutResource = R.layout.preference_bamboo_category
                isIconSpaceReserved = false
                isPersistent = false
                setTitle(title)
                setSubtitle(subtitle)
            }
        addPreference(header)
        header.block()
    }

    /** 页首的「恢复本页默认」。 */
    private fun PreferenceGroup.addRestoreAll(resetters: MutableList<() -> Unit>) {
        addPreference(
            Preference(context).apply {
                title = "恢复本页默认"
                summary = "把这一页的取景框、透明度、识别门槛全部恢复成出厂值"
                isIconSpaceReserved = false
                isPersistent = false
                setOnPreferenceClickListener {
                    AlertDialog.Builder(context)
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

    private fun PreferenceGroup.addSwitchSetting(
        title: String,
        summary: String,
        default: Boolean,
        current: Boolean,
        resetters: MutableList<() -> Unit>,
        onChange: (Boolean) -> Unit,
    ) {
        val pref =
            BambooSwitch(context).apply {
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
        // 坑：`pref.isChecked = x` 是程序赋值，**不会**触发 OnPreferenceChangeListener
        // （那个只响应用户点击），所以必须显式 onChange 写 prefs，再把视图同步过去，
        // 否则会出现「界面显示已恢复、实际值没变」——开关和生效值脱钩。
        val restore = {
            onChange(default)
            pref.isChecked = default
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
        unit: String = "%",
        resetters: MutableList<() -> Unit>,
        onChange: (Int) -> Unit,
    ) {
        val pref =
            ValueSliderPreference(context).apply {
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
}
