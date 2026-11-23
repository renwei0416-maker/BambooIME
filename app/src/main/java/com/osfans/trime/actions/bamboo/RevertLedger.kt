package com.osfans.trime.actions.bamboo

/**
 * 上屏账本。
 *
 * 只记「上了什么字」，不记位置——回退时从最新的条目往前扣，扣多少由
 * 编辑器实际删掉的字符数决定（见 BambooActions.deleteCodePoints）。
 * 用户在别处手动改动文本、或在别的 App 里改过内容，账本会和屏幕不一致，
 * 这是与 Windows 版一致的已知限制。
 *
 * 所有方法都加锁，因为记账发生在主线程（commit 消息），回退发生在按键协程。
 */
object RevertLedger {
    private const val MAX_ENTRIES = 50

    private class Entry(val text: String, val codePoints: Int)

    private val entries = ArrayDeque<Entry>()

    private fun codePointCount(s: String): Int = s.codePointCount(0, s.length)

    /** 记一次上屏。空串忽略。 */
    @Synchronized
    fun record(text: String) {
        if (text.isEmpty()) return
        entries.addLast(Entry(text, codePointCount(text)))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    /** 最近一条上屏的文本，没有则空串。 */
    @Synchronized
    fun lastText(): String = entries.lastOrNull()?.text ?: ""

    /** 最近一条上屏的字符数（码点）。 */
    @Synchronized
    fun lastCodePoints(): Int = entries.lastOrNull()?.codePoints ?: 0

    /** 编辑器实际删掉了 [actual] 个码点之后，按这个数字扣账。 */
    @Synchronized
    fun consume(actual: Int) {
        var remaining = actual
        while (remaining > 0 && entries.isNotEmpty()) {
            val last = entries.last()
            when {
                last.codePoints <= remaining -> {
                    remaining -= last.codePoints
                    entries.removeLast()
                }

                else -> {
                    val keep = last.codePoints - remaining
                    var end = last.text.length
                    var n = 0
                    while (end > 0 && n < remaining) {
                        end = last.text.offsetByCodePoints(end, -1)
                        n++
                    }
                    entries.removeLast()
                    if (keep > 0) entries.addLast(Entry(last.text.substring(0, end), keep))
                    remaining = 0
                }
            }
        }
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}
