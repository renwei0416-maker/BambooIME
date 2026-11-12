/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.glossary

import android.util.Log
import com.osfans.trime.TrimeApplication
import java.io.File
import java.io.InputStream

/**
 * 已安装词库包的集合：扫描 App 私有目录 `files` 下的 `glossary` 子目录（只认 `.bge`），多包合并查询。
 *
 * 用户可放多份不同语言的包（`en-cedict`、以后的 `de-handedict`…），查询时全部走一遍再合并，
 * 所以「换语言」只是换个包，不需要改代码。
 */
object GlossaryStore {
    private val TAG = "glossary"
    private val packs = ArrayList<GlossaryPack>()

    @Volatile
    private var loaded = false
    private val lock = Any()

    /** 词库目录（首次访问自动创建）。 */
    val dir: File
        get() = File(TrimeApplication.getInstance().filesDir, "glossary").also { if (!it.exists()) it.mkdirs() }

    fun installedFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".bge", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            packs.clear()
            installedFiles().forEach { f ->
                val p = GlossaryPack.open(f)
                if (p != null) packs.add(p) else Log.w(TAG, "skip broken pack: ${f.name}")
            }
            loaded = true
            Log.i(TAG, "loaded ${packs.size} pack(s), entries=${packs.sumOf { it.entryCount }}")
        }
    }

    /** 装/删包之后调用，下次查询重新打开。 */
    fun reload() {
        synchronized(lock) {
            packs.clear()
            loaded = false
        }
        ensureLoaded()
    }

    /** 是否至少装了一个包。 */
    fun ready(): Boolean {
        ensureLoaded()
        return packs.isNotEmpty()
    }

    fun packCount(): Int {
        ensureLoaded()
        return packs.size
    }

    fun entryCount(): Int {
        ensureLoaded()
        return packs.sumOf { it.entryCount }
    }

    /**
     * 查一个词的全部义项（多包结果合并、按义项正文去重）。
     *
     * 空列表表示「没有任何包收录这个词」，调用方应保持候选的 comment 原样。
     */
    fun lookup(word: String): List<GlossaryPack.Sense> {
        ensureLoaded()
        if (packs.isEmpty()) return emptyList()
        var merged: MutableList<GlossaryPack.Sense>? = null
        for (p in packs) {
            val senses = p.lookup(word) ?: continue
            if (merged == null) {
                merged = ArrayList(senses)
            } else {
                val seen = merged.mapTo(HashSet()) { it.text }
                for (s in senses) if (seen.add(s.text)) merged.add(s)
            }
        }
        return merged ?: emptyList()
    }

    fun delete(file: File): Boolean {
        val ok = runCatching { file.delete() }.getOrDefault(false)
        if (ok) reload()
        return ok
    }

    /** 从一个已存在的文件安装（「从文件夹导入」用）。 */
    fun installFile(src: File): String? {
        val target = File(dir, src.name)
        if (src.absolutePath == target.absolutePath) {
            val probe = runCatching { GlossaryPack.open(src) }.getOrNull()
                ?: return "文件不是有效的 .bge 词库包"
            probe.close()
            reload()
            return null
        }
        return runCatching { src.inputStream().use { install(it, src.name) } }
            .getOrElse { it.message ?: it.javaClass.simpleName }
    }

    /**
     * 从外部输入流安装一个包：先写 `.part`，校验能打开且 magic 正确，再改名落盘。
     * 返回 `null` 表示成功，否则是失败原因（给设置页显示）。
     */
    fun install(
        input: InputStream,
        fileName: String,
    ): String? {
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "glossary.bge" }
        val target = File(dir, if (safeName.endsWith(".bge", true)) safeName else "$safeName.bge")
        val tmp = File(dir, "${target.name}.part")
        return runCatching {
            tmp.outputStream().use { out -> input.copyTo(out) }
            val probe = GlossaryPack.open(tmp)
            if (probe == null) {
                tmp.delete()
                return "文件不是有效的 .bge 词库包"
            }
            probe.close()
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return "无法写入 ${target.name}"
            }
            reload()
            null
        }.getOrElse {
            tmp.delete()
            it.message ?: it.javaClass.simpleName
        }
    }
}
