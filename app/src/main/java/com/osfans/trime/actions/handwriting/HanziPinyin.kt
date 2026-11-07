package com.osfans.trime.actions.handwriting

import android.content.Context
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 手写候选的读音（带声调）。
 *
 * 数据：`assets/handwriting/hanzi_pinyin.tsv`（26,785 行，`字<TAB>拼音`，多音字用 `/` 分隔）。
 * 模型字表内的 7,185 个汉字全部有读音；生僻字库里的 16,176 个字也几乎都有。
 *
 * 读法：整表解析成按码位升序的两个数组，查询走二分；解析放在后台线程，没读完就先不显示注音。
 */
object HanziPinyin {
    private const val ASSET = "handwriting/hanzi_pinyin.tsv"

    private class Table(val codePoints: IntArray, val readings: Array<String>) {
        fun lookup(codePoint: Int): String? {
            val index = codePoints.binarySearch(codePoint)
            return if (index >= 0) readings[index] else null
        }
    }

    @Volatile
    private var table: Table? = null

    private val loading = AtomicBoolean(false)

    /** 读音表就绪的代数，界面用它判断是否需要重绘。 */
    @Volatile
    var revision: Int = 0
        private set

    fun load(context: Context) {
        if (table != null || !loading.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread(name = "bamboo-pinyin", isDaemon = true) {
            try {
                table = readTable(app)
                revision++
            } catch (e: Throwable) {
                Timber.w(e, "handwriting: pinyin table load failed")
                loading.set(false)
            }
        }
    }

    /** 查一个字的读音；多音字只取第一个读音（避免一行挤三四个音节）。 */
    fun of(char: Char): String? {
        val reading = table?.lookup(char.code) ?: return null
        return reading.substringBefore('/')
    }

    /** 给候选显示用：多字候选逐字拼读音，字与字之间不留空格。 */
    fun ofText(text: String): String? {
        if (text.isEmpty() || table == null) return null
        val builder = StringBuilder()
        for (ch in text) {
            val reading = of(ch) ?: return null
            builder.append(reading)
        }
        return builder.toString().ifEmpty { null }
    }

    private fun readTable(context: Context): Table {
        val pairs = ArrayList<Pair<Int, String>>(28000)
        context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.isEmpty()) return@forEach
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab == line.length - 1) return@forEach
                val char = line.substring(0, tab)
                if (char.codePointCount(0, char.length) != 1) return@forEach
                pairs.add(char.codePointAt(0) to line.substring(tab + 1))
            }
        }
        pairs.sortBy { it.first }
        val codePoints = IntArray(pairs.size) { pairs[it].first }
        val readings = Array(pairs.size) { pairs[it].second }
        return Table(codePoints, readings)
    }
}
