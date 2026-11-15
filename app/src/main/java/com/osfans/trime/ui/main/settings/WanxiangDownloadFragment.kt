/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.actions.wanxiang.WanxiangCustom
import com.osfans.trime.actions.wanxiang.WanxiangInstaller
import com.osfans.trime.actions.wanxiang.WanxiangPrefs
import com.osfans.trime.actions.wanxiang.WanxiangVariants
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.packaging.SchemaPackageManager
import com.osfans.trime.data.packaging.SchemaPackStore
import com.osfans.trime.ui.common.BambooClick
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.rebuildPreferencesKeepingScroll
import com.osfans.trime.util.addPreference
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 方案包与语法模型的下载页。
 *
 * 一行一个包：当前在用的显示「使用中」，其余显示「下载并切换」。点一行就下载整包、
 * 覆盖同名文件（跳过 `custom/`），然后重写配置、切到这个包、部署一次。
 */
class WanxiangDownloadFragment : PaddingPreferenceFragment() {

    private val rime = RimeDaemon.createSession(javaClass.name)
    private var busy = false

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
        val baseInstalled = SchemaPackStore.isInstalled(SchemaPackStore.BASE_ID)
        val proInstalled = SchemaPackStore.isInstalled(SchemaPackStore.PRO_ID)
        val activeId = SchemaPackageManager.activePackageId
        val proVariant = SchemaPackStore.variantOf(SchemaPackStore.PRO_ID)
        val proTitle = WanxiangVariants.byId(proVariant)?.title ?: "已装"

        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addBambooCategory("当前方案包", statusNote(activeId, baseInstalled, proInstalled)) {
                val baseOutdated = SchemaPackStore.isOutdated(SchemaPackStore.BASE_ID)
                addRow(
                    title =
                        when {
                            activeId == SchemaPackStore.BASE_ID -> "Base（内置）· 使用中"
                            else -> "Base（内置）"
                        },
                    summary =
                        when {
                            !baseInstalled -> "还没释放；走进设置向导第 1 步才会拷到本地"
                            baseOutdated -> "${SchemaPackStore.sizeText(SchemaPackStore.BASE_ID)} · 有新版本，点击更新"
                            else -> "${SchemaPackStore.sizeText(SchemaPackStore.BASE_ID)} · 已是最新"
                        },
                ) {
                    when {
                        !baseInstalled -> goWizard()
                        baseOutdated -> updateBase()
                        activeId == SchemaPackStore.BASE_ID -> toast("现在用的就是 Base")
                        else -> switchTo(SchemaPackStore.BASE_ID)
                    }
                }
                if (proInstalled) {
                    val proOutdated = SchemaPackStore.isOutdated(SchemaPackStore.PRO_ID)
                    addRow(
                        title =
                            when {
                                activeId == SchemaPackStore.PRO_ID -> "Pro · $proTitle · 使用中"
                                proOutdated -> "Pro · $proTitle · 有新版本"
                                else -> "Pro · $proTitle"
                            },
                        summary =
                            when {
                                proOutdated -> "${SchemaPackStore.sizeText(SchemaPackStore.PRO_ID)} · 点击重新下载当前变体"
                                else -> "${SchemaPackStore.sizeText(SchemaPackStore.PRO_ID)} · 带辅助码词库，没有九键"
                            },
                    ) {
                        when {
                            proOutdated -> {
                                val v = WanxiangVariants.byId(SchemaPackStore.variantOf(SchemaPackStore.PRO_ID))
                                if (v != null) confirmInstall(v) else switchTo(SchemaPackStore.PRO_ID)
                            }
                            activeId == SchemaPackStore.PRO_ID -> toast("现在用的就是 Pro")
                            else -> switchTo(SchemaPackStore.PRO_ID)
                        }
                    }
                }
            }
            addBambooCategory("Pro 词库（带辅助码）", "装进 wanxiang-pro；换变体只覆盖同名文件，不删别的") {
                WanxiangVariants.list.filter { it.hasAux }.forEach { v ->
                    val here = proInstalled && v.id == proVariant
                    val outdated = here && SchemaPackStore.isOutdated(SchemaPackStore.PRO_ID)
                    addRow(
                        title =
                            when {
                                outdated -> "${v.title}（可更新）"
                                here -> "${v.title}（使用中）"
                                else -> v.title
                            },
                        summary = "${WanxiangVariants.sizeText(v.bytes)} · ${v.note}",
                    ) {
                        if (here && !outdated) {
                            toast("这个变体正在用")
                        } else {
                            confirmInstall(v)
                        }
                    }
                }
            }
            addBambooCategory("语法模型", "可选。有它长句更顺，没有也能正常打字") {
                addRow(title = gramTitle(), summary = gramSummary()) { confirmGram() }
            }
        }
    }

    private fun statusNote(activeId: String, baseInstalled: Boolean, proInstalled: Boolean): String =
        when {
            activeId == SchemaPackStore.BASE_ID -> "当前生效：Base（内置）"
            activeId == SchemaPackStore.PRO_ID -> "当前生效：Pro（下载）"
            !baseInstalled && !proInstalled -> "两个包都还没装"
            else -> "当前生效的是别的方案包"
        }

    private fun goWizard() {
        AlertDialog.Builder(requireContext())
            .setTitle("先配好万象拼音")
            .setMessage("Base 还没释放。走向导配一次，之后这里就能直接切。")
            .setNegativeButton("取消", null)
            .setPositiveButton("去设置向导") { _, _ ->
                runCatching {
                    androidx.navigation.fragment.NavHostFragment.findNavController(this)
                        .navigate(com.osfans.trime.ui.main.NavigationRoute.WanxiangSetup)
                }.onFailure { toast("打不开向导：${it.message}") }
            }
            .show()
    }

    /** 更新内置 Base：强制重拷 assets（跳过 custom/），在用时顺手部署一次。 */
    private fun updateBase() {
        if (busy) {
            toast("正在处理上一个任务")
            return
        }
        busy = true
        lifecycleScope.launch {
            toast("正在更新 Base…")
            val ok = withContext(Dispatchers.IO) { SchemaPackStore.ensureBaseCopied(requireContext(), force = true) }
            if (!ok) {
                busy = false
                toast("更新失败：内置包不可用")
                return@launch
            }
            if (SchemaPackageManager.activePackageId == SchemaPackStore.BASE_ID) {
                withContext(Dispatchers.IO) {
                    runCatching { RimeDaemon.restartRime(fullCheck = true) }
                        .onFailure { Timber.w(it, "wanxiang: deploy failed") }
                }
                toast("Base 已更新并重新部署")
            } else {
                toast("Base 已更新；切到 Base 时生效")
            }
            busy = false
            rebuildPreferencesKeepingScroll { buildScreen() }
        }
    }

    /** 切到某个包：写配置 → 设活动包 → 部署。 */
    private fun switchTo(pkgId: String) {
        if (busy) {
            toast("正在处理上一个任务")
            return
        }
        busy = true
        lifecycleScope.launch {
            if (pkgId == SchemaPackStore.BASE_ID && !SchemaPackStore.isInstalled(SchemaPackStore.BASE_ID)) {
                busy = false
                goWizard()
                return@launch
            }
            val wrote = withContext(Dispatchers.IO) { WanxiangCustom.writeAll(pkgId) }
            if (!wrote) {
                busy = false
                toast("写入配置失败")
                return@launch
            }
            SchemaPackageManager.setActivePackageId(pkgId)
            toast("已切换，正在部署…")
            withContext(Dispatchers.IO) {
                runCatching { RimeDaemon.restartRime(fullCheck = true) }
                    .onFailure { Timber.w(it, "wanxiang: deploy failed") }
            }
            busy = false
            rebuildPreferencesKeepingScroll { buildScreen() }
        }
    }

    // ---------------------------------------------------------------- 方案包

    private fun confirmInstall(v: WanxiangVariants.Variant) {
        AlertDialog.Builder(requireContext())
            .setTitle(v.title)
            .setMessage("下载 ${WanxiangVariants.sizeText(v.bytes)}，装好后替换现有词库并部署，需要几分钟。")
            .setPositiveButton("下载") { _, _ -> install(v) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun install(v: WanxiangVariants.Variant) {
        if (busy) {
            toast("正在处理上一个任务")
            return
        }
        busy = true
        val url = WanxiangVariants.urlOf(v)
        Timber.d("wanxiang: download $url")
        lifecycleScope.launch {
            val zip = File(requireContext().cacheDir, v.file)
            if (zip.isFile) zip.delete()
            toast("开始下载 ${v.title}")
            val ok = WanxiangInstaller.download(url, zip) { done, total ->
                if (total > 0) {
                    val pct = (done * 100 / total).toInt()
                    activity?.runOnUiThread { updateRow(v.title, "下载中 $pct%") }
                }
            }
            if (!ok) {
                busy = false
                toast("下载失败，检查网络后重试")
                return@launch
            }
            val dir = SchemaPackStore.dir(SchemaPackStore.PRO_ID)
            dir.mkdirs()
            val count = WanxiangInstaller.install(zip, dir, v.id)
            zip.delete()
            if (count <= 0) {
                busy = false
                toast("解包失败")
                return@launch
            }
            // 装进 Pro 包：按当前设置写它的 custom，然后切过去
            val wrote = withContext(Dispatchers.IO) { WanxiangCustom.writeAll(SchemaPackStore.PRO_ID) }
            Timber.d("wanxiang: write pro custom=$wrote")
            SchemaPackageManager.setActivePackageId(SchemaPackStore.PRO_ID)
            toast("已装进 Pro 并切换，正在部署…")
            runCatching { RimeDaemon.restartRime(fullCheck = true) }
                .onFailure { Timber.w(it, "wanxiang: deploy failed") }
            busy = false
            rebuildPreferencesKeepingScroll { buildScreen() }
        }
    }

    /** 把进度写到标题以 [prefix] 开头的那一行上。 */
    private fun updateRow(prefix: String, text: String) {
        runCatching {
            val root = preferenceScreen ?: return
            for (i in 0 until root.preferenceCount) {
                val group = root.getPreference(i) as? PreferenceGroup ?: continue
                for (j in 0 until group.preferenceCount) {
                    val row = group.getPreference(j) as? BambooClick ?: continue
                    if (row.title?.toString()?.startsWith(prefix) == true) {
                        row.summary = text
                        return
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 语法模型

    /** 语法模型跟当前生效的万象包走（librime 只从活动包目录读）。 */
    private fun gramFile(): File =
        File(SchemaPackStore.dir(WanxiangCustom.activePackage()), WanxiangVariants.GRAM_FILE)

    private fun gramTitle(): String =
        if (gramFile().isFile) "语法模型（已下载）" else "下载语法模型"

    private fun gramSummary(): String =
        if (gramFile().isFile) {
            "${WanxiangVariants.sizeText(gramFile().length())} · 点击可删除"
        } else {
            "${WanxiangVariants.sizeText(WanxiangVariants.GRAM_BYTES)} · 让长句更顺，可选"
        }

    private fun confirmGram() {
        if (gramFile().isFile) {
            AlertDialog.Builder(requireContext())
                .setTitle("删除语法模型？")
                .setMessage("删掉后长句联想会变差，随时可以再下。")
                .setPositiveButton("删除") { _, _ ->
                    gramFile().delete()
                    rebuildPreferencesKeepingScroll { buildScreen() }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("下载语法模型")
            .setMessage("约 390 MB，只能整包下载，中途退出要重下。")
            .setPositiveButton("下载") { _, _ -> downloadGram() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadGram() {
        if (busy) {
            toast("正在处理上一个任务")
            return
        }
        busy = true
        lifecycleScope.launch {
            val dest = gramFile()
            dest.parentFile?.mkdirs()
            toast("开始下载语法模型")
            val ok = WanxiangInstaller.download(WanxiangVariants.gramUrl(), dest) { done, total ->
                if (total > 0) {
                    val pct = (done * 100 / total).toInt()
                    activity?.runOnUiThread { updateRow("下载语法模型", "下载中 $pct%") }
                }
            }
            busy = false
            if (!ok) {
                dest.delete()
                toast("下载失败，检查网络后重试")
                return@launch
            }
            toast("语法模型已就位，重新部署后生效")
            runCatching { RimeDaemon.restartRime(fullCheck = true) }
            rebuildPreferencesKeepingScroll { buildScreen() }
        }
    }

    // ---------------------------------------------------------------- 工具

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
                setOnPreferenceClickListener {
                    onClick?.invoke()
                    true
                }
            },
        )
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }
}
