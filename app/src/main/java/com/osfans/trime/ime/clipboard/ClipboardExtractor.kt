/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.common.buildDialog

class ClipboardExtractor(
    private val context: Context,
    private val service: TrimeInputMethodService,
    private val windowManager: BoardWindowManager,
) {
    private val prefs = AppPrefs.defaultInstance().clipboard
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste

    /** 规则文本：用户在设置里填了就用他的，没填就用内置兜底那 12 条。 */
    private val ruleTexts: List<String> by lazy {
        val rules by prefs.clipboardExtractRules
        rules
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { ClipboardExtractScan.FALLBACK_RULE_TEXTS }
    }

    fun showExtractDialog(text: String) {
        val extracted = ClipboardExtractScan.scan(text, ruleTexts)
        if (extracted.isEmpty()) {
            val dialog = context.buildDialog()
                .setMessage(R.string.no_extraction_results)
                .setPositiveButton(R.string.ok, null)
                .create()
            service.showDialog(dialog)
            return
        }
        val dialog = context.buildDialog(R.string.select_extracted_content)
            .setItems(extracted.toTypedArray()) { _, which ->
                service.commitText(extracted[which])
                if (clipboardReturnAfterPaste) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        service.showDialog(dialog)
    }
}
