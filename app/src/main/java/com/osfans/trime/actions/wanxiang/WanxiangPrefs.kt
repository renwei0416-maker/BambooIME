/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.wanxiang

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.osfans.trime.BuildConfig

/**
 * 万象拼音方案的用户偏好（独立于设置界面存的文件，只放「要写进 custom 的那几个值」）。
 *
 * 口径：
 *  * **一律以官方默认为默认值**，官方关掉的一律关（`userDict` / `contextual` 默认 false）；
 *  * 辅助码默认关闭（用户要求），只有手动打开才写进 custom；
 *  * 九键那一侧保持官方 schema 的值（`userDict` 默认 true），单独一组键[KEY_T9_*]。
 */
object WanxiangPrefs {
    private const val SP = "bamboo_wanxiang"
    private lateinit var p: SharedPreferences
    private lateinit var app: Context

    // ---- 布局：26 / 18 / 14 / 9
    const val LAYOUT_26 = 26
    const val LAYOUT_18 = 18
    const val LAYOUT_14 = 14
    const val LAYOUT_9 = 9

    // ---- 编码方式
    const val ENCODE_QUANPIN = "quanpin"
    const val ENCODE_SHUANGPIN = "shuangpin"

    // ---- 辅助码
    const val AUX_NONE = 0
    const val AUX_DIRECT = 1
    const val AUX_INDIRECT = 2

    // ---- 「打不出字」时那张一次性提示卡的状态（0 未看过 / 1 选了去配置或× / 2 选了自己配置）
    const val HINT_NONE = 0
    const val HINT_DISMISSED = 1
    const val HINT_SELF_CONFIG = 2

    /** 双拼方案（必须是 `wanxiang_algebra.yaml` 里 `pro:` 下的节点名，不含「全拼」）。 */
    val SCHEMES = listOf(
        "小鹤双拼", "自然码", "搜狗双拼", "微软双拼", "智能ABC", "紫光双拼", "拼音加加",
        "国标双拼", "乱序17", "蓝天双拼", "大牛双拼", "首道双拼", "万象双拼",
    )

    /** 模糊音键名（对应 `wanxiang_algebra:/模糊音_<key>`）。 */
    val FUZZY_KEYS = listOf("nl", "ry", "hf", "rl", "kg", "en_eng", "in_ing", "c_ch", "z_zh", "s_sh")
    val FUZZY_LABELS = mapOf(
        "nl" to "n / l", "ry" to "r / l", "hf" to "h / f", "rl" to "r / l 混",
        "kg" to "k / g", "en_eng" to "en / eng", "in_ing" to "in / ing",
        "c_ch" to "c / ch", "z_zh" to "z / zh", "s_sh" to "s / sh",
    )

    private const val KEY_LAYOUT = "layout"
    private const val KEY_RESTORE_KEYBOARD = "restore_keyboard"
    private const val KEY_LAST_KEYBOARD = "last_keyboard"
    private const val KEY_PANEL_LONG_PRESS = "panel_long_press"
    private const val KEY_PANEL_ACTIONS = "panel_actions"
    private const val KEY_ENCODE = "encode"
    private const val KEY_SCHEME = "scheme"
    private const val KEY_AUX = "aux"
    private const val KEY_PAGE_SIZE = "page_size"
    private const val KEY_USER_DICT = "user_dict"
    private const val KEY_CONTEXTUAL = "contextual"
    private const val KEY_FUZZY = "fuzzy"
    private const val KEY_WIZARD_DONE = "wizard_done"
    private const val KEY_HINT_STATE = "first_run_hint"
    private const val KEY_HINT_STAMP = "first_run_hint_stamp"
    private const val KEY_APPLIED = "applied_signature"
    private const val KEY_DOWNLOAD_BASE = "download_base"

    private const val KEY_T9_PAGE_SIZE = "t9_page_size"
    private const val KEY_T9_USER_DICT = "t9_user_dict"
    private const val KEY_T9_CONTEXTUAL = "t9_contextual"

    fun init(context: Context) {
        if (::p.isInitialized) return
        app = context.applicationContext
        p = app.getSharedPreferences(SP, Context.MODE_PRIVATE)
    }

    /** 初始化完成没有。引擎线程回调里用，没初始化就什么都别做。 */
    fun isReady(): Boolean = ::p.isInitialized

    private fun ready(): SharedPreferences {
        check(::p.isInitialized) { "WanxiangPrefs.init() 未调用" }
        return p
    }

