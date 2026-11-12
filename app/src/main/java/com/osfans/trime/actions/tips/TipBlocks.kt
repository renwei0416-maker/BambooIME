/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.tips

import com.osfans.trime.actions.glossary.GlossaryPrefs
import com.osfans.trime.actions.glossary.GlossaryTips
import com.osfans.trime.actions.wenquxing.WenquxingPrefs
import com.osfans.trime.actions.wenquxing.WenquxingTips

/**
 * 编码区下面那一块提示「现在显示的是谁」。
 *
 * 显示层（`PreeditUi.pick()`）和上屏 token 都问这里，免得两处各判一次、结论打架：
 * 屏幕上显示的是哪一块，token 就上屏哪一块的第 N 条。
 *
 * 让位规则见 [GlossaryTips.CONFLICT_AUTO] 等三个值。
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
        val wenquxingLines = if (wenquxingEnabled) WenquxingTips.blockLines() else null
        val conflict = GlossaryPrefs.conflict
        return when {
            !glossaryOn -> if (wenquxingLines.isNullOrEmpty()) NONE else WENQUXING
            conflict == GlossaryTips.CONFLICT_GLOSSARY -> GLOSSARY
            conflict == GlossaryTips.CONFLICT_OFF -> if (wenquxingEnabled) WENQUXING else GLOSSARY
            else -> if (wenquxingLines.isNullOrEmpty()) GLOSSARY else WENQUXING
        }
    }

    /**
     * 第 [no] 条（1 起，指屏幕上从上往下第几条）该上屏的文本。
     *
     * 外语学习取第 N 个候选的英文，文曲星取当前第 N 行（圈号就是位置）的正文；没有这一条就是 null。
     */
    fun textAt(no: Int): String? =
        try {
            if (no < 1) {
                null
            } else {
                when (current()) {
                    GLOSSARY -> GlossaryTips.tipAt(no)
                    WENQUXING -> WenquxingTips.entryAt(no)
                    else -> null
                }
            }
        } catch (t: Throwable) {
            null
        }

    /**
     * 「当前那一条」的文本：外语学习取高亮候选那条，文曲星取当前第一行。
     *
     * 文曲星没有「高亮」的概念，屏幕上排第一的就是句号轮换到的那条，所以取第 1 行。
     */
    fun highlightedEnglish(): String? =
        try {
            when (current()) {
                GLOSSARY -> GlossaryTips.highlightedTip()
                WENQUXING -> WenquxingTips.entryAt(1)
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
}
