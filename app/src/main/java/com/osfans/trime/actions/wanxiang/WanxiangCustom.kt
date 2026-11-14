/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.wanxiang

import com.osfans.trime.data.packaging.SchemaPackStore
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把 [WanxiangPrefs] 的状态写成 rime 的 custom 文件。
 *
 * 写法约定（2026-11 重写，解决"两个 patch 并存"）：
 *
 *  * 每个文件**永远只有一个顶层 `patch:`**。写盘前会自检，不满足就抛错、不落盘。
 *  * 有托管块 → 只替换块内；块外内容原样保留。
 *  * 没有托管块（旧版本留下的、或 librime 自己重写的）→ 先规范化：
 *    备份成 `*.bak-legacy-*`，把块外我们不管的键（用户加的）搬进新托管块，其余丢弃。
 *  * 我们管的键只有四个 + `schema_list`，见 [MANAGED_KEYS]。
 */
object WanxiangCustom {

    const val SCHEMA_PRO = "wanxiang_pro"
    const val SCHEMA_T9 = "wanxiang_t9_pro"

    private const val PRO_CUSTOM = "wanxiang_pro.custom.yaml"
    private const val T9_CUSTOM = "wanxiang_t9_pro.custom.yaml"
    private const val DEFAULT_CUSTOM = "default.custom.yaml"

    private const val BEGIN = "# >>> BambooIME managed（这一段由「万象拼音」设置页生成，手改会被覆盖）"
    private const val END = "# <<< BambooIME managed"

    private const val HEADER = "# 由 BambooIME「万象拼音」设置页生成；块外内容会保留，块内每次重写。"

    /** 由本对象管理的 patch 子键；其余子键视为用户自定义，规范化时保留。 */
    private val MANAGED_KEYS =
        setOf(
            "speller/algebra",
            "menu/page_size",
            "translator/enable_user_dict",
            "translator/contextual_suggestions",
            "schema_list",
        )

    /** 按布局决定用哪个方案。 */
    fun activeSchema(layout: Int): String =
        if (layout == WanxiangPrefs.LAYOUT_9) SCHEMA_T9 else SCHEMA_PRO

    /** 按布局决定要启用哪些方案：**一次只有一个**。 */
    fun enabledSchemas(layout: Int): Array<String> = arrayOf(activeSchema(layout))

    // ------------------------------------------------------------ algebra

    /**
     * 顺序照官方模板：模糊音 → 编码方案 → 辅助码 → 键位映射。
     *
     * 键位映射必须放最后（9/14/18/26 都是「字母级别」的折叠，放在前面会把源码改掉）。
     */
    fun algebraNodes(): List<String> = algebraNodes(
        fuzzy = WanxiangPrefs.fuzzy,
        encode = WanxiangPrefs.encode,
        scheme = WanxiangPrefs.scheme,
        aux = WanxiangPrefs.aux,
        layout = WanxiangPrefs.layout,
    )

    private fun algebraNodes(
        fuzzy: Set<String>,
        encode: String,
        scheme: String,
        aux: Int,
        layout: Int,
    ): List<String> {
        val nodes = mutableListOf<String>()
        WanxiangPrefs.FUZZY_KEYS.forEach { key ->
            if (fuzzy.contains(key)) nodes += "wanxiang_algebra:/模糊音_$key"
        }
        nodes += if (encode == WanxiangPrefs.ENCODE_QUANPIN) {
            "wanxiang_algebra:/pro/全拼"
        } else {
            "wanxiang_algebra:/pro/$scheme"
        }
        when (aux) {
            WanxiangPrefs.AUX_DIRECT -> nodes += "wanxiang_algebra:/pro/直接辅助"
            WanxiangPrefs.AUX_INDIRECT -> nodes += "wanxiang_algebra:/pro/间接辅助"
            else -> Unit
        }
        nodes += when (layout) {
            WanxiangPrefs.LAYOUT_18 -> "wanxiang_algebra:/18jian"
            WanxiangPrefs.LAYOUT_14 -> "wanxiang_algebra:/14jian"
            else -> "wanxiang_algebra:/26jian"
        }
        return nodes
    }

