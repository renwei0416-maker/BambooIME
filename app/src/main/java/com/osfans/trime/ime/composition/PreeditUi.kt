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
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.buildSpannedString
import com.osfans.trime.actions.glossary.GlossaryPrefs
import com.osfans.trime.actions.glossary.GlossaryTips
import com.osfans.trime.actions.glossary.TipLine
import com.osfans.trime.actions.tips.TipBlocks
import com.osfans.trime.actions.wenquxing.WenquxingPrefs
import com.osfans.trime.actions.wenquxing.WenquxingTips
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
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
 * 样式照抄 preedit（同一背景色、同一内边距、同一个上下拼起来的圆角）但完全独立，
 * 同一时间只显示其中一块，由各自的设置决定让位规则。
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
            visibility = View.GONE
        }

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
                add(preedit, lParams(matchParent, wrapContent))
                add(tips, lParams(matchParent, wrapContent))
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
        if (picked == null || picked.lines.isEmpty()) {
            tips.visibility = View.GONE
        } else {
            applyTipsStyle(picked.wenquxing)
            tips.text = buildBlock(picked.lines)
            tips.visibility = View.VISIBLE
        }
        visible = preedit.visibility == View.VISIBLE || tips.visibility == View.VISIBLE
    }

    /**
     * 提示块的背景与字号：背景色跟编码行同一个键，圆角挪到右下角（和编码行拼成一张卡片），
     * 不透明度与字号各用所属模块自己的设置。
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

    private fun buildBlock(lines: List<TipLine>): CharSequence {
        // 三角跟在高亮那一条前面；文曲星块没有高亮，就落在第一条
        val markAt = lines.indexOfFirst { it.highlighted }.takeIf { it >= 0 } ?: 0
        val unmatchedAlpha = runCatching { GlossaryPrefs.unmatchedAlpha }.getOrDefault(80)
        val unmatchedScale = runCatching { GlossaryPrefs.unmatchedTextScale }.getOrDefault(100)
        return buildSpannedString {
            lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                val markStart = length
                append(if (index == markAt) MARK else INDENT)
                if (index == markAt) {
                    // 只放大三角字形本身，后面的全角空格保持原宽度，避免各行文字错位
                    setSpan(
                        ForegroundColorSpan(markColor),
                        markStart,
                        markStart + 1,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        markStart,
                        markStart + 1,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        RelativeSizeSpan(MARK_SCALE),
                        markStart,
                        markStart + 1,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                    )
                }
                val cnStart = length
                append(line.text)
                val cnEnd = length
                if (line.highlighted) {
                    setSpan(StyleSpan(Typeface.BOLD), cnStart, cnEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                val en = line.english
                if (line.unmatched) {
                    // 词库里没有这个词：中文斜体 + 按设置调淡（只有「外语学习」会置这个标记）
                    setSpan(
                        StyleSpan(Typeface.ITALIC),
                        cnStart,
                        cnEnd,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                    )
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
            }
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
        val lines: List<TipLine>,
        val wenquxing: Boolean,
    )

    /**
     * 两块提示互斥：先看文曲星这一句有没有内容，再按「外语学习 → 和文曲星同时开启时」的设置让位。
     */
    private fun pick(): Picked? {
        val wenquxingEnabled = runCatching { WenquxingPrefs.enabled }.getOrDefault(false)
        val wenquxing =
            if (wenquxingEnabled) {
                runCatching { WenquxingTips.blockLines() }.getOrNull()
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
            val lines = wenquxing.orEmpty()
            return if (lines.isEmpty()) null else Picked(withTotal(lines), true)
        }
        if (!glossaryOn) return null
        val lines = runCatching { GlossaryTips.lines(GlossaryPrefs.tipsMax) }.getOrDefault(emptyList())
        return if (lines.isEmpty()) null else Picked(lines, false)
    }

    /** 「共 N 组」跟在最后一条后面，不单独占一行。 */
    private fun withTotal(lines: List<String>): List<TipLine> {
        val total = runCatching { WenquxingTips.hitCount() }.getOrDefault(0)
        return lines.mapIndexed { index, text ->
            val suffix = if (total > 1 && index == lines.lastIndex) "共${total}组" else ""
            TipLine(text + suffix, null, false)
        }
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
        /** 三角和提示块底色的最低对比度，低于这个就退回文字色，避免箭头看不见。 */
        private const val MIN_MARK_CONTRAST = 2.0
        private const val MARK = "\u25B6\u3000"
        private const val INDENT = "\u3000\u3000"
    }
}
