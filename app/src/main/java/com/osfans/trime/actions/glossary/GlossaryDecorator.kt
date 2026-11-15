/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.glossary

import android.os.Handler
import com.osfans.trime.util.BambooLog
import android.os.Looper
import android.os.SystemClock
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.ime.core.TrimeInputMethodService
import timber.log.Timber

/**
 * 候选进 UI 的唯一入口：在这里缓存最近一页候选与高亮下标。
 *
 * 英文**不再写进 `comment`**（那一小段又小又窄，看不清），改成由 [GlossaryTips]
 * 在编码区画多行学习条 —— 但学习条要跟着候选翻页刷新，而编码行只在组句变化时才重画，
 * 所以这里顺手请求一次重画。
 *
 * 上屏英文的 token 也读这里缓存的候选，与显示位置无关。
 */
object GlossaryDecorator {
    @Volatile
    private var lastCandidates: Array<CandidateProto> = emptyArray()

    @Volatile
    private var lastHighlighted: Int = -1

    @Volatile
    private var lastDiagAt: Long = 0L

    /**
     * 返回装饰后的消息。没有改动时**返回原对象**，这样 `MenuProto.equals` /
     * `CandidateListMessage.Data.equals` 不会因为新数组而误判为内容变化、触发无谓重绘。
     */
    fun decorate(msg: RimeMessage<*>): RimeMessage<*> =
        try {
            decorateUnsafe(msg)
        } catch (e: Throwable) {
            // 词库/格式化出任何问题都不许把输入法弄崩：记一笔，原样放行。
            Timber.e(e, "glossary: decorate failed")
            BambooLog.gl.d("glossary", "decorate failed: " + e.stackTraceToString())
            msg
        }

    private fun decorateUnsafe(msg: RimeMessage<*>): RimeMessage<*> =
        when (msg) {
            is RimeMessage.CandidateMenuMessage -> {
                val data = msg.data
                onCandidates(data.candidates, data.highlightedCandidateIndex)
                msg
            }

            is RimeMessage.CandidateListMessage -> {
                val data = msg.data
                onCandidates(data.candidates, data.highlighted)
                msg
            }

            else -> msg
        }

    private fun onCandidates(
        candidates: Array<CandidateProto>,
        highlighted: Int,
    ) {
        lastCandidates = candidates
        lastHighlighted = highlighted
        dumpForDiagnosis(candidates)
        if (!GlossaryPrefs.enabled) return
        requestPreeditRefresh()
    }

    /** 学习条要跟着候选翻页刷新；编码行是 UI 的东西，必须回主线程。 */
    private fun requestPreeditRefresh() {
        val service = TrimeInputMethodService.current ?: return
        ioHandler.post { runCatching { service.refreshCandidatesPreedit() } }
    }

    /**
     * 只在前两秒内打一次「候选 → 英文」对照，用来排查「某个词显示的英文是别的词的」这类问题。
     * 正常情况下是死代码，不产生任何输出。
     */
    private fun dumpForDiagnosis(candidates: Array<CandidateProto>) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagAt < 2000L) return
        lastDiagAt = now
        runCatching {
            val sb = StringBuilder("cands=")
            for (i in 0 until minOf(3, candidates.size)) {
                val w = candidates[i].text
                val first = GlossaryStore.lookup(w).firstOrNull()?.text ?: "-"
                sb.append('[').append(w).append("->").append(first).append(']')
            }
            BambooLog.gl.d("glossary", sb.toString())
        }
    }

    /** 供学习条读取（与显示位置无关）。 */
    fun currentCandidates(): Array<CandidateProto> = lastCandidates

    /** 当前高亮候选下标；-1 表示未知。 */
    fun currentHighlighted(): Int = lastHighlighted

    private val ioHandler = Handler(Looper.getMainLooper())
}
