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
        val current = SchemaPackStore.variant()
        val installed = SchemaPackStore.isInstalled()

        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addBambooCategory("方案包", installedNote(installed, current)) {
                WanxiangVariants.list.forEach { v ->
                    val here = installed && v.id == current
                    addRow(
                        title = if (here) "${v.title}（使用中）" else v.title,
                        summary = "${WanxiangVariants.sizeText(v.bytes)} · ${v.note}",
                    ) {
                        if (here) {
                            toast("这个包正在用")
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

    private fun installedNote(installed: Boolean, variant: String): String =
        if (!installed) "内置包还没释放" else "当前：${WanxiangVariants.installedNote(variant)}"

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
            val dir = SchemaPackStore.dir()
            dir.mkdirs()
            val count = WanxiangInstaller.install(zip, dir, v.id)
            zip.delete()
            if (count <= 0) {
                busy = false
                toast("解包失败")
                return@launch
            }
            // 变体换了，配置里的节点前缀（base/ ↔ pro/）也要跟着换
            withContext(Dispatchers.IO) { runCatching { com.osfans.trime.actions.wanxiang.WanxiangCustom.writeAll() } }
            SchemaPackageManager.setActivePackageId(SchemaPackStore.PACKAGE_ID)
            toast("已切换，正在部署…")
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

    private fun gramFile(): File = File(SchemaPackStore.dir(), WanxiangVariants.GRAM_FILE)

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
