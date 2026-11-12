/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.glossary

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

/**
 * 候选英文词库（`.bge`）的只读 mmap 读取器。
 *
 * 格式由 `tools/glossary_pack.py` 生成，全部小端：
 * ```
 * 0   4  magic 'BGE1'
 * 4   2  version
 * 6   2  flags
 * 8   4  lang
 * 12  4  entryCount
 * 16  4  senseCount
 * 20  4  offKeys     entryCount × 12，按 key 的 UTF-8 字节升序
 * 24  4  offRecs     senseCount × 10，义项在 arena 里的偏移/长度/词性/标签
 * 28  4  offArena    key 与义项文本共用的字符串区
 * 32  4  arenaSize
 * 36  4  dataSum
 * 40  8  buildTime
 * ```
 * KEY：`u32 keyOff / u16 keyLen / u32 recStart / u16 senseCount`
 * REC：`u32 textOff / u16 textLen / u8 pos / u8 label1 / u8 label2 / u8 rsv`
 *
 * 文件只读、不建全量 HashMap，靠二分查找定位，单次查询 1–3 µs，
 * 内存占用只是 mmap 的虚拟地址（115 万条也就 8 MB 文件）。
 */
class GlossaryPack private constructor(
    val file: File,
    private val buf: MappedByteBuffer,
) : AutoCloseable {
    /** 词条数。 */
    val entryCount: Int

    /** 义项数（一个词可以有多条）。 */
    val senseCount: Int

    /** 语言代码，目前只有 'en'。 */
    val lang: Int

    /** 生成时间（unix 秒）。 */
    val buildTime: Long

    private val offKeys: Int
    private val offRecs: Int
    private val offArena: Int

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        require(buf.getInt(0) == MAGIC) { "不是 .bge 文件：${file.name}" }
        lang = buf.getInt(8)
        entryCount = buf.getInt(12)
        senseCount = buf.getInt(16)
        offKeys = buf.getInt(20)
        offRecs = buf.getInt(24)
        offArena = buf.getInt(28)
        buildTime = buf.getLong(40)
    }

    /** 一条义项。 */
    class Sense(
        /** 义项正文（可能带行内注 `〔CL: 个〕`）。 */
        val text: String,
        /** 词性 id，见 [GlossaryFormatter.POS_NAMES]。 */
        val pos: Int,
        /** 语体/领域标签 id，0 表示没有。 */
        val label1: Int,
        val label2: Int,
    )

    private fun keyOff(i: Int): Int = offArena + buf.getInt(offKeys + i * KEY_SIZE)
    private fun keyLen(i: Int): Int = buf.getShort(offKeys + i * KEY_SIZE + 4).toInt() and 0xFFFF
    private fun recStart(i: Int): Int = buf.getInt(offKeys + i * KEY_SIZE + 6)
    private fun recCount(i: Int): Int = buf.getShort(offKeys + i * KEY_SIZE + 10).toInt() and 0xFFFF

    /**
     * 从 arena 里切出 [len] 字节并解码成字符串。
     *
     * 注意：不能用 `ByteBuffer.get(int, byte[], int, int)` —— 那是 Java 13 的绝对批量读，
     * Android 12（API 31）上会抛 `NoSuchMethodError`。这里走 `duplicate()` + `slice()`，
     * 从 API 1 就有，而且 `CharsetDecoder` 能正确处理被切断的多字节序列。
     */
    private fun readString(
        base: Int,
        len: Int,
    ): String {
        if (len <= 0) return ""
        val d = buf.duplicate()
        d.position(base)
        d.limit(base + len)
        return StandardCharsets.UTF_8.decode(d.slice()).toString()
    }

    /** 比较第 [i] 个词的 key 与目标字节串；只做单字节绝对读，不分配临时数组。 */
    private fun compareKey(
        i: Int,
        target: ByteArray,
    ): Int {
        val base = keyOff(i)
        val len = keyLen(i)
        val n = if (len < target.size) len else target.size
        for (k in 0 until n) {
            val x = buf.get(base + k).toInt() and 0xFF
            val y = target[k].toInt() and 0xFF
            if (x != y) return x - y
        }
        return len - target.size
    }

    /** 把所有义项挂到 [out] 上；找不到返回 `null`。 */
    fun lookup(word: String): List<Sense>? {
        val target = word.toByteArray(StandardCharsets.UTF_8)
        var lo = 0
        var hi = entryCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareKey(mid, target)
            when {
                cmp == 0 -> return sensesAt(recStart(mid), recCount(mid))
                cmp < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return null
    }

    private fun sensesAt(
        start: Int,
        cnt: Int,
    ): List<Sense> {
        val out = ArrayList<Sense>(cnt)
        for (j in start until start + cnt) {
            val base = offRecs + j * REC_SIZE
            val off = offArena + buf.getInt(base)
            val len = buf.getShort(base + 4).toInt() and 0xFFFF
            val pos = buf.get(base + 6).toInt()
            val l1 = buf.get(base + 7).toInt()
            val l2 = buf.get(base + 8).toInt()
            out.add(Sense(readString(off, len), pos, l1, l2))
        }
        return out
    }

    companion object {
        const val MAGIC = 0x31454742
        const val HEADER_SIZE = 48
        const val KEY_SIZE = 12
        const val REC_SIZE = 10

        fun open(file: File): GlossaryPack? =
            runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val ch = raf.channel
                    if (ch.size() < HEADER_SIZE) return null
                    val map = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                    GlossaryPack(file, map)
                }
            }.getOrNull()

    }

    override fun close() {
        // mmap 由 GC 回收；这里只是让调用方可以显式“结束使用”。
    }
}
