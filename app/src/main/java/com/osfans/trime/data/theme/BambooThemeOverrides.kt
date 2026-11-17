/*
 * SPDX-FileCopyrightText: 2026 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.base.DataManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.io.File

/**
 * 主题覆盖层：把「主题样式」里改过的值存成一份 JSON，加载主题时与 Lua 产出的 JSON 深合并。
 *
 * 放 `userDataBaseDir/bamboo/theme-overrides/<主题id>.json`。
 * 不能放 `themes/` 目录下：那里会被 `ThemeManager.loadThemeFromLua` 的 `latestMtime` 遍历，
 * 覆盖层一改就会连带把 Lua 主题的 JSON 缓存判为过期、整份重跑。
 */
object BambooThemeOverrides {
    private const val DIR = "bamboo/theme-overrides"

    private fun dir(): File = File(DataManager.userDataBaseDir, DIR).also { it.mkdirs() }

    fun fileOf(themeId: String): File = File(dir(), "$themeId.json")

    /** 覆盖层的时间戳；不存在时返回 0。用于让主题缓存感知覆盖层变化。 */
    fun stampOf(themeId: String): Long = fileOf(themeId).takeIf { it.exists() }?.lastModified() ?: 0L

    fun isActive(themeId: String): Boolean = fileOf(themeId).exists()

    fun read(themeId: String): JsonObject? {
        val f = fileOf(themeId)
        if (!f.exists()) return null
        return runCatching { Theme.json.parseToJsonElement(f.readText()) as? JsonObject }
            .onFailure { Timber.w(it, "BambooThemeOverrides: 解析失败 ${f.name}") }
            .getOrNull()
    }

    fun write(themeId: String, obj: JsonObject): Boolean {
        val f = fileOf(themeId)
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(Theme.json.encodeToString(JsonElement.serializer(), obj))
            true
        }.onFailure { Timber.w(it, "BambooThemeOverrides: 写入失败 ${f.name}") }
            .getOrDefault(false)
    }

    fun clear(themeId: String): Boolean = runCatching { fileOf(themeId).delete() }.getOrDefault(false)

    /** 深合并：对象逐键递归；数组与标量整体替换（覆盖层永远赢）。 */
    fun merge(base: JsonObject, override: JsonObject): JsonObject {
        if (override.isEmpty()) return base
        val out = LinkedHashMap<String, JsonElement>(base)
        for ((k, v) in override) {
            val old = out[k]
            out[k] = if (old is JsonObject && v is JsonObject) merge(old, v) else v
        }
        return JsonObject(out)
    }

    /** 读该主题的覆盖层并与基础 JSON 合并；无覆盖层时原样返回。 */
    fun merged(themeId: String, base: JsonObject): JsonObject {
        val ov = read(themeId) ?: return base
        return runCatching { merge(base, ov) }
            .onFailure { Timber.w(it, "BambooThemeOverrides: 合并失败 $themeId") }
            .getOrDefault(base)
    }

    /** 把 JSON 里的一串键写成嵌套对象，例如 path("style","fonts","key_size") = {"style":{"fonts":{"key_size":..}}} */
    fun nest(vararg path: String, value: JsonElement): JsonObject {
        require(path.isNotEmpty())
        var node: JsonObject = JsonObject(mapOf(path.last() to value))
        for (i in path.size - 2 downTo 0) {
            node = JsonObject(mapOf(path[i] to node))
        }
        return node
    }

    fun arrayOf(vararg values: String): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
}
