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
 * 写法约定：
 *
 *  * **只动我们自己的托管块**（`# >>> BambooIME` 与 `# <<< BambooIME` 之间），块外内容
 *    （用户手写的 node、注释、`__include`）原样保留；
 *  * 文件不存在才写整份模板；
 *  * 每次写入前把旧文件备份成 `*.bak-<时间戳>`，只保留最近 3 份；
 *  * 不写任何官方默认就是关闭的项（`enable_user_dict` / `contextual_suggestions`
 *    只在用户真的打开时才出现）。
 */
object WanxiangCustom {

    const val SCHEMA_PRO = "wanxiang_pro"
    const val SCHEMA_T9 = "wanxiang_t9_pro"

    private const val PRO_CUSTOM = "wanxiang_pro.custom.yaml"
    private const val T9_CUSTOM = "wanxiang_t9_pro.custom.yaml"
    private const val DEFAULT_CUSTOM = "default.custom.yaml"

    private const val BEGIN = "# >>> BambooIME managed（这一段由「万象拼音」设置页生成，手改会被覆盖）"
    private const val END = "# <<< BambooIME managed"

    /** 按布局决定用哪个方案。 */
    fun activeSchema(layout: Int): String =
        if (layout == WanxiangPrefs.LAYOUT_9) SCHEMA_T9 else SCHEMA_PRO

    /** 按布局决定要启用哪些方案（九键只启 t9，其余只启 26 键 Pro）。 */
    fun enabledSchemas(layout: Int): Array<String> = arrayOf(activeSchema(layout))

    // ------------------------------------------------------------ algebra

    /**
     * 顺序照官方模板：模糊音 → 编码方案 → 辅助码 → 键位映射。
     *
     * 键位映射必须放最后（9/14/18/26 都是「字母级别」的折叠，放在前面会把源码改掉）。
     */
    fun algebraNodes(): List<String> {
        val nodes = mutableListOf<String>()

        WanxiangPrefs.FUZZY_KEYS.forEach { key ->
            if (WanxiangPrefs.fuzzy.contains(key)) {
                nodes += "wanxiang_algebra:/模糊音_$key"
            }
        }

        nodes += if (WanxiangPrefs.encode == WanxiangPrefs.ENCODE_QUANPIN) {
            "wanxiang_algebra:/pro/全拼"
        } else {
            "wanxiang_algebra:/pro/${WanxiangPrefs.scheme}"
        }

        when (WanxiangPrefs.aux) {
            WanxiangPrefs.AUX_DIRECT -> nodes += "wanxiang_algebra:/pro/直接辅助"
            WanxiangPrefs.AUX_INDIRECT -> nodes += "wanxiang_algebra:/pro/间接辅助"
            else -> Unit // 默认不启用辅助码
        }

        // 九键本身是独立方案，26 键方案里固定留 26jian（恒等），保证切回来时正常
        nodes += when (WanxiangPrefs.layout) {
            WanxiangPrefs.LAYOUT_18 -> "wanxiang_algebra:/18jian"
            WanxiangPrefs.LAYOUT_14 -> "wanxiang_algebra:/14jian"
            else -> "wanxiang_algebra:/26jian"
        }

        return nodes
    }

    // ------------------------------------------------------------ 渲染

    private fun renderProBlock(): String = buildString {
        appendLine(BEGIN)
        appendLine("patch:")
        appendLine("  speller/algebra:")
        appendLine("    __patch:")
        algebraNodes().forEach { appendLine("      - $it") }
        appendLine("  menu/page_size: ${WanxiangPrefs.pageSize}")
        if (WanxiangPrefs.userDict) {
            appendLine("  translator/enable_user_dict: true")
        }
        if (WanxiangPrefs.contextual) {
            appendLine("  translator/contextual_suggestions: true")
        }
        append(END)
    }

    private fun renderT9Block(): String = buildString {
        appendLine(BEGIN)
        appendLine("patch:")
        appendLine("  menu/page_size: ${WanxiangPrefs.t9PageSize}")
        appendLine("  translator/enable_user_dict: ${WanxiangPrefs.t9UserDict}")
        if (WanxiangPrefs.t9Contextual) {
            appendLine("  translator/contextual_suggestions: true")
        } else {
            appendLine("  translator/contextual_suggestions: false")
        }
        append(END)
    }

    // ------------------------------------------------------------ 写入

    /** 把 [block] 塞进 [file] 的托管块位置；块外原样保留。 */
    private fun writeManaged(file: File, block: String) {
        val old = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        if (old != null) backup(file)

        val text = if (old == null) {
            """
            # 由 BambooIME「万象拼音」设置页生成；块外可以自己加东西，块内会被覆盖。
            $block
            """.trimIndent() + "\n"
        } else {
            val b = old.indexOf(BEGIN)
            val e = old.indexOf(END)
            when {
                b >= 0 && e > b -> old.substring(0, b) + block + old.substring(e + END.length)
                else -> old.trimEnd() + "\n\n" + block + "\n"
            }
        }
        file.writeText(text)
        Timber.d("wanxiang: wrote ${file.name} (${text.length} chars)")
    }

    private fun backup(file: File) {
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val bak = File(file.parentFile, "${file.name}.bak-$stamp")
            file.copyTo(bak, overwrite = true)
            // 只留最近 3 份
            file.parentFile?.listFiles { f -> f.name.startsWith("${file.name}.bak-") }
                ?.sortedByDescending { it.name }
                ?.drop(3)
                ?.forEach { it.delete() }
        }.onFailure { Timber.w(it, "wanxiang: backup ${file.name} failed") }
    }

    /** 生成 `schema_list`（只放 [enabledSchemas] 里的那一个）。 */
    private fun writeSchemaList(): Boolean {
        val file = File(SchemaPackStore.dir(), DEFAULT_CUSTOM)
        val schema = activeSchema(WanxiangPrefs.layout)
        val block = buildString {
            appendLine(BEGIN)
            appendLine("patch:")
            appendLine("  schema_list:")
            appendLine("    - {schema: $schema}")
            append(END)
        }
        return runCatching { writeManaged(file, block); true }
            .onFailure { Timber.w(it, "wanxiang: write $DEFAULT_CUSTOM failed") }
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
        ok = runCatching { writeManaged(File(dir, PRO_CUSTOM), renderProBlock()) }
            .onFailure { Timber.w(it, "wanxiang: write $PRO_CUSTOM failed") }
            .isSuccess && ok
        ok = runCatching { writeManaged(File(dir, T9_CUSTOM), renderT9Block()) }
            .onFailure { Timber.w(it, "wanxiang: write $T9_CUSTOM failed") }
            .isSuccess && ok
        ok = writeSchemaList() && ok

        if (ok) WanxiangPrefs.appliedSignature = WanxiangPrefs.signature()
        return ok
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
