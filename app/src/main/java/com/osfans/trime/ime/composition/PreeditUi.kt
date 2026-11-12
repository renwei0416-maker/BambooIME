/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
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

/**
 * 编码区。
 *
 * 上面那一行 `preedit` 保持上游原样：只画 librime 给的编码 + 方案给的 prompt，
 * 单行、超长省略、点击定位；我们**不往里面拼自己的东西**。
 *
 * 下面那一块 `tips` 是我们自己的提示区（文曲星 / 外语学习），
 * 样式照抄 preedit（同一背景色、同一内边距、同一个上下拼起来的圆角）但完全独立：
 * 同一时间只显示其中一块，由各自的设置决定让位规则。
 *
 * 这一块现在是可交互的：
 * - 高度 = 各自设置里「显示几条」那么多行，内容不足就自动变矮，超出则内部滚动；
 * - 点某一条 = 上屏它（与 `BambooTip` 系列 token 同一条路）；
 * - 点每条末尾的 `▾` = 在这块地里展开全文（高度不变、内部滚动），`▴` 收起；
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

    /** 提示块。与 preedit 同一套样式，但逻辑独立，不参与上游编码行的排版。 */
    private val tips =
        view(::TextView) {
            setTextColor(textColor)
            textSize = theme.preedit.foreground.fontSize
            typeface = FontManager.getTypeface()
            setLineSpacing(0f, 1.18f)
            horizontalPadding = dp(theme.preedit.horizontalPadding)
            clipToOutline = true
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
            visibility = View.GONE
            setOnTouchListener(
                object : View.OnTouchListener {
                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        gestureDetector.onTouchEvent(event)
                        return false
                    }
                },
            )
        }

    // ---- 提示块的内容与命中测试所需的元数据 ----

    /** 当前块里的每一条（顺序 = 屏幕上的显示顺序）。 */
    private var blockEntries: List<TipEntry> = emptyList()

    /** 每条在 Spanned 里的起始字符偏移，点击时用来把「点到的字」换算成「第几条」。 */
    private var entryStart: IntArray = IntArray(0)

    /** 每条「可点击区域」的结束偏移（正文 + 行尾字数，不含图标）。 */
    private var entryBlockEnd: IntArray = IntArray(0)

    /** 每条展开图标在 Spanned 里的字符范围（-1 表示这条没有图标）。 */
    private var iconStart: IntArray = IntArray(0)
    private var iconEnd: IntArray = IntArray(0)

    /** 正在展开的那一条的下标；-1 表示列表视图。 */
    private var expandedIndex: Int = -1

    /** 上一次渲染的内容指纹，相同就不重画（否则每次按键都会把滚动位置打回顶部）。 */
    private var lastKey: String = ""

    /** 用户手动滚动的时间戳，用来判断要不要把视口拽回高亮项。 */
    private var manualScrollAt: Long = 0L

    /** 展开前的滚动位置，收起时还原。 */
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
                    // 用户自己滑了：短时间内别把人家拽回高亮行
                    manualScrollAt = SystemClock.uptimeMillis()
                    return false
                }
            },
        )

    /** 编码字号缩放，只有接外接键盘时由设置调整；提示块跟着一起缩。 */
    var textScale: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SCALE, MAX_SCALE)
            preedit.textSize = theme.preedit.foreground.fontSize * field
            refreshTips()
        }

    override val root =
        object : LinearLayout(ctx) {
            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false

            init {
                orientation = VERTICAL
                add(preedit, lParams(wrapContent, wrapContent))
                add(tips, lParams(matchParent, wrapContent))
                // 上游把整块挂成 wrapContent 宽，提示块得自己钉到可用宽度，否则展开时只有内容那么宽
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

    /** 编码行的透明度只作用于它自己，不再连累提示块。 */
    fun applyPreeditAlpha(alpha: Float) {
        preedit.alpha = alpha.coerceIn(0f, 1f)
    }


    /** 这一份该不该由自己画提示块：候选窗显示着就让候选窗画，否则键盘画。 */
    private fun tipsShownHere(): Boolean =
        runCatching {
            if (tipsOwner == OWNER_CANDIDATES) TipBlocks.candidatesWindowShown else !TipBlocks.candidatesWindowShown
        }.getOrDefault(tipsOwner != OWNER_CANDIDATES)

    /** 外部改了提示内容（翻页、开关变化）后重算这一块。 */
    fun refreshTips() {
        val picked = if (tipsShownHere()) runCatching { pick() }.getOrNull() else null
        if (picked == null || picked.entries.isEmpty()) {
            blockEntries = emptyList()
            entryStart = IntArray(0)
            entryBlockEnd = IntArray(0)
            iconStart = IntArray(0)
            iconEnd = IntArray(0)
            expandedIndex = -1
            lastKey = ""
            tips.visibility = View.GONE
            visible = preedit.visibility == View.VISIBLE
            return
        }
        applyTipsStyle(picked.wenquxing)
        if (expandedIndex !in picked.entries.indices) expandedIndex = -1
        val showChars = showCharCount(picked.wenquxing)
        val key =
            picked.entries.joinToString("") { "${it.text}${it.chars}${it.detail.length}" } +
                "#$expandedIndex#$showChars#${textScale}#${picked.wenquxing}"
        if (key == lastKey && tips.visibility == View.VISIBLE) {
            visible = true
            return
        }
        lastKey = key
        blockEntries = picked.entries
        tips.text =
            if (expandedIndex >= 0) {
                buildExpanded(picked.entries, expandedIndex, picked.wenquxing)
            } else {
                buildBlock(picked.entries, picked.wenquxing)
            }
        tips.visibility = View.VISIBLE
        visible = true
        tips.maxLines =
            if (expandedIndex >= 0) {
                Int.MAX_VALUE
            } else {
                (if (picked.wenquxing) WenquxingPrefs.maxDisplay else GlossaryPrefs.tipsMax).coerceIn(1, 6)
            }
        tips.post {
            HandwritingLog.d(
                "tips",
                "block w=${tips.width} rootW=${root.width} h=${tips.height} entries=${blockEntries.size} " +
                    "expanded=$expandedIndex maxLines=${tips.maxLines} scroll=${tips.scrollY}",
            )
        }
        if (expandedIndex >= 0) {
            tips.scrollTo(0, 0)
        } else {
            scrollToHighlight()
        }
    }

    /**
     * 提示块的背景与字号：背景色跟编码行同一个键，圆角挪到右下角（和编码行拼成一张卡片），
     * 不透明度与字号各用所属模块自己的设置；行数上限也就是各自设置里的「显示几条」。
     */
    private fun applyTipsStyle(wenquxing: Boolean) {
        val scale =
            if (wenquxing) {
                WenquxingPrefs.tipsTextScale
            } else {
                GlossaryPrefs.tipsTextScale
            }
        val alpha =
            if (wenquxing) {
                WenquxingPrefs.tipsAlpha
            } else {
                GlossaryPrefs.tipsAlpha
            }
        val rows =
            if (wenquxing) {
                WenquxingPrefs.maxDisplay
            } else {
                GlossaryPrefs.tipsMax
            }
        tips.maxLines = rows.coerceIn(1, 6)
        tips.textSize = theme.preedit.foreground.fontSize * textScale * (scale / 100f)

        val base = ColorManager.getColor("text_back_color")
        val baseAlpha = (base ushr 24) and 0xFF
        val blended = baseAlpha * alpha.coerceIn(0, 100) / 100
        val fill = (base and 0x00FFFFFF) or (blended shl 24)
        val radius = ctx.dp(theme.preedit.topEndRadius)
        tips.background =
            GradientDrawable().apply {
                setColor(fill)
                shape = GradientDrawable.RECTANGLE
                // 上左、上右、下右、下左：只有右下角圆
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, 0f, 0f)
            }
        tips.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun showCharCount(wenquxing: Boolean): Boolean =
        runCatching {
            if (wenquxing) WenquxingPrefs.showCharCount else GlossaryPrefs.showCharCount
        }.getOrDefault(true)

    /** 列表视图：一条一行，行尾「·N字」和展开图标。 */
    private fun buildBlock(
        entries: List<TipEntry>,
        wenquxing: Boolean,
    ): CharSequence {
        // 三角跟在高亮那一条前面；文曲星块没有高亮，就落在第一条
        val markAt = entries.indexOfFirst { it.highlighted }.takeIf { it >= 0 } ?: 0
        val unmatchedAlpha = runCatching { GlossaryPrefs.unmatchedAlpha }.getOrDefault(80)
        val unmatchedScale = runCatching { GlossaryPrefs.unmatchedTextScale }.getOrDefault(100)
        val showChars = showCharCount(wenquxing)
        val n = entries.size
        entryStart = IntArray(n)
        entryBlockEnd = IntArray(n)
        iconStart = IntArray(n) { -1 }
        iconEnd = IntArray(n) { -1 }
        return buildSpannedString {
            entries.forEachIndexed { index, line ->
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
                    setSpan(RelativeSizeSpan(unmatchedScale / 100f), cnStart, cnEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
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
                if (showChars && line.chars > 0) {
                    append("  ")
                    val cntStart = length
                    append("·${line.chars}字")
                    setSpan(ForegroundColorSpan(tipColor), cntStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(COUNT_SCALE), cntStart, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                entryBlockEnd[index] = length
                // 展开图标：只有「展开后确实能看到更多内容」才给
                if (line.detail.length > line.text.length) {
                    append(" ")
                    iconStart[index] = length
                    append(if (index == expandedIndex) ICON_COLLAPSE else ICON_EXPAND)
                    iconEnd[index] = length
                    setSpan(
                        ForegroundColorSpan(tipColor),
                        iconStart[index],
                        iconEnd[index],
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        RelativeSizeSpan(ICON_SCALE),
                        iconStart[index],
                        iconEnd[index],
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        iconStart[index],
                        iconEnd[index],
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
    }

    /** 展开视图：占满这块地显示某一条的全文，第一行是收起图标。 */
    private fun buildExpanded(
        entries: List<TipEntry>,
        index: Int,
        wenquxing: Boolean,
    ): CharSequence {
        val line = entries[index]
        val unmatchedAlpha = runCatching { GlossaryPrefs.unmatchedAlpha }.getOrDefault(80)
        entryStart = intArrayOf(0)
        iconStart = intArrayOf(0)
        iconEnd = intArrayOf(1)
        return buildSpannedString {
            append(ICON_COLLAPSE)
            setSpan(ForegroundColorSpan(tipColor), 0, 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(ICON_SCALE), 0, 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            append(" 收起")
            iconEnd[0] = length
            setSpan(ForegroundColorSpan(withAlpha(textColor, unmatchedAlpha)), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            append('\n')
            append(line.detail)
        }
    }

    /** 点到了哪一条：先按字符偏移定位条目，再看是不是落在展开图标上。 */
    private fun handleTap(x: Int, y: Int) {
        val layout = tips.layout ?: return
        if (blockEntries.isEmpty()) return
        val vx = (x - tips.totalPaddingLeft + tips.scrollX).toFloat()
        val vy = y - tips.totalPaddingTop + tips.scrollY
        if (vy < 0) return
        val line = layout.getLineForVertical(vy)
        if (line < 0 || line >= layout.lineCount) return
        val offset = layout.getOffsetForHorizontal(line, vx.coerceAtLeast(0f))

        if (expandedIndex >= 0) {
            // 展开态第一行整行都是「收起」，点正文区域则上屏这一条
            if (line == 0) collapse() else commitPayload(blockEntries.getOrNull(expandedIndex)?.payload.orEmpty())
            return
        }

        val index = entryIndexOf(offset)
        if (index < 0) return
        // 图标热区：点到这一条图标所在的视觉行、且落在行尾 40dp 内，都算点图标
        if (iconStart[index] >= 0) {
            val iconLine = layout.getLineForOffset(iconStart[index])
            val nearRight = vx >= layout.getLineRight(iconLine) - ctx.dp(ICON_TOUCH_DP)
            if (line == iconLine && nearRight) {
                expand(index)
                return
            }
        }
        // 落在行首缩进、三角标记上不算点到这一条
        if (offset < entryStart[index] || offset >= entryBlockEnd[index]) return
        commitPayload(blockEntries[index].payload)
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
     * 展开某一条：**只在这一次动作里**把块锁到屏幕宽、并锁住展开前的高度。
     * 正常态一个字节都不碰宽度（宽度由内容决定），否则首次布局量到的小值会被钉死。
     */
    private fun expand(index: Int) {
        scrollBeforeExpand = tips.scrollY
        heightBeforeExpand = tips.height
        expandedIndex = index
        lastKey = ""
        val screen = ctx.resources.displayMetrics.widthPixels
        val params = tips.layoutParams as LinearLayout.LayoutParams
        params.width = LinearLayout.LayoutParams.MATCH_PARENT
        params.height = if (heightBeforeExpand > 0) heightBeforeExpand else LinearLayout.LayoutParams.WRAP_CONTENT
        tips.layoutParams = params
        refreshTips()
        HandwritingLog.d(
            "tips",
            "expand idx=$index screen=$screen tipsW=${tips.width} rootW=${root.width} " +
                "h=${tips.height} beforeH=$heightBeforeExpand maxLines=${tips.maxLines}",
        )
    }

    private fun collapse() {
        expandedIndex = -1
        lastKey = ""
        val params = tips.layoutParams as LinearLayout.LayoutParams
        params.width = LinearLayout.LayoutParams.MATCH_PARENT
        params.height = LinearLayout.LayoutParams.WRAP_CONTENT
        tips.layoutParams = params
        refreshTips()
        tips.post { tips.scrollTo(0, scrollBeforeExpand) }
        HandwritingLog.d(
            "tips",
            "collapse w=${tips.width} h=${tips.height} lpW=${tips.layoutParams.width} " +
                "lpH=${tips.layoutParams.height} maxLines=${tips.maxLines}",
        )
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

    /** 高亮项换了就把它滚到视口里；用户刚手动滑过就不动。 */
    private fun scrollToHighlight() {
        val index = blockEntries.indexOfFirst { it.highlighted }
        if (index < 0) return
        tips.post {
            if (SystemClock.uptimeMillis() - manualScrollAt < MANUAL_SCROLL_GRACE_MS) return@post
            val layout = tips.layout ?: return@post
            val offset = entryStart.getOrNull(index) ?: return@post
            val line = layout.getLineForOffset(offset)
            if (line < 0) return@post
            tips.scrollTo(0, layout.getLineTop(line).coerceAtLeast(0))
        }
    }

    /** 把颜色按 [percent]（0–100）改成半透明，用于「没命中」的行。 */
    private fun withAlpha(color: Int, percent: Int): Int {
        val a = ((color ushr 24) and 0xFF) * percent.coerceIn(0, 100) / 100
        return (color and 0x00FFFFFF) or (a shl 24)
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

    /** 一块提示 + 它来自哪个模块（决定用谁的显示设置）。 */
    private data class Picked(
        val entries: List<TipEntry>,
        val wenquxing: Boolean,
    )

    /**
     * 两块提示互斥：先看文曲星这一句有没有内容，再按「外语学习 → 和文曲星同时开启时」的设置让位。
     */
    private fun pick(): Picked? {
        val wenquxingEnabled = runCatching { WenquxingPrefs.enabled }.getOrDefault(false)
        val wenquxing =
            if (wenquxingEnabled) {
                runCatching { WenquxingTips.entries() }.getOrNull()
            } else {
                null
            }
        val glossaryOn = runCatching { GlossaryTips.shouldShow() }.getOrDefault(false)
        val conflict = runCatching { GlossaryPrefs.conflict }.getOrDefault(GlossaryTips.CONFLICT_AUTO)

        val takeWenquxing =
            when {
                !glossaryOn -> true
                conflict == GlossaryTips.CONFLICT_GLOSSARY -> false
                conflict == GlossaryTips.CONFLICT_OFF -> wenquxingEnabled
                else -> !wenquxing.isNullOrEmpty()
            }

        if (takeWenquxing) {
            val entries = wenquxing.orEmpty()
            return if (entries.isEmpty()) null else Picked(entries, true)
        }
        if (!glossaryOn) return null
        val entries = runCatching { GlossaryTips.entries() }.getOrDefault(emptyList())
        return if (entries.isEmpty()) null else Picked(entries, false)
    }

    companion object {
        /** 键盘上那一份。 */
        const val OWNER_KEYBOARD = 0

        /** 悬浮候选窗那一份。 */
        const val OWNER_CANDIDATES = 1

        private const val MIN_SCALE = 0.4f
        private const val MAX_SCALE = 2f
        private const val EN_SCALE = 0.82f
        private const val MARK_SCALE = 1.1f
        private const val COUNT_SCALE = 0.78f
        private const val ICON_SCALE = 1.3f

        /** 三角和提示块底色的最低对比度，低于这个就退回文字色，避免箭头看不见。 */
        private const val MIN_MARK_CONTRAST = 2.0

        /** 手动滑动之后多久之内不再自动把视口拉回高亮项。 */
        private const val MANUAL_SCROLL_GRACE_MS = 2500L

        /** 展开图标的热区宽度（dp）：从行尾往左这么多都算点到图标，不用精确点那个小三角。 */
        private const val ICON_TOUCH_DP = 40

        private const val MARK = "\u25B6\u3000"
        private const val INDENT = "\u3000\u3000"
        private const val ICON_EXPAND = "\u25BE"
        private const val ICON_COLLAPSE = "\u25B4"
    }
}
