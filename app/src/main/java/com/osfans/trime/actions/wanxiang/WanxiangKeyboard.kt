package com.osfans.trime.actions.wanxiang

/**
 * 方案 id + 键盘形态 -> 主题里的键盘 id。
 *
 * 只在我们自己的方案上生效：别的方案（或用户自带包）一律返回 null，绝不插手。
 *
 * 为什么需要它：`KeyboardWindow.smartMatchKeyboard()` 重建键盘时，
 * 只有当「方案 id 恰好等于主题 preset_keyboards 的键名」才会保持，
 * 而 18/14/26 键用的都是同一个方案 `wanxiang`，主题无从区分，
 * 于是每次都会掉回 `default`（26 键）。
 */
object WanxiangKeyboard {

    /** 我们内置/下载包里的方案 id。 */
    private val SCHEMAS = setOf(
        "wanxiang",        // Base 26/18/14 键
        "wanxiang_t9",     // Base 九宫格
        "wanxiang_pro",    // Pro 26/18/14 键
        "wanxiang_t9_pro", // Pro 九宫格
    )

    /** 这个方案归我们管吗。 */
    fun owns(schemaId: String): Boolean = schemaId in SCHEMAS

    /**
     * 该用的键盘 id；不归我们管时返回 null。
     *
     * @param schemaId 当前方案 id
     * @param layout [WanxiangPrefs.layout]
     */
    fun keyboardIdOf(schemaId: String, layout: Int): String? {
        if (!owns(schemaId)) return null
        return when (layout) {
            WanxiangPrefs.LAYOUT_18 -> "wanxiang_18key"
            WanxiangPrefs.LAYOUT_14 -> "wanxiang_14key"
            WanxiangPrefs.LAYOUT_9 -> "wanxiang_t9"
            else -> "default"
        }
    }
}
