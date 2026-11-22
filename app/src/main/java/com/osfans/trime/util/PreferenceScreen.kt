/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import splitties.resources.drawable
import splitties.resources.styledColor
import com.osfans.trime.ui.common.BambooClick

fun PreferenceScreen.addCategory(title: String, block: PreferenceCategory.() -> Unit) {
    val category = PreferenceCategory(context).apply {
        setTitle(title)
    }
    addPreference(category)
    block.invoke(category)
}

fun PreferenceScreen.addCategory(@StringRes title: Int, block: PreferenceCategory.() -> Unit) {
    addCategory(context.getString(title), block)
}

fun Preference.setup(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    isSingleLineTitle = false
    setTitle(title)
    setSummary(summary)
    if (icon == null) {
        isIconSpaceReserved = false
    } else {
        setIcon(
            context.drawable(icon)?.apply {
                setTint(context.styledColor(android.R.attr.colorControlNormal))
            },
        )
    }
    onClick?.also {
        setOnPreferenceClickListener { _ ->
            it.invoke()
            true
        }
    }
}

fun PreferenceGroup.addPreference(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    // 用 BambooClick 而不是裸 Preference：它能在行尾挂「↺ 恢复默认」按钮（见 PrefRestore.kt）
    addPreference(BambooClick(context).apply { setup(title, summary, icon, onClick) })
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    summary: String,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    addPreference(context.getString(title), summary, icon, onClick)
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    addPreference(context.getString(title), summary?.let(context::getString), icon, onClick)
}

class LongClickPreference(context: Context) : Preference(context) {
    private var onLongClick: (() -> Unit)? = null

    fun setOnPreferenceLongClickListener(callback: (() -> Unit)? = null) {
        onLongClick = callback
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke()
            true
        }
    }
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    addPreference(
        LongClickPreference(context).apply {
            setup(context.getString(title), summary?.let { context.getString(it) }, icon, onClick)
            setOnPreferenceLongClickListener(onLongClick)
        },
    )
}

/**
 * 需要醒目的入口统一用酒红，全 App 只此一处强调色。
 * 浅底用本色，深底自动换亮一档 —— 同一个色值在深色主题上会暗到看不出来。
 */
@ColorInt
val ACCENT_RED: Int = 0xFF8E3B30.toInt()

/** 深色背景上用的酒红（亮两档，仍属酒红）。 */
@ColorInt
val ACCENT_RED_ON_DARK: Int = 0xFFD9695A.toInt()

private fun luminance(@ColorInt c: Int): Float {
    val f = { v: Int -> v / 255f }
    val r = f((c shr 16) and 0xFF)
    val g = f((c shr 8) and 0xFF)
    val b = f(c and 0xFF)
    return 0.299f * r + 0.587f * g + 0.114f * b
}

/** 按当前背景亮度选一个看得清的酒红。 */
@ColorInt
fun accentColorFor(context: Context): Int {
    val bg = runCatching { context.styledColor(android.R.attr.colorBackground) }.getOrNull()
    return if (bg == null || luminance(bg) >= 0.5f) ACCENT_RED else ACCENT_RED_ON_DARK
}

