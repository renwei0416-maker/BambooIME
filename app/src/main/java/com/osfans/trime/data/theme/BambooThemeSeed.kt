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
 * 把「出厂主题」从 APK 资产铺到用户主题目录，并按**主题版本**做整体更新。
 *
 * 为什么不用 `assets/themes/`：
 * 那条通道会被 `DataManager.sync` 按校验和自动同步进**应用私有**的 `themesDir`，
 * 于是同一个 id 会在「私有目录（内置）」和「用户目录（自己装的）」各存一份，
 * 而 `ThemeManager.getAllThemes()` 是把两个目录直接相加、不按 id 去重 ——
 * 主题列表里就会出现两条同名主题（点哪条都是用户那份生效）。
 *
 * 所以内置资产放 `assets/bamboo-themes/`（不进 sync 清单），由这里**只往用户目录铺一份**。
 *
 * 版本化更新（本轮改造）：
 * 以前 `lib/` 是「只补不覆盖」，导致改了 `lib/gboard/preset_keys.lua` 这类共享文件，
 * 用户装了新 APK 也永远用手机里那份旧的 —— 「更新一版主题」实际到不了用户手里。
 * 现在 `assets/bamboo-themes/VERSION` 是唯一版本号：
 * - 手机副本的版本与资产不同 → 把**全部内置主题（themes 目录下的 lua）与共享 lib 目录整体覆盖**一遍；
 * - 版本相同 → 只补齐缺失文件（保留用户自己的改动）。
 *
 * 边界：只覆盖「资产里存在的那一份」；用户自建主题、自己往 `lib/` 加的文件不会被删。
 * 字体始终**只补不覆盖**（用户可能自己换过字体）。
 */
object BambooThemeSeed {
    private const val ASSET_ROOT = "bamboo-themes"
    private const val THEME_DIR = "themes"
    private const val FONT_DIR = "fonts"

    /** 主题共用的 `lib/`（配色、布局、preset_keys…）：内置主题 require 的就是它。 */
    private const val LIB_DIR = "lib"

    /** 出厂主题包的版本号文件：内容变化即触发一次整体覆盖。 */
    private const val VERSION_ASSET = "$ASSET_ROOT/VERSION"

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

        val version = readAssetText(context, VERSION_ASSET)?.trim().orEmpty()
        val marker = File(themesDir, MARKER)
        val deployed = marker.takeIf { it.isFile }
            ?.let { f -> runCatching { f.readText().trim() }.getOrNull() }
            .orEmpty()

        val needUpdate = version.isNotEmpty() && deployed != version

        if (needUpdate) {
            // 版本不同：内置内容整体覆盖（所有主题 + 共享 lib）
            mirrorAssetTree(context, "$ASSET_ROOT/$THEME_DIR", themesDir)
            mirrorAssetTree(context, "$ASSET_ROOT/$LIB_DIR", File(themesDir, LIB_DIR))
            runCatching { marker.writeText(version) }
                .onSuccess { Timber.i("BambooThemeSeed: 主题更新到 $version") }
                .onFailure { Timber.w(it, "BambooThemeSeed: 版本戳写入失败") }
        } else {
            // 版本相同（或资产没有版本戳）：只补齐缺失文件，不动用户改动
            copyAssetTreeIfMissing(context, "$ASSET_ROOT/$THEME_DIR", themesDir)
            copyAssetTreeIfMissing(context, "$ASSET_ROOT/$LIB_DIR", File(themesDir, LIB_DIR))
        }

        copyFontsIfMissing(context, fontsDir)
        adoptDefaultThemeOnce(themesDir, version)
    }

    private fun readAssetText(context: Context, assetPath: String): String? =
        runCatching { context.assets.open(assetPath).use { it.readBytes().decodeToString() } }
            .onFailure { Timber.w(it, "BambooThemeSeed: 读取 $assetPath 失败") }
            .getOrNull()

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
     * 整体镜像：目录递归，叶子文件**无条件覆盖**。
     *
     * 不删「资产里没有、用户目录里有」的文件：用户可能自建主题或往 `lib/` 加过布局。
     */
    private fun mirrorAssetTree(context: Context, assetPath: String, dest: File) {
        val children = runCatching { context.assets.list(assetPath) }.getOrNull() ?: return
        if (children.isEmpty()) {
            runCatching {
                context.assets.open(assetPath).use { input ->
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { Timber.w(it, "BambooThemeSeed: $assetPath 覆盖失败") }
            return
        }
        dest.mkdirs()
        children.forEach { mirrorAssetTree(context, "$assetPath/$it", File(dest, it)) }
    }

    /** 只补不覆盖：仅在目标缺失或为空时铺设。 */
    private fun copyAssetTreeIfMissing(context: Context, assetPath: String, dest: File) {
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
        children.forEach { copyAssetTreeIfMissing(context, "$assetPath/$it", File(dest, it)) }
    }

    /**
     * 首次运行把默认主题设为出厂主题（Gboard）。
     *
     * 只在「用户从没选过主题」（偏好值仍是上游默认 `catppuccin`）时改一次，
     * 之后用户自己换主题不会被我们再抢回来。不改上游 `ThemePrefs` 的默认值，
     * 因为那是上游文件；这里只是替用户点一次「选择主题」。
     */
    private fun adoptDefaultThemeOnce(themesDir: File, version: String) {
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
        runCatching { adopted.writeText("$THEME_ID/$version") }
    }
}
