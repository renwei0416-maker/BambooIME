package com.osfans.trime.actions.bamboo

import android.view.KeyEvent

/**
 * 快捷组合键的解析与匹配。
 *
 * 语法：`ctrl+alt+;`、`shift+space`、`Control+semicolon` —— 修饰符用 `+` 连接，
 * 最后一个 token 是主键。修饰符大小写不敏感，支持 ctrl/control、alt、shift、lock(caps)。
 *
 * 软键盘侧同一个组合键的写法是主题里的 `send:`，例如 `send: "Control+semicolon"`；
 * 两者解析出来的 (keyCode, metaState) 是一致的，所以这里能用同一套匹配。
 */
object KeyCombo {
    private val charCodes =
        mapOf(
            ';' to KeyEvent.KEYCODE_SEMICOLON,
            ':' to KeyEvent.KEYCODE_SEMICOLON,
            ',' to KeyEvent.KEYCODE_COMMA,
            '.' to KeyEvent.KEYCODE_PERIOD,
            '/' to KeyEvent.KEYCODE_SLASH,
            '\'' to KeyEvent.KEYCODE_APOSTROPHE,
            '-' to KeyEvent.KEYCODE_MINUS,
            '=' to KeyEvent.KEYCODE_EQUALS,
            '[' to KeyEvent.KEYCODE_LEFT_BRACKET,
            ']' to KeyEvent.KEYCODE_RIGHT_BRACKET,
            '\\' to KeyEvent.KEYCODE_BACKSLASH,
            '`' to KeyEvent.KEYCODE_GRAVE,
            ' ' to KeyEvent.KEYCODE_SPACE,
        )

    private val nameCodes =
        mapOf(
            "semicolon" to KeyEvent.KEYCODE_SEMICOLON,
            "comma" to KeyEvent.KEYCODE_COMMA,
            "period" to KeyEvent.KEYCODE_PERIOD,
            "slash" to KeyEvent.KEYCODE_SLASH,
            "apostrophe" to KeyEvent.KEYCODE_APOSTROPHE,
            "minus" to KeyEvent.KEYCODE_MINUS,
            "equals" to KeyEvent.KEYCODE_EQUALS,
            "bracketleft" to KeyEvent.KEYCODE_LEFT_BRACKET,
            "bracketright" to KeyEvent.KEYCODE_RIGHT_BRACKET,
            "backslash" to KeyEvent.KEYCODE_BACKSLASH,
            "grave" to KeyEvent.KEYCODE_GRAVE,
            "space" to KeyEvent.KEYCODE_SPACE,
            "enter" to KeyEvent.KEYCODE_ENTER,
            "return" to KeyEvent.KEYCODE_ENTER,
            "tab" to KeyEvent.KEYCODE_TAB,
            "escape" to KeyEvent.KEYCODE_ESCAPE,
            "backspace" to KeyEvent.KEYCODE_DEL,
            "delete" to KeyEvent.KEYCODE_FORWARD_DEL,
            "up" to KeyEvent.KEYCODE_DPAD_UP,
            "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        )

    data class Parsed(
        val ctrl: Boolean,
        val alt: Boolean,
        val shift: Boolean,
        val lock: Boolean,
        val keyCode: Int,
    )

    private fun keyCodeOf(token: String): Int? {
        if (token.length == 1) {
            val c = token[0]
            charCodes[c]?.let { return it }
            if (c in '0'..'9') return KeyEvent.KEYCODE_0 + (c - '0')
            if (c in 'a'..'z') return KeyEvent.KEYCODE_A + (c - 'a')
            if (c in 'A'..'Z') return KeyEvent.KEYCODE_A + (c - 'A')
            return null
        }
        val lower = token.lowercase()
        nameCodes[lower]?.let { return it }
        // 兜底：交给平台的键名解析（KEYCODE_SEMICOLON 这类写法）
        val byName =
            runCatching { KeyEvent.keyCodeFromString(lower.uppercase()) }.getOrDefault(0)
        if (byName != 0) return byName
        val prefixed =
            runCatching { KeyEvent.keyCodeFromString("KEYCODE_" + lower.uppercase()) }
                .getOrDefault(0)
        return if (prefixed != 0) prefixed else null
    }

    /** 解析失败返回 null（调用方据此提示用户写法不对）。 */
    fun parse(spec: String): Parsed? {
        val raw = spec.trim()
        if (raw.isEmpty()) return null
        val tokens = raw.split('+').map { it.trim() }
        if (tokens.any { it.isEmpty() }) return null
        var ctrl = false
        var alt = false
        var shift = false
        var lock = false
        for (t in tokens.dropLast(1)) {
            when (t.lowercase()) {
                "ctrl", "control", "ctl" -> ctrl = true
                "alt", "option" -> alt = true
                "shift" -> shift = true
                "lock", "caps", "capslock" -> lock = true
                else -> return null
            }
        }
        val keyCode = keyCodeOf(tokens.last()) ?: return null
        return Parsed(ctrl, alt, shift, lock, keyCode)
    }

    /** 按下的是不是这个组合键（修饰键必须完全一致，多一个少一个都不算）。 */
    fun matches(
        spec: String,
        keyCode: Int,
        metaState: Int,
    ): Boolean {
        val p = parse(spec) ?: return false
        if (p.keyCode != keyCode) return false
        val ctrl = metaState and KeyEvent.META_CTRL_ON != 0
        val alt = metaState and KeyEvent.META_ALT_ON != 0
        val shift = metaState and KeyEvent.META_SHIFT_ON != 0
        val meta = metaState and KeyEvent.META_META_ON != 0
        val lock = metaState and KeyEvent.META_CAPS_LOCK_ON != 0
        if (meta) return false
        return ctrl == p.ctrl && alt == p.alt && shift == p.shift && lock == p.lock
    }
}