    /** 整份快照。向导用它做「取消了就还原」。 */
    fun snapshotAll(): Map<String, *> = HashMap(ready().all)

    /** 用快照整体覆盖（清空后写回）。 */
    fun restoreAll(map: Map<String, *>) {
        ready().edit {
            clear()
            map.forEach { (k, v) ->
                when (v) {
                    is String -> putString(k, v)
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is Float -> putFloat(k, v)
                    is Boolean -> putBoolean(k, v)
                    is Set<*> -> putStringSet(k, v.map { it.toString() }.toSet())
                }
            }
        }
    }

    var layout: Int
        get() = ready().getInt(KEY_LAYOUT, LAYOUT_26)
        set(v) = ready().edit { putInt(KEY_LAYOUT, v) }

    /**
     * 进输入框时是否按 [layout] 把键盘形态恢复回来（默认开）。
     *
     * 关掉之后完全不管键盘，用户手动切了就保持手动的。
     */
    var restoreKeyboard: Boolean
        get() = ready().getBoolean(KEY_RESTORE_KEYBOARD, true)
        set(v) = ready().edit { putBoolean(KEY_RESTORE_KEYBOARD, v) }

    /**
     * 用户上次主动切的键盘 id（`default` / `qijian` / `xiaohe` / `wanxiang_*` …）。
     *
     * 主题里所有键盘都是 `lock = false`，[com.osfans.trime.ime.keyboard.KeyboardWindow]
     * 重建时只会回落 `default`，所以自己记一份；空串表示「没记过」。
     */
    var lastKeyboard: String
        get() = ready().getString(KEY_LAST_KEYBOARD, "").orEmpty()
        set(v) = ready().edit { putString(KEY_LAST_KEYBOARD, v) }

    /** 长按切换键是否打开我们的「键盘与方案」面板（默认开）。 */
    var panelOnLongPress: Boolean
        get() = ready().getBoolean(KEY_PANEL_LONG_PRESS, true)
        set(v) = ready().edit { putBoolean(KEY_PANEL_LONG_PRESS, v) }

    /** 面板里是否显示「手写 / 拍照 / AI / 剪贴板」这一行（默认开）。 */
    var panelShowActions: Boolean
        get() = ready().getBoolean(KEY_PANEL_ACTIONS, true)
        set(v) = ready().edit { putBoolean(KEY_PANEL_ACTIONS, v) }

    var encode: String
        get() = ready().getString(KEY_ENCODE, ENCODE_QUANPIN).orEmpty()
        set(v) = ready().edit { putString(KEY_ENCODE, v) }

    var scheme: String
        get() = ready().getString(KEY_SCHEME, SCHEMES.first()).orEmpty()
        set(v) = ready().edit { putString(KEY_SCHEME, v) }

    var aux: Int
        get() = ready().getInt(KEY_AUX, AUX_NONE)
        set(v) = ready().edit { putInt(KEY_AUX, v) }

    var pageSize: Int
        get() = ready().getInt(KEY_PAGE_SIZE, 6).coerceIn(3, 6)
        set(v) = ready().edit { putInt(KEY_PAGE_SIZE, v.coerceIn(3, 6)) }

    /** 用户词典/自动调频。官方默认 false，这里也默认 false。 */
    var userDict: Boolean
        get() = ready().getBoolean(KEY_USER_DICT, false)
        set(v) = ready().edit { putBoolean(KEY_USER_DICT, v) }

    /** 智能调频（上下文预测）。官方默认 false。 */
    var contextual: Boolean
        get() = ready().getBoolean(KEY_CONTEXTUAL, false)
        set(v) = ready().edit { putBoolean(KEY_CONTEXTUAL, v) }

    var fuzzy: Set<String>
        get() = ready().getStringSet(KEY_FUZZY, emptySet()).orEmpty().toSet()
        set(v) = ready().edit { putStringSet(KEY_FUZZY, v) }

    var wizardDone: Boolean
        get() = ready().getBoolean(KEY_WIZARD_DONE, false)
        set(v) = ready().edit { putBoolean(KEY_WIZARD_DONE, v) }

