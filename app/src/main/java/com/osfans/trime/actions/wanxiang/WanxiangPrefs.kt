/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.wanxiang

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 万象拼音方案的用户偏好（独立于设置界面存的文件，只放「要写进 custom 的那几个值」）。
 *
 * 口径：
 *  * **一律以官方默认为默认值**，官方关掉的一律关（`userDict` / `contextual` 默认 false）；
 *  * 辅助码默认关闭（用户要求），只有手动打开才写进 custom；
 *  * 九键那一侧保持官方 schema 的值（`userDict` 默认 true），单独一组键[KEY_T9_*]。
 */
object WanxiangPrefs {
    private const val SP = "bamboo_wanxiang"
    private lateinit var p: SharedPreferences

    // ---- 布局：26 / 18 / 14 / 9
    const val LAYOUT_26 = 26
    const val LAYOUT_18 = 18
    const val LAYOUT_14 = 14
    const val LAYOUT_9 = 9

    // ---- 编码方式
    const val ENCODE_QUANPIN = "quanpin"
    const val ENCODE_SHUANGPIN = "shuangpin"

    // ---- 辅助码
    const val AUX_NONE = 0
    const val AUX_DIRECT = 1
    const val AUX_INDIRECT = 2

    /** 双拼方案（必须是 `wanxiang_algebra.yaml` 里 `pro:` 下的节点名，不含「全拼」）。 */
    val SCHEMES = listOf(
        "小鹤双拼", "自然码", "搜狗双拼", "微软双拼", "智能ABC", "紫光双拼", "拼音加加",
        "国标双拼", "乱序17", "蓝天双拼", "大牛双拼", "首道双拼", "万象双拼",
    )

    /** 模糊音键名（对应 `wanxiang_algebra:/模糊音_<key>`）。 */
    val FUZZY_KEYS = listOf("nl", "ry", "hf", "rl", "kg", "en_eng", "in_ing", "c_ch", "z_zh", "s_sh")
    val FUZZY_LABELS = mapOf(
        "nl" to "n / l", "ry" to "r / l", "hf" to "h / f", "rl" to "r / l 混",
        "kg" to "k / g", "en_eng" to "en / eng", "in_ing" to "in / ing",
        "c_ch" to "c / ch", "z_zh" to "z / zh", "s_sh" to "s / sh",
    )

    private const val KEY_LAYOUT = "layout"
    private const val KEY_ENCODE = "encode"
    private const val KEY_SCHEME = "scheme"
    private const val KEY_AUX = "aux"
    private const val KEY_PAGE_SIZE = "page_size"
    private const val KEY_USER_DICT = "user_dict"
    private const val KEY_CONTEXTUAL = "contextual"
    private const val KEY_FUZZY = "fuzzy"
    private const val KEY_WIZARD_DONE = "wizard_done"
    private const val KEY_APPLIED = "applied_signature"

    private const val KEY_T9_PAGE_SIZE = "t9_page_size"
    private const val KEY_T9_USER_DICT = "t9_user_dict"
    private const val KEY_T9_CONTEXTUAL = "t9_contextual"

    fun init(context: Context) {
        if (::p.isInitialized) return
        p = context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
    }

    private fun ready(): SharedPreferences {
        check(::p.isInitialized) { "WanxiangPrefs.init() 未调用" }
        return p
    }

    var layout: Int
        get() = ready().getInt(KEY_LAYOUT, LAYOUT_26)
        set(v) = ready().edit { putInt(KEY_LAYOUT, v) }

    var encode: String
        get() = ready().getString(KEY_ENCODE, ENCODE_SHUANGPIN).orEmpty()
        set(v) = ready().edit { putString(KEY_ENCODE, v) }

    var scheme: String
        get() = ready().getString(KEY_SCHEME, SCHEMES.first()).orEmpty()
        set(v) = ready().edit { putString(KEY_SCHEME, v) }

    var aux: Int
        get() = ready().getInt(KEY_AUX, AUX_NONE)
        set(v) = ready().edit { putInt(KEY_AUX, v) }

