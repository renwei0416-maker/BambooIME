/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.segments

import android.os.Build

/**
 * 把一段任意文本切成可以点选的小块（剪贴板的「分词」用）。
 *
 * **不变量**：块按原文顺序排列，全部拼起来**正好是原文**（空格、制表符、换行、缩进都在）。
 * 所以挑几块拼起来，就是那几块的原样；全选等于原文。
 *
 * 切法：
 * - 换行 `\r\n` / `\n` / `\r` 各成一块；连着的一串空格、制表符成一块；
 * - 汉字连续段交给 [hanSplit]（默认用系统 ICU 词典），切不动就退化成一字一块；
 * - 不带空白的 ASCII 串：
 *   - 链接、邮箱、路径（含 `/ . @ :` 且有字母）整块，冒号斜杠不拆；
 *   - 数字之间夹单个 `- . , : / +` 的仍算一个数（电话、日期、时间、千分位、小数），`+86` 也整块；
 *   - 连着的同一个运算符合成一块（`//` `==` `&&`），括号引号不合并；
 *   - 其余按单词、标点各成一块；
 * - 其它字符（中文标点、emoji 等）一个字符一块。
 *
 * 设计目标：空白也是块、不丢信息、链接电话不被切碎，切完拼回去必须等于原文。
 */
object PieceTokenizer {
    /** 链接、邮箱、路径常见的连接符：串里有这些就整块不拆。 */
    private const val LINK_MARKS = "/.@:"

    /** 夹在两段数字之间仍算同一个数的符号。 */
    private const val NUMBER_JOINERS = "-.,:/+"

    /** 连着出现时合成一块的运算符（括号、引号不合，`((` 还是两块）。 */
    private const val REPEATABLE = "/=-+*&|<>:.!?#%~"

    // 上面三张表按字符查，转成集合避免在热循环里反复构造
    private val LINK_MARKS_SET: Set<Int> = LINK_MARKS.map { it.code }.toHashSet()
    private val NUMBER_JOINERS_SET: Set<Int> = NUMBER_JOINERS.map { it.code }.toHashSet()
    private val REPEATABLE_SET: Set<Int> = REPEATABLE.map { it.code }.toHashSet()

    /**
     * 切块。
     *
     * @param text 原文本
     * @param hanSplit 汉字连续段的切词器；返回 null 表示切不动，调用方退化成一字一块。
     */
    internal fun split(
        text: String,
        hanSplit: (String) -> List<String>? = ::icuHanSplit,
    ): List<String> {
        if (text.isEmpty()) return emptyList()

        val cps = text.codePoints().toArray()
        val out = ArrayList<String>(cps.size.coerceAtLeast(8))
        var i = 0
        while (i < cps.size) {
            val c = cps[i]
            when {
                // \r\n 算一个换行块
                c == CR && i + 1 < cps.size && cps[i + 1] == LF -> {
                    out.add(sub(cps, i, i + 2))
                    i += 2
                }

                c == LF || c == CR -> {
                    out.add(sub(cps, i, i + 1))
                    i += 1
                }

                isBlank(c) -> {
                    val end = runEnd(cps, i) { isBlank(it) && it != LF && it != CR }
                    out.add(sub(cps, i, end))
                    i = end
                }

                isHan(c) -> {
                    val end = runEnd(cps, i, cps.size, ::isHan)
                    val run = sub(cps, i, end)
                    val parts = runCatching { hanSplit(run) }.getOrNull()
                    // 切词器必须连续覆盖整段，否则不变量就破了 —— 宁可退化成一字一块
                    if (parts != null && parts.isNotEmpty() && parts.joinToString("") == run) {
                        out.addAll(parts)
                    } else {
                        var k = i
                        while (k < end) {
                            out.add(sub(cps, k, k + 1))
                            k += 1
                        }
                    }
                    i = end
                }

                isAsciiGraphic(c) -> {
                    val end = runEnd(cps, i, cps.size, ::isAsciiGraphic)
                    asciiPieces(cps, i, end, out)
                    i = end
                }

                else -> {
                    out.add(sub(cps, i, i + 1))
                    i += 1
                }
            }
        }
        return out
    }

