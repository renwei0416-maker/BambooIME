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
        get() = p().getInt("mode", MODE_TRIGGER_AUTO).coerceIn(MODE_ALWAYS, MODE_TRIGGER_SEND)
        set(value) = p().edit().putInt("mode", value.coerceIn(MODE_ALWAYS, MODE_TRIGGER_SEND)).apply()

    /** 提示里每条摘要显示多少字。 */
    /** 每条最多显示几行（1~3）：不再按字数截断，由真实排版决定。 */
    var displayLen: Int
        get() = p().getInt("display_len", 40).coerceIn(4, 100)
        set(value) = p().edit().putInt("display_len", value.coerceIn(4, 100)).apply()

    /** 最多显示几条。 */
    var maxDisplay: Int
        get() = p().getInt("max_display", 3).coerceIn(1, 6)
        set(value) = p().edit().putInt("max_display", value.coerceIn(1, 6)).apply()

    /** 提示块的字号，相对编码行的百分比（50–150）。 */
    var tipsTextScale: Int
        get() = p().getInt("tips_text_scale", 80).coerceIn(50, 150)
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

    /**
     * 诗词标题字数门槛：`=` 前那个条目名（K 类 key）短于这个字数的不提示。
     * 默认 2＝不设限（等于编译期的 MIN_KEY_LEN）。
     */
    var minTitleLen: Int
        get() = p().getInt("min_title_len", 2).coerceIn(1, 8)
        set(value) = p().edit().putInt("min_title_len", value.coerceIn(1, 8)).apply()

    /**
     * 分句字数门槛：`{}` 里拆出来的每一句（S 类 key）短于这个字数的不提示。
     * 默认 4＝不设限（等于编译期的 MIN_SEG_LEN）。
     */
    var minSegLen: Int
        get() = p().getInt("min_seg_len", 4).coerceIn(1, 8)
        set(value) = p().edit().putInt("min_seg_len", value.coerceIn(1, 8)).apply()

    /** 首次启动默认只开这三个包（其余内置包由用户自己点开）。 */
    private val DEFAULT_ENABLED = setOf("mingju", "songci1000", "tangshi1000")

    /** 启用集（白名单）。旧版本的 `disabled_packs` 只在迁移时读一次。 */
    private const val KEY_ENABLED = "enabled_packs"
    private const val KEY_DISABLED_LEGACY = "disabled_packs"

    /**
     * 首次按默认表写入启用集。
     *
     * 三个前提条件缺一不可：
     * 1. 包列表非空 —— 包还拷好之前不写，否则会把默认值"烧"成空集；
     * 2. [KEY_ENABLED] 不存在 —— 写过就不再动用户的选择；
     * 3. 老版本的 [KEY_DISABLED_LEGACY] 只在**非空**时才用来反推：旧实现里空集
     *    既可能是"用户全开"，也可能是"包列表还没准备好就种过"（3.2.0 的 bug），
     *    两者无法区分，一律按默认表处理。
     */
    fun ensureEnabledSeeded(packIds: List<String>, titles: List<String> = emptyList()) {
        if (packIds.isEmpty()) return
        val stored = p().getStringSet(KEY_ENABLED, null)
        if (stored == null) {
            val legacyOff = p().getStringSet(KEY_DISABLED_LEGACY, null).orEmpty()
            val seeded =
                if (legacyOff.isEmpty()) {
                    packIds.filter { it in DEFAULT_ENABLED }.toSet()
                } else {
                    packIds.filter { it !in legacyOff }.toSet()
                }
            p().edit().putStringSet(KEY_ENABLED, seeded).apply()
            return
        }
        // 归一化：3.3.0 的设置页曾把「标题」写进这里，遇到就按 标题→id 映射回来，
        // 认不出来的直接丢掉，避免脏数据把启用状态搅乱。
        val idSet = packIds.toSet()
        val titleToId = titles.zip(packIds).toMap()
        var dirty = false
        val fixed =
            stored.mapNotNull { key ->
                when {
                    key in idSet -> key
                    titleToId.containsKey(key) -> {
                        dirty = true
                        titleToId[key]
                    }
                    else -> {
                        dirty = true
                        null
                    }
                }
            }.toSet()
        if (dirty) p().edit().putStringSet(KEY_ENABLED, fixed).apply()
    }

    /** 当前启用的包名。没种过时按默认表推算，保证设置页与真实行为一致。 */
    private var enabledPacks: Set<String>
        get() = p().getStringSet(KEY_ENABLED, null)?.toSet() ?: DEFAULT_ENABLED
        set(value) = p().edit().putStringSet(KEY_ENABLED, value).apply()

    fun isPackEnabled(name: String): Boolean = name in enabledPacks

    fun setPackEnabled(name: String, enabled: Boolean) {
        val current = enabledPacks.toMutableSet()
        if (enabled) current.add(name) else current.remove(name)
        enabledPacks = current
    }
}
