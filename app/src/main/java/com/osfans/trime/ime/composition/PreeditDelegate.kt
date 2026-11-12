/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TouchEventReceiverWindow
import com.osfans.trime.ime.dependency.InputDependencyManager
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.horizontalPadding

class PreeditDelegate : InputBroadcastReceiver {

    private val context: Context by InputDependencyManager.getInstance().di.instance()
    private val theme: Theme by InputDependencyManager.getInstance().di.instance()
    private val rime: RimeSession by InputDependencyManager.getInstance().di.instance()

    val ui =
        PreeditUi(
            context,
            theme,
            setupPreeditView = {
                val radiusSize = dp(theme.preedit.topEndRadius)
                val radii = if (layoutDirection == View.LAYOUT_DIRECTION_LTR) {
                    floatArrayOf(0f, 0f, radiusSize, radiusSize, 0f, 0f, 0f, 0f)
                } else {
                    floatArrayOf(radiusSize, radiusSize, 0f, 0f, 0f, 0f, 0f, 0f)
                }
                background = GradientDrawable().apply {
                    setColor(ColorManager.getColor("text_back_color"))
                    shape = GradientDrawable.RECTANGLE
                    cornerRadii = radii
                }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                horizontalPadding = dp(theme.preedit.horizontalPadding)
            },
            onMoveCursor = { pos -> rime.launchOnReady { it.moveCursorPos(pos) } },
        ).apply {
            // 透明度只压在编码行那个 TextView 上：root 现在是「编码行 + 提示块」的纵向容器，
            // 压在 root 上会连带把提示块一起变半透明 —— 主题的 preedit.alpha 是乘性的，
            // 用户把提示块不透明度拉到 100% 也永远到不了全不透明。
            applyPreeditAlpha(theme.preedit.alpha)
            root.visibility = View.INVISIBLE
        }

    private val touchEventReceiverWindow = TouchEventReceiverWindow(ui.root)

    private var lastComposition = CompositionProto()

    /** 外部改了提示内容后，让编码区重算一次（提示块自己从模块里取内容）。 */
    fun refresh() {
        onCompositionUpdate(lastComposition)
    }
    override fun onCompositionUpdate(data: CompositionProto) {
        lastComposition = data
        ui.update(data)
        ui.root.visibility = if (ui.visible) View.VISIBLE else View.INVISIBLE
        if (data.length > 0) {
            touchEventReceiverWindow.show()
        } else {
            touchEventReceiverWindow.dismiss()
        }
    }
}
