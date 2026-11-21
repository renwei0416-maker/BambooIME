/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.theme.model.KeyboardRow
import com.osfans.trime.data.theme.model.TextKeyboard

/**
 * 隐藏键盘最上面那行「纯数字键」。
 *
 * 只做两件事：
 *  1. 首行全是纯数字（0-9）时把它从 rows 里去掉；
 *  2. 给出 [Result.scale]（剩余行数 / 原来行数），让 [Keyboard] 按比例缩总高，
 *     这样键盘是**真的变矮**，而不是剩下的行各自变高。
 *
 * 不做"保护"：只要首行是纯数字就隐藏。
 */
object NumberRowHider {
    data class Result(
        val config: TextKeyboard?,
        val scale: Float,
    )

    fun apply(config: TextKeyboard?, enabled: Boolean): Result {
        if (!enabled) return Result(config, 1f)
        val rows = config?.rows ?: return Result(config, 1f)
        if (rows.size <= 1) return Result(config, 1f)
        if (!isNumericRow(rows.first())) return Result(config, 1f)
        val kept = rows.drop(1)
        return Result(
            config.copy(
                rows = kept,
                // 自动行高原本指向某一行；删掉首行后索引全错位，直接关掉最安全
                autoHeightIndex = -1,
            ),
            kept.size.toFloat() / rows.size.toFloat(),
        )
    }

    fun isNumericRow(row: KeyboardRow): Boolean {
        val keys = row.keys.filterNot { it.spacer }
        if (keys.isEmpty()) return false
        return keys.all { isNumericKey(it) }
    }

    private fun isNumericKey(key: TextKeyboard.TextKey): Boolean {
        val click = key.click.trim()
        if (click.length == 1 && click[0] in '0'..'9') return true
        // 有些主题把数字写在 label 段里、click 留空
        val text = key.label.joinToString("") { it.text ?: "" }.trim()
        return text.length == 1 && text[0] in '0'..'9'
    }
}