    // ------------------------------------------------------------ 渲染

    private fun renderProBlock(
        nodes: List<String>,
        pageSize: Int,
        userDict: Boolean,
        contextual: Boolean,
    ): String = buildString {
        appendLine(BEGIN)
        appendLine("patch:")
        appendLine("  speller/algebra:")
        appendLine("    __patch:")
        nodes.forEach { appendLine("      - $it") }
        appendLine("  menu/page_size: $pageSize")
        // 两个键总是显式写：官方以后改默认值时，「关」也能生效
        appendLine("  translator/enable_user_dict: $userDict")
        appendLine("  translator/contextual_suggestions: $contextual")
        append(END)
    }

    private fun renderT9Block(
        pageSize: Int,
        userDict: Boolean,
        contextual: Boolean,
    ): String = buildString {
        appendLine(BEGIN)
        appendLine("patch:")
        appendLine("  menu/page_size: $pageSize")
        appendLine("  translator/enable_user_dict: $userDict")
        appendLine("  translator/contextual_suggestions: $contextual")
        append(END)
    }

    private fun renderSchemaListBlock(schema: String): String = buildString {
        appendLine(BEGIN)
        appendLine("patch:")
        appendLine("  schema_list:")
        appendLine("    - schema: $schema")
        append(END)
    }

    // ------------------------------------------------------------ 写入

    /** 把 [block] 塞进 [file] 的托管块位置，并保证全文只有一个顶层 `patch:`。 */
    private fun writeManaged(
        file: File,
        block: String,
    ) {
        val old = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        if (old != null && old.isNotBlank()) {
            backup(file, if (hasMarkers(old)) "bak" else "bak-legacy")
        }

        val text = when {
            old.isNullOrBlank() -> HEADER + "\n" + block + "\n"
            else -> normalize(old, block)
        }

        val patches = countTopLevelPatches(text)
        check(patches == 1) {
            "wanxiang: ${file.name} 归一化后仍有 $patches 个 patch:，拒绝写入"
        }
        file.writeText(text)
        Timber.d("wanxiang: wrote ${file.name} (${text.length} chars, patch=$patches)")
    }

    private fun hasMarkers(text: String): Boolean {
        val b = text.indexOf(BEGIN)
        val e = text.indexOf(END)
        return b >= 0 && e > b
    }

    /**
     * 没有（或残缺）托管块时的规范化：保留块外非 patch 内容 + 用户自定义的 patch 子键，
     * 丢掉旧 patch 里我们管的键，最后拼成「一个托管块」。
     */
    private fun normalize(
        old: String,
        block: String,
    ): String {
        var outside = old
        val b = old.indexOf(BEGIN)
        val e = old.indexOf(END)
        if (b >= 0 && e > b) {
            outside = old.substring(0, b) + "\n" + old.substring(e + END.length)
        }
        // 从块外剥掉所有顶层 patch 段，同时收集用户自定义子键
        val (rest, extras) = stripPatchBlocks(outside)
        val merged = if (extras.isEmpty()) block else insertExtras(block, extras)
        val head = rest.trim()
        return buildString {
            if (head.isNotEmpty()) {
                appendLine(head)
                appendLine()
            } else {
                appendLine(HEADER)
            }
            appendLine(merged)
        }
    }

