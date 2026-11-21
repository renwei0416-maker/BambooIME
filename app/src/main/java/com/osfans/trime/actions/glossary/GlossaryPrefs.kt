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
    /** 学习语言：.bge 头部里的 lang 字段（'en' / 'ja'）。 */
    /** 显示模式：始终把候选都列出来（没命中的只显示中文）。 */
    const val MODE_ALWAYS = 0

    /** 显示模式：只列有英文的候选，没命中的整条不出现。 */
    const val MODE_HITS_ONLY = 1

    const val LANG_EN = "en"
    const val LANG_JA = "ja"

    /** 对应 .bge 头部的 lang 编码（大端字符值）。 */
    fun langCodeOf(lang: String): Int = when (lang) {
        LANG_JA -> 0x00006A61
        else -> 0x00006E65
    }

    const val DEFAULT_DOWNLOAD_URL_JA =
        "https://github.com/renwei0416-maker/BambooIME/releases/download/glossary-ja-v1/glossary-ja-v1.bge"

    const val DEFAULT_DOWNLOAD_URL_JA_CNB =
        "https://cnb.cool/renwei.co/BambooIME/-/releases/download/glossary-ja-v1/glossary-ja-v1.bge"

    const val DEFAULT_DOWNLOAD_URL_CNB =
        "https://cnb.cool/renwei.co/BambooIME/-/releases/download/glossary-en-v1/glossary-en-cedict-1.0.bge"

    /** 当前语言对应的默认下载地址。 */
    fun defaultDownloadUrlFor(lang: String): String =
        if (lang == LANG_JA) DEFAULT_DOWNLOAD_URL_JA else DEFAULT_DOWNLOAD_URL

    /** GitHub 源（上游发布资产）。 */
    fun githubUrlFor(lang: String): String =
        if (lang == LANG_JA) DEFAULT_DOWNLOAD_URL_JA else DEFAULT_DOWNLOAD_URL

    /** 两源的字节数（同一份文件）：用来判断下载是否完整。 */
    fun expectedSizeFor(lang: String): Long =
        if (lang == LANG_JA) 16_505_046L else 8_472_162L

    /** CNB 国内镜像源，与 GitHub 同一份文件。 */
    fun cnbUrlFor(lang: String): String =
        if (lang == LANG_JA) DEFAULT_DOWNLOAD_URL_JA_CNB else DEFAULT_DOWNLOAD_URL_CNB

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
    /** 一次只学一门语言；只加载 lang 匹配的词库包。 */
    /** 显示模式，见 [MODE_ALWAYS] / [MODE_HITS_ONLY]。 */
    var displayMode: Int
        get() = p().getInt("display_mode", MODE_ALWAYS).coerceIn(0, 1)
        set(value) = p().edit().putInt("display_mode", value.coerceIn(0, 1)).apply()

    var lang: String
        get() = p().getString("lang", LANG_EN) ?: LANG_EN
        set(v) = p().edit().putString("lang", if (v == LANG_JA) LANG_JA else LANG_EN).apply()

    var enabled: Boolean
        get() = p().getBoolean("enabled", false)
        set(v) = p().edit().putBoolean("enabled", v).apply()

    /** 开关码：打出这个码并终结即开关外语学习（默认 `wy;`）。与「回退与AI」总开关无关。 */
    var code: String
        get() = p().getString("switch_code", "wy;") ?: "wy;"
        set(v) = p().edit().putString("switch_code", v.trim().ifEmpty { "wy;" }).apply()

    /** 学习条最多显示几条（1–6）。 */
    var tipsMax: Int
        get() = p().getInt("tips_max", 4).coerceIn(1, 6)
        set(v) = p().edit().putInt("tips_max", v.coerceIn(1, 6)).apply()


    /** 显示几个义项：1/2/3，4 表示全部。 */
    var senseCount: Int
        get() = p().getInt("sense_count", 5).coerceIn(1, 8)
        set(v) = p().edit().putInt("sense_count", v.coerceIn(1, 8)).apply()

    /** 每条义项最多显示多少字符（8–70）。 */
    /** 每条最多显示几行（1~3）：不再按字符数截断，由真实排版决定，宽度富裕就多显示。 */
    var maxLines: Int
        get() = p().getInt("max_lines", 2).coerceIn(1, 3)
        set(v) = p().edit().putInt("max_lines", v.coerceIn(1, 3)).apply()

    /** 提示块的字号，相对编码行的百分比（50–150）。 */
    var tipsTextScale: Int
        get() = p().getInt("tips_text_scale", 90).coerceIn(50, 150)
        set(v) = p().edit().putInt("tips_text_scale", v.coerceIn(50, 150)).apply()

    /** 提示块背景的不透明度（0–100），默认 90。 */
    var tipsAlpha: Int
        get() = p().getInt("tips_alpha", 90).coerceIn(0, 100)
        set(v) = p().edit().putInt("tips_alpha", v.coerceIn(0, 100)).apply()

    /** 行尾「·N字」：这一条点下去会提交多少字。 */
    var showCharCount: Boolean
        get() = p().getBoolean("show_char_count", true)
        set(v) = p().edit().putBoolean("show_char_count", v).apply()

    /** 查不到英文的那些行，中文用多少不透明度（0–100），默认 80。 */
    var unmatchedAlpha: Int
        get() = p().getInt("unmatched_alpha", 80).coerceIn(0, 100)
        set(v) = p().edit().putInt("unmatched_alpha", v.coerceIn(0, 100)).apply()

    /** 查不到英文的那些行，字号相对上面「字号」的百分比（50–150），默认 100。 */
    var unmatchedTextScale: Int
        get() = p().getInt("unmatched_text_scale", 100).coerceIn(50, 150)
        set(v) = p().edit().putInt("unmatched_text_scale", v.coerceIn(50, 150)).apply()

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

    /** 要下载哪一份外文词库：日语、英语（内置一份，也允许重新下载覆盖）或自定义地址。 */
    const val PACK_JA = "ja"
    const val PACK_EN = "en"
    const val PACK_CUSTOM = "custom"

    var downloadPack: String
        get() = p().getString("download_pack", PACK_JA) ?: PACK_JA
        set(v) = p().edit().putString(
            "download_pack",
            when (v) {
                PACK_EN -> PACK_EN
                PACK_CUSTOM -> PACK_CUSTOM
                else -> PACK_JA
            },
        ).apply()

    /** 下拉选项对应的语言；自定义地址返回 null（语言由装进来的包自己决定）。 */
    fun langForPack(pack: String): String? = when (pack) {
        PACK_JA -> LANG_JA
        PACK_EN -> LANG_EN
        else -> null
    }

    /** 自定义地址（镜像）里的那个 URL；留空表示还没填。 */
    var customDownloadUrl: String
        get() = p().getString("download_url", "") ?: ""
        set(v) = p().edit().putString("download_url", v.trim()).apply()

    /**
     * 当前实际要下载的地址。
     *
     * 选了「日语词库」就固定用发布资产里的地址（用户改过的自定义地址不参与），
     * 这样切语言/切下拉不会因为残留的旧字符串下错包——这是 3.2.0 的 bug。
     */
    var downloadUrl: String
        get() = if (downloadPack == PACK_CUSTOM) {
            customDownloadUrl.ifBlank { DEFAULT_DOWNLOAD_URL_JA }
        } else {
            defaultDownloadUrlFor(langForPack(downloadPack) ?: LANG_JA)
        }
        set(v) {
            customDownloadUrl = v
        }

    /** 下拉里「恢复默认地址」用：清掉自定义地址，回到日语官方地址。 */
    fun resetDownloadUrl() {
        p().edit().putString("download_pack", PACK_JA).putString("download_url", "").apply()
    }

    fun resetToDefault() {
        p().edit()
            .putBoolean("enabled", false)
            .putString("lang", LANG_EN)
            .putInt("tips_max", 4)
            .putInt("sense_count", 5)
            .putInt("max_lines", 2)
            .putInt("tips_text_scale", 90)
            .putInt("tips_alpha", 90)
            .putBoolean("show_char_count", true)
            .putInt("unmatched_alpha", 80)
            .putInt("unmatched_text_scale", 100)
            .putBoolean("show_pos", true)
            .putBoolean("show_style", true)
            .putBoolean("show_domain", false)
            .putBoolean("show_cl", true)
            .putString("download_pack", PACK_JA)
            .putString("download_url", "")
            .apply()
    }
}
