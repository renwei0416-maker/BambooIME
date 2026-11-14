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

    /**
     * 打开「AI 模式」面板：所有 AI 模式 + 文曲星做成按钮，点一下就进，不用记触发码。
     *
     * 与其它 token 的区别：它要建视图，所以在
     * [com.osfans.trime.ime.keyboard.CommonKeyboardActionListener] 里就拦下来了，
     * 不会走到 [BambooActions.handleToken]（那里跑在 rime 线程上）。
     */
    const val AI_PANEL = "BambooAiPanel"

    /** 所有 token 的公共前缀；[match] 只认这个前缀，避免误吞用户真打出来的文字。 */
    const val PREFIX = "Bamboo"

    /** 打开「回退与AI」设置页。 */
    const val SETTINGS = "BambooSettings"

    /**
     * 双引号补全：提交真正的左双引号 `“`，右半边与光标居中由「符号补全」完成。
     * 不受「回退与AI」总开关影响，属于「输入增强」。
     */
    const val QUOTE = "BambooQuote"

    /** 单引号补全：提交真正的左单引号 `‘`，其余同 [QUOTE]。 */
    const val QUOTE_SINGLE = "BambooQuoteSingle"

    /**
     * 上屏提示块「当前高亮那一条」的内容：外语学习给高亮候选的英文。
     * 没有内容时吞掉这个键、什么都不做，避免误上屏。
     */
    const val TIP = "BambooTip"

    /**
     * 上屏提示块「第 N 条」（N = 屏幕上从上往下第几条，1–6）。
     *
     * 显示哪一块就取哪一块：外语学习取第 N 个候选的英文，文曲星取圈号 N 的正文；
     * 那一块没有第 N 条就吞键不动。通常绑在数字键下滑。
     */
    val TIP_SLOTS: List<String> = (1..6).map { "BambooTip$it" }

    /**
     * 按住说话（等价主题里原来的 `VoiceAssist`）。
     *
     * 绑到某个键的 `long_click` 上即可：按下开始录音（波纹、提示与 VoiceAssist 一致），
     * 松手结束；识别结果出来后，若当时在某个 AI 模式里就顺带发给它。
     *
     * 注意：长按那一支由 [com.osfans.trime.ime.keyboard.KeyView] 直接处理（提前 return），
     * 所以这个 token 只会在「点按 / 外接键盘」这类非长按路径上经过
     * [BambooActions.handleToken]。
     */
    const val VOICE_AI = "BambooVoiceAi"

    val fixed: List<String> =
        listOf(
            REVERT, CLEAR, DELETE_LINE, DELETE_WORD, AI_SEND, AI_CLIP, AI_OFF, AI_PANEL,
            SETTINGS, QUOTE, QUOTE_SINGLE, TIP, VOICE_AI,
        ) + TIP_SLOTS

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
            AI_PANEL to "打开「AI 模式」面板（点按钮选模式 / 再点一次退出）",
            SETTINGS to "打开「回退与AI」设置页",
            QUOTE to "提交左双引号“（开启「符号补全」时自动补右半边并居中）",
            QUOTE_SINGLE to "提交左单引号‘（同上）",
            TIP to "上屏当前高亮那一条的英文（外语学习；没有则不上屏）",
            VOICE_AI to "按住说话（等价 VoiceAssist）；在 AI 模式里时识别结果同时发给该模式",
        ) + TIP_SLOTS.mapIndexed { i, n -> n to "上屏提示块第 ${i + 1} 条（外语学习取英文，文曲星取圈号 ${i + 1}；没有则不上屏）" }

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