    var pageSize: Int
        get() = ready().getInt(KEY_PAGE_SIZE, 6).coerceIn(3, 6)
        set(v) = ready().edit { putInt(KEY_PAGE_SIZE, v.coerceIn(3, 6)) }

    /** 用户词典/自动调频。官方默认 false，这里也默认 false。 */
    var userDict: Boolean
        get() = ready().getBoolean(KEY_USER_DICT, false)
        set(v) = ready().edit { putBoolean(KEY_USER_DICT, v) }

    /** 智能调频（上下文预测）。官方默认 false。 */
    var contextual: Boolean
        get() = ready().getBoolean(KEY_CONTEXTUAL, false)
        set(v) = ready().edit { putBoolean(KEY_CONTEXTUAL, v) }

    var fuzzy: Set<String>
        get() = ready().getStringSet(KEY_FUZZY, emptySet()).orEmpty().toSet()
        set(v) = ready().edit { putStringSet(KEY_FUZZY, v) }

    var wizardDone: Boolean
        get() = ready().getBoolean(KEY_WIZARD_DONE, false)
        set(v) = ready().edit { putBoolean(KEY_WIZARD_DONE, v) }

    /** 上次「应用并部署」时的状态指纹；与 [signature] 不等即表示「有未应用的改动」。 */
    var appliedSignature: String
        get() = ready().getString(KEY_APPLIED, "").orEmpty()
        set(v) = ready().edit { putString(KEY_APPLIED, v) }

    var t9PageSize: Int
        get() = ready().getInt(KEY_T9_PAGE_SIZE, 6).coerceIn(3, 9)
        set(v) = ready().edit { putInt(KEY_T9_PAGE_SIZE, v.coerceIn(3, 9)) }

    var t9UserDict: Boolean
        get() = ready().getBoolean(KEY_T9_USER_DICT, true)
        set(v) = ready().edit { putBoolean(KEY_T9_USER_DICT, v) }

    var t9Contextual: Boolean
        get() = ready().getBoolean(KEY_T9_CONTEXTUAL, false)
        set(v) = ready().edit { putBoolean(KEY_T9_CONTEXTUAL, v) }

    /** 当前状态的可比较指纹（不含「上次应用」本身）。 */
    fun signature(): String = buildString {
        append(layout).append('|').append(encode).append('|').append(scheme).append('|')
        append(aux).append('|').append(pageSize).append('|')
        append(userDict).append('|').append(contextual).append('|')
        append(fuzzy.sorted().joinToString(",")).append('|')
        append(t9PageSize).append('|').append(t9UserDict).append('|').append(t9Contextual)
    }

    fun hasPendingChanges(): Boolean = signature() != appliedSignature

    /**
     * 把互斥项收干净（改任何设置后都调一次）：
     * - 九键没有字母键：编码固定全拼、辅助码与模糊音都用不上；
     * - 关掉用户词典后，智能调频没有依据，一并关掉。
     */
    fun sanitize() {
        if (layout == LAYOUT_9) {
            encode = ENCODE_QUANPIN
            aux = AUX_NONE
            fuzzy = emptySet()
        }
        if (!userDict) contextual = false
        if (!t9UserDict) t9Contextual = false
    }

    fun resetToDefaults() {
        ready().edit {
            putInt(KEY_LAYOUT, LAYOUT_26)
            putString(KEY_ENCODE, ENCODE_SHUANGPIN)
            putString(KEY_SCHEME, SCHEMES.first())
            putInt(KEY_AUX, AUX_NONE)
            putInt(KEY_PAGE_SIZE, 6)
            putBoolean(KEY_USER_DICT, false)
            putBoolean(KEY_CONTEXTUAL, false)
            putStringSet(KEY_FUZZY, emptySet())
            putInt(KEY_T9_PAGE_SIZE, 6)
            putBoolean(KEY_T9_USER_DICT, true)
            putBoolean(KEY_T9_CONTEXTUAL, false)
        }
    }
}
