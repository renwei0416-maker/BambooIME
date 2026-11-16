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
 * 所以内置资产放 `assets/bamboo-themes/`（不进 sync 清单），由这里**只往用户目录铺一份**：
 * - 磁盘上永远只有一份 `Gboard.lua`，列表天然不重复，`getAllThemes()` 不用改（不碰上游）；
 * - 用户解压 `themes.7z` 覆盖同名文件 = 覆盖它，行为和以前一致；
 * - 我们更新了内置主题而用户没换 APK：用户覆盖即生效（同一目录同一文件名）。
 *
 * 字体：`FontManager` 只认 `userDataBaseDir/themes/fonts/`，所以字体也铺到这里，
 * 且**只在缺失时补**（用户可能自己换过字体，不覆盖）。
 */
object BambooThemeSeed {
    private const val ASSET_ROOT = "bamboo-themes"
    private const val THEME_DIR = "themes"
    private const val FONT_DIR = "fonts"

    /** 主题共用的 `lib/`（配色、布局、preset_keys…）：内置主题 require 的就是它。 */
    private const val LIB_DIR = "lib"

    /** 内置主题/字体内容变化时 +1，用来触发一次重新铺设。 */
    private const val STAMP = "1"

    private const val MARKER = ".bamboo-themes"
    private const val ADOPTED = ".bamboo-theme-default"

    /** 出厂主题的 id（= 文件名去掉 .lua）。 */
    private const val THEME_ID = "Gboard"

    /** 上游 `ThemePrefs.selectedTheme` 的默认值，等于「用户从没选过主题」。 */
    private const val UPSTREAM_DEFAULT_THEME = "catppuccin"

    fun ensure(context: Context) {
        runCatching { ensureInner(context) }
            .onFailure { Timber.w(it, "BambooThemeSeed: 铺设出厂主题失败") }
    }

    private fun ensureInner(context: Context) {
        val themesDir = DataManager.userThemesDir
        val fontsDir = File(DataManager.userDataBaseDir, "$THEME_DIR/$FONT_DIR").also { it.mkdirs() }

        copyFontsIfMissing(context, fontsDir)
        copyLibIfMissing(context)

        val themeAsset = "$ASSET_ROOT/$THEME_DIR/$THEME_ID.lua"
        val themeTarget = File(themesDir, "$THEME_ID.lua")
        syncBundledFile(context, themeAsset, themeTarget, keepBackup = true)

        val marker = File(themesDir, MARKER)
        if (marker.takeIf { it.isFile }?.readText()?.trim() != STAMP) {
            marker.writeText(STAMP)
            Timber.d("BambooThemeSeed: stamped $STAMP")
        }

        adoptDefaultThemeOnce(themesDir)
    }

    /** 字体只补不管：目标已存在就认为用户自备/已装，绝不覆盖。 */
    private fun copyFontsIfMissing(context: Context, fontsDir: File) {
        val names = runCatching { context.assets.list("$ASSET_ROOT/$FONT_DIR") }.getOrNull() ?: return
        names.forEach { name ->
            val target = File(fontsDir, name)
            if (target.exists() && target.length() > 0L) return@forEach
            runCatching {
                context.assets.open("$ASSET_ROOT/$FONT_DIR/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Timber.d("BambooThemeSeed: font $name")
            }.onFailure { Timber.w(it, "BambooThemeSeed: 字体 $name 铺设失败") }
        }
    }

    /**
     * 主题共用库 `lib/`（82 个文件 / 0.77 MB）：`Gboard.lua` 里 `safe_require("colors.gboard")`、
     * `layouts.*`、`preset_keys` 全落在这里，缺了它主题会直接加载失败（首次安装没有 themes.7z 时就是这种情况）。
     *
     * 同样**只补不覆盖**：用户装过 themes.7z 的话 `lib/` 已是自己的那份，不能抢。
     */
    private fun copyLibIfMissing(context: Context) {
        val root = File(DataManager.userDataBaseDir, THEME_DIR).also { it.mkdirs() }
        runCatching { copyAssetTree(context, "$ASSET_ROOT/$LIB_DIR", File(root, LIB_DIR)) }
            .onFailure { Timber.w(it, "BambooThemeSeed: lib 铺设失败") }
    }

    private fun copyAssetTree(context: Context, assetPath: String, dest: File) {
        val children = runCatching { context.assets.list(assetPath) }.getOrNull() ?: return
        if (children.isEmpty()) {
            if (dest.exists() && dest.length() > 0L) return
            runCatching {
                context.assets.open(assetPath).use { input ->
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { Timber.w(it, "BambooThemeSeed: $assetPath 铺设失败") }
            return
        }
        dest.mkdirs()
        children.forEach { copyAssetTree(context, "$assetPath/$it", File(dest, it)) }
    }

    /**
     * 主题文件与内置保持同步。
     *
     * `Gboard.lua` 这个 id 只由我们发行，所以内容不同就说明是**旧版内置**（或用户改过）。
     * 两种情况都覆盖成新版（否则老用户的「默认深色」永远不生效），
     * 但覆盖前把旧文件另存为 `Gboard.lua.bak-<sha8>`，用户想找回随时能找。
     */
    private fun syncBundledFile(
        context: Context,
        assetPath: String,
        target: File,
        keepBackup: Boolean,
    ) {
        val assetBytes = runCatching { context.assets.open(assetPath).use { it.readBytes() } }
            .getOrNull() ?: run {
            Timber.w("BambooThemeSeed: 资产缺失 $assetPath")
            return
        }
        val assetSha = assetBytes.sha256()

        if (target.isFile) {
            val current = runCatching { target.readBytes() }.getOrNull()
            if (current != null && current.sha256() == assetSha) return
            if (keepBackup && current != null) {
                val bak = File(target.parentFile, "${target.name}.bak-${current.sha256().take(8)}")
                if (!bak.exists()) {
                    runCatching { bak.writeBytes(current) }
                    Timber.d("BambooThemeSeed: 备份旧主题 ${bak.name}")
                }
            }
        }

        runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(assetBytes)
            Timber.d("BambooThemeSeed: 主题 ${target.name}")
        }.onFailure { Timber.w(it, "BambooThemeSeed: 主题 ${target.name} 写入失败") }
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
        runCatching { adopted.writeText("$THEME_ID/$STAMP") }
    }

    private fun ByteArray.sha256(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }
}