    /** 首次「打不出字」提示卡的状态，见 `HINT_*`。非 0 即不再弹。 */
    var hintState: Int
        get() {
            val saved = ready().getInt(KEY_HINT_STATE, HINT_NONE)
            if (saved == HINT_NONE) return HINT_NONE
            // debug 包：装了新版本就当成没提示过，方便反复测试这条提示
            if (BuildConfig.DEBUG) {
                val stamp = ready().getString(KEY_HINT_STAMP, "").orEmpty()
                if (stamp != hintStamp()) return HINT_NONE
            }
            return saved
        }
        set(v) = ready().edit {
            putInt(KEY_HINT_STATE, v)
            putString(KEY_HINT_STAMP, hintStamp())
        }

    /**
     * 方案包下载源前缀（可换国内镜像）。
     *
     * 空 = 用官方 GitHub；填了就按 `<前缀>/<文件名>` 拼。
     */
    var downloadBase: String
        get() = ready().getString(KEY_DOWNLOAD_BASE, "").orEmpty()
        set(v) = ready().edit { putString(KEY_DOWNLOAD_BASE, v.trim()) }

    /** 上次「应用并部署」时的状态指纹；与 [signature] 不等即表示「有未应用的改动」。 */
    var appliedSignature: String
        get() = ready().getString(KEY_APPLIED, "").orEmpty()
        set(v) = ready().edit { putString(KEY_APPLIED, v) }

    var t9PageSize: Int
        get() = ready().getInt(KEY_T9_PAGE_SIZE, 6).coerceIn(3, 6)
        set(v) = ready().edit { putInt(KEY_T9_PAGE_SIZE, v.coerceIn(3, 9)) }

    var t9UserDict: Boolean
        get() = ready().getBoolean(KEY_T9_USER_DICT, true)
        set(v) = ready().edit { putBoolean(KEY_T9_USER_DICT, v) }

    var t9Contextual: Boolean
        get() = ready().getBoolean(KEY_T9_CONTEXTUAL, false)
        set(v) = ready().edit { putBoolean(KEY_T9_CONTEXTUAL, v) }

    /** 当前状态的可比较指纹（不含「上次应用」本身）。 */
    fun signature(): String = buildString {
        append(layout).append('|').append(encode).append('|').append(scheme).append('|')
        append(aux).append('|').append(pageSize).append('|')
        append(userDict).append('|').append(contextual).append('|')
        append(fuzzy.sorted().joinToString(",")).append('|')
        append(t9PageSize).append('|').append(t9UserDict).append('|').append(t9Contextual)
    }

    fun hasPendingChanges(): Boolean = signature() != appliedSignature

    /**
     * 把互斥项收干净（改任何设置后都调一次）：
     * - 九键没有字母键：编码固定全拼、辅助码与模糊音都用不上；
     * - 关掉用户词典后，智能调频没有依据，一并关掉。
     */
    fun sanitize() {
        if (layout == LAYOUT_9) {
            encode = ENCODE_QUANPIN
            aux = AUX_NONE
            fuzzy = emptySet()
        }
        if (!userDict) contextual = false
        if (!t9UserDict) t9Contextual = false
    }

    /**
     * debug 包用「本次安装时间」当戳，release 用版本号。
     * 这样同一个版本号下反复出新包，装一次就能重测一次提示。
     */
    private fun hintStamp(): String {
        if (!BuildConfig.DEBUG) return BuildConfig.BUILD_VERSION_NAME
        return runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault(BuildConfig.BUILD_VERSION_NAME)
    }

    /** 把「首次提示卡」状态清掉：切回键盘会再弹一次。 */
    fun resetHint() {
        ready().edit { remove(KEY_HINT_STATE); remove(KEY_HINT_STAMP) }
    }

    fun resetToDefaults() {
        ready().edit {
            putInt(KEY_LAYOUT, LAYOUT_26)
            putBoolean(KEY_RESTORE_KEYBOARD, true)
            putString(KEY_LAST_KEYBOARD, "")
            putString(KEY_ENCODE, ENCODE_QUANPIN)
            putString(KEY_SCHEME, SCHEMES.first())
            putInt(KEY_AUX, AUX_NONE)
            putInt(KEY_PAGE_SIZE, 6)
            putBoolean(KEY_USER_DICT, false)
            putBoolean(KEY_CONTEXTUAL, false)
            putStringSet(KEY_FUZZY, emptySet())
            putInt(KEY_T9_PAGE_SIZE, 6)
            putBoolean(KEY_T9_USER_DICT, true)
            putBoolean(KEY_T9_CONTEXTUAL, false)
        }
    }
}
