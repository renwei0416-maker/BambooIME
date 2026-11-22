/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.osfans.trime.ui.common

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R

/**
 * 把设计 token 刷到设置界面的窗口、工具栏和列表上。
 *
 * P0 视觉重构后这里**不再使用主题派生的 6 个颜色**：底色、卡片、文字、分隔线
 * 一律取 [SettingsTokens]（固定值），只有强调色可能跟随键盘主题。
 * 为了不动调用方，`applyWindow` / `applyToolbar` / `applyPreferenceList`
 * 仍保留原来的 `SettingsPalette` 参数，内部忽略其颜色值。
 */
object SettingsStyler {
    private const val SIDE_PADDING_DP = 20f
    private const val CATEGORY_TEXT_SP = 13f

    /** 窗口底色与状态栏 / 导航栏图标的深浅。 */
    fun applyWindow(activity: Activity, palette: SettingsPalette) {
        val tokens = SettingsTokensResolver.resolve(activity)
        val window = activity.window
        window.setBackgroundDrawable(ColorDrawable(tokens.background))
        window.decorView.setBackgroundColor(tokens.background)
        @Suppress("DEPRECATION")
        window.statusBarColor = tokens.background
        @Suppress("DEPRECATION")
        window.navigationBarColor = tokens.background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !tokens.isDark
            isAppearanceLightNavigationBars = !tokens.isDark
        }
    }

    /** 顶部工具栏：卡片色作底、主文字色作字。 */
    fun applyToolbar(toolbar: Toolbar?, palette: SettingsPalette) {
        if (toolbar == null) return
        val tokens = SettingsTokensResolver.resolve(toolbar.context)
        toolbar.background = ColorDrawable(tokens.background)
        toolbar.setTitleTextColor(tokens.textPrimary)
        toolbar.setSubtitleTextColor(tokens.textSecondary)
        val tint = ColorStateList.valueOf(tokens.textPrimary)
        toolbar.navigationIcon = toolbar.navigationIcon?.apply { setTintList(tint) }
        toolbar.overflowIcon = toolbar.overflowIcon?.apply { setTintList(tint) }
    }

    /** 工具栏菜单图标同样跟主文字色走。 */
    fun applyToolbarMenu(
        menu: Menu,
        palette: SettingsPalette,
    ) {
        val tint = ColorStateList.valueOf(palette.textPrimary)
        for (index in 0 until menu.size()) menu.getItem(index).icon?.setTintList(tint)
    }

    /**
     * 设置页列表：固定底色 + 左右 20dp 留白 + 卡片分组装饰 + 行内文字/控件着色。
     *
     * 注意：卡片的圆角背景由 [SettingsCardDecoration] 画在子 View 下面，
     * 行本身保持透明，这样点击水波纹仍然完整。
     */
    fun applyPreferenceList(list: RecyclerView, palette: SettingsPalette) {
        val tokens = SettingsTokensResolver.resolve(list.context)
        val side = (SIDE_PADDING_DP * list.resources.displayMetrics.density).toInt()
        list.setBackgroundColor(tokens.background)
        list.clipToPadding = false
        list.setPadding(side, 0, side, 0)
        while (list.itemDecorationCount > 0) list.removeItemDecorationAt(0)
        list.addItemDecoration(SettingsCardDecoration(list.context, tokens.card, tokens.divider))
        list.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) = applyRowIn(list, view, tokens)

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            },
        )
        for (index in 0 until list.childCount) applyRowIn(list, list.getChildAt(index), tokens)
    }

    /** 单行：标题用主文字色，摘要与其余文字用次文字色。 */
    fun applyRow(row: View, palette: SettingsPalette) {
        tintText(row, SettingsTokensResolver.resolve(row.context), isCategory = false)
    }

    private fun applyRowIn(list: RecyclerView, view: View, tokens: SettingsTokens) {
        val adapter = list.adapter as? PreferenceGroupAdapter
        val position = list.getChildAdapterPosition(view)
        val item = if (adapter == null || position == RecyclerView.NO_POSITION) {
            null
        } else {
            runCatching { adapter.getItem(position) }.getOrNull()
        }
        if (item is BambooClick && item.selfStyled) return
        tintText(view, tokens, isCategory = item is PreferenceCategory)
    }

    private fun tintText(view: View, tokens: SettingsTokens, isCategory: Boolean) {
        when (view.id) {
            R.id.bamboo_cat_bar -> view.visibility = View.GONE
            R.id.bamboo_cat_title -> (view as? TextView)?.apply {
                setTextColor(tokens.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, CATEGORY_TEXT_SP)
            }
            R.id.bamboo_cat_subtitle -> (view as? TextView)?.apply {
                setTextColor(tokens.textSecondary)
                alpha = 0.75f
            }
            android.R.id.title -> (view as? TextView)?.apply {
                if (isCategory) {
                    setTextColor(tokens.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, CATEGORY_TEXT_SP)
                } else {
                    setTextColor(tokens.textPrimary)
                }
            }
            android.R.id.summary -> (view as? TextView)?.setTextColor(tokens.textSecondary)
            android.R.id.icon -> (view as? ImageView)?.setColorFilter(tokens.accent, PorterDuff.Mode.SRC_IN)
            else -> when (view) {
                is SwitchCompat -> tintSwitch(view, tokens)
                is SeekBar -> tintSeekBar(view, tokens)
                is TextView -> setTextColorSafe(view, tokens.textSecondary)
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) tintText(view.getChildAt(index), tokens, isCategory)
        }
    }

    private fun setTextColorSafe(view: TextView, color: Int) {
        // 数值（右侧当前值）用强调色更醒目；其余说明文字保持次文字色
        view.setTextColor(color)
    }

    private fun tintSwitch(view: SwitchCompat, tokens: SettingsTokens) {
        val thumb = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(tokens.accent, if (tokens.isDark) 0xFF6E7773.toInt() else 0xFFC9D1CC.toInt()),
        )
        val track = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(withAlpha(tokens.accent, 0.55f), if (tokens.isDark) 0xFF333A38.toInt() else 0xFFDDE4DF.toInt()),
        )
        runCatching {
            view.thumbTintList = thumb
            view.trackTintList = track
        }
    }

    private fun tintSeekBar(view: SeekBar, tokens: SettingsTokens) {
        runCatching {
            view.progressTintList = ColorStateList.valueOf(tokens.accent)
            view.progressBackgroundTintList =
                ColorStateList.valueOf(if (tokens.isDark) 0xFF333A38.toInt() else 0xFFDDE4DF.toInt())
            view.thumbTintList = ColorStateList.valueOf(tokens.accent)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}
