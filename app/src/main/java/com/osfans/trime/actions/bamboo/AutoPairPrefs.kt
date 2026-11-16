// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.actions.bamboo

import android.content.Context
import android.content.SharedPreferences
import com.osfans.trime.TrimeApplication

/**
 * 「输入增强」的偏好设置（成对符号补全，以后候选英文注释也放这里）。
 *
 * 独立于主题和 Rime 方案，改完立即生效，不需要部署、不需要重启输入法。
 */
object AutoPairPrefs {
    private const val FILE = "bamboo_input"

    private const val K_ENABLED = "auto_pair"
    private const val K_SKIP = "auto_pair_skip"
    private const val K_QUOTES = "auto_pair_quotes"
    private const val K_ALTERNATE = "quote_alternate"

    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences {
        sp?.let { return it }
        val ctx = TrimeApplication.getInstance()
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).also { sp = it }
    }

    /**
     * 成对符号自动补全总开关（默认关）。
     *
     * 开＝打「（」自动补上「）」，并把光标停在中间；打「）」而后面已经有一个「）」时只把光标挪过去。
     */
    var enabled: Boolean
        get() = p().getBoolean(K_ENABLED, false)
        set(value) = p().edit().putBoolean(K_ENABLED, value).apply()

    /** 光标后已经是对称的右半边时，只挪光标、不重复输入（默认开）。 */
    var skipRight: Boolean
        get() = p().getBoolean(K_SKIP, true)
        set(value) = p().edit().putBoolean(K_SKIP, value).apply()

    /** 引号也配对（“”「」『』'' ""，默认开；关掉只配对括号）。 */
    var quotes: Boolean
        get() = p().getBoolean(K_QUOTES, true)
        set(value) = p().edit().putBoolean(K_QUOTES, value).apply()

    /**
     * 引号左右轮替（默认开）。
     *
     * 开＝引号 token 把原键还给引擎，跟键盘上那个引号键一模一样，由 librime 自己左右交替；
     * 轮到左引号时 [AutoPair] 照常命中补全，轮到右引号时走「关掉重复的右半边」。
     * 关＝token 永远提交一个普通左引号，和别的成对符号一样只靠 [AutoPair] 补全。
     *
     * 这条开关只是选走哪条路，[AutoPair] 的补全逻辑一行都不用改。
     */
    var quoteAlternate: Boolean
        get() = p().getBoolean(K_ALTERNATE, true)
        set(value) = p().edit().putBoolean(K_ALTERNATE, value).apply()
}
