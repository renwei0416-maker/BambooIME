// 苍竹输入法 · 文曲星（诗词提示）
// 读 .bwx 包：整包映射进内存，查询走开放寻址哈希表，不解析、不建库。
// 包由 tools/wenquxing_pack.py 生成，格式见该脚本头部注释。
package com.osfans.trime.actions.wenquxing

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class WenquxingPack private constructor(
    private val raf: RandomAccessFile,
    val file: File,
    private val buf: ByteBuffer,
    private val keyCount: Int,
    private val contentCount: Int,
    private val hashCap: Int,
    private val offHash: Int,
    private val offKeys: Int,
    private val offRecs: Int,
    private val offContents: Int,
    private val offArena: Int,
    private val titleLen: Int,
) : AutoCloseable {

    val name: String = file.name.removeSuffix(SUFFIX)

    /** 包的中文名（编译时从 txt 头部的 # 行取），没有就用文件名。 */
    val title: String
        get() = if (titleLen > 0) readUtf8(offArena, titleLen) else name

    /** 一条命中：type 为 [TYPE_KEY]、[TYPE_SEG] 或 [TYPE_FULL]，text 为要上屏的内容。 */
    data class Hit(val type: Int, val text: String)

    fun lookup(key: String): List<Hit> {
        if (key.isEmpty()) return emptyList()
        val mask = hashCap - 1
        var slot = fnv1a32(key) and mask
        var probe = 0
        while (probe < hashCap) {
            val value = buf.getInt(offHash + slot * 4)
            if (value == 0) return emptyList()
            val kid = value - 1
            if (kid in 0 until keyCount && keyText(kid) == key) {
                val row = offKeys + kid * KEY_ROW
                val rcount = buf.getShort(row + 6).toInt() and 0xFFFF
                val rstart = buf.getInt(row + 8)
                val out = ArrayList<Hit>(rcount)
                for (i in 0 until rcount) {
                    val packed = buf.getInt(offRecs + (rstart + i) * 4)
                    val type = packed ushr 30
                    val cid = packed and 0x3FFFFFFF
                    when (type) {
                        TYPE_FULL -> if (cid in 0 until keyCount) out.add(Hit(TYPE_FULL, keyText(cid)))
                        else -> if (cid in 0 until contentCount) out.add(Hit(type, contentText(cid)))
                    }
                }
                return out
            }
            slot = (slot + 1) and mask
            probe++
        }
        return emptyList()
    }

    private fun keyText(kid: Int): String {
        val row = offKeys + kid * KEY_ROW
        val off = buf.getInt(row)
        val len = buf.getShort(row + 4).toInt() and 0xFFFF
        return readUtf8(offArena + off, len)
    }

    private fun contentText(cid: Int): String {
        val row = offContents + cid * CONTENT_ROW
        val off = buf.getInt(row)
        val len = buf.getInt(row + 4)
        return readUtf8(offArena + off, len)
    }

    private fun readUtf8(offset: Int, length: Int): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        val slice = buf.duplicate()
        slice.position(offset)
        slice.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    override fun close() {
        runCatching { raf.close() }
    }

    companion object {
        const val TYPE_KEY = 1
        const val TYPE_SEG = 2
        const val TYPE_FULL = 3

        const val SUFFIX = ".bwx"
        private const val MAGIC = "BWX1"
        private const val HEADER_SIZE = 48
        private const val KEY_ROW = 12
        private const val CONTENT_ROW = 8

        fun open(file: File): WenquxingPack? = runCatching {
            val raf = RandomAccessFile(file, "r")
            val size = raf.length()
            if (size < HEADER_SIZE) {
                raf.close()
                return null
            }
            val buf: ByteBuffer = raf.channel
                .map(FileChannel.MapMode.READ_ONLY, 0, size)
                .order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            val head = buf.duplicate()
            head.position(0)
            head.get(magic)
            if (String(magic, Charsets.US_ASCII) != MAGIC) {
                raf.close()
                return null
            }
            var pos = 4
            buf.getShort(pos).toInt() // version
            pos += 2
            val titleLen = buf.getShort(pos).toInt() and 0xFFFF
            pos += 2
            val keyCount = buf.getInt(pos); pos += 4
            buf.getInt(pos); pos += 4 // recCount：记录表长度，运行时不单独用
            val contentCount = buf.getInt(pos); pos += 4
            buf.getInt(pos); pos += 4 // arenaSize
            val hashCap = buf.getInt(pos); pos += 4
            val offHash = buf.getInt(pos); pos += 4
            val offKeys = buf.getInt(pos); pos += 4
            val offRecs = buf.getInt(pos); pos += 4
            val offContents = buf.getInt(pos); pos += 4
            val offArena = buf.getInt(pos)
            if (keyCount <= 0 || hashCap <= 0) {
                raf.close()
                return null
            }
            WenquxingPack(
                raf = raf,
                file = file,
                buf = buf,
                keyCount = keyCount,
                contentCount = contentCount,
                hashCap = hashCap,
                offHash = offHash,
                offKeys = offKeys,
                offRecs = offRecs,
                offContents = offContents,
                offArena = offArena,
                titleLen = titleLen,
            )
        }.getOrNull()

        /** 与 tools/wenquxing_pack.py 的 fnv1a32 一致。 */
        fun fnv1a32(text: String): Int {
            var hash = -0x7EE3623B // 0x811C9DC5
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toInt() and 0xFF)
                hash *= 0x01000193
            }
            return hash
        }
    }
}
