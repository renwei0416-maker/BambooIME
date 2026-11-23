/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.Context
import com.osfans.trime.data.base.DataManager
import timber.log.Timber
import java.io.File

/**
 * 把「出厂主题」从 APK 资产铺到用户主题目录。
 *
 * 为什么不用 `assets/themes/`：
 * 那条通道会被 `DataManager.sync` 按校验和自动同步进**应用私有**的 `themesDir`，
 * 于是同一个 id 会在「私有目录（内置）」和「用户目录（自己装的）」各存一份，
 * 而 `ThemeManager.getAllThemes()` 是把两个目录直接相加、不按 id 去重 ——
 * 主题列表里就会出现两条同名主题（点哪条都是用户那份生效）。
 *
 * 所以内置资产放 `assets/bamboo-themes/`（不进 sync 清单），由这里管理：
 *
 * 1. [ensure]（启动）：**按主题判断**——某套主题在用户目录里不完整（缺 `.lua`
 *    或缺 `lib/<id>/`）就把这套补齐；已经完整的主题一律不动。这样全新安装能直接用，
 *    老用户手改过的主题也不会被悄悄改掉。
 * 2. [releaseAll]（设置页「释放内置主题」）：**无条件覆盖**这 5 套主题（`.lua` + `lib/<id>/`）
 *    与全部内置字体，用于「我想回到出厂状态」。只覆盖同名文件，绝不删除
 *    用户目录里我们没有的文件，也不碰用户自建主题。
 *
 * 唯一例外：首次运行会把默认主题设为出厂主题（见 [adoptDefaultThemeOnce]），
 * 且只在「用户从没选过主题」时做一次。
 */
object BambooThemeSeed {
    private const val ASSET_ROOT = "bamboo-themes"
    private const val THEME_DIR = "themes"
    private const val FONT_DIR = "fonts"

    /** 每套主题私有的键位库目录名（`lib/<id>/`）。 */
    private const val LIB_DIR = "lib"

    private const val ADOPTED = ".bamboo-theme-default"

    /** 出厂主题（= 文件名去掉 `.lua`）。 */
    const val THEME_ID = "Gboard"

    /** 内置的全部主题 id。新增/删除内置主题时只需改这里。 */
    val BUNDLED_THEME_IDS = listOf(
        "ChucaiJiannuan",
        "DevPalettes",
        "Gboard",
        "Gmk",
        "NekoCatppuccin",
    )

    /** 上游 `ThemePrefs.selectedTheme` 的默认值，等于「用户从没选过主题」。 */
    private const val UPSTREAM_DEFAULT_THEME = "catppuccin"

    fun ensure(context: Context) {
        runCatching { ensureInner(context) }
            .onFailure { Timber.w(it, "BambooThemeSeed: 铺设出厂主题失败") }
    }

    /**
     * 无条件覆盖：把 5 套内置主题（`.lua` + `lib/<id>/`）与全部内置字体写回用户目录。
     *
     * @return 写入的文件数
     */
    fun releaseAll(context: Context): Int {
        val themesDir = DataManager.userThemesDir
        val fontsDir = File(DataManager.userDataBaseDir, "$THEME_DIR/$FONT_DIR")
        var count = 0
        BUNDLED_THEME_IDS.forEach { id ->
            count += copyFile(context, "$ASSET_ROOT/$THEME_DIR/$id.lua", File(themesDir, "$id.lua"), overwrite = true)
            count += copyTree(context, "$ASSET_ROOT/$LIB_DIR/$id", File(themesDir, "$LIB_DIR/$id"), overwrite = true)
        }
        count += copyTree(context, "$ASSET_ROOT/$FONT_DIR", fontsDir, overwrite = true)
        Timber.d("BambooThemeSeed: released $count files")
        return count
    }

    private fun ensureInner(context: Context) {
        val themesDir = DataManager.userThemesDir
        val fontsDir = File(DataManager.userDataBaseDir, "$THEME_DIR/$FONT_DIR")

        BUNDLED_THEME_IDS.forEach { id ->
            val lua = File(themesDir, "$id.lua")
            val lib = File(themesDir, "$LIB_DIR/$id")
            if (lua.exists() && lib.isDirectory) return@forEach
            copyFile(context, "$ASSET_ROOT/$THEME_DIR/$id.lua", lua, overwrite = false)
            copyTree(context, "$ASSET_ROOT/$LIB_DIR/$id", lib, overwrite = false)
        }
        copyTree(context, "$ASSET_ROOT/$FONT_DIR", fontsDir, overwrite = false)

        adoptDefaultThemeOnce(themesDir)
    }

    /** 复制单个资产；[overwrite] 为 false 时「目标已存在且非空」就跳过。 */
    private fun copyFile(context: Context, assetPath: String, dest: File, overwrite: Boolean): Int {
        if (!overwrite && dest.exists() && dest.length() > 0L) return 0
        return runCatching {
            context.assets.open(assetPath).use { input ->
                dest.parentFile?.mkdirs()
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            1
        }.getOrElse {
            Timber.w(it, "BambooThemeSeed: $assetPath 铺设失败")
            0
        }
    }

    /** 递归复制资产目录（`assets.list` 对文件返回空数组，故与 [copyFile] 合流）。 */
    private fun copyTree(context: Context, assetPath: String, dest: File, overwrite: Boolean): Int {
        val children = runCatching { context.assets.list(assetPath) }.getOrNull() ?: return 0
        if (children.isEmpty()) return copyFile(context, assetPath, dest, overwrite)
        var count = 0
        dest.mkdirs()
        children.forEach { name ->
            count += copyTree(context, "$assetPath/$name", File(dest, name), overwrite)
        }
        return count
    }

    /**
     * 首次运行把默认主题设为出厂主题（Gboard）。
     *
     * 只在「用户从没选过主题」（偏好值仍是上游默认 `catppuccin`）时改一次，
     * 之后用户自己换主题不会被我们再抢回来。不改上游 `ThemePrefs` 的默认值，
     * 因为那是上游文件；这里只是替用户点一次「选择主题」。
     */
    private fun adoptDefaultThemeOnce(themesDir: File) {
        val adopted = File(themesDir, ADOPTED)
        if (adopted.exists()) return

        val prefs = ThemeManager.prefs
        val current = runCatching { prefs.selectedTheme.getValue() }.getOrNull()
        if (current == null || current == UPSTREAM_DEFAULT_THEME) {
            runCatching {
                prefs.selectedTheme.setValue(THEME_ID)
                Timber.d("BambooThemeSeed: 默认主题 -> $THEME_ID")
            }.onFailure { Timber.w(it, "BambooThemeSeed: 设置默认主题失败") }
        }
        runCatching { adopted.writeText(THEME_ID) }
    }
}
