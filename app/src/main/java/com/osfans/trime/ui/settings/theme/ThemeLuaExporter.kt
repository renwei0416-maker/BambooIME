/*
 * SPDX-FileCopyrightText: 2026 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 把一份 Theme 序列化成「自包含的完整 .lua」。
 *
 * 主题源本身就是 .lua（nativeLoadTheme 跑 chunk 取返回的纯表转 JSON），
 * Gboard.lua 里的 theme{}/style{}/safe_require() 都只是建普通表的糖。
 * 因此把 effective theme 的 JSON 直接写成 `return { ... }` 纯表，就是一个
 * 能独立安装、与当前覆盖层叠加结果完全等价的主题文件。
 */
package com.osfans.trime.ui.settings.theme

import com.osfans.trime.data.theme.Theme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ThemeLuaExporter {

    /** 生成一个自包含 Lua chunk：`return { name = ..., ... }`。 */
    fun buildLua(theme: Theme): String {
        val json = Theme.json.encodeToJsonElement(Theme.serializer(), theme)
        return "return " + elementToLua(json)
    }

    private fun elementToLua(e: JsonElement): String =
        when (e) {
            is JsonObject -> objectToLua(e)
            is JsonArray -> arrayToLua(e)
            is JsonPrimitive -> primitiveToLua(e)
            JsonNull -> "nil"
        }

    private fun objectToLua(o: JsonObject): String {
        if (o.isEmpty()) return "{}"
        return o.entries
            .filter { it.value !is JsonNull }
            .joinToString(prefix = "{ ", postfix = " }") { (k, v) ->
                "${luaKey(k)} = ${elementToLua(v)}"
            }
    }

    private fun arrayToLua(a: JsonArray): String =
        if (a.isEmpty()) "{}" else a.joinToString(prefix = "{ ", postfix = " }") { elementToLua(it) }

    private fun primitiveToLua(p: JsonPrimitive): String =
        if (p.isString) quote(p.content) else p.content

    /** 合法标识符且非 Lua 关键字则裸写，否则转 `["..."]`。 */
    private fun luaKey(k: String): String =
        if (IDENTIFIER.matches(k) && k !in LUA_KEYWORDS) k else "[${quote(k)}]"

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private val LUA_KEYWORDS = setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function",
        "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return", "then",
        "true", "until", "while",
    )
}
