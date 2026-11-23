package com.osfans.trime.actions.ocr

import android.graphics.Color

/**
 * 主题配色的可读性兜底。
 *
 * 主题里的 `key_text_color` / `key_back_color` 在某些配色（或图片键帽解析失败落回默认值时）
 * 会非常接近，字就糊在底色里。这里只修正**文字色**，底色的语义（主题想表达的底色）不动：
 * 对比度不足时，换成黑或白里更清楚的那一个。
 */
object ThemeContrast {
    /** 正文文字的最低对比度（WCAG AA 小字号是 4.5，面板字号偏小，取 3.5 折中）。 */
    const val MIN_TEXT = 3.5

    /** 次要文字（提示、说明）可以低一些。 */
    const val MIN_SUB = 3.0

    fun luminance(color: Int): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** 在 [bg] 上找一个看得清的文字色；原色够清楚就原样返回。 */
    fun readableOn(bg: Int, fg: Int, min: Double = MIN_TEXT): Int {
        if (ratio(bg, fg) >= min) return fg
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        return if (ratio(bg, black) >= ratio(bg, white)) black else white
    }

    /**
     * 半透明底色的可读性兜底。
     *
     * 底色带 alpha 时真正的对比度取决于背后是什么（照片、壁纸、App 界面都可能），
     * 这里按「叠在中性灰上」估算：alpha 越低越接近灰色，文字就按灰色去挑黑/白。
     */
    fun readableOnAlpha(
        bgArgb: Int,
        alphaPercent: Int,
        fg: Int,
        min: Double = MIN_TEXT,
        backdrop: Int = 0xFF808080.toInt(),
    ): Int {
        val a = (alphaPercent.coerceIn(0, 100)) / 100.0
        fun mix(c: Int, b: Int): Int = (c * a + b * (1 - a)).toInt().coerceIn(0, 255)
        val composited =
            Color.rgb(
                mix(Color.red(bgArgb), Color.red(backdrop)),
                mix(Color.green(bgArgb), Color.green(backdrop)),
                mix(Color.blue(bgArgb), Color.blue(backdrop)),
            )
        return readableOn(composited, fg, min)
    }

}
