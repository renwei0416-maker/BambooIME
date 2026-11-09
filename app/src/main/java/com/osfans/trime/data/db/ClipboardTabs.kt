/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.osfans.trime.data.db

import android.util.Patterns
import androidx.annotation.StringRes
import com.osfans.trime.R

/**
 * 剪贴板的内容类型页签。
 *
 * 与旧版 [ClipboardCategory] 的区别：分类按**内容**判断，而不是在写入时打标，
 * 所以历史里已有的条目也能自动归类。参考 CyIME 的分类方式。
 */
enum class ClipboardTab(@StringRes val titleRes: Int) {
    All(R.string.clipboard_tab_all),
    Text(R.string.clipboard_tab_text),
    Link(R.string.clipboard_tab_link),
    Phone(R.string.clipboard_tab_phone),
    Email(R.string.clipboard_tab_email),
    Address(R.string.clipboard_tab_address),
    Image(R.string.clipboard_tab_image),
}

/** 来源筛选，替代旧的「本地 / 远程」两个页签。 */
enum class ClipboardSource(@StringRes val titleRes: Int) {
    All(R.string.clipboard_source_all),
    Local(R.string.clipboard_source_local),
    Remote(R.string.clipboard_source_remote),
}

/** 内容类型的判定规则，纯函数，不碰数据库。 */
object ClipboardTabFilter {
    private val emailPattern = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /** 中日文地址特征：邮编、都道府县+市区町村、省市+路街巷号、丁目/番地/几号楼。 */
    private val addressPattern = Regex(
        "〒\\s*\\d{3}" +
            "|[都道府県].{0,10}[市区町村郡]" +
            "|(?:省|市|区|县).{0,12}(?:路|街|巷|弄|号|道)" +
            "|\\d+\\s*(?:丁目|番地|号楼|栋|单元|室|层)",
    )

    /** 「验证码 123456」「123456 是您的验证码」两类写法。 */
    private val codePatterns = listOf(
        Regex("(?:验证码|校验码|动态码|确认码|激活码|安全码|verification code|code)[^0-9]{0,16}(\\d{4,8})", RegexOption.IGNORE_CASE),
        Regex("(\\d{4,8})[^0-9]{0,16}(?:是|为|即)?\\s*(?:您的|你的)?\\s*(?:验证码|校验码|动态码|确认码|激活码)", RegexOption.IGNORE_CASE),
    )

    fun matches(bean: DatabaseBean, tab: ClipboardTab): Boolean {
        if (tab == ClipboardTab.All) return true
        if (bean.isUriEntry()) return tab == ClipboardTab.Image
        val raw = bean.text
        if (raw.isBlank()) return false
        return when (tab) {
            ClipboardTab.Image -> false
            ClipboardTab.Text -> true
            ClipboardTab.Link -> Patterns.WEB_URL.matcher(raw.trim()).find()
            ClipboardTab.Email -> emailPattern.containsMatchIn(raw)
            ClipboardTab.Phone -> phoneOf(raw) != null
            ClipboardTab.Address -> addressPattern.containsMatchIn(raw)
            else -> true
        }
    }

    fun matchesSource(bean: DatabaseBean, source: ClipboardSource): Boolean = when (source) {
        ClipboardSource.All -> true
        ClipboardSource.Local -> bean.source == DatabaseBean.SOURCE_LOCAL
        ClipboardSource.Remote -> bean.source == DatabaseBean.SOURCE_REMOTE
    }

    /**
     * 判断是不是「一个电话号码」：整条内容除了数字和常见分隔符之外几乎没有别的字符，
     * 且数字位数 7–15（座机、手机、带区号或国际前缀都覆盖）。
     */
    fun phoneOf(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains('\n')) return null
        val digits = trimmed.count { it.isDigit() }
        if (digits !in 7..15) return null
        val others = trimmed.count { !it.isDigit() && it !in " +-()（）." }
        if (others > 2) return null
        return trimmed
    }

    /** 从整段文字里挑出验证码。 */
    fun verificationCode(text: String): String? {
        if (text.isBlank() || text.length > 2000) return null
        for (pattern in codePatterns) {
            val match = pattern.find(text) ?: continue
            val code = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
            if (code.length in 4..8) return code
        }
        return null
    }
}
