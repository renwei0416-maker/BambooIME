/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.osfans.trime.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.osfans.trime.TrimeApplication

/**
 * 设置界面外观的偏好。独立存储，不写进 Trime 的 default shared preferences，
 * 所以改动外观不会影响输入法本身的行为。
 */
object AppearancePrefs {
    private const val FILE = "bamboo_appearance"
    private const val K_FOLLOW_THEME = "follow_theme"
    private const val K_FORCE_MODE = "force_mode"
    private const val K_TOKENS_MIGRATED = "tokens_v1_migrated"

    /** 跟随当前键盘主题取色。 */
    const val MODE_THEME = 0

    /** 强制浅色。 */
    const val MODE_LIGHT = 1

    /** 强制深色。 */
    const val MODE_DARK = 2

    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences {
        sp?.let { return it }
        return TrimeApplication.getInstance().getSharedPreferences(FILE, Context.MODE_PRIVATE).also { sp = it }
    }

    /** 设置界面是否跟随键盘主题配色（默认开）。 */
    var followTheme: Boolean
        get() = p().getBoolean(K_FOLLOW_THEME, true)
        set(value) = p().edit().putBoolean(K_FOLLOW_THEME, value).apply()

    /** 不跟随时强制使用的深浅：0 跟随系统、1 浅、2 深。 */
    var forceMode: Int
        get() = p().getInt(K_FORCE_MODE, MODE_THEME).coerceIn(MODE_THEME, MODE_DARK)
        set(value) = p().edit().putInt(K_FORCE_MODE, value.coerceIn(MODE_THEME, MODE_DARK)).apply()

    /** 新版设置页（固定配色 + 默认深色）的一次性迁移是否已执行。 */
    var tokensMigrated: Boolean
        get() = p().getBoolean(K_TOKENS_MIGRATED, false)
        private set(value) = p().edit().putBoolean(K_TOKENS_MIGRATED, value).apply()

    /**
     * 3.9.x 迁移：老版本「跟随键盘主题配色」出厂就是开着的，而新版设置页默认不跟随主题
     * （只让强调色跟随）。如果不动它，老用户升级后看到的仍是"跟随主题"的效果，却找不到原因。
     * 这里只在第一次进入新版设置界面时改一次，之后完全尊重用户的选择。
     */
    fun migrateTokensOnce() {
        if (tokensMigrated) return
        val editor = p().edit().putBoolean(K_FOLLOW_THEME, false)
        if (forceMode == MODE_THEME) editor.putInt(K_FORCE_MODE, MODE_DARK)
        editor.putBoolean(K_TOKENS_MIGRATED, true).apply()
    }
}
