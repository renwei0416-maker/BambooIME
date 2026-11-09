/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewAnimator
import com.osfans.trime.R
import com.osfans.trime.data.db.ClipboardCategory
import com.osfans.trime.data.db.ClipboardTab
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.gravityCenter
import splitties.views.setPaddingDp

class ClipboardUi(override val ctx: Context, private val theme: Theme) : Ui {

    val recyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
    }

    val enableUi = ClipboardInstructionUi.Enable(ctx)

    val emptyUi = ClipboardInstructionUi.Empty(ctx)

    val viewAnimator = view(::ViewAnimator) {
        add(recyclerView, lParams(matchParent, matchParent))
        add(emptyUi.root, lParams(matchParent, matchParent))
        add(enableUi.root, lParams(matchParent, matchParent))
    }

    private val tabButtons = linkedMapOf<ClipboardTab, TextView>()

    private val tabRow = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        setPaddingDp(4, 8, 4, 4)
    }

    private val tabScroll = android.widget.HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(tabRow)
    }

    override val root = verticalLayout {
        ClipboardTab.entries.forEach { tab ->
            val button = createTabButton(tab.titleRes)
            tabButtons[tab] = button
            tabRow.addView(
                button,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(30),
                ).apply { marginEnd = dp(4) },
            )
        }
        add(tabScroll, lParams(matchParent, wrapContent))
        add(viewAnimator, LinearLayout.LayoutParams(matchParent, 0, 1f))
    }

    private fun createTabButton(textRes: Int) = textView {
        gravity = gravityCenter
        minWidth = dp(64)
        text = ctx.getString(textRes)
        textSize = theme.generalStyle.fonts.clipboard_category_size
        setPaddingDp(12, 0, 12, 0)
        background = categoryBackground(selected = false)
        setTextColor(ColorManager.getColor("key_text_color"))
        typeface = FontManager.getTypeface()
    }

    private fun categoryBackground(selected: Boolean) = ColorManager.getDecorDrawable(
        if (selected) "clipboard_category_selected_back_color" else "clipboard_category_back_color",
        cornerRadius = ctx.dp(15).toFloat(),
    )

    fun setOnTabSelectedListener(listener: (ClipboardTab) -> Unit) {
        tabButtons.forEach { (tab, button) ->
            button.setOnClickListener {
                setSelectedTab(tab)
                listener(tab)
            }
        }
    }

    fun setSelectedTab(tab: ClipboardTab) {
        tabButtons.forEach { (buttonTab, button) ->
            val selected = buttonTab == tab
            button.background = categoryBackground(selected)
            button.setTextColor(
                if (selected) {
                    ColorManager.getColor("clipboard_category_selected_text_color")
                } else {
                    ColorManager.getColor("key_text_color")
                },
            )
        }
        tabButtons[tab]?.let { button ->
            tabScroll.post {
                val target = (button.left - ctx.dp(16)).coerceAtLeast(0)
                if (target != tabScroll.scrollX) tabScroll.smoothScrollTo(target, 0)
            }
        }
    }

    fun switchUiByState(state: ClipboardStateMachine.State) {
        when (state) {
            ClipboardStateMachine.State.Normal -> {
                viewAnimator.displayedChild = 0
            }

            ClipboardStateMachine.State.AddMore -> {
                viewAnimator.displayedChild = 1
            }

            ClipboardStateMachine.State.EnableListening -> {
                viewAnimator.displayedChild = 2
            }
        }
    }
}
