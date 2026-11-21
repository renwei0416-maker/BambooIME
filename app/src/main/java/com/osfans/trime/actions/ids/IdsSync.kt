/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.ids

import com.osfans.trime.actions.wanxiang.WanxiangCustom
import com.osfans.trime.actions.wanxiang.WanxiangPrefs
import com.osfans.trime.data.packaging.SchemaPackageManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.link.IdsDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 把「部件反查」的开关状态同步进万象方案，然后部署。
 *
 * 这是**唯一**会写 IDS patch 的地方。以前「写不写」由 `idsConfigOrNull()` 的默认值决定，
 * 而它把「开关关着」和「数据没装」都当成 null（= 清空），于是「删数据 → 下载 → 点应用」
 * 这条路上会静默把配置抹掉。
 *
 * 规则只有一条：[IdsPrefs.enabled] 且数据已安装 → 写入 ids 段；否则摘掉 ids 段。
 * 写完必须读回校验，不一致就用最近一份备份回滚，且不部署。
 */
object IdsSync {
    /** [ids] 表示这次写完后的状态是「有 IDS」还是「没有 IDS」。 */
    data class Outcome(
        val ok: Boolean,
        val ids: Boolean,
        val message: String,
    )

    suspend fun applyAndDeploy(target: String): Outcome = withContext(Dispatchers.IO) {
        val wantIds = IdsPrefs.enabled && IdsDataManager.isInstalled()
        if (IdsPrefs.enabled && !IdsDataManager.isInstalled()) {
            // 开关开着但数据没装：既不能写（部署必失败），也不该清（把用户已有配置留着）。
            return@withContext Outcome(false, false, "数据没装，未改动方案")
        }

        val written = WanxiangCustom.applySettings(
            pkgId = target,
            fuzzy = WanxiangPrefs.fuzzy,
            pageSize = WanxiangPrefs.pageSize,
            contextual = WanxiangPrefs.contextual,
            ids = if (wantIds) idsConfigOrNull() else null,
        )
        if (!written) {
            return@withContext Outcome(false, wantIds, "写入失败：检查 Documents 目录是否可写")
        }

        val file = WanxiangCustom.customFile(target)
        val text = if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
        val hasIds = text.contains("ids_reverse")
        if (hasIds != wantIds) {
            WanxiangCustom.restoreLatestBackup(target)
            return@withContext Outcome(
                false,
                hasIds,
                if (wantIds) "写入后没找到 ids_reverse，已回滚" else "摘除后仍有 ids_reverse，已回滚",
            )
        }

        SchemaPackageManager.setActivePackageId(target)
        runCatching { RimeDaemon.restartRime(fullCheck = false) }
            .onFailure { Timber.w(it, "ids: deploy failed") }
        Outcome(true, wantIds, if (wantIds) "已写入并部署" else "已摘除并部署")
    }
}
