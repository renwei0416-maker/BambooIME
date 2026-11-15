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

/**
 * 标题带色的行。除了 span，还在 bind 时直接给标题 TextView 上色：
 * 有的主题布局会另建一个 TextView，只靠 span 不生效。
 */
open class AccentClick(context: Context) : BambooClick(context) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        runCatching {
            val color = accentColorFor(holder.itemView.context)
            holder.itemView.findViewById<android.widget.TextView>(android.R.id.title)?.setTextColor(color)
        }
    }
}

/** 标题带色的行：用于「万象拼音」「设置向导」这类要抓眼球的入口。 */
fun PreferenceGroup.addAccentPreference(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    addPreference(
        AccentClick(context).apply {
            setup(title, summary, icon, onClick)
            this.title = accentTitle(title, accentColorFor(context))
        },
    )
}
