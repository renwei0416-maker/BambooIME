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
 * 万象拼音 Pro 方案包：内置在 APK 的 assets 里，安装完就静默释放到用户目录。
 *
 * 设计口径（与用户逐条确认过）：
 *
 *  * **内置官方 Base 包**（全拼/任意双拼 + 官方九键），包 id 固定 [PACKAGE_ID]，
 *    不占用用户自己可能已有的 `wanxiang` 目录；
 *  * **安装完就拷**：后台线程释放，不部署、不改活动包，避免「预置→部署→再下载→再部署」；
 *  * **覆盖规则是黑名单式**：只跳过 `custom/` 目录与 `*.custom.yaml`，其余同名文件全盖；
 *    并且**永不删除**目标目录里已有的文件（包里没有的一律不碰）；
 *  * 九键直接用官方 `wanxiang_t9.schema.yaml`（Base 自带），不再 vendor 任何 t9 文件；
 *  * manifest 只记 version / variant / 文件数 / 字节数 / 安装时间，不做 per-file 哈希
 *    （librime 按词库校验和自行重建 `build/`，不需要我们比对）。
 */
object SchemaPackStore {

    /** 内置包 id，同时也是 `Documents/BambooIME/` 下的目录名。 */
    const val PACKAGE_ID = "wanxiang-base"

    /** 当前内置的变体：官方 Base（全拼/任意双拼 + 九个官方九键方案，不含辅码词库）。 */
    const val VARIANT_BASE = "base"

    private const val ASSET_BASE = "wanxiang-base"
    /** 上一版内置的 Pro 包目录名；升级后没人清理会白占 120 MB 以上。 */
    private const val LEGACY_PACKAGE_ID = "wanxiang-pro"

    private const val MANIFEST = "pkg-manifest.json"

    /** 内置资产集合有变化时加一，让老安装重拷一次。 */
    private const val STAMP = "1"

    private const val CUSTOM_FILE = "wanxiang.custom.yaml"
    private const val DEFAULT_CUSTOM = "default.custom.yaml"

    @Volatile
    private var copying = false

    // ---------------------------------------------------------------- 路径

    /** 包目录：`Documents/BambooIME[Debug]/wanxiang-base`。 */
    fun dir(): File = File(DataManager.userDataBaseDir, PACKAGE_ID)

    fun isInstalled(): Boolean = File(dir(), MANIFEST).isFile

    fun isActive(): Boolean = SchemaPackageManager.activePackageId == PACKAGE_ID

    /**
     * 清掉上一版内置的 `wanxiang-pro` 目录。
     *
     * 只在三种条件都满足时删：①该目录里有我们写的 manifest；②manifest 的 package 字段
     * 确实是 `wanxiang-pro`（说明是我们释放的，不是用户自己建的）；③它当前不是活动包。
     */
    fun cleanupLegacy() {
        val old = File(DataManager.userDataBaseDir, LEGACY_PACKAGE_ID)
        if (!old.isDirectory) return
        if (SchemaPackageManager.activePackageId == LEGACY_PACKAGE_ID) {
            Timber.d("wanxiang: legacy package is active, skip cleanup")
            return
        }
        val meta = runCatching {
            JSONObject(File(old, MANIFEST).readText())
        }.getOrNull() ?: return
        if (meta.optString("package") != LEGACY_PACKAGE_ID) return
        if (old.deleteRecursively()) {
            Timber.d("wanxiang: removed legacy ${old.name}")
        }
    }

    fun manifest(): JSONObject? =
        runCatching { JSONObject(File(dir(), MANIFEST).readText()) }.getOrNull()

    fun variant(): String = manifest()?.optString("variant").orEmpty().ifEmpty { VARIANT_BASE }

    fun fileCount(): Int = manifest()?.optInt("files") ?: 0

    fun sizeText(): String {
        val bytes = manifest()?.optLong("bytes") ?: 0L
        if (bytes <= 0L) return "—"
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    fun installedAtText(): String =
        manifest()?.optString("installedAt").orEmpty().ifEmpty { "—" }

    // ---------------------------------------------------------------- 释放

    /**
     * 启动时只做「清理旧版本残留」，**不解包**。
     *
     * 内置包只在用户明确要用的时候才释放（向导第 1 步、或点「启用这个方案包」），
     * 否则用户一装 App 就被塞 67 MB 文件、还占着磁盘，是很讨厌的行为。
     */
    fun cleanupLegacyInBackground(context: Context) {
        if (!BuildConfig.BUNDLED_WANXIANG_PRO) return
        if (copying) return
        copying = true
        Thread(
            {
                try {
                    cleanupLegacy()
                } catch (e: Exception) {
                    Timber.w(e, "wanxiang: legacy cleanup failed")
                } finally {
                    copying = false
                }
            },
            "wanxiang-cleanup",
        ).apply { isDaemon = true }.start()
    }

    /**
     * 同步释放（阻塞）。返回 true 表示包已就绪（这次拷了或者本来就在）。
     *
     * 「启用」按钮在后台还没拷完时会调它兜底，保证不会拿到半份包。
     */
    fun ensureCopied(context: Context): Boolean {
        if (!BuildConfig.BUNDLED_WANXIANG_PRO) return false
        val dir = dir()
        val stamp = "${BuildConfig.BUILD_VERSION_NAME ?: "dev"}/$STAMP"
        val marker = File(dir, MANIFEST)
        if (marker.isFile && manifest()?.optString("stamp") == stamp) return true

        if (!dir.exists() && !dir.mkdirs()) {
            Timber.w("wanxiang: cannot create ${dir.absolutePath}")
            return false
        }
        val started = System.currentTimeMillis()
        val files = copyAssetTree(context.assets, ASSET_BASE, dir)
        writeManifest(dir, stamp, files)
        // 首次释放时写一份「带托管块」的默认配置；已存在则一个字节不动。
        WanxiangCustom.writeInitialConfigIfMissing(dir)
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

    private fun writeManifest(
        dir: File,
        stamp: String,
        files: Int,
    ) {
        var bytes = 0L
        dir.walkTopDown().forEach { if (it.isFile) bytes += it.length() }
        val json =
            JSONObject()
                .put("package", PACKAGE_ID)
                .put("variant", VARIANT_BASE)
                .put("stamp", stamp)
                .put("version", BuildConfig.BUILD_VERSION_NAME ?: "dev")
                .put("files", files)
                .put("bytes", bytes)
                .put("installedAt", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        runCatching { File(dir, MANIFEST).writeText(json.toString(2)) }
    }

    /**
     * 首次释放时写两份配置（已存在就不动，避免覆盖用户改过的内容）。
     *
     *  * `wanxiang.custom.yaml`：全拼 + 26 键 + 每页 6 个候选（官方默认）；
     *  * `default.custom.yaml`：把九键 Pro 也列进方案表（否则用户只能看到 26 键那一个）。
     */
    // ---------------------------------------------------------------- 删除

    /** 删除已释放的包目录（只删我们自己这份，绝不碰用户的 `wanxiang`）。 */
    fun deleteFiles(): Boolean {
        val dir = dir()
        if (!dir.exists()) return true
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }
}
