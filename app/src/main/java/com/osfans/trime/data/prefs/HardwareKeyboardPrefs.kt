// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.osfans.trime.TrimeApplication

/**
 * 外接键盘相关的配置。
 *
 * 单独一个 SharedPreferences 文件：这些项只服务硬件键盘下的浮动候选条与状态条，
 * 与 [AppPrefs] 里的通用候选设置分开，恢复默认时互不影响。
 */
object HardwareKeyboardPrefs {
    private const val FILE = "bamboo_hardware_keyboard"

    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences =
        sp ?: TrimeApplication.getInstance()
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .also { sp = it }

    /** 状态条总开关。 */
    var statusBarEnabled: Boolean
        get() = p().getBoolean("status_bar_enabled", true)
        set(value) = p().edit().putBoolean("status_bar_enabled", value).apply()

    /** 状态条上显示的项，五项各自独立。 */
    var showAscii: Boolean
        get() = p().getBoolean("status_ascii", true)
        set(value) = p().edit().putBoolean("status_ascii", value).apply()

    var showCaps: Boolean
        get() = p().getBoolean("status_caps", true)
        set(value) = p().edit().putBoolean("status_caps", value).apply()

    var showFullShape: Boolean
        get() = p().getBoolean("status_full_shape", true)
        set(value) = p().edit().putBoolean("status_full_shape", value).apply()

    var showPunct: Boolean
        get() = p().getBoolean("status_punct", true)
        set(value) = p().edit().putBoolean("status_punct", value).apply()

    /** 状态条上的设置入口。 */
    var showSettings: Boolean
        get() = p().getBoolean("status_settings", true)
        set(value) = p().edit().putBoolean("status_settings", value).apply()

    /** 状态条整体缩放，百分比；100 为默认大小（相当于最初样式的 80%）。 */
    var statusBarScale: Int
        get() = p().getInt("status_bar_scale", 100).coerceIn(70, 150)
        set(value) = p().edit().putInt("status_bar_scale", value.coerceIn(70, 150)).apply()

    /** 状态条是否收起成只剩中/英的一格。 */
    var statusBarCollapsed: Boolean
        get() = p().getBoolean("status_bar_collapsed", false)
        set(value) = p().edit().putBoolean("status_bar_collapsed", value).apply()

    /** 浮动候选条上最多显示几个候选。方案一次最多给 6 个，上限就定在 6。 */
    var candidateCount: Int
        get() = p().getInt("candidate_count", 4).coerceIn(1, 6)
        set(value) = p().edit().putInt("candidate_count", value.coerceIn(1, 6)).apply()

    /** 候选字的字号，百分比；100 即与主题一致。 */
    var candidateTextScale: Int
        get() = p().getInt("candidate_text_scale", 100).coerceIn(40, 200)
        set(value) = p().edit().putInt("candidate_text_scale", value.coerceIn(40, 200)).apply()

    /** 编码（preedit）的字号，百分比；100 即与主题一致。 */
    var preeditTextScale: Int
        get() = p().getInt("preedit_text_scale", 100).coerceIn(40, 200)
        set(value) = p().edit().putInt("preedit_text_scale", value.coerceIn(40, 200)).apply()

    /** 浮动候选条是否竖排（默认横排）。 */
    var candidateVertical: Boolean
        get() = p().getBoolean("candidate_vertical", false)
        set(value) = p().edit().putBoolean("candidate_vertical", value).apply()

    /** 候选条贴到光标下方时，若会盖住输入框就改为贴到上方。 */
    var candidateAvoidEditor: Boolean
        get() = p().getBoolean("candidate_avoid_editor", true)
        set(value) = p().edit().putBoolean("candidate_avoid_editor", value).apply()

    /** 接硬件键盘时强制把候选位置改成跟随光标（断开后恢复用户原设置）。 */
    var candidateFollowCursor: Boolean
        get() = p().getBoolean("candidate_follow_cursor", true)
        set(value) = p().edit().putBoolean("candidate_follow_cursor", value).apply()

    /** 状态条位置，按屏幕比例存，换分辨率不会跑偏。 */
    var statusBarX: Float
        get() = p().getFloat("status_bar_x", Float.NaN)
        set(value) = p().edit().putFloat("status_bar_x", value).apply()

    var statusBarY: Float
        get() = p().getFloat("status_bar_y", Float.NaN)
        set(value) = p().edit().putFloat("status_bar_y", value).apply()
}
