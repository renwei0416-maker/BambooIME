/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.glossary

import com.osfans.trime.actions.tips.TipEntry

/**
 * 「外语学习」在编码区下面显示的学习条。
 *
 * 一行一个候选，中文在前、英文在后。词库里查不到的词**仍然占一行**（只显示中文、斜体），
 * 这样「第几行 = 高亮往后第几个候选」，点哪行心里有数；开了「只显示命中项」才整条跳过。
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

    /** 往后看几个候选位置（含高亮那个）。 */
    private const val SCAN_WINDOW = 6

    /** 展开时取几个义项、多长；够长就行，真取不完也无所谓。 */
    private const val DETAIL_SENSES = 8
    private const val DETAIL_CHARS = 400

    /** 一条都别太长，超过这个长度的候选根本不去查（诗词整句之类）。 */
    private const val MAX_CANDIDATE_LEN = 8

    /** 安全上限，防止极端情况下构造出几百行。 */
    private const val MAX_ENTRIES = 60

    /**
     * 「外语学习」这块能不能用：开关打开 + 词库已就绪。
     *
     * 和文曲星的让位规则不在这里判——提示块的取舍统一由 `PreeditUi.pick()` / `TipBlocks` 决定，
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

    /**
     * 这一屏该显示的条目。
     *
     * 窗口从高亮那个候选开始往后数 6 个位置（走到末尾截断、不绕回）；
     * **不再按「显示几条」截断**——那个设置现在只决定视口高度，多出来的靠上下滑动看。
     */
    fun entries(): List<TipEntry> =
        try {
            build()
        } catch (e: Throwable) {
            emptyList()
        }

    private fun build(): List<TipEntry> {
        if (!shouldShow()) return emptyList()
        val candidates = GlossaryDecorator.currentCandidates()
        if (candidates.isEmpty()) return emptyList()
        val highlight = GlossaryDecorator.currentHighlighted()
        val start = if (highlight in candidates.indices) highlight else 0
        val end = (start + SCAN_WINDOW).coerceAtMost(candidates.size)
        val hitsOnly = GlossaryPrefs.displayMode == GlossaryPrefs.MODE_HITS_ONLY
        val senseCount = GlossaryPrefs.senseCount
        val maxChars = GlossaryPrefs.maxChars
        val out = ArrayList<TipEntry>(end - start)
        for (i in start until end) {
            if (out.size >= MAX_ENTRIES) break
            val text = candidates[i].text
            if (text.isEmpty() || text.length > MAX_CANDIDATE_LEN) continue
            val senses = GlossaryStore.lookup(text)
            val english = GlossaryFormatter.format(senses, senseCount, maxChars)?.takeIf { it.isNotEmpty() }
            // 「只显示命中项」：没命中的整条不出现
            if (hitsOnly && english == null) continue
            val detail =
                GlossaryFormatter.format(senses, DETAIL_SENSES, DETAIL_CHARS)?.takeIf { it.isNotEmpty() }
                    ?: english
                    ?: text
            out.add(
                TipEntry(
                    text = text,
                    detail = detail,
                    payload = english.orEmpty(),
                    highlighted = i == highlight,
                    unmatched = english == null,
                    english = english,
                ),
            )
        }
        return out
    }
}
