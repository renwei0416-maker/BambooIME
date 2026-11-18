/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * 内存占用相关设置：空闲后释放三个模型（语音 / 手写 / 拍照识字）。
 *
 * 独立存一个 SharedPreferences（`bamboo_memory`），与其它模块的设置互不影响；
 * 全部有默认值，缺失时按默认行为运行。
 */
object MemoryPrefs {
    private const val STORE = "bamboo_memory"

    private const val KEY_IDLE_RELEASE = "idle_release"
    private const val KEY_KEEP_ALIVE_MIN = "keep_alive_min"
    private const val KEY_VOICE_PIN = "voice_pin"

    /** 保留时长的可选范围（分钟）。 */
    const val KEEP_ALIVE_MIN = 1

    const val KEEP_ALIVE_MAX = 10

    /** 保留时长默认值（分钟）。 */
    const val KEEP_ALIVE_DEFAULT = 4

    private lateinit var sp: SharedPreferences

    /** 记录模型上次加载时实测到的内存增量（MB）；0 表示尚未测量。 */
    fun measuredMb(name: String): Int = if (::sp.isInitialized) sp.getInt("measured_$name", 0) else 0

    fun setMeasuredMb(name: String, mb: Int) {
        if (!::sp.isInitialized || mb <= 0) return
        sp.edit().putInt("measured_$name", mb).apply()
    }

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    }

    /** 空闲后是否释放模型；关闭后所有模型常驻内存。 */
    var idleRelease: Boolean
        get() = if (::sp.isInitialized) sp.getBoolean(KEY_IDLE_RELEASE, true) else true
        set(value) {
            if (::sp.isInitialized) sp.edit().putBoolean(KEY_IDLE_RELEASE, value).apply()
        }

    /** 空闲多久释放（分钟），限制在 1..10。 */
    var keepAliveMinutes: Int
        get() {
            if (!::sp.isInitialized) return KEEP_ALIVE_DEFAULT
            return sp.getInt(KEY_KEEP_ALIVE_MIN, KEEP_ALIVE_DEFAULT).coerceIn(KEEP_ALIVE_MIN, KEEP_ALIVE_MAX)
        }
        set(value) {
            if (!::sp.isInitialized) return
            sp.edit()
                .putInt(KEY_KEEP_ALIVE_MIN, value.coerceIn(KEEP_ALIVE_MIN, KEEP_ALIVE_MAX))
                .apply()
        }

    /** 语音模型常驻：开启后语音不参与空闲释放。 */
    var voicePinned: Boolean
        get() = if (::sp.isInitialized) sp.getBoolean(KEY_VOICE_PIN, false) else false
        set(value) {
            if (::sp.isInitialized) sp.edit().putBoolean(KEY_VOICE_PIN, value).apply()
        }
}
