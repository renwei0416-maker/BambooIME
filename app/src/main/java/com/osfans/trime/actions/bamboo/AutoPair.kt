// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.actions.bamboo

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * 成对符号自动补全。
 *
 * 只处理「一次提交一个字符」的情况，两件事：
 * 1. 左半边（`（` `「` `【` …）→ 连右半边一起提交，调用方随后把光标退回一格，停在中间；
 * 2. 右半边（`）` `」` `】` …）而光标后面**已经**是同一个字符 → 不提交，只把光标挪过去，
 *    这样「（ → 补全 → 打内容 → 再打 ）」不会变成「（））」。
 *
 * 明确不介入的场合：一次提交多个字符（整句上屏、剪贴板、AI 结果）、正在组句（交给 Rime 自己处理）、
 * 密码框、以及总开关关掉时。
 */
object AutoPair {
    /** 一次提交的处置方式。 */
    sealed interface Action {
        /** 原样提交。 */
        data object Pass : Action

        /** 不提交，只把光标右移一格。 */
        data object SkipRight : Action

        /** 提交 [text]，然后把光标左移 [caretBack] 格。 */
        data class Insert(
            val text: String,
            val caretBack: Int,
        ) : Action
    }

    /** 左半边 → 右半边。成对表里同一字符出现在两边表示「左右同形」（直引号）。 */
    private val PAIRS: Map<Char, Char> =
        mapOf(
            // 全角圆括号 / 方括号 / 花括号
            '（' to '）',
            '［' to '］',
            '｛' to '｝',
            // 书名号、尖括号
            '《' to '》',
            '〈' to '〉',
            // 直角引号 / 双直角引号（中文引号的常用写法）
            '「' to '」',
            '『' to '』',
            // 中括号（六角括号 / 空心方头括号）
            '【' to '】',
            '〔' to '〕',
            '〖' to '〗',
            // 弯引号
            '“' to '”',
            '‘' to '’',
            // 半角
            '(' to ')',
            '[' to ']',
            '{' to '}',
            '"' to '"',
            '\'' to '\'',
        )

    /** 属于「引号」一类：关掉引号配对时既不补全、也不跳过。 */
    private val QUOTES = setOf('“', '”', '‘', '’', '"', '\'', '「', '」', '『', '』')

    /** 所有可作「右半边」的字符。 */
    private val RIGHT_HALVES: Set<Char> = PAIRS.values.toSet()

    /**
     * 决定这次提交怎么处理。
     *
     * @param ic 当前输入连接，用来读光标后一个字符
     * @param text 本次要提交的文本
     * @param info 当前编辑框信息，用来避开密码框
     */
    fun plan(
        ic: InputConnection,
        text: String,
        info: EditorInfo?,
    ): Action {
        if (text.length != 1) return Action.Pass
        if (!AutoPairPrefs.enabled) return Action.Pass

        val ch = text[0]
        val isQuote = ch in QUOTES
        if (isQuote && !AutoPairPrefs.quotes) return Action.Pass
        if (isPassword(info)) return Action.Pass

        // 右半边重复：后面已经有一个一样的，就只把光标挪过去。
        // 这一步要在「补全」之前判断，否则直引号（左右同形）会一直往后长。
        if (AutoPairPrefs.skipRight && ch in RIGHT_HALVES) {
            val after =
                runCatching { ic.getTextAfterCursor(1, 0)?.toString() }.getOrNull()
            if (after != null && after.length == 1 && after[0] == ch) return Action.SkipRight
        }

        val right = PAIRS[ch] ?: return Action.Pass
        return Action.Insert("$ch$right", 1)
    }

    /** 密码框不补全（补了会泄露字符位数）。 */
    private fun isPassword(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            -> true
            else -> false
        }
    }
}
