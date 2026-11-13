/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.text.buildSpannedString
import com.osfans.trime.actions.glossary.GlossaryPrefs
import com.osfans.trime.actions.glossary.GlossaryTips
import com.osfans.trime.actions.handwriting.HandwritingLog
import com.osfans.trime.actions.tips.TipBlocks
import com.osfans.trime.actions.tips.TipEntry
import com.osfans.trime.actions.wenquxing.WenquxingPrefs
import com.osfans.trime.actions.wenquxing.WenquxingTips
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.core.TrimeInputMethodService
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.horizontalPadding

/**
 * 编码区。
 *
 * 上面那一行 `preedit` 保持上游原样：只画 librime 给的编码 + 方案给的 prompt，
 * 单行、超长省略、点击定位；我们**不往里面拼自己的东西**。
 *
 * 下面挂着**两块彼此独立的提示区**：
 * - [wqTips] 文曲星（诗词 / 名句）
 * - [glTips] 外语学习（候选 + 英文 / 日文）
 *
 * ## 提示区为什么是「ScrollView + 每条一个 View」
 *
 * 和符号面板同构：滚动交给 `ScrollView`（原生惯性），点击交给每一行自己的
 * `OnClickListener`。**不在一段文本里用手算坐标做命中判定**——那条路试过十几版，
 * 折行、滚动、展开交错时必然出错（点不动、点到隔壁、滑一半卡住）。
 *
 * ## 唯一的一条显示规则
 *
 * 文曲星这一句有命中时，**只显示文曲星那一块**，外语学习强制隐藏；
 * 文曲星没命中时才轮到外语学习（它自己没命中就不显示）。
 *
 * ## 每块的状态
 *
 * - `snapshot`：这一批候选的内容指纹，**一变就丢弃展开态与滚动位置**。
 * - `expandedIndex`：展开的是第几条，`-1` 表示列表态。
 * - 高度：列表态 = 前「显示几条」条之和（超出才能滚）；展开态 = 展开行数与屏高 40% 取小。
 */
