/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.glossary

import android.util.Log
import com.osfans.trime.BuildConfig
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

    /** 读一个文件的语言码；读不到返回 null。只读文件头，不动已加载的实例。 */
    fun langOf(file: File): Int? =
        runCatching { GlossaryPack.open(file)?.let { it.lang } }.getOrNull()

    fun installedFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".bge", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** 内置英文词库的资产名（见 `app/src/main/glossary-assets/glossary/`）。 */
    private const val BUNDLED_EN = "glossary-en-cedict-1.0.bge"

    /** 这个包是不是随 App 内置的（内置包在设置页里不可删除）。 */
    fun isBuiltin(file: File): Boolean = file.name == BUNDLED_EN

    /** 安装时记下的语言码；列表里给每个包打标签用，免得每次重新打开词库。 */
    private fun langSidecar(pack: File) = File(dir, "${pack.name}.lang")

    private fun rememberLang(pack: File, code: Int) {
        runCatching { langSidecar(pack).writeText(code.toString()) }
    }

    /**
     * 一个包属于哪门语言：优先读安装时记下的码，其次试着打开词库读头。
     * 两样都读不到就返回 null（列表里标「无法识别」，不影响加载）。
     */
    fun langTagOf(file: File): Int? {
        val side = langSidecar(file)
        if (side.isFile) {
            runCatching { side.readText().trim().toInt() }.getOrNull()?.let { return it }
        }
        return langOf(file)
    }

    /**
     * 内置英文词库：**首次启动**从 assets 拷一份到私有目录，之后不再管。
     *
     * 用标记文件而不是"文件是否存在"来判定：用户主动删掉内置包时不该被偷偷补回来。
     * 这里不调 [install]，因为它会 `reload()`，而我们正处在加载过程中。
     */
    private fun syncBundled() {
        if (!BuildConfig.BUNDLED_GLOSSARY_EN) return
        val marker = File(dir, ".bundled-en")
        if (marker.isFile) return
        val target = File(dir, BUNDLED_EN)
        if (target.exists()) {
            runCatching { marker.writeText("1") }
            return
        }
        runCatching {
            val tmp = File(dir, "$BUNDLED_EN.part")
            TrimeApplication.getInstance().assets.open("glossary/$BUNDLED_EN").use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            val probe = GlossaryPack.open(tmp)
            if (probe == null) {
                tmp.delete()
                Log.w(TAG, "bundled pack is broken, skipped")
                return@runCatching
            }
            probe.close()
            if (tmp.renameTo(target)) {
                marker.writeText("1")
                Log.i(TAG, "installed bundled pack: $BUNDLED_EN")
            } else {
                tmp.delete()
                Log.w(TAG, "cannot rename bundled pack")
            }
        }.onFailure { Log.w(TAG, "install bundled pack failed: ${it.message}") }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            syncBundled()
            packs.clear()
            val want = GlossaryPrefs.langCodeOf(GlossaryPrefs.lang)
            installedFiles().forEach { f ->
                val p = GlossaryPack.open(f)
                when {
                    p == null -> Log.w(TAG, "skip broken pack: ${f.name}")
                    // 一次只学一门语言：别的语言的包先不打开（换语言时 reload）
                    p.lang != want -> Log.i(TAG, "skip ${f.name} (lang=${p.lang})")
                    else -> packs.add(p)
                }
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
        if (ok) {
            runCatching { langSidecar(file).delete() }
            reload()
        }
        return ok
    }

    /** 从一个已存在的文件安装（「从文件夹导入」用）。 */
    fun installFile(src: File): String? {
        val target = File(dir, src.name)
        if (src.absolutePath == target.absolutePath) {
            val probe = runCatching { GlossaryPack.open(src) }.getOrNull()
                ?: return "文件不是有效的 .bge 词库包"
            rememberLang(src, probe.lang)
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
            val lang = runCatching { GlossaryPack.open(target)?.use { it.lang } }.getOrNull()
            if (lang != null) rememberLang(target, lang)
            Log.i(TAG, "installed ${target.name} (lang=$lang)")
            reload()
            null
        }.getOrElse {
            tmp.delete()
            it.message ?: it.javaClass.simpleName
        }
    }
}
