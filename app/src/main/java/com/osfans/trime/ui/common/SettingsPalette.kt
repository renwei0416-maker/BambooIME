/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.osfans.trime.ui.common

import android.content.Context
import android.content.res.Configuration
import com.osfans.trime.data.prefs.AppearancePrefs
import com.osfans.trime.data.theme.ColorManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 一处颜色的实际来源，用于设置页的取色自检。 */
data class PaletteRole(
    val name: String,
    val source: String,
    val color: Int,
    val contrast: Double,
    val note: String = "",
)

/**
 * 设置界面的配色，全部由当前键盘主题的颜色派生。
 */
data class SettingsPalette(
    /** 页面底色。 */
    val background: Int,
    /** 卡片 / 输入框底色。 */
    val card: Int,
    /** 主文字。 */
    val textPrimary: Int,
    /** 次文字与图标。 */
    val textSecondary: Int,
    /** 强调色。 */
    val accent: Int,
    /** 分隔线。 */
    val divider: Int,
    /** 警示色。 */
    val warning: Int,
    val isDark: Boolean,
    val followTheme: Boolean,
    val roles: List<PaletteRole>,
)

/**
 * 把主题颜色解析成设置界面用的 6+1 个角色。
 *
 * 键名是 Trime 的固定语义，换主题不会失效；解析链为
 * 当前配色方案 → 主题 fallback_colors → 内置别名 → 内置中性色。
 * 取到的颜色还要过一遍对比度，不达标就换成派生色，保证文字始终可读。
 */
object SettingsPaletteResolver {
    /** 正文最小对比度。 */
    const val MIN_TEXT = 4.5

    /** 次文字、图标、强调色最小对比度。 */
    const val MIN_SUB = 3.0
    const val MIN_ACCENT = 3.0
    const val MIN_WARNING = 3.0

    /** 分隔线只需要看得见。 */
    const val MIN_DIVIDER = 1.5

    private val LIGHT_BG = 0xFFF7F6F2.toInt()
    private val DARK_BG = 0xFF141414.toInt()

