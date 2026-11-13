/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.Layout
import android.text.Spanned
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
 * 它们共用同一套外观，但状态、内容、设置项全部各管各的，互不影响。
 *
 * ## 唯一的一条显示规则
 *
 * 文曲星这一句有命中时，**只显示文曲星那一块**，外语学习强制隐藏；
 * 文曲星没命中时才轮到外语学习（它自己没命中就不显示）。
 * 规则只写在 [refreshTips] 一处，其他任何地方都不再判一次。
 *
 * ## 每块自己能做什么
 *
 * - 高度 = 各自设置里「显示几条」那么多行，内容不足自动变矮，超出则内部滚动；
 * - 点某一条 = 上屏它（与 `BambooTip` 系列 token 同一条路）；
 * - 点每条末尾的展开入口 = 在这块地里展开全文（高度锁定、内部滚动），点顶部收起；
 * - 高亮项换了会自动滚到它那一行，但用户手动滑过之后短时间内不再抢滚动位置。
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
    }

    /** 相对亮度（WCAG），只看 RGB。 */
    private fun luminance(color: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((color shr 16) and 0xFF) +
            0.7152 * ch((color shr 8) and 0xFF) +
            0.0722 * ch(color and 0xFF)
    }

    /** 两色的对比度（1.0–21.0）。 */
    private fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = if (la > lb) la else lb
        val lo = if (la > lb) lb else la
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * 一块提示区。文曲星和外语学习各持一个实例：
     * 内容、展开态、滚动位置、视口高度、设置项来源全部是**这个对象自己的**，
     * 两块之间没有任何共享状态，也就没有互相踩的可能。
     */
    private inner class TipsView(
        private val wenquxing: Boolean,
    ) : TextView(ctx) {
        // ---- 内容与命中测试所需的元数据（每块自己一份）----

        /** 当前块里的每一条（顺序 = 屏幕上的显示顺序）。 */
        var entries: List<TipEntry> = emptyList()
            private set

        /** 每条在 Spanned 里的起始字符偏移，点击时用来把「点到的字」换算成「第几条」。 */
        private var entryStart: IntArray = IntArray(0)

        /** 每条「正文区」的结束偏移（正文 + 行尾字数，不含展开入口）。 */
        private var entryBlockEnd: IntArray = IntArray(0)

        /** 每条展开入口在 Spanned 里的字符范围（-1 表示这条没有入口）。 */
        private var iconStart: IntArray = IntArray(0)
        private var iconEnd: IntArray = IntArray(0)

        /** 正在展开的那一条的下标；-1 表示列表视图。 */
        private var expandedIndex: Int = -1

        /** 上一次渲染的内容指纹，相同就不重画（否则每次按键都会把滚动位置打回顶部）。 */
        private var lastKey: String = ""

        /** 视口条数（= 各自设置里的「显示几条」），只在列表视图用。 */
        private var viewportRows = 3

        /** 用户手动滚动的时间戳，用来判断要不要把视口拽回高亮项。 */
        private var manualScrollAt: Long = 0L

        /** 展开前的滚动位置与高度，收起时还原。 */
        private var scrollBeforeExpand: Int = 0
        private var heightBeforeExpand: Int = 0

        private val gestureDetector =
            GestureDetector(
                ctx,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        handleTap(e.x.toInt(), e.y.toInt())
                        return true
                    }

                    override fun onScroll(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        distanceX: Float,
                        distanceY: Float,
                    ): Boolean {
                        // 用户自己滑了：短时间内别把人家拽回高亮行；同时自己接管滚动，
                        // 不再交给 ScrollingMovementMethod（两套判断会打架）。
                        manualScrollAt = SystemClock.uptimeMillis()
                        // 只认纵向：横向位移一律丢掉，否则手感像拖一张图片
                        scrollBy(0, distanceY.toInt())
                        return true
                    }
                },
            )

        init {
            setTextColor(textColor)
            textSize = theme.preedit.foreground.fontSize
            typeface = FontManager.getTypeface()
            setLineSpacing(0f, 1.18f)
            horizontalPadding = dp(theme.preedit.horizontalPadding)
            clipToOutline = true
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setOnTouchListener(
                object : View.OnTouchListener {
                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        // 全部接管：点击走 gestureDetector，滑动在 onScroll 里自己 scrollBy
                        gestureDetector.onTouchEvent(event)
                        return true
                    }
                },
            )
        }

        /** 禁止一切横向滚动（文本比视口宽时也不许左右拖）。 */
        override fun canScrollHorizontally(direction: Int): Boolean = false

        // ---- 设置项：只读自己所属模块的 ----

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
                (if (wenquxing) WenquxingPrefs.maxDisplay else GlossaryPrefs.tipsMax).coerceIn(1, 6)
            }.getOrDefault(3)

        private fun showChars(): Boolean =
            runCatching {
                if (wenquxing) WenquxingPrefs.showCharCount else GlossaryPrefs.showCharCount
            }.getOrDefault(true)

        private fun expandRows(): Int =
            runCatching {
                (if (wenquxing) WenquxingPrefs.expandRows else GlossaryPrefs.expandRows).coerceIn(1, 8)
            }.getOrDefault(5)

        // ---- 渲染 ----

        /** 外部（组合变化、设置变化、翻页）调这个：决定这一块要不要显示、显示什么。 */
        fun refresh(active: Boolean) {
            if (!active) {
                if (visibility != View.GONE) visibility = View.GONE
                return
            }
            val list =
                runCatching {
                    if (wenquxing) WenquxingTips.entries() else GlossaryTips.entries()
                }.getOrNull().orEmpty()
            if (list.isEmpty()) {
                entries = emptyList()
                entryStart = IntArray(0)
                entryBlockEnd = IntArray(0)
                iconStart = IntArray(0)
                iconEnd = IntArray(0)
                expandedIndex = -1
                lastKey = ""
                if (visibility != View.GONE) visibility = View.GONE
                return
            }
            applyStyle()
            if (expandedIndex !in list.indices) expandedIndex = -1
            val key =
                list.joinToString("\u0001") { "${it.text}\u0002${it.chars}\u0002${it.detail.length}" } +
                    "#$expandedIndex#${showChars()}#${textScale}"
            if (key == lastKey && visibility == View.VISIBLE) return
            lastKey = key
            entries = list
            text =
                if (expandedIndex >= 0) {
                    buildExpanded(list, expandedIndex)
                } else {
                    viewportRows = rows()
                    buildBlock(list)
                }
            maxLines = Int.MAX_VALUE
            visibility = View.VISIBLE
            post { applyViewportHeight() }
            if (expandedIndex >= 0) scrollTo(0, 0) else scrollToHighlight()
        }

        /**
         * 背景与字号：背景色跟编码行同一个键，圆角挪到右下角（和编码行拼成一张卡片），
         * 不透明度与字号各用所属模块自己的设置。
         */
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
                    // 上左、上右、下右、下左：只有右下角圆
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, 0f, 0f)
                }
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }

        /** 列表视图：一条一行，行尾「·N字」和展开入口。 */
        private fun buildBlock(list: List<TipEntry>): CharSequence {
            // 三角跟在高亮那一条前面；文曲星块没有高亮，就落在第一条
            val markAt = list.indexOfFirst { it.highlighted }.takeIf { it >= 0 } ?: 0
            val unmatchedAlpha = runCatching { GlossaryPrefs.unmatchedAlpha }.getOrDefault(80)
            val unmatchedScale = runCatching { GlossaryPrefs.unmatchedTextScale }.getOrDefault(100)
            val showCount = showChars()
            val n = list.size
            entryStart = IntArray(n)
            entryBlockEnd = IntArray(n)
            iconStart = IntArray(n) { -1 }
            iconEnd = IntArray(n) { -1 }
            val badge = if (wenquxing) runCatching { WenquxingTips.badgeText() }.getOrNull() else null
            return buildSpannedString {
                // 第一行单独放「共N组」，贴最左
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
                        // 只放大三角字形本身，后面的全角空格保持原宽度，避免各行文字错位
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
                        // 词库里没有这个词：中文斜体 + 按设置调淡（只有「外语学习」会置这个标记）
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
                    // 行尾字数：这次点击会提交多少字
                    // 字数只对文曲星有意义（外语学习上屏的是英文，字数参考价值不大）
                    if (showCount && wenquxing && line.chars > 0) {
                        append("  ")
                        val cntStart = length
                        append("·${line.chars}字")
                        setSpan(ForegroundColorSpan(tipColor), cntStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(COUNT_SCALE), cntStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                    entryBlockEnd[index] = length
                    // 展开入口：文曲星用「展开」文字按钮；外语学习用带空格的 ▾ 撑出热区
                    if (line.expandable) {
                        append("  ")
                        iconStart[index] = length
                        if (wenquxing) {
                            append(" \u5c55\u5f00 ")
                            setSpan(
                                BackgroundColorSpan(withAlpha(textColor, BUTTON_BG_ALPHA)),
                                iconStart[index],
                                length,
                                Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                            )
                            setSpan(
                                ForegroundColorSpan(textColor),
                                iconStart[index],
                                length,
                                Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                            )
                        } else {
                            append(" \u25BE ")
                            setSpan(
                                RelativeSizeSpan(GLOSSARY_ICON_SCALE),
                                iconStart[index],
                                length,
                                Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                            )
                            setSpan(
                                ForegroundColorSpan(tipColor),
                                iconStart[index],
                                length,
                                Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                            )
                        }
                        iconEnd[index] = length
                    }
                }
            }
        }

        /** 展开视图：占满这块地显示某一条的全文，第一行是收起入口。 */
        private fun buildExpanded(
            list: List<TipEntry>,
            index: Int,
        ): CharSequence {
            val line = list[index]
            entryStart = intArrayOf(0)
            entryBlockEnd = intArrayOf(0)
            iconStart = intArrayOf(0)
            iconEnd = intArrayOf(0)
            return buildSpannedString {
                val rowStart = length
                append("\u25B4 \u6536\u8d77 ")
                iconEnd[0] = length
                setSpan(ForegroundColorSpan(tipColor), rowStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(ICON_SCALE), rowStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                setSpan(
                    BackgroundColorSpan(withAlpha(textColor, BUTTON_BG_ALPHA)),
                    rowStart,
                    length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                )
                append('\n')
                // 段落级对齐必须把换行也包进 span 才生效
                setSpan(
                    AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE),
                    rowStart,
                    length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                )
                entryStart[0] = length
                append(line.detail)
            }
        }

        /** 点到了什么：全部按「字符偏移落在哪个区间」判断，不再按像素猜热区。 */
        private fun handleTap(x: Int, y: Int) {
            val layout = layout ?: return
            val vx = (x - totalPaddingLeft + scrollX).toFloat()
            val vy = y - totalPaddingTop + scrollY
            if (vy < 0) return
            val line = layout.getLineForVertical(vy)
            if (line < 0 || line >= layout.lineCount) return
            val offset = layout.getOffsetForHorizontal(line, vx.coerceAtLeast(0f))

            // 展开态：顶端那一段（字符区间）收起，其余任意位置点了上屏
            if (expandedIndex >= 0) {
                val collapseEnd = iconEnd.getOrNull(0) ?: -1
                if (collapseEnd > 0 && offset < collapseEnd) {
                    collapse()
                    return
                }
                commitPayload(entries.getOrNull(expandedIndex)?.payload.orEmpty())
                return
            }

            // 展开入口：字符落在入口区间内就展开（行尾空白也会算到这一行的最后一个字，所以好点）
            for (i in iconStart.indices) {
                val s = iconStart.getOrNull(i) ?: -1
                val e = iconEnd.getOrNull(i) ?: -1
                if (s < 0 || e <= s) continue
                if (offset in s until e) {
                    expand(i)
                    return
                }
            }

            // 正文：落到第几条就上屏第几条
            val index = entryIndexOf(offset)
            if (index < 0) return
            if (offset < entryStart[index] || offset >= entryBlockEnd[index]) return
            commitPayload(entries[index].payload)
        }

        /** 二分查找「这个字符偏移属于第几条」。 */
        private fun entryIndexOf(offset: Int): Int {
            if (entryStart.isEmpty()) return -1
            var lo = 0
            var hi = entryStart.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (entryStart[mid] <= offset) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return ans
        }

        /**
         * 展开某一条：把块锁成整宽，并把高度夹在「设置行数」与「屏幕 40%」之间。
         * 只命中一条时块本来只有一行高，展开不该把几百字塞进一行高的窗。
         */
        private fun expand(index: Int) {
            scrollBeforeExpand = scrollY
            heightBeforeExpand = height
            expandedIndex = index
            lastKey = ""
            val params = layoutParams as LinearLayout.LayoutParams
            params.width = LinearLayout.LayoutParams.MATCH_PARENT
            val lineHeight = lineHeight.takeIf { it > 0 } ?: ctx.dp(LINE_FALLBACK_DP)
            val minHeight = expandRows() * lineHeight
            val maxHeight = (ctx.resources.displayMetrics.heightPixels * EXPAND_MAX_RATIO).toInt()
            params.height = heightBeforeExpand.coerceAtLeast(minHeight).coerceAtMost(maxHeight)
            layoutParams = params
            refresh(true)
        }

        private fun collapse() {
            expandedIndex = -1
            lastKey = ""
            val params = layoutParams as LinearLayout.LayoutParams
            params.width = LinearLayout.LayoutParams.MATCH_PARENT
            params.height = LinearLayout.LayoutParams.WRAP_CONTENT
            layoutParams = params
            refresh(true)
            post {
                applyViewportHeight()
                scrollTo(0, scrollBeforeExpand)
            }
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

        /**
         * 视口高度 = 前 N 条（「显示条数」）的高度，内容 = 全部条目。
         * 两者之差就是可滑动的余量；条目不超过 N 条时高度自适应，不留空白。
         */
        private fun applyViewportHeight() {
            if (expandedIndex >= 0) return
            val lp = layoutParams as? LinearLayout.LayoutParams ?: return
            val lay = layout ?: return
            if (entries.isEmpty()) return
            val r = viewportRows.coerceIn(1, 6)
            val want =
                if (entries.size <= r) {
                    LinearLayout.LayoutParams.WRAP_CONTENT
                } else {
                    val endChar = entryBlockEnd.getOrNull(r - 1) ?: -1
                    if (endChar <= 0) return
                    val line = lay.getLineForOffset(endChar - 1)
                    totalPaddingTop + lay.getLineBottom(line) + totalPaddingBottom
                }
            if (want == lp.height) return
            if (want != LinearLayout.LayoutParams.WRAP_CONTENT && kotlin.math.abs(want - lp.height) <= ctx.dp(1)) return
            lp.height = want
            layoutParams = lp
        }

        /** 高亮项换了就把它滚到视口里；用户刚手动滑过就不动。 */
        private fun scrollToHighlight() {
            val index = entries.indexOfFirst { it.highlighted }
            if (index < 0) return
            post {
                if (SystemClock.uptimeMillis() - manualScrollAt < MANUAL_SCROLL_GRACE_MS) return@post
                val lay = layout ?: return@post
                val offset = entryStart.getOrNull(index) ?: return@post
                val line = lay.getLineForOffset(offset)
                if (line < 0) return@post
                scrollTo(0, lay.getLineTop(line).coerceAtLeast(0))
            }
        }

        /** 把颜色按 [percent]（0–100）改成半透明，用于「没命中」的行 / 按钮底色。 */
        private fun withAlpha(color: Int, percent: Int): Int {
            val a = ((color ushr 24) and 0xFF) * percent.coerceIn(0, 100) / 100
            return (color and 0x00FFFFFF) or (a shl 24)
        }
    }

    companion object {
        /** 键盘上那一份。 */
        const val OWNER_KEYBOARD = 0

        /** 悬浮候选窗那一份。 */
        const val OWNER_CANDIDATES = 1

        private const val MIN_SCALE = 0.4f
        private const val MAX_SCALE = 2f
        private const val DEFAULT_SCALE_PCT = 90
        private const val DEFAULT_ALPHA_PCT = 90
        private const val EN_SCALE = 0.82f
        private const val MARK_SCALE = 1.1f
        private const val COUNT_SCALE = 0.78f
        private const val LINE_FALLBACK_DP = 22
        private const val EXPAND_MAX_RATIO = 0.4f
        private const val ICON_SCALE = 1.5f
        private const val BUTTON_BG_ALPHA = 34
        private const val BADGE_SCALE = 0.88f
        private const val GLOSSARY_ICON_SCALE = 1.15f

        /** 三角和提示块底色的最低对比度，低于这个就退回文字色，避免箭头看不见。 */
        private const val MIN_MARK_CONTRAST = 2.0

        /** 手动滑动之后多久之内不再自动把视口拉回高亮项。 */
        private const val MANUAL_SCROLL_GRACE_MS = 2500L

        private const val MARK = "\u25B6\u3000"
        private const val INDENT = "\u3000\u3000"
    }
}
