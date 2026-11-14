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
import com.osfans.trime.R
import com.osfans.trime.daemon.RimeDaemon
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
 * 内置的万象 Pro（小鹤）在安装完就会被静默释放到 `Documents/BambooIME[Debug]/wanxiang-pro`，
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
        preferenceScreen =
            preferenceManager.createPreferenceScreen(requireContext()).apply {
                addBambooCategory(
                    "内置方案包",
                    "万象拼音 Pro（小鹤双拼 + 直接辅助码），首次启动时已静默释放到用户目录",
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
                                    "期间键盘可能短暂不可用；你现有目录不会被动。"
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
                        summary = "只删 Documents 下这份 `wanxiang-pro`，你的 `wanxiang` 目录一个字节都不动",
                    ) {
                        confirmDelete()
                    }
                }

                addBambooCategory("说明", "装之前先知道这几件事") {
                    addRow(
                        title = "体积与耗时",
                        summary = "包内 84.7 MB；部署后 `build/` 约 100–120 MB；首次部署 2–4 分钟。" +
                            "九键（T9）需要更大的 word 表，首次会再久一点。",
                    )

                    addRow(
                        title = "都带了什么",
                        summary = "官方 Pro：26 键（小鹤双拼 + 直接辅助码）、英文、混输、反查、自定义短语；" +
                            "另有 BambooIME 补的九键 Pro（全拼，词库与 26 键共用）",
                    )

                    addRow(
                        title = "许可与署名",
                        summary = "词库与配置来自 amzxyz/rime-wanxiang（CC-BY-4.0）。" +
                            "九键方案由本应用按官方 Base 的九键方案生成，未改动词库。",
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
            .setTitle("启用万象拼音 Pro")
            .setMessage(
                "将切到 `wanxiang-pro` 并立即部署。\n\n" +
                    "· 词库较大，首次部署约 2–4 分钟，期间键盘可能短暂不可用\n" +
                    "· 不动你现有的 `wanxiang` 目录\n" +
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
            toast("正在部署，请稍候…")
            runCatching {
                rime.runOnReady {
                    selectSchemaCrossPackage(SCHEMA_MAIN)
                    setEnabledSchemata(arrayOf(SCHEMA_MAIN, SCHEMA_T9))
                    selectSchema(SCHEMA_MAIN)
                }
            }.onFailure {
                toast("切换失败：${it.message ?: it.javaClass.simpleName}")
            }
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
                "只会删除 `wanxiang-pro` 这个目录（${SchemaPackStore.sizeText()}）。\n" +
                    "你自己的 `wanxiang` 目录不受影响。\n\n" +
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

    private companion object {
        const val SCHEMA_MAIN = "wanxiang_pro"
        const val SCHEMA_T9 = "wanxiang_t9_pro"
    }
}
