/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.wanxiang

import com.osfans.trime.actions.ids.IdsConfig
import com.osfans.trime.actions.ids.idsConfigOrNull
import com.osfans.trime.data.packaging.CustomSignature
import com.osfans.trime.data.packaging.SchemaPackStore
import com.osfans.trime.data.packaging.SchemaPackageManager
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

    /** Base 包（内置）的 26 键方案 id。 */
    const val SCHEMA_BASE_26 = "wanxiang"

    /** Base 包（内置）的九键方案 id（官方自带，Pro 没有九键）。 */
    const val SCHEMA_BASE_T9 = "wanxiang_t9"

    /** Pro 包（下载）的 26 键方案 id。 */
    const val SCHEMA_PRO_26 = "wanxiang_pro"

    private const val BASE_CUSTOM = "wanxiang.custom.yaml"
    private const val PRO_CUSTOM = "wanxiang_pro.custom.yaml"
    private const val DEFAULT_CUSTOM = "default.custom.yaml"

    /** 当前该往哪个包写：活动包是 Pro 就写 Pro，否则按 Base。 */
    /** 当前活动包**如果**是我们的 Base/Pro 就返回它，否则 null（分项设置只在这种情况可写）。 */
    fun currentPackage(): String? {
        val id = SchemaPackageManager.activePackageId
        return if (id == SchemaPackStore.BASE_ID || id == SchemaPackStore.PRO_ID) id else null
    }

    fun activePackage(): String =
        if (SchemaPackStore.isActive(SchemaPackStore.PRO_ID)) {
            SchemaPackStore.PRO_ID
        } else {
            SchemaPackStore.BASE_ID
        }

    /**
     * 这次配置该落到哪个包 —— 唯一判据。
     *
     * 辅助码是「选包」的开关：要辅助码就是 Pro（Pro 没有「不用辅助码」这一档），
     * 不要辅助码就是 Base（Base 词库本来就没有辅码列）。九键只能用 Base。
     */
    fun targetPackage(): String {
        val wantsAux = WanxiangPrefs.aux != WanxiangPrefs.AUX_NONE
        val slotFitsPro = WanxiangPrefs.layout != WanxiangPrefs.LAYOUT_9
        return if (wantsAux && slotFitsPro) SchemaPackStore.PRO_ID else SchemaPackStore.BASE_ID
    }

    /** 包 + 键盘形态 → 该包的方案 id。Pro 没有九键，永远是 26 键那个。 */
    fun schemaOf(pkgId: String, layout: Int): String = when {
        pkgId == SchemaPackStore.PRO_ID -> SCHEMA_PRO_26
        layout == WanxiangPrefs.LAYOUT_9 -> SCHEMA_BASE_T9
        else -> SCHEMA_BASE_26
    }

    /** 包里那份「主 custom」的文件名。 */
    private fun customFileOf(pkgId: String): String =
        if (pkgId == SchemaPackStore.PRO_ID) PRO_CUSTOM else BASE_CUSTOM

    /**
     * 这个包配置过没有：看它的 custom 文件在不在。
     *
     * 只装包不写 custom = 裸包，用户还没选过输入方式，不该拿默认值替他配。
     */
    fun isConfigured(pkgId: String = activePackage()): Boolean =
        File(SchemaPackStore.dir(pkgId), customFileOf(pkgId)).isFile

    /** algebra 节点前缀：Base 用 `base/`，Pro 用 `pro/`。 */
    private fun sectionOf(pkgId: String): String =
        if (pkgId == SchemaPackStore.PRO_ID) "pro" else "base"

    /** 包里装的是不是 Pro（决定辅助码能不能写）。 */
    fun isPro(pkgId: String): Boolean = pkgId == SchemaPackStore.PRO_ID

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

    /** 按包 + 布局决定用哪个方案。 */
    fun activeSchema(pkgId: String, layout: Int): String = schemaOf(pkgId, layout)

    /** 要启用哪些方案：**一次只有一个**。 */
    fun enabledSchemas(pkgId: String, layout: Int): Array<String> = arrayOf(schemaOf(pkgId, layout))

    // ------------------------------------------------------------ algebra

    /**
     * 顺序照官方模板：模糊音 → 编码方案 → 辅助码 → 键位映射。
     *
     * 键位映射必须放最后（9/14/18/26 都是「字母级别」的折叠，放在前面会把源码改掉）。
     */
    fun algebraNodes(pkgId: String = activePackage()): List<String> = algebraNodes(
        pkgId = pkgId,
        fuzzy = WanxiangPrefs.fuzzy,
        encode = WanxiangPrefs.encode,
        scheme = WanxiangPrefs.scheme,
        aux = WanxiangPrefs.aux,
        layout = WanxiangPrefs.layout,
    )

    private fun algebraNodes(
        pkgId: String,
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
        // 节点前缀跟着包走：Base 的词库没有辅码列，必须用 base/ 那一套
        val section = sectionOf(pkgId)
        nodes += if (encode == WanxiangPrefs.ENCODE_QUANPIN) {
            "wanxiang_algebra:/$section/全拼"
        } else {
            "wanxiang_algebra:/$section/$scheme"
        }
        // 辅助码只在 Pro 词库上才有意义（Base 词库不带 `;辅码` 列）
        if (section == "pro") {
            when (aux) {
                WanxiangPrefs.AUX_DIRECT -> nodes += "wanxiang_algebra:/pro/直接辅助"
                WanxiangPrefs.AUX_INDIRECT -> nodes += "wanxiang_algebra:/pro/间接辅助"
                // Pro 词库每个音节都带 `;辅码`（如 `ā;ek`），只有挂「直接辅助」/「间接辅助」
                // 时才会被剥掉。所以 Pro 不存在「不用辅助码」这一档 —— 不用辅助码就配 Base。
                else -> Unit
            }
        }
        nodes += when (layout) {
            WanxiangPrefs.LAYOUT_18 -> "wanxiang_algebra:/18jian"
            WanxiangPrefs.LAYOUT_14 -> "wanxiang_algebra:/14jian"
            else -> "wanxiang_algebra:/26jian"
        }
        return nodes
    }

    // ------------------------------------------------------------ 渲染

    /**
     * 只写两样：`speller/algebra` 与 `menu/page_size`。
     *
     * 用户词典、智能调频一律不写，交给方案自己的默认值 —— 官方 Base 的 custom 也只写这两样，
     * 少写一个键就少一处和上游打架的地方。
     */
    private fun renderCustomBlock(
        nodes: List<String>,
        pageSize: Int,
        contextual: Boolean = WanxiangPrefs.contextual,
        ids: IdsConfig? = idsConfigOrNull(),
    ): String = buildString {
        appendLine(BEGIN)
        appendLine("patch:")
        appendLine("  speller/algebra:")
        appendLine("    __patch:")
        nodes.forEach { appendLine("      - $it") }
        appendLine("  menu/page_size: $pageSize")
        appendLine("  translator/contextual_suggestions: $contextual")
        if (ids != null) {
            IDS_ENGINE_LINES.forEach { appendLine(it) }
            appendIdsSection(ids) { appendLine(it) }
        }
        append(END)
    }

    private fun renderSchemaListBlock(schema: String): String = buildString {
        appendLine(BEGIN)
        appendLine("patch:")
        appendLine("  schema_list:")
        appendLine("    - schema: $schema")
        append(END)
    }

    // ------------------------------------------------------------ 当前方案

    /**
     * 当前真正在用的方案。设置页改哪份 custom，全看它。
     *
     * 引擎没起（冷启动直接进设置）时退回我们自己的记录，避免显示成「没配置」。
     */
    fun detectedSchemaId(): String? {
        val live = com.osfans.trime.daemon.RimeDaemon.currentSchemaId
        if (live.isNotBlank() && live != ".default") return live
        return when (SchemaPackageManager.activePackageId) {
            SchemaPackStore.BASE_ID ->
                if (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) SCHEMA_BASE_T9 else SCHEMA_BASE_26
            SchemaPackStore.PRO_ID -> SCHEMA_PRO_26
            else -> null
        }
    }

    /** 六种判定里只留五种：Base 26 / Base 九键 / Pro / 别人的方案 / 没配置。 */
    enum class Scope { BASE_26, BASE_9, PRO_26, FOREIGN, NONE }

    fun currentScope(): Scope {
        val schema = detectedSchemaId() ?: return Scope.NONE
        return when {
            SchemaPackageManager.activePackageId == SchemaPackStore.BASE_ID && schema == SCHEMA_BASE_26 ->
                Scope.BASE_26
            SchemaPackageManager.activePackageId == SchemaPackStore.BASE_ID && schema == SCHEMA_BASE_T9 ->
                Scope.BASE_9
            SchemaPackageManager.activePackageId == SchemaPackStore.PRO_ID && schema == SCHEMA_PRO_26 ->
                Scope.PRO_26
            else -> Scope.FOREIGN
        }
    }

    /** 这个作用域改哪份 custom；null = 不归我们管，不写。 */
    fun scopeTarget(): String? = when (currentScope()) {
        Scope.BASE_26, Scope.BASE_9 -> SchemaPackStore.BASE_ID
        Scope.PRO_26 -> SchemaPackStore.PRO_ID
        else -> null
    }

    /**
     * 能不能在设置页里改这三项。
     *
     * 九键不给改：`wanxiang_t9` 没有独立的 custom（我们按约定不新建），写包里那份
     * `wanxiang.custom.yaml` 只对 26 键方案生效，改了九键也没反应 —— 所以整块灰掉。
     */
    fun scopeEditable(): Boolean = when (currentScope()) {
        Scope.BASE_26, Scope.PRO_26 -> true
        else -> false
    }

    // ------------------------------------------------------------ 部件反查（IDS）

    /**
     * 挂进 `engine` 的四行。
     *
     * processors / segmentors / filters 是**有序链**，必须排在 ascii-composer、recognizer 之前，
     * 才能在 `I` / `O` 起头时先被拦下来；translators 是并行合并候选、顺序只影响排序，所以用 `+` 追加
     * —— 与插件作者 `examples/attach.patch.yaml` 的写法一致。
     */
    private val IDS_ENGINE_LINES = listOf(
        "  engine/processors/@before 0: ids_reverse_processor",
        "  engine/segmentors/@before 0: ids_reverse_segmentor",
        "  engine/translators/+: ids_reverse_translator",
        "  engine/filters/@before 0: ids_reverse_filter",
    )

    private val IDS_ENGINE_PREFIXES = listOf(
        "engine/processors/@before 0:",
        "engine/segmentors/@before 0:",
        "engine/translators/+:",
        "engine/filters/@before 0:",
    )

    /** 把 `ids_reverse:` 这一段拼出来（缩进两格，与 `menu/page_size` 同级）。 */
    private fun appendIdsSection(ids: IdsConfig, sink: (String) -> Unit) {
        sink("  ids_reverse:")
        sink("    data_file: \"${ids.dataFile}\"")
        sink("    search_prefix: ${ids.searchPrefix}")
        sink("    hybrid_prefix: ${ids.hybridPrefix}")
        sink("    max_results: ${ids.maxResults}")
        sink("    shuangpin_scheme: ${ids.shuangpinScheme}")
        sink("    show_pinyin: ${ids.showPinyin}")
        sink("    show_ids: ${ids.showIds}")
        sink("    show_unicode: ${ids.showUnicode}")
        sink("    show_block: ${ids.showBlock}")
        sink("    ids_format: ${ids.idsFormat}")
        sink("    variant_trigger: \"${ids.variantTrigger}\"")
        sink("    variant_replace_key: \"${ids.variantReplaceKey}\"")
        if (ids.disabledBlocks.isNotBlank()) sink("    disabled_blocks: ${ids.disabledBlocks}")
        if (ids.searchTips.isNotBlank()) sink("    search_tips: \"${ids.searchTips}\"")
        if (ids.hybridTips.isNotBlank()) sink("    hybrid_tips: \"${ids.hybridTips}\"")
    }

    /** 删掉整段 `name:`（含它下面所有缩进更深的行）。 */
    private fun removeSection(lines: MutableList<String>, name: String) {
        val start = lines.indexOfFirst { it.trimStart().startsWith("$name:") }
        if (start < 0) return
        val indent = lines[start].indexOfFirst { !it.isWhitespace() }
        var end = start + 1
        while (end < lines.size) {
            val line = lines[end]
            if (line.isBlank()) {
                end++
                continue
            }
            val ind = line.indexOfFirst { !it.isWhitespace() }
            if (ind > indent) end++ else break
        }
        lines.subList(start, end).clear()
    }

    // ------------------------------------------------------------ 合并写入

    /** 取出现有托管块（含首尾标记行）。 */
    private fun blockOf(text: String): String {
        val b = text.indexOf(BEGIN)
        val e = text.indexOf(END)
        return if (b >= 0 && e > b) text.substring(b, e + END.length) else ""
    }

    /**
     * 只改托管块里的三处：模糊音节点、`menu/page_size`、`translator/contextual_suggestions`。
     *
     * 其余行（向导写进去的编码方案、键位折叠、用户自己加的子键）一个字节都不动 ——
     * 早先的实现是拿 prefs 重建整段 algebra，那样会把向导的选择覆盖掉。
     */
    private fun mergeBlock(
        block: String,
        fuzzy: Set<String>,
        pageSize: Int,
        contextual: Boolean,
        ids: IdsConfig? = idsConfigOrNull(),
    ): String {
        val lines = block.lines().toMutableList()

        lines.removeAll { it.trimStart().startsWith("- wanxiang_algebra:/模糊音_") }
        // 部件反查：先整段清掉再按开关插回，避免反复「应用」堆叠出重复行。
        lines.removeAll { line -> IDS_ENGINE_PREFIXES.any { line.trimStart().startsWith(it) } }
        removeSection(lines, "ids_reverse")

        val wanted =
            WanxiangPrefs.FUZZY_KEYS.filter { fuzzy.contains(it) }
                .map { "      - wanxiang_algebra:/模糊音_$it" }
        if (wanted.isNotEmpty()) {
            val at = lines.indexOfFirst { it.trim() == "__patch:" }
            if (at >= 0) lines.addAll(at + 1, wanted)
        }

        fun setOrAppend(prefix: String, line: String) {
            val i = lines.indexOfFirst { it.trimStart().startsWith(prefix) }
            if (i >= 0) {
                lines[i] = line
            } else {
                val end = lines.indexOfFirst { it.trim() == END }
                if (end >= 0) lines.add(end, line) else lines.add(line)
            }
        }
        setOrAppend("menu/page_size:", "  menu/page_size: $pageSize")
        setOrAppend("translator/contextual_suggestions:", "  translator/contextual_suggestions: $contextual")

        if (ids != null) {
            val end = lines.indexOfFirst { it.trim() == END }
            val at = if (end >= 0) end else lines.size
            val added = buildList {
                addAll(IDS_ENGINE_LINES)
                appendIdsSection(ids) { add(it) }
            }
            lines.addAll(at, added)
        }

        return lines.joinToString("\n")
    }

    /**
     * 设置页「应用并部署」用这个：只改那三行，不重建 algebra。
     *
     * @return 写入成功与否；[pkgId] 目录不存在直接返回 false（不去创建）。
     */
    fun applySettings(
        pkgId: String,
        fuzzy: Set<String>,
        pageSize: Int,
        contextual: Boolean,
        ids: IdsConfig? = idsConfigOrNull(),
    ): Boolean = runCatching {
        val dir = SchemaPackStore.dir(pkgId)
        if (!dir.isDirectory) {
            Timber.w("wanxiang: applySettings skipped, %s not installed", pkgId)
            return@runCatching false
        }
        val file = File(dir, customFileOf(pkgId))
        val old = if (file.isFile) file.readText() else ""
        val base =
            if (hasMarkers(old)) {
                blockOf(old)
            } else {
                renderCustomBlock(algebraNodes(pkgId), pageSize, contextual, ids)
            }
        writeManaged(file, mergeBlock(base, fuzzy, pageSize, contextual, ids))
        true
    }.getOrElse {
        Timber.w(it, "wanxiang: applySettings failed")
        false
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
        // 只有 default.custom.yaml 需要 rime 的 customization 签名块
        val signed = if (file.name == DEFAULT_CUSTOM) CustomSignature.apply(text) else text
        file.writeText(signed)
        Timber.d("wanxiang: wrote ${file.name} (${signed.length} chars, patch=$patches)")
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
        pkgId: String = SchemaPackStore.BASE_ID,
    ) {
        runCatching {
            dir.mkdirs()
            val main = File(dir, customFileOf(pkgId))
            if (!main.exists()) {
                writeManaged(
                    main,
                    renderCustomBlock(
                        nodes = algebraNodes(
                            pkgId = pkgId,
                            fuzzy = emptySet(),
                            encode = WanxiangPrefs.ENCODE_QUANPIN,
                            scheme = WanxiangPrefs.SCHEMES.first(),
                            aux = WanxiangPrefs.AUX_NONE,
                            layout = WanxiangPrefs.LAYOUT_26,
                        ),
                        pageSize = 6,
                    ),
                )
            }
            val defaults = File(dir, DEFAULT_CUSTOM)
            if (!defaults.exists()) {
                writeManaged(defaults, renderSchemaListBlock(schemaOf(pkgId, WanxiangPrefs.LAYOUT_26)))
            }
        }.onFailure { Timber.w(it, "wanxiang: writeInitialConfigIfMissing failed") }
    }

    /** 生成 `schema_list`（只放 [enabledSchemas] 里的那一个）。 */
    private fun writeSchemaList(pkgId: String): Boolean {
        val file = File(SchemaPackStore.dir(pkgId), DEFAULT_CUSTOM)
        return runCatching {
            writeManaged(file, renderSchemaListBlock(schemaOf(pkgId, WanxiangPrefs.layout)))
            true
        }.onFailure { Timber.w(it, "wanxiang: write $DEFAULT_CUSTOM failed") }
            .getOrDefault(false)
    }

    /**
     * 把当前状态全部写盘（不动 rime，不部署）。返回是否全部成功。
     *
     * 注意：九键时也照样写 26 键那份 custom —— 幂等、无害，切回全键盘时直接就是对的。
     */
    fun writeAll(pkgId: String = activePackage()): Boolean {
        if (!SchemaPackStore.isInstalled(pkgId)) return false
        val dir = SchemaPackStore.dir(pkgId)
        val ok =
            runCatching {
                writeManaged(
                    File(dir, customFileOf(pkgId)),
                    renderCustomBlock(
                        nodes = algebraNodes(pkgId),
                        pageSize = WanxiangPrefs.pageSize,
                    ),
                )
            }.onFailure { Timber.w(it, "wanxiang: write custom of $pkgId failed") }
                .isSuccess && writeSchemaList(pkgId)

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
        var fixed = false
        listOf(SchemaPackStore.BASE_ID, SchemaPackStore.PRO_ID)
            .filter { SchemaPackStore.isInstalled(it) }
            .forEach { pkgId ->
                val dir = SchemaPackStore.dir(pkgId)
                val broken = runCatching {
                    listOf(customFileOf(pkgId), DEFAULT_CUSTOM).any { name ->
                        val f = File(dir, name)
                        if (!f.isFile) false else countTopLevelPatches(f.readText()) > 1
                    }
                }.getOrDefault(false)
                if (broken) {
                    Timber.w("wanxiang: $pkgId 发现重复 patch:，开始归一化")
                    if (runCatching { writeAll(pkgId) }.getOrDefault(false)) fixed = true
                }
            }
        return fixed
    }

    /** 读回设备上实际的 custom（给「当前生效配置」预览用）。 */
    fun readProCustom(pkgId: String = activePackage()): String =
        runCatching { File(SchemaPackStore.dir(pkgId), customFileOf(pkgId)).readText() }
            .getOrDefault("")

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
        append("候选数：").append(WanxiangPrefs.pageSize).append('\n')
        append("方案包：").append(if (isPro(activePackage())) "Pro（下载）" else "Base（内置）")
            .append('\n')
        append("启用方案：").append(activeSchema(activePackage(), WanxiangPrefs.layout))
    }

    /** manifest 里的版本号；找不到就返回空（下载功能用）。 */
    fun packVersion(pkgId: String = activePackage()): String =
        SchemaPackStore.manifest(pkgId)?.optString("version").orEmpty()
}
