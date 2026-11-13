/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.tips

import com.osfans.trime.actions.glossary.GlossaryTips
import com.osfans.trime.actions.wenquxing.WenquxingPrefs
import com.osfans.trime.actions.wenquxing.WenquxingTips

/**
 * 提示块里的一条。
 *
 * 三个字段刻意分开，因为它们的用途不同：
 * - [text]：列表里显示的那一行（可能被各自的「显示字数」截断，文曲星还带圈号）
 * - [detail]：点开 `▾` 之后显示的完整正文（不截断）
 * - [payload]：点这一条要上屏的内容
 *
 * [chars] 是 [payload] 的长度，用来在行尾显示「·N字」。
 */
data class TipEntry(
    val text: String,
    val detail: String,
    val payload: String,
    val highlighted: Boolean = false,
    val unmatched: Boolean = false,
    val english: String? = null,
    /** 是否有被截掉的内容：只有这时才画展开入口（看得全就不给按钮）。 */
    val expandable: Boolean = detail.length > text.length,
) {
    val chars: Int get() = payload.length
}

/**
 * 编码区下面那一块提示「现在显示的是谁」。
 *
 * 显示层（`PreeditUi.pick()`）和上屏 token / 点击上屏都问这里，免得几处各判一次、结论打架：
 * 屏幕上显示的是哪一块，就取哪一块的第 N 条。
 *
 * 显示规则只有一条：文曲星这一句有命中就显示文曲星，否则显示外语学习。
 */
object TipBlocks {
    /** 两块都不显示。 */
    const val NONE = 0

    /** 外语学习（候选 + 英文）。 */
    const val GLOSSARY = 1

    /** 文曲星（诗词/名句）。 */
    const val WENQUXING = 2

    /**
     * 悬浮候选窗当前是否在显示（外接键盘/「根据输入设备而定」时会显示）。
     *
     * 提示块在两个窗口里各有一份，靠这个标志二选一：候选窗显示时由它显示，
     * 键盘那份让开；否则反过来。由 [com.osfans.trime.ime.composition.CandidatesView] 维护。
     */
    @Volatile
    var candidatesWindowShown: Boolean = false

    /** 当前显示哪一块；任何异常都当成不显示，绝不让它传到引擎线程。 */
    fun current(): Int =
        try {
            decide()
        } catch (t: Throwable) {
            NONE
        }

    private fun decide(): Int {
        val glossaryOn = GlossaryTips.shouldShow()
        val wenquxingEnabled = WenquxingPrefs.enabled
        // 用「有没有命中」而不是「这一帧 entries 是否为空」：后者会在上屏/展开那一瞬间
        // 被清空，导致块内容闪跳到另一个模块。
        val wenquxingHit = wenquxingEnabled && WenquxingTips.hitCount() > 0
        // 显示规则与 PreeditUi.refreshTips() 完全一致：文曲星有命中就压住外语学习。
        return when {
            wenquxingHit -> WENQUXING
            glossaryOn -> GLOSSARY
            else -> NONE
        }
    }

    /** 当前块的全部条目（顺序 = 屏幕上的显示顺序）。 */
    fun entries(): List<TipEntry> =
        try {
            when (current()) {
                GLOSSARY -> GlossaryTips.entries()
                WENQUXING -> WenquxingTips.entries()
                else -> emptyList()
            }
        } catch (t: Throwable) {
            emptyList()
        }

    /**
     * 第 [no] 条（1 起，指列表里从上往下第几条）该上屏的文本。
     *
     * 注意「条数」现在只是视口高度，块里可能不止这么多条，所以 N 指列表序号、不是可见行号。
     */
    fun textAt(no: Int): String? =
        try {
            if (no < 1) null else entries().getOrNull(no - 1)?.payload?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }

    /**
     * 「当前那一条」的文本：外语学习取高亮候选那条，文曲星取当前第一行。
     */
    fun highlightedEnglish(): String? =
        try {
            val list = entries()
            val line = list.firstOrNull { it.highlighted } ?: list.firstOrNull()
            line?.payload?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
}
