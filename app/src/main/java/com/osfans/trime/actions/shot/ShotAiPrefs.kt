/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import com.osfans.trime.TrimeApplication
import com.osfans.trime.actions.bamboo.SecretStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 截图界面的 AI 动作与「视觉 AI」接口配置。
 *
 * 为什么单独一份、不复用「回退与AI」那套：那套是给**文本**用的（可以选便宜的纯文本模型），
 * 截图问图必须用**支持视觉**的模型，两者经常不是同一个 provider/model，混在一起会互相拖累。
 *
 * 存在独立的 SharedPreferences（`bamboo_shot_ai`）里；API Key 与别处一样走
 * [SecretStore] 加密落盘，明文只在内存里短暂存在。
 */
object ShotAiPrefs {
    private const val FILE = "bamboo_shot_ai"
    private const val K_BASE_URL = "base_url"
    private const val K_MODEL = "model"
    private const val K_API_KEY = "api_key"
    private const val K_ACTIONS = "actions"

    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1/chat/completions"
    const val DEFAULT_MODEL = "deepseek-flash"

    /** 一个动作＝扇形菜单上的一项。 */
    data class Action(
        val id: String,
        val name: String,
        val prompt: String,
        val enabled: Boolean,
    )

    private val prefs
        get() =
            TrimeApplication.getInstance()
                .getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)

    /** 一次内置两条，用户可改可删可新增。 */
    private fun defaultActions(): List<Action> =
        listOf(
            Action("builtin_ask", "问 AI", "看看这张图，用中文简要说明里面有什么、在讲什么。", true),
            Action("builtin_translate", "翻译", "把图片里的文字翻译成中文，只输出译文，保留原有换行。", true),
        )

    var baseUrl: String
        get() = prefs.getString(K_BASE_URL, DEFAULT_BASE_URL).orEmpty().ifBlank { DEFAULT_BASE_URL }
        set(v) = prefs.edit().putString(K_BASE_URL, v.trim()).apply()

    var model: String
        get() = prefs.getString(K_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(v) = prefs.edit().putString(K_MODEL, v.trim()).apply()

    /** 明文 key（读时解密）。 */
    var apiKey: String
        get() = SecretStore.decrypt(prefs.getString(K_API_KEY, null)).orEmpty()
        set(value) {
            val trimmed = value.trim()
            val stored = if (trimmed.isEmpty()) null else SecretStore.encrypt(trimmed)
            prefs.edit().putString(K_API_KEY, stored).apply()
        }

    var actions: List<Action>
        get() {
            val raw = prefs.getString(K_ACTIONS, null)
            if (raw.isNullOrBlank()) return defaultActions()
            val parsed =
                runCatching {
                    val arr = JSONArray(raw)
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val name = o.optString("name").trim()
                        if (name.isEmpty()) return@mapNotNull null
                        Action(
                            id = o.optString("id").ifBlank { "a" + System.nanoTime() },
                            name = name.take(4),
                            prompt = o.optString("prompt"),
                            enabled = o.optBoolean("enabled", true),
                        )
                    }
                }.getOrNull()
            return parsed ?: defaultActions()
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { a ->
                arr.put(
                    JSONObject()
                        .put("id", a.id)
                        .put("name", a.name)
                        .put("prompt", a.prompt)
                        .put("enabled", a.enabled),
                )
            }
            prefs.edit().putString(K_ACTIONS, arr.toString()).apply()
        }

    /** 扇形菜单里真正要显示的（只保留启用的）。 */
    fun enabledActions(): List<Action> = actions.filter { it.enabled && it.name.isNotBlank() }

    fun addAction(
        name: String,
        prompt: String,
    ) {
        actions = actions + Action("a" + System.currentTimeMillis(), name.take(4), prompt, true)
    }

    fun updateAction(
        id: String,
        name: String? = null,
        prompt: String? = null,
        enabled: Boolean? = null,
    ) {
        actions =
            actions.map {
                if (it.id != id) {
                    it
                } else {
                    it.copy(
                        name = (name ?: it.name).take(4),
                        prompt = prompt ?: it.prompt,
                        enabled = enabled ?: it.enabled,
                    )
                }
            }
    }

    fun removeAction(id: String) {
        actions = actions.filterNot { it.id == id }
    }

    /** 恢复成内置两条（设置页用）。 */
    fun resetActions() {
        prefs.edit().remove(K_ACTIONS).apply()
    }
}
