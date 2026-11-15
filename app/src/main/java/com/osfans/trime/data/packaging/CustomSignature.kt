/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.packaging

import com.osfans.trime.BuildConfig
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * 给 `default.custom.yaml` 写 librime 那套 `customization:` 签名块。
 *
 * 为什么要自己写：librime 只在**它自己**保存 custom 文件时才签名
 * （`Signature::Sign()`，见 `librime/src/rime/lever/custom_settings.cc` 的
 * `CustomSettings::Save()`）。我们从「写进我们释放的那个包」开始改成自己拼 YAML
 * 文本落盘（[PackageSchemaList] / `WanxiangCustom`），绕过了那条保存路径，
 * 所以文件里只有 `patch:`、没有签名。
 *
 * 签名块有两个实际用途：
 * 1. 溯源——一眼看出这份文件是哪个发行版、哪个版本、什么时候写的；
 * 2. `CustomSettings::IsFirstRun()` 的判据就是「文件里有没有 `customization` 这一层」，
 *    缺了它 librime 会一直认为这是首次运行。
 *
 * 注意 `generator` 不写 `Rime::SwitcherSettings`——那份文件不是 rime 的方案选单写的，
 * 冒用它的名字只会在排障时误导。
 */
object CustomSignature {
    /** 对应 `librime/CMakeLists.txt` 的 `set(rime_version 1.17.0)`。 */
    const val RIME_VERSION = "1.17.0"

    private const val GENERATOR = "BambooIME::SchemaPackageList"
    private const val KEY = "customization:"

    /** 生成签名块（行尾统一 `\n`）。 */
    fun render(now: Date = Date()): String = buildString {
        appendLine(KEY)
        appendLine("  distribution_code_name: bambooime")
        appendLine("  distribution_version: \"${BuildConfig.BUILD_VERSION_NAME}\"")
        appendLine("  generator: \"$GENERATOR\"")
        appendLine("  modified_time: \"${ctime(now)}\"")
        appendLine("  rime_version: $RIME_VERSION")
    }

    /**
     * 把签名块合并进 [text]：已有 `customization:` 就原地替换整段（它的缩进子行一直到
     * 下一个顶格行为止），没有就放到文件最前面。其余内容一个字节都不动。
     */
    fun apply(text: String): String = try {
        val block = render().trimEnd('\n').split('\n')
        val lines = text.replace("\r\n", "\n").split("\n").toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)

        val start = lines.indexOfFirst { it.trimEnd() == KEY && !it.startsWith(" ") && !it.startsWith("\t") }
        val out = if (start < 0) {
            (block + lines).toMutableList()
        } else {
            var end = start + 1
            while (end < lines.size) {
                val raw = lines[end]
                if (raw.isNotBlank() && !raw.startsWith(" ") && !raw.startsWith("\t")) break
                end++
            }
            (lines.subList(0, start) + block + lines.subList(end, lines.size)).toMutableList()
        }
        // 去掉首尾多余空行，结尾补一个换行
        while (out.isNotEmpty() && out.first().isBlank()) out.removeAt(0)
        out.joinToString("\n") + "\n"
    } catch (e: Exception) {
        Timber.w(e, "customization: apply failed, leave file unchanged")
        text
    }

    /** 模仿 `ctime()`：`Sat Nov 15 08:12:00 2026`（日期不补零、用空格补齐两位）。 */
    private fun ctime(date: Date): String {
        val cal = Calendar.getInstance().apply { time = date }
        val head = SimpleDateFormat("EEE MMM ", Locale.US).format(date)
        val tail = SimpleDateFormat("HH:mm:ss yyyy", Locale.US).format(date)
        return head + String.format(Locale.US, "%2d", cal.get(Calendar.DAY_OF_MONTH)) + tail
    }
}
