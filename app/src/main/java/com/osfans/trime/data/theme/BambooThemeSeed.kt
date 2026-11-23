/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.Context
import com.osfans.trime.data.base.DataManager
import timber.log.Timber
import java.io.File
import java.io.IOException

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
 * 1. [ensure]（启动）：按主题判断 —— 某套主题在用户目录里不完整（缺 `.lua`
 *    或缺 `lib/<目录>/`）就把这套补齐；已经完整的主题一律不动。这样全新安装能直接用，
 *    老用户手改过的主题也不会被悄悄改掉。
 * 2. [releaseAll]（设置页「释放内置主题」）：无条件覆盖这 5 套主题与全部内置字体，
 *    用于「我想回到出厂状态」。只覆盖同名文件，绝不删除用户目录里我们没有的文件，
 *    也不碰用户自建主题。任何一步失败都直接抛出，由调用方提示「释放异常」。
 *
 * 唯一例外：首次运行会把默认主题设为出厂主题（见 [adoptDefaultThemeOnce]），
 * 且只在「用户从没选过主题」时做一次。
 */
object BambooThemeSeed {
    private const val ASSET_ROOT = "bamboo-themes"
    private const val THEME_DIR = "themes"
    private const val FONT_DIR = "fonts"

    /** 每套主题私有的键位库目录名（`lib/<目录>/`）。 */
    private const val LIB_DIR = "lib"

    private const val ADOPTED = ".bamboo-theme-default"

    /** 出厂主题（= 文件名去掉 `.lua`）。 */
    const val THEME_ID = "Gboard"

    /**
     * 主题 id -> 资产里 `lib/` 下的目录名。
     *
     * 两套命名从拆分 `lib/<id>/` 起就不一致（`ChucaiJiannuan` 是 `cjn`、
     * `NekoCatppuccin` 是 `neko`），而 `assets.list()` 又大小写敏感，
     * 所以必须显式映射，不能拿 id 直接拼路径。
     */
    private val LIB_DIR_NAMES = mapOf(
        "ChucaiJiannuan" to "cjn",
        "DevPalettes" to "dev",
        "Gboard" to "gboard",
        "Gmk" to "gmk",
        "NekoCatppuccin" to "neko",
    )

    /** 内置的全部主题 id。新增/删除内置主题时只需改这里与 [LIB_DIR_NAMES]。 */
    val BUNDLED_THEME_IDS = LIB_DIR_NAMES.keys.toList()

    /** 上游 `ThemePrefs.selectedTheme` 的默认值，等于「用户从没选过主题」。 */
    private const val UPSTREAM_DEFAULT_THEME = "catppuccin"

    fun ensure(context: Context) {
        runCatching { ensureInner(context) }
            .onFailure { Timber.w(it, "BambooThemeSeed: 铺设出厂主题失败") }
    }

    /**
     * 无条件覆盖：把 5 套内置主题（`.lua` + `lib/<目录>/`）与全部内置字体写回用户目录。
     *
     * 失败即抛 [IOException]，不吞、不计数。
     */
    fun releaseAll(context: Context) {
        val themesDir = DataManager.userThemesDir
        val fontsDir = File(DataManager.userDataBaseDir, "$THEME_DIR/$FONT_DIR")
        BUNDLED_THEME_IDS.forEach { id ->
            val libDirName = LIB_DIR_NAMES.getValue(id)
            copyFile(context, "$ASSET_ROOT/$THEME_DIR/$id.lua", File(themesDir, "$id.lua"), overwrite = true)
            copyTree(context, "$ASSET_ROOT/$LIB_DIR/$libDirName", File(themesDir, "$LIB_DIR/$libDirName"), overwrite = true)
        }
        copyTree(context, "$ASSET_ROOT/$FONT_DIR", fontsDir, overwrite = true)
        Timber.d("BambooThemeSeed: 已全部覆盖")
    }

    private fun ensureInner(context: Context) {
        val themesDir = DataManager.userThemesDir
        val fontsDir = File(DataManager.userDataBaseDir, "$THEME_DIR/$FONT_DIR")

        BUNDLED_THEME_IDS.forEach { id ->
            val libDirName = LIB_DIR_NAMES.getValue(id)
            val lua = File(themesDir, "$id.lua")
            val lib = File(themesDir, "$LIB_DIR/$libDirName")
            if (lua.exists() && lib.isDirectory) return@forEach
            copyFile(context, "$ASSET_ROOT/$THEME_DIR/$id.lua", lua, overwrite = false)
            copyTree(context, "$ASSET_ROOT/$LIB_DIR/$libDirName", lib, overwrite = false)
        }
        copyTree(context, "$ASSET_ROOT/$FONT_DIR", fontsDir, overwrite = false)

        adoptDefaultThemeOnce(themesDir)
    }

    /** 复制单个资产；[overwrite] 为 false 时「目标已存在且非空」就跳过。 */
    private fun copyFile(context: Context, assetPath: String, dest: File, overwrite: Boolean) {
        if (!overwrite && dest.exists() && dest.length() > 0L) return
        try {
            context.assets.open(assetPath).use { input ->
                dest.parentFile?.mkdirs()
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: IOException) {
            throw IOException("$assetPath -> ${dest.absolutePath}", e)
        }
    }

    /** 递归复制资产目录（`assets.list` 对文件返回空数组，故与 [copyFile] 合流）。 */
    private fun copyTree(context: Context, assetPath: String, dest: File, overwrite: Boolean) {
        val children = context.assets.list(assetPath) ?: throw IOException("资产目录不存在：$assetPath")
        if (children.isEmpty()) {
            copyFile(context, assetPath, dest, overwrite)
            return
        }
        dest.mkdirs()
        children.forEach { name ->
            copyTree(context, "$assetPath/$name", File(dest, name), overwrite)
        }
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