    /** 去掉所有 `^patch:` 段，返回（剩余文本, 未托管的子键原文块）。 */
    private fun stripPatchBlocks(text: String): Pair<String, List<String>> {
        val lines = text.lines()
        val rest = mutableListOf<String>()
        val extras = mutableListOf<String>()
        var inPatch = false
        var childIndent = -1
        var currentKey: String? = null
        var currentBlock = mutableListOf<String>()

        fun flush() {
            val key = currentKey
            if (key != null && currentBlock.isNotEmpty() && key !in MANAGED_KEYS) {
                extras += currentBlock
            }
            currentKey = null
            currentBlock = mutableListOf()
        }

        lines.forEach { line ->
            if (line.startsWith("patch:") && line.trim() == "patch:") {
                flush()
                inPatch = true
                childIndent = -1
                return@forEach
            }
            if (inPatch) {
                val indent = line.length - line.trimStart().length
                if (line.isBlank()) return@forEach
                if (indent == 0) {
                    // patch 段结束，交还给 rest
                    flush()
                    inPatch = false
                    rest += line
                    return@forEach
                }
                // 子键缩进取「patch 下第一条子行」的缩进：2 空格、4 空格、tab 一视同仁
                if (childIndent < 0) childIndent = indent
                if (indent == childIndent) {
                    flush()
                    val key = line.trim().substringBefore(':').trim()
                    currentKey = key
                    currentBlock = mutableListOf(line)
                    return@forEach
                }
                currentBlock += line
                return@forEach
            }
            rest += line
        }
        flush()
        return rest.joinToString("\n") to extras
    }

    /** 把用户自定义子键插到托管块里 `# <<< BambooIME managed` 之前。 */
    private fun insertExtras(
        block: String,
        extras: List<String>,
    ): String {
        val idx = block.indexOf(END)
        if (idx < 0) return block
        val injected = extras.joinToString("\n") { it.trimEnd() }
        return block.substring(0, idx) + injected + "\n" + block.substring(idx)
    }

    private fun countTopLevelPatches(text: String): Int =
        text.lines().count { it.startsWith("patch:") && it.trim() == "patch:" }

