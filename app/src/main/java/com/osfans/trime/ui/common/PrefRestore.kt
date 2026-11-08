/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat

/**
 * 设置项右侧的「↺ 恢复默认」按钮。
 *
 * 只占**同一行**结尾的一小块（标题行最右边，紧挨开关/数值），不再单独占一行——设置项多的页面
 * 如果每条都多一行，列表会长一倍，找起来更费劲。
 *
 * 挂载点按顺序尝试：
 * 1. `UnPressableLinearLayout` —— **滑块行（SeekBarPreference）专用**，它的布局里根本没有
 *    `android.R.id.widget_frame`，按钮要和 SeekBar 排在同一行右侧（`seekbar_value` 隐藏时右边空着）；
 * 2. `android.R.id.widget_frame` —— 开关/普通行的行尾 FrameLayout；
 * 3. 整行容器末尾（兜底）。
 *
 * 按钮做成 44dp 最小热区 + 无边界水波纹，纯 `↺` 字符太小点不中。
 */
internal const val RESTORE_BUTTON_TAG = "bamboo_restore_button"

/** 给一行偏好注入（或隐藏）「↺」按钮；[onRestore] 为 null 时隐藏。 */
internal fun PreferenceViewHolder.ensureRestoreButton(onRestore: (() -> Unit)?) {
    val row = itemView as? ViewGroup ?: return
    val existing = row.findViewWithTag<TextView>(RESTORE_BUTTON_TAG)
    if (onRestore == null) {
        existing?.visibility = View.GONE
        return
    }
    val button =
        existing ?: run {
            val ctx = row.context
            val density = ctx.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val view =
                TextView(ctx).apply {
                    tag = RESTORE_BUTTON_TAG
                    text = "↺"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    alpha = 0.75f
                    // 44dp 最小热区：纯字符太小，手指点不中
                    minWidth = dp(44)
                    minHeight = dp(44)
                    setPadding(dp(8), 0, dp(8), 0)
                    val typed = TypedValue()
                    if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typed, true)) {
                        setBackgroundResource(typed.resourceId)
                    }
                }
            val host = findRestoreHost(row)
            if (host != null) {
                val lp =
                    if (host is LinearLayout) {
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { gravity = Gravity.CENTER_VERTICAL }
                    } else {
                        // 交给 ViewGroup.addView 自己按宿主类型转换（FrameLayout 等会 convert），
                        // 不能用 generateDefaultLayoutParams()——那是 protected。
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    }
                host.addView(view, lp)
            }
            view
        }
    button.visibility = View.VISIBLE
    button.setOnClickListener { onRestore() }
}

/** 深度优先找滑块行的 `UnPressableLinearLayout`（SeekBarPreference 里包着 SeekBar 的那层）。 */
private fun findSeekBarRow(root: ViewGroup): LinearLayout? {
    for (i in 0 until root.childCount) {
        val child = root.getChildAt(i)
        if (child is ViewGroup) {
            if (child.javaClass.name.endsWith("UnPressableLinearLayout")) {
                return child as? LinearLayout
            }
            findSeekBarRow(child)?.let { return it }
        }
    }
    return null
}

/** 按 滑块行 → widget_frame → 整行 的顺序选挂载点。 */
private fun findRestoreHost(row: ViewGroup): ViewGroup? =
    findSeekBarRow(row)
        ?: row.findViewById<ViewGroup>(android.R.id.widget_frame)
        ?: row

/** 带「↺ 恢复默认」按钮的开关。 */
open class BambooSwitch
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : SwitchPreferenceCompat(context, attrs) {
        var onRestore: (() -> Unit)? = null

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            holder.ensureRestoreButton(onRestore)
        }
    }

/** 带「↺ 恢复默认」按钮的普通可点条目。 */
open class BambooClick
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs) {
        var onRestore: (() -> Unit)? = null

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            holder.ensureRestoreButton(onRestore)
        }
    }

/**
 * 重建整个设置页，但**保持滚动位置**。
 *
 * 设置页每次改一项都 `preferenceScreen.removeAll()` + 重新建页，RecyclerView 会滚回顶部；
 * 列表一长，调一项就丢位置（恢复默认尤其明显）。这里记下「第一个可见项 + 它的顶边偏移」，
 * 重建完成后按同样的位置滚回去。
 */
private fun findRecyclerView(root: android.view.View?): RecyclerView? {
    if (root == null) return null
    if (root is RecyclerView) return root
    if (root is android.view.ViewGroup) {
        for (i in 0 until root.childCount) {
            findRecyclerView(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}

internal fun Fragment.rebuildPreferencesKeepingScroll(rebuild: () -> Unit) {
    val recycler = findRecyclerView(view)
    val manager = recycler?.layoutManager as? LinearLayoutManager
    val position = manager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
    val offset =
        if (position != RecyclerView.NO_POSITION) {
            manager?.findViewByPosition(position)?.top ?: 0
        } else {
            0
        }
    rebuild()
    if (recycler != null && position != RecyclerView.NO_POSITION) {
        recycler.post {
            (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, offset)
        }
    }
}
