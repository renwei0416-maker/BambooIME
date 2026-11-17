// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.NinePatch
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.NinePatchDrawable
import androidx.annotation.ColorInt
import androidx.collection.LruCache
import androidx.core.graphics.drawable.toDrawable
import com.caverock.androidsvg.SVG
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.util.ColorUtils
import com.osfans.trime.util.NinePatchBitmapFactory
import timber.log.Timber
import java.io.FileInputStream

/**
 * 针对「一个具体主题 + 一个具体配色方案」的取色/取图实现。
 *
 * 从全局 [ColorManager] 单例中抽离出的纯解析逻辑，可同时服务：
 *  - 活动主题（[ColorManager] 在每次 [ColorManager.switchTheme] 后重建一个实例）
 *  - 编辑器草稿主题（[com.osfans.trime.ui.settings.theme.ThemePreviewView] 自建实例）
 *
 * 每个实例持有独立的 [colorCache]/[gradientDrawableCache]/[bitmapCache]，因此草稿渲染
 * 不会污染活动主题的缓存；[GradientDrawable] 通过 [android.graphics.drawable.Drawable.ConstantState.newDrawable]
 * 隔离，避免多实例共享可变状态。
 */
class ThemeResourceResolver(
    private val theme: Theme,
    private val activeColorScheme: ColorScheme,
) : ThemeResolver {
    private val backgroundFolder get() = theme.generalStyle.backgroundFolder

    private val bitmapCache: LruCache<String, Bitmap>
    private val gradientDrawableCache: LruCache<Int, GradientDrawable>
    private val colorCache = LruCache<String, Int>(128)

    init {
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        val cacheSize = maxMemory / 8
        bitmapCache =
            object : LruCache<String, Bitmap>(cacheSize.toInt()) {
                override fun sizeOf(
                    key: String,
                    value: Bitmap,
                ): Int = value.byteCount / 1024
            }
        gradientDrawableCache =
            object : LruCache<Int, GradientDrawable>(32) {}
    }

    @ColorInt
    override fun getColor(key: String): Int = resolveColor(key)

    override fun getDrawable(key: String): Drawable? = resolveDrawable(key)

    @ColorInt
    private fun resolveColor(key: String): Int = colorCache.get(key) ?: run {
        var resolved = true
        val color =
            try {
                resolveValue(key) { value ->
                    if (SUPPORTED_IMG_FORMATS.any { value.endsWith(it, ignoreCase = true) }) {
                        throw IllegalArgumentException(
                            "Color key '$key' resolved to image file '$value' - use a color, not an image",
                        )
                    }
                    ColorUtils.parseColor(value)
                }
            } catch (e: IllegalArgumentException) {
                if (e.message?.contains("image file") == true) throw e
                try {
                    ColorUtils.parseColor(key)
                } catch (_: IllegalArgumentException) {
                    Timber.w(e, "Color key '$key' not resolved, falling back to transparent")
                    // 失败值不入缓存：否则一次失败会在本 resolver 生命周期内永久透明，
                    // 之后即使配色变了也不会重算（「改完不生效」的经典来源）。
                    resolved = false
                    Color.TRANSPARENT
                }
            }
        if (resolved) colorCache.put(key, color)
        color
    }

    private fun resolveDrawable(key: String): Drawable? =
        try {
            resolveValue(key) { value ->
                parseDrawable(value)
            }
        } catch (_: IllegalArgumentException) {
            parseDrawable(key)
        }

    private inline fun <T> resolveValue(
        key: String,
        parser: (String) -> T,
    ): T {
        var currentKey = key

        while (true) {
            val target = activeColorScheme.colors[currentKey]
            if (!target.isNullOrEmpty()) {
                Timber.d("current: $currentKey, origin: $key, target: $target")
                return parser(target)
            }
            val fallback = theme.fallbackColors[currentKey]
            if (!fallback.isNullOrEmpty()) {
                currentKey = fallback
                continue
            }
            val altFallback = BuiltinFallbackColors[currentKey]
            if (!altFallback.isNullOrEmpty()) {
                currentKey = altFallback
            } else {
                throw IllegalArgumentException("$key not found")
            }
        }
    }

    private fun parseDrawable(value: String): Drawable? {
        if (value.isEmpty()) return null
        if (SUPPORTED_IMG_FORMATS.any { value.endsWith(it) }) {
            val path = resolveImageFilePath(value)
            if (value.endsWith(".svg")) {
                val bitmap =
                    bitmapCache.get(path)
                        ?: loadSvgBitmap(path)?.also {
                            bitmapCache.put(path, it)
                        } ?: return null
                return bitmap.toDrawable(Resources.getSystem())
            }
            val bitmap =
                bitmapCache.get(path)
                    ?: BitmapFactory.decodeFile(path)?.also {
                        bitmapCache.put(path, it)
                    } ?: return null
            if (path.endsWith(".9.png")) {
                val chunk = bitmap.ninePatchChunk
                return if (NinePatch.isNinePatchChunk(chunk)) {
                    // for compiled nine patch image
                    NinePatchDrawable(Resources.getSystem(), bitmap, chunk, null, null)
                } else {
                    // for source nine patch image
                    NinePatchBitmapFactory.createNinePatchDrawable(Resources.getSystem(), bitmap)
                }
            }
            return bitmap.toDrawable(Resources.getSystem())
        } else {
            val color =
                try {
                    ColorUtils.parseColor(value)
                } catch (_: Exception) {
                    Color.TRANSPARENT
                }
            val cached = gradientDrawableCache.get(color)
            return cached?.constantState?.newDrawable()
                ?: GradientDrawable().apply {
                    setColor(color)
                    gradientDrawableCache.put(color, this)
                }
        }
    }

    private fun resolveImageFilePath(value: String): String {
        val default = DataManager.userDataBaseDir.resolve("themes/backgrounds/$backgroundFolder/$value")
        if (default.exists()) return default.absolutePath
        val fallback = DataManager.userDataBaseDir.resolve("themes/backgrounds/$value")
        return fallback.absolutePath
    }

    private fun loadSvgBitmap(path: String): Bitmap? = try {
        val svg = SVG.getFromInputStream(FileInputStream(path))
        val docWidth = svg.documentWidth
        val docHeight = svg.documentHeight
        val width = if (docWidth > 0f) docWidth.toInt() else 256
        val height = if (docHeight > 0f) docHeight.toInt() else 256
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        svg.renderToCanvas(canvas)
        bitmap
    } catch (e: Exception) {
        Timber.w(e, "Failed to load SVG: $path")
        null
    }

    companion object {
        private val SUPPORTED_IMG_FORMATS = arrayOf(".png", ".webp", ".jpg", ".gif", ".svg")

        val BuiltinFallbackColors =
            mapOf(
                "candidate_text_color" to "text_color",
                "comment_text_color" to "candidate_text_color",
                "border_color" to "back_color",
                "candidate_separator_color" to "border_color",
                "candidate_separator_color_unrolled" to "candidate_separator_color",
                "hilited_text_color" to "text_color",
                "hilited_back_color" to "back_color",
                "hilited_candidate_text_color" to "hilited_text_color",
                "hilited_candidate_back_color" to "hilited_back_color",
                "hilited_candidate_button_color" to "hilited_candidate_back_color",
                "hilited_label_color" to "hilited_candidate_text_color",
                "hilited_comment_text_color" to "comment_text_color",
                "hilited_key_back_color" to "hilited_candidate_back_color",
                "hilited_key_text_color" to "hilited_candidate_text_color",
                "hilited_key_symbol_color" to "hilited_comment_text_color",
                "hilited_off_key_back_color" to "hilited_key_back_color",
                "hilited_on_key_back_color" to "hilited_key_back_color",
                "hilited_off_key_text_color" to "hilited_key_text_color",
                "hilited_on_key_text_color" to "hilited_key_text_color",
                "key_back_color" to "back_color",
                "key_border_color" to "border_color",
                "key_shadow_color" to "shadow_color",
                "key_text_color" to "candidate_text_color",
                "key_symbol_color" to "comment_text_color",
                "label_color" to "candidate_text_color",
                "off_key_back_color" to "key_back_color",
                "off_key_text_color" to "key_text_color",
                "on_key_back_color" to "hilited_key_back_color",
                "on_key_text_color" to "hilited_key_text_color",
                "popup_back_color" to "key_back_color",
                "popup_text_color" to "key_text_color",
                "hilited_popup_back_color" to "hilited_key_back_color",
                "hilited_popup_text_color" to "hilited_key_text_color",
                "shadow_color" to "border_color",
                "root_background" to "back_color",
                "candidate_background" to "back_color",
                "keyboard_back_color" to "border_color",
                "keyboard_background" to "keyboard_back_color",
                "liquid_keyboard_background" to "keyboard_back_color",
                "liquid_keyboard_board" to "key_border_color",
                "liquid_keyboard_divider_color" to "candidate_separator_color",
                "text_back_color" to "back_color",
                "long_text_color" to "key_text_color",
                "long_text_back_color" to "key_back_color",
                "clipboard_category_back_color" to "key_back_color",
                "clipboard_category_selected_back_color" to "hilited_candidate_back_color",
                "clipboard_category_selected_text_color" to "hilited_candidate_text_color",
                "clipboard_entry_back_color" to "key_back_color",
                "hilited_clipboard_entry_back_color" to "hilited_candidate_back_color",
                "clipboard_checkbox_color" to "key_text_color",
                "candidate_border_color" to "border_color",
                "off_key_symbol_color" to "key_symbol_color",
                "on_key_symbol_color" to "hilited_key_symbol_color",
                "hilited_off_key_symbol_color" to "hilited_key_symbol_color",
                "hilited_on_key_symbol_color" to "hilited_key_symbol_color",
                "sidebar_back_color" to "key_back_color",
                "sidebar_hilited_back_color" to "hilited_key_back_color",
                "sidebar_text_color" to "key_text_color",
                "sidebar_border_color" to "key_border_color",
                "sidebar_spacing_color" to "key_border_color",
            )
    }
}