    /** 一串不带空白的 ASCII：像链接就去掉首尾括号引号后整块，否则拆成数、单词与标点。 */
    private fun asciiPieces(
        cps: IntArray,
        from: Int,
        to: Int,
        out: MutableList<String>,
    ) {
        var bodyStart = to
        for (k in from until to) {
            if (isWord(cps[k])) {
                bodyStart = k
                break
            }
        }
        var bodyEnd = bodyStart
        for (k in to - 1 downTo from) {
            if (isWord(cps[k])) {
                bodyEnd = k + 1
                break
            }
        }

        if (bodyStart < bodyEnd) {
            var hasLinkMark = false
            var hasLetter = false
            for (k in bodyStart until bodyEnd) {
                val ch = cps[k]
                if (ch in LINK_MARKS_SET) hasLinkMark = true
                if (Character.isLetter(ch)) hasLetter = true
                if (hasLinkMark && hasLetter) break
            }
            if (hasLinkMark && hasLetter) {
                for (k in from until bodyStart) out.add(sub(cps, k, k + 1))
                out.add(sub(cps, bodyStart, bodyEnd))
                for (k in bodyEnd until to) out.add(sub(cps, k, k + 1))
                return
            }
        }

        var i = from
        while (i < to) {
            val numberEnd = numberEnd(cps, i, to)
            val end =
                when {
                    numberEnd != null -> numberEnd
                    isWord(cps[i]) -> runEnd(cps, i, to, ::isWord)
                    cps[i] in REPEATABLE_SET -> runEnd(cps, i, to) { it == cps[i] }
                    else -> i + 1
                }
            out.add(sub(cps, i, end))
            i = end
        }
    }

    /**
     * 从 [start] 起是一个数（可带开头的 `+`）就返回它的结尾：
     * 数字段之间夹单个 [NUMBER_JOINERS] 仍算一个数；数后面紧跟字母（`3rd`、`utf8`）
     * 就整个当单词处理，返回 null。
     */
    private fun numberEnd(
        cps: IntArray,
        start: Int,
        to: Int,
    ): Int? {
        var i = start
        if (i < to && cps[i] == '+'.code) i += 1
        if (i >= to || !isAsciiDigit(cps[i])) return null
        while (true) {
            i = runEnd(cps, i, to, ::isAsciiDigit)
            val joined =
                i < to &&
                    cps[i] in NUMBER_JOINERS_SET &&
                    i + 1 < to &&
                    isAsciiDigit(cps[i + 1])
            if (!joined) break
            i += 1
        }
        if (i < to && isWord(cps[i])) return null
        return i
    }

    /** 从 [start] 起，第一个不满足 [keep] 的下标（不超过 [to]）。 */
    private fun runEnd(
        cps: IntArray,
        start: Int,
        to: Int = cps.size,
        keep: (Int) -> Boolean,
    ): Int {
        var i = start
        while (i < to && keep(cps[i])) i += 1
        return i
    }

    private fun sub(
        cps: IntArray,
        from: Int,
        to: Int,
    ): String = String(cps, from, to - from)

    private fun isWord(c: Int): Boolean = isAsciiAlnum(c) || c == '_'.code || c == '\''.code

    private fun isAsciiAlnum(c: Int): Boolean =
        (c >= 'a'.code && c <= 'z'.code) ||
            (c >= 'A'.code && c <= 'Z'.code) ||
            (c >= '0'.code && c <= '9'.code)

    private fun isAsciiDigit(c: Int): Boolean = c >= '0'.code && c <= '9'.code

    private fun isAsciiGraphic(c: Int): Boolean = c in 0x21..0x7e

    private fun isBlank(c: Int): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c)

    private fun isHan(c: Int): Boolean = Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN

    /**
     * 汉字连续段的默认切法：系统 ICU 词典分词（API 24+ 用 `android.icu`，否则 `java.text`）。
     *
     * 返回 null 时 [split] 会退化成一字一块。这一层可以以后换成内嵌词表 + 最大正向匹配。
     */
    internal fun icuHanSplit(run: String): List<String>? {
        val words =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val iterator = android.icu.text.BreakIterator.getWordInstance().apply { setText(run) }
                val list = ArrayList<String>()
                var start = iterator.first()
                var end = iterator.next()
                while (end != android.icu.text.BreakIterator.DONE) {
                    list.add(run.substring(start, end))
                    start = end
                    end = iterator.next()
                }
                list
            } else {
                val iterator = java.text.BreakIterator.getWordInstance().apply { setText(run) }
                val list = ArrayList<String>()
                var start = iterator.first()
                var end = iterator.next()
                while (end != java.text.BreakIterator.DONE) {
                    list.add(run.substring(start, end))
                    start = end
                    end = iterator.next()
                }
                list
            }
        return words.ifEmpty { null }
    }

    private const val LF = '\n'.code
    private const val CR = '\r'.code
}
