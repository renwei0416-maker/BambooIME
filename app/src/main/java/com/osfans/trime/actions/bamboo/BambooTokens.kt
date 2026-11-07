package com.osfans.trime.actions.bamboo

/**
 * 可以被主题按键直接绑定的「苍竹动作名」（token）。
 *
 * 用法：在主题 Lua 里把某个键的动作字段写成这些名字，例如
 * ```lua
 * key(merge(S.backspace, key { click = "del", swipe_right = "BambooRevert" }))
 * ```
 * 解析路径：主题 → [com.osfans.trime.ime.keyboard.KeyAction]（解析不出键码时变成 code=0 的纯文本）
 * → `CommonKeyboardActionListener.onAction` → 这里命中 → [BambooActions.handleToken]。
 *
 * 名字**大小写不敏感**（`bamboorevert` 也认）。
 */
object BambooTokens {
    /** 回退最近一笔上屏，语义与敲 `qq/` 完全一致。 */
    const val REVERT = "BambooRevert"

    /** 清空编辑区（光标前后全删）。 */
    const val CLEAR = "BambooClear"

    /** 删到行首（行首时并入上一行）。 */
    const val DELETE_LINE = "BambooDeleteLine"

    /** 删掉光标前的一个词。 */
    const val DELETE_WORD = "BambooDeleteWord"

    /** 用当前 AI 模式发送（等价双击发送键）。 */
    const val AI_SEND = "BambooAiSend"

    /** 用当前 AI 模式发送剪贴板内容（等价 `jt;`）。 */
    const val AI_CLIP = "BambooAiClip"

    /** 退出 AI 模式（等价 `tc;`）。 */
    const val AI_OFF = "BambooAiOff"

    /** 所有 token 的公共前缀；[match] 只认这个前缀，避免误吞用户真打出来的文字。 */
    const val PREFIX = "Bamboo"

    /** 打开「苍竹增强」设置页。 */
    const val SETTINGS = "BambooSettings"

    val fixed: List<String> =
        listOf(REVERT, CLEAR, DELETE_LINE, DELETE_WORD, AI_SEND, AI_CLIP, AI_OFF, SETTINGS)

    /** 设置页「动作名一览」用的说明表；与 [fixed] 同源，不会漏项。 */
    private val docMap: Map<String, String> =
        mapOf(
            REVERT to "回退最近一笔上屏（等价敲 qq/）",
            CLEAR to "清空编辑区（光标前后全删）",
            DELETE_LINE to "删到行首；已在行首时并入上一行",
            DELETE_WORD to "删掉光标前的一个词",
            AI_SEND to "用当前 AI 模式发送（等价双击发送键）",
            AI_CLIP to "用当前 AI 模式发送剪贴板（等价 jt;）",
            AI_OFF to "退出 AI 模式（等价 tc;）",
            SETTINGS to "打开「苍竹增强」设置页",
        )

    /** 名字 → 说明，与 [fixed] 同源。 */
    fun docRows(): List<Pair<String, String>> = fixed.map { it to (docMap[it] ?: "") }

    /**
     * 判断一个动作文本是不是苍竹 token；是则返回规范形态，否则 `null`。
     *
     * 只认 `Bamboo` 开头的名字，避免把普通文字（用户真打出来的 "bamboo…"）误吞。
     */
    fun match(text: String): String? {
        if (text.isEmpty()) return null
        val t = text.trim()
        if (t.length <= PREFIX.length) return null
        if (!t.startsWith(PREFIX, ignoreCase = true)) return null
        fixed.forEach { name -> if (t.equals(name, ignoreCase = true)) return name }
        return null
    }
}