    private fun backup(
        file: File,
        kind: String,
    ) {
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val bak = File(file.parentFile, "${file.name}.$kind-$stamp")
            file.copyTo(bak, overwrite = true)
            // 只留最近 3 份
            file.parentFile?.listFiles { f -> f.name.startsWith("${file.name}.$kind-") }
                ?.sortedByDescending { it.name }
                ?.drop(3)
                ?.forEach { it.delete() }
        }.onFailure { Timber.w(it, "wanxiang: backup ${file.name} failed") }
    }

    /**
     * 首次释放内置包时调用：只在文件**不存在**时写一份默认配置。
     *
     * 不需要 [WanxiangPrefs] 初始化（释放发生在后台线程、可能早于输入法启动）。
     */
    fun writeInitialConfigIfMissing(
        dir: File,
        schema: String = SCHEMA_PRO,
    ) {
        runCatching {
            dir.mkdirs()
            val pro = File(dir, PRO_CUSTOM)
            if (!pro.exists()) {
                writeManaged(
                    pro,
                    renderProBlock(
                        nodes = algebraNodes(
                            fuzzy = emptySet(),
                            encode = WanxiangPrefs.ENCODE_SHUANGPIN,
                            scheme = "小鹤双拼",
                            aux = WanxiangPrefs.AUX_NONE,
                            layout = WanxiangPrefs.LAYOUT_26,
                        ),
                        pageSize = 6,
                        userDict = false,
                        contextual = false,
                    ),
                )
            }
            val t9 = File(dir, T9_CUSTOM)
            if (!t9.exists()) {
                writeManaged(t9, renderT9Block(pageSize = 6, userDict = true, contextual = false))
            }
            val defaults = File(dir, DEFAULT_CUSTOM)
            if (!defaults.exists()) {
                writeManaged(defaults, renderSchemaListBlock(schema))
            }
        }.onFailure { Timber.w(it, "wanxiang: writeInitialConfigIfMissing failed") }
    }

    /** 生成 `schema_list`（只放 [enabledSchemas] 里的那一个）。 */
    private fun writeSchemaList(): Boolean {
        val file = File(SchemaPackStore.dir(), DEFAULT_CUSTOM)
        return runCatching {
            writeManaged(file, renderSchemaListBlock(activeSchema(WanxiangPrefs.layout)))
            true
        }.onFailure { Timber.w(it, "wanxiang: write $DEFAULT_CUSTOM failed") }
            .getOrDefault(false)
    }

    /**
     * 把当前状态全部写盘（不动 rime，不部署）。返回是否全部成功。
     *
     * 注意：九键时也照样写 26 键那份 custom —— 幂等、无害，切回全键盘时直接就是对的。
     */
    fun writeAll(): Boolean {
        if (!SchemaPackStore.isInstalled()) return false
        val dir = SchemaPackStore.dir()
        var ok = true
        ok = runCatching {
            writeManaged(
                File(dir, PRO_CUSTOM),
                renderProBlock(
                    nodes = algebraNodes(),
                    pageSize = WanxiangPrefs.pageSize,
                    userDict = WanxiangPrefs.userDict,
                    contextual = WanxiangPrefs.contextual,
                ),
            )
        }.onFailure { Timber.w(it, "wanxiang: write $PRO_CUSTOM failed") }
            .isSuccess && ok
        ok = runCatching {
            writeManaged(
                File(dir, T9_CUSTOM),
                renderT9Block(
                    pageSize = WanxiangPrefs.t9PageSize,
                    userDict = WanxiangPrefs.t9UserDict,
                    contextual = WanxiangPrefs.t9Contextual,
                ),
            )
        }.onFailure { Timber.w(it, "wanxiang: write $T9_CUSTOM failed") }
            .isSuccess && ok
        ok = writeSchemaList() && ok

        if (ok) WanxiangPrefs.appliedSignature = WanxiangPrefs.signature()
        return ok
    }

    /**
     * 启动时自检：三份 custom 里只要有一份出现多个 `patch:`（旧版本追加写留下的），
     * 就用当前设置整体重写一遍（每份都会先备份成 `*.bak-legacy-*`）。
     *
     * @return 是否真的修过
     */
    fun repairIfNeeded(): Boolean {
        if (!SchemaPackStore.isInstalled()) return false
        val dir = SchemaPackStore.dir()
        val broken = runCatching {
            listOf(PRO_CUSTOM, T9_CUSTOM, DEFAULT_CUSTOM).any { name ->
                val f = File(dir, name)
                if (!f.isFile) {
                    false
                } else {
                    val text = f.readText()
                    countTopLevelPatches(text) > 1
                }
            }
        }.getOrDefault(false)
        if (!broken) return false
        Timber.w("wanxiang: 发现重复 patch:，开始归一化")
        return runCatching { writeAll() }.getOrDefault(false)
    }

    /** 读回设备上实际的 custom（给「当前生效配置」预览用）。 */
    fun readProCustom(): String =
        runCatching { File(SchemaPackStore.dir(), PRO_CUSTOM).readText() }.getOrDefault("")

    /** 当前生效方案的简要摘要（预览页用）。 */
    fun summary(): String = buildString {
        append("输入方式：").append(
            when (WanxiangPrefs.layout) {
                WanxiangPrefs.LAYOUT_9 -> "九宫格（9 键）"
                WanxiangPrefs.LAYOUT_18 -> "18 键"
                WanxiangPrefs.LAYOUT_14 -> "14 键（用 26 键键盘模拟）"
                else -> "全键盘（26 键）"
            },
        ).append('\n')
        if (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) {
            append("编码方案：全拼（九键固定全拼）\n")
        } else {
            append("编码方案：").append(
                if (WanxiangPrefs.encode == WanxiangPrefs.ENCODE_QUANPIN) "全拼" else WanxiangPrefs.scheme,
            ).append('\n')
        }
        append("辅助码：").append(
            when (WanxiangPrefs.aux) {
                WanxiangPrefs.AUX_DIRECT -> "直接辅助"
                WanxiangPrefs.AUX_INDIRECT -> "间接辅助"
                else -> "关闭"
            },
        ).append('\n')
        append("用户词典/调频：")
            .append(if (WanxiangPrefs.userDict) "开" else "关").append('\n')
        append("智能调频：").append(if (WanxiangPrefs.contextual) "开" else "关").append('\n')
        append("候选数：").append(WanxiangPrefs.pageSize).append('\n')
        append("启用方案：").append(activeSchema(WanxiangPrefs.layout))
    }

    /** manifest 里的版本号；找不到就返回空（下载功能用）。 */
    fun packVersion(): String =
        runCatching { JSONObject(File(SchemaPackStore.dir(), "pkg-manifest.json").readText()) }
            .getOrNull()?.optString("version").orEmpty()
}