    /** 强调色候选：先用主题里被高亮的文字色（带色相），再用高亮背景色。 */
    private val ACCENT_KEYS = listOf(
        "key_text_color",
        "key_symbol_color",
    )

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun resolve(context: Context): SettingsPalette {
        val follow = AppearancePrefs.followTheme
        val mode = if (follow) AppearancePrefs.MODE_THEME else AppearancePrefs.forceMode
        val night = isNight(context)
        val notes = HashMap<String, String>()

        fun readTheme(vararg keys: String): Pair<Int, String>? {
            for (key in keys) {
                val value = runCatching { ColorManager.getColorOr(key, 0) }.getOrNull() ?: continue
                if ((value ushr 24) == 0) continue
                return (value or 0xFF000000.toInt()) to key
            }
            return null
        }

        // 背景：决定整套是深色还是浅色
        var isDark = when (mode) {
            AppearancePrefs.MODE_LIGHT -> false
            AppearancePrefs.MODE_DARK -> true
            else -> night
        }
        val themeBg = if (mode == AppearancePrefs.MODE_THEME) readTheme("back_color", "keyboard_back_color") else null
        var background = if (isDark) DARK_BG else LIGHT_BG
        var backgroundSource = if (mode == AppearancePrefs.MODE_THEME) "内置（主题没有背景色）" else "内置"
        if (themeBg != null) {
            background = themeBg.first
            backgroundSource = themeBg.second
            isDark = luminance(background) < 0.5
        }

        // 主文字
        val themeText = if (mode == AppearancePrefs.MODE_THEME) {
            readTheme("key_text_color", "candidate_text_color", "text_color")
        } else {
            null
        }
        var text = themeText?.first ?: if (isDark) 0xFFEDEDED.toInt() else 0xFF1B1B1B.toInt()
        var textSource = themeText?.second ?: "内置"
        // 背景是中等明度时，浅字深字都可能可行，取能压住背景的那一个
        val darkText = 0xFF141414.toInt()
        val lightText = 0xFFF5F5F5.toInt()
        if (contrast(text, background) < MIN_TEXT) {
            val fallbackText = if (contrast(darkText, background) >= contrast(lightText, background)) darkText else lightText
            if (contrast(fallbackText, background) >= MIN_TEXT) {
                text = fallbackText
                textSource = "派生（与背景反向的中性色）"
                notes["主文字"] = "主题取值与背景对比过低，改用相反方向的中性色"
            } else {
                background = if (luminance(background) < 0.5) DARK_BG else LIGHT_BG
                backgroundSource = "内置（与文字色拉不开对比）"
                text = if (contrast(lightText, background) >= contrast(darkText, background)) lightText else darkText
                textSource = "派生（背景已换）"
                notes["背景"] = "主题背景与文字色对比过低，改用中性背景"
            }
        }
        // 深浅跟随主文字方向，状态栏图标、警示色等都以它为准
        isDark = luminance(text) > 0.5

        // 卡片
        val themeCard = if (mode == AppearancePrefs.MODE_THEME) readTheme("key_back_color", "popup_back_color") else null
        var card = themeCard?.first ?: blend(text, background, if (isDark) 0.10f else 0.06f)
        var cardSource = themeCard?.second ?: "派生"
        if (contrast(card, background) < 1.04) {
            card = blend(text, background, if (isDark) 0.12f else 0.07f)
            cardSource = "派生（与背景几乎同色）"
        }
        if (contrast(text, card) < MIN_TEXT) {
            card = background
            cardSource = "同背景（卡片色压不住文字）"
            notes["卡片"] = "卡片改用背景色，靠描边区分"
        }

        // 次文字：既要有 3.0 以上可读性，又要明显比标题淡，否则层级看不出来
        val themeSub = if (mode == AppearancePrefs.MODE_THEME) readTheme("key_symbol_color", "comment_text_color") else null
        val titleOnCard = contrast(text, card)
        val subRoom = (titleOnCard * 0.8).toFloat()
        var sub = themeSub?.first ?: blend(text, card, 0.55f)
        var subSource = themeSub?.second ?: "派生"
        val subOnCard = contrast(sub, card)
        if (subOnCard < MIN_SUB || subOnCard > subRoom || min(contrast(sub, background), subOnCard) < MIN_SUB) {
            val target = min(5.0, max(MIN_SUB, titleOnCard * 0.6)).toFloat()
            var ratio = 0.35f
            var candidate = blend(text, card, ratio)
            while (ratio < 1f && contrast(candidate, card) < target) {
                ratio += 0.05f
                candidate = blend(text, card, ratio)
            }
            sub = candidate
            subSource = "派生（与标题拉开层次）"
            if (themeSub != null) notes["次文字"] = "主题取值与标题同色或太淡，改用主文字与卡片的混合色"
        }

        // 强调色跟按键字母同色：设置页的图标、分类标题都属于「键面上的字」，
        // 取 Key 上文字的颜色才能和键盘观感一致
        var accent = 0
        var accentSource = ""
        if (mode == AppearancePrefs.MODE_THEME) {
            for (key in ACCENT_KEYS) {
                val value = runCatching { ColorManager.getColorOr(key, 0) }.getOrNull() ?: continue
                if ((value ushr 24) == 0) continue
                accent = value or 0xFF000000.toInt()
                accentSource = key
                break
            }
        }
        if (accent == 0) {
            accent = text
            accentSource = "同按键字母"
        }
        // 与主文字同色时对比度已达标；万一主题给的是浅色字，朝主文字方向补齐
        var accentStep = 0f
        while (contrast(accent, background) < MIN_ACCENT && accentStep < 0.9f) {
            accentStep += 0.1f
            accent = blend(text, accent, accentStep)
        }

        // 分隔线与强调色同值（都取按键字母色），设置页的行分隔线按这个色画
        val divider = blend(text, background, 0.42f)
        val dividerSource = "主文字 42%"

        // 警示色：主题里没有可靠语义键，用固定色，再按需要朝主文字压到达标
        var warning = if (isDark) 0xFFF38BA8.toInt() else 0xFFD20F39.toInt()
        var warningStep = 0f
        while (contrast(warning, background) < MIN_WARNING && warningStep < 0.9f) {
            warningStep += 0.1f
            warning = blend(text, warning, warningStep)
        }

        val roles = listOf(
            PaletteRole("背景", backgroundSource, background, 1.0, notes["背景"].orEmpty()),
            PaletteRole("卡片", cardSource, card, contrast(card, background), notes["卡片"].orEmpty()),
            PaletteRole("主文字", textSource, text, contrast(text, background), notes["主文字"].orEmpty()),
            PaletteRole("次文字", subSource, sub, contrast(sub, background), notes["次文字"].orEmpty()),
            PaletteRole("强调色", accentSource, accent, contrast(accent, background), notes["强调色"].orEmpty()),
            PaletteRole("分隔线", dividerSource, divider, contrast(divider, background), ""),
            PaletteRole("警示色", "固定", warning, contrast(warning, background), ""),
        )

        return SettingsPalette(
            background = background,
            card = card,
            textPrimary = text,
            textSecondary = sub,
            accent = accent,
            divider = divider,
            warning = warning,
            isDark = isDark,
            followTheme = follow,
            roles = roles,
        )
    }

    /** 把 [foreground] 按 [ratio] 混进 [background]，用于派生底色和半透明分隔线。 */
    fun blend(foreground: Int, background: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int): Int = (b * (1f - r) + a * r).toInt().coerceIn(0, 255)
        val red = mix((foreground shr 16) and 0xFF, (background shr 16) and 0xFF)
        val green = mix((foreground shr 8) and 0xFF, (background shr 8) and 0xFF)
        val blue = mix(foreground and 0xFF, background and 0xFF)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /** WCAG 相对亮度。 */
    fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    /** WCAG 对比度，1.0 ~ 21.0。 */
    fun contrast(a: Int, b: Int): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }
}
