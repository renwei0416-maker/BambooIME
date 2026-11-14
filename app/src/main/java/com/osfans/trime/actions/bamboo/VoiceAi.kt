/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.bamboo

import android.view.inputmethod.InputConnection
import com.osfans.trime.ime.core.TrimeInputMethodService
import timber.log.Timber

/**
 * 语音识别结果 → AI 的桥。
 *
 * 语音链路有两条（内置 sherpa / 外部 asrkb 服务），两条最后都会拿到一段 final 文本；
 * 这里是**唯一的收口**：判断要不要发给当前 AI 模式，并统一负责把识别文本落到编辑框。
 *
 * 调用约定（重要）：调用方拿到的 final 文本**一律交给这里**，自己不要再上屏；
 * 是不是发了 AI 由返回值告知（只用于日志/调试，行为已经全在这里决定）。
 */
object VoiceAi {
    /**
     * @param text 识别出的最终文本。
     * @return true = 已经把这段文字发给 AI；false = 只上屏（原来的行为）。
     */
    suspend fun handleFinal(
        service: TrimeInputMethodService,
        text: String,
    ): Boolean {
        val source = text.trim()
        if (source.isEmpty()) {
            clearComposing(service.currentInputConnection)
            return false
        }

        // 先问「要不要发」，再决定「要不要上屏」——顺序不能反，
        // 否则未进模式时会把原文吞掉。
        val sent = runCatching { BambooActions.dispatchVoiceText(service, source) }
            .getOrElse { e ->
                Timber.w(e, "voice ai: dispatch failed")
                false
            }

        val ic = service.currentInputConnection
        if (!sent) {
            // 没发 AI：原文照旧上屏（partial 被 final 替换，与以前完全一致）
            commitRecognized(ic, source)
            return false
        }

        if (VoiceAiPrefs.commitSource) {
            commitRecognized(ic, source)
            RevertLedger.record(source)
        } else {
            // 不同时上屏：必须清掉识别过程中留下的组合文本，
            // 否则一半在组合区、AI 结果又插在它后面，正文会串。
            clearComposing(ic)
        }
        Timber.d("voice ai: sent, commitSource=%s", VoiceAiPrefs.commitSource)
        return true
    }

    /** 与 [com.osfans.trime.link.SherpaSpeechClient] 原来那段相同：先组合再收尾，能替换掉 partial。 */
    private fun commitRecognized(ic: InputConnection?, text: String) {
        if (ic == null) return
        runCatching {
            ic.setComposingText(text, 1)
            ic.finishComposingText()
        }.onFailure { Timber.w(it, "voice ai: commit failed") }
    }

    private fun clearComposing(ic: InputConnection?) {
        if (ic == null) return
        runCatching {
            ic.setComposingText("", 1)
            ic.finishComposingText()
        }.onFailure { Timber.w(it, "voice ai: clear composing failed") }
    }
}
