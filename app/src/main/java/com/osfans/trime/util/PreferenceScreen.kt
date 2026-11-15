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

/** 需要醒目的入口统一用这个色（酒红），全 App 只此一处强调色。 */
@ColorInt
val ACCENT_RED: Int = 0xFF8E3B30.toInt()

/** 把标题染成指定颜色。 */
fun accentTitle(text: String, @ColorInt color: Int = ACCENT_RED): CharSequence =
    SpannableString(text).apply {
        setSpan(ForegroundColorSpan(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

/** 标题带色的行：用于「万象拼音」「设置向导」这类要抓眼球的入口。 */
fun PreferenceGroup.addAccentPreference(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    @ColorInt color: Int = ACCENT_RED,
    onClick: (() -> Unit)? = null,
) {
    addPreference(BambooClick(context).apply { setup(title, summary, icon, onClick) })
    val last = getPreference(preferenceCount - 1)
    last.title = accentTitle(title, color)
}
