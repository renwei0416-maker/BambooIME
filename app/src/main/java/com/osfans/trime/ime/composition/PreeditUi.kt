/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import com.osfans.trime.util.BambooLog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
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
import com.osfans.trime.BuildConfig
import com.osfans.trime.actions.glossary.GlossaryTips
import com.osfans.trime.actions.tips.TipBlocks
import com.osfans.trime.actions.tips.TipEntry
import com.osfans.trime.actions.wenquxing.WenquxingPrefs
import com.osfans.trime.actions.wenquxing.WenquxingTips
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.segments.PieceTokenizer
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
/**
 * 外语学习提示块的共享状态。
 *
 * 键盘侧与候选窗侧各有一个 [PreeditUi]，也就各有一个 GlossaryTipsView 实例。
 * 展开项、钉住的条目、撤回长度、会话号若挂成 View 的字段，两个实例会各算一份，
 * 于是「屏幕上显示的是哪一份」与「点击落到哪一份」可能不一致 —— 撤回会凭空失效。
 * 这里集中放一份，两个实例只读写这里。
 */
private object GlossaryTipsState {
    /** 展开的条目下标；-1 = 列表态。 */
    var expandedIndex: Int = -1

    /** 提交之后钉住的条目快照（组合被清空后仍要能画出来）。 */
    var pinnedItem: TipEntry? = null

    /** 待撤回的 UTF-16 长度；>0 表示按钮处于「撤回上屏」。 */
    var undoLength: Int = 0

    /** 提交/撤回事务号，用来丢弃过期的异步回调。 */
    var txn: Int = 0

    /** 事务状态：见 [UNDO_NONE] / [UNDO_COMMITTED] / [UNDO_PENDING]。 */
    var undoState: Int = UNDO_NONE

    /** 上次看到的输入会话号。 */
    var seenSession: Int = -1
}

/** 没有待办事务。 */
private const val UNDO_NONE = 0

/** 已上屏，可以撤回。 */
private const val UNDO_COMMITTED = 1

