/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import com.osfans.trime.TrimeApplication

/**
 * 桌面「截图浮球」的开关与位置。
 *
 * 和 [ShotAiPrefs] 分开：那是截图界面里的 AI 配置，这是常驻浮层的状态。
 * 位置按屏幕可用范围的**比例**存（0~1），换分辨率或横竖屏都不会跑到屏幕外。
 */
object ShotBallPrefs {
    private const val FILE = "bamboo_shot_ball"
    private const val K_ENABLED = "enabled"
    private const val K_POS_X = "pos_x"
    private const val K_POS_Y = "pos_y"

    private val prefs
        get() =
            TrimeApplication.getInstance()
                .getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(K_ENABLED, false)
        set(v) = prefs.edit().putBoolean(K_ENABLED, v).apply()

    /** 归一化横坐标；< 0 表示还没拖过，用默认位置。 */
    var posX: Float
        get() = prefs.getFloat(K_POS_X, -1f)
        set(v) = prefs.edit().putFloat(K_POS_X, v).apply()

    var posY: Float
        get() = prefs.getFloat(K_POS_Y, -1f)
        set(v) = prefs.edit().putFloat(K_POS_Y, v).apply()
}
