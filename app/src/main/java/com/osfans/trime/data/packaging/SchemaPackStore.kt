/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.packaging

import android.content.Context
import android.content.res.AssetManager
import com.osfans.trime.BuildConfig
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
 *  * **只内置 Pro（小鹤 flypy）**，包 id 固定 [PACKAGE_ID]，绝不占用用户自己的 `wanxiang`；
 *  * **安装完就拷**：后台线程释放，不部署、不改活动包，避免「预置→部署→再下载→再部署」；
 *  * **覆盖规则是黑名单式**：只跳过 `custom/` 目录与 `*.custom.yaml`，其余同名文件全盖；
 *    并且**永不删除**目标目录里已有的文件（包里没有的一律不碰）；
 *  * 九键 Pro 需要 4 个官方 Base 的 t9 文件 + 1 个我们生成的 schema，一起叠进同一个目录；
 *  * manifest 只记 version / variant / 文件数 / 字节数 / 安装时间，不做 per-file 哈希
 *    （librime 按词库校验和自行重建 `build/`，不需要我们比对）。
 */
object SchemaPackStore {

    /** 内置包 id，同时也是 `Documents/BambooIME/` 下的目录名。 */
    const val PACKAGE_ID = "wanxiang-pro"

    /** 当前内置的变体。其余 8 套走 App 内下载（P3）。 */
    const val VARIANT_FLYPY = "flypy"

    private const val ASSET_PRO = "wanxiang-pro"
    private const val ASSET_T9 = "wanxiang-t9"
    private const val MANIFEST = "pkg-manifest.json"

    /** 内置资产集合有变化时加一，让老安装重拷一次。 */
    private const val STAMP = "1"

    private const val CUSTOM_FILE = "wanxiang_pro.custom.yaml"
    private const val DEFAULT_CUSTOM = "default.custom.yaml"

    @Volatile
    private var copying = false

    // ---------------------------------------------------------------- 路径

    /** 包目录：`Documents/BambooIME[Debug]/wanxiang-pro`。 */
    fun dir(): File = File(DataManager.userDataBaseDir, PACKAGE_ID)

    fun isInstalled(): Boolean = File(dir(), MANIFEST).isFile

    fun isActive(): Boolean = SchemaPackageManager.activePackageId == PACKAGE_ID

    fun manifest(): JSONObject? =
        runCatching { JSONObject(File(dir(), MANIFEST).readText()) }.getOrNull()

    fun variant(): String = manifest()?.optString("variant").orEmpty().ifEmpty { VARIANT_FLYPY }

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
     * 后台静默释放内置包（只拷文件，不部署、不改活动包）。
     *
     * 由 [com.osfans.trime.core.Rime] 在每次启动引擎时调一次；已就绪时只有一次 stat，开销可忽略。
     */
    fun startBackgroundCopy(context: Context) {
        if (!BuildConfig.BUNDLED_WANXIANG_PRO) return
        if (copying) return
        copying = true
        val app = context.applicationContext
        Thread(
            {
                try {
                    ensureCopied(app)
                } catch (e: Exception) {
                    Timber.w(e, "wanxiang: background copy failed")
                } finally {
                    copying = false
                }
            },
            "wanxiang-copy",
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
        val files = copyAssetTree(context.assets, ASSET_PRO, dir) +
            copyAssetTree(context.assets, ASSET_T9, dir)
        writeManifest(dir, stamp, files)
        writeFirstConfigIfAbsent(dir)
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
                .put("variant", VARIANT_FLYPY)
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
     *  * `wanxiang_pro.custom.yaml`：小鹤双拼 + 直接辅助码 + 每页 6 个候选（官方默认）；
     *  * `default.custom.yaml`：把九键 Pro 也列进方案表（否则用户只能看到 26 键那一个）。
     */
    private fun writeFirstConfigIfAbsent(dir: File) {
        val custom = File(dir, CUSTOM_FILE)
        if (!custom.exists()) {
            runCatching {
                custom.writeText(
                    """
                    # 由 BambooIME 首次释放内置包时生成，之后可在「万象拼音」设置页里改。
                    # 只写了这两个键，其余全部走官方 schema 的默认值。
                    patch:
                      speller/algebra:
                        __patch:
                          - wanxiang_algebra:/pro/小鹤双拼
                          - wanxiang_algebra:/pro/直接辅助
                      menu/page_size: 6
                    """.trimIndent() + "\n",
                )
            }
        }
        val defaults = File(dir, DEFAULT_CUSTOM)
        if (!defaults.exists()) {
            runCatching {
                defaults.writeText(
                    """
                    # 由 BambooIME 生成：26 键 Pro 与九键 Pro 都列进方案表。
                    patch:
                      schema_list:
                        - schema: wanxiang_pro
                        - schema: wanxiang_t9_pro
                    """.trimIndent() + "\n",
                )
            }
        }
    }

    // ---------------------------------------------------------------- 删除

    /** 删除已释放的包目录（只删我们自己这份，绝不碰用户的 `wanxiang`）。 */
    fun deleteFiles(): Boolean {
        val dir = dir()
        if (!dir.exists()) return true
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }
}
