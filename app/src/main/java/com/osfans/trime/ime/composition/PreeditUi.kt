/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
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
import kotlin.math.abs

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
 * 它们外观一样，但状态、内容、设置项各管各的，互不影响。
 *
 * ## 唯一的一条显示规则
 *
 * 文曲星这一句有命中时，**只显示文曲星那一块**，外语学习强制隐藏；
 * 文曲星没命中时才轮到外语学习（它自己没命中就不显示）。
 * 规则只写在 [refreshTips] 一处。
 *
 * ## 每块的状态机（就这四个变量，没有别的）
 *
 * - `snapshot`：这一批候选的内容指纹。**指纹一变，展开态与滚动位置全部作废**，
 *   回到未展开、滚到顶部——这就是「候选变了就丢弃当前显示」。
 * - `expandedIndex`：展开的是第几条，`-1` 表示列表态。只对当前 `snapshot` 有效。
 * - `viewportRows`：视口高度按「显示几条」算，内容永远是全部条目。
 * - 滚动位置由 View 自己持有（原生 `ScrollingMovementMethod`）。
 *
 * 高度只在 **`onLayout` 之后**量（`applyViewportHeight`），不再赌 `post` 时序。
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

    /** 提示块里英文部分用的颜色：主题里的注释色，取不到就退回高亮色。 */
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
            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false

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
    private fun withAlpha(color: Int, percent: Int): Int {
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
        wqTips.refresh(here && wqHit)
        glTips.refresh(here && !wqHit)
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
     * 一块提示区：列表态一条一行，点某一条 = 展开或上屏；展开态整块换成那一好的全文。
     *
     * 所有交互都在这个类里闭环，外部只有 [refresh]。
     */
    private inner class TipsView(
        private val wenquxing: Boolean,
    ) : TextView(ctx) {
        private val gestureDetector =
            GestureDetector(
                ctx,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        handleTap(e.x.toInt(), e.y.toInt())
                        return true
                    }
                },
            )

        private var entries: List<TipEntry> = emptyList()
        private var entryStart = IntArray(0)
        private var entryBlockEnd = IntArray(0)
        private var snapshot = 0
        private var expandedIndex = -1
        private var viewportRows = 3
        private var scrollBeforeExpand = 0

        init {
            setTextColor(textColor)
            typeface = FontManager.getTypeface()
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            maxLines = Int.MAX_VALUE
            visibility = View.GONE
            horizontalPadding = ctx.dp(theme.preedit.horizontalPadding)
            clipToOutline = true
            setLineSpacing(0f, 1.18f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
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

        private fun expandRows(): Int =
            runCatching {
                if (wenquxing) WenquxingPrefs.expandRows else GlossaryPrefs.expandRows
            }.getOrDefault(5)

        private fun applyStyle() {
            textSize = theme.preedit.foreground.fontSize * textScale * (scalePct() / 100f)
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
        }

        /**
         * 主入口。`active == true` 时按最新候选重画；否则隐藏并清状态。
         *
         * 候选内容一变（[snapshot] 不同），展开态与滚动位置**一律作废**。
         */
        fun refresh(active: Boolean) {
            if (!active) {
                if (visibility != View.GONE) visibility = View.GONE
                entries = emptyList()
                entryStart = IntArray(0)
                entryBlockEnd = IntArray(0)
                expandedIndex = -1
                snapshot = 0
                scrollTo(0, 0)
                return
            }
            val list =
                runCatching {
                    if (wenquxing) WenquxingTips.entries() else GlossaryTips.entries()
                }.getOrNull().orEmpty()
            if (list.isEmpty()) {
                if (visibility != View.GONE) visibility = View.GONE
                entries = emptyList()
                entryStart = IntArray(0)
                entryBlockEnd = IntArray(0)
                expandedIndex = -1
                snapshot = 0
                scrollTo(0, 0)
                return
            }
            applyStyle()
            viewportRows = rows()
            var id = 7
            list.forEach { id = id * 31 + it.text.hashCode() * 17 + it.chars * 7 + it.detail.length * 13 }
            val changed = id != snapshot || visibility != View.VISIBLE
            snapshot = id
            entries = list
            if (changed) {
                // 候选变了：丢弃展开态、回到顶部，全部重画
                expandedIndex = -1
                render()
                scrollTo(0, 0)
            }
            visibility = View.VISIBLE
        }

        /** 按当前 `expandedIndex` 重建文本；高度交给 [onLayout] 或 [expand]。 */
        private fun render() {
            text =
                if (expandedIndex >= 0) {
                    buildExpanded(entries, expandedIndex)
                } else {
                    buildBlock(entries)
                }
            val lp = layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                if (lp.width != LinearLayout.LayoutParams.MATCH_PARENT) lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                if (lp.height != LinearLayout.LayoutParams.WRAP_CONTENT) lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                layoutParams = lp
            }
            requestLayout()
        }

        override fun onLayout(
            changed: Boolean,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) {
            super.onLayout(changed, left, top, right, bottom)
            if (expandedIndex < 0) applyViewportHeight()
            this@PreeditUi.syncTouchable()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            gestureDetector.onTouchEvent(event)
            val handled = super.onTouchEvent(event)
            return handled || event.actionMasked == MotionEvent.ACTION_DOWN
        }

        override fun canScrollHorizontally(direction: Int): Boolean = false

        /**
         * 视口高度 = 前「显示几条」条的高度（**不含「共N组」那一行**，
         * 它只是标题，永远可见）。条目不足一屏就自适应，不留空白。
         */
        private fun applyViewportHeight() {
            val lay = layout ?: return
            val lp = layoutParams as? LinearLayout.LayoutParams ?: return
            val r = viewportRows.coerceIn(1, MAX_ROWS)
            if (entries.size <= r) return
            val endChar = entryBlockEnd.getOrNull(r - 1) ?: return
            if (endChar <= 0) return
            val line = lay.getLineForOffset(endChar - 1)
            if (line < 0) return
            val want = totalPaddingTop + lay.getLineBottom(line) + totalPaddingBottom
            if (want <= 0) return
            if (lp.height != LinearLayout.LayoutParams.WRAP_CONTENT && abs(lp.height - want) <= ctx.dp(1)) return
            lp.height = want
            layoutParams = lp
        }

        /** 列表视图：一条一行，行尾「·N字」；可展开的条目行尾带提示。 */
        private fun buildBlock(list: List<TipEntry>): CharSequence {
            // 三角跟在高亮那一条前面（文曲星的高亮就是轮换到当前的第一条）
            val markAt = list.indexOfFirst { it.highlighted }.takeIf { it >= 0 } ?: 0
            val unmatchedAlpha = runCatching { GlossaryPrefs.unmatchedAlpha }.getOrDefault(80)
            val unmatchedScale = runCatching { GlossaryPrefs.unmatchedTextScale }.getOrDefault(100)
            val showCount = showChars()
            val n = list.size
            entryStart = IntArray(n)
            entryBlockEnd = IntArray(n)
            val badge = if (wenquxing) runCatching { WenquxingTips.badgeText() }.getOrNull() else null
            return buildSpannedString {
                if (!badge.isNullOrEmpty()) {
                    val badgeStart = length
                    append(badge)
                    setSpan(ForegroundColorSpan(tipColor), badgeStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(BADGE_SCALE), badgeStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    append('\n')
                }
                list.forEachIndexed { index, line ->
                    if (index > 0) append('\n')
                    val markStart = length
                    append(if (index == markAt) MARK else INDENT)
                    if (index == markAt) {
                        listOf(
                            ForegroundColorSpan(markColor),
                            StyleSpan(Typeface.BOLD),
                            RelativeSizeSpan(MARK_SCALE),
                        ).forEach { setSpan(it, markStart, markStart + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE) }
                    }
                    entryStart[index] = length
                    val cnStart = length
                    append(line.text)
                    val cnEnd = length
                    if (line.highlighted) {
                        setSpan(StyleSpan(Typeface.BOLD), cnStart, cnEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                    val en = line.english
                    if (line.unmatched) {
                        setSpan(StyleSpan(Typeface.ITALIC), cnStart, cnEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        setSpan(
                            RelativeSizeSpan(unmatchedScale / 100f),
                            cnStart,
                            cnEnd,
                            Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                        )
                        setSpan(
                            ForegroundColorSpan(withAlpha(textColor, unmatchedAlpha)),
                            cnStart,
                            cnEnd,
                            Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                        )
                    } else if (!en.isNullOrEmpty()) {
                        append("  ")
                        val enStart = length
                        append(en)
                        setSpan(ForegroundColorSpan(tipColor), enStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(EN_SCALE), enStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                    if (showCount && wenquxing && line.chars > 0) {
                        append("  ")
                        val cntStart = length
                        append("·${line.chars}字")
                        setSpan(ForegroundColorSpan(tipColor), cntStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(COUNT_SCALE), cntStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                    if (line.expandable) {
                        append("  ")
                        val hintStart = length
                        append(EXPAND_HINT)
                        setSpan(ForegroundColorSpan(tipColor), hintStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(COUNT_SCALE), hintStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                    entryBlockEnd[index] = length
                }
            }
        }

        /** 展开视图：第一行右上角「收起」，后面是该条全文。 */
        private fun buildExpanded(
            list: List<TipEntry>,
            index: Int,
        ): CharSequence {
            val item = list[index]
            return buildSpannedString {
                val collapseStart = length
                append(COLLAPSE_LABEL)
                val collapseEnd = length
                setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), collapseStart, collapseEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                setSpan(BackgroundColorSpan(withAlpha(textColor, BUTTON_BG_ALPHA)), collapseStart, collapseEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(tipColor), collapseStart, collapseEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                append('\n')
                append(item.detail)
            }
        }

        /** 点在哪儿：展开态第一行 = 收起，其余 = 上屏；列表态按条目判。 */
        private fun handleTap(
            x: Int,
            y: Int,
        ) {
            val lay = layout ?: return
            val vy = y - totalPaddingTop + scrollY
            val line = lay.getLineForVertical(vy.coerceAtLeast(0))
            if (line < 0) return
            if (expandedIndex >= 0) {
                if (line == 0) {
                    collapse()
                } else {
                    entries.getOrNull(expandedIndex)?.let { commitPayload(it.payload) }
                }
                return
            }
            val index = entryIndexForLine(line)
            val item = entries.getOrNull(index) ?: return
            HandwritingLog.d(
                "tips",
                "tap line=$line y=$y vy=$vy -> idx=$index expandable=${item.expandable} entries=${entries.size}",
            )
            if (item.expandable) expand(index) else commitPayload(item.payload)
        }

        /** 视觉行 → 条目下标（折行时同一视觉行的多个条目取第一个）。 */
        private fun entryIndexForLine(line: Int): Int {
            val lay = layout ?: return -1
            for (i in entries.indices) {
                val start = entryStart.getOrNull(i) ?: continue
                val end = entryBlockEnd.getOrNull(i) ?: continue
                if (end <= start) continue
                val first = lay.getLineForOffset(start)
                val last = lay.getLineForOffset(end - 1)
                if (line in first..last) return i
            }
            return -1
        }

        /** 展开某一条：宽度锁成整块，高度夹在「展开行数」与屏高 40% 之间。 */
        private fun expand(index: Int) {
            val item = entries.getOrNull(index) ?: return
            if (!item.expandable) {
                commitPayload(item.payload)
                return
            }
            scrollBeforeExpand = scrollY
            expandedIndex = index
            text = buildExpanded(entries, index)
            val lp = layoutParams as LinearLayout.LayoutParams
            lp.width = LinearLayout.LayoutParams.MATCH_PARENT
            val lh = lineHeight.takeIf { it > 0 } ?: ctx.dp(LINE_FALLBACK_DP)
            val minH = expandRows().coerceIn(1, MAX_EXPAND_ROWS) * lh + totalPaddingTop + totalPaddingBottom
            val maxH = (ctx.resources.displayMetrics.heightPixels * EXPAND_MAX_RATIO).toInt()
            lp.height = height.coerceAtLeast(minH).coerceAtMost(maxH)
            layoutParams = lp
            scrollTo(0, 0)
        }

        /** 收起：回到列表态，滚回展开前的位置。 */
        private fun collapse() {
            expandedIndex = -1
            render()
            post { scrollTo(0, scrollBeforeExpand) }
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
        private const val LINE_FALLBACK_DP = 22
        private const val EXPAND_MAX_RATIO = 0.4f
        private const val MAX_ROWS = 6
        private const val MAX_EXPAND_ROWS = 8
        private const val BUTTON_BG_ALPHA = 34

        /** 三角和提示块底色的最低对比度，低于这个就退回文字色，避免箭头看不见。 */
        private const val MIN_MARK_CONTRAST = 2.0

        private const val MARK = "\u25B6\u3000"
        private const val INDENT = "\u3000\u3000"
        private const val EXPAND_HINT = "\u25B8"
        private const val COLLAPSE_LABEL = "\u25BE \u6536\u8D77"
    }
}
