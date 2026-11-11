// 苍竹输入法 · 文曲星（诗词提示）运行时
//
// 触发码进模式、发送键查一次、退出码退出——状态机与 AI 模式完全同一套
// （见 BambooActions.handleKey / aiSend），这里只负责「拿一段文本查词包、
// 把摘要拼到编码后面」。命中后逗号/句号直接上屏对应正文。
package com.osfans.trime.actions.wenquxing

import android.os.SystemClock
import android.view.KeyEvent

import com.osfans.trime.actions.bamboo.BambooActions
import com.osfans.trime.actions.handwriting.HandwritingLog

object WenquxingTips {

    /** 查询后允许「自刷新」的窗口：窗口内 preedit 变化视为自己引起。 */
    private const val ADOPT_WINDOW_MS = 900L

    /** 一次最多收集多少条命中，够统计「共 N 组」用即可。 */
    private const val COLLECT_LIMIT = 99

    /** 当前可上屏的正文（顺序与显示的摘要一致）。 */
    @Volatile
    private var tips: List<String> = emptyList()

    /** 显示顺序：元素是 tips 的下标。句号轮换只改这个顺序。 */
    private var order: MutableList<Int> = mutableListOf()

    /** 本轮命中的总条数，与「显示条数」无关。 */
    private var total = 0

    /** 这次提示是不是「没有编码行、自己占一行」显示的；轮换时要按同样方式重画。 */
    private var tipOnly = false

    @Volatile
    private var shown: CharSequence = ""

    /** 实时模式下上一次查的文本，避免同一个候选反复查。 */
    private var lastQuery: String? = null

    /** 上一次查询时的编码，编码一变就把旧提示收掉。 */
    private var shownPreedit: String = ""

    /**
     * 刚查询完的一小段时间内，编码行的重绘是自己触发的（刷新组合），
     * 这期间的 preedit 变化不算「用户改了编码」，直接采信、不清提示。
     */
    private var adoptUntil: Long = 0L

    /** 空组合的宽限期：显示层刷新时可能短暂给出空组合，这段时间不算「用户清空了编码」。 */
    private var blankUntil: Long = 0L

    /**
     * 组合被清空（ESC、上屏、外部清组合）时调用。
     * 只有「挂在编码后面」的提示才跟着清；剪贴板那种自己占一行的提示不受影响。
     */
    fun onCompositionCleared() {
        if (shownPreedit.isNotEmpty()) clear()
    }

    /** 给 PreeditUi 用：要追加到编码后面的提示文本。 */
    fun suffix(): CharSequence = shown

    /** 自己占一行时返回这一行要显示的内容，否则 null（表示追加到编码后面）。 */
    fun ownLineText(): String? =
        if (tipOnly && shown.isNotEmpty()) shown.toString() else null

    /**
     * 查一次并准备好摘要。
     *
     * @param text 由调用方决定：正在拼写时是首选候选，否则是刚上屏的那句 / 光标前整句。
     */
    fun show(text: String, preedit: String? = null, ownLine: Boolean = false): Int {
        if (!WenquxingPrefs.enabled) return 0
        val keys = keysOf(text)
        val texts = ArrayList<String>()
        // 命中都算进去（共 N 组要真实），显示时再按「显示条数」截断
        for (key in keys) {
            HandwritingLog.d("wenquxing", "query key=$key")
            val hits = WenquxingStore.query(key) { name -> WenquxingPrefs.isPackEnabled(name) }
            HandwritingLog.d("wenquxing", "hit n=${hits.size}")
            for ((_, hit) in hits) {
                if (hit.text.isEmpty() || hit.text in texts) continue
                texts.add(hit.text)
                if (texts.size >= COLLECT_LIMIT) break
            }
            if (texts.size >= COLLECT_LIMIT) break
        }
        // 正文最长的排第一（同长度保持原顺序）：整首比摘录更完整，优先给出。
        tips = texts.sortedByDescending { it.length }
        total = tips.size
        tipOnly = ownLine
        order = MutableList(total) { it }
        lastQuery = text.trim()
        shownPreedit = preedit.orEmpty()
        adoptUntil = SystemClock.uptimeMillis() + ADOPT_WINDOW_MS
        blankUntil = adoptUntil
        shown = render()
        HandwritingLog.d("wenquxing", "shown=\"$shown\" n=$total")
        return total
    }

    /**
     * PreeditUi 每次刷新编码时调用。
     *
     * 实时模式按候选重查；编码清空就把提示收掉（此时 preedit 也不显示了）。
     */
    fun onComposition(preedit: String?, candidate: String?) {
        if (preedit.isNullOrBlank()) {
            // 刚查完的一小段时间内，空组合可能只是刷新过程，不当成「用户清空了编码」
            if (SystemClock.uptimeMillis() >= blankUntil) clear()
            return
        }
        if (!WenquxingPrefs.enabled) return
        // 任何模式都一样：编码一变先收掉上一次的结果，避免它留在新编码后面。
        // 自己刚引发的重绘（adoptUntil 之内）不算编码变动。
        if (tips.isNotEmpty() && preedit != shownPreedit) {
            if (SystemClock.uptimeMillis() < adoptUntil) {
                shownPreedit = preedit
            } else {
                HandwritingLog.d("wenquxing", "clear by preedit change")
                clear()
            }
        }
        if (!autoQuery()) return
        val text = candidate?.trim().orEmpty()
        if (text.length < 2 || text == lastQuery) return
        show(text, preedit)
    }

