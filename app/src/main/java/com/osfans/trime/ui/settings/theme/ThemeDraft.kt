/*
 * SPDX-FileCopyrightText: 2026 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 编辑器草稿：整套样式 + 配色 + **单键覆盖**。
 *
 * 单键覆盖不写进 Theme（Theme 里的 preset_keyboards 在加载时就被抽走了），
 * 而是记成 patch，应用时把「重新序列化后的键盘 JSON」深合并进覆盖层。
 */
package com.osfans.trime.ui.settings.theme

import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.TextKeyboard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 单键定位：键盘名 + 行 + 列（列是**含 spacer** 的原始下标，便于直接改 JSON）。 */
data class KeyPath(val keyboard: String, val row: Int, val column: Int)

/**
 * 一个键被改过的字段集合。value 为 null 表示「删除该字段」＝回到继承整套配色。
 */
typealias KeyPatch = LinkedHashMap<String, JsonElement?>

class ThemeDraft(
    val themeId: String,
    var style: GeneralStyle,
    schemes: List<ColorScheme>,
    var schemeId: String,
    var keyboardName: String,
) {
    /**
     * 自己持有一份副本。
     *
     * 调用方 [com.osfans.trime.ui.main.settings.ThemeStyleFragment.refreshPreview] 的做法是
     * `draft.schemes.clear(); draft.schemes.addAll(schemes)`；若这里直接引用调用方的列表，
     * clear() 会把调用方那份也清空，addAll 再从已被清空的自己复制 → 永远为空，
     * 预览所有颜色都会解析成 TRANSPARENT（表现为「键盘完全不显示」）。
     */
    val schemes: MutableList<ColorScheme> = schemes.toMutableList()
    val patches = linkedMapOf<KeyPath, KeyPatch>()

    fun currentScheme(): ColorScheme? = schemes.firstOrNull { it.id == schemeId } ?: schemes.firstOrNull()

    fun patchOf(path: KeyPath): KeyPatch = patches[path] ?: LinkedHashMap()

    fun rawField(path: KeyPath, field: String): JsonElement? = patches[path]?.get(field)

    /** 该键该字段是否被覆盖（值可以是被删除的 null，也算覆盖）。 */
    fun hasField(path: KeyPath, field: String): Boolean = patches[path]?.containsKey(field) == true

    fun setField(path: KeyPath, field: String, value: JsonElement?) {
        val m = patches.getOrPut(path) { LinkedHashMap() }
        m[field] = value
    }

    fun clearField(path: KeyPath, field: String) {
        patches[path]?.let { it[field] = null }
    }

    fun clearAll(path: KeyPath) {
        patches.remove(path)
    }

    fun hasPatches(): Boolean = patches.values.any { m -> m.isNotEmpty() }

    /**
     * 把当前键盘的 patch 应用到一份真实 [TextKeyboard] 上。
     * 一次序列化 + 一次反序列化，之后 [com.osfans.trime.ime.keyboard.Keyboard] 会自己重算几何。
     */
    fun patchedKeyboard(original: TextKeyboard): TextKeyboard {
        val mine = patches.filterKeys { it.keyboard == keyboardName }
        if (mine.isEmpty()) return original

        // serializer 是 internal object，直接引用全限定名
        val ser = com.osfans.trime.data.theme.model.TextKeyboardSerializer
        val root = runCatching {
            Theme.json.encodeToJsonElement(ser, original).jsonObject
        }.getOrNull() ?: return original

        val rows = root["rows"]?.jsonArray ?: return original
        val rowsMut = rows.map { it.jsonObject }.toMutableList()

        for ((path, fields) in mine) {
            if (fields.isEmpty()) continue
            val rowObj = rowsMut.getOrNull(path.row) ?: continue
            val keys = rowObj["keys"]?.jsonArray ?: continue
            val keysMut = keys.map { it.jsonObject }.toMutableList()
            val keyObj = keysMut.getOrNull(path.column) ?: continue
            val m = LinkedHashMap(keyObj)
            for ((field, value) in fields) {
                if (value == null) m.remove(field) else m[field] = value
            }
            keysMut[path.column] = JsonObject(m)
            rowsMut[path.row] = JsonObject(rowObj + ("keys" to JsonArray(keysMut)))
        }

        val patched = JsonObject(root + ("rows" to JsonArray(rowsMut)))
        return runCatching { Theme.json.decodeFromJsonElement(ser, patched) }.getOrDefault(original)
    }

    /**
     * 覆盖层里要写的 preset_keyboards：**基于原始键盘完整序列化后再打补丁**。
     *
     * 不能只写「改过的键」，因为 rows/keys 是数组，缺项会被解析成空行；
     * 整份写出虽然大一点（几十 KB），但语义明确、解码安全。
     */
    fun keyboardJsonFor(base: TextKeyboard): JsonElement? {
        if (patches.keys.none { it.keyboard == keyboardName }) return null
        val patched = patchedKeyboard(base)
        return runCatching {
            Theme.json.encodeToJsonElement(
                com.osfans.trime.data.theme.model.TextKeyboardSerializer,
                patched,
            )
        }.getOrNull()
    }
}
