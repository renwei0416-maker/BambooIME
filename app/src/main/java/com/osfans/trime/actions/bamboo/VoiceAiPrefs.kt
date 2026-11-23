/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.bamboo

import android.content.Context
import android.content.SharedPreferences

/**
 * 「语音发AI」的设置。
 *
 * 独立存一个 SharedPreferences（`bamboo_voice_ai`），跟「回退与AI」的
 * `bamboo_actions` 分开——这一页整个可以关掉，不该影响那边的既有行为。
 */
object VoiceAiPrefs {
    /** 只在已经进了某个 AI 模式时才发送（默认）。 */
    const val SEND_WHEN_ARMED = 0

    /** 总是发送；没进模式时落到默认模式。 */
    const val SEND_ALWAYS = 1

    /** 关闭：完全不影响语音识别原来的行为。 */
    const val SEND_NEVER = 2

    private const val STORE = "bamboo_voice_ai"

    /**
     * 叠加在模式提示词前面的「语音纠错」提示。
     *
     * 分两句：前一句说明文字来自语音识别、可能有错；后一句要求先还原原意，再执行模式自身的提示词。
     * 这样本提示与模式提示词叠加时职责清晰：纠错归纠正、改写归模式。
     */
    const val DEFAULT_FIX_PROMPT =
        "下面这段文字是用户用语音输入、由识别引擎转成文字的，其中可能有个别字或词被识别错了。" +
            "请先根据上下文判断用户原本想说什么，纠正明显的识别错误（不要改写语气、不要扩写、不要增删内容），" +
            "然后再完成后面这段要求。"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    }

    private fun ensure(): Boolean = ::sp.isInitialized

    /** 0 = 仅已进模式（默认）／1 = 总是／2 = 关闭。 */
    var sendMode: Int
        get() = if (ensure()) sp.getInt("send_mode", SEND_WHEN_ARMED) else SEND_WHEN_ARMED
        set(value) {
            if (ensure()) sp.edit().putInt("send_mode", value).apply()
        }

    /** 总开关是否允许发送。 */
    val enabled: Boolean get() = sendMode != SEND_NEVER

    /** 没进模式时也发（落到默认模式）。 */
    val alwaysSend: Boolean get() = sendMode == SEND_ALWAYS

    /** 识别到的文字是否同时上屏。关掉就只留 AI 结果。 */
    var commitSource: Boolean
        get() = !ensure() || sp.getBoolean("commit_source", true)
        set(value) {
            if (ensure()) sp.edit().putBoolean("commit_source", value).apply()
        }

    /** 是否把 [fixPrompt] 叠加到模式提示词前面。 */
    var stackFixPrompt: Boolean
        get() = !ensure() || sp.getBoolean("stack_fix", true)
        set(value) {
            if (ensure()) sp.edit().putBoolean("stack_fix", value).apply()
        }

    var fixPrompt: String
        get() = if (ensure()) sp.getString("fix_prompt", DEFAULT_FIX_PROMPT) ?: DEFAULT_FIX_PROMPT else DEFAULT_FIX_PROMPT
        set(value) {
            if (ensure()) sp.edit().putString("fix_prompt", value).apply()
        }

    /** AI 失败时把识别到的原文上屏（否则什么都不留）。 */
    var commitOnFail: Boolean
        get() = !ensure() || sp.getBoolean("commit_on_fail", true)
        set(value) {
            if (ensure()) sp.edit().putBoolean("commit_on_fail", value).apply()
        }

    /** 请求超时（秒）。 */
    var timeoutSec: Int
        get() = if (ensure()) sp.getInt("timeout_sec", 20) else 20
        set(value) {
            if (ensure()) sp.edit().putInt("timeout_sec", value.coerceIn(5, 120)).apply()
        }

    /** 太短的不发（「嗯」「啊」这类）。 */
    var minChars: Int
        get() = if (ensure()) sp.getInt("min_chars", 2) else 2
        set(value) {
            if (ensure()) sp.edit().putInt("min_chars", value.coerceIn(0, 20)).apply()
        }

    /** 太长截断，防止把一整段话塞进去。 */
    var maxChars: Int
        get() = if (ensure()) sp.getInt("max_chars", 500) else 500
        set(value) {
            if (ensure()) sp.edit().putInt("max_chars", value.coerceIn(50, 4000)).apply()
        }

    /** 语音浮层上显示「请求中 / 已发送」这类状态。 */
    var showStatus: Boolean
        get() = !ensure() || sp.getBoolean("show_status", true)
        set(value) {
            if (ensure()) sp.edit().putBoolean("show_status", value).apply()
        }

}
