// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.actions.handwriting

import android.content.Context
import android.content.SharedPreferences
import com.osfans.trime.TrimeApplication

/** 手写板的偏好设置（独立于输入法的其它设置）。 */
object HandwritingPrefs {
    private const val FILE = "bamboo_handwriting"

    private const val K_ACTIVE_VERSION = "active_version"
    private const val K_PAUSE_MS = "pause_ms"
    private const val K_MAX_CANDIDATES = "max_candidates"
    private const val K_PEN_WIDTH = "pen_width"

    private const val K_THEME_COLORS = "theme_colors"
    private const val K_INK_MODE = "ink_mode"
    private const val K_SHOW_PINYIN = "show_pinyin"
    private const val K_TOOL_ICONS = "tool_icons"

    /** 手写区配色：跟随主题（含对比度保底）。 */
    const val INK_THEME = "theme"

    /** 强制深色纸 + 浅色笔。 */
    const val INK_DARK = "dark"

    /** 强制浅色纸 + 深色笔。 */
    const val INK_LIGHT = "light"

    private var sp: SharedPreferences? = null
    private var inkModeCache: String? = null

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences {
        sp?.let { return it }
        val ctx = TrimeApplication.getInstance()
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).also { sp = it }
    }

    /** 当前使用的手写模型版本。 */
    var activeVersion: String
        get() = p().getString(K_ACTIVE_VERSION, HandwritingModelStore.VERSION_V1_1)
            ?: HandwritingModelStore.VERSION_V1_1
        set(value) = p().edit().putString(K_ACTIVE_VERSION, value).apply()

    /** 抬笔后多久开始识别（毫秒）。 */
    var pauseMs: Int
        get() = p().getInt(K_PAUSE_MS, 500).coerceIn(100, 3000)
        set(value) = p().edit().putInt(K_PAUSE_MS, value.coerceIn(100, 3000)).apply()

    /** 候选个数。 */
    var maxCandidates: Int
        get() = p().getInt(K_MAX_CANDIDATES, 12).coerceIn(4, 12)
        set(value) = p().edit().putInt(K_MAX_CANDIDATES, value.coerceIn(4, 12)).apply()

    /** 笔迹宽度（dp）。 */
    var penWidth: Int
        get() = p().getInt(K_PEN_WIDTH, 3).coerceIn(1, 16)
        set(value) = p().edit().putInt(K_PEN_WIDTH, value.coerceIn(1, 16)).apply()


    /** 手写板跟随主题配色（默认开）。 */
    var themeColors: Boolean
        get() = p().getBoolean(K_THEME_COLORS, true)
        set(value) = p().edit().putBoolean(K_THEME_COLORS, value).apply()

    /** 候选右侧显示读音（默认开）。 */
    var showPinyin: Boolean
        get() = p().getBoolean(K_SHOW_PINYIN, true)
        set(value) = p().edit().putBoolean(K_SHOW_PINYIN, value).apply()

    /**
     * 底部工具栏按钮显示图标（默认开）。
     *
     * 开＝返回/数字/空格/符号/收起/回车/退格都画成图标；关＝显示中文文字。
     * 图标来自 App 内置的 Community Material 图标字体，名字见 [TOOL_ICONS]。
     */
    var toolIcons: Boolean
        get() = p().getBoolean(K_TOOL_ICONS, true)
        set(value) = p().edit().putBoolean(K_TOOL_ICONS, value).apply()

    /**
     * 手写区配色模式：
     * - [INK_THEME]：跟随主题，对比度不足时自动翻纸色（默认）
     * - [INK_DARK]：强制深纸浅笔
     * - [INK_LIGHT]：强制浅纸深笔
     */
    var inkMode: String
        get() = inkModeCache ?: p().getString(K_INK_MODE, INK_THEME).let { v ->
            when (v) {
                INK_DARK, INK_LIGHT -> v
                else -> INK_THEME
            }
        }.also { inkModeCache = it }
        set(value) {
            val v = when (value) {
                INK_DARK, INK_LIGHT -> value
                else -> INK_THEME
            }
            inkModeCache = v
            p().edit().putString(K_INK_MODE, v).apply()
        }
}