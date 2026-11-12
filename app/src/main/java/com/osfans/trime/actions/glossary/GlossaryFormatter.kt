/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.glossary

/**
 * 把词库里的义项渲染成候选后面那一小段英文。
 *
 * 打包时所有信息都留着，这里按设置决定**显示什么、显示多少**：
 * 词性前缀、语体/领域标签、量词注、义项个数、每条字符上限。
 */
object GlossaryFormatter {
    /** 词性名，下标与 `.bge` 里的 pos id 一致。 */
    val POS_NAMES =
        arrayOf(
            "", "n.", "v.", "adj.", "adv.", "prep.", "conj.", "pron.", "num.", "interj.", "part.", "meas.",
        )

    /** 标签的中文短名，下标 = label id - 1。前 [STYLE_MAX_ID] 个是语体，其后是领域。 */
    val LABEL_NAMES =
        arrayOf(
            "书面", "口语", "俚语", "方言", "旧", "古", "敬", "贬", "婉", "外来",
            "喻", "直译", "拟声", "网络", "构词",
            "计算机", "数学", "化学", "物理", "医学", "中医", "佛教", "体育", "植物", "地质",
            "解剖", "成语", "人名", "台湾", "鸟类",
        )

    /** 语体标签的 id 上界（含）；大于它的都是领域标签。 */
    const val STYLE_MAX_ID = 15

    private val CL_RE = Regex("〔CL:[^〕]*〕")
    private val SPACES = Regex("[ ]{2,}")

    /** 渲染一条释义；没有可用内容时返回 `null`。 */
    fun format(
        senses: List<GlossaryPack.Sense>,
        maxSenses: Int,
        maxChars: Int,
    ): String? {
        if (senses.isEmpty()) return null
        val limit = if (maxSenses >= senses.size) senses.size else maxSenses.coerceAtLeast(1)
        val parts = ArrayList<String>(limit)
        for (i in 0 until limit) {
            val one = renderOne(senses[i], maxChars)
            if (one.isNotEmpty()) parts.add(one)
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(SEP)
    }


    private fun renderOne(
        sense: GlossaryPack.Sense,
        maxChars: Int,
    ): String {
        val sb = StringBuilder()
        if (GlossaryPrefs.showPos) {
            val pos = POS_NAMES.getOrNull(sense.pos).orEmpty()
            if (pos.isNotEmpty()) sb.append(pos).append(' ')
        }
        val label = labelOf(sense)
        if (label != null) sb.append('（').append(label).append('）')
        var text = sense.text
        if (!GlossaryPrefs.showCl) text = CL_RE.replace(text, "")
        text = SPACES.replace(text.trim(), " ")
        sb.append(text)
        return truncate(sb.toString(), maxChars)
    }

    private fun labelOf(sense: GlossaryPack.Sense): String? {
        for (id in intArrayOf(sense.label1, sense.label2)) {
            if (id <= 0) continue
            val isStyle = id <= STYLE_MAX_ID
            if (isStyle && !GlossaryPrefs.showStyle) continue
            if (!isStyle && !GlossaryPrefs.showDomain) continue
            val name = LABEL_NAMES.getOrNull(id - 1) ?: continue
            return name
        }
        return null
    }

    private fun truncate(
        s: String,
        maxChars: Int,
    ): String = if (s.length <= maxChars) s else s.take((maxChars - 1).coerceAtLeast(1)).trimEnd() + "…"

    /** 义项之间的分隔符。 */
    const val SEP = " ｜ "
}