open class PreeditUi(
    final override val ctx: Context,
    private val theme: Theme,
    private val setupPreeditView: (TextView.() -> Unit)? = null,
    private val onMoveCursor: ((Int) -> Unit)? = null,
    private val tipsOwner: Int = OWNER_KEYBOARD,
) : Ui {
    private val textColor = ColorManager.getColor("text_color")
    private val highlightTextColor = ColorManager.getColor("hilited_text_color")

    /** 提示块里英文 / 次要信息用的颜色：主题注释色，取不到退回高亮色。 */
    private val tipColor =
        runCatching { ColorManager.getColorOr("comment_text_color", highlightTextColor) }
            .getOrDefault(highlightTextColor)

    /** 三角标记的颜色：跟候选高亮那块底色一致；对比度不够（浅色主题）就退回文字高亮色。 */
    private val markColor =
        runCatching {
            val cand = ColorManager.getColorOr("hilited_candidate_back_color", highlightTextColor)
            val back = ColorManager.getColor("text_back_color")
            if (contrastRatio(cand, back) >= MIN_MARK_CONTRAST) cand else highlightTextColor
        }.getOrDefault(highlightTextColor)

    val preedit =
        view(::PreeditTextView) {
            setTextColor(textColor)
            textSize = theme.preedit.foreground.fontSize
            typeface = FontManager.getTypeface()
            setupPreeditView?.invoke(this)
            onMoveCursor = this@PreeditUi.onMoveCursor
        }

    private val wqTips = TipsView(wenquxing = true)
    private val glTips = TipsView(wenquxing = false)

    /** 编码字号缩放，只有接外接键盘时由设置调整；两块提示跟着一起缩。 */
    var textScale: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SCALE, MAX_SCALE)
            preedit.textSize = theme.preedit.foreground.fontSize * field
            refreshTips()
        }

    override val root =
        object : LinearLayout(ctx) {
            override fun onInterceptTouchEvent(ev: android.view.MotionEvent?): Boolean = false

            override fun onMeasure(
                widthMeasureSpec: Int,
                heightMeasureSpec: Int,
            ) {
                // root 自己是「整行」容器：宽度必须等于父容器宽度，不能按内容收缩。
                // 父级给的是 AT_MOST（wrap 语义），而 LinearLayout 在 wrap 模式下会先按内容
                // 定宽、再把 MATCH_PARENT 的子 View 重测成这个宽度——于是提示块被夹成编码行宽度。
                // 这里直接把测量规格改成 EXACTLY(父宽)，一次解决。
                val parentWidth =
                    (parent as? View)?.width?.takeIf { it > 0 }
                        ?: ctx.resources.displayMetrics.widthPixels
                val forced = MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.EXACTLY)
                super.onMeasure(forced, heightMeasureSpec)
            }

            init {
                orientation = VERTICAL
                add(preedit, lParams(wrapContent, wrapContent))
                add(wqTips, lParams(matchParent, wrapContent))
                add(glTips, lParams(matchParent, wrapContent))
            }
        }

    /** 把颜色按 [percent]（0–100）改成半透明。 */
    private fun withAlpha(
        color: Int,
        percent: Int,
    ): Int {
        val a = ((color ushr 24) and 0xFF) * percent.coerceIn(0, 100) / 100
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun CompositionProto.toSpannedString() = buildSpannedString {
        if (!preedit.isNullOrEmpty()) {
            append(preedit)
            setSpan(ForegroundColorSpan(highlightTextColor), selStart, selEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
    }

    var visible = false
        private set

    fun update(composition: CompositionProto) {
        val hasPreedit = composition.length > 0
        preedit.text = composition.toSpannedString()
        preedit.visibility = if (hasPreedit) View.VISIBLE else View.GONE
        // 引擎每给一次组合，文曲星都跟着重算一句
        runCatching { WenquxingTips.onComposition(composition.preedit, composition.commitTextPreview) }
        refreshTips()
    }

    /** 编码行的透明度只作用于它自己，不连累提示块。 */
    fun applyPreeditAlpha(alpha: Float) {
        preedit.alpha = alpha.coerceIn(0f, 1f)
    }

    /** 这一份该不该由自己画提示块：候选窗显示着就让候选窗画，否则键盘画。 */
    private fun tipsShownHere(): Boolean =
        runCatching {
            if (tipsOwner == OWNER_CANDIDATES) TipBlocks.candidatesWindowShown else !TipBlocks.candidatesWindowShown
        }.getOrDefault(tipsOwner != OWNER_CANDIDATES)

    /**
     * 重算两块提示。**显示规则只在这里判**：
     * 文曲星有命中 → 只有它；否则 → 外语学习（它自己没命中就隐藏）。
     */
    fun refreshTips() {
        val here = tipsShownHere()
        val wqHit =
            runCatching { WenquxingPrefs.enabled && WenquxingTips.hitCount() > 0 }.getOrDefault(false)
        val noContent =
            !wqHit && runCatching { GlossaryTips.entries().isEmpty() }.getOrDefault(true)
        wqTips.refresh(here && wqHit, noContent)
        glTips.refresh(here && !wqHit, noContent)
        visible = preedit.visibility == View.VISIBLE ||
            wqTips.visibility == View.VISIBLE ||
            glTips.visibility == View.VISIBLE
        syncTouchable()
    }

    /**
     * 提示块占着屏幕时，把它并进输入法窗口的触摸区。
     *
     * 不并进来，手指按在提示块上会被系统判成点到下面的 App（表现为「滑编辑区」「点不中」）。
     */
    /**
     * 同步「提示块是否占用触摸区」。
     *
     * 只在**可见性变化**时通知输入法；块的尺寸变化不通知 —— 否则 updateInputViewShown()
     * 会让窗口重算一轮，途中 refreshTips() 再把块判成「刚变可见」，
     * 于是刚展开的条目会被自己收起来（日志实证：tap → 展开 → 3.6s 后自动缩回）。
     * 尺寸变化不需要通知，是因为 InputView.getInputTouchRegion() 里已经把
     * 整个窗口上半区并进触摸区（rect.top = 0），高度怎么变都盖得住。
     */
    private fun syncTouchable() {
        val any = wqTips.visibility == View.VISIBLE || glTips.visibility == View.VISIBLE
        if (any == tipsTouchable) return
        tipsTouchable = any
        runCatching { TrimeInputMethodService.current?.updateInputViewShown() }
    }

    /** 相对亮度（WCAG），只看 RGB。 */
    private fun luminance(color: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = ch((color shr 16) and 0xFF)
        val g = ch((color shr 8) and 0xFF)
        val b = ch(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrastRatio(
        a: Int,
        b: Int,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * 一块提示区：`ScrollView` 里每条一个可点的 [TextView]，右上角浮一个「收起」。
     *
     * 滚动 = 原生 `ScrollView`；点击 = 每行自己的 `OnClickListener`；
     * **没有任何坐标换算**，所以「点不动 / 点到隔壁 / 滑一半卡住」从机制上不存在。
     */
    private inner class TipsView(
        private val wenquxing: Boolean,
    ) : FrameLayout(ctx) {
        private val column =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }

        private val scroller =
            ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(
                    column,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                )
            }

        /** 固定在右上角的「收起」：不参与滚动，只在展开态出现。 */
        val collapseButton: TextView =
            TextView(ctx).apply {
                text = COLLAPSE_LABEL
                textSize = COLLAPSE_TEXT_SP
                setTextColor(textColor)
                gravity = Gravity.CENTER
                visibility = View.GONE
                background =
                    GradientDrawable().apply {
                        setColor(withAlpha(textColor, BUTTON_BG_ALPHA))
                        cornerRadius = ctx.dp(6).toFloat()
                    }
                setOnClickListener { collapse() }
            }

        private var entries: List<TipEntry> = emptyList()
        private var snapshot = 0
        private var expandedIndex = -1
        private var scrollBeforeExpand = 0

        /** 上一次测出的高度，只用来避免日志刷屏。 */
        private var lastMeasured = -1
        private var lastBounds = ""
        /** 视图被清空过（隐藏时），回来要按 snapshot 重画一次。 */
        private var dirty = true

        init {
            visibility = View.GONE
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            addView(
                scroller,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            addView(
                collapseButton,
                LayoutParams(ctx.dp(COLLAPSE_W_DP), ctx.dp(COLLAPSE_H_DP), Gravity.TOP or Gravity.END).apply {
                    topMargin = ctx.dp(4)
                    rightMargin = ctx.dp(8)
                },
            )
        }

        private fun scalePct(): Int =
            runCatching {
                if (wenquxing) WenquxingPrefs.tipsTextScale else GlossaryPrefs.tipsTextScale
            }.getOrDefault(DEFAULT_SCALE_PCT)

        private fun alphaPct(): Int =
            runCatching {
                if (wenquxing) WenquxingPrefs.tipsAlpha else GlossaryPrefs.tipsAlpha
            }.getOrDefault(DEFAULT_ALPHA_PCT)

        private fun rows(): Int =
            runCatching {
                if (wenquxing) WenquxingPrefs.maxDisplay else GlossaryPrefs.tipsMax
            }.getOrDefault(3)

        private fun showChars(): Boolean =
            runCatching {
                if (wenquxing) WenquxingPrefs.showCharCount else GlossaryPrefs.showCharCount
            }.getOrDefault(true)

        private fun bodyTextSize(): Float =
            theme.preedit.foreground.fontSize * textScale * (scalePct() / 100f)

        /** 量一段文字在正文尺寸下的像素宽度（悬挂缩进要用）。 */
        private val measurePaint =
            android.graphics.Paint().apply {
                isAntiAlias = true
            }

        private fun measureWidth(text: CharSequence): Int {
            measurePaint.textSize = bodyTextSize()
            measurePaint.typeface = FontManager.getTypeface()
            return measurePaint.measureText(text.toString()).toInt().coerceAtLeast(1)
        }

        private fun applyStyle() {
            val base = ColorManager.getColor("text_back_color")
            val baseAlpha = (base ushr 24) and 0xFF
            val blended = baseAlpha * alphaPct().coerceIn(0, 100) / 100
            val fill = (base and 0x00FFFFFF) or (blended shl 24)
            val radius = ctx.dp(theme.preedit.topEndRadius)
            background =
                GradientDrawable().apply {
                    setColor(fill)
                    shape = GradientDrawable.RECTANGLE
                    // 上左、上右、下右、下左：只有右下角圆，跟编码行拼成一张卡片
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, 0f, 0f)
                }
            horizontalPadding = ctx.dp(theme.preedit.horizontalPadding)
        }

        /**
         * 主入口。
         * @param active  是否由本块显示
         * @param clear   本次「彻底没有候选」——要丢掉展开态，避免下次输入同一编码又看到上次的展开内容
         */
        fun refresh(active: Boolean, clear: Boolean = false) {
            if (!active) {
                if (clear) hardReset() else reset()
                return
            }
            val list =
                runCatching {
                    if (wenquxing) WenquxingTips.entries() else GlossaryTips.entries()
                }.getOrNull().orEmpty()
            if (list.isEmpty()) {
                hardReset()
                return
            }
            applyStyle()
            var id = 7
            list.forEach { id = id * 31 + it.text.hashCode() * 17 + it.chars * 7 + it.detail.length * 13 }
            // 只有「内容真的变了」才算变；可见性抖动（块被临时隐藏）不算，
            // 否则展开态会被自己人清掉——用户看到的就是「展开后一秒自己缩回」。
            val changed = id != snapshot
            if (changed) {
                HandwritingLog.d(
                    "tips",
                    "refresh ${if (wenquxing) "wq" else "gl"} changed vis=$visibility " +
                        "expanded=$expandedIndex entries=${list.size}",
                )
            }
            snapshot = id
            entries = list
            if (changed) {
                // 候选变了：丢弃展开态、回到顶部，全部重画
                expandedIndex = -1
                collapseButton.visibility = View.GONE
                scroller.setPadding(0, 0, 0, 0)
                render()
                scroller.scrollTo(0, 0)
                dirty = false
            } else if (dirty) {
                // 之前被 reset() 清过视图，内容没变：按原状态（含展开态）重画
                render()
                dirty = false
            }
            applyVisibility(View.VISIBLE)
        }

        private fun reset() {
            // 只是隐藏：**不**清 expandedIndex / snapshot / entries，
            // 否则「可见性抖动」会把展开态丢掉（历史 bug：展开 1 秒后自己缩回）。
            applyVisibility(View.GONE)
            collapseButton.visibility = View.GONE
            column.removeAllViews()
            dirty = true
            // 这里的 layoutParams 属于父容器（LinearLayout），不能用 FrameLayout.LayoutParams 覆盖，
            // 否则下一次 measureVertical 会抛 ClassCastException。
            val cur = layoutParams
            if (cur != null && cur.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                cur.height = ViewGroup.LayoutParams.WRAP_CONTENT
                layoutParams = cur
            }
        }

        /** 彻底没有候选时调用：连展开态一起丢。 */
        private fun hardReset() {
            expandedIndex = -1
            snapshot = 0
            entries = emptyList()
            reset()
        }

        private fun applyVisibility(v: Int) {
            if (visibility != v) visibility = v
        }

        /** 列表态重画：每条一个 View；展开态：只放一条全文。 */
        private fun render() {
            column.removeAllViews()
            if (expandedIndex >= 0) {
                val item = entries.getOrNull(expandedIndex)
                if (item == null) {
                    expandedIndex = -1
                } else {
                    scroller.setPadding(0, ctx.dp(COLLAPSE_H_DP + 8), 0, 0)
                    column.addView(makeDetail(item))
                    collapseButton.visibility = View.VISIBLE
                    return
                }
            }
            scroller.setPadding(0, 0, 0, 0)
            collapseButton.visibility = View.GONE
            val badge = if (wenquxing) runCatching { WenquxingTips.badgeText() }.getOrNull() else null
            if (!badge.isNullOrEmpty()) column.addView(makeBadge(badge))
            val markAt = entries.indexOfFirst { it.highlighted }.takeIf { it >= 0 } ?: 0
            entries.forEachIndexed { index, item ->
                if (index > 0) column.addView(makeDivider())
                column.addView(makeEntry(index, item, index == markAt))
            }
        }

        /**
         * 条目之间的分隔线：1dp、很淡。
         * 命中判定不看它（dispatchTouchEvent 只认条目），它只是让「一条到哪结束」肉眼可见。
         */
        private fun makeDivider(): View =
            View(ctx).apply {
                setBackgroundColor(withAlpha(textColor, DIVIDER_ALPHA))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ctx.dp(1),
                ).apply {
                    topMargin = ctx.dp(2)
                    bottomMargin = ctx.dp(2)
                }
            }

        /** 一条列表项：`▶ 中文  释义  ·N字  [...]展开`，整行可点。 */
        private fun makeEntry(
            index: Int,
            item: TipEntry,
            isMark: Boolean,
        ): TextView {
            val en = item.english.orEmpty()
            val unmatchedAlpha = runCatching { GlossaryPrefs.unmatchedAlpha }.getOrDefault(80)
            val unmatchedScale = runCatching { GlossaryPrefs.unmatchedTextScale }.getOrDefault(100)
            val text =
                buildSpannedString {
                    val markStart = length
                    append(if (isMark) MARK else INDENT)
                    if (isMark) {
                        setSpan(ForegroundColorSpan(markColor), markStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        setSpan(StyleSpan(Typeface.BOLD), markStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(MARK_SCALE), markStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                    val prefixEnd = length
                    val cnStart = length
                    append(item.text)
                    val cnEnd = length
                    if (item.highlighted) {
                        setSpan(StyleSpan(Typeface.BOLD), cnStart, cnEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                    if (item.unmatched) {
                        setSpan(StyleSpan(Typeface.ITALIC), cnStart, cnEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(unmatchedScale / 100f), cnStart, cnEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        setSpan(ForegroundColorSpan(withAlpha(textColor, unmatchedAlpha)), cnStart, cnEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    } else if (en.isNotEmpty()) {
                        append("  ")
                        val enStart = length
                        append(en)
                        setSpan(ForegroundColorSpan(tipColor), enStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(EN_SCALE), enStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        // 折行的续行缩进到正文开头（悬挂缩进）：两个模块都要，
                        // 否则一条折成三行时看起来像三条。
                        val indent = measureWidth(substring(0, prefixEnd))
                        setSpan(
                            LeadingMarginSpan.Standard(0, indent),
                            0,
                            length,
                            Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                        )
                    }
                    if (showChars() && wenquxing && item.chars > 0) {
                        append("  ")
                        val cntStart = length
                        append("·${item.chars}字")
                        setSpan(ForegroundColorSpan(tipColor), cntStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(COUNT_SCALE), cntStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                    if (item.expandable) {
                        append("  ")
                        val hintStart = length
                        append(EXPAND_HINT_WQ)
                        setSpan(ForegroundColorSpan(tipColor), hintStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(COUNT_SCALE), hintStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                }
            return entryView(text, bodyTextSize()).apply {
                tag = index
                setPadding(paddingLeft, ctx.dp(3), paddingRight, ctx.dp(3))
                // 条目之间留 6dp：每条一个块，视觉上分得开
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        }

        /** 展开态：该条全文，占满整个区域、可上下滑。 */
        private fun makeDetail(item: TipEntry): TextView =
            entryView(
                buildSpannedString { append(item.detail) },
                bodyTextSize(),
            )

        private fun makeBadge(badge: String): TextView =
            entryView(
                buildSpannedString {
                    append(badge)
                    setSpan(ForegroundColorSpan(tipColor), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(BADGE_SCALE), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                },
                bodyTextSize(),
            ).apply { isClickable = false }

        private fun entryView(
            text: CharSequence,
            size: Float,
        ): TextView =
            TextView(ctx).apply {
                this.text = text
                textSize = size
                typeface = FontManager.getTypeface()
                setTextColor(textColor)
                // 行距必须 1.0：TextView 会把额外行距加在**每一行下面**（含最后一行），
                // 那一段是不可见的死区，会让「上一条的可点区域」压住下一条，点击就错位。
                setLineSpacing(0f, 1.0f)
                setPadding(
                    ctx.dp(theme.preedit.horizontalPadding),
                    ctx.dp(2),
                    ctx.dp(theme.preedit.horizontalPadding),
                    ctx.dp(2),
                )
                isClickable = true
                isFocusable = false
            }

        private var downRawY = 0f
        private var downAt = 0L
        private var moved = false

        /**
         * 命中判定：把每一条当成一个普通按钮——
         * 按下的点落在哪条 View 的 top..bottom 之间，就是哪一条。
         * 只有「几乎没移动」才算点击，移动超过 12dp 视为滑动，交给 ScrollView。
         */
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = ev.rawY
                    downAt = System.currentTimeMillis()
                    moved = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!moved && kotlin.math.abs(ev.rawY - downRawY) > ctx.dp(TAP_SLOP_DP)) moved = true
                }

                MotionEvent.ACTION_UP -> {
                    val quick = System.currentTimeMillis() - downAt < 600L
                    if (!moved && quick) {
                        val me = IntArray(2)
                        getLocationOnScreen(me)
                        val localY = ev.rawY - me[1]
                        // 先判「收起」：它浮在右上角、盖在内容上方，不能被当成条目
                        val collapseTop = scroller.top.toFloat()
                        val collapseBottom = collapseTop + ctx.dp(COLLAPSE_H_DP + 8)
                        if (expandedIndex >= 0 && localY in collapseTop..collapseBottom) {
                            HandwritingLog.d("tips", "tap collapse rawY=" + ev.rawY.toInt())
                            collapse()
                            return true
                        }
                        val yInColumn = localY - scroller.top - scroller.paddingTop + scroller.scrollY
                        val idx = childIndexAtScreen(ev.rawY)
                        val dead = StringBuilder()
                        for (ci in 0 until column.childCount) {
                            val cv = column.getChildAt(ci)
                            val tv = cv as? TextView
                            val lay = tv?.layout
                            val textBottom =
                                if (tv != null && lay != null && lay.lineCount > 0) {
                                    cv.top + tv.totalPaddingTop + lay.getLineBottom(lay.lineCount - 1)
                                } else {
                                    cv.bottom
                                }
                            dead.append(" #").append(ci).append(':').append(cv.top).append("..")
                                .append(cv.bottom).append("/txt").append(textBottom)
                        }
                        runCatching {
                            val me = IntArray(2)
                            getLocationOnScreen(me)
                            val sc = IntArray(2)
                            scroller.getLocationOnScreen(sc)
                            val col = IntArray(2)
                            column.getLocationOnScreen(col)
                            val scr = StringBuilder()
                            for (ci in 0 until column.childCount) {
                                val cv = column.getChildAt(ci)
                                val loc = IntArray(2)
                                cv.getLocationOnScreen(loc)
                                scr.append(" #").append(ci).append(':').append(loc[1])
                                    .append("..").append(loc[1] + cv.height)
                            }
                            HandwritingLog.d(
                                "tips",
                                "tap rawY=" + ev.rawY.toInt() + " blockTop=" + me[1] +
                                    " scrollerTop=" + sc[1] + " columnTop=" + col[1] +
                                    " localY=" + ev.y.toInt() + " yInColumn=" + yInColumn.toInt() +
                                    " idx=" + idx + " scrollY=" + scroller.scrollY,
                            )
                            HandwritingLog.d("tips", "screen$scr")
                        }
                        HandwritingLog.d(
                            "tips",
                            "tap y=" + ev.y.toInt() + " yInColumn=" + yInColumn.toInt() +
                                " idx=" + idx + " expanded=" + expandedIndex +
                                " scrollY=" + scroller.scrollY + dead,
                        )
                        if (idx >= 0) {
                            activate(idx)
                            return true
                        }
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        /** 返回「条目下标」（已扣掉 badge 行）；不在任何条目上返回 -1。 */
        /**
         * 用**屏幕绝对 y** 命中：把每条 TextView 的屏幕矩形拿出来直接比。
         * 这样不管事件来自哪个坐标空间（可能是 TouchEventReceiverWindow 转发的、
         * 带偏移的 ev.y），结果都一样。
         */
        private fun childIndexAtScreen(rawY: Float): Int {
            val loc = IntArray(2)
            for (i in 0 until column.childCount) {
                val child = column.getChildAt(i)
                val entryIdx = (child.tag as? Int) ?: continue
                child.getLocationOnScreen(loc)
                val top = loc[1].toFloat()
                val bottom = top + child.height
                if (rawY >= top && rawY < bottom) {
                    return if (entryIdx in entries.indices) entryIdx else -1
                }
            }
            // 落在分隔线/缝里：吸附到最近的一条
            val slack = ctx.dp(GAP_SLACK_DP).toFloat()
            var best = -1
            var bestDist = Float.MAX_VALUE
            for (i in 0 until column.childCount) {
                val child = column.getChildAt(i)
                val entryIdx = (child.tag as? Int) ?: continue
                if (entryIdx !in entries.indices) continue
                child.getLocationOnScreen(loc)
                val top = loc[1].toFloat()
                val bottom = top + child.height
                val d = if (rawY < top) top - rawY else rawY - bottom
                if (d <= slack && d < bestDist) {
                    bestDist = d
                    best = entryIdx
                }
            }
            return best
        }

        @Suppress("unused")
        private fun childIndexAt(yInColumn: Float): Int {
            for (i in 0 until column.childCount) {
                val child = column.getChildAt(i)
                val entryIdx = (child.tag as? Int) ?: continue
                if (yInColumn >= child.top && yInColumn < child.bottom) {
                    return if (entryIdx in entries.indices) entryIdx else -1
                }
            }
            // 落在条目之间的缝里（底部 6dp margin）：吸附到最近的那一条，别让手指落空
            val slack = ctx.dp(GAP_SLACK_DP).toFloat()
            var best = -1
            var bestDist = Float.MAX_VALUE
            for (i in 0 until column.childCount) {
                val child = column.getChildAt(i)
                val entryIdx = (child.tag as? Int) ?: continue
                if (entryIdx !in entries.indices) continue
                val d =
                    when {
                        yInColumn < child.top -> child.top - yInColumn
                        else -> yInColumn - child.bottom
                    }
                if (d <= slack && d < bestDist) {
                    bestDist = d
                    best = entryIdx
                }
            }
            return best
        }

        private fun activate(idx: Int) {
            if (expandedIndex >= 0) {
                // 展开态：点正文 = 上屏
                val item = entries.getOrNull(expandedIndex) ?: return
                commitPayload(item.payload)
                return
            }
            val item = entries.getOrNull(idx) ?: return
            HandwritingLog.d(
                "tips",
                "activate idx=" + idx + " expandable=" + item.expandable + " text=" + item.text.take(12),
            )
            if (item.expandable) expand(idx) else commitPayload(item.payload)
        }

        override fun onLayout(
            changed: Boolean,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) {
            super.onLayout(changed, left, top, right, bottom)
            // 高度已经在 onMeasure 里定好，这里不再改 layoutParams（布局阶段改参数会被推迟，
            // 表现就是「有时全显示 / 有时显示 N 条又滑不动」）。
            runCatching {
                val sb = StringBuilder()
                for (ci in 0 until column.childCount) {
                    val cv = column.getChildAt(ci)
                    sb.append(" #").append(ci).append(':').append(cv.top).append("..").append(cv.bottom)
                }
                val line = (if (wenquxing) "wq" else "gl") + " h=" + height +
                    " scrollY=" + scroller.scrollY + " expanded=" + expandedIndex + sb
                if (line != lastBounds) {
                    lastBounds = line
                    HandwritingLog.d("tips", "bounds " + line)
                }
            }
            this@PreeditUi.syncTouchable()
        }

        /**
         * 高度只在测量阶段决定：先把内容按不限高量一遍，拿到每条的真实高度，
         * 再按「显示几条」或「展开行数」定自己的高度。用 measuredHeight，不用 height。
         */
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val wSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
            if (expandedIndex >= 0) {
                // expanded height is a fixed ratio: no need to measure content at all.
                val ratio = if (wenquxing) EXPAND_RATIO_WQ else EXPAND_RATIO_GL
                val h = (ctx.resources.displayMetrics.heightPixels * ratio).toInt().coerceAtLeast(1)
                if (h != lastMeasured) {
                    lastMeasured = h
                    HandwritingLog.d("tips", "measure ${if (wenquxing) "wq" else "gl"} expanded h=$h")
                }
                scroller.measure(wSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                collapseButton.measure(
                    MeasureSpec.makeMeasureSpec(ctx.dp(COLLAPSE_W_DP), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ctx.dp(COLLAPSE_H_DP), MeasureSpec.EXACTLY),
                )
                setMeasuredDimension(w, h)
                return
            }
            column.measure(wSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val padV = scroller.paddingTop + scroller.paddingBottom
            val contentH = column.measuredHeight + padV
            val want =
                if (expandedIndex >= 0) {
                    // 展开态用**固定**高度（占屏高比例），不再按行数、也不被内容高度压小。
                    // 两个模块比例不同：文曲星条目长，给 28%；外语学习只有一行英文，18% 就够。
                    val ratio = if (wenquxing) EXPAND_RATIO_WQ else EXPAND_RATIO_GL
                    (ctx.resources.displayMetrics.heightPixels * ratio).toInt()
                } else {
                    val r = rows().coerceIn(1, MAX_ROWS)
                    // 子 View 里混着三种东西：角标、条目（带 tag）、分隔线（不带 tag）。
                    // 只有**带 tag 的**才算一条；角标和分隔线照样占高度、但不计条数。
                    // （之前「取前 r 个子 View」会把分隔线也算成一条，导致设 3 条只显示 1.5 条。）
                    var sum = 0
                    var count = 0
                    var i = 0
                    while (i < column.childCount && count < r) {
                        val ch = column.getChildAt(i)
                        val m = ch.layoutParams as? ViewGroup.MarginLayoutParams
                        sum += ch.measuredHeight + (m?.topMargin ?: 0) + (m?.bottomMargin ?: 0)
                        if (ch.tag is Int) count++
                        i++
                    }
                    if (count == 0) contentH else sum + padV
                }
            // 内容不够高就自适应，别留空白
            val h =
                if (expandedIndex < 0 && contentH in 1 until want) {
                    contentH
                } else {
                    want.coerceAtLeast(1)
                }
            if (h != lastMeasured) {
                lastMeasured = h
                HandwritingLog.d(
                    "tips",
                    "measure ${if (wenquxing) "wq" else "gl"} rows=$want content=$contentH final=$h",
                )
            }
            scroller.measure(wSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            collapseButton.measure(
                MeasureSpec.makeMeasureSpec(ctx.dp(COLLAPSE_W_DP), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ctx.dp(COLLAPSE_H_DP), MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(w, h)
        }

        private fun expand(index: Int) {
            val item = entries.getOrNull(index) ?: return
            if (!item.expandable) {
                commitPayload(item.payload)
                return
            }
            scrollBeforeExpand = scroller.scrollY
            expandedIndex = index
            render()
            requestLayout()
            post { scroller.scrollTo(0, 0) }
        }

        private fun collapse() {
            expandedIndex = -1
            collapseButton.visibility = View.GONE
            render()
            // 收起后必须重新测量一次，否则会沿用展开态的高度（表现：变得很矮、内容被裁）
            requestLayout()
            post { scroller.scrollTo(0, scrollBeforeExpand) }
        }

        /** 上屏：与 `BambooTip` 系列 token 同一条路——先清组合，再提交。 */
        private fun commitPayload(payload: String) {
            if (payload.isEmpty()) return
            val service = TrimeInputMethodService.current ?: return
            service.postKeyJob {
                runCatching {
                    clearComposition()
                    service.commitText(payload)
                }
            }
        }
    }

    companion object {
        /** 键盘上那一份。 */
        const val OWNER_KEYBOARD = 0

        /** 悬浮候选窗那一份。 */
        const val OWNER_CANDIDATES = 1

        /** 提示块当前是否占着屏幕（供输入法窗口把这块并进触摸区）。 */
        @Volatile
        var tipsTouchable: Boolean = false
            private set

        private const val MIN_SCALE = 0.4f
        private const val MAX_SCALE = 2f
        private const val DEFAULT_SCALE_PCT = 90
        private const val DEFAULT_ALPHA_PCT = 90
        private const val EN_SCALE = 0.82f
        private const val MARK_SCALE = 1.1f
        private const val COUNT_SCALE = 0.78f
        private const val BADGE_SCALE = 0.88f
        /** 展开态高度占屏高的比例：文曲星 28%、外语学习 18%。 */
        private const val EXPAND_RATIO_WQ = 0.28f
        private const val EXPAND_RATIO_GL = 0.14f
        private const val MAX_ROWS = 6
        private const val MAX_EXPAND_ROWS = 8
        private const val BUTTON_BG_ALPHA = 40

        /** 三角和提示块底色的最低对比度，低于这个就退回文字色，避免箭头看不见。 */
        private const val MIN_MARK_CONTRAST = 2.0

        private const val MARK = "\u25B6\u3000"
        private const val INDENT = "\u3000\u3000"
        private const val EXPAND_HINT = "\u25B8"
        private const val EXPAND_HINT_WQ = "[...\u5C55\u5F00]"
        private const val COLLAPSE_LABEL = "\u6536\u8D77"
        private const val COLLAPSE_TEXT_SP = 12f
        private const val DIVIDER_ALPHA = 36
        private const val GAP_SLACK_DP = 10
        private const val TAP_SLOP_DP = 12
        private const val COLLAPSE_W_DP = 60
        private const val COLLAPSE_H_DP = 26
    }
}
