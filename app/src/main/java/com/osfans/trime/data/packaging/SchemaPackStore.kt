/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.packaging

import android.content.Context
import android.content.res.AssetManager
import com.osfans.trime.BuildConfig
import com.osfans.trime.actions.wanxiang.WanxiangCustom
import com.osfans.trime.data.base.DataManager
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 万象拼音的**两个**方案包。
 *
 *  * [BASE_ID]：内置在 APK 的 assets 里（官方 Base —— 全拼/任意双拼 + 官方九键），
 *    安装完静默释放到用户目录；**九键只用 Base**，Pro 没有九键。
 *  * [PRO_ID]：用户从下载页装（官方 `*-fuzhu` 变体，带辅助码词库），九个变体共用
 *    这一个目录、互相覆盖同名文件。
 *
 * 两条包各自独立目录、各自一份 `build/`，互不覆盖；切换 = 改活动包 + 部署一次。
 *
 * 覆盖规则是黑名单式：只跳过 `custom/` 目录与 `*.custom.yaml`，其余同名文件全盖，
 * 并且**永不删除**目标目录里已有的文件（包里没有的一律不碰）。
 *
 * manifest 只记 version / variant / 文件数 / 字节数 / 安装时间，不做 per-file 哈希
 * （librime 按词库校验和自行重建 `build/`）。
 */
object SchemaPackStore {

    /** 内置包 id，同时也是 `Documents/BambooIME/` 下的目录名。 */
    const val BASE_ID = "wanxiang-base"

    /** 下载包的 id / 目录名。九个 fuzhu 变体都装进这里。 */
    const val PRO_ID = "wanxiang-pro"

    /** 内置资产对应的变体名。 */
    const val VARIANT_BASE = "base"

    private const val ASSET_BASE = "wanxiang-base"
    private const val MANIFEST = "pkg-manifest.json"

    /** 内置资产集合有变化时加一，让老安装重拷一次。 */
    private const val STAMP = "1"

    @Volatile
    private var copying = false

    /** 这个 id 是不是我们管的包（用于「活动包不是我们的」判定）。 */
    fun isOurs(pkgId: String): Boolean = pkgId == BASE_ID || pkgId == PRO_ID

    // ---------------------------------------------------------------- 路径

    /** 包目录：`Documents/BambooIME[Debug]/<pkgId>`。 */
    fun dir(pkgId: String = BASE_ID): File = File(DataManager.userDataBaseDir, pkgId)

    fun isInstalled(pkgId: String = BASE_ID): Boolean = File(dir(pkgId), MANIFEST).isFile

    fun isActive(pkgId: String = BASE_ID): Boolean = SchemaPackageManager.activePackageId == pkgId

    /**
     * 已装但和当前 APK 带的那份不一致 → 有新版本可更新。
     *
     * stamp = `BUILD_VERSION_NAME/STAMP`，所以升级 APK 后 Base 会被判为可更新；
     * Pro 的 stamp 也在装包时写入，bump 过 Variants.TAG 的新版同样会被判为可更新。
     */
    fun isOutdated(pkgId: String = BASE_ID): Boolean {
        val m = manifest(pkgId) ?: return false
        return m.optString("stamp") != currentStamp()
    }

    fun manifest(pkgId: String = BASE_ID): JSONObject? =
        runCatching { JSONObject(File(dir(pkgId), MANIFEST).readText()) }.getOrNull()

    /** 变体名：内置 Base 是 `base`，下载的是对应 fuzhu id（`flypy`/`zrm`…）。 */
    fun variantOf(pkgId: String = BASE_ID): String =
        manifest(pkgId)?.optString("variant").orEmpty()
            .ifEmpty { if (pkgId == PRO_ID) "pro" else VARIANT_BASE }

    fun fileCount(pkgId: String = BASE_ID): Int = manifest(pkgId)?.optInt("files") ?: 0

    fun sizeText(pkgId: String = BASE_ID): String {
        val bytes = manifest(pkgId)?.optLong("bytes") ?: 0L
        if (bytes <= 0L) return "—"
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    fun installedAtText(pkgId: String = BASE_ID): String =
        manifest(pkgId)?.optString("installedAt").orEmpty().ifEmpty { "—" }

    // ---------------------------------------------------------------- 释放

    /** 当前这份资产对应的版本戳；下载安装的变体也用它，只有内容变了才会补。 */
    fun currentStamp(): String = "${BuildConfig.BUILD_VERSION_NAME}/$STAMP"

    /**
     * 同步释放内置 Base（阻塞）。返回 true 表示包已就绪（这次拷了或者本来就在）。
     *
     * 只拷文件：**不部署、不改活动包**。谁想用它，谁自己再写 custom + 设活动包 + 部署。
     */
    fun ensureBaseCopied(context: Context, force: Boolean = false): Boolean {
        if (!BuildConfig.BUNDLED_WANXIANG_PRO) return false
        val dir = dir(BASE_ID)
        val stamp = currentStamp()
        // force=true 时无视 stamp，直接重拷（用于「更新内置包」）。custom 永远只补不改。
        if (!force && File(dir, MANIFEST).isFile && manifest(BASE_ID)?.optString("stamp") == stamp) return true

        if (!dir.exists() && !dir.mkdirs()) {
            Timber.w("wanxiang: cannot create ${dir.absolutePath}")
            return false
        }
        val started = System.currentTimeMillis()
        val files = copyAssetTree(context.assets, ASSET_BASE, dir)
        writeManifest(dir, BASE_ID, stamp, files, VARIANT_BASE)
        // 首次释放时写一份「带托管块」的默认配置；已存在则一个字节不动。
        WanxiangCustom.writeInitialConfigIfMissing(dir, BASE_ID)
        Timber.d("wanxiang: copied $files files in ${System.currentTimeMillis() - started} ms")
        return true
    }

    private fun copyAssetTree(
        assets: AssetManager,
        path: String,
        dest: File,
    ): Int {
        val children = assets.list(path) ?: return 0
        if (children.isEmpty()) {
            // 叶子节点＝文件
            dest.parentFile?.mkdirs()
            assets.open(path).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return 1
        }
        dest.mkdirs()
        var n = 0
        for (child in children) {
            n += copyAssetTree(assets, "$path/$child", File(dest, child))
        }
        return n
    }

    /** 写 manifest。变体名由调用方给：内置释放是 `base`，下载装的是各自 fuzhu id。 */
    internal fun writeManifest(
        dir: File,
        pkgId: String,
        stamp: String,
        files: Int,
        variantId: String,
    ) {
        var bytes = 0L
        dir.walkTopDown().forEach { if (it.isFile) bytes += it.length() }
        val json =
            JSONObject()
                .put("package", pkgId)
                .put("variant", variantId)
                .put("stamp", stamp)
                .put("version", BuildConfig.BUILD_VERSION_NAME ?: "dev")
                .put("files", files)
                .put("bytes", bytes)
                .put("installedAt", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        runCatching { File(dir, MANIFEST).writeText(json.toString(2)) }
    }

    // ---------------------------------------------------------------- 删除

    /** 删除某个包的目录（只删我们自己这两个 id，绝不碰用户的 `wanxiang`）。 */
    fun deleteFiles(pkgId: String = BASE_ID): Boolean {
        if (!isOurs(pkgId)) return false
        val dir = dir(pkgId)
        if (!dir.exists()) return true
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }
}
