/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.glossary

import android.content.Context
import android.content.SharedPreferences
import com.osfans.trime.TrimeApplication

/**
 * 「候选英文」的设置（独立 SharedPreferences：`bamboo_glossary`）。
 *
 * 设计原则：打包时一个义项都不丢，「显示什么、显示多少」全部在这里决定，改完立即生效，
 * 不用部署方案、不用重启输入法。
 */
object GlossaryPrefs {
    private const val FILE = "bamboo_glossary"

    /** 默认下载地址（发布资产；用户可在设置里改成任意镜像）。 */
    const val DEFAULT_DOWNLOAD_URL =
        "https://github.com/renwei0416-maker/BambooIME/releases/download/glossary-en-v1/glossary-en-cedict-1.0.bge"

    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences =
        sp ?: TrimeApplication.getInstance()
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .also { sp = it }

    /** 总开关：候选后面要不要显示英文。 */
    var enabled: Boolean
        get() = p().getBoolean("enabled", false)
        set(v) = p().edit().putBoolean("enabled", v).apply()

    /** 学习条最多显示几条（1–6）。 */
    var tipsMax: Int
        get() = p().getInt("tips_max", 3).coerceIn(1, 6)
        set(v) = p().edit().putInt("tips_max", v.coerceIn(1, 6)).apply()

    /** 和文曲星同时开启时怎么办，见 [GlossaryTips.CONFLICT_AUTO]。 */
    var conflict: Int
        get() = p().getInt("conflict", GlossaryTips.CONFLICT_AUTO).coerceIn(0, 2)
        set(v) = p().edit().putInt("conflict", v.coerceIn(0, 2)).apply()

    /** 显示几个义项：1/2/3，4 表示全部。 */
    var senseCount: Int
        get() = p().getInt("sense_count", 2).coerceIn(1, 4)
        set(v) = p().edit().putInt("sense_count", v.coerceIn(1, 4)).apply()

    /** 每条义项最多显示多少字符（8–70）。 */
    var maxChars: Int
        get() = p().getInt("max_chars", 20).coerceIn(8, 70)
        set(v) = p().edit().putInt("max_chars", v.coerceIn(8, 70)).apply()

    /** 提示块的字号，相对编码行的百分比（50–150）。 */
    var tipsTextScale: Int
        get() = p().getInt("tips_text_scale", 90).coerceIn(50, 150)
        set(v) = p().edit().putInt("tips_text_scale", v.coerceIn(50, 150)).apply()

    /** 提示块背景的不透明度（0–100），默认 90。 */
    var tipsAlpha: Int
        get() = p().getInt("tips_alpha", 90).coerceIn(0, 100)
        set(v) = p().edit().putInt("tips_alpha", v.coerceIn(0, 100)).apply()

    /** 查不到英文的那些行，中文用多少不透明度（0–100），默认 80。 */
    var unmatchedAlpha: Int
        get() = p().getInt("unmatched_alpha", 80).coerceIn(0, 100)
        set(v) = p().edit().putInt("unmatched_alpha", v.coerceIn(0, 100)).apply()

    /** 显示词性（v. / n. …）。 */
    var showPos: Boolean
        get() = p().getBoolean("show_pos", true)
        set(v) = p().edit().putBoolean("show_pos", v).apply()

    /** 显示语体标签（书面/口语/俚语/方言…）。 */
    var showStyle: Boolean
        get() = p().getBoolean("show_style", true)
        set(v) = p().edit().putBoolean("show_style", v).apply()

    /** 显示领域标签（计算机/数学/中医…）。 */
    var showDomain: Boolean
        get() = p().getBoolean("show_domain", false)
        set(v) = p().edit().putBoolean("show_domain", v).apply()

    /** 显示量词（CL: 个）。 */
    var showCl: Boolean
        get() = p().getBoolean("show_cl", true)
        set(v) = p().edit().putBoolean("show_cl", v).apply()

    /** 下载地址。 */
    var downloadUrl: String
        get() = p().getString("download_url", DEFAULT_DOWNLOAD_URL) ?: DEFAULT_DOWNLOAD_URL
        set(v) = p().edit().putString("download_url", v.ifBlank { DEFAULT_DOWNLOAD_URL }).apply()

    fun resetToDefault() {
        p().edit()
            .putBoolean("enabled", false)
            .putInt("tips_max", 3)
            .putInt("conflict", GlossaryTips.CONFLICT_AUTO)
            .putInt("sense_count", 2)
            .putInt("max_chars", 20)
            .putInt("tips_text_scale", 90)
            .putInt("tips_alpha", 90)
            .putInt("unmatched_alpha", 80)
            .putBoolean("show_pos", true)
            .putBoolean("show_style", true)
            .putBoolean("show_domain", false)
            .putBoolean("show_cl", true)
            .putString("download_url", DEFAULT_DOWNLOAD_URL)
            .apply()
    }
}
