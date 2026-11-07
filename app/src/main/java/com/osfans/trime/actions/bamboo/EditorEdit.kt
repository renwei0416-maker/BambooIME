/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.bamboo

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.osfans.trime.ime.core.TrimeInputMethodService
import java.text.BreakIterator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 编辑区整块编辑：清空、删到行首、删一个词。
 *
 * 主题里原本是「连发 N 次按键」凑出来的（`Shift+Home` ×10 再退格、`SelectAll` ×5 再退格 ×19），
 * 会随编辑器不同而随机失败：全选走的是 `performContextMenuAction`，是**异步**投递给编辑器的，
 * 紧跟其后的退格常常赶在选区生效之前；而连发次数又是猜的，行短就多选、行长就删不完。
 *
 * 这里改成**直接调 InputConnection**：先读出光标前后真实文本的长度，再用
 * `deleteSurroundingText` 一次删到目标位置，整批用 `beginBatchEdit/endBatchEdit` 包住。
 * 只有编辑器明确不配合时，才退回一次按键序列（`Shift+Home`+`Del`、`Ctrl+BackSpace`、全选+空串替换）。
 *
 * 线程：所有方法内部切到主线程，必须在 rime 线程之外（`postKeyJob` 的挂起函数）调用。
 */
object EditorEdit {
    /** 单次读取的窗口上限；超出的部分靠多轮循环继续删。 */
    private const val SCAN = 64_000

    /** 防御性上限：正常一次就够，这个只是防止编辑器死循环不响应。 */
    private const val MAX_ROUNDS = 32

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 清空编辑区：光标前、光标后全部删掉。 */
    suspend fun clearAll(service: TrimeInputMethodService): Boolean =
        onEditor(service) { ic ->
            var touched = false
            repeat(MAX_ROUNDS) {
                val before = textBefore(ic, SCAN) ?: return@onEditor fallbackClear(ic)
                val after = textAfter(ic, SCAN) ?: return@onEditor fallbackClear(ic)
                if (before.isEmpty() && after.isEmpty()) return@onEditor true

                delete(ic, before.length, after.length)

                val nb = textBefore(ic, SCAN)
                val na = textAfter(ic, SCAN)
                val shrunk =
                    (nb != null && nb.length < before.length) ||
                        (na != null && na.length < after.length)
                if (!shrunk) {
                    // 编辑器没理这次删除：没删过东西就整体降级，删过一部分就当成功
                    return@onEditor if (touched) true else fallbackClear(ic)
                }
                touched = true
                if (nb.isNullOrEmpty() && na.isNullOrEmpty()) return@onEditor true
            }
            touched
        }

    /** 删到行首；光标已经在行首时，把上一行的换行删掉（并入上一行）。 */
    suspend fun deleteLine(service: TrimeInputMethodService): Boolean =
        onEditor(service) { ic ->
            val before = textBefore(ic, SCAN)
            if (before != null && before.isNotEmpty()) {
                val nl = before.lastIndexOf('\n')
                val del = if (nl >= 0) before.length - nl - 1 else before.length
                if (del > 0 && delete(ic, del, 0)) return@onEditor true
                if (del == 0 && nl >= 0 && delete(ic, 1, 0)) return@onEditor true
            }
            fallbackLine(ic)
        }

    /** 删掉光标前的一个词（连同词后面的空白一起删）。 */
    suspend fun deleteWord(service: TrimeInputMethodService): Boolean =
        onEditor(service) { ic ->
            val before = textBefore(ic, SCAN)
            if (before != null && before.isNotEmpty()) {
                var end = before.length
                while (end > 0 && before[end - 1].isWhitespace()) end--
                val start =
                    if (end == 0) {
                        0
                    } else {
                        runCatching {
                            BreakIterator.getWordInstance().apply { setText(before.toString()) }.preceding(end)
                        }.getOrDefault(0)
                    }
                val del = before.length - start.coerceAtLeast(0)
                if (del > 0 && delete(ic, del, 0)) return@onEditor true
            }
            fallbackWord(ic)
        }

    // ------------------------------------------------------------------ 内部

    /**
     * 统一入口：切主线程、挡掉密码框、收掉组合区后把 [block] 交给 InputConnection。
     * 拿不到连接、或是密码输入框时直接返回 false（**绝不**在密码框里做删除动作）。
     */
    private suspend fun onEditor(
        service: TrimeInputMethodService,
        block: (InputConnection) -> Boolean,
    ): Boolean =
        withTimeoutOrNull(6000) {
            withContext(Dispatchers.Main) {
                val ic = service.currentInputConnection ?: return@withContext false
                if (isPasswordInput(service.currentInputEditorInfo)) return@withContext false
                // 组合区还在时先收掉，否则删完可能被重新插回来
                runCatching { ic.finishComposingText() }
                block(ic)
            }
        } ?: false

    private fun textBefore(
        ic: InputConnection,
        n: Int,
    ): CharSequence? = runCatching { ic.getTextBeforeCursor(n, 0) }.getOrNull()

    private fun textAfter(
        ic: InputConnection,
        n: Int,
    ): CharSequence? = runCatching { ic.getTextAfterCursor(n, 0) }.getOrNull()

    /** 一次删除 [before] 个 UTF-16 单元（光标左侧）和 [after] 个（右侧）。 */
    private fun delete(
        ic: InputConnection,
        before: Int,
        after: Int,
    ): Boolean {
        if (before <= 0 && after <= 0) return false
        return runCatching {
            ic.beginBatchEdit()
            try {
                ic.deleteSurroundingText(before, after)
            } finally {
                runCatching { ic.endBatchEdit() }
            }
        }.getOrDefault(false)
    }

    /**
     * 降级：全选 → 用空串替换选区。
     * 只在编辑器不支持 `deleteSurroundingText` 时才会走到（部分 WebView / 自绘编辑器）。
     */
    private fun fallbackClear(ic: InputConnection): Boolean {
        val selected = runCatching { ic.performContextMenuAction(android.R.id.selectAll) }.getOrDefault(false)
        if (!selected) return false
        // 全选是异步投递的，等编辑器处理完再替换，否则会把空串插到光标处
        mainHandler.postDelayed({ runCatching { ic.commitText("", 1) } }, 80)
        return true
    }

    /** 降级：`Shift+Home` 选到行首，再按一次 Del（只发一遍，不连发）。 */
    private fun fallbackLine(ic: InputConnection): Boolean {
        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_HOME, 0, KeyEvent.META_SHIFT_ON)
        val up = KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_HOME, 0, KeyEvent.META_SHIFT_ON)
        val sent = runCatching { ic.sendKeyEvent(down) && ic.sendKeyEvent(up) }.getOrDefault(false)
        if (sent) mainHandler.postDelayed({ sendKey(ic, KeyEvent.KEYCODE_DEL) }, 60)
        return sent
    }

    /** 降级：`Ctrl+BackSpace`。 */
    private fun fallbackWord(ic: InputConnection): Boolean =
        runCatching {
            sendKey(ic, KeyEvent.KEYCODE_DEL, KeyEvent.META_CTRL_ON)
            true
        }.getOrDefault(false)

    private fun sendKey(
        ic: InputConnection,
        code: Int,
        meta: Int = 0,
    ) {
        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun isPasswordInput(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

            else -> false
        }.also { if (it) Timber.d("bamboo: editor is password, skip") }
    }
}
