/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.glossary

/**
 * 学习条上的一行：候选中文 + 对应英文（可能没有），以及它是不是当前高亮那个。
 *
 * [unmatched] 只有「外语学习」会置 true（词库里查不到这个词），渲染时中文用斜体 + 调淡；
 * 文曲星的行没有英文但**不是**未命中，所以不能靠「english == null」判断——那会把文曲星整块变斜体。
 */
data class TipLine(
    val text: String,
    val english: String?,
    val highlighted: Boolean,
    val unmatched: Boolean = false,
)

/**
 * 「外语学习」在编码区多行显示的学习条。
 *
 * 一行一个候选，中文在前、英文在后。词库里查不到的词**仍然占一行**（只显示中文），
 * 这样「第几行」永远等于「第几个候选」，`BambooTip1..6` 的指向和眼睛看到的完全一致。
 *
 * 和文曲星共用编码区这一块，所以这里定了让步规则（[GlossaryTips.CONFLICT_AUTO] 等）。
 */
object GlossaryTips {
    /** 自动让位：文曲星这一句有结果就显示文曲星，否则显示学习条。 */
    const val CONFLICT_AUTO = 0

    /** 外语学习优先：永远显示学习条。 */
    const val CONFLICT_GLOSSARY = 1

    /** 文曲星开着就不显示学习条（等价于被顶掉）。 */
    const val CONFLICT_OFF = 2

    /**
     * 「外语学习」这块能不能用：开关打开 + 词库已就绪。
     *
     * 和文曲星的让位规则不在这里判——提示块的取舍统一由 `PreeditUi.pickLines()` 决定，
     * 免得两处各判一次、结论打架。
     */
    fun shouldShow(): Boolean =
        try {
            if (!GlossaryPrefs.enabled) {
                false
            } else {
                GlossaryStore.ready()
            }
        } catch (e: Throwable) {
            false
        }

    /** 取最多 [max] 行；不该显示时返回空表。 */
    fun lines(max: Int): List<TipLine> =
        try {
            build(max)
        } catch (e: Throwable) {
            emptyList()
        }

    /** 上屏用：第 [no] 行（1 起）的英文；没有这一行或这一行没命中都返回 null。 */
    fun tipAt(no: Int): String? {
        val lines = lines(GlossaryPrefs.tipsMax)
        return lines.getOrNull(no - 1)?.english?.takeIf { it.isNotEmpty() }
    }

    /** 上屏用：当前高亮那一行的英文（没有高亮时用第一行）。 */
    fun highlightedTip(): String? {
        val lines = lines(GlossaryPrefs.tipsMax)
        val line = lines.firstOrNull { it.highlighted } ?: lines.firstOrNull()
        return line?.english?.takeIf { it.isNotEmpty() }
    }

    /** 往后看几个候选位置（含高亮那个）。 */
    private const val SCAN_WINDOW = 6

    private fun build(max: Int): List<TipLine> {
        if (!shouldShow()) return emptyList()
        val limit = max.coerceIn(1, 6)
        val candidates = GlossaryDecorator.currentCandidates()
        if (candidates.isEmpty()) return emptyList()
        val highlight = GlossaryDecorator.currentHighlighted()
        // 窗口跟着高亮走：高亮那个永远排第一行，下面依次是它后面的候选。
        // 这样候选条只显示几条、而总数更多时，往后移动候选块也会跟着滚，箭头不会卡在末行。
        val start = if (highlight in candidates.indices) highlight else 0
        // 扫描窗口固定 6 个候选位置（含高亮这个），走到末尾就截断、不绕回。
        val end = (start + SCAN_WINDOW).coerceAtMost(candidates.size)
        val hitsOnly = GlossaryPrefs.displayMode == GlossaryPrefs.MODE_HITS_ONLY
        val senseCount = GlossaryPrefs.senseCount
        val maxChars = GlossaryPrefs.maxChars
        val out = ArrayList<TipLine>(limit)
        for (i in start until end) {
            if (out.size >= limit) break
            val text = candidates[i].text
            if (text.isEmpty()) continue
            if (text.length > 8) continue
            val english = GlossaryFormatter.format(GlossaryStore.lookup(text), senseCount, maxChars)
                ?.takeIf { it.isNotEmpty() }
            // 「只显示命中项」：没命中的整条不出现，行数不再等于候选位置。
            if (hitsOnly && english == null) continue
            // 「始终显示」：查不到也占一行（只显示中文、斜体），保证「第几行 = 高亮往后第几个候选」
            out.add(TipLine(text, english, i == highlight, english == null))
        }
        return out
    }
}
