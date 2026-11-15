/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.packaging

import com.osfans.trime.data.base.DataManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * 把「启用哪些方案」写进**指定方案包自己**的 `default.custom.yaml`。
 *
 * 为什么需要它：上游 `Rime.selectSchemaCrossPackage(schemaId)` 是按**方案名**反查包的
 * （`SchemaPackageManager.findPackageForSchema()` 取 `discoverPackages()` 里排序靠前的
 * 第一个命中项），当两个包里存在同名方案（例如都叫 `wanxiang_pro`）时，就只会命中
 * 排在前面的那个包，导致「点 A 包、实际改到 B 包、还被切到 B 包」。
 *
 * 这里改成按**包名**（也就是目录名）定位——点谁改谁，不存在歧义。
 *
 * 写盘规则（与 `WanxiangCustom` 同一套思路）：
 * - 只替换 `patch:` 下的 `schema_list` 这一个键；
 * - 文件里其余内容（其它 patch 键、顶层键、注释）原样保留；
 * - 改写前把原文件备份成 `<name>.bak-yyyyMMdd-HHmmss`，最多保留 3 份；
 * - 已存在的 `schema_list` 若缩进不是 2 空格也能识别（按 `patch:` 下第一个子键的缩进自适应）。
 */
object PackageSchemaList {
    private const val FILE_NAME = "default.custom.yaml"
    private const val MAX_BACKUPS = 3

    /** 写入指定包的 `schema_list`；返回是否成功。 */
    fun write(packageId: String, schemaIds: List<String>): Boolean {
        if (packageId.isEmpty() || schemaIds.isEmpty()) return false
        return try {
            val dir = File(DataManager.userDataBaseDir, packageId)
            if (!dir.isDirectory) {
                Timber.w("PackageSchemaList: %s is not a package directory", packageId)
                return false
            }
            val file = File(dir, FILE_NAME)
            val old = if (file.exists()) file.readText() else ""
            if (file.exists()) backup(file, old)
            val merged = withSchemaList(old, render(schemaIds))
            // 补 rime 的 customization 签名块（见 CustomSignature 的说明）
            val signed = CustomSignature.apply(merged)
            file.writeText(signed)
            Timber.i("PackageSchemaList: %s -> %s", packageId, schemaIds.joinToString(","))
            true
        } catch (e: Exception) {
            Timber.w(e, "PackageSchemaList: failed to write %s", packageId)
            false
        }
    }

    /** 读回某个包当前启用的方案 id（解析失败返回空列表）。 */
    fun read(packageId: String): List<String> = try {
        SchemaPackageManager.getEnabledSchemaIds(packageId).toList()
    } catch (e: Exception) {
        Timber.w(e, "PackageSchemaList: failed to read %s", packageId)
        emptyList()
    }

    private fun render(schemaIds: List<String>): String = buildString {
        append("  schema_list:\n")
        schemaIds.forEach { append("    - schema: ").append(it).append('\n') }
    }

    /**
     * 把 [block]（已经缩进 2 空格的 `schema_list` 段）合并进 [old]。
     * 有 `patch:` 就替换它下面的同名键，没有就补一个 `patch:` 段。
     */
    private fun withSchemaList(old: String, block: String): String {
        val lines = old.replace("\r\n", "\n").split("\n").toMutableList()
        // 去掉末尾空行，稍后统一补一个换行
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)

        val patchIndex = lines.indexOfFirst { it.trimEnd() == "patch:" && indentOf(it) == 0 }
        if (patchIndex < 0) {
            val head = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
            return "patch:\n" + block + head
        }

        // patch: 下第一个非空、非注释行的缩进，作为子键缩进基准
        var childIndent = 2
        for (i in patchIndex + 1 until lines.size) {
            val raw = lines[i]
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            childIndent = indentOf(raw)
            break
        }

        // patch 段结束位置：缩进小于 childIndent 的非空行
        var end = lines.size
        for (i in patchIndex + 1 until lines.size) {
            val raw = lines[i]
            if (raw.isBlank()) continue
            if (indentOf(raw) < childIndent) {
                end = i
                break
            }
        }

        // 段内是否已有 schema_list
        val listIndex = (patchIndex + 1 until end).firstOrNull {
            indentOf(lines[it]) == childIndent && lines[it].trimStart().startsWith("schema_list:")
        }

        val blockLines = block.split("\n").filter { it.isNotEmpty() }
        val rewrite = if (childIndent == 2) {
            blockLines
        } else {
            // 保持用户原来的缩进风格
            blockLines.map { if (it.startsWith("  ")) " ".repeat(childIndent) + it.trimStart() else it }
        }

        if (listIndex == null) {
            lines.addAll(patchIndex + 1, rewrite)
        } else {
            // 替换整个 schema_list 键（到下一个同级键或段尾）
            var stop = end
            for (i in listIndex + 1 until end) {
                val raw = lines[i]
                if (raw.isBlank()) continue
                if (indentOf(raw) <= childIndent) {
                    stop = i
                    break
                }
            }
            val tail = lines.subList(stop, lines.size).toMutableList()
            val head = lines.subList(0, listIndex).toMutableList()
            head.addAll(rewrite)
            head.addAll(tail)
            lines.clear()
            lines.addAll(head)
        }
        return lines.joinToString("\n") + "\n"
    }

    private fun indentOf(line: String): Int {
        var n = 0
        while (n < line.length && line[n] == ' ') n++
        return n
    }

    private fun backup(file: File, content: String) {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(file.parentFile, "${file.name}.bak-$stamp").writeText(content)
            file.parentFile?.listFiles()
                ?.filter { it.name.startsWith("${file.name}.bak-") }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_BACKUPS)
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Timber.w(e, "PackageSchemaList: backup failed for %s", file.name)
        }
    }
}
