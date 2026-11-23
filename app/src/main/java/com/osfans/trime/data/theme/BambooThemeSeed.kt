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
 * 所以内置资产放 `assets/bamboo-themes/`（不进 sync 清单），由这里**只往用户目录铺一份**。
 *
 * 铺设策略：**只补缺失，绝不覆盖**。
 * 用户目录里的主题是「用户的东西」—— 他可能手改过、也可能就是想用旧版；
 * 主题的更新由用户自己决定（复制文件到 `Documents/BambooIME/themes/` 下即可），
 * App 不替他做版本比对，也不会悄悄改写他目录里的文件。
 *
 * 唯一例外：首次运行会把默认主题设为出厂主题（见 [adoptDefaultThemeOnce]），
 * 且只在「用户从没选过主题」时做一次。
 */
object BambooThemeSeed {
    private const val ASSET_ROOT = "bamboo-themes"
    private const val THEME_DIR = "themes"
    private const val FONT_DIR = "fonts"

    /** 主题共用的 `lib/`（配色、布局、preset_keys…）：内置主题 require 的就是它。 */
    private const val LIB_DIR = "lib"

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

        copyAssetTreeIfMissing(context, "$ASSET_ROOT/$THEME_DIR", themesDir)
        copyAssetTreeIfMissing(context, "$ASSET_ROOT/$LIB_DIR", File(themesDir, LIB_DIR))
        copyFontsIfMissing(context, fontsDir)

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
