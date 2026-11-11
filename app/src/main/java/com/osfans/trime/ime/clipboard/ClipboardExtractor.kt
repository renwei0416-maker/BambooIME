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
import com.osfans.trime.util.extractAll
import kotlin.text.Regex

class ClipboardExtractor(
    private val context: Context,
    private val service: TrimeInputMethodService,
    private val windowManager: BoardWindowManager,
) {
    private companion object {
        /**
         * 「剪贴板提取规则」这个设置项默认是空的。空着的时候用这一组兜底，
         * 否则菜单点下去永远是「未找到匹配内容」。用户在设置里填了就用他自己的。
         */
        val FALLBACK_RULE_TEXTS = listOf(
            // 验证码 / 校验码（优先取捕获组里的数字）
            "(?i)(?:验证码|校验码|动态码|确认码|激活码|安全码|verification code|code)[^0-9]{0,16}(\\d{4,8})",
            // 单号 / 订单号 / 快递单号 / 运单号：字母数字横杠，遇到空格或汉字自然中断
            "(?<!\\w)(?:快递单号|运单号|订单号|单号)[\\s:：=]*([A-Za-z0-9][A-Za-z0-9-]{5,})",
            // 链接：显式排除汉字与全角标点，避免把紧跟的中文吞进来
            "https?://[^\\s\"'<>，。；、（）【】「」\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]+",
            // 邮箱：只认 ASCII，且顶级域必须是 2 位以上字母（结尾的 . 或 - 不会再被吃进去）
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
            // 中国大陆手机号
            "(?<!\\d)1[3-9]\\d{9}(?!\\d)",
            // 金额
            "[¥￥$]\\s?\\d+(?:\\.\\d{1,2})?",
            // 日期
            "\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?",
            // 身份证号
            "(?<!\\d)\\d{17}[\\dXx](?!\\d)",
        )
    }
    private val prefs = AppPrefs.defaultInstance().clipboard
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste

    val extractRules: Set<Regex> by lazy {
        val rules by prefs.clipboardExtractRules
        val configured = rules
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val texts = if (configured.isEmpty()) FALLBACK_RULE_TEXTS else configured
        texts
            .mapNotNull {
                try {
                    Regex(it)
                } catch (e: Exception) {
                    null
                }
            }
            .toSet()
    }

    fun showExtractDialog(text: String) {
        val extracted = text.extractAll(extractRules)
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
