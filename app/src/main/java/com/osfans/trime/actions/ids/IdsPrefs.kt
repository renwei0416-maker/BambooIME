/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.ids

import android.content.Context
import com.osfans.trime.TrimeApplication
import com.osfans.trime.link.IdsDataManager

/**
 * 部件反查（librime-ids-reverse）的设置。
 *
 * 插件本身已经编进 `librime_jni.so`，这里管的是「要不要挂到方案里」以及挂上去之后
 * 的那些 `ids_reverse:` 字段；真正落盘由 [com.osfans.trime.actions.wanxiang.WanxiangCustom] 完成。
 */
object IdsPrefs {
    private const val SP_NAME = "bamboo_ids"

    /** 下载源：自动测速 / 只用 GitHub / 只用 CNB。 */
    const val HOST_AUTO = 0
    const val HOST_GITHUB = 1
    const val HOST_CNB = 2

    const val FORMAT_UNICODE = "unicode"
    const val FORMAT_BRACKET = "bracket"
    const val FORMAT_PLAIN = "plain"

    const val SHUANGPIN_FLYPY = "flypy"
    private val sp get() =
        TrimeApplication.getInstance().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun reset() {
        sp.edit().clear().apply()
    }

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    var host: Int
        get() = sp.getInt("host", HOST_AUTO)
        set(v) { sp.edit().putInt("host", v).apply() }

    /** 全码反查前缀（默认 I，像 `I木口` 找「杏」）。 */
    var searchPrefix: String
        get() = sp.getString("search_prefix", "I").orEmpty().ifBlank { "I" }
        set(v) { sp.edit().putString("search_prefix", v).apply() }

    /** 混打前缀（默认 O，像 `O木木` 在拼音串里夹部件）。 */
    var hybridPrefix: String
        get() = sp.getString("hybrid_prefix", "O").orEmpty().ifBlank { "O" }
        set(v) { sp.edit().putString("hybrid_prefix", v).apply() }

    var maxResults: Int
        get() = sp.getInt("max_results", 60)
        set(v) { sp.edit().putInt("max_results", v).apply() }

    /** 上次「应用并部署」的自检结果（部署产物里有没有 `ids_reverse`），空 = 还没跑过。 */
    var deployCheck: String
        get() = sp.getString("deploy_check", "").orEmpty()
        set(v) { sp.edit().putString("deploy_check", v).apply() }

    var showPinyin: Boolean
        get() = sp.getBoolean("show_pinyin", true)
        set(v) { sp.edit().putBoolean("show_pinyin", v).apply() }

    var showIds: Boolean
        get() = sp.getBoolean("show_ids", true)
        set(v) { sp.edit().putBoolean("show_ids", v).apply() }

    var showUnicode: Boolean
        get() = sp.getBoolean("show_unicode", false)
        set(v) { sp.edit().putBoolean("show_unicode", v).apply() }

    var showBlock: Boolean
        get() = sp.getBoolean("show_block", false)
        set(v) { sp.edit().putBoolean("show_block", v).apply() }

    var idsFormat: String
        get() = sp.getString("ids_format", FORMAT_UNICODE).orEmpty()
        set(v) { sp.edit().putString("ids_format", v).apply() }

    /** 异体字触发符（默认 `/`）。 */
    var variantTrigger: String
        get() = sp.getString("variant_trigger", "/").orEmpty()
        set(v) { sp.edit().putString("variant_trigger", v).apply() }

    /** 异体字替换键（默认 `Shift+V`）。 */
    var variantReplaceKey: String
        get() = sp.getString("variant_replace_key", "Shift+V").orEmpty()
        set(v) { sp.edit().putString("variant_replace_key", v).apply() }

    /** 双拼方案是否跟随当前键盘（小鹤→flypy）。 */
    var shuangpinFollowKeyboard: Boolean
        get() = sp.getBoolean("shuangpin_follow", true)
        set(v) { sp.edit().putBoolean("shuangpin_follow", v).apply() }

    /** 关闭「跟随键盘」时手动选的双拼方案：`flypy` / `zrm` / `quanpin`。 */
    var shuangpinScheme: String
        get() = sp.getString("shuangpin_scheme", "flypy").orEmpty().ifBlank { "flypy" }
        set(v) { sp.edit().putString("shuangpin_scheme", v).apply() }

    /** 禁用的字区，逗号分隔（原样写进 `disabled_blocks`）。 */
    var disabledBlocks: String
        get() = sp.getString("disabled_blocks", "").orEmpty()
        set(v) { sp.edit().putString("disabled_blocks", v).apply() }

    /** 自定义提示文案（留空用插件默认）。 */
    var searchTips: String
        get() = sp.getString("search_tips", "").orEmpty()
        set(v) { sp.edit().putString("search_tips", v).apply() }

    var hybridTips: String
        get() = sp.getString("hybrid_tips", "").orEmpty()
        set(v) { sp.edit().putString("hybrid_tips", v).apply() }
}

/**
 * 要写进方案 `ids_reverse:` 的那一段配置。
 *
 * [dataFile] 是绝对路径 —— 数据装在 App 私有目录，rime 的工作目录不是它，只能写绝对路径。
 */
data class IdsConfig(
    val dataFile: String,
    val searchPrefix: String,
    val hybridPrefix: String,
    val maxResults: Int,
    val shuangpinScheme: String,
    val showPinyin: Boolean,
    val showIds: Boolean,
    val showUnicode: Boolean,
    val showBlock: Boolean,
    val idsFormat: String,
    val variantTrigger: String,
    val variantReplaceKey: String,
    val disabledBlocks: String,
    val searchTips: String,
    val hybridTips: String,
)

/** 当前要写入的配置；总开关关着、或数据没装，都返回 null（= 要清理掉相关配置）。 */
fun idsConfigOrNull(): IdsConfig? {
    if (!IdsPrefs.enabled) return null
    if (!IdsDataManager.isInstalled()) return null
    return IdsConfig(
        dataFile = IdsDataManager.dataFile().absolutePath,
        searchPrefix = IdsPrefs.searchPrefix,
        hybridPrefix = IdsPrefs.hybridPrefix,
        maxResults = IdsPrefs.maxResults,
        shuangpinScheme = resolvedShuangpinScheme(),
        showPinyin = IdsPrefs.showPinyin,
        showIds = IdsPrefs.showIds,
        showUnicode = IdsPrefs.showUnicode,
        showBlock = IdsPrefs.showBlock,
        idsFormat = IdsPrefs.idsFormat,
        variantTrigger = IdsPrefs.variantTrigger,
        variantReplaceKey = IdsPrefs.variantReplaceKey,
        disabledBlocks = IdsPrefs.disabledBlocks,
        searchTips = IdsPrefs.searchTips,
        hybridTips = IdsPrefs.hybridTips,
    )
}

/**
 * 实际写进配置的双拼方案。
 *
 * 开了「跟随键盘」就按小鹤双拼（flypy）写 —— 万象包里我们带的双拼键盘就是小鹤；
 * 关掉则用手动选项（flypy / zrm / quanpin）。
 * 这里不去猜「当前键盘叫什么」：`Theme.presetKeyboards` 在运行时会被裁剪，取不可靠。
 */
fun resolvedShuangpinScheme(): String =
    if (IdsPrefs.shuangpinFollowKeyboard) IdsPrefs.SHUANGPIN_FLYPY else IdsPrefs.shuangpinScheme
