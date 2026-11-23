// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.actions.ocr

import android.content.Context
import java.io.File

/**
 * 拍照识别的模型文件（PP-OCRv5 det + rec + 字典）。
 *
 * 目录：`filesDir/ocr/ppocr-v5/`。打包进 APK 的模型放在 assets `ocr/ppocr-v5/`
 * （Gradle 属性 `bundleOcrModel`，见 `app/build.gradle.kts`），首次使用拷到
 * filesDir，之后一直用磁盘上的副本 —— onnxruntime 直接从文件加载，省一份内存。
 */
object OcrModelStore {
    const val VERSION_V5 = "ppocr-v5"

    const val DET_FILE = "ch_PP-OCRv5_det_mobile.onnx"
    const val REC_FILE = "ch_PP-OCRv5_rec_mobile.onnx"
    const val CHARS_FILE = "ppocrv5-characters.txt"

    /** assets 里的内置模型目录。 */
    private const val ASSET_DIR = "ocr/ppocr-v5"

    private val assetNames = arrayOf(DET_FILE, REC_FILE, CHARS_FILE)

    data class Entry(
        val version: String,
        val det: File,
        val rec: File,
        val chars: File,
        val builtin: Boolean,
    ) {
        val ready: Boolean
            get() = det.isFile && det.length() > 0 && rec.isFile && rec.length() > 0 &&
                chars.isFile && chars.length() > 0

        val sizeBytes: Long
            get() = det.length() + rec.length() + chars.length()
    }

    fun root(context: Context): File = File(context.filesDir, "ocr")

    fun dirFor(context: Context, version: String): File = File(root(context), version)

    fun entry(context: Context, version: String = VERSION_V5): Entry {
        val dir = dirFor(context, version)
        return Entry(
            version = version,
            det = File(dir, DET_FILE),
            rec = File(dir, REC_FILE),
            chars = File(dir, CHARS_FILE),
            builtin = true,
        )
    }

    /**
     * 首次使用时把 assets 里的模型拷到 filesDir。
     * 已经拷过（文件长度一致）就直接复用，不重复写盘。
     */
    fun ensureInstalled(context: Context): Entry {
        val entry = entry(context)
        if (entry.ready) return entry
        val dir = dirFor(context, entry.version)
        if (!dir.isDirectory && !dir.mkdirs()) return entry
        for (name in assetNames) {
            val target = File(dir, name)
            if (target.isFile && target.length() > 0) continue
            context.assets.open("$ASSET_DIR/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        }
        return entry
    }

    fun delete(context: Context, version: String = VERSION_V5): Boolean =
        dirFor(context, version).deleteRecursively()
}