    /** 编码一变就查的两种情形：常开模式，或「触发码 + 直接显示」且已经进了模式。 */
    private fun autoQuery(): Boolean = when (WenquxingPrefs.mode) {
        WenquxingPrefs.MODE_ALWAYS -> true
        WenquxingPrefs.MODE_TRIGGER_AUTO -> BambooActions.isTipsArmed()
        else -> false
    }

    /** 查询键：整段 + 按行拆开的每一段，都短于 2 字的丢掉。 */
    private fun keysOf(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        val whole = raw.trim()
        if (whole.length >= 2) out.add(whole)
        if (whole.contains('\n')) {
            for (line in whole.split('\n')) {
                val l = line.trim()
                if (l.length >= 2) out.add(l)
            }
        }
        return out.toList()
    }

    /**
     * 按当前顺序渲染提示：「显示条数」只限制括号个数，「共 N 组」用真实命中数。
     * 圈号是「条目编号」（tips 里的下标 + 1），轮换只换位置、不改编号。
     */
    private fun render(): CharSequence {
        if (tips.isEmpty()) return ""
        ensureOrder()
        val len = WenquxingPrefs.displayLen
        val cap = minOf(total, WenquxingPrefs.maxDisplay)
        return buildString {
            for (i in 0 until cap) {
                append(circled(order[i] + 1)).append("〔").append(brief(tips[order[i]], len)).append("〕")
            }
            if (total > 1) append("共").append(total).append("组")
        }
    }

    private fun ensureOrder() {
        if (order.size != total) order = MutableList(total) { it }
    }

    /** 圈号 1..20，超出用普通数字。编号跟着条目走，只用于显示，上屏走 tips 原文。 */
    private fun circled(n: Int): String =
        if (n in 1..20) (0x2460 + n - 1).toChar().toString() else n.toString()

    private fun brief(text: String, len: Int): String {
        val one = text.replace('\n', ' ').trim()
        return if (one.length <= len) one else one.substring(0, len) + "…"
    }

    /** 上屏键的三种结果：上屏正文 / 只换了显示顺序 / 不处理。 */
    sealed interface KeyResult {
        data class Commit(val text: String) : KeyResult

        /** 已经轮换并重算过提示，调用方只需重绘编码行。 */
        object Rotated : KeyResult

        object None : KeyResult
    }

    /**
     * 上屏键命中时的行为：
     * 第一档键上屏当前第一条；第二档键在两条时上屏第二条，三条以上只轮换顺序，
     * 一条时既不轮换也不上屏（按键按普通标点走）。
     */
    /**
     * 物理键盘预筛用：当前有提示，且这个键是「第一档 / 第二档上屏键」。
     *
     * 键名随设置实时变化（见 [WenquxingPrefs.keyFirst] / [WenquxingPrefs.keySecond]），
     * 所以设置里改完不用重启。
     */
    fun isTipsKey(keyCode: Int): Boolean {
        if (tips.isEmpty()) return false
        return keyCode == codeOf(WenquxingPrefs.keyFirst) || keyCode == codeOf(WenquxingPrefs.keySecond)
    }

    fun consumeKey(keyCode: Int): KeyResult {
        if (tips.isEmpty()) return KeyResult.None
        val first = codeOf(WenquxingPrefs.keyFirst)
        val second = codeOf(WenquxingPrefs.keySecond)
        return when (keyCode) {
            first -> KeyResult.Commit(tips[order[0]])
            second -> when {
                total < 2 -> KeyResult.None
                total == 2 -> KeyResult.Commit(tips[order[1]])
                else -> {
                    rotateLeft()
                    KeyResult.Rotated
                }
            }
            else -> KeyResult.None
        }
    }

    /** 整个顺序循环左移一位：1 2 3 4 5 -> 2 3 4 5 1，按 N 次回到原样。 */
    private fun rotateLeft() {
        if (order.size != total || total < 3) return
        val head = order.removeAt(0)
        order.add(head)
        shown = render()
        // 轮换后马上会有一次重绘，那一次不算「编码变了」
        adoptUntil = SystemClock.uptimeMillis() + ADOPT_WINDOW_MS
        blankUntil = adoptUntil
        HandwritingLog.d("wenquxing", "rotate order=$order shown=\"$shown\"")
    }

    private fun codeOf(name: String): Int = when (name.lowercase()) {
        "comma" -> KeyEvent.KEYCODE_COMMA
        "period" -> KeyEvent.KEYCODE_PERIOD
        "semicolon" -> KeyEvent.KEYCODE_SEMICOLON
        "space" -> KeyEvent.KEYCODE_SPACE
        "apostrophe" -> KeyEvent.KEYCODE_APOSTROPHE
        else -> 0
    }

    fun clear() {
        tips = emptyList()
        order = mutableListOf()
        total = 0
        tipOnly = false
        shown = ""
        lastQuery = null
        shownPreedit = ""
    }
}
