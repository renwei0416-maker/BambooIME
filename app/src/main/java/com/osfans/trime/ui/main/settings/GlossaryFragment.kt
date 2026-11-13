/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.actions.glossary.GlossaryDownloader
import com.osfans.trime.actions.glossary.GlossaryPrefs
import com.osfans.trime.actions.glossary.GlossaryStore
import com.osfans.trime.actions.glossary.GlossaryTips
import com.osfans.trime.ui.common.BambooSwitch
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.rebuildPreferencesKeepingScroll
import com.osfans.trime.util.addPreference
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 「外语学习」设置页。
 *
 * 词库不随 APK 发布（全量约 8 MB），由这里下载或从文件导入；所有显示细节都在这一页调，
 * 改完立即生效，不用部署方案、不用重启输入法。
 */
class GlossaryFragment : PaddingPreferenceFragment() {
    private val pickPack =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            importPack(uri)
        }

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
        GlossaryStore.reload()
        val resetters = mutableListOf<() -> Unit>()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(requireContext()).apply {
                addRestoreAll(resetters)

                addCategory("语言", "一次只学一门语言；换语言后要再装一门语言的词库包") {
                    addChoice(
                        title = "学习语言",
                        subtitle = if (GlossaryPrefs.lang == GlossaryPrefs.LANG_JA) {
                            "日语：候选下显示日文译词（带假名读音）"
                        } else {
                            "英语：候选下显示英文释义"
                        },
                        entries = arrayOf("英语", "日语"),
                        currentIndex = if (GlossaryPrefs.lang == GlossaryPrefs.LANG_JA) 1 else 0,
                        defaultIndex = 0,
                        resetters = resetters,
                    ) { idx ->
                        GlossaryPrefs.lang = if (idx == 1) GlossaryPrefs.LANG_JA else GlossaryPrefs.LANG_EN
                        GlossaryPrefs.downloadUrl = ""
                        GlossaryStore.reload()
                        rebuildScreen()
                    }
                }

                addCategory("显示", "编码行下面按候选顺序列出「中文 + 英文」，一行一个") {
                    addChoice(
                        title = "在提示区显示哪些",
                        subtitle =
                            if (GlossaryPrefs.displayMode == GlossaryPrefs.MODE_HITS_ONLY) {
                                "只显示命中项：没查到的整条不出现，从当前候选往后最多看 6 个"
                            } else {
                                "始终显示候选项：每个候选都占一行，没查到的只有中文（斜体）"
                            },
                        entries = arrayOf("始终显示候选项", "只显示命中项"),
                        currentIndex = GlossaryPrefs.displayMode,
                        defaultIndex = GlossaryPrefs.MODE_ALWAYS,
                        resetters = resetters,
                    ) { idx ->
                        GlossaryPrefs.displayMode = idx
                        TrimeInputMethodService.current?.refreshCandidatesPreedit()
                        rebuildScreen()
                    }
                    addSwitch(
                        "边打字边显示英文",
                        "开＝在编码行下面显示学习条；关掉就完全不占地方，上屏英文的按键照样能用",
                        default = false,
                        current = GlossaryPrefs.enabled,
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.enabled = it
                    }
                    addSeek(
                        "最多显示几条",
                        "最多显示几行；「只显示命中项」时这些行都带英文",
                        min = 1,
                        max = 6,
                        step = 1,
                        default = 4,
                        current = GlossaryPrefs.tipsMax,
                        unit = " 条",
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.tipsMax = it
                    }
                }

                addCategory("显示多少", "太长会截断，用「…」结尾") {
                    addSeek(
                        "义项个数",
                        "一个词最多拼几条释义；拼完再按下面的「每条最多几行」实际排版",
                        min = 1,
                        max = 8,
                        step = 1,
                        default = 5,
                        current = GlossaryPrefs.senseCount,
                        unit = " 条",
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.senseCount = it
                    }
                    addSeek(
                        "每条最多几行",
                        "按实际宽度排版：宽就多显示，放不下才截断并出现「[...展开]」",
                        min = 1,
                        max = 3,
                        step = 1,
                        default = 2,
                        current = GlossaryPrefs.maxLines,
                        unit = " 行",
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.maxLines = it
                    }
                    addSeek(
                        "字号",
                        "提示块的字号，相对编码行的百分比；调大更好认，调小能看到更多候选",
                        min = 50,
                        max = 150,
                        step = 5,
                        default = 90,
                        current = GlossaryPrefs.tipsTextScale,
                        unit = " %",
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.tipsTextScale = it
                    }
                    addSeek(
                        "背景不透明度",
                        "印在底上那层背景的浓度；太低会透出输入框里的字",
                        min = 0,
                        max = 100,
                        step = 5,
                        default = 90,
                        current = GlossaryPrefs.tipsAlpha,
                        unit = " %",
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.tipsAlpha = it
                    }
                    addSwitch(
                        "显示命中项字数",
                        "在每条末尾显示「·N字」：点这一条会提交多少字",
                        default = true,
                        current = GlossaryPrefs.showCharCount,
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.showCharCount = it
                        TrimeInputMethodService.current?.refreshCandidatesPreedit()
                    }
                    addSeek(
                        "未命中行不透明度",
                        "词库里没有的词只显示中文（斜体），这里是那行中文的浓度；调低更不抢眼",
                        min = 0,
                        max = 100,
                        step = 5,
                        default = 80,
                        current = GlossaryPrefs.unmatchedAlpha,
                        unit = " %",
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.unmatchedAlpha = it
                    }
                    addSeek(
                        "未命中行字号",
                        "没命中的那行中文相对上面「字号」的百分比；默认 100% ＝ 和命中行一样大",
                        min = 50,
                        max = 150,
                        step = 5,
                        default = 100,
                        current = GlossaryPrefs.unmatchedTextScale,
                        unit = " %",
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.unmatchedTextScale = it
                    }
                    addRow(
                        "学习条和上屏共用这里的设置",
                        "学习条每条一行，英文太长会截断成「…」；上屏用的是纯英文、不带标签",
                    )
                }

                addCategory("显示什么", "打包时全部留着，这里决定露不露出来") {
                    addSwitch(
                        "词性",
                        "显示 v. / n. / adj. 之类的词性前缀（规则推导，覆盖约四分之一义项）",
                        default = true,
                        current = GlossaryPrefs.showPos,
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.showPos = it
                    }
                    addSwitch(
                        "语体标签",
                        "书面、口语、俚语、方言、古、敬、贬、外来语等",
                        default = true,
                        current = GlossaryPrefs.showStyle,
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.showStyle = it
                    }
                    addSwitch(
                        "领域标签",
                        "计算机、数学、中医、体育、植物、成语、地名等",
                        default = false,
                        current = GlossaryPrefs.showDomain,
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.showDomain = it
                    }
                    addSwitch(
                        "量词",
                        "形如〔CL: 个〕的量词注",
                        default = true,
                        current = GlossaryPrefs.showCl,
                        resetters = resetters,
                    ) {
                        GlossaryPrefs.showCl = it
                    }
                }

                addCategory("语言包", "全量中文→英文约 11.5 万词、18.7 万义项，8 MB") {
                    val files = GlossaryStore.installedFiles()
                    if (files.isEmpty()) {
                        addRow("还没有安装语言包", "点下面「下载」或「从文件导入」，装好后候选才有英文")
                    } else {
                        files.forEach { f ->
                            addRow(
                                f.name,
                                "${"%.1f".format(f.length() / 1024.0 / 1024.0)} MB · 点击可删除",
                            ) {
                                AlertDialog.Builder(requireContext())
                                    .setTitle("删除语言包")
                                    .setMessage("删除 ${f.name} 后，这一份词库的英文就不显示了。") 
                                    .setNegativeButton(R.string.cancel, null)
                                    .setPositiveButton("删除") { _, _ ->
                                        GlossaryStore.delete(f)
                                        rebuildScreen()
                                    }.show()
                            }
                        }
                        addRow(
                            "已加载 ${GlossaryStore.packCount()} 个包、${GlossaryStore.entryCount()} 个词",
                            "多个包会同时查询，结果合并显示",
                        )
                    }
                    addRow("下载语言包", "约 8 MB，走下面这个地址（国内可换成任意镜像）") {
                        confirmDownload()
                    }
                    addRow("从文件导入", "用系统的文件选择器挑一个 .bge") {
                        runCatching { pickPack.launch(arrayOf("*/*")) }
                    }
                    addRow(
                        "从文件夹导入",
                        "把 .bge 放进 ${glossaryFolder().absolutePath}，点这里全部安装",
                    ) {
                        importFromFolder()
                    }
                    addRow("下载地址", GlossaryPrefs.downloadUrl) {
                        editDownloadUrl()
                    }
                }

                addCategory("说明与许可", null) {
                    if (GlossaryPrefs.lang == GlossaryPrefs.LANG_JA) {
                        addRow(
                            "词库来源：青简释义表（MIT）",
                            "由青简项目用 DeepSeek 离线生成，按 MIT 许可使用；本项目只做了格式转换与字段清洗",
                        )
                    } else {
                        addRow(
                            "词库来源：CC-CEDICT",
                            "CC BY-SA 4.0，由 MDBG 发布；本项目的 .bge 是它的改编版本，同样以 CC BY-SA 4.0 提供",
                        )
                    }
                }
            }
    }

    // ---- 动作 ----

    private fun confirmDownload() {
        AlertDialog.Builder(requireContext())
            .setTitle("下载语言包")
            .setMessage("将从下面的地址下载约 8 MB 的词库：\n\n${GlossaryPrefs.downloadUrl}")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("下载") { _, _ -> startDownload() }
            .show()
    }

    private fun startDownload() {
        val dialog =
            AlertDialog.Builder(requireContext())
                .setTitle("正在下载")
                .setMessage("0%")
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, null)
                .show()
        lifecycleScope.launch {
            val err =
                withContext(Dispatchers.IO) {
                    GlossaryDownloader.download(GlossaryPrefs.downloadUrl) { done, total ->
                        val text = if (total > 0) "${done * 100 / total}%" else "${done / 1024} KB"
                        dialog.setMessage(text)
                    }
                }
            dialog.dismiss()
            if (!isAdded) return@launch
            if (err == null) {
                Toast.makeText(requireContext(), "语言包已安装", Toast.LENGTH_SHORT).show()
                rebuildScreen()
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle("下载失败")
                    .setMessage("$err\n\n可以换成镜像地址后重试，或先在电脑上下载再「从文件导入」。")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /** 用户放 .bge 的公开目录：`Documents/BambooIME[Debug]/glossary/`。 */
    private fun glossaryFolder(): File = File(DataManager.defaultDataDir, "glossary").also { it.mkdirs() }

    /** 问系统要真文件名——`uri.lastPathSegment` 常常是 `document/primary:Download/x.bge` 这种。 */
    private fun displayNameOf(uri: Uri): String? =
        runCatching {
            requireContext().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    /**
     * 三种打开方式依次兜底：`openInputStream` → `openFileDescriptor` → `openAssetFileDescriptor`。
     * 返回 `null` 表示安装成功。（部分 ROM 上第一种会返回空流，直接判失败太武断。）
     */
    private fun installFromUri(uri: Uri, name: String): String? {
        val cr = requireContext().contentResolver
        var last: String? = null
        runCatching { cr.openInputStream(uri) }
            .onFailure { last = it.message }
            .getOrNull()
            ?.use { return GlossaryStore.install(it, name) }
        runCatching { cr.openFileDescriptor(uri, "r") }
            .onFailure { last = it.message }
            .getOrNull()
            ?.use { return GlossaryStore.install(FileInputStream(it.fileDescriptor), name) }
        runCatching { cr.openAssetFileDescriptor(uri, "r") }
            .onFailure { last = it.message }
            .getOrNull()
            ?.use { return GlossaryStore.install(FileInputStream(it.fileDescriptor), name) }
        Timber.w("glossary: cannot open %s authority=%s err=%s", uri, uri.authority, last ?: "null stream")
        return "读不到这个文件（${last ?: "系统没给出内容流"}）。" +
            "可以先把 .bge 放进 ${glossaryFolder().absolutePath}，再用「从文件夹导入」"
    }

    private fun importPack(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val err =
                withContext(Dispatchers.IO) {
                    val name = displayNameOf(uri) ?: uri.lastPathSegment ?: "glossary.bge"
                    Timber.d("glossary: import %s as %s", uri, name)
                    runCatching { installFromUri(uri, name) }
                        .getOrElse { it.message ?: it.javaClass.simpleName }
                }
            if (!isAdded) return@launch
            if (err == null) {
                Toast.makeText(ctx, "语言包已安装", Toast.LENGTH_SHORT).show()
                rebuildScreen()
            } else {
                Toast.makeText(ctx, "导入失败：$err", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 扫固定目录，把里面的 .bge 全部装进来——避开系统文件选择器的各种坑。 */
    private fun importFromFolder() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val err =
                withContext(Dispatchers.IO) {
                    val dir = glossaryFolder()
                    val packs =
                        dir.listFiles { f -> f.isFile && f.name.endsWith(".bge", true) }
                            ?.sortedBy { it.name }
                            .orEmpty()
                    if (packs.isEmpty()) {
                        "目录里没有 .bge：\n${dir.absolutePath}\n\n把下载好的包拷进去再来一次。"
                    } else {
                        val failed =
                            packs.mapNotNull { f ->
                                GlossaryStore.installFile(f)?.let { "${f.name}：$it" }
                            }
                        if (failed.isEmpty()) null else failed.joinToString("\n")
                    }
                }
            if (!isAdded) return@launch
            if (err == null) {
                Toast.makeText(ctx, "语言包已安装", Toast.LENGTH_SHORT).show()
                rebuildScreen()
            } else {
                AlertDialog.Builder(ctx)
                    .setTitle("从文件夹导入")
                    .setMessage(err)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun editDownloadUrl() {
        val input =
            EditText(requireContext()).apply {
                setText(GlossaryPrefs.downloadUrl)
                setSelection(text.length)
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            }
        AlertDialog.Builder(requireContext())
            .setTitle("下载地址")
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton("恢复默认") { _, _ ->
                GlossaryPrefs.downloadUrl = GlossaryPrefs.DEFAULT_DOWNLOAD_URL
                rebuildScreen()
            }.setPositiveButton("保存") { _, _ ->
                GlossaryPrefs.downloadUrl = input.text.toString().trim()
                rebuildScreen()
            }.show()
    }

    // ---- 通用小控件 ----

    private fun PreferenceScreen.addRestoreAll(resetters: MutableList<() -> Unit>) {
        addPreference(
            Preference(requireContext()).apply {
                key = "restore_all"
                title = "恢复本页默认"
                summary = "把本页的显示设置恢复成出厂值（不动已安装的语言包）"
                isIconSpaceReserved = true
                isPersistent = false
                setOnPreferenceClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("恢复本页默认")
                        .setMessage("本页所有显示设置都会恢复成默认值，确定吗？")
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton("恢复") { _, _ ->
                            resetters.forEach { runCatching { it() } }
                            GlossaryPrefs.resetToDefault()
                            rebuildScreen()
                        }.show()
                    true
                }
            },
        )
    }

    private fun PreferenceScreen.addCategory(
        title: String,
        subtitle: String?,
        block: PreferenceCategory.() -> Unit,
    ) {
        val category =
            BambooCategoryPreference(requireContext()).apply {
                layoutResource = R.layout.preference_bamboo_category
                isIconSpaceReserved = false
                isPersistent = false
                key = title
                setTitle(title)
                if (subtitle != null) setSubtitle(subtitle)
            }
        addPreference(category)
        category.block()
    }

    private fun PreferenceScreen.addRow(
        title: String,
        subtitle: String,
        onClick: (() -> Unit)? = null,
    ) {
        addPreference(
            Preference(requireContext()).apply {
                key = "row_$title"
                this.title = title
                summary = subtitle
                isIconSpaceReserved = true
                isPersistent = false
                if (onClick != null) {
                    setOnPreferenceClickListener {
                        onClick()
                        true
                    }
                }
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
        // 程序赋值不触发监听，必须先写 prefs 再同步视图。
        val restore = {
            onChange(default)
            pref.isChecked = default
        }
        pref.onRestore = restore
        resetters.add(restore)
        addPreference(pref)
    }

    private fun PreferenceScreen.addChoice(
        title: String,
        subtitle: String,
        entries: Array<String>,
        currentIndex: Int,
        defaultIndex: Int,
        resetters: MutableList<() -> Unit>,
        onPick: (Int) -> Unit,
    ) {
        val pref =
            Preference(requireContext()).apply {
                key = "choice_$title"
                this.title = title
                summary = subtitle
                isIconSpaceReserved = true
                isPersistent = false
                setOnPreferenceClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle(title)
                        .setSingleChoiceItems(entries, currentIndex) { dialog, which ->
                            onPick(which)
                            dialog.dismiss()
                        }.setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
            }
        val restore = {
            onPick(defaultIndex)
            pref.summary = subtitle
        }
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
