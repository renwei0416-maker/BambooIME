/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.buildSpannedString
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.actions.wenquxing.WenquxingTips
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.view

open class PreeditUi(
    final override val ctx: Context,
    private val theme: Theme,
    private val setupPreeditView: (TextView.() -> Unit)? = null,
    private val onMoveCursor: ((Int) -> Unit)? = null,
) : Ui {
    private val textColor = ColorManager.getColor("text_color")
    private val highlightTextColor = ColorManager.getColor("hilited_text_color")

    /** 文曲星提示用的颜色：主题里的注释色，取不到就退回高亮色。 */
    private val tipColor =
        runCatching { ColorManager.getColorOr("comment_text_color", highlightTextColor) }
            .getOrDefault(highlightTextColor)

    val preedit =
        view(::PreeditTextView) {
            setTextColor(textColor)
            textSize = theme.preedit.foreground.fontSize
            typeface = FontManager.getTypeface()
            setupPreeditView?.invoke(this)
            onMoveCursor = this@PreeditUi.onMoveCursor
        }

    /** 编码字号缩放，只有接外接键盘时由设置调整。 */
    var textScale: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SCALE, MAX_SCALE)
            preedit.textSize = theme.preedit.foreground.fontSize * field
        }

    override val root =
        object : LinearLayout(ctx) {
            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false

            init {
                orientation = HORIZONTAL
                add(preedit, lParams())
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

    private fun updateTextView(
        str: CharSequence,
        visible: Boolean,
    ) = preedit.run {
        text = str
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 没有编码行可挂时，直接把提示单独画成这一行。 */
    fun showTipLine(text: CharSequence) {
        if (text.isEmpty()) {
            visible = false
            updateTextView("", false)
            return
        }
        visible = true
        updateTextView(text, true)
    }

    fun update(composition: CompositionProto) {
        com.osfans.trime.actions.handwriting.HandwritingLog.d(
            "wenquxing",
            "preedit update enter len=" + composition.length +
                " preeditLen=" + (composition.preedit?.length ?: -1) +
                " tipLen=" + com.osfans.trime.actions.wenquxing.WenquxingTips.suffix().length,
        )
        val string = composition.toSpannedString()
        val cursorPos = composition.cursorPos
        val hasPreedit = composition.length > 0
        visible = hasPreedit
        if (!visible) {
            updateTextView("", false)
            return
        }
        val stringWithCursor =
            if (cursorPos == 0 || cursorPos == string.length) {
                string
            } else {
                buildSpannedString {
                    if (cursorPos > 0) append(string, 0, cursorPos)
                    append(string, cursorPos, string.length)
                }
            }
        WenquxingTips.onComposition(composition.preedit, composition.commitTextPreview)
        val tip = WenquxingTips.suffix()
        com.osfans.trime.actions.handwriting.HandwritingLog.d(
            "wenquxing",
            "preedit render tipLen=" + tip.length,
        )
        // 没有编码行时提示本身被合成进 preedit，这里不能再拼一遍
        val alreadyThere = tip.isNotEmpty() &&
            stringWithCursor.toString().endsWith(tip.toString())
        val withTip =
            if (tip.isEmpty() || alreadyThere) {
                stringWithCursor
            } else {
                buildSpannedString {
                    append(stringWithCursor)
                    val start = length
                    append(tip)
                    setSpan(ForegroundColorSpan(tipColor), start, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
            }
        com.osfans.trime.actions.handwriting.HandwritingLog.d(
            "wenquxing",
            "preedit render already=" + alreadyThere + " final=" + withTip,
        )
        updateTextView(withTip, true)
    }

    private companion object {
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 2f
    }
}
