package com.osfans.trime.ui.main.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import com.osfans.trime.ui.common.ensureRestoreButton

/**
 * 带「拖动气泡」的滑块。
 *
 * - 说明行常驻「…（当前 90%，默认 90%）」，随时能看到当前值和默认值；
 * - 拖动过程中气泡跟着拇指走，松手 600ms 后消失；
 * - 气泡是**滑槽所在行的普通子 View**（把 SeekBar 套进一个 FrameLayout 再挂一个 TextView），
 *   不用 `PopupWindow`：没有窗口令牌、没有跨窗口坐标、也不受列表滚动影响，一定画得出来。
 */
class ValueSliderPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : SeekBarPreference(context, attrs),
        SeekBar.OnSeekBarChangeListener {
        /** 说明文字的正文部分（不含当前值/默认值）。 */
        var description: String = ""

        /** 数值后缀，例如 `%` 或 ` 毫秒`（可带前导空格）。 */
        var unit: String = "%"

        /** 默认值：「恢复默认」用，也显示在说明里。 */
        var defaultValue: Int = 0

        /** 行尾「↺ 恢复默认」按钮的动作；为 null 时不显示按钮。 */
        var onRestore: (() -> Unit)? = null

        private var bubbleHost: FrameLayout? = null
        private var bubbleLabel: TextView? = null
        private val hideHandler = Handler(Looper.getMainLooper())
        private val hideTask = Runnable { dismissBubble() }

        /** 说明行的完整文字：「说明（当前 X，默认 Y）」。 */
        private fun summaryText(): CharSequence {
            val shown = getValue()
            return if (description.isEmpty()) {
                "当前 $shown$unit，默认 $defaultValue$unit"
            } else {
                "$description（当前 $shown$unit，默认 $defaultValue$unit）"
            }
        }

        /**
         * 刷新说明行（会走 `setSummary` → `notifyChanged`）。
         *
         * **只能在非布局阶段调用**（拖动、点恢复按钮之后）。绑定阶段请用 [bindSummaryText]，
         * 否则 RecyclerView 正在布局时 `notifyItemChanged` 会抛
         * `IllegalStateException: Cannot call this method while RecyclerView is computing a layout`。
         */
        fun refreshSummary() {
            summary = summaryText()
        }

        /** 绑定阶段直接改说明 TextView 的文字，不触发 notifyChanged。 */
        private fun bindSummaryText(holder: PreferenceViewHolder) {
            val view = holder.itemView.findViewById<TextView>(android.R.id.summary) ?: return
            view.text = summaryText()
            view.visibility = View.VISIBLE
        }

        /** 恢复默认值（走 [callChangeListener]，等价于手动拖回默认）。 */
        fun restoreDefault() {
            if (getValue() == defaultValue) {
                refreshSummary()
                return
            }
            if (callChangeListener(defaultValue)) {
                value = defaultValue
            }
            refreshSummary()
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            // 这里不能调 refreshSummary()：它走 setSummary → notifyChanged，
            // 而绑定发生在 RecyclerView 布局过程中，会直接抛 IllegalStateException（进页面就崩）。
            bindSummaryText(holder)
            val seekBar = findSeekBar(holder.itemView) ?: return
            wrapWithBubbleHost(seekBar)
            seekBar.setOnSeekBarChangeListener(this)
            holder.ensureRestoreButton(onRestore)
        }

        override fun onProgressChanged(
            seekBar: SeekBar,
            progress: Int,
            fromUser: Boolean,
        ) {
            if (!fromUser) return
            val wanted = progress + min
            if (wanted != value) {
                // 我们换掉了框架的监听器，这里必须自己回调 callChangeListener，
                // 否则 OnPreferenceChangeListener 不触发、设置不落盘（表现为「拖了没生效还退回去」）。
                if (callChangeListener(wanted)) {
                    value = wanted
                } else {
                    seekBar.progress = value - min
                }
            }
            refreshSummary()
            showBubble(seekBar)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            hideHandler.removeCallbacks(hideTask)
            showBubble(seekBar)
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            hideHandler.removeCallbacks(hideTask)
            hideHandler.postDelayed(hideTask, 600)
        }

        override fun onDetached() {
            hideHandler.removeCallbacks(hideTask)
            dismissBubble()
            super.onDetached()
        }

        private fun findSeekBar(view: View): SeekBar? {
            if (view is SeekBar) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val found = findSeekBar(view.getChildAt(i))
                    if (found != null) return found
                }
            }
            return null
        }

        /** 把 SeekBar 套进一个 FrameLayout，并在里面挂气泡（同一个 SeekBar 只做一次）。 */
        private fun wrapWithBubbleHost(seekBar: SeekBar) {
            val current = bubbleHost
            if (current != null && seekBar.parent === current) return
            val parent = seekBar.parent as? ViewGroup ?: return
            val index = parent.indexOfChild(seekBar)
            if (index < 0) return
            val params = seekBar.layoutParams ?: return
            parent.removeViewAt(index)
            val host =
                FrameLayout(context).apply {
                    clipChildren = false
                    clipToPadding = false
                }
            // SeekBar 在原生布局里常是 width=0dp + layout_weight=1（靠父容器权重撑开），
            // 搬进 FrameLayout 后没有权重可用，宽度会变成 0 —— 滑块连横线一起消失。
            // 所以宽度为 0 时改成 MATCH_PARENT，让宿主（它拿的是原 params，含权重）撑开它。
            val seekBarWidth =
                if (params.width <= 0) ViewGroup.LayoutParams.MATCH_PARENT else params.width
            host.addView(seekBar, FrameLayout.LayoutParams(seekBarWidth, params.height))
            val label = newBubbleLabel()
            host.addView(
                label,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.TOP or Gravity.START },
            )
            parent.addView(host, index, params)
            bubbleHost = host
            bubbleLabel = label
        }

        private fun newBubbleLabel(): TextView =
            TextView(context).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(9).toFloat()
                        setColor(0xE6000000.toInt())
                    }
                visibility = View.GONE
            }

        private fun showBubble(seekBar: SeekBar) {
            val host = bubbleHost ?: return
            if (seekBar.parent !== host) wrapWithBubbleHost(seekBar)
            val label = bubbleLabel ?: return
            if (label.parent == null) return
            label.text = "${getValue()}$unit"
            label.visibility = View.VISIBLE
            label.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val bubbleWidth = label.measuredWidth
            val trackWidth = if (host.width > 0) host.width else seekBar.width
            if (trackWidth <= 0) return
            // 拇指真实位置（minSdk 29 起有 getThumb），比自己按比例估算准
            val thumbX =
                runCatching { seekBar.thumb?.bounds?.centerX()?.toFloat() }
                    .getOrNull() ?: (seekBar.width / 2f)
            val maxX = (trackWidth - bubbleWidth).coerceAtLeast(0).toFloat()
            label.translationX = (thumbX - bubbleWidth / 2f).coerceIn(0f, maxX)
            label.translationY = 0f
        }

        private fun dismissBubble() {
            bubbleLabel?.visibility = View.GONE
        }

        private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    }