/** 正在撤回（按钮禁用以防连点）。 */
private const val UNDO_PENDING = 2


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

    private val wqTips = WenquxingTipsView()
    private val glTips = GlossaryTipsView()

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
        if (!buildLogged) {
            buildLogged = true
            BambooLog.tips.d(
                "tips",
                "build=" + BuildConfig.BUILD_VERSION_NAME + " vc=" + BuildConfig.VERSION_CODE +
                    " (nomodel5: voice idle timer + no trim on hide)",
            )
        }
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
        val active = tipsShownHere()
        val before = tipsTouchable
        val me = "owner=" + tipsOwner + " @" + Integer.toHexString(System.identityHashCode(this))
        // 非活动的那个实例只能把触摸区打开，不能写回 false。
        // 两个实例各 refresh 一轮就会 true/false 互相覆盖，抖动期间手指按下的那一下
        // 会被系统判成点到下面的 App（表现为「块还在，却点不动、穿透」）。
        if (!active && !any) {
            BambooLog.tips.d("tips", "touchable skip inactive any=false before=" + before + " " + me)
            return
        }
        if (any == tipsTouchable) return
        tipsTouchable = any
        BambooLog.tips.d(
            "tips",
            "touchable active=" + active + " any=" + any + " before=" + before +
                " after=" + tipsTouchable + " " + me,
        )
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
    private inner class WenquxingTipsView : FrameLayout(ctx) {
        // 由 TipsView 复制而来，`wenquxing` 固定为 true。
        // 两块提示各自一份代码：改这边不会影响另一边。
        private val wenquxing = true

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

        /**
         * 排版测量用的字号（px）。
         *
         * [bodyTextSize] 是 sp（直接给 TextView.textSize 用是对的），但 Paint.textSize 要 px。
         * 之前把 sp 当 px 用，量出来的字只有实际的三分之一大，于是 StaticLayout 认为行数很少、
         * 永远不截断——外文学习「设了 2 行却显示五六行」就是这么来的。
         */
        private fun textSizePx(): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                bodyTextSize(),
                resources.displayMetrics,
            )

        /** 每条渲染时算出来的「是否被截断」——展开入口只看这个，不再看数据层猜的 expandable。 */
        private val entryExpandable = HashMap<Int, Boolean>()

        /** 每条最多显示几行（两个模块各自设置）。 */
        private fun maxLines(): Int =
            runCatching { GlossaryPrefs.maxLines }.getOrDefault(2).coerceIn(1, MAX_ENTRY_LINES)

        /** 条目可用宽度（px）：块宽 - 内边距 - 条目自身左右 padding。 */
        private fun entryAvailWidth(): Int {
            val w =
                measuredWidth.takeIf { it > 0 }
                    ?: runCatching { ctx.resources.displayMetrics.widthPixels }.getOrDefault(1080)
            val used = paddingLeft + paddingRight + column.paddingLeft + column.paddingRight +
                ctx.dp(theme.preedit.horizontalPadding) * 2
            return (w - used).coerceAtLeast(120)
        }

        /**
         * 按「宽度 + 最多行数」真实排版：放得下就原样返回，放不下就二分最长前缀，
         * 并在尾部拼上 [...展开]。返回 (显示用文本, 是否被截断)。
         */
        private fun textPaint(): TextPaint =
            TextPaint().apply {
                isAntiAlias = true
                textSize = textSizePx()
                typeface = FontManager.getTypeface()
                color = textColor
            }

        /** 用真实宽度排一遍，返回行数。 */
        private fun lineCountOf(cs: CharSequence, width: Int): Int =
            StaticLayout.Builder.obtain(cs, 0, cs.length, textPaint(), width)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false)
                .build()
                .lineCount

        /**
         * 只把「正文」裁到最多 [lines] 行，返回 (显示用文本, 是否被截断)。
         * 提示按钮不在这里拼——它由 [withHint] 负责，保证不会被拆成 [...展 / 开]。
         */
        private fun fitLines(src: CharSequence, width: Int, lines: Int): Pair<CharSequence, Boolean> {
            if (src.isEmpty()) return src to false
            if (src.length <= MEASURE_CHAR_CAP && lineCountOf(src, width) <= lines) return src to false
            var lo = 0
            var hi = minOf(src.length, MEASURE_CHAR_CAP)
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                val trial = src.subSequence(0, mid)
                if (lineCountOf(trial, width) <= lines) lo = mid else hi = mid - 1
            }
            return src.subSequence(0, lo) to true
        }

        /**
         * 把 [...展开] 当按钮拼上去：能整块放进行尾就放行尾，放不下就整块换到下一行。
         * 绝不允许折成两行（历史上就是被挤成 [...展 / 开]）。
         */
        private fun withHint(base: CharSequence, width: Int): CharSequence {
            // 提示一定和正文待在同一行末尾：放不下就从正文尾部删字，
            // 绝不 append("\n") 另起一行（那样会多占一行、把块撑高）。
            val hint = EXPAND_HINT_TEXT
            val limit = lineCountOf(base, width).coerceAtLeast(1)
            var cut = base.length
            while (cut > 1) {
                val cand = SpannableStringBuilder(base.subSequence(0, cut)).append(hint)
                if (lineCountOf(cand, width) <= limit) {
                    styleHint(cand, cand.length - hint.length, cand.length)
                    return cand
                }
                cut--
            }
            val only = SpannableStringBuilder(hint)
            styleHint(only, 0, only.length)
            return only
        }

        private fun styleHint(sb: SpannableStringBuilder, start: Int, end: Int) {
            sb.setSpan(ForegroundColorSpan(tipColor), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(COUNT_SCALE), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }

        /** 量一段文字在正文尺寸下的像素宽度（悬挂缩进要用）。 */
        private val measurePaint =
            android.graphics.Paint().apply {
                isAntiAlias = true
            }

        private fun measureWidth(text: CharSequence): Int {
            measurePaint.textSize = textSizePx()
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
                // 「真的没有候选了」和「只是窗口暂时不可见」必须分开：
                // 前者要连指纹一起清（否则重新打回同样的编码会命中淡出的旧指纹，
                // 滚动位置也停在上次滑到的地方）；后者要保留，否则展开态会被自己人清掉。
                val gone =
                    runCatching {
                        if (wenquxing) WenquxingTips.entries().isEmpty() else GlossaryTips.entries().isEmpty()
                    }.getOrDefault(true)
                if (clear || gone) hardReset() else reset()
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
                BambooLog.tips.d(
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
                // 之前被 reset() 清过视图，内容没变：按原状态（含展开态）重画，并回到顶部
                render()
                scroller.scrollTo(0, 0)
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
            entryExpandable.clear()
            column.removeAllViews()
            if (expandedIndex >= 0) {
                val item = entries.getOrNull(expandedIndex)
                if (item == null) {
                    expandedIndex = -1
                } else {
                    scroller.setPadding(0, ctx.dp(COLLAPSE_H_DP + 8), 0, 0)
                    // 展开态也要带 tag：dispatchTouchEvent 的 childIndexAtScreen 只认带 tag 的子 View，
                    // 不带的话点正文拿不到 index → activate 不执行 → 「展开后点内容不上屏」。
                    column.addView(makeDetail(item).apply { tag = expandedIndex })
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
                    topMargin = ctx.dp(1)
                    bottomMargin = ctx.dp(1)
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
                }
            // 真实排版后再决定要不要截断（宽度富裕就多显示，窄就少显示），
            // 截断过才给 [...展开] 入口 —— 这样不会再出现「有入口没内容 / 有内容没入口」。
            // 文曲星：按「条目显示字数」控制，展开入口按字数判定，不再按行数二次裁；
            // 外语学习：按「宽度 + 最多行数」真实排版后再裁。
            val width = entryAvailWidth()
            val fitted =
                if (wenquxing) {
                    // 文曲星按「条目显示字数」截断（数据层已截），这里不再按行二次裁
                    text to false
                } else {
                    fitLines(text, width, maxLines())
                }
            val truncated = if (wenquxing) item.expandable else fitted.second
            entryExpandable[index] = truncated
            // 提示按钮整块放：行尾放得下就放行尾，放不下整块换行，绝不拆开
            val shown = if (truncated) withHint(fitted.first, width) else fitted.first
            return entryView(shown, bodyTextSize()).apply {
                tag = index
                setPadding(paddingLeft, ctx.dp(2), paddingRight, ctx.dp(2))
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
                            BambooLog.tips.d("tips", "tap collapse rawY=" + ev.rawY.toInt())
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
                            BambooLog.tips.d(
                                "tips",
                                "tap rawY=" + ev.rawY.toInt() + " blockTop=" + me[1] +
                                    " scrollerTop=" + sc[1] + " columnTop=" + col[1] +
                                    " localY=" + ev.y.toInt() + " yInColumn=" + yInColumn.toInt() +
                                    " idx=" + idx + " scrollY=" + scroller.scrollY,
                            )
                            BambooLog.tips.d("tips", "screen$scr")
                        }
                        BambooLog.tips.d(
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
            BambooLog.tips.d(
                "tips",
                "activate idx=" + idx + " expandable=" + entryExpandable[idx] + " text=" + item.text.take(12),
            )
            if (entryExpandable[idx] == true) expand(idx) else commitPayload(item.payload)
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
                    BambooLog.tips.d("tips", "bounds " + line)
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
                val h = expandHeightPx()
                if (h != lastMeasured) {
                    lastMeasured = h
                    BambooLog.tips.d("tips", "measure ${if (wenquxing) "wq" else "gl"} expanded h=$h")
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
                BambooLog.tips.d(
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
            if (entryExpandable[index] != true) {
                commitPayload(item.payload)
                return
            }
            scrollBeforeExpand = scroller.scrollY
            expandedIndex = index
            render()
            requestLayout()
            (parent as? View)?.requestLayout()
            BambooLog.tips.d(
                "tips",
                "expand ${if (wenquxing) "wq" else "gl"} idx=" + index +
                    " h=" + expandHeightPx(),
            )
            post { scroller.scrollTo(0, 0) }
        }

        /** 展开态固定高度：占屏高比例（文曲星 28%、外语学习 14%）。 */
        private fun expandHeightPx(): Int {
            val ratio = if (wenquxing) EXPAND_RATIO_WQ else EXPAND_RATIO_GL
            return (resources.displayMetrics.heightPixels * ratio).toInt().coerceAtLeast(1)
        }

        /**
         * 直接改 layoutParams.height 并让父容器重排。
         *
         * 只靠 requestLayout() 在输入法窗口里不可靠：onMeasure 会走展开分支把高度算对，
         * 但父 LinearLayout 不一定重新遍历——表现就是「点了展开没反应、也不能滑」。
         */
        private fun applyHeight(px: Int) {
            val lp = layoutParams ?: return
            val target = if (px > 0) px else ViewGroup.LayoutParams.WRAP_CONTENT
            if (lp.height == target) return
            lp.height = target
            layoutParams = lp
            requestLayout()
            (parent as? View)?.requestLayout()
            // 输入法窗口里父容器不一定会重排（触摸派发期间尤其如此），
            // 这里直接按已知高度自测自排，保证「点了展开立刻变高」，不依赖 ViewRoot 那次遍历。
            if (px > 0 && width > 0) {
                measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY),
                )
                layout(left, top, right, top + px)
            } else if (px <= 0 && width > 0) {
                measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                layout(left, top, right, top + measuredHeight)
                parent?.requestLayout()
            }
            invalidate()
        }

        private fun collapse() {
            expandedIndex = -1
            collapseButton.visibility = View.GONE
            render()
            // 收起后把高度交还给内容（WRAP_CONTENT）并强制父容器重排
            val lpBack = layoutParams
            if (lpBack != null) {
                lpBack.height = ViewGroup.LayoutParams.WRAP_CONTENT
                layoutParams = lpBack
            }
            requestLayout()
            (parent as? View)?.requestLayout()
            BambooLog.tips.d("tips", "collapse ${if (wenquxing) "wq" else "gl"}")
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

    private inner class GlossaryTipsView : LinearLayout(ctx) {
        /**
         * 顶部按钮行：占一行真实布局，不做浮层。
         *
         * 浮层按钮会被下面的滚动区盖住、并且只能靠手工坐标判命中；
         * 改成独立一行后，点击走标准 View 分发，不会出现「点了没反应」。
         */
        private val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // 按钮整体靠右，右侧留白由容器 padding 给。
            // 用子控件的 rightMargin 时，一旦「可用宽度」算得比控件实际宽，
            // 按钮就会被顶出右边界，表现为右圆角被裁掉。
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, 0, ctx.dp(HEADER_PAD_END_DP), 0)
        }
        // 由 TipsView 复制而来，`wenquxing` 固定为 false。
        // 两块提示各自一份代码：改这边不会影响另一边。
        private val wenquxing = false

        /** 点词 / 全部上屏 / 撤回上屏后，下一次 refresh 的清空是被动的，要原地不动。 */
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

        /** 展开态：按分词结果把正文排成可点的词块，顺序与原文一致。 */
        /**
         * 词块背景：自己 new 一个 GradientDrawable。
         *
         * 不用 ColorManager.getDecorDrawable：它返回缓存实例并就地改 cornerRadius，
         * 多个词块共用同一对象时圆角和状态会互相污染。描边保证任何主题下边界都可见。
         */
        private fun tokenCellDrawable(colorKey: String): GradientDrawable {
            val radius = ctx.dp(theme.generalStyle.roundCorner.toFloat()).toFloat()
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(
                    runCatching { ColorManager.getColor(colorKey) }
                        .getOrDefault(android.graphics.Color.TRANSPARENT),
                )
                setStroke(
                    ctx.dp(1),
                    runCatching { ColorManager.getColor("border_color") }
                        .getOrDefault(android.graphics.Color.GRAY) and 0x66FFFFFF,
                )
            }
        }

        private fun makeTokenDetail(item: TipEntry): View {
            val avail = expandedAvailWidth()
            val paint = textPaint()
            val tokens =
                runCatching { PieceTokenizer.split(item.detail) }
                    .getOrDefault(listOf(item.detail))
            val root =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    // 这里不能用 sidePadPx()：那是列表态给右侧三角列留的位置，
                    // 展开态没有那一列，按它设内边距会让末尾词块排到父容器外被裁掉。
                    setPadding(
                        ctx.dp(EXPAND_PAD_H_DP),
                        ctx.dp(2),
                        ctx.dp(EXPAND_PAD_H_DP),
                        ctx.dp(2),
                    )
                }
            var row =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            var used = 0
            val cellPadH = ctx.dp(4) * 2 + ctx.dp(2) * 2

            fun flush() {
                if (row.childCount > 0) {
                    root.addView(
                        row,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
                row =
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                used = 0
            }

            // 空白与换行不显示，但并入它前一个词的上屏文本，
            // 否则相邻两个词点出来会粘在一起。
            val cells = ArrayList<Pair<String, String>>()
            var ti = 0
            while (ti < tokens.size) {
                val word = tokens[ti]
                if (word.isBlank()) {
                    ti++
                    continue
                }
                var commit = word
                var tj = ti + 1
                while (tj < tokens.size && tokens[tj].isBlank()) {
                    commit += tokens[tj]
                    tj++
                }
                cells.add(word to commit)
                ti = tj
            }

            for ((display, commitText) in cells) {
                // cell 的真实宽度 = 文字宽 + 左右 padding + 左右 margin，
                // 只按文字宽判断会在行尾“看着放得下、实际放不下”，被父行硬压成两行。
                val w = paint.measureText(display).toInt() + cellPadH
                if (used > 0 && used + w > avail) flush()
                val cell =
                    TextView(ctx).apply {
                        text = display
                        textSize = bodyTextSize()
                        typeface = FontManager.getTypeface()
                        setPadding(ctx.dp(4), ctx.dp(1), ctx.dp(4), ctx.dp(1))
                        includeFontPadding = false
                        setLineSpacing(0f, 1f)
                        setTextColor(
                            ColorStateList(
                                arrayOf(
                                    intArrayOf(-android.R.attr.state_pressed),
                                    intArrayOf(android.R.attr.state_pressed),
                                ),
                                intArrayOf(
                                    ColorManager.getColor("key_text_color"),
                                    ColorManager.getColor("hilited_key_text_color"),
                                ),
                            ),
                        )
                        background =
                            StateListDrawable().apply {
                                addState(
                                    intArrayOf(-android.R.attr.state_pressed),
                                    tokenCellDrawable("key_back_color"),
                                )
                                addState(
                                    intArrayOf(android.R.attr.state_pressed),
                                    tokenCellDrawable("hilited_key_back_color"),
                                )
                            }
                        clipToOutline = true
                        outlineProvider = ViewOutlineProvider.BACKGROUND
                        tag = commitText
                        // 点击由 cell 自己消费：父类只做滚动，不再算坐标命中。
                        isClickable = true
                        setOnClickListener {
                            BambooLog.tips.d("tips", "cell click len=" + commitText.length)
                            setUndo(0)
                            commitPayload(commitText)
                        }
                    }
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        // 横向合计 5dp：左右各 2dp 外边距 + 累加时补 1dp。
                        leftMargin = ctx.dp(2)
                        rightMargin = ctx.dp(2)
                    },
                )
                used += w
            }
            flush()
            return root
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

        /** 展开态右上角「全部上屏」，位于「收起」左侧。 */
        val commitAllButton: TextView =
            TextView(ctx).apply {
                text = COMMIT_ALL_LABEL
                textSize = COLLAPSE_TEXT_SP
                setTextColor(textColor)
                gravity = Gravity.CENTER
                visibility = View.GONE
                background =
                    GradientDrawable().apply {
                        setColor(withAlpha(textColor, BUTTON_BG_ALPHA))
                        cornerRadius = ctx.dp(6).toFloat()
                    }
            }

        private var entries: List<TipEntry> = emptyList()
        private var snapshot = 0

        /** 展开项下标；与另一份实例共用，见 [GlossaryTipsState]。 */
        private var expandedIndex: Int
            get() = GlossaryTipsState.expandedIndex
            set(v) {
                GlossaryTipsState.expandedIndex = v
            }

        /**
         * 「全部上屏」已经提交的字符数（>0 表示下一次点击是撤回）。
         *
         * 与按钮文案同源；放在共享状态里，避免「显示的是这一份、点击落到那一份」导致撤回凭空丢失。
         */
        private var undoLength: Int
            get() = GlossaryTipsState.undoLength
            set(v) {
                GlossaryTipsState.undoLength = v
            }
        private var scrollBeforeExpand = 0

        /**
         * 展开时钉住那一条。
         *
         * 点词 / 「全部上屏」会清空组合，`entries` 随之变空；此时若还从 `entries` 取内容，
         * 展开块既画不出东西也会被当成“没有匹配项”收掉。钉住之后渲染与判定都不再依赖 `entries`。
         */
        private var pinnedItem: TipEntry?
            get() = GlossaryTipsState.pinnedItem
            set(v) {
                GlossaryTipsState.pinnedItem = v
            }

        /** 上次看到的输入会话号；变了说明换了编辑框，展开块要退掉。 */
        private var seenSession: Int
            get() = GlossaryTipsState.seenSession
            set(v) {
                GlossaryTipsState.seenSession = v
            }

        /** 上一次测出的高度，只用来避免日志刷屏。 */
        private var lastMeasured = -1

        /**
         * 本次测量得到的宽度。
         *
         * 流式排版发生在测量之前，那时 `width`/`measuredWidth` 还是 0，
         * 以前会退回「整屏宽」，于是按更宽的量换行，行尾越过控件右边界被裁掉。
         */
        private var measuredW = 0

        /** 上次记录的宽度，只用于让宽度日志不刷屏。 */
        private var lastWidth = -1
        private var lastBounds = ""

        /** 视图被清空过（隐藏时），回来要按 snapshot 重画一次。 */
        private var dirty = true

        init {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            // 两颗按钮都走原生点击：不受可见性/布局时机影响。
            collapseButton.isClickable = true
            commitAllButton.isClickable = true
            collapseButton.setOnClickListener {
                BambooLog.tips.d("tips", "collapseBtn click " + idTag)
                collapse()
            }
            commitAllButton.setOnClickListener {
                BambooLog.tips.d("tips", "commitAll click undo=" + undoLength + " " + idTag)
                onCommitAllClick()
            }
            // header 行：[弹性空白][全部上屏][6dp][收起]，右侧留白见 headerRow 的 paddingEnd
            headerRow.addView(
                View(ctx),
                LinearLayout.LayoutParams(0, 1, 1f),
            )
            headerRow.addView(
                commitAllButton,
                LinearLayout.LayoutParams(ctx.dp(COMMIT_ALL_W_DP), ctx.dp(COLLAPSE_H_DP)),
            )
            headerRow.addView(
                View(ctx),
                LinearLayout.LayoutParams(ctx.dp(6), 1),
            )
            headerRow.addView(
                collapseButton,
                LinearLayout.LayoutParams(ctx.dp(COLLAPSE_W_DP), ctx.dp(COLLAPSE_H_DP)),
            )
            addView(
                headerRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ctx.dp(COLLAPSE_H_DP + HEADER_EXTRA_DP),
                ),
            )
            // 滚动区占满 header 以下的全部高度。
            addView(
                scroller,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }

        /** 两个实例（键盘内 / 候选窗内）日志要能分清。 */
        private val idTag: String get() = "#" + Integer.toHexString(System.identityHashCode(this))


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

        /**
         * 排版测量用的字号（px）。
         *
         * [bodyTextSize] 是 sp（直接给 TextView.textSize 用是对的），但 Paint.textSize 要 px。
         * 之前把 sp 当 px 用，量出来的字只有实际的三分之一大，于是 StaticLayout 认为行数很少、
         * 永远不截断——外文学习「设了 2 行却显示五六行」就是这么来的。
         */
        private fun textSizePx(): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                bodyTextSize(),
                resources.displayMetrics,
            )

        /** 每条渲染时算出来的「是否被截断」——展开入口只看这个，不再看数据层猜的 expandable。 */
        private val entryExpandable = HashMap<Int, Boolean>()

        /** 每条最多显示几行（两个模块各自设置）。 */
        private fun maxLines(): Int =
            runCatching { GlossaryPrefs.maxLines }.getOrDefault(2).coerceIn(1, MAX_ENTRY_LINES)

        /** 条目可用宽度（px）：块宽 - 内边距 - 条目自身左右 padding。 */
        /**
         * 展开态可用宽度。
         *
         * [entryAvailWidth] 会扣掉右下角三角列（列表态才有），展开态没有那一列，
         * 继续扣就会每行凭空少两个字宽，表现为“右边明明还有空间却提前换行”。
         */
        private fun expandedAvailWidth(): Int {
            val w =
                measuredW.takeIf { it > 0 } ?: width.takeIf { it > 0 }
                    ?: measuredWidth.takeIf { it > 0 }
                    ?: runCatching { ctx.resources.displayMetrics.widthPixels }.getOrDefault(1080)
            // 展开态没有三角列，不要扣 sidePad —— 否则每行右侧会白白空掉一段，
            // 看着就像“右边被裁掉了”。只扣真实存在的内边距与块的外边距。
            val used = paddingLeft + paddingRight + column.paddingLeft + column.paddingRight +
                ctx.dp(EXPAND_PAD_H_DP) * 2 + ctx.dp(2) * 2
            return (w - used - ctx.dp(HEADER_PAD_END_DP)).coerceAtLeast(120)
        }

        private fun entryAvailWidth(): Int {
            val w =
                measuredW.takeIf { it > 0 } ?: measuredWidth.takeIf { it > 0 }
                    ?: runCatching { ctx.resources.displayMetrics.widthPixels }.getOrDefault(1080)
            val used = paddingLeft + paddingRight + column.paddingLeft + column.paddingRight +
                sidePadPx() * 2 + arrowBoxPx() + arrowEndExtraPx()
            return (w - used).coerceAtLeast(120)
        }

        /**
         * 按「宽度 + 最多行数」真实排版：放得下就原样返回，放不下就二分最长前缀，
         * 并在尾部拼上 [...展开]。返回 (显示用文本, 是否被截断)。
         */
        private fun textPaint(): TextPaint =
            TextPaint().apply {
                isAntiAlias = true
                textSize = textSizePx()
                typeface = FontManager.getTypeface()
                color = textColor
            }

        /** 用真实宽度排一遍，返回行数。 */
        private fun lineCountOf(cs: CharSequence, width: Int): Int =
            StaticLayout.Builder.obtain(cs, 0, cs.length, textPaint(), width)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false)
                .build()
                .lineCount

        /**
         * 只把「正文」裁到最多 [lines] 行，返回 (显示用文本, 是否被截断)。
         * 提示按钮不在这里拼——它由 [withHint] 负责，保证不会被拆成 [...展 / 开]。
         */
        private fun fitLines(src: CharSequence, width: Int, lines: Int): Pair<CharSequence, Boolean> {
            if (src.isEmpty()) return src to false
            if (src.length <= MEASURE_CHAR_CAP && lineCountOf(src, width) <= lines) return src to false
            var lo = 0
            var hi = minOf(src.length, MEASURE_CHAR_CAP)
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                val trial = src.subSequence(0, mid)
                if (lineCountOf(trial, width) <= lines) lo = mid else hi = mid - 1
            }
            return src.subSequence(0, lo) to true
        }

        /**
         * 把 [...展开] 当按钮拼上去：能整块放进行尾就放行尾，放不下就整块换到下一行。
         * 绝不允许折成两行（历史上就是被挤成 [...展 / 开]）。
         */
        private fun styleHint(sb: SpannableStringBuilder, start: Int, end: Int) {
            sb.setSpan(ForegroundColorSpan(tipColor), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(EXPAND_ARROW_SCALE), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }

        /** 量一段文字在正文尺寸下的像素宽度（悬挂缩进要用）。 */
        private val measurePaint =
            android.graphics.Paint().apply {
                isAntiAlias = true
            }

        private fun measureWidth(text: CharSequence): Int {
            measurePaint.textSize = textSizePx()
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
        BambooLog.tips.d(
            "tips",
            idTag + " refresh active=" + active + " clear=" + clear + " entries=" + entries.size +
                " expanded=" + expandedIndex + " pinned=" + (pinnedItem != null) +
                " session=" + sessionToken + " seen=" + seenSession,
        )
            if (seenSession != sessionToken) {
                seenSession = sessionToken
                pinnedItem = null
                // 换了编辑框：旧的下文/上屏事务都不再成立，撤回也必须失效，
                // 否则会把删除作用到新编辑框里。
                GlossaryTipsState.txn++
                GlossaryTipsState.undoState = UNDO_NONE
                GlossaryTipsState.undoLength = 0
                hardReset()
                return
            }
            if (!active) {
                // 只是「本实例不该显示」：清自己的绘制与可见性即可，
                // 不能动共享的展开项/撤回状态 —— 另一个实例可能正靠它显示同一块。
                hideLocal()
                return
            }
            if (clear && GlossaryTipsState.undoState == UNDO_NONE && pinnedItem == null) {
                // 调用方明确说「一条候选都没有」，且没有待撤回的事务：丢掉展开态，
                // 免得下次打出同一编码又看到上一次的展开内容。
                pinnedItem = null
                hardReset()
                return
            }
            val list =
                runCatching {
                    if (wenquxing) WenquxingTips.entries() else GlossaryTips.entries()
                }.getOrNull().orEmpty()
            if (list.isNotEmpty()) {
                // 又打出了新的匹配项：之前钉住的那一条已经过期，换回按 entries 渲染。
                pinnedItem = null
            } else if (expandedIndex >= 0 && pinnedItem != null) {
                // 空列表是我们自己提交造成的（点词 / 全部上屏 / 撤回），展开块保持不动。
                return
            }
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
                BambooLog.tips.d(
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
                // 之前被 reset() 清过视图，内容没变：按原状态（含展开态）重画，并回到顶部
                render()
                scroller.scrollTo(0, 0)
                dirty = false
            }
            applyVisibility(View.VISIBLE)
        }

        /**
         * 本实例不再显示时的收尾：清自己的列表快照与可见性。
         *
         * 与 [hardReset] 的区别是**不碰共享状态**：展开项、钉住的条目、撤回事务属于整个
         * 外语学习功能，不属于某一个宿主实例。
         */
        private fun hideLocal() {
            entries = emptyList()
            snapshot = 0
            dirty = true
            entryExpandable.clear()
            reset()
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
        BambooLog.tips.d("tips", "render expanded=" + expandedIndex + " entries=" + entries.size)
            entryExpandable.clear()
            column.removeAllViews()
            if (expandedIndex >= 0) {
                val item = pinnedItem ?: entries.getOrNull(expandedIndex)
                if (item == null) {
                    expandedIndex = -1
                } else {
                    // 按钮独占顶部一行，词块从它下方开始，这里不再留白。
                    scroller.setPadding(0, 0, 0, 0)
                    // 展开态也要带 tag：dispatchTouchEvent 的 childIndexAtScreen 只认带 tag 的子 View，
                    // 不带的话点正文拿不到 index → activate 不执行 → 「展开后点内容不上屏」。
                    column.addView(makeTokenDetail(item).apply { tag = expandedIndex })
                    collapseButton.visibility = View.VISIBLE
                    commitAllButton.visibility = View.VISIBLE
                    return
                }
            }
            scroller.setPadding(0, 0, 0, 0)
            collapseButton.visibility = View.GONE
            commitAllButton.visibility = View.GONE
            val badge = if (wenquxing) runCatching { WenquxingTips.badgeText() }.getOrNull() else null
            if (!badge.isNullOrEmpty()) column.addView(makeBadge(badge))
            val markAt = entries.indexOfFirst { it.highlighted }.takeIf { it >= 0 } ?: 0
            entries.forEachIndexed { index, item ->
                if (index > 0) column.addView(makeDivider())
                // 列表条目自己响应点击：展开由条目视图触发，父类不再算坐标。
                column.addView(
                    makeEntry(index, item, index == markAt).apply {
                        isClickable = true
                        setOnClickListener {
                            BambooLog.tips.d("tips", "entry click idx=" + index)
                            activate(index)
                        }
                    },
                )
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
                    topMargin = ctx.dp(1)
                    bottomMargin = ctx.dp(1)
                }
            }

        /** 一个正文字符的宽度（px）：用来把「2 个字宽」换算成像素。 */
        private fun charWidthPx(): Float {
            val p = textPaint()
            return p.measureText("\u4E00").coerceAtLeast(1f)
        }

        /** 行首/行尾留白：保持主题的横向内边距不变（行首 ▶ 的位置跟以前一样）。 */
        private fun sidePadPx(): Int = ctx.dp(theme.preedit.horizontalPadding)

        /**
         * 行尾三角列的宽度（px）。
         *
         * 这一列始终存在（不可展开的行也留空），
         * 所以正文实际可用宽度必须先把它扣掉——
         * 否则「按宽度算能放下、实际放不下」就会多折一行。
         */
        private fun arrowBoxPx(): Int =
            (charWidthPx() * ARROW_BOX_CHARS).toInt().coerceAtLeast(ctx.dp(10))

        /** 行尾额外留白（px）。 */
        private fun arrowEndExtraPx(): Int = (charWidthPx() * ARROW_END_EXTRA_CHARS).toInt()

        /**
         * 一条列表项 = 正文 + **固定位置**的展开三角。
         *
         * 三角不参与文字排版：它固定贴在整条右下、右侧留白槽里居中，
         * 所以不管这条最后一行写了多少字，所有条的三角都落在同一个 x 上、竖直成一列。
         * 正文左右各留 2 个字宽，三角就住右边那 2 个字宽里，压不到字。
         */
        private fun entryRow(body: CharSequence, expandable: Boolean): View {
            val side = sidePadPx()
            val box = arrowBoxPx()
            val bodyView =
                entryView(body, bodyTextSize()).apply {
                    setPadding(0, ctx.dp(2), 0, ctx.dp(2))
                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                }
            // 尾列始终存在：可展开就放三角，不可展开就留空。
            // 这样所有行的正文宽度完全一致，折行也就一致；
            // 而且与 [entryAvailWidth] 里扣掉的那一列对得上。
            val tail =
                if (expandable) {
                    TextView(ctx).apply {
                        text = EXPAND_HINT_ARROW
                        textSize = bodyTextSize() * EXPAND_ARROW_SCALE
                        typeface = FontManager.getTypeface()
                        setTextColor(tipColor)
                        setLineSpacing(0f, 1.0f)
                        includeFontPadding = false
                        setPadding(0, 0, 0, 0)
                        gravity = Gravity.CENTER
                        isClickable = false
                        isFocusable = false
                    }
                } else {
                    View(ctx)
                }
            tail.layoutParams =
                LinearLayout.LayoutParams(
                    box,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            return LinearLayout(ctx).apply {
                isClickable = true
                gravity = Gravity.CENTER_VERTICAL
                setPadding(side, 0, side + arrowEndExtraPx(), 0)
                addView(bodyView)
                addView(tail)
            }
        }

        /** 一条列表项：`▶ 中文  释义  ·N字  [...]展开`，整行可点。 */
        private fun makeEntry(
            index: Int,
            item: TipEntry,
            isMark: Boolean,
        ): View {
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
                }
            // 真实排版后再决定要不要截断（宽度富裕就多显示，窄就少显示），
            // 截断过才给 [...展开] 入口 —— 这样不会再出现「有入口没内容 / 有内容没入口」。
            // 文曲星：按「条目显示字数」控制，展开入口按字数判定，不再按行数二次裁；
            // 外语学习：按「宽度 + 最多行数」真实排版后再裁。
            val width = entryAvailWidth()
            val fitted =
                if (wenquxing) {
                    // 文曲星按「条目显示字数」截断（数据层已截），这里不再按行二次裁
                    text to false
                } else {
                    fitLines(text, width, maxLines())
                }
            // 外语学习每条都可展开：展开后进去点词，条目本身不再直接上屏。
            val truncated = true
            entryExpandable[index] = truncated
            return entryRow(fitted.first, truncated).apply {
                tag = index
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
                // 不自己消费点击：让点击冒泡到行容器（行容器才有 activate 监听）。
                // 否则点正文没反应、只有点行尾空白（三角那一列）才能展开。
                isClickable = false
                isFocusable = false
            }

        private var downRawY = 0f
        private var downAt = 0L
        private var moved = false

        /** 抬手兜底命中用的条目下标（-1 = 不在条目上）。 */
        private var downIndex = -1

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
                    downIndex = hitIndexAt(ev.rawY)
                    BambooLog.tips.d(
                        "tips",
                        "gl down rawY=" + ev.rawY.toInt() + " idx=" + downIndex +
                            " scrollY=" + scroller.scrollY + " childH=" + column.height +
                            " viewH=" + scroller.height + " scrollerKids=" + scroller.childCount,
                    )
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!moved && kotlin.math.abs(ev.rawY - downRawY) > ctx.dp(TAP_SLOP_DP)) {
                        moved = true
                        // 诊断：确认拖动事件真的到了、ScrollView 当前的滚动位置是多少
                        BambooLog.tips.d(
                            "tips",
                            "gl move dy=" + (ev.rawY - downRawY).toInt() +
                                " scrollY=" + scroller.scrollY +
                                " scrollable=" + scroller.canScrollVertically(1) +
                                " childH=" + column.height + " viewH=" + scroller.height,
                        )
                    }
                }

            }
            val handled = super.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                // 诊断抬手：handled=false 说明没有子 View 消费，moved=true 时兜底会被跳过。
                BambooLog.tips.d(
                    "tips",
                    "gl up handled=" + handled + " moved=" + moved +
                        " dy=" + (ev.rawY - downRawY).toInt() + " idx=" + downIndex +
                        " scrollY=" + scroller.scrollY,
                )
            }
            // 子 View（按钮、词块）没有消费这次抬手时兜底：按 DOWN 记下的条目展开。
            if (ev.actionMasked == MotionEvent.ACTION_UP && !handled && !moved) {
                val quick = System.currentTimeMillis() - downAt < 600L
                if (quick && downIndex >= 0 && expandedIndex < 0) {
                    BambooLog.tips.d("tips", "tap fallback idx=" + downIndex)
                    activate(downIndex)
                    return true
                }
            }
            return handled
        }

        /**
         * 「全部上屏 / 撤回上屏」两态。
         *
         * 第一次点击把当前条目的整段正文上屏并记住字符数；再次点击按该字符数删回去。
         * 换条目、收起、点单词上屏都会复位，避免删掉用户后面输入的内容。
         */
        /**
         * 「全部上屏」/「撤回上屏」两个动作共用一个按钮，由 [undoLength] 单一决定。
         *
         * 这里不再使用 `entries`：提交后组合被清空、`entries` 会变空，
         * 旧写法会在第二次点击时 `?: return`，表现成“点了没反应”。
         */
        private fun onCommitAllClick() {
            val service = TrimeInputMethodService.current
            if (service == null) {
                toast("输入法未就绪，稍后再试")
                return
            }
            BambooLog.tips.d(
                "tips",
                idTag + " commitAll click undo=" + undoLength + " txn=" + GlossaryTipsState.txn +
                    " state=" + GlossaryTipsState.undoState + " session=" + sessionToken +
                    " expanded=" + expandedIndex,
            )
            if (undoLength > 0) {
                undoCommit(service)
                return
            }
            val item =
                pinnedItem ?: entries.getOrNull(expandedIndex) ?: run {
                    BambooLog.tips.w("tips", "commitAll: no item to commit")
                    return
                }
            val payload = item.detail
            if (payload.isEmpty()) return
            val txn = ++GlossaryTipsState.txn
            GlossaryTipsState.undoState = UNDO_COMMITTED
            setUndo(payload.length)
            BambooLog.tips.d("tips", idTag + " commitAll commit txn=" + txn + " len=" + payload.length)
            commitPayload(payload)
        }

        /**
         * 撤回刚上屏的整段内容。
         *
         * 顺序必须是「先标记待撤回 → 再删 → 按结果恢复按钮」。删除是异步的，
         * 若先清零再删，删除失败时按钮已经变回「全部上屏」，用户看到的就是「点了没反应、文字还在」。
         */
        private fun undoCommit(service: TrimeInputMethodService) {
            val n = undoLength
            val txn = ++GlossaryTipsState.txn
            GlossaryTipsState.undoState = UNDO_PENDING
            commitAllButton.isEnabled = false
            BambooLog.tips.d("tips", idTag + " undo start txn=" + txn + " len=" + n)
            service.postKeyJob {
                val ic = service.currentInputConnection
                val ok =
                    ic != null &&
                        runCatching { ic.deleteSurroundingText(n, 0) }.getOrDefault(false)
                commitAllButton.post {
                    if (GlossaryTipsState.txn != txn) {
                        // 期间又提交/收起/换了编辑框：这次回调作废，不要把新状态改回去。
                        BambooLog.tips.d("tips", idTag + " undo stale txn=" + txn)
                        return@post
                    }
                    commitAllButton.isEnabled = true
                    if (ok) {
                        GlossaryTipsState.undoState = UNDO_NONE
                        setUndo(0)
                        BambooLog.tips.d("tips", idTag + " undo ok txn=" + txn + " len=" + n)
                    } else {
                        // 删除没成功：保留「撤回上屏」，用户可以再点一次，同时留下证据。
                        GlossaryTipsState.undoState = UNDO_COMMITTED
                        commitAllButton.text = COMMIT_ALL_UNDO_LABEL
                        BambooLog.tips.w(
                            "tips",
                            idTag + " undo FAILED txn=" + txn + " len=" + n +
                                " ic=" + (ic != null),
                        )
                        toast("撤回失败，可再点一次")
                    }
                }
            }
        }

        /** 按钮文案、可点状态、撤回长度三者永远同源。 */
        private fun setUndo(length: Int) {
            undoLength = length
            commitAllButton.text = if (length > 0) COMMIT_ALL_UNDO_LABEL else COMMIT_ALL_LABEL
            commitAllButton.isEnabled = true
            BambooLog.tips.d("tips", idTag + " setUndo len=" + length)
        }

        private fun toast(msg: String) {
            runCatching {
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        /** 复位「全部上屏」按钮；已经上屏的内容不动。 */
        private fun resetCommitAll() {
            if (undoLength == 0) return
            setUndo(0)
        }

        @Suppress("unused")

        /** 屏幕绝对 y → 条目下标（-1 = 不在条目上）。 */
        private fun hitIndexAt(rawY: Float): Int {
            val loc = IntArray(2)
            var index = 0
            for (n in 0 until column.childCount) {
                val child = column.getChildAt(n)
                if (child.tag == null) continue
                child.getLocationOnScreen(loc)
                val top = loc[1].toFloat()
                if (rawY >= top && rawY < top + child.height) return index
                index++
            }
            return -1
        }

        private fun activate(idx: Int) {
        BambooLog.tips.d("tips", "activate idx=" + idx + " expandedBefore=" + expandedIndex)
            if (expandedIndex >= 0) {
                // 展开态的词块由 dispatchTouchEvent 处理，这里不再整条上屏。
                return
            }
            val item = entries.getOrNull(idx) ?: return
            BambooLog.tips.d(
                "tips",
                "activate idx=" + idx + " expandable=" + entryExpandable[idx] + " text=" + item.text.take(12),
            )
            if (entryExpandable[idx] == true) {
                expand(idx)
            } else {
                commitPayload(item.payload)
            }
        }
        /**
         * 高度只在测量阶段决定：先把内容按不限高量一遍，拿到每条的真实高度，
         * 再按「显示几条」或「展开行数」定自己的高度。用 measuredHeight，不用 height。
         */
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val specW = MeasureSpec.getSize(widthMeasureSpec)
            val screenW = runCatching { resources.displayMetrics.widthPixels }.getOrDefault(specW)
            // 宽度夹到屏幕宽：父布局给的宽度比屏幕还宽时，块会整段右移出屏，
            // 内部留白与行尾裁剪都落在屏幕外，调多少都看不出来。
            val w = if (specW > screenW) screenW else specW
            measuredW = w
            if (w != lastWidth) {
                lastWidth = w
                val pw = runCatching { (parent as? View)?.width ?: -1 }.getOrDefault(-1)
                BambooLog.tips.d(
                    "tips",
                    "width gl spec=" + specW + " screen=" + screenW + " parent=" + pw +
                        " self=" + w + " " + idTag,
                )
            }
            val wSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
            // 1) 先按「高度不限」量一遍：正文到底多高
            column.measure(wSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val contentH = column.measuredHeight.coerceAtLeast(1)
            // 2) 把内容高度写成固定值。ScrollView 再量这个子 View 时拿到的是
            //    EXACTLY(内容高)，不会被它自己的 AT_MOST(视口高) 夹成一屏 ——
            //    这正是「看着填满、却滑不动」的根因。
            val clp = column.layoutParams
            if (clp != null && clp.height != contentH) {
                clp.height = contentH
                column.layoutParams = clp
            }
            val padV = scroller.paddingTop + scroller.paddingBottom
            // 3) 容器高度：展开态 = 屏高比例；列表态 = min(内容高, 前 N 条)
            val h =
                if (expandedIndex >= 0) {
                    expandHeightPx()
                } else {
                    var sum = 0
                    var count = 0
                    var i = 0
                    val limit = rows().coerceIn(1, MAX_ROWS)
                    while (i < column.childCount && count < limit) {
                        val ch = column.getChildAt(i)
                        val m = ch.layoutParams as? ViewGroup.MarginLayoutParams
                        sum += ch.measuredHeight + (m?.topMargin ?: 0) + (m?.bottomMargin ?: 0)
                        if (ch.tag is Int) count++
                        i++
                    }
                    val want = if (count == 0) contentH + padV else sum + padV
                    if (contentH + padV <= want) contentH + padV else want
                }.coerceAtLeast(1)
            val headerH = if (expandedIndex >= 0) ctx.dp(COLLAPSE_H_DP + HEADER_EXTRA_DP) else 0
            headerRow.measure(
                wSpec,
                MeasureSpec.makeMeasureSpec(headerH.coerceAtLeast(1), MeasureSpec.EXACTLY),
            )
            scroller.measure(
                wSpec,
                MeasureSpec.makeMeasureSpec((h - headerH).coerceAtLeast(1), MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(w, h)
            if (h != lastMeasured) {
                lastMeasured = h
                BambooLog.tips.d(
                    "tips",
                    idTag + " measure gl content=" + contentH + " final=" + h + " expanded=" + expandedIndex,
                )
            }
        }

        private fun expand(index: Int) {
            BambooLog.tips.d("tips", idTag + " expand idx=" + index)
            val item = entries.getOrNull(index) ?: return
            if (entryExpandable[index] != true) {
                commitPayload(item.payload)
                return
            }
            scrollBeforeExpand = scroller.scrollY
            expandedIndex = index
            pinnedItem = item
            render()
            applyHeight()
            BambooLog.tips.d(
                "tips",
                "expand gl idx=" + index + " h=" + expandHeightPx() +
                    " txn=" + GlossaryTipsState.txn + " state=" + GlossaryTipsState.undoState +
                    " undo=" + undoLength,
            )
            post { scroller.scrollTo(0, 0) }
        }

        /**
         * 展开态高度：屏高比例（文曲星 28%、外语学习 14%），但**不能超过窗口里真正剩下的空间**。
         *
         * 单编码时候选行少、提示块位置更低，按 14% 屏高撑开会顶出输入法窗口，
         * 被裁掉的那一截既滑不到（看着不全）也点不到右上角的「收起」——就是「单编码展开异常」。
         */
        private fun expandHeightPx(): Int {
            val ratio = if (wenquxing) EXPAND_RATIO_WQ else EXPAND_RATIO_GL
            val wanted = (resources.displayMetrics.heightPixels * ratio).toInt()
            return wanted.coerceAtMost(availableAbove()).coerceAtLeast(ctx.dp(COLLAPSE_H_DP + 12))
        }

        /** 本块顶部往上顶到窗口底部还剩多少像素可用（窗口内、不含被键盘盖住的部分）。 */
        private fun availableAbove(): Int {
            val fallback = (resources.displayMetrics.heightPixels * EXPAND_RATIO_GL).toInt()
            val root = rootView ?: return fallback
            if (root.height <= 0) return fallback
            val me = IntArray(2)
            getLocationOnScreen(me)
            val rt = IntArray(2)
            root.getLocationOnScreen(rt)
            val bottom = rt[1] + root.height
            return (bottom - me[1] - ctx.dp(4)).coerceAtLeast(1)
        }

        /**
         * 高度完全由 onMeasure 决定（展开态固定屏高比例、列表态前 N 条）。
         * 这里只负责「立刻重排」，不等父容器下一趟。
         */
        private fun applyHeight() {
            forceLayout()
            requestLayout()
            (parent as? View)?.requestLayout()
            if (width > 0) {
                measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                layout(left, top, right, top + measuredHeight)
            }
            invalidate()
        }

        private fun collapse() {
        BambooLog.tips.d("tips", "collapse")
            // 收起 = 显式结束撤回事务：之后不再允许撤回，并作废仍在飞行中的删除回调。
            GlossaryTipsState.txn++
            GlossaryTipsState.undoState = UNDO_NONE
            resetCommitAll()
            expandedIndex = -1
            pinnedItem = null
            collapseButton.visibility = View.GONE
            render()
            // 收起后把高度交还给内容（WRAP_CONTENT）并强制父容器重排
            applyHeight()
            BambooLog.tips.d(
                "tips",
                "collapse gl txn=" + GlossaryTipsState.txn + " state=" + GlossaryTipsState.undoState,
            )
            post { scroller.scrollTo(0, scrollBeforeExpand) }
        }

        /**
         * 上屏：只提交文本，**不清组合**。
         *
         * 清组合（`clearComposition()`）会推回一个空组合，带来两个连锁反应：
         * 1. 编码行立刻消失，退格从「删编码」变成「删正文」；
         * 2. 实体键盘时候选窗按「组合为空」判成不该显示而收起，提示块跟着整条消失。
         * 保留组合后，引擎的组句还在，编码行、退格、候选窗都维持原样；
         * `InputConnection.commitText()` 会顺带把编辑器里的组合区换成上屏文本，不留残留。
         */
        private fun commitPayload(payload: String) {
            if (payload.isEmpty()) return
            val service = TrimeInputMethodService.current ?: return
            service.postKeyJob {
                runCatching {
                    BambooLog.tips.d(
                        "tips",
                        "commit gl keepComposition len=" + payload.length,
                    )
                    service.commitText(payload)
                }
            }
        }
    }

    companion object {
        /**
         * 输入会话号：`onStartInput` 每次进入新的编辑框都会 +1。
         *
         * 提示块据此判断「换了编辑区」，把展开态退掉——不能靠 `active`，
         * 它只表示提示该挂在键盘还是候选窗，与人换了哪个编辑框无关。
         */
        @Volatile
        var sessionToken: Int = 0

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
        /** 外语学习行尾展开三角：比行首 ▶ 再小一档，避免视觉上压过正文。 */
        private const val EXPAND_ARROW_SCALE = 0.80f
        /** 行尾三角所在的固定列宽（字宽倍数）。 */
        private const val ARROW_BOX_CHARS = 1.15f

        /**
         * 行尾额外留白（单位：字宽）。
         *
         * 三角在它自己的列里居中，所以列窄一点 = 左间距小一点；
         * 再在行尾多留一点空白 = 右间距大一点。两者合起来就是
         * 三角整体往右挪一小步。
         */
        private const val ARROW_END_EXTRA_CHARS = 0.35f
        private const val COUNT_SCALE = 0.78f
        /** 展开小图标的相对字号（比正文大一点，才看得清是实心三角）。 */
        private const val BADGE_SCALE = 0.88f
        /** 展开态高度占屏高的比例：文曲星 28%、外语学习 17%。 */
        private const val EXPAND_RATIO_WQ = 0.28f
        private const val EXPAND_RATIO_GL = 0.17f
        private const val MAX_ROWS = 6
        private const val MAX_EXPAND_ROWS = 8
        private const val BUTTON_BG_ALPHA = 40
        /** header 行右侧留白（「收起」按钮与右边界之间）。 */
        /** 展开正文左右内边距；与列表态的 sidePad 无关。 */
        private const val EXPAND_PAD_H_DP = 8

        private const val HEADER_PAD_END_DP = 18
        /** header 行高度 = 按钮高 + 这个值（决定首行正文离按钮行多远）。 */
        private const val HEADER_EXTRA_DP = 12

        /** 三角和提示块底色的最低对比度，低于这个就退回文字色，避免箭头看不见。 */
        private const val MIN_MARK_CONTRAST = 2.0

        private const val MARK = "\u25B6\u3000"
        private const val INDENT = "\u3000\u3000"
        private const val MAX_ENTRY_LINES = 3

        /** 排版测宽时只取前这么多字符。 */
        private const val MEASURE_CHAR_CAP = 600
        private const val EXPAND_HINT = "\u25B8"
        /** 文曲星：沿用它原本的 [...展开]，只保证贴在行尾、不单独换行。 */
        private const val EXPAND_HINT_TEXT = "[...\u5C55\u5F00]"
        /** 外语学习：行尾实心小三角，字号与行首 ▶ 一致。 */
        private const val EXPAND_HINT_ARROW = "\u25BC"
        private const val COLLAPSE_LABEL = "\u6536\u8D77"
        private const val COLLAPSE_TEXT_SP = 12f
        private const val DIVIDER_ALPHA = 36
        private const val GAP_SLACK_DP = 10
        private var buildLogged = false
        private const val TAP_SLOP_DP = 12
        private const val COMMIT_ALL_LABEL = "全部上屏"
    private const val COMMIT_ALL_UNDO_LABEL = "撤回上屏"
    private const val COMMIT_ALL_W_DP = 74
    private const val COLLAPSE_W_DP = 60
        private const val COLLAPSE_H_DP = 26
    }
}
