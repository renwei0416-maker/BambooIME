/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.actions.bamboo.BambooActions
import com.osfans.trime.actions.bamboo.BambooPrefs
import com.osfans.trime.actions.wenquxing.WenquxingPrefs
import com.osfans.trime.actions.wenquxing.WenquxingTips
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.actions.wenquxing.WenquxingStore
import com.osfans.trime.ui.common.BambooClick
import com.osfans.trime.ui.common.BambooSwitch
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.rebuildPreferencesKeepingScroll
import com.osfans.trime.util.addPreference
import java.io.File

/**
 * 「文曲星」设置页：诗词名句提示。
 *
 * 三种使用模式（常开 / 触发码·直接显示 / 触发码·发送查询）与 AI 模式共用同一套状态机，
 * 见 `BambooActions`；页面本身只管把值写进 [WenquxingPrefs]。
 *
 * 每一项右侧有「↺」，页首有「恢复本页默认」，都不动已导入的词包。
 */
class WenquxingFragment : PaddingPreferenceFragment() {

    private val importPack =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }

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
                addBambooCategory("文曲星", "把查到的诗词名句接在编码后面，逗号/句号直接上屏") {
                    addRestoreAll(resetters, "把运行方式、触发码、显示条数、条目显示字数、两个字数门槛和上屏键恢复成出厂值（不动已导入的词包）")

                    addSwitchSetting(
                        title = "启用文曲星",
                        summary = "关掉后不查词包、不显示提示，触发码也失效",
                        default = true,
                        current = WenquxingPrefs.enabled,
                        resetters = resetters,
                    ) { WenquxingPrefs.enabled = it }

                    addChoiceSetting(
                        title = "使用模式",
                        summary = "常开＝不要触发码；两种触发码的共同点是都要先打触发码，区别只在「自动查」还是「按发送键才查」",
                        labels = arrayOf("常开（不需要触发码）", "触发码 · 直接显示", "触发码 · 发送查询"),
                        default = WenquxingPrefs.MODE_TRIGGER_AUTO,
                        current = WenquxingPrefs.mode,
                        resetters = resetters,
                    ) {
            WenquxingPrefs.mode = it
            BambooActions.exitTipsMode()
        }

                    addCodeSetting(resetters)
                }

                addBambooCategory("显示", "编码后面显示多少条、每条取几个字") {
                    addSliderSetting(
                        title = "显示条数",
                        summary = "编码后面最多列几条；超过这个数的结果仍然存在，只是不显示",
                        min = 1,
                        max = 6,
                        step = 1,
                        default = 3,
                        current = WenquxingPrefs.maxDisplay,
                        unit = " 条",
                        resetters = resetters,
                    ) { WenquxingPrefs.maxDisplay = it }

                    addSliderSetting(
                        title = "条目显示字数",
                        summary = "每条最多取多少个字；太长会把列撑开",
                        min = 4,
                        max = 100,
                        step = 1,
                        default = 40,
                        current = WenquxingPrefs.displayLen,
                        unit = " 字",
                        resetters = resetters,
                    ) { WenquxingPrefs.displayLen = it }

                    addSliderSetting(
                        title = "诗词标题字数",
                        summary = "`=` 前面的标题至少几个字才提示；设 3 时「春晓」不提示、「静夜思」提示",
                        min = 1,
                        max = 8,
                        step = 1,
                        default = 2,
                        current = WenquxingPrefs.minTitleLen,
                        unit = " 字",
                        resetters = resetters,
                    ) {
                        WenquxingPrefs.minTitleLen = it
                        WenquxingTips.requery()
                    }

                    addSliderSetting(
                        title = "分句的字数",
                        summary = "`{}` 里拆出来的分句至少几个字才提示；调大能少些零碎命中，调小更灵敏",
                        min = 1,
                        max = 8,
                        step = 1,
                        default = 4,
                        current = WenquxingPrefs.minSegLen,
                        unit = " 字",
                        resetters = resetters,
                    ) {
                        WenquxingPrefs.minSegLen = it
                        WenquxingTips.requery()
                    }

                    addSliderSetting(
                        title = "字号",
                        summary = "提示块的字号，相对编码行的百分比",
                        min = 50,
                        max = 150,
                        step = 5,
                        default = 80,
                        current = WenquxingPrefs.tipsTextScale,
                        unit = " %",
                        resetters = resetters,
                    ) { WenquxingPrefs.tipsTextScale = it }

                    addSliderSetting(
                        title = "背景不透明度",
                        summary = "印在底下那层背景的浓度，默认 90%",
                        min = 0,
                        max = 100,
                        step = 5,
                        default = 90,
                        current = WenquxingPrefs.tipsAlpha,
                        unit = " %",
                        resetters = resetters,
                    ) { WenquxingPrefs.tipsAlpha = it }

                    addSwitchSetting(
                        title = "显示命中项字数",
                        summary = "在每条末尾显示「·N字」：点这一条会提交多少字",
                        default = true,
                        current = WenquxingPrefs.showCharCount,
                        resetters = resetters,
                    ) {
                        WenquxingPrefs.showCharCount = it
                        TrimeInputMethodService.current?.refreshCandidatesPreedit()
                    }
                }

                addBambooCategory("上屏", "命中之后用哪两个键把正文打出来") {
                    addChoiceSetting(
                        title = "第一档上屏键",
                        summary = "按它上屏第 1 条",
                        labels = KEY_LABELS,
                        default = 0,
                        current = keyIndex(WenquxingPrefs.keyFirst),
                        resetters = resetters,
                    ) { WenquxingPrefs.keyFirst = KEY_VALUES[it] }

                    addChoiceSetting(
                        title = "第二档上屏键",
                        summary = "两条结果时上屏第 2 条；三条以上每按一次往后轮换一位",
                        labels = KEY_LABELS,
                        default = 1,
                        current = keyIndex(WenquxingPrefs.keySecond),
                        resetters = resetters,
                    ) { WenquxingPrefs.keySecond = KEY_VALUES[it] }
                }

                addBambooCategory("词包", "内置包＋自己导入的包，可单独启用") {
                    addRow(
                        title = "词包目录",
                        summary = WenquxingStore.userDir().absolutePath + "（点这里打开）",
                    ) {
                        showDirDialog()
                        true
                    }

                    addRow(
                        title = "词包",
                        summary = packSummary(),
                        key = "packs",
                    ) {
                        showPackDialog()
                        true
                    }

                    addRow(
                        title = "导入词包",
                        summary = "选择 .bwx 包文件导入（离线用 tools/wenquxing_pack.py 生成）",
                    ) {
                        runCatching { importPack.launch(arrayOf("*/*")) }
                        true
                    }
                }
            }
    }

    // ---------------------------------------------------------------- 行构造

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

    private fun PreferenceGroup.addRestoreAll(
        resetters: MutableList<() -> Unit>,
        detail: String,
    ) {
        addPreference(
            Preference(context).apply {
                title = "恢复本页默认"
                summary = detail
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
        unit: String,
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

    private fun PreferenceGroup.addChoiceSetting(
        title: String,
        summary: String,
        labels: Array<String>,
        default: Int,
        current: Int,
        resetters: MutableList<() -> Unit>,
        onPick: (Int) -> Unit,
    ) {
        val pref =
            BambooClick(context).apply {
                this.title = title
                this.summary = summary + "（当前：" + labels[current.coerceIn(0, labels.lastIndex)] + "）"
                isIconSpaceReserved = false
                isPersistent = false
                setOnPreferenceClickListener {
                    AlertDialog.Builder(context)
                        .setTitle(title)
                        .setSingleChoiceItems(labels, current.coerceIn(0, labels.lastIndex)) { dialog, which ->
                            onPick(which)
                            this.summary = summary + "（当前：" + labels[which] + "）"
                            dialog.dismiss()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            }
        val restore = {
            onPick(default.coerceIn(0, labels.lastIndex))
            pref.summary = summary + "（当前：" + labels[default.coerceIn(0, labels.lastIndex)] + "）"
        }
        pref.onRestore = restore
        resetters.add(restore)
        addPreference(pref)
    }

    private fun PreferenceGroup.addCodeSetting(resetters: MutableList<() -> Unit>) {
        val summaryOf = { code: String -> "$code（发送 Ctrl+; / jt;，退出 tc;）" }
        val pref =
            BambooClick(context).apply {
                title = "触发码"
                summary = summaryOf(BambooPrefs.wenquxingCode)
                isIconSpaceReserved = false
                isPersistent = false
                setOnPreferenceClickListener {
                    val input =
                        EditText(context).apply {
                            inputType = InputType.TYPE_CLASS_TEXT
                            setText(BambooPrefs.wenquxingCode)
                            setSelection(text.length)
                        }
                    val wrap =
                        LinearLayout(context).apply {
                            setPadding(dp(20), dp(8), dp(20), 0)
                            addView(input)
                        }
                    AlertDialog.Builder(context)
                        .setTitle("触发码")
                        .setMessage("打到编码区就进入文曲星模式，写法和 AI 模式码一样（字母 + 一个终结键）")
                        .setView(wrap)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("好") { _, _ ->
                            val code = input.text.toString().trim()
                            if (code.length < 2) {
                                Toast.makeText(context, "至少两个字", Toast.LENGTH_SHORT).show()
                            } else {
                                BambooPrefs.wenquxingCode = code
                                summary = summaryOf(code)
                            }
                        }
                        .show()
                    true
                }
            }
        val restore = {
            BambooPrefs.wenquxingCode = DEFAULT_CODE
            pref.summary = summaryOf(DEFAULT_CODE)
        }
        pref.onRestore = restore
        resetters.add(restore)
        addPreference(pref)
    }

    private fun PreferenceGroup.addRow(
        title: String,
        summary: String,
        key: String? = null,
        onClick: () -> Boolean,
    ) {
        addPreference(
            BambooClick(context).apply {
                this.key = key
                this.title = title
                this.summary = summary
                isIconSpaceReserved = false
                isPersistent = false
                setOnPreferenceClickListener { onClick() }
            },
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun keyIndex(name: String): Int = KEY_VALUES.indexOf(name).let { if (it < 0) 0 else it }

    // ---------------------------------------------------------------- 词包

    private fun showDirDialog() {
        val dir = WenquxingStore.userDir()
        if (!dir.exists()) dir.mkdirs()
        AlertDialog.Builder(requireContext())
            .setTitle("词包目录")
            .setMessage(dir.absolutePath)
            .setNeutralButton("复制路径") { _, _ ->
                runCatching {
                    val manager = requireContext().getSystemService(ClipboardManager::class.java)
                    manager?.setPrimaryClip(ClipData.newPlainText("path", dir.absolutePath))
                }
                Toast.makeText(requireContext(), "路径已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("打开文件夹") { _, _ -> openFolder(dir) }
            .show()
    }

    private fun openFolder(dir: File) {
        val context = requireContext()
        val folderUri =
            runCatching {
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", dir)
            }.getOrNull()
        val intents = ArrayList<Intent>()
        if (folderUri != null) {
            intents.add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(folderUri, "resource/folder")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            intents.add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(folderUri, "vnd.android.document/directory")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }
        intents.add(Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.fromFile(dir), "resource/folder") })
        for (intent in intents) {
            val ok = runCatching { startActivity(intent); true }.getOrDefault(false)
            if (ok) return
        }
        Toast.makeText(context, "这台机器上没有能打开目录的应用，路径已在上一步显示", Toast.LENGTH_LONG).show()
    }

    private fun packSummary(): String {
        val names = WenquxingStore.packNames()
        if (names.isEmpty()) return "还没有载入任何包"
        val enabled = names.count { WenquxingPrefs.isPackEnabled(it) }
        return "共 ${names.size} 个包，启用 $enabled 个"
    }

    private fun showPackDialog() {
        val names = WenquxingStore.packNames().toTypedArray()
        if (names.isEmpty()) {
            Toast.makeText(requireContext(), "没有找到词包", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(names.size) { WenquxingPrefs.isPackEnabled(names[it]) }
        AlertDialog.Builder(requireContext())
            .setTitle("选择要用的词包")
            .setMultiChoiceItems(names, checked) { _, which, value ->
                WenquxingPrefs.setPackEnabled(names[which], value)
            }
            .setPositiveButton("好") { _, _ -> refresh() }
            .show()
    }

    private fun importFrom(uri: Uri) {
        val context = requireContext()
        val dir = WenquxingStore.userDir()
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(context, "无法创建目录", Toast.LENGTH_SHORT).show()
            return
        }
        val name = queryName(uri) ?: ("import-" + System.currentTimeMillis() + ".bwx")
        val target = File(dir, if (name.endsWith(".bwx")) name else "$name.bwx")
        val ok =
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
        if (ok) {
            WenquxingStore.reload(context)
            refresh()
            Toast.makeText(context, "已导入 ${target.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryName(uri: Uri): String? =
        runCatching {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()

    private fun refresh() {
        runCatching {
            @Suppress("DEPRECATION")
            preferenceScreen.findPreference<Preference>("packs")?.summary = packSummary()
        }
    }

    private companion object {
        const val DEFAULT_CODE = "mj;"

        val KEY_VALUES = arrayOf("comma", "period", "semicolon", "space")
        val KEY_LABELS = arrayOf("逗号", "句号", "分号", "空格")
    }
}
