// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

/**
 * 主题资源解析接口，隔离「活动主题」与「草稿主题」。
 *
 * 真实键盘（[com.osfans.trime.ime.keyboard.KeyView]）与主题编辑器预览共用
 * [com.osfans.trime.ime.keyboard.KeyRenderer]，二者都必须通过本接口取色/取图，
 * 不得直接依赖全局 [ColorManager] 单例——预览传入的是草稿主题的解析器，从而避免
 * 污染全局配色/字体/图片缓存。
 */
interface ThemeResolver {
    @ColorInt
    fun getColor(key: String): Int

    fun getDrawable(key: String): Drawable?

    /**
     * 取主题颜色，解析不到（透明）时用 [fallback]。
     * 给主题以外的自绘组件（例如手写板）用，避免它们自己去猜主题里有没有这个键。
     */
    @ColorInt
    fun getColorOr(
        key: String,
        @ColorInt fallback: Int,
    ): Int {
        val color = getColor(key)
        return if (color == Color.TRANSPARENT) fallback else color
    }
}
