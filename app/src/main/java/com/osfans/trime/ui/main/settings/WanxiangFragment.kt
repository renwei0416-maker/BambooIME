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
        val activeId = SchemaPackageManager.activePackageId
        val ourId = WanxiangCustom.currentPackage()
        val installed = ourId != null && SchemaPackStore.isInstalled(ourId)
        preferenceScreen =
            preferenceManager.createPreferenceScreen(requireContext()).apply {
                val scope = WanxiangCustom.currentScope()
                val editable = WanxiangCustom.scopeEditable()
                addRow(
                    title = when (scope) {
                        WanxiangCustom.Scope.BASE_26,
                        WanxiangCustom.Scope.BASE_9,
                        WanxiangCustom.Scope.PRO_26,
                        -> "当前生效：${scopeLabel(scope)}"
                        WanxiangCustom.Scope.FOREIGN -> "当前生效：不是内置方案"
                        WanxiangCustom.Scope.NONE -> "还没配置"
                    },
                    summary = when (scope) {
                        WanxiangCustom.Scope.BASE_26,
                        WanxiangCustom.Scope.BASE_9,
                        WanxiangCustom.Scope.PRO_26,
                        -> "${layoutLabel()} · ${encodeLabel()} · 改的是 ${WanxiangCustom.scopeTarget()} 那份配置"
                        else -> scopeBlockedReason(scope)
                    },
                )
                addBambooCategory("方案设置", "改完点「应用并部署」生效") {
                    addAccentRow(
                        title = "设置向导",
                        summary = "键盘 → 编码 → 方案，选完自动应用",
                    ) {
                        runCatching {
                            findNavController().navigate(
                                com.osfans.trime.ui.main.NavigationRoute.WanxiangSetup,
                            )
                        }.onFailure { toast("打不开向导：${it.message}") }
                    }

                    addRow(
                        title = "方案包与下载",
                        summary = downloadSummary(),
                    ) {
                        runCatching {
                            findNavController().navigate(
                                com.osfans.trime.ui.main.NavigationRoute.WanxiangDownload,
                            )
                        }.onFailure { toast("打不开下载页：${it.message}") }
                    }

                    settingRow(
                        title = "候选个数",
                        summary = if (!editable) {
                            scopeBlockedReason(scope)
                        } else {
                            "${WanxiangPrefs.pageSize} 个（最大 6）"
                        },
                        enabled = editable,
                    ) {
                        pickInt(
                            "候选个数",
                            (3..6).map { it to "$it 个" },
                            WanxiangPrefs.pageSize,
                        ) { WanxiangPrefs.pageSize = it; refresh() }
                    }

                    settingRow(
                        title = "模糊音",
                        summary = if (!editable) {
                            scopeBlockedReason(scope)
                        } else if (WanxiangPrefs.fuzzy.isEmpty()) {
                            "关闭"
                        } else {
                            WanxiangPrefs.fuzzy.sorted().joinToString("、") { WanxiangPrefs.FUZZY_LABELS[it] ?: it }
                        },
                        enabled = editable,
                    ) {
                        pickFuzzy()
                    }

                    settingRow(
                        title = "是否调频",
                        summary = if (!editable) {
                            scopeBlockedReason(scope)
                        } else if (WanxiangPrefs.contextual) {
                            "开启（按上文预测，越用越准）"
                        } else {
                            "关闭"
                        },
                        enabled = editable,
                    ) {
                        pickBool("是否调频", WanxiangPrefs.contextual) {
                            WanxiangPrefs.contextual = it
                            refresh()
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
                        summary = "只把候选个数（6）、模糊音（全关）、是否调频（关）恢复成官方默认，然后应用；" +
                            "编码方式、键盘形态、辅助码不动（那些在「设置向导」里）",
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

    private fun enable() {
        val context = requireContext()
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) { SchemaPackStore.ensureBaseCopied(context) }
            if (!ready) {
                toast("释放失败：检查 Documents 目录是否可写")
                return@launch
            }
            val target = WanxiangCustom.currentPackage()
            if (target == null) {
                goWizard()
                return@launch
            }
            val wrote = withContext(Dispatchers.IO) { WanxiangCustom.writeAll(target) }
            if (!wrote) {
                toast("写入配置失败：检查 Documents 目录是否可写")
                return@launch
            }
            toast("正在部署，请稍候…")
            deployToOurPackage()
            rebuildPreferencesKeepingScroll { buildScreen() }
        }
    }

    private fun confirmDelete() {
        val ourId = WanxiangCustom.activePackage()
        AlertDialog.Builder(requireContext())
            .setTitle("删除已释放的文件")
            .setMessage(
                "只会删除 `$ourId` 这个目录（${SchemaPackStore.sizeText(ourId)}）。\n\n" +
                    "如果当前正在使用它，删掉后键盘会没有可用方案，请先切到别的包。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val ok = SchemaPackStore.deleteFiles(ourId)
                toast(if (ok) "已删除" else "删除失败，可能正在被占用")
                rebuildPreferencesKeepingScroll { buildScreen() }
            }
            .show()
    }

    private fun showDirDialog() {
        val dir: File = SchemaPackStore.dir(WanxiangCustom.activePackage())
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
    /**
     * 能不能改：两个包一个都没装才拦人。
     *
     * 这里不判断「当前活动包」—— 落盘目标是 `WanxiangCustom.targetPackage()`，
     * 由辅助码决定；「应用并部署」时再按目标解包 / 切包。
     */
    private fun ensureWritable(): Boolean {
        val anyInstalled = SchemaPackStore.isInstalled(SchemaPackStore.BASE_ID) ||
            SchemaPackStore.isInstalled(SchemaPackStore.PRO_ID)
        if (!anyInstalled) {
            goWizard()
            return false
        }
        return true
    }

    /** 没配好就引导去向导；这里绝不解包、不切包。 */
    private fun goWizard() {
        AlertDialog.Builder(requireContext())
            .setTitle("先配好万象拼音")
            .setMessage("这些设置要先走向导配一次，配好之后回来就能改。")
            .setNegativeButton("取消", null)
            .setPositiveButton("去设置向导") { _, _ ->
                runCatching {
                    findNavController().navigate(
                        com.osfans.trime.ui.main.NavigationRoute.WanxiangSetup,
                    )
                }.onFailure { toast("打不开向导：${it.message}") }
            }
            .show()
    }

    /**
     * 「方案设置」里的行统一走这里：先过守卫，再执行。
     *
     * 必须写成 PreferenceGroup 的扩展函数：buildScreen() 是在
     * createPreferenceScreen(...).apply { ... } 里跑的，此时 preferenceScreen 还没赋值，
     * 用 preferenceScreen.addRow() 会拿到 null 直接崩（踩过）。
     */
    private fun PreferenceGroup.settingRow(
        title: String,
        summary: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        addRow(title = title, summary = summary) {
            if (enabled && ensureWritable()) onClick()
        }.apply { isEnabled = enabled }
    }

    private fun scopeLabel(scope: WanxiangCustom.Scope): String = when (scope) {
        WanxiangCustom.Scope.BASE_26 -> "万象 Base（全键盘）"
        WanxiangCustom.Scope.BASE_9 -> "万象 Base（九键）"
        WanxiangCustom.Scope.PRO_26 -> "万象 Pro"
        else -> "—"
    }

    private fun scopeBlockedReason(scope: WanxiangCustom.Scope): String = when (scope) {
        WanxiangCustom.Scope.BASE_9 -> "九键方案没有独立的配置文件，这三项在这里改不了"
        WanxiangCustom.Scope.FOREIGN ->
            "您使用的不是内置的 Base 方案以及万象 Pro 方案。不对您自己的方案提供修改。"
        WanxiangCustom.Scope.NONE -> "请先在「设置向导」里配置你的输入方案"
        else -> "当前方案不支持"
    }

    private fun downloadSummary(): String {
        val pkg = WanxiangCustom.activePackage()
        val v = SchemaPackStore.variantOf(pkg)
        val title = com.osfans.trime.actions.wanxiang.WanxiangVariants.installedNote(v)
        return when {
            !SchemaPackStore.isInstalled(pkg) -> "下载辅助码词库、语法模型"
            SchemaPackStore.isOutdated(pkg) -> "当前：$title · 有新版本可更新"
            else -> "当前：$title"
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
        WanxiangPrefs.LAYOUT_14 -> "14 键"
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
        val scoped = WanxiangCustom.scopeTarget()
        if (scoped == null) {
            toast(scopeBlockedReason(WanxiangCustom.currentScope()))
            return
        }
        lifecycleScope.launch {
            val target = scoped

            // 目标是 Base 而没解包：直接解（67 MB，几秒），解完再配。
            if (target == SchemaPackStore.BASE_ID && !SchemaPackStore.isInstalled(SchemaPackStore.BASE_ID)) {
                toast("正在释放内置方案包…")
                val copied = withContext(Dispatchers.IO) { SchemaPackStore.ensureBaseCopied(requireContext()) }
                if (!copied) {
                    toast("释放内置包失败：检查 Documents 目录是否可写")
                    return@launch
                }
            }

            // 目标是 Pro 而没装：不能替用户下载，提示去下载。
            if (target == SchemaPackStore.PRO_ID && !SchemaPackStore.isInstalled(SchemaPackStore.PRO_ID)) {
                toast("还没装 Pro 词库：先去「方案包与下载」装一个带辅助码的")
                return@launch
            }

            toast("正在写入配置…")
            val ok = withContext(Dispatchers.IO) {
                WanxiangCustom.applySettings(
                    pkgId = target,
                    fuzzy = WanxiangPrefs.fuzzy,
                    pageSize = WanxiangPrefs.pageSize,
                    contextual = WanxiangPrefs.contextual,
                )
            }
            if (!ok) {
                toast("写入失败：检查 Documents 目录是否可写")
                return@launch
            }
            toast("正在部署，请稍候…")
            deployToOurPackage(target, fullCheck = false)
            refresh()
        }
    }

    /**
     * 切到**我们这个包**并重新部署。
     *
     * 注意：这里不能用 `selectSchemaCrossPackage("wanxiang_pro")` —— 那个 API 是按方案 id
     * 反查包的，用户自己的包里也有同名 `wanxiang_pro` 时会被切到人家的包（实测踩过）。
     * 所以一律按包 id 激活，部署则由 `restartRime` 完成（它会读 `default.custom.yaml`
     * 里的 schema_list，我们刚刚只写了一个方案）。
     *
     * [fullCheck] 只在**装包/换包/更新包**时用 true（无条件重编词库，2–4 分钟）；
     * 应用设置只需要重新生成方案配置，走 false 的增量比对（只重建改动项，几十秒）。
     */
    private suspend fun deployToOurPackage(
        pkgId: String = WanxiangCustom.targetPackage(),
        fullCheck: Boolean = true,
    ) {
        SchemaPackageManager.setActivePackageId(pkgId)
        runCatching { RimeDaemon.restartRime(fullCheck = fullCheck) }
            .onFailure { toast("部署失败：${it.message ?: it.javaClass.simpleName}") }
    }

    private fun confirmRestoreDefaults() {
        AlertDialog.Builder(requireContext())
            .setTitle("恢复官方默认")
            .setMessage(
                "只恢复这三项：\n\n" +
                    "· 候选个数 → 6\n" +
                    "· 模糊音 → 全部关闭\n" +
                    "· 是否调频 → 关闭\n\n" +
                    "编码方式、键盘形态、辅助码不受影响（那些在「设置向导」里改）。\n" +
                    "改完会立刻写盘并重新部署一次。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                WanxiangPrefs.resetScopeDefaults()
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

    private fun PreferenceGroup.addAccentRow(
        title: String,
        summary: String,
        onClick: () -> Unit,
    ) {
        addPreference(
            com.osfans.trime.util.AccentBarRow(context).apply {
                setTitle(title)
                setSummary(summary)
                isIconSpaceReserved = false
                isPersistent = false
                setOnPreferenceClickListener {
                    onClick()
                    false
                }
            },
        )
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
