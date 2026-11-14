/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.navigation.fragment.findNavController
import com.osfans.trime.R
import com.osfans.trime.actions.wanxiang.WanxiangCustom
import com.osfans.trime.actions.wanxiang.WanxiangPrefs
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.packaging.SchemaPackageManager
import com.osfans.trime.data.packaging.SchemaPackStore
import com.osfans.trime.ui.common.BambooClick
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.rebuildPreferencesKeepingScroll
import com.osfans.trime.util.addPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「万象拼音」设置页（P1：只管装包与切包）。
 *
 * 内置的万象拼音 Base 在安装完就会被静默释放到 `Documents/BambooIME[Debug]/wanxiang-base`，
 * 这里只负责把状态显示清楚，并提供「启用 / 删除」两个动作。
 *
 * 参数设置（双拼、模糊音、候选数、语法模型等）是 P2 的事，本页暂时不碰。
 */
class WanxiangFragment : PaddingPreferenceFragment() {

    private val rime = RimeDaemon.createSession(javaClass.name)

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        rebuildPreferencesKeepingScroll { buildScreen() }
    }

    override fun onDestroy() {
        RimeDaemon.destroySession(javaClass.name)
        super.onDestroy()
    }

    private fun buildScreen() {
        val installed = SchemaPackStore.isInstalled()
        val active = SchemaPackStore.isActive()
        val activeId = SchemaPackageManager.activePackageId
        preferenceScreen =
            preferenceManager.createPreferenceScreen(requireContext()).apply {
                // 顶部状态行：让用户随时知道「现在生效的是哪个包」，避免改了不生效还不知道
                addRow(
                    title = when {
                        !installed -> "尚未安装内置方案包"
                        active -> "当前生效：wanxiang-base（内置）"
                        activeId.isBlank() -> "当前没有活动方案包"
                        else -> "当前生效：$activeId（不是内置包）"
                    },
                    summary = when {
                        !installed ->
                            "安装包里已内置 67 MB，点下面的「启用这个方案包」释放并启用，约 1 分钟"
                        active ->
                            "${layoutLabel()} · ${encodeLabel()}，下面「方案设置」改的就是它"
                        else ->
                            "下面「方案设置」只会写进内置包，不会影响你现在用的这个包；" +
                                "想改内置包的设置，先点「启用这个方案包」切过去"
                    },
                )
                addBambooCategory(
                    "内置方案包",
                    "万象拼音 Base（全拼/双拼 + 九键）。装好后在后台静默释放，不需要你手动解压",
                ) {
                    addRow(
                        title = if (installed) "已就绪" else "尚未释放",
                        summary =
                            if (installed) {
                                "变体 ${SchemaPackStore.variant()}　占用 ${SchemaPackStore.sizeText()}" +
                                    "　${SchemaPackStore.fileCount()} 个文件\n释放于 ${SchemaPackStore.installedAtText()}"
                            } else {
                                "安装包内已内置，但还没拷到用户目录（可能被手工删过），点这里重新释放"
                            },
                    ) {
                        if (!installed) releaseThenRefresh()
                    }

                    addRow(
                        title = "启用这个方案包",
                        summary =
                            if (active) {
                                "当前已经在使用它了。要换别的包请到「方案包管理」里选。"
                            } else {
                                "会切到这个包并立即部署一次。词库较大，首次约 2–4 分钟，" +
                                    "期间键盘可能短暂不可用。"
                            },
                    ) {
                        if (active) {
                            toast("已经在使用它了")
                        } else {
                            confirmEnable()
                        }
                    }

                    addRow(
                        title = "检查并补齐文件",
                        summary = "升级 APK 后如果这个包没有被自动刷新，点它会重新释放一遍（不动你改过的 *.custom.yaml）",
                    ) {
                        releaseThenRefresh()
                    }

                    addRow(
                        title = "打开文件夹",
                        summary = SchemaPackStore.dir().absolutePath,
                    ) {
                        showDirDialog()
                    }

                    addRow(
                        title = "删除已释放的文件",
                        summary = "只删 Documents 下这份 `wanxiang-base`，保留其它方案包",
                    ) {
                        confirmDelete()
                    }
                }

                addBambooCategory("方案设置", "这些值会写进 custom，点「应用并部署」后生效") {
                    addRow(
                        title = "设置向导",
                        summary = "三步选完：键盘 → 全拼/双拼 → 拼音方案；选完自动切方案并部署",
                    ) {
                        runCatching {
                            findNavController().navigate(
                                com.osfans.trime.ui.main.NavigationRoute.WanxiangSetup,
                            )
                        }.onFailure { toast("打不开向导：${it.message}") }
                    }

                    settingRow(
                        title = "键盘形态",
                        summary = layoutLabel() + "　（改这个不会切换键盘布局，键盘请在键盘弹窗里选）",
                    ) {
                        pickInt(
                            "键盘形态",
                            listOf(
                                WanxiangPrefs.LAYOUT_26 to "全键盘（26 键）",
                                WanxiangPrefs.LAYOUT_18 to "18 键",
                                WanxiangPrefs.LAYOUT_14 to "14 键（用 26 键键盘模拟，有重复按键）",
                                WanxiangPrefs.LAYOUT_9 to "九宫格（9 键，固定全拼）",
                            ),
                            WanxiangPrefs.layout,
                        ) { WanxiangPrefs.layout = it; refresh() }
                    }

                    settingRow(
                        title = "编码方式",
                        summary = if (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) {
                            "九键固定全拼"
                        } else {
                            if (WanxiangPrefs.encode == WanxiangPrefs.ENCODE_QUANPIN) "全拼" else "双拼"
                        },
                    ) {
                        if (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) {
                            toast("九键固定全拼，切回全键盘后可改")
                        } else {
                            pickString(
                                "编码方式",
                                listOf(
                                    WanxiangPrefs.ENCODE_QUANPIN to "全拼",
                                    WanxiangPrefs.ENCODE_SHUANGPIN to "双拼",
                                ),
                                WanxiangPrefs.encode,
                            ) { WanxiangPrefs.encode = it; refresh() }
                        }
                    }

                    settingRow(
                        title = "双拼方案",
                        summary = WanxiangPrefs.scheme + "（只在双拼下生效）",
                    ) {
                        pickString(
                            "双拼方案",
                            WanxiangPrefs.SCHEMES.map { it to it },
                            WanxiangPrefs.scheme,
                        ) {
                            WanxiangPrefs.scheme = it
                            WanxiangPrefs.encode = WanxiangPrefs.ENCODE_SHUANGPIN
                            refresh()
                        }
                    }

                    settingRow(
                        title = "辅助码",
                        summary = if (SchemaPackStore.variant() == SchemaPackStore.VARIANT_BASE) {
                            "内置 Base 词库不含辅助码，装上 Pro 变体后才能开"
                        } else if (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) {
                            "九键没有字母键，辅助码用不上（切回全键盘后可开）"
                        } else {
                            when (WanxiangPrefs.aux) {
                                WanxiangPrefs.AUX_DIRECT -> "直接辅助（默认关闭，已开启）"
                                WanxiangPrefs.AUX_INDIRECT -> "间接辅助（默认关闭，已开启）"
                                else -> "关闭（官方默认）"
                            }
                        },
                    ) {
                        if (SchemaPackStore.variant() == SchemaPackStore.VARIANT_BASE) {
                            toast("内置 Base 词库不含辅助码；装上 Pro 变体后再来开")
                            return@settingRow
                        }
                        if (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) {
                            toast("九键下辅助码无效，切回全键盘再开")
                            return@settingRow
                        }
                        pickInt(
                            "辅助码",
                            listOf(
                                WanxiangPrefs.AUX_NONE to "关闭（推荐，官方默认）",
                                WanxiangPrefs.AUX_DIRECT to "直接辅助",
                                WanxiangPrefs.AUX_INDIRECT to "间接辅助",
                            ),
                            WanxiangPrefs.aux,
                        ) { WanxiangPrefs.aux = it; refresh() }
                    }

                    settingRow(
                        title = "模糊音",
                        summary = if (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) {
                            "九键不跟随模糊音（切回全键盘后可设）"
                        } else if (WanxiangPrefs.fuzzy.isEmpty()) {
                            "全部关闭（官方默认）"
                        } else {
                            WanxiangPrefs.fuzzy.sorted().joinToString("、") { WanxiangPrefs.FUZZY_LABELS[it] ?: it }
                        },
                    ) {
                        if (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) {
                            toast("九键不跟随模糊音，切回全键盘再设")
                            return@settingRow
                        }
                        pickFuzzy()
                    }

                    settingRow(
                        title = "候选个数",
                        summary = "${WanxiangPrefs.pageSize} 个（官方默认 6，最大 6）",
                    ) {
                        pickInt(
                            "候选个数",
                            (3..6).map { it to "$it 个" },
                            WanxiangPrefs.pageSize,
                        ) { WanxiangPrefs.pageSize = it; refresh() }
                    }

                    settingRow(
                        title = "用户词典（自动调频）",
                        summary = if (WanxiangPrefs.userDict) "已开启" else "关闭（官方默认）",
                    ) {
                        pickBool("用户词典（自动调频）", WanxiangPrefs.userDict) {
                            WanxiangPrefs.userDict = it; refresh()
                        }
                    }

                    settingRow(
                        title = "智能调频（上下文预测）",
                        summary = if (!WanxiangPrefs.userDict) {
                            "依赖用户词典，先打开「用户词典」再设"
                        } else if (WanxiangPrefs.contextual) {
                            "已开启"
                        } else {
                            "关闭（官方默认，官方注释写着效果一般）"
                        },
                    ) {
                        if (!WanxiangPrefs.userDict) {
                            toast("先打开「用户词典」")
                            return@settingRow
                        }
                        pickBool("智能调频（上下文预测）", WanxiangPrefs.contextual) {
                            WanxiangPrefs.contextual = it; refresh()
                        }
                    }

                    settingRow(
                        title = "九键候选个数",
                        summary = "${WanxiangPrefs.t9PageSize} 个",
                    ) {
                        pickInt(
                            "九键候选个数",
                            (3..6).map { it to "$it 个" },
                            WanxiangPrefs.t9PageSize,
                        ) { WanxiangPrefs.t9PageSize = it; refresh() }
                    }

                    settingRow(
                        title = "九键用户词典（自动调频）",
                        summary = if (WanxiangPrefs.t9UserDict) "开启（官方默认）" else "关闭",
                    ) {
                        pickBool("九键用户词典（自动调频）", WanxiangPrefs.t9UserDict) {
                            WanxiangPrefs.t9UserDict = it; refresh()
                        }
                    }

                    settingRow(
                        title = "应用并部署",
                        summary = if (WanxiangPrefs.hasPendingChanges()) {
                            "有未应用的改动 —— 点这里写盘并重新部署（约 1–4 分钟）"
                        } else {
                            "当前配置已应用"
                        },
                    ) {
                        applyAndDeploy()
                    }

                    settingRow(
                        title = "恢复官方默认",
                        summary = "把上面这些改回官方默认值（辅助码关闭、调频关闭、候选 6、模糊音全关），然后应用",
                    ) {
                        confirmRestoreDefaults()
                    }
                }

                addBambooCategory("说明", "装之前先知道这几件事") {
                    addRow(
                        title = "体积与耗时",
                        summary = "包内 67.3 MB；部署后 `build/` 约 70–90 MB；首次部署 1–2 分钟。" +
                            "九键（T9）需要更大的 word 表，首次会再久一点。",
                    )

                    addRow(
                        title = "都带了什么",
                        summary = "官方 Base：26 键（全拼或任意双拼）、九键、英文、混输、反查、自定义短语；" +
                            "九键用官方方案，不额外改动。要辅助码请装 Pro 变体（后续版本提供下载）。",
                    )

                    addRow(
                        title = "许可与署名",
                        summary = "词库与配置来自 amzxyz/rime-wanxiang（CC-BY-4.0）。" +
                            "九键用的是官方 `wanxiang_t9` 方案，未改动词库。",
                    ) {
                        runCatching {
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/amzxyz/rime-wanxiang"),
                                ),
                            )
                        }
                    }
                }
            }
    }

    // ---------------------------------------------------------------- 动作

    private fun confirmEnable() {
        AlertDialog.Builder(requireContext())
            .setTitle("启用万象拼音")
            .setMessage(
                "将切到 `wanxiang-base` 并立即部署。\n\n" +
                    "· 词库较大，首次部署约 2–4 分钟，期间键盘可能短暂不可用\n" +
                    "· 部署完成前请不要反复切换方案",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("启用") { _, _ -> enable() }
            .show()
    }

    private fun enable() {
        val context = requireContext()
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) { SchemaPackStore.ensureCopied(context) }
            if (!ready) {
                toast("释放失败：检查 Documents 目录是否可写")
                return@launch
            }
            val wrote = withContext(Dispatchers.IO) { WanxiangCustom.writeAll() }
            if (!wrote) {
                toast("写入配置失败：检查 Documents 目录是否可写")
                return@launch
            }
            toast("正在部署，请稍候…")
            deployToOurPackage()
            rebuildPreferencesKeepingScroll { buildScreen() }
        }
    }

    private fun releaseThenRefresh() {
        val context = requireContext()
        lifecycleScope.launch {
            toast("正在释放内置包…")
            val ok = withContext(Dispatchers.IO) { SchemaPackStore.ensureCopied(context) }
            toast(if (ok) "已就绪" else "释放失败：检查 Documents 目录是否可写")
            rebuildPreferencesKeepingScroll { buildScreen() }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle("删除已释放的文件")
            .setMessage(
                "只会删除 `wanxiang-base` 这个目录（${SchemaPackStore.sizeText()}）。\n\n" +
                    "如果当前正在使用它，删掉后键盘会没有可用方案，请先切到别的包。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val ok = SchemaPackStore.deleteFiles()
                toast(if (ok) "已删除" else "删除失败，可能正在被占用")
                rebuildPreferencesKeepingScroll { buildScreen() }
            }
            .show()
    }

    private fun showDirDialog() {
        val dir: File = SchemaPackStore.dir()
        if (!dir.exists()) dir.mkdirs()
        AlertDialog.Builder(requireContext())
            .setTitle("方案包目录")
            .setMessage(dir.absolutePath)
            .setNeutralButton("复制路径") { _, _ ->
                runCatching {
                    val manager = requireContext().getSystemService(ClipboardManager::class.java)
                    manager?.setPrimaryClip(ClipData.newPlainText("path", dir.absolutePath))
                }
                toast("路径已复制")
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("打开文件夹") { _, _ ->
                runCatching {
                    val uri =
                        FileProvider.getUriForFile(
                            requireContext(),
                            requireContext().packageName + ".fileprovider",
                            dir,
                        )
                    startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "resource/folder")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                }.onFailure { toast("打不开文件夹：${it.message}") }
            }
            .show()
    }

    // ---------------------------------------------------------------- 方案设置

    /**
     * 改设置前的守卫：确保「内置包存在」且「它就是当前活动的包」。
     *
     * 不做这个检查的后果：用户自己的包是活动包时，设置会写进内置包的 custom，
     * 屏幕上什么都不会变，用户只会觉得「改了没用」。
     */
    private fun ensureWritable(): Boolean {
        if (!SchemaPackStore.isInstalled()) {
            toast("内置方案包还没释放，正在准备…")
            releaseThenRefresh()
            return false
        }
        if (!SchemaPackStore.isActive()) {
            val current = SchemaPackageManager.activePackageId.ifBlank { "未选择" }
            AlertDialog.Builder(requireContext())
                .setTitle("先切到内置方案包")
                .setMessage(
                    "这些设置会写进内置包（`wanxiang-base`）的 custom，" +
                        "但你现在用的是 $current，改了不会生效。\n\n要切过去吗？",
                )
                .setNegativeButton("取消", null)
                .setPositiveButton("切过去并部署") { _, _ ->
                    lifecycleScope.launch {
                        val ready =
                            withContext(Dispatchers.IO) { SchemaPackStore.ensureCopied(requireContext()) }
                        if (!ready) {
                            toast("释放失败：检查 Documents 目录是否可写")
                            return@launch
                        }
                        toast("正在部署，请稍候…")
                        deployToOurPackage()
                        rebuildPreferencesKeepingScroll { buildScreen() }
                    }
                }
                .show()
            return false
        }
        return true
    }

    /** 「方案设置」里的行统一走这里：先过守卫，再执行。 */
    private fun settingRow(
        title: String,
        summary: String,
        onClick: () -> Unit,
    ) {
        preferenceScreen.addRow(title = title, summary = summary) {
            if (ensureWritable()) onClick()
        }
    }

    private fun encodeLabel(): String =
        if (WanxiangPrefs.encode == WanxiangPrefs.ENCODE_QUANPIN) {
            "全拼"
        } else {
            "双拼 · ${WanxiangPrefs.scheme}"
        }

    private fun layoutLabel(): String = when (WanxiangPrefs.layout) {
        WanxiangPrefs.LAYOUT_18 -> "18 键"
        WanxiangPrefs.LAYOUT_14 -> "14 键（用 26 键模拟）"
        WanxiangPrefs.LAYOUT_9 -> "九宫格（9 键）"
        else -> "全键盘（26 键）"
    }

    private fun refresh() {
        // 每次改动后顺手把互斥项收干净（九键 → 全拼/无辅码/无模糊音；关词典 → 关调频）
        WanxiangPrefs.sanitize()
        rebuildPreferencesKeepingScroll { buildScreen() }
    }

    private fun pickInt(
        title: String,
        items: List<Pair<Int, String>>,
        current: Int,
        onPick: (Int) -> Unit,
    ) {
        val labels = items.map { it.second }.toTypedArray()
        val checked = items.indexOfFirst { it.first == current }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onPick(items[which].first)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickString(
        title: String,
        items: List<Pair<String, String>>,
        current: String,
        onPick: (String) -> Unit,
    ) {
        val labels = items.map { it.second }.toTypedArray()
        val checked = items.indexOfFirst { it.first == current }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onPick(items[which].first)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickBool(
        title: String,
        current: Boolean,
        onPick: (Boolean) -> Unit,
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(
                arrayOf("开启", "关闭"),
                if (current) 0 else 1,
            ) { dialog, which ->
                onPick(which == 0)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickFuzzy() {
        val labels =
            WanxiangPrefs.FUZZY_KEYS.map { WanxiangPrefs.FUZZY_LABELS[it] ?: it }.toTypedArray()
        val checked = WanxiangPrefs.FUZZY_KEYS.map { WanxiangPrefs.fuzzy.contains(it) }.toBooleanArray()
        AlertDialog.Builder(requireContext())
            .setTitle("模糊音")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val key = WanxiangPrefs.FUZZY_KEYS[which]
                val next = WanxiangPrefs.fuzzy.toMutableSet()
                if (isChecked) next.add(key) else next.remove(key)
                WanxiangPrefs.fuzzy = next
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("好") { _, _ -> refresh() }
            .show()
    }

    private fun applyAndDeploy() {
        lifecycleScope.launch {
            toast("正在写入配置…")
            val ok = withContext(Dispatchers.IO) { WanxiangCustom.writeAll() }
            if (!ok) {
                toast("写入失败：检查 Documents 目录是否可写")
                return@launch
            }
            toast("正在部署，请稍候…")
            deployToOurPackage()
            refresh()
        }
    }

    /**
     * 切到**我们这个包**并重新部署。
     *
     * 注意：这里不能用 `selectSchemaCrossPackage("wanxiang_pro")` —— 那个 API 是按方案 id
     * 反查包的，用户自己的包里也有同名 `wanxiang_pro` 时会被切到人家的包（实测踩过）。
     * 所以一律按包 id 激活，部署则由 `restartRime(fullCheck = true)` 完成（它会读
     * `default.custom.yaml` 里的 schema_list，我们刚刚只写了一个方案）。
     */
    private suspend fun deployToOurPackage() {
        SchemaPackageManager.setActivePackageId(SchemaPackStore.PACKAGE_ID)
        runCatching { RimeDaemon.restartRime(fullCheck = true) }
            .onFailure { toast("部署失败：${it.message ?: it.javaClass.simpleName}") }
    }

    private fun confirmRestoreDefaults() {
        AlertDialog.Builder(requireContext())
            .setTitle("恢复官方默认")
            .setMessage(
                "会把方案设置改回官方默认：辅助码关闭、用户词典与智能调频关闭、" +
                    "候选 6 个、模糊音全部关闭、26 键全拼。\n\n" +
                    "改完会立刻写盘并重新部署一次。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                WanxiangPrefs.resetToDefaults()
                applyAndDeploy()
            }
            .show()
    }

    private fun toast(text: String) {
        if (isAdded) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
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

    private fun PreferenceGroup.addRow(
        title: String,
        summary: String,
        onClick: (() -> Unit)? = null,
    ) {
        addPreference(
            BambooClick(context).apply {
                this.title = title
                this.summary = summary
                isIconSpaceReserved = false
                isPersistent = false
                // 纯说明行（onClick == null）绑一个永远返回 false 的监听：
                // 点击有按下反馈但什么也不发生，比留空更像设置项。
                setOnPreferenceClickListener {
                    onClick?.invoke()
                    false
                }
            },
        )
    }

}
