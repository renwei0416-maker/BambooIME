// 苍竹输入法 · 文曲星（诗词提示）运行时
//
// 触发码进模式、发送键查一次、退出码退出——状态机与 AI 模式完全同一套
// （见 BambooActions.handleKey / aiSend），这里只负责「拿一段文本查词包、
// 把摘要拼到编码后面」。命中后逗号/句号直接上屏对应正文。
package com.osfans.trime.actions.wenquxing

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

import com.osfans.trime.actions.bamboo.BambooActions
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.actions.handwriting.HandwritingLog
import com.osfans.trime.actions.tips.TipEntry

object WenquxingTips {

    /** 查询后允许「自刷新」的窗口：窗口内 preedit 变化视为自己引起。 */
    private const val ADOPT_WINDOW_MS = 900L

    /** 一次最多收集多少条命中，够统计「共 N 组」用即可。 */
    private const val COLLECT_LIMIT = 99

    /** 一次最多给多少条（视口高度只决定显示几行，内容能滑到更多）。 */
    private const val MAX_ENTRIES = 60

    /** 当前可上屏的正文（顺序与显示的摘要一致）。 */
    @Volatile
    private var tips: List<String> = emptyList()

    /** 显示顺序：元素是 tips 的下标。句号轮换只改这个顺序。 */
    private var order: MutableList<Int> = mutableListOf()

    /** 本轮命中的总条数，与「显示条数」无关。 */
    private var total = 0

    /** 实时模式下上一次查的文本，避免同一个候选反复查。 */
    private var lastQuery: String? = null

    /** 每轮换一次 +1；界面据此把视口滚回第一条。 */
    var rotationSeq: Int = 0
        private set

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
        if (shownPreedit.isEmpty()) return
        clear()
        // 这里只清了数据，视图要等下一次组合事件才收；主动通知一次，立刻收掉。
        Handler(Looper.getMainLooper()).post {
            runCatching { TrimeInputMethodService.current?.refreshCandidatesPreedit() }
        }
    }

    /**
     * 查一次并准备好摘要。
     *
     * @param text 由调用方决定：正在拼写时是首选候选，否则是刚上屏的那句 / 光标前整句。
     */
    fun show(text: String, preedit: String? = null): Int {
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
        order = MutableList(total) { it }
        lastQuery = text.trim()
        shownPreedit = preedit.orEmpty()
        adoptUntil = SystemClock.uptimeMillis() + ADOPT_WINDOW_MS
        blankUntil = adoptUntil
        HandwritingLog.d("wenquxing", "hit n=$total")
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
                // 只吸收「上屏那一下」的这一次变化，吸完立刻关窗：
                // 否则连打时每次按键都只记账，块会一直挂着不消失。
                shownPreedit = preedit
                adoptUntil = 0L
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
     * 文曲星块的全部条目：一条一行，显示位置打头（①是当前第一行，轮换后重排）。
     *
     * **不按「显示条数」截断**——那个设置现在只决定视口高度，多出来的靠上下滑动看，
     * 所以这里把命中的全部给出来（安全上限 [MAX_ENTRIES]）。「共 N 组」并到最后一条后面。
     */
    fun entries(): List<TipEntry> {
        if (tips.isEmpty()) return emptyList()
        ensureOrder()
        val cap = minOf(total, MAX_ENTRIES)
        val out = ArrayList<TipEntry>(cap)
        for (i in 0 until cap) {
            val full = tips.getOrNull(order.getOrNull(i) ?: break) ?: continue
            val shown = circled(i + 1) + "\u3014" + full + "\u3015"
            // 只有 brief() 真的截断了才给展开入口
            out.add(
                TipEntry(
                    text = shown,
                    detail = shown,
                    payload = full,
                    // 第一条就是「逗号上屏」的那一条（轮换后跟着变），高亮它
                    highlighted = i == 0,
                    expandable = false, // 只有真被截断了才给展开（否则点了直接上屏）
                ),
            )
        }
        return out
    }

    /** 真实命中组数，用于在块末尾跟「共 N 组」。 */
    fun hitCount(): Int = total

    /** 顶部那行：「共 N 组」（轮换过就带上「第 M 组起」）。 */
    fun badgeText(): String? {
        if (total <= 0) return null
        val head = (order.firstOrNull() ?: 0) + 1
        val tail = if (total >= 3 && head != 1) "\u00b7 \u7b2c${head}\u7ec4\u8d77" else ""
        return "\u5171${total}\u7ec4$tail"
    }

    private fun ensureOrder() {
        if (order.size != total) order = MutableList(total) { it }
    }

    /** 圈号 1..20，超出用普通数字。编号 = 显示位置（第几条），轮换后跟着重排。 */
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
    /** 键名 → 该键在键盘上实际打出的字符（虚拟键走 text 通道，拿不到 keyCode）。 */
    private fun charOfName(name: String): Char? = when (name.lowercase()) {
        "comma" -> ','
        "period" -> '.'
        "semicolon" -> ';'
        "space" -> ' '
        "apostrophe" -> '\''
        else -> null
    }

    /**
     * 虚拟键盘的逗号/句号是 `click = "."` 这种「提交文本」键，code 为 0，
     * 所以这里按字符再认一次，逻辑与 [consumeKey] 完全一致。
     */
    fun consumeKeyText(text: String?): KeyResult {
        if (tips.isEmpty()) return KeyResult.None
        val ch = text?.takeIf { it.length == 1 }?.get(0) ?: return KeyResult.None
        val first = charOfName(WenquxingPrefs.keyFirst)
        val second = charOfName(WenquxingPrefs.keySecond)
        return when (ch) {
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
        if (total < 3) return
        if (order.size != total) order = MutableList(total) { it }
        rotationSeq++
        val head = order.removeAt(0)
        order.add(head)
        // 轮换后马上会有一次重绘，那一次不算「编码变了」
        adoptUntil = SystemClock.uptimeMillis() + ADOPT_WINDOW_MS
        blankUntil = adoptUntil
        HandwritingLog.d("wenquxing", "rotate order=$order")
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
        lastQuery = null
        shownPreedit = ""
    }
}