/** 把标题染成指定颜色（另一条腿见 [AccentClick]，两条一起上更稳）。 */
fun accentTitle(
    text: String,
    @ColorInt color: Int = ACCENT_RED,
): CharSequence =
    SpannableString(text).apply {
        setSpan(ForegroundColorSpan(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

/** 强调行的底色（酒红）与文字色（白），固定值、不跟主题走。 */
@ColorInt
val ACCENT_ROW_BG: Int = 0xFF8E3B30.toInt()

@ColorInt
val ACCENT_ROW_TEXT: Int = 0xFFFFFFFF.toInt()

/** 摘要用略透的白，保证在主色块上仍然清楚。 */
@ColorInt
val ACCENT_ROW_SUMMARY: Int = 0xE6FFFFFF.toInt()

private fun dpOf(
    context: Context,
    value: Int,
): Int = (value * context.resources.displayMetrics.density).toInt()

/** 淡红底（酒红 25%），用于「左侧竖条」那种轻强调。 */
@ColorInt
val ACCENT_BAR_BG: Int = 0xFF3C2320.toInt()

/** 左侧竖条上的米色文字。 */
@ColorInt
val ACCENT_BAR_TEXT: Int = 0xFFFFFFFF.toInt()

@ColorInt
val ACCENT_BAR_SUMMARY: Int = 0xE6FFFFFF.toInt()

/**
 * 「行不动、标题后面挂一个小胶囊」用的 span。
 * 自己画圆角矩形，所以不需要额外的布局或 View。
 */
private class ChipSpan(
    private val label: String,
    @ColorInt private val bg: Int,
    @ColorInt private val fg: Int,
    private val padH: Float,
    private val padV: Float,
    private val radius: Float,
) : android.text.style.ReplacementSpan() {
    private val paint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    override fun getSize(
        paint: android.graphics.Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: android.graphics.Paint.FontMetricsInt?,
    ): Int {
        this.paint.textSize = paint.textSize * 0.74f
        return (this.paint.measureText(label) + padH * 2).toInt()
    }

    override fun draw(
        canvas: android.graphics.Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: android.graphics.Paint,
    ) {
        this.paint.textSize = paint.textSize * 0.74f
        this.paint.color = fg
        val w = this.paint.measureText(label)
        val h = (bottom - top) - padV * 2
        val rect =
            android.graphics.RectF(
                x + padH * 0.4f,
                top + padV,
                x + padH * 0.4f + w + padH * 2,
                top + padV + h,
            )
        val fill =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        canvas.drawRoundRect(rect, radius, radius, fill)
        val baseline = rect.centerY() - (this.paint.descent() + this.paint.ascent()) / 2f
        canvas.drawText(label, rect.left + padH, baseline, this.paint)
    }
}

/** D：行保持原样，标题后面挂一个「新手用户」胶囊。 */
open class AccentChipRow(
    context: Context,
    private val chip: String = "新手用户",
) : BambooClick(context) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        runCatching {
            val tv = holder.itemView.findViewById<android.widget.TextView>(android.R.id.title) ?: return@runCatching
            val base = tv.text?.toString().orEmpty()
            if (base.endsWith(chip)) return@runCatching
            val density = holder.itemView.resources.displayMetrics.density
            val span =
                ChipSpan(
                    label = chip,
                    bg = ACCENT_ROW_BG,
                    fg = ACCENT_ROW_TEXT,
                    padH = 6f * density,
                    padV = 2f * density,
                    radius = 9f * density,
                )
            val text = base + " "
            tv.text =
                SpannableString(text + chip).apply {
                    setSpan(span, text.length, text.length + chip.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
        }
    }
}

/** 背景：圆角淡红底 + 左侧一条实心酒红竖条。 */
private class BarBackground(
    @ColorInt private val bg: Int,
    @ColorInt private val bar: Int,
    private val radius: Float,
    private val barWidth: Float,
    private val barInset: Float,
) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        paint.color = bg
        canvas.drawRoundRect(android.graphics.RectF(b), radius, radius, paint)
        paint.color = bar
        val barRect =
            android.graphics.RectF(
                b.left.toFloat(),
                b.top + barInset,
                b.left + barWidth,
                b.bottom - barInset,
            )
        canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** C：淡红底 + 左侧竖条，文字改米色。 */
open class AccentBarRow(context: Context) : BambooClick(context) {
    override val selfStyled: Boolean get() = true

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        runCatching {
            val ctx = holder.itemView.context
            val bg =
                BarBackground(
                    bg = ACCENT_BAR_BG,
                    bar = ACCENT_ROW_BG,
                    radius = dpOf(ctx, 8).toFloat(),
                    barWidth = dpOf(ctx, 4).toFloat(),
                    barInset = dpOf(ctx, 10).toFloat(),
                )
            holder.itemView.background =
                android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                    bg,
                    null,
                )
            holder.itemView.findViewById<android.widget.TextView>(android.R.id.title)
                ?.setTextColor(ACCENT_BAR_TEXT)
            holder.itemView.findViewById<android.widget.TextView>(android.R.id.summary)
                ?.setTextColor(ACCENT_BAR_SUMMARY)
            holder.itemView.findViewById<android.widget.ImageView>(android.R.id.icon)
                ?.setColorFilter(ACCENT_BAR_TEXT, android.graphics.PorterDuff.Mode.SRC_IN)
        }
    }
}

/** 标题带红的行（保留给需要「只红字」的场合）。 */
open class AccentClick(context: Context) : BambooClick(context) {
    override val selfStyled: Boolean get() = true

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        runCatching {
            val color = accentColorFor(holder.itemView.context)
            holder.itemView.findViewById<android.widget.TextView>(android.R.id.title)?.setTextColor(color)
        }
    }
}

/** 行不动、标题后面挂「新手用户」胶囊：首页那个入口用它。 */
fun PreferenceGroup.addAccentChipPreference(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    chip: String = "新手用户",
    onClick: (() -> Unit)? = null,
) {
    addPreference(
        AccentChipRow(context, chip).apply {
            setup(title, summary, icon, onClick)
        },
    )
}

/** 淡红底 + 左侧竖条：设置页里的「设置向导」用它。 */
fun PreferenceGroup.addAccentBarPreference(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    addPreference(
        AccentBarRow(context).apply {
            setup(title, summary, icon, onClick)
        },
    )
}
