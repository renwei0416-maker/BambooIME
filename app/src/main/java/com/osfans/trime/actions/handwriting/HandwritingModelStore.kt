// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.actions.handwriting

import android.content.Context
import com.osfans.trime.BuildConfig
import timber.log.Timber
import java.io.File

/**
 * 手写模型文件管理：内置副本、下载副本、本地导入副本、删除。
 *
 * 目录：`filesDir/handwriting/<版本>/ochwpro.onnx` + `char_index.json`。
 * 打包进 APK 的模型放在 assets `handwriting/ochwpro/`（Gradle 属性
 * `bundleHandwritingModel=false` 出「无模型」版时这段资产不存在），
 * 首次使用时拷到 filesDir，之后一直用磁盘上的副本。
 */
object HandwritingModelStore {
    /** 内置版本（微调过 100 个常用字，默认用它）。 */
    const val VERSION_V1_1 = "v1.1"

    /** 上游原始版本，作为可下载项保留。 */
    const val VERSION_V1_0 = "v1.0"

    const val MODEL_FILE = "ochwpro.onnx"
    const val CHARS_FILE = "char_index.json"

    /** assets 里的内置模型目录。 */
    private const val ASSET_DIR = "handwriting/ochwpro"

    data class Entry(
        val version: String,
        val model: File,
        val chars: File,
        val builtin: Boolean,
    ) {
        val ready: Boolean get() = model.isFile && model.length() > 0 && chars.isFile
        val sizeBytes: Long get() = model.length() + chars.length()
    }

    fun root(context: Context): File = File(context.filesDir, "handwriting")

    fun dirFor(context: Context, version: String): File = File(root(context), version)

    fun entry(context: Context, version: String): Entry {
        val dir = dirFor(context, version)
        return Entry(
            version = version,
            model = File(dir, MODEL_FILE),
            chars = File(dir, CHARS_FILE),
            builtin = version == VERSION_V1_1 && BuildConfig.BUNDLED_HANDWRITING_MODEL,
        )
    }

    /** 这份包有没有把模型打进 APK。 */
    val isBundledBuild: Boolean get() = BuildConfig.BUNDLED_HANDWRITING_MODEL

    /**
     * 确保某个版本的模型已落在磁盘上。内置版本会从 assets 拷出来（只拷一次），
     * 下载版本则要求用户先下载过。
     */
    fun ensureInstalled(context: Context, version: String): Boolean {
        val e = entry(context, version)
        if (e.ready) return true
        if (version != VERSION_V1_1 || !BuildConfig.BUNDLED_HANDWRITING_MODEL) return false
        return runCatching { copyBuiltin(context) }
            .onFailure { Timber.w(it, "handwriting: copy builtin model failed") }
            .isSuccess && entry(context, version).ready
    }

    private fun copyBuiltin(context: Context) {
        val dir = dirFor(context, VERSION_V1_1)
        if (!dir.exists() && !dir.mkdirs()) error("cannot create ${dir.absolutePath}")
        for (name in listOf(MODEL_FILE, CHARS_FILE)) {
            val target = File(dir, name)
            if (target.isFile && target.length() > 0) continue
            val tmp = File(dir, "$name.part")
            context.assets.open("$ASSET_DIR/$name").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Timber.i("handwriting: installed %s (%s bytes)", target.name, target.length())
        }
    }

    /** 列表：内置 v1.1 与可下载的 v1.0。 */
    fun entries(context: Context): List<Entry> =
        listOf(VERSION_V1_1, VERSION_V1_0).map { entry(context, it) }

    fun delete(context: Context, version: String): Boolean {
        val dir = dirFor(context, version)
        if (!dir.exists()) return true
        return runCatching { dir.deleteRecursively() }
            .onFailure { Timber.w(it, "handwriting: delete %s failed", version) }
            .getOrDefault(false)
    }

    /** 从本地文件导入：需要 onnx 与 char_index.json，导入后位于 [version] 目录。 */
    fun import(context: Context, version: String, modelFile: File, charsFile: File): Boolean {
        if (!modelFile.isFile || modelFile.length() == 0L) return false
        if (!charsFile.isFile || charsFile.length() == 0L) return false
        val dir = dirFor(context, version)
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) error("cannot create dir")
            modelFile.copyTo(File(dir, MODEL_FILE), overwrite = true)
            charsFile.copyTo(File(dir, CHARS_FILE), overwrite = true)
            true
        }.onFailure { Timber.w(it, "handwriting: import %s failed", version) }
            .getOrDefault(false)
    }
}
