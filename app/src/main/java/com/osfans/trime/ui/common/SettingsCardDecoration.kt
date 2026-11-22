/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.osfans.trime.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * 把设置列表画成「卡片分组」。
 *
 * 为什么用 ItemDecoration 而不是给每行加背景：
 * 设置页的行是代码里 new Preference 拼出来的（`res/xml` 里没有布局），
 * 逐页包一层卡片容器既侵入又容易踩到点击水波纹。用装饰器在子 View **下面**
 * 画一层圆角矩形，就能让任何用系统 Preference 生成的页面自动获得卡片外观，
 * 且完全不碰行的测量与点击。
 *
 * 分组规则：以 [PreferenceCategory] 为界——分类标题之后的连续若干行算一组，
 * 组与组之间留白。没有分类标题的页面（上游页）整页算一组。
 */
class SettingsCardDecoration(
    context: Context,
    private val cardColor: Int,
    private val dividerColor: Int,
) : RecyclerView.ItemDecoration() {
    private val density = context.resources.displayMetrics.density
    private val radius = 16f * density
    private val sidePadding = 20f * density
    private val dividerInset = 16f * density
    private val dividerHeight = (1f * density).coerceAtLeast(1f)
    private val groupGap = 20f * density

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardColor }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dividerColor
        strokeWidth = dividerHeight
    }
    private val path = Path()
    private val rect = RectF()

    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val item = runCatching { adapter.getItem(position) }.getOrNull() ?: return
        if (item is PreferenceCategory) {
            // 分组标题自带间距：让上方的卡片与标题之间透气
            outRect.top = groupGap.toInt()
        }
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        val childCount = parent.childCount
        if (childCount == 0) return
        var index = 0
        while (index < childCount) {
            val child = parent.getChildAt(index)
            val position = parent.getChildAdapterPosition(child)
            val item = if (position == RecyclerView.NO_POSITION) {
                null
            } else {
                runCatching { adapter.getItem(position) }.getOrNull()
            }
            if (item == null || item is PreferenceCategory) {
                index++
                continue
            }
            // 一组：从这里往后连续的普通行
            var last = index
            var cursor = index + 1
            while (cursor < childCount) {
                val next = parent.getChildAt(cursor)
                val nextPos = parent.getChildAdapterPosition(next)
                val nextItem = if (nextPos == RecyclerView.NO_POSITION) {
                    null
                } else {
                    runCatching { adapter.getItem(nextPos) }.getOrNull()
                }
                if (nextItem == null || nextItem is PreferenceCategory) break
                last = cursor
                cursor++
            }
            drawGroup(canvas, parent, parent.getChildAt(index), parent.getChildAt(last))
            index = last + 1
        }
    }

    private fun drawGroup(canvas: Canvas, parent: RecyclerView, first: View, last: View) {
        val left = first.left.toFloat().coerceAtLeast(sidePadding)
        val right = first.right.toFloat().coerceAtMost(parent.width - sidePadding)
        if (right <= left) return
        val top = first.top + first.translationY
        val bottom = last.top + last.translationY + last.height
        rect.set(left, top, right, bottom)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.drawPath(path, cardPaint)

        // 组内分隔线：左右各缩进，不通栏
        var index = parent.indexOfChild(first)
        val lastIndex = parent.indexOfChild(last)
        while (index < lastIndex) {
            val child = parent.getChildAt(index)
            val y = child.top + child.translationY + child.height
            canvas.drawLine(
                left + dividerInset,
                y,
                right - dividerInset,
                y,
                dividerPaint,
            )
            index++
        }
    }
}
