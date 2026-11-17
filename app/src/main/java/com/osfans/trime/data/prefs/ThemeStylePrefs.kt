/*
 * SPDX-FileCopyrightText: 2026 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.prefs

import android.content.SharedPreferences
import com.osfans.trime.util.appContext

/**
 * 「主题样式」编辑器的界面偏好（预览高度、预览模式、预览用的键盘/配色）。
 *
 * 这些只是编辑器自己的状态，不属于主题数据；主题改动存在
 * `BambooThemeOverrides`（`bamboo/theme-overrides/<id>.json`）里。
 */
object ThemeStylePrefs {
    private const val SP = "bamboo_theme_style"
    private const val K_PREVIEW_HEIGHT = "preview_height"
    private const val K_PREVIEW_MODE = "preview_mode"
    private const val K_PREVIEW_KB = "preview_keyboard"
    private const val K_PREVIEW_SCHEME = "preview_scheme"

    const val HEIGHT_DEFAULT = 200
    const val HEIGHT_MIN = 96
    const val HEIGHT_MAX = 420
    const val HEIGHT_COLLAPSED = 56

    private val p: SharedPreferences by lazy {
        appContext.getSharedPreferences(SP, android.content.Context.MODE_PRIVATE)
    }

    /** 预览区高度（dp）。 */
    var previewHeight: Int
        get() = p.getInt(K_PREVIEW_HEIGHT, HEIGHT_DEFAULT).coerceIn(HEIGHT_MIN, HEIGHT_MAX)
        set(value) = p.edit().putInt(K_PREVIEW_HEIGHT, value.coerceIn(HEIGHT_MIN, HEIGHT_MAX)).apply()

    /** 0 = 等比，1 = 1:1 裁剪。 */
    var previewMode: Int
        get() = p.getInt(K_PREVIEW_MODE, 0)
        set(value) = p.edit().putInt(K_PREVIEW_MODE, value).apply()

    var previewKeyboard: String
        get() = p.getString(K_PREVIEW_KB, "default") ?: "default"
        set(value) = p.edit().putString(K_PREVIEW_KB, value).apply()

    var previewScheme: String
        get() = p.getString(K_PREVIEW_SCHEME, "") ?: ""
        set(value) = p.edit().putString(K_PREVIEW_SCHEME, value).apply()
}
