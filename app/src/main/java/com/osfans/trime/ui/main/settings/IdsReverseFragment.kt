/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.actions.ids.IdsConfig
import com.osfans.trime.actions.ids.IdsPrefs
import com.osfans.trime.actions.ids.IdsSync
import com.osfans.trime.actions.ids.idsConfigOrNull
import com.osfans.trime.actions.ids.resolvedShuangpinScheme
import com.osfans.trime.actions.wanxiang.WanxiangCustom
import com.osfans.trime.data.packaging.SchemaPackageManager
import com.osfans.trime.data.packaging.SchemaPackStore
import com.osfans.trime.link.IdsDataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.ui.common.BambooClick
import com.osfans.trime.util.toast
import com.osfans.trime.ui.common.BambooSwitch
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「部件反查（IDS）」设置页。
 *
 * 插件（librime-ids-reverse）已经编进包里，缺的只是 29 MB 的 `ids_data.bin`，
 * 所以这里第一件事是下载/导入数据，第二件事是把 4 个组件挂进万象方案的 `engine:`。
 *
 * 挂接落点固定写「万象 Base / Pro 的 custom 文件托管块」，非 Base/Pro 的用户
 * （自己的方案）需要照底部「说明」里的片段自己贴。
 */
class IdsReverseFragment : PaddingPreferenceFragment() {
    private var downloadJob: kotlinx.coroutines.Job? = null
    private var syncing = false

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val tmp = java.io.File(requireContext().cacheDir, "ids-import.bin")
                            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                                tmp.outputStream().use { out -> input.copyTo(out) }
                            } ?: return@runCatching false
                            val installed = IdsDataManager.importFrom(tmp)
                            tmp.delete()
                            installed
                        }.getOrDefault(false)
                    }
                requireContext().toast(
                    if (ok) {
                        "已导入（${IdsDataManager.installedSize() / 1024 / 1024} MB），可以打开开关使用反查了"
                    } else {
                        "导入失败：文件大小不符（应为 29.0 MB）"
                    },
                )
                refreshScreen()
            }
        }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).also {
            buildScreen(it)
        }
    }

    override fun onDestroyView() {
        downloadJob?.cancel()
        super.onDestroyView()
    }

    private fun buildScreen(screen: PreferenceScreen) {
        val installed = IdsDataManager.isInstalled()

        screen.addBambooCategory("开关", "先把数据装上，再打开；关掉会从方案里摘掉这几个组件") {
            addPreference(
                BambooSwitch(requireContext()).apply {
                    key = "ids_enabled"
                    title = "启用部件反查"
                    isChecked = IdsPrefs.enabled
                    isEnabled = installed
                    summary =
                        when {
                            !installed -> "还不能开：数据没装（先点下面「下载数据」）"
                            IdsPrefs.enabled -> "开：打 ${IdsPrefs.searchPrefix} 查部件（如 ${IdsPrefs.searchPrefix}木口）"
                            else -> "关（默认）：万象方案里不会挂这 4 个组件"
                        }
                    isIconSpaceReserved = false
                    onRestore = {
                        IdsPrefs.enabled = false
                        refreshScreen()
                    }
                    setOnPreferenceChangeListener { _, newValue ->
                        if (newValue as Boolean && !IdsDataManager.isInstalled()) {
                            requireContext().toast("先下载数据（约 29 MB）")
                            return@setOnPreferenceChangeListener false
                        }
                        IdsPrefs.enabled = newValue
                        refreshScreen()
                        // 拨动开关就是「启用 / 停用」本身：立刻写盘并部署，不要求用户再去点按钮。
                        syncAndDeploy()
                        true
                    }
                },
            )

            addPreference(
                BambooClick(requireContext()).apply {
                    key = "ids_data_status"
                    title = "数据状态"
                    summary =
                        if (installed) {
                            "已安装 · ${IdsDataManager.installedSize() / 1024 / 1024} MB"
                        } else {
                            "未安装 · 需要 ids_data.bin（29.0 MB）"
                        }
                    isIconSpaceReserved = false
                    isSelectable = false
                },
            )

            if (IdsPrefs.deployCheck.isNotBlank()) {
                addPreference(
                    BambooClick(requireContext()).apply {
                        key = "ids_deploy_status"
                        title = "上次部署自检"
                        summary = IdsPrefs.deployCheck
                        isIconSpaceReserved = false
                        isSelectable = false
                    },
                )
            }

            addPreference(
                BambooClick(requireContext()).apply {
                    key = "ids_download"
                    title = if (installed) "重新下载数据" else "下载数据"
                    summary = "GitHub / CNB 双源，自动选快的那个；完成后自动校验 sha256"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        startDownload()
                        true
                    }
                },
            )

            addPreference(
                BambooClick(requireContext()).apply {
                    key = "ids_import"
                    title = "从文件导入"
                    summary = "手动挑一个 ids_data.bin（会被校验大小）"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        runCatching { importLauncher.launch(arrayOf("*/*")) }
                            .onFailure { requireContext().toast("打不开文件选择器") }
                        true
                    }
                },
            )

            if (installed) {
                addPreference(
                    BambooClick(requireContext()).apply {
                        key = "ids_delete"
                        title = "删除数据"
                        summary = "只删这一份数据，不影响词库与主题"
                        isIconSpaceReserved = false
                        onRestore = null
                        setOnPreferenceClickListener {
                            AlertDialog.Builder(requireContext())
                                .setTitle("删除部件反查数据？")
                                .setMessage("删掉后功能会关闭，并把方案里的 IDS 配置一起摘掉；下次用要重新下载（29.0 MB）。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除") { _, _ ->
                                    IdsDataManager.delete()
                                    IdsPrefs.enabled = false
                                    requireContext().toast("已删除")
                                    refreshScreen()
                                    syncAndDeploy()
                                }
                                .show()
                            true
                        }
                    },
                )
            }

            addPreference(
                BambooClick(requireContext()).apply {
                    key = "ids_host"
                    title = "下载源"
                    summary =
                        when (IdsPrefs.host) {
                            IdsPrefs.HOST_GITHUB -> "优先 GitHub；失败自动换 CNB"
                            IdsPrefs.HOST_CNB -> "优先 CNB（国内镜像）；失败自动换 GitHub"
                            else -> "自动（两个源测速，快的先下；失败自动换另一个）"
                        }
                    isIconSpaceReserved = false
                    onRestore = {
                        IdsPrefs.host = IdsPrefs.HOST_AUTO
                        refreshScreen()
                    }
                    setOnPreferenceClickListener {
                        val labels = arrayOf("自动（测速，失败换源）", "优先 GitHub（失败换源）", "优先 CNB（失败换源）")
                        val values = intArrayOf(IdsPrefs.HOST_AUTO, IdsPrefs.HOST_GITHUB, IdsPrefs.HOST_CNB)
                        val checked = values.indexOf(IdsPrefs.host).coerceAtLeast(0)
                        AlertDialog.Builder(requireContext())
                            .setTitle("下载源")
                            .setSingleChoiceItems(labels, checked) { dialog, which ->
                                IdsPrefs.host = values[which]
                                IdsDataManager.invalidateCache()
                                dialog.dismiss()
                                refreshScreen()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                        true
                    }
                },
            )
        }

        screen.addBambooCategory("输入", "打前缀，再打部件名（木、口、土…）；一次多打几个部件就是组合查询") {
            addPreference(
                textRow(
                    key = "ids_search_prefix",
                    title = "查找前缀",
                    value = IdsPrefs.searchPrefix,
                    hint = "单个英文字母，默认 I",
                    singleChar = true,
                ) { IdsPrefs.searchPrefix = it },
            )
            addPreference(
                textRow(
                    key = "ids_hybrid_prefix",
                    title = "组合前缀",
                    value = IdsPrefs.hybridPrefix,
                    hint = "拼音与部件混着打，默认 O",
                    singleChar = true,
                ) { IdsPrefs.hybridPrefix = it },
            )
            addPreference(
                textRow(
                    key = "ids_max_results",
                    title = "最多候选",
                    value = IdsPrefs.maxResults.toString(),
                    hint = "1–300，默认 60",
                    numeric = true,
                ) { raw ->
                    raw.toIntOrNull()?.let { IdsPrefs.maxResults = it.coerceIn(1, 300) }
                },
            )

            addPreference(
                BambooSwitch(requireContext()).apply {
                    key = "ids_shuangpin_follow"
                    title = "双拼方案跟随键盘"
                    isChecked = IdsPrefs.shuangpinFollowKeyboard
                    summary =
                        if (IdsPrefs.shuangpinFollowKeyboard) {
                            "开（默认）：按当前键盘判断（小鹤→flypy），当前解析为 ${resolvedShuangpinScheme()}"
                        } else {
                            "关：用下面手动选的方案"
                        }
                    isIconSpaceReserved = false
                    onRestore = {
                        IdsPrefs.shuangpinFollowKeyboard = true
                        refreshScreen()
                    }
                    setOnPreferenceChangeListener { _, newValue ->
                        IdsPrefs.shuangpinFollowKeyboard = newValue as Boolean
                        refreshScreen()
                        true
                    }
                },
            )

            if (!IdsPrefs.shuangpinFollowKeyboard) {
                addPreference(
                    BambooClick(requireContext()).apply {
                        key = "ids_shuangpin_scheme"
                        title = "双拼方案"
                        summary = IdsPrefs.shuangpinScheme
                        isIconSpaceReserved = false
                        setOnPreferenceClickListener {
                            val labels = arrayOf("小鹤（flypy）", "自然码（zrm）", "全拼（quanpin）")
                            val values = arrayOf("flypy", "zrm", "quanpin")
                            val checked = values.indexOf(IdsPrefs.shuangpinScheme).coerceAtLeast(0)
                            AlertDialog.Builder(requireContext())
                                .setTitle("双拼方案")
                                .setSingleChoiceItems(labels, checked) { dialog, which ->
                                    IdsPrefs.shuangpinScheme = values[which]
                                    dialog.dismiss()
                                    refreshScreen()
                                }
                                .setNegativeButton("取消", null)
                                .show()
                            true
                        }
                    },
                )
            }
        }

        screen.addBambooCategory("显示", "反查候选里显示什么；只影响显示，不影响能不能查到") {
            addPreference(
                BambooSwitch(requireContext()).apply {
                    key = "ids_show_pinyin"
                    title = "显示拼音"
                    isChecked = IdsPrefs.showPinyin
                    summary = "在候选后面标读音"
                    isIconSpaceReserved = false
                    onRestore = {
                        IdsPrefs.showPinyin = true
                        refreshScreen()
                    }
                    setOnPreferenceChangeListener { _, v ->
                        IdsPrefs.showPinyin = v as Boolean
                        refreshScreen()
                        true
                    }
                },
            )
            addPreference(
                BambooSwitch(requireContext()).apply {
                    key = "ids_show_ids"
                    title = "显示部件结构（IDS）"
                    isChecked = IdsPrefs.showIds
                    summary = "例如 木口 / ⿰木口"
                    isIconSpaceReserved = false
                    onRestore = {
                        IdsPrefs.showIds = true
                        refreshScreen()
                    }
                    setOnPreferenceChangeListener { _, v ->
                        IdsPrefs.showIds = v as Boolean
                        refreshScreen()
                        true
                    }
                },
            )
            addPreference(
                BambooSwitch(requireContext()).apply {
                    key = "ids_show_unicode"
                    title = "显示 Unicode"
                    isChecked = IdsPrefs.showUnicode
                    summary = "例如 U+6797"
                    isIconSpaceReserved = false
                    onRestore = {
                        IdsPrefs.showUnicode = false
                        refreshScreen()
                    }
                    setOnPreferenceChangeListener { _, v ->
                        IdsPrefs.showUnicode = v as Boolean
                        refreshScreen()
                        true
                    }
                },
            )
            addPreference(
                BambooSwitch(requireContext()).apply {
                    key = "ids_show_block"
                    title = "显示分区"
                    isChecked = IdsPrefs.showBlock
                    summary = "标出这个字在哪个 Unicode 区（CJK / 扩展 A…）"
                    isIconSpaceReserved = false
                    onRestore = {
                        IdsPrefs.showBlock = false
                        refreshScreen()
                    }
                    setOnPreferenceChangeListener { _, v ->
                        IdsPrefs.showBlock = v as Boolean
                        refreshScreen()
                        true
                    }
                },
            )
            addPreference(
                BambooClick(requireContext()).apply {
                    key = "ids_format"
                    title = "结构格式"
                    summary =
                        when (IdsPrefs.idsFormat) {
                            IdsPrefs.FORMAT_BRACKET -> "括号（⿰木口）"
                            IdsPrefs.FORMAT_PLAIN -> "纯部件（木口）"
                            else -> "Unicode（⿰ 这类结构符）"
                        }
                    isIconSpaceReserved = false
                    onRestore = {
                        IdsPrefs.idsFormat = IdsPrefs.FORMAT_UNICODE
                        refreshScreen()
                    }
                    setOnPreferenceClickListener {
                        val labels = arrayOf("Unicode 结构符", "括号写法", "纯部件")
                        val values = arrayOf(IdsPrefs.FORMAT_UNICODE, IdsPrefs.FORMAT_BRACKET, IdsPrefs.FORMAT_PLAIN)
                        val checked = values.indexOf(IdsPrefs.idsFormat).coerceAtLeast(0)
                        AlertDialog.Builder(requireContext())
                            .setTitle("结构格式")
                            .setSingleChoiceItems(labels, checked) { dialog, which ->
                                IdsPrefs.idsFormat = values[which]
                                dialog.dismiss()
                                refreshScreen()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                        true
                    }
                },
            )
            addPreference(
                textRow(
                    key = "ids_variant_trigger",
                    title = "异体字触发符",
                    value = IdsPrefs.variantTrigger,
                    hint = "默认 /，打了它才列异体字",
                    singleChar = true,
                ) { IdsPrefs.variantTrigger = it },
            )
            addPreference(
                textRow(
                    key = "ids_variant_replace",
                    title = "异体字替换键",
                    value = IdsPrefs.variantReplaceKey,
                    hint = "默认 Shift+V",
                ) { IdsPrefs.variantReplaceKey = it },
            )
            addPreference(
                textRow(
                    key = "ids_disabled_blocks",
                    title = "禁用分区",
                    value = IdsPrefs.disabledBlocks,
                    hint = "留空＝不限制。多个用逗号分隔，如 ExtB,ExtC",
                    allowEmpty = true,
                ) { IdsPrefs.disabledBlocks = it },
            )
            addPreference(
                textRow(
                    key = "ids_search_tips",
                    title = "查找提示文案",
                    value = IdsPrefs.searchTips,
                    hint = "留空＝用默认提示",
                    allowEmpty = true,
                ) { IdsPrefs.searchTips = it },
            )
            addPreference(
                textRow(
                    key = "ids_hybrid_tips",
                    title = "组合提示文案",
                    value = IdsPrefs.hybridTips,
                    hint = "留空＝用默认提示",
                    allowEmpty = true,
                ) { IdsPrefs.hybridTips = it },
            )
        }

        screen.addBambooCategory("应用", "改完点这里才会写进万象方案的 custom 文件并重新部署") {
            addPreference(
                BambooClick(requireContext()).apply {
                    key = "ids_apply"
                    title = "应用并部署"
                    summary = "按当前开关状态写入或摘除，然后部署（几十秒）"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        syncAndDeploy()
                        true
                    }
                },
            )
            addPreference(
                BambooClick(requireContext()).apply {
                    key = "ids_reset"
                    title = "恢复本页默认"
                    summary = "关掉功能、前缀回 I / O、最多 60 个、显示项回默认（不删数据）"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        AlertDialog.Builder(requireContext())
                            .setTitle("恢复本页默认？")
                            .setMessage("只恢复本页这些开关与数值，不动已下载的数据，也不动词库和主题。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("恢复") { _, _ ->
                                IdsPrefs.reset()
                                refreshScreen()
                            }
                            .show()
                        true
                    }
                },
            )
        }

        screen.addBambooCategory("说明", "不用万象 Base / Pro 的人看这里：把片段贴到自己方案的 custom 文件") {
            addPreference(
                BambooClick(requireContext()).apply {
                    key = "ids_doc"
                    title = "配置说明（每一项设置什么）"
                    summary = "看这 4 个组件分别是什么、每个参数什么意思"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        AlertDialog.Builder(requireContext())
                            .setTitle("部件反查配置说明")
                            .setMessage(DOC_TEXT)
                            .setPositiveButton("知道了", null)
                            .show()
                        true
                    }
                },
            )
            addPreference(
                BambooClick(requireContext()).apply {
                    key = "ids_copy"
                    title = "复制这段配置"
                    summary = "把下面这段 YAML 复制到剪贴板，贴到你方案的 patch: 下"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        copyPatch()
                        true
                    }
                },
            )
            addPreference(
                BambooClick(requireContext()).apply {
                    key = "ids_preview"
                    title = "查看当前会写入的内容"
                    summary = "按现在的设置生成，只读"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        AlertDialog.Builder(requireContext())
                            .setTitle("将要写入")
                            .setMessage(patchSnippet(idsConfigOrNull() ?: previewConfig()))
                            .setPositiveButton("好", null)
                            .show()
                        true
                    }
                },
            )
        }
    }

    // ---- 数据下载 ----

    private fun startDownload() {
        if (downloadJob?.isActive == true) {
            requireContext().toast("正在下载中…")
            return
        }
        val progress = AlertDialog.Builder(requireContext())
            .setTitle("下载 ids_data.bin")
            .setMessage("准备中…")
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create()
        progress.show()
        progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            downloadJob?.cancel()
            progress.dismiss()
        }

        downloadJob =
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        IdsDataManager.download(
                            onProgress = { fraction, done, total ->
                                lifecycleScope.launch(Dispatchers.Main) {
                                    progress.setMessage(
                                        if (total > 0) {
                                            "${(fraction * 100).toInt()}% · ${done / 1024 / 1024} / ${total / 1024 / 1024} MB"
                                        } else {
                                            "${done / 1024 / 1024} MB"
                                        },
                                    )
                                }
                            },
                            isCancelled = { downloadJob?.isCancelled == true },
                            onAttempt = { label, index, total ->
                                lifecycleScope.launch(Dispatchers.Main) {
                                    progress.setMessage("下载源：$label（$index/$total）")
                                }
                            },
                        )
                    }
                }.onSuccess {
                    progress.dismiss()
                    // 下载只负责下载：不动开关、不写 patch、不部署。
                    // 若在这里顺手把开关置 true，会出现「值=true 而控件外观仍是关」的双源，
                    // 用户以为没开又点一下 → 值变 false → 刚写进去的 patch 被摘掉。
                    requireContext().toast(
                        "已下载（${IdsDataManager.installedSize() / 1024 / 1024} MB），可以打开开关使用反查了",
                    )
                    refreshScreen()
                }.onFailure {
                    progress.dismiss()
                    val msg = it.message ?: it.javaClass.simpleName
                    if (downloadJob?.isCancelled == true) {
                        requireContext().toast("已取消")
                    } else {
                        AlertDialog.Builder(requireContext())
                            .setTitle("下载失败")
                            .setMessage(msg)
                            .setPositiveButton("好", null)
                            .show()
                    }
                }
            }
    }

    // ---- 写入 ----

    /**
     * 唯一的写入入口：按钮「应用并部署」、拨动开关、删数据、下载/导入完成都走这里。
     *
     * 具体写什么由 [IdsSync] 决定（只看开关 + 数据在不在），这里只管前置检查与提示。
     */
    private fun syncAndDeploy() {
        if (syncing) {
            requireContext().toast("正在处理上一步，稍等")
            return
        }
        val target = WanxiangCustom.scopeTarget()
        if (target == null) {
            requireContext().toast("只有万象 Base / Pro 方案能自动写入；自己的方案请看「说明」手动贴")
            return
        }
        syncing = true
        lifecycleScope.launch {
            try {
                if (target == SchemaPackStore.BASE_ID && !SchemaPackStore.isInstalled(SchemaPackStore.BASE_ID)) {
                    requireContext().toast("正在释放内置方案包…")
                    val copied = withContext(Dispatchers.IO) { SchemaPackStore.ensureBaseCopied(requireContext()) }
                    if (!copied) {
                        requireContext().toast("释放内置包失败：检查 Documents 目录是否可写")
                        return@launch
                    }
                }
                if (target == SchemaPackStore.PRO_ID && !SchemaPackStore.isInstalled(SchemaPackStore.PRO_ID)) {
                    requireContext().toast("还没装 Pro 词库：先去「方案包与下载」装一个")
                    return@launch
                }
                requireContext().toast("正在写入并部署…")
                val out = IdsSync.applyAndDeploy(target)
                requireContext().toast(out.message)
                if (!out.ok) IdsPrefs.deployCheck = out.message
                refreshScreen()
                if (out.ok) selfCheckDeploy(target)
            } finally {
                syncing = false
            }
        }
    }

    /**
     * 部署完成后回读 `build/<schema>.schema.yaml`，确认 patch 真的合并进去了。
     *
     * 以前只能靠「打字试试」判断，部署失败时会静默保留旧产物；把这一步的结果写到
     * [IdsPrefs.deployCheck] 并显示在设置页，出问题一眼就能看出来。
     */
    private fun selfCheckDeploy(pkgId: String) {
        lifecycleScope.launch {
            delay(3000)
            val file = WanxiangCustom.deployedArtifact(pkgId)
            val text = withContext(Dispatchers.IO) {
                if (file.isFile) runCatching { file.readText() }.getOrNull() else null
            }
            IdsPrefs.deployCheck = when {
                text == null ->
                    "没找到部署产物：${file.absolutePath}（部署可能没跑完）"
                text.contains("ids_reverse") ->
                    "已合并：${file.name} 里有 ids_reverse"
                else ->
                    "未合并：${file.name} 里没有 ids_reverse（部署失败或 patch 语法被拒）"
            }
            refreshScreen()
        }
    }

    private fun copyPatch() {
        val text = patchSnippet(idsConfigOrNull() ?: previewConfig())
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ids-reverse", text))
        requireContext().toast("已复制：贴到你方案 custom 文件的 patch: 下")
    }

    /** 数据没装时也要能看到「装上以后会写成什么样」。 */
    private fun previewConfig(): IdsConfig =
        IdsConfig(
            dataFile = IdsDataManager.dataFile().absolutePath,
            searchPrefix = IdsPrefs.searchPrefix,
            hybridPrefix = IdsPrefs.hybridPrefix,
            maxResults = IdsPrefs.maxResults,
            shuangpinScheme = resolvedShuangpinScheme(),
            showPinyin = IdsPrefs.showPinyin,
            showIds = IdsPrefs.showIds,
            showUnicode = IdsPrefs.showUnicode,
            showBlock = IdsPrefs.showBlock,
            idsFormat = IdsPrefs.idsFormat,
            variantTrigger = IdsPrefs.variantTrigger,
            variantReplaceKey = IdsPrefs.variantReplaceKey,
            disabledBlocks = IdsPrefs.disabledBlocks,
            searchTips = IdsPrefs.searchTips,
            hybridTips = IdsPrefs.hybridTips,
        )

    private fun patchSnippet(ids: IdsConfig): String {
        val sb = StringBuilder()
        sb.append("patch:\n")
        sb.append("  engine/processors/@before 0: ids_reverse_processor\n")
        sb.append("  engine/segmentors/@before 0: ids_reverse_segmentor\n")
        sb.append("  engine/translators/+: ids_reverse_translator\n")
        sb.append("  engine/filters/@before 0: ids_reverse_filter\n")
        sb.append("  ids_reverse:\n")
        sb.append("    data_file: \"${ids.dataFile}\"\n")
        sb.append("    search_prefix: \"${ids.searchPrefix}\"\n")
        sb.append("    hybrid_prefix: \"${ids.hybridPrefix}\"\n")
        sb.append("    max_results: ${ids.maxResults}\n")
        sb.append("    shuangpin_scheme: ${ids.shuangpinScheme}\n")
        sb.append("    show_pinyin: ${ids.showPinyin}\n")
        sb.append("    show_ids: ${ids.showIds}\n")
        sb.append("    show_unicode: ${ids.showUnicode}\n")
        sb.append("    show_block: ${ids.showBlock}\n")
        sb.append("    ids_format: ${ids.idsFormat}\n")
        sb.append("    variant_trigger: \"${ids.variantTrigger}\"\n")
        sb.append("    variant_replace_key: \"${ids.variantReplaceKey}\"\n")
        if (ids.disabledBlocks.isNotBlank()) sb.append("    disabled_blocks: \"${ids.disabledBlocks}\"\n")
        if (ids.searchTips.isNotBlank()) sb.append("    search_tips: \"${ids.searchTips}\"\n")
        if (ids.hybridTips.isNotBlank()) sb.append("    hybrid_tips: \"${ids.hybridTips}\"\n")
        return sb.toString()
    }

    // ---- 小控件 ----

    private fun textRow(
        key: String,
        title: String,
        value: String,
        hint: String,
        singleChar: Boolean = false,
        numeric: Boolean = false,
        allowEmpty: Boolean = false,
        onValue: (String) -> Unit,
    ): BambooClick =
        BambooClick(requireContext()).apply {
            this.key = key
            this.title = title
            summary = if (value.isBlank()) "$hint（当前：空）" else "$hint（当前：$value）"
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                val input =
                    EditText(requireContext()).apply {
                        setText(value)
                        setSelection(length())
                        inputType =
                            when {
                                numeric -> InputType.TYPE_CLASS_NUMBER
                                else -> InputType.TYPE_CLASS_TEXT
                            }
                        this.hint = hint
                    }
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setView(input)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("好") { _, _ ->
                        var raw = input.text?.toString()?.trim().orEmpty()
                        if (singleChar && raw.length > 1) raw = raw.take(1)
                        if (raw.isEmpty() && !allowEmpty) {
                            requireContext().toast("不能为空")
                            return@setPositiveButton
                        }
                        onValue(raw)
                        refreshScreen()
                    }
                    .show()
                true
            }
        }

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

    private fun refreshScreen() {
        val screen = preferenceScreen ?: return
        screen.removeAll()
        buildScreen(screen)
    }

    private companion object {
        val DOC_TEXT: String =
            """
            这 4 行是组件挂接（列表 patch 用锚点，不写行号）：
              engine/processors/@before 0: ids_reverse_processor
                —— 拦编码流：看到「前缀 + 部件」就交给反查，不让它进拼音
              engine/segmentors/@before 0: ids_reverse_segmentor
                —— 把「木口」切成「木」「口」两个部件
              engine/translators/+: ids_reverse_translator
                —— 出候选（+ 表示追加到 translator 列表末尾，不抢拼音的顺序）
              engine/filters/@before 0: ids_reverse_filter
                —— 过滤与排序（去重、按字频、限制条数）

            ids_reverse: 这一段是本插件的参数：
              data_file            数据文件绝对路径（必须先下载 29.0 MB 的 ids_data.bin）
              search_prefix        查找前缀，默认 I：打 I木口 = 找「木+口」的字
              hybrid_prefix        组合前缀，默认 O：拼音和部件混着打
              max_results          最多给几条候选（1–300，默认 60）
              shuangpin_scheme     双拼方案，决定候选上的读音怎么标：flypy / zrm / quanpin
              show_pinyin          候选后面标不标读音
              show_ids             显示部件结构（IDS）
              show_unicode         显示 U+6797 这种码位
              show_block           显示 Unicode 分区（CJK / 扩展 A…）
              ids_format           结构怎么画：unicode / bracket / plain
              variant_trigger      异体字触发符，默认 /：打 I木口/ 才列异体字
              variant_replace_key  用异体字替换当前候选的键，默认 Shift+V
              disabled_blocks      禁用某些分区，逗号分隔；留空不限
              search_tips / hybrid_tips  自定义提示文案，留空用默认

            你如果不是万象 Base / Pro：
              「应用并部署」不会替你写（会提示），但可以点「复制这段配置」，
              把内容贴到你正在用的方案自己的 <方案>.custom.yaml 的 patch: 下面，
              然后重新部署一次即可。路径 data_file 要改成你手机上真实路径。
            """.trimIndent()
    }
}
