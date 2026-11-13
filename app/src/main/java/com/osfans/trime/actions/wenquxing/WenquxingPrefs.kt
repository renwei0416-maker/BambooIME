// 苍竹输入法 · 文曲星（诗词提示）配置
package com.osfans.trime.actions.wenquxing

import android.content.Context
import android.content.SharedPreferences
import com.osfans.trime.TrimeApplication

/**
 * 文曲星配置。
 *
 * 单独一个 SharedPreferences：这些项只服务诗词提示，与别的功能分开，恢复默认互不影响。
 */
object WenquxingPrefs {
    private const val FILE = "bamboo_wenquxing"

    /** 常开：不需要触发码，编码一变就查（不受 `tc;` 影响）。 */
    const val MODE_ALWAYS = 0

    /** 触发码 + 直接显示：进模式后编码一变就查。 */
    const val MODE_TRIGGER_AUTO = 1

    /** 触发码 + 发送查询（默认）：进模式后按发送键才查。 */
    const val MODE_TRIGGER_SEND = 2

    private var sp: SharedPreferences? = null

    private fun p(): SharedPreferences =
        sp ?: TrimeApplication.getInstance()
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .also { sp = it }

    /** 总开关：关掉后不查、不显示，触发码也不生效。 */
    var enabled: Boolean
        get() = p().getBoolean("enabled", true)
        set(value) = p().edit().putBoolean("enabled", value).apply()

    /**
     * 使用模式。
     *
     *  * [MODE_ALWAYS] 常开：不需要触发码，编码一变就查（不受 `tc;` 影响）
     *  * [MODE_TRIGGER_AUTO] 触发码 + 直接显示：进模式后编码一变就查
     *  * [MODE_TRIGGER_SEND] 触发码 + 发送查询：进模式后按发送键才查
     */
    var mode: Int
        get() = p().getInt("mode", MODE_TRIGGER_SEND).coerceIn(MODE_ALWAYS, MODE_TRIGGER_SEND)
        set(value) = p().edit().putInt("mode", value.coerceIn(MODE_ALWAYS, MODE_TRIGGER_SEND)).apply()

    /** 提示里每条摘要显示多少字。 */
    /** 每条最多显示几行（1~3）：不再按字数截断，由真实排版决定。 */
    var maxLines: Int
        get() = p().getInt("max_lines", 2).coerceIn(1, 3)
        set(value) = p().edit().putInt("max_lines", value.coerceIn(1, 3)).apply()

    /** 最多显示几条。 */
    var maxDisplay: Int
        get() = p().getInt("max_display", 3).coerceIn(1, 6)
        set(value) = p().edit().putInt("max_display", value.coerceIn(1, 6)).apply()

    /** 提示块的字号，相对编码行的百分比（50–150）。 */
    var tipsTextScale: Int
        get() = p().getInt("tips_text_scale", 90).coerceIn(50, 150)
        set(value) = p().edit().putInt("tips_text_scale", value.coerceIn(50, 150)).apply()

    /** 提示块背景的不透明度（0–100），默认 85。 */
    var tipsAlpha: Int
        get() = p().getInt("tips_alpha", 90).coerceIn(0, 100)
        set(value) = p().edit().putInt("tips_alpha", value.coerceIn(0, 100)).apply()

    /** 行尾「·N字」：这一条点下去会提交多少字。 */
    var showCharCount: Boolean
        get() = p().getBoolean("show_char_count", true)
        set(value) = p().edit().putBoolean("show_char_count", value).apply()

    /** 第一档上屏键（rime key 名，脚本默认 comma）。 */
    var keyFirst: String
        get() = p().getString("key_first", "comma") ?: "comma"
        set(value) = p().edit().putString("key_first", value).apply()

    /** 第二档上屏键（脚本默认 period）。 */
    var keySecond: String
        get() = p().getString("key_second", "period") ?: "period"
        set(value) = p().edit().putString("key_second", value).apply()

    /** 关掉的包名（空表示全部启用）。 */
    var disabledPacks: Set<String>
        get() = p().getStringSet("disabled_packs", emptySet()) ?: emptySet()
        set(value) = p().edit().putStringSet("disabled_packs", value).apply()

    fun isPackEnabled(name: String): Boolean = name !in disabledPacks

    fun setPackEnabled(name: String, enabled: Boolean) {
        val current = disabledPacks.toMutableSet()
        if (enabled) current.remove(name) else current.add(name)
        disabledPacks = current
    }
}
