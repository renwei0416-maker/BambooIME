// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.actions.handwriting

import android.content.Context
import android.content.SharedPreferences
import com.osfans.trime.TrimeApplication

/**
 * 手写板右侧符号列：记录每个符号被点了多少次，用得多的排在上面。
 *
 * 存储格式是一行行 `symbol|count`，整体存在 `bamboo_handwriting` 里，
 * 不额外开 SharedPreferences 文件。
 */
object SymbolUsage {
    private const val K_COUNTS = "symbol_counts"

    /** 默认顺序按中文标点使用习惯排，数字与常见符号排在后面。 */
    val defaults: List<String> = listOf(
        "，", "。", "？", "！", "、", "；", "：", "「", "」", "（", "）", "《", "》",
        "…", "—", "·", "~", "\"", "'", "“", "”",
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        ",", ".", "?", "!", ";", ":", "-", "=", "/", "@", "#", "%", "&", "*", "+",
        "(", ")", "[", "]", "{", "}", "<", ">", "|", "_", "^", "`", "\\",
    )

    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("bamboo_handwriting", Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences {
        sp?.let { return it }
        val ctx = TrimeApplication.getInstance()
        return ctx.getSharedPreferences("bamboo_handwriting", Context.MODE_PRIVATE).also { sp = it }
    }

    private fun readCounts(): Map<String, Int> {
        val raw = p().getString(K_COUNTS, "").orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return raw.lineSequence()
            .mapNotNull { line ->
                val i = line.lastIndexOf('|')
                if (i <= 0) return@mapNotNull null
                val sym = line.substring(0, i)
                val n = line.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
                sym to n
            }
            .toMap()
    }

    /** 按点击次数降序、次数相同保持默认顺序。 */
    /** 按「一对符号的合计次数」降序排；两列共用这个顺序，行才对得齐。 */
    fun orderedPairs(pairs: List<Pair<String, String>>): List<Pair<String, String>> {
        val counts = readCounts()
        val idx = pairs.withIndex().associate { (i, p) -> p to i }
        return pairs.sortedWith(
            compareByDescending<Pair<String, String>> {
                (counts[it.first] ?: 0) + (counts[it.second] ?: 0)
            }.thenBy { idx[it] ?: 0 },
        )
    }

    /** 按点击次数降序排一份给定的符号表；次数相同保持原有顺序。 */
    fun ordered(base: List<String>): List<String> {
        val counts = readCounts()
        return base.sortedWith(
            compareByDescending<String> { counts[it] ?: 0 }
                .thenBy { base.indexOf(it) },
        )
    }

    fun ordered(): List<String> {
        val counts = readCounts()
        if (counts.isEmpty()) return defaults
        return defaults.sortedByDescending { counts[it] ?: 0 }
    }

    fun record(symbol: String) {
        if (symbol.isEmpty()) return
        val counts = readCounts().toMutableMap()
        counts[symbol] = (counts[symbol] ?: 0) + 1
        val raw = counts.entries.joinToString("\n") { "${it.key}|${it.value}" }
        p().edit().putString(K_COUNTS, raw).apply()
    }
}
