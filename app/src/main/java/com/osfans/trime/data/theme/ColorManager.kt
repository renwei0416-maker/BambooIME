// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorInt
import androidx.core.math.MathUtils
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.isNightMode

object ColorManager : ThemeResolver {
    private lateinit var theme: Theme
    private val prefs = ThemeManager.prefs
    private var normalModeColor by prefs.normalModeColor
    private val followSystemDayNight by prefs.followSystemDayNight

    private var isNightMode = false

    private lateinit var _activeColorScheme: ColorScheme

    var activeColorScheme: ColorScheme
        get() = _activeColorScheme
        private set(value) {
            if (this::_activeColorScheme.isInitialized && _activeColorScheme == value) return
            _activeColorScheme = value
            fireChange()
        }

    private var lightModeColorScheme: ColorScheme? = null

    private var darkModeColorScheme: ColorScheme? = null

    /** 活动主题的取色/取图实现，每次 [switchTheme]/配色变化后重建。 */
    private lateinit var resolver: ThemeResourceResolver

    private fun rebuildResolver() {
        resolver = ThemeResourceResolver(theme, activeColorScheme)
    }

    fun interface OnColorChangeListener {
        fun onColorChange(theme: Theme)
    }

    private val onChangeListeners = WeakHashSet<OnColorChangeListener>()

    fun addOnChangedListener(listener: OnColorChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnColorChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onColorChange(theme) }
    }

    private fun colorScheme(id: String) = theme.colorSchemes.find { it.id == id }

    fun init(configuration: Configuration) {
        isNightMode = configuration.isNightMode()
        activeColorScheme = evaluateActiveColorScheme()
        rebuildResolver()
    }

    fun onSystemNightModeChange(isNight: Boolean) {
        isNightMode = isNight
        activeColorScheme = evaluateActiveColorScheme()
        rebuildResolver()
    }

    private fun evaluateActiveColorScheme(): ColorScheme = when {
        followSystemDayNight -> {
            val defaultModeScheme = if (isNightMode) darkModeColorScheme else lightModeColorScheme

            fun resolveScheme(id: String?) = id?.let { colorScheme(it) } ?: defaultModeScheme

            colorScheme(normalModeColor)?.let { userScheme ->
                val lightSchemeId = userScheme.colors["light_scheme"]
                val darkSchemeId = userScheme.colors["dark_scheme"]

                when {
                    lightSchemeId != null && darkSchemeId != null ->
                        // 如果两者都指定了，根据当前模式选择对应的配色
                        resolveScheme(if (isNightMode) darkSchemeId else lightSchemeId)

                    lightSchemeId != null ->
                        // 如果只指定了light_scheme，说明是暗色方案
                        if (isNightMode) userScheme else resolveScheme(lightSchemeId)

                    darkSchemeId != null ->
                        // 如果只指定了dark_scheme，说明是亮色方案
                        if (isNightMode) resolveScheme(darkSchemeId) else userScheme

                    else -> defaultModeScheme
                }
            } ?: defaultModeScheme
        }

        else -> colorScheme(normalModeColor)
    } ?: colorScheme("default") ?: theme.colorSchemes.firstOrNull() ?: ColorScheme("default", emptyMap())

    /** 每次切换主题后，都要调用此函数，初始化配色 */
    fun switchTheme(
        theme: Theme,
        suppressFireChange: Boolean = false,
    ) {
        this.theme = theme
        val defaultScheme = colorScheme("default") ?: theme.colorSchemes.firstOrNull() ?: ColorScheme("default", emptyMap())
        lightModeColorScheme = defaultScheme.colors["light_scheme"]?.let { colorScheme(it) }
        darkModeColorScheme = defaultScheme.colors["dark_scheme"]?.let { colorScheme(it) }
        if (suppressFireChange) {
            _activeColorScheme = evaluateActiveColorScheme()
        } else {
            activeColorScheme = evaluateActiveColorScheme()
        }
        rebuildResolver()
    }

    fun setColorScheme(scheme: ColorScheme) {
        activeColorScheme = scheme
        normalModeColor = scheme.id
        rebuildResolver()
    }

    @ColorInt
    override fun getColor(key: String): Int = resolver.getColor(key)

    override fun getDrawable(key: String): Drawable? = resolver.getDrawable(key)

    fun getDecorDrawable(
        colorKey: String,
        borderColorKey: String? = null,
        borderPx: Int = 0,
        cornerRadius: Float = 0f,
        alpha: Int = 255,
    ): Drawable? = when (val drawable = getDrawable(colorKey)) {
        is GradientDrawable ->
            drawable.also {
                it.cornerRadius = cornerRadius
                it.alpha = MathUtils.clamp(alpha, 0, 255)
                if (!borderColorKey.isNullOrEmpty()) {
                    try {
                        val borderColor = getColor(borderColorKey)
                        it.setStroke(borderPx, borderColor)
                    } catch (_: Exception) {
                    }
                }
            }

        else -> drawable?.also { it.alpha = MathUtils.clamp(alpha, 0, 255) }
    }
}
