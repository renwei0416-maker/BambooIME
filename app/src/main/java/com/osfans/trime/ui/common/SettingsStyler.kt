/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.osfans.trime.ui.common

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R

/**
 * 把 [SettingsPalette] 刷到设置界面的窗口、工具栏和列表行上。
 */
object SettingsStyler {
    /** 窗口底色与状态栏 / 导航栏图标的深浅。 */
    fun applyWindow(activity: Activity, palette: SettingsPalette) {
        val window = activity.window
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        window.decorView.setBackgroundColor(palette.background)
        @Suppress("DEPRECATION")
        window.statusBarColor = palette.background
        @Suppress("DEPRECATION")
        window.navigationBarColor = palette.background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    /** 顶部工具栏用主文字色作底、背景色作字，保证与页面同源。 */
    fun applyToolbar(toolbar: Toolbar?, palette: SettingsPalette) {
        if (toolbar == null) return
        toolbar.background = ColorDrawable(palette.textPrimary)
        toolbar.setTitleTextColor(palette.background)
        toolbar.setSubtitleTextColor(palette.background)
        val tint = ColorStateList.valueOf(palette.background)
        toolbar.navigationIcon = toolbar.navigationIcon?.apply { setTintList(tint) }
        toolbar.overflowIcon = toolbar.overflowIcon?.apply { setTintList(tint) }
    }

    /** 工具栏菜单图标跟着工具栏底色走，否则深色主题下白图标会看不见。 */
    fun applyToolbarMenu(
        menu: Menu,
        palette: SettingsPalette,
    ) {
        val tint = ColorStateList.valueOf(palette.background)
        for (index in 0 until menu.size()) menu.getItem(index).icon?.setTintList(tint)
    }

    /** 设置页列表：底色 + 行文字颜色。行背景保持透明，以免盖掉点击水波纹。 */
    fun applyPreferenceList(list: RecyclerView, palette: SettingsPalette) {
        list.setBackgroundColor(palette.background)
        while (list.itemDecorationCount > 0) list.removeItemDecorationAt(0)
        list.addItemDecoration(SettingsDividerDecoration(list.context, palette.divider))
        list.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) = applyRow(view, palette)

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            },
        )
        for (index in 0 until list.childCount) applyRow(list.getChildAt(index), palette)
    }

    /** 单行：标题用主文字色，摘要与其余文字用次文字色。 */
    fun applyRow(row: View, palette: SettingsPalette) {
        tintText(row, palette)
    }

    private fun tintText(view: View, palette: SettingsPalette) {
        when (view.id) {
            R.id.bamboo_cat_bar -> view.setBackgroundColor(palette.accent)
            R.id.bamboo_cat_title -> (view as? TextView)?.setTextColor(palette.accent)
            R.id.bamboo_cat_subtitle -> (view as? TextView)?.setTextColor(palette.textSecondary)
            android.R.id.title -> (view as? TextView)?.setTextColor(palette.textPrimary)
            android.R.id.icon -> (view as? ImageView)?.setColorFilter(palette.accent, PorterDuff.Mode.SRC_IN)
            else -> if (view is TextView) view.setTextColor(palette.textSecondary)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) tintText(view.getChildAt(index), palette)
        }
    }
}
