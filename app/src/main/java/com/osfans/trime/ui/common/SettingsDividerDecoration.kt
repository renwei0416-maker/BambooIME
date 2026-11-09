/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.osfans.trime.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * 在分类标题上方画一条 1dp 的分隔线。
 *
 * Preference 库自带的 item divider 用的是资源里写死的颜色，主题换不掉，
 * 所以整个摘掉（见 [SettingsStyler.applyPreferenceList]），改由这里绘制，
 * 颜色与线宽都取当前主题解析出来的值。
 */
class SettingsDividerDecoration(context: Context, color: Int) : RecyclerView.ItemDecoration() {
    private val height = (context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private val paint = Paint().apply {
        this.color = color
        isAntiAlias = false
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        for (index in 0 until parent.childCount) {
            val child: View = parent.getChildAt(index)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val preference = runCatching { adapter.getItem(position) }.getOrNull()
            if (preference !is PreferenceCategory) continue
            val top = child.top + child.translationY
            canvas.drawRect(0f, top, parent.width.toFloat(), top + height, paint)
        }
    }
}
