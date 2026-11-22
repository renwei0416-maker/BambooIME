/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.osfans.trime.ui.common

import android.content.Context
import com.osfans.trime.data.prefs.AppearancePrefs
import androidx.annotation.ColorInt

/**
 * 设置界面的设计 token（P0 视觉重构）。
 *
 * 与 [SettingsPalette] 的区别：那是"从键盘主题派生 6 个角色"的老逻辑，
 * 仍在「外观 → 检查当前取色」里用来解释颜色来源；这里是**固定设计系统**：
 * 底色、卡片、文字、分隔线都不随主题变，只有强调色允许跟随主题
 * （`AppearancePrefs.followTheme` 打开时取主题里最像"品牌色"的那个）。
 *
 * 这样做的好处是任何主题下设置页观感一致；跟随主题时也只会换点缀色，
 * 不会出现"高饱和主题把整页弄花"的情况。
 */
data class SettingsTokens(
    /** 页面底色。 */
    @param:ColorInt val background: Int,
    /** 卡片底色。 */
    @param:ColorInt val card: Int,
    /** 主文字。 */
    @param:ColorInt val textPrimary: Int,
    /** 次文字（说明、分组标题）。 */
    @param:ColorInt val textSecondary: Int,
    /** 强调色（开关、滑块、选中态、可点击图标）。 */
    @param:ColorInt val accent: Int,
    /** 卡片内分隔线。 */
    @param:ColorInt val divider: Int,
    /** 危险操作。 */
    @param:ColorInt val danger: Int,
    val isDark: Boolean,
    /** 强调色是否跟随键盘主题。 */
    val followTheme: Boolean,
)

object SettingsTokensResolver {
    private const val LIGHT_BG = 0xFFF5F6F4.toInt()
    private const val LIGHT_CARD = 0xFFFFFFFF.toInt()
    private const val LIGHT_TEXT = 0xFF1B211E.toInt()
    private const val LIGHT_SUB = 0xFF6B7671.toInt()
    private const val LIGHT_DIVIDER = 0xFFE6EAE7.toInt()
    private const val LIGHT_DANGER = 0xFFC0392B.toInt()

    private const val DARK_BG = 0xFF101316.toInt()
    private const val DARK_CARD = 0xFF1B1F22.toInt()
    private const val DARK_TEXT = 0xFFE9EFEA.toInt()
    private const val DARK_SUB = 0xFF93A09A.toInt()
    private const val DARK_DIVIDER = 0xFF2A3033.toInt()
    private const val DARK_DANGER = 0xFFE8705F.toInt()

    /** 品牌墨绿：浅色下直接用，深色下提亮一档以免发闷。 */
    const val BRAND_LIGHT = 0xFF2F4F43.toInt()
    const val BRAND_DARK = 0xFF7FB39A.toInt()

    fun resolve(context: Context): SettingsTokens {
        AppearancePrefs.migrateTokensOnce()
        val mode = AppearancePrefs.forceMode
        val dark =
            when (mode) {
                AppearancePrefs.MODE_LIGHT -> false
                AppearancePrefs.MODE_DARK -> true
                else -> SettingsPaletteResolver.isNight(context)
            }
        val legacy = runCatching { SettingsPaletteResolver.resolve(context) }.getOrNull()

        // 跟随主题：整页配色都取自当前键盘主题（布局、圆角、间距不变）。
        if (AppearancePrefs.followTheme && legacy != null) {
            val bg = legacy.background
            return SettingsTokens(
                background = bg,
                card = deriveCard(legacy.card, bg, legacy.isDark),
                textPrimary = legacy.textPrimary,
                textSecondary = legacy.textSecondary,
                accent = legacy.accent,
                divider = withAlpha(legacy.textPrimary, 0.12f),
                danger = if (legacy.isDark) DARK_DANGER else LIGHT_DANGER,
                isDark = legacy.isDark,
                followTheme = true,
            )
        }

        val accent = if (dark) BRAND_DARK else BRAND_LIGHT
        return if (dark) {
            SettingsTokens(
                background = DARK_BG,
                card = DARK_CARD,
                textPrimary = DARK_TEXT,
                textSecondary = DARK_SUB,
                accent = accent,
                divider = DARK_DIVIDER,
                danger = DARK_DANGER,
                isDark = true,
                followTheme = false,
            )
        } else {
            SettingsTokens(
                background = LIGHT_BG,
                card = LIGHT_CARD,
                textPrimary = LIGHT_TEXT,
                textSecondary = LIGHT_SUB,
                accent = accent,
                divider = LIGHT_DIVIDER,
                danger = LIGHT_DANGER,
                isDark = false,
                followTheme = false,
            )
        }
    }

    /** 卡片与背景亮度差不足 6% 时自动派生一档，保证分组还看得出来。 */
    private fun deriveCard(
        card: Int,
        background: Int,
        dark: Boolean,
    ): Int {
        val diff = kotlin.math.abs(luminance(card) - luminance(background))
        if (diff >= 0.06f) return card
        return if (dark) {
            mix(background, 0xFFFFFFFF.toInt(), 0.10f)
        } else {
            mix(background, 0xFF000000.toInt(), 0.04f)
        }
    }

    private fun luminance(color: Int): Float =
        0.2126f * channel(color shr 16 and 0xFF) +
            0.7152f * channel(color shr 8 and 0xFF) +
            0.0722f * channel(color and 0xFF)

    private fun channel(c: Int): Float {
        val s = c / 255f
        return if (s <= 0.03928f) {
            s / 12.92f
        } else {
            Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
    }

    private fun mix(
        from: Int,
        to: Int,
        ratio: Float,
    ): Int {
        val a = (from shr 24) and 0xFF
        val r = ((from shr 16 and 0xFF) * (1 - ratio) + (to shr 16 and 0xFF) * ratio).toInt()
        val g = ((from shr 8 and 0xFF) * (1 - ratio) + (to shr 8 and 0xFF) * ratio).toInt()
        val b = ((from and 0xFF) * (1 - ratio) + (to and 0xFF) * ratio).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun withAlpha(
        color: Int,
        alpha: Float,
    ): Int = (color and 0x00FFFFFF) or (((alpha * 255).toInt() and 0xFF) shl 24)
}
