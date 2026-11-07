package com.osfans.trime.actions.bamboo

/**
 * AI 模式的提示词表。
 *
 * 与 rime-actions 的 `actions/ai/prompts/<名字>` 完全对应：名字就是模式名，
 * 名字为空（`@ai`）时用 `default`。这里的 8 套是默认值，用户可在设置里逐条改，
 * 也可以新增模式（新快捷码 + 新提示词）。
 */
object AiPrompts {
    const val DEFAULT_NAME = "default"

    val builtin: Map<String, String> =
        linkedMapOf(
            "default" to
                "你是中文输入助手。如果是问题就进行精准简短的回复，如果是诗词或名句就回复原文和作者等。" +
                "只输出结果正文，不要解释、不要提问、不加引号。每一次接收到的内容，单独回复不要理会之前提交的内容。",
            "eng" to
                "Translate the user's Chinese text into natural, idiomatic English. " +
                "Output only the translation, no explanations, no quotes.",
            "jp" to "把用户的中文翻译成自然、地道的日语。只输出译文，不要解释，不要加引号。",
            "polish" to
                "你是中文润色助手。把用户给你的文字改得更通顺自然，保留原意和语气，不增删信息。" +
                "只输出改写后的正文，不要解释、不要加引号。",
            "zh" to "把用户给你的外文翻译成自然、通顺的简体中文。只输出译文，不要解释，不要加引号。",
            "proofread" to
                "你是中文校对。只改错别字、标点和语病，尽量保留原句，不要改写风格。原文没有问题时原样输出。" +
                "只输出校正后的正文，不要解释、不要列出改动。",
            "expand" to
                "把用户给你的文字扩写到原来的两倍左右，可以补充合理的细节和过渡，但不要编造事实、不要改变原意。" +
                "只输出扩写后的正文，不要解释、不要加引号。",
            "reply" to
                "用户会给你别人说过的一段话。结合上文写一条得体的回复，语气按原话的场合来定，简短直接，不要客套堆砌。" +
                "只输出回复正文，不要解释、不要加引号。",
        )

    /** 默认快捷码：触发码 = 前缀 + 终结键，例如 `rs;`。 */
    val defaultCodes: Map<String, String> =
        linkedMapOf(
            "default" to "ai;",
            "eng" to "eg;",
            "jp" to "jp;",
            "polish" to "rs;",
            "zh" to "fy;",
            "proofread" to "jd;",
            "expand" to "kx;",
            "reply" to "hf;",
        )

    fun codeOf(name: String): String = defaultCodes[name] ?: "${name.take(2)};"

    /** 内置模式的显示名（模式提示），用户可在设置里改。 */
    val displayNames: Map<String, String> =
        linkedMapOf(
            "default" to "默认",
            "eng" to "英文翻译",
            "jp" to "日文翻译",
            "polish" to "润色",
            "zh" to "中文翻译",
            "proofread" to "校对",
            "expand" to "扩写",
            "reply" to "回复",
        )

    fun displayNameOf(name: String): String = displayNames[name] ?: name

    fun promptOf(name: String): String = builtin[name] ?: builtin.getValue(DEFAULT_NAME)
}
