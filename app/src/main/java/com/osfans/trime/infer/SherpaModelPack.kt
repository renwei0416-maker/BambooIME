/*
 * Copyright (C) 2025 BambooIME
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.osfans.trime.infer

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * sherpa-onnx 多模型包管理器（「其它多模型」方案用）。
 *
 * 模型不随 APK 分发，按需下载后解包到 `filesDir/sherpa-models/<model_id>/`：
 * - `paraformer_zh_int8`   中文（离线 CTC，单文件 model.int8.onnx）
 * - `sensevoice_multi_int8` 中英日韩粤（离线多语）
 * - `xasr_offline_int8`    中英（离线 transducer，自带标点，用作中文定稿校正）
 *
 * 上游原件是 `.tar.bz2`（Android 只有 deflate，解不了 bz2），
 * 因此改用**内容完全相同**的 `.zip` 重新打包后镜像到 CNB，
 * 直接复用现有 `ZipInputStream` 解包路径，不需要额外依赖。
 *
 * 下载只走 CNB（GitHub 上只有原始 tar.bz2，没有我们重打包的 zip；
 * 且用户网络环境里 github.com 常常不可达）。导入走 SAF 兜底。
 */
object SherpaModelPack {
    /** CNB release 前缀（GET 会 302 到 asset.cnb.cool，OkHttp 默认跟随重定向）。 */
    const val CNB_BASE: String =
        "https://cnb.cool/renwei.co/BambooIME/-/releases/download/sherpa-models-2026-11"

    /** manifest 里记录的上游原始资产（只在 CNB 上，供人手动下载）。 */
    const val MANIFEST_NAME: String = "manifest.json"

    private const val DIR_NAME = "sherpa-models"
    private const val BUFFER = 256 * 1024

    data class Source(val label: String, val url: String)

    /**
     * 一个模型包的静态描述（与 CNB 上的 manifest.json 保持一致）。
     *
     * @param files 包内必须存在的文件与其字节数，用来判断「装好了没有、装全了没有」
     */
    data class Spec(
        val id: String,
        val label: String,
        val zipName: String,
        val bytes: Long,
        val sha256: String,
        val files: List<Pair<String, Long>>,
        /** 主模型 onnx 文件名（SenseVoice/Paraformer 是 model.int8.onnx，X-ASR 是 encoder …） */
        val primaryFile: String,
    ) {
        val megabytes: Long get() = bytes / (1024 * 1024)
    }

    val PARAFORMER = Spec(
        id = "paraformer_zh_int8",
        label = "中文模型 · Paraformer",
        zipName = "sherpa-onnx-paraformer-zh-int8-2025-10-07.zip",
        bytes = 217_633_564L,
        sha256 = "d4a0a52c8045a3ac73c59220392f1ed8ae1399d47989292e4e5593e1dcf11638",
        files = listOf("model.int8.onnx" to 238_429_929L, "tokens.txt" to 75_756L),
        primaryFile = "model.int8.onnx",
    )

    val SENSE_VOICE = Spec(
        id = "sensevoice_multi_int8",
        label = "多语言模型 · SenseVoice",
        zipName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.zip",
        bytes = 159_083_767L,
        sha256 = "e158f0903986af025c211d9f91cc5c275a26a6d8f969e5a71e277e8df63e9228",
        files = listOf("model.int8.onnx" to 237_115_547L, "tokens.txt" to 315_894L),
        primaryFile = "model.int8.onnx",
    )

    val X_ASR = Spec(
        id = "xasr_offline_int8",
        label = "中文定稿模型 · X-ASR",
        zipName = "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03.zip",
        bytes = 132_565_584L,
        sha256 = "cf8bd797bfa7301314fafbdc934a14d4fe26def42d1fe5a9080d5bfbf02dce0c",
        files = listOf(
            "encoder-epoch-99-avg-1.int8.onnx" to 161_015_713L,
            "decoder-epoch-99-avg-1.onnx" to 11_309_084L,
            "joiner-epoch-99-avg-1.int8.onnx" to 2_581_422L,
            "tokens.txt" to 58_806L,
            "bpe.model" to 119_265L,
        ),
        primaryFile = "encoder-epoch-99-avg-1.int8.onnx",
    )

    /** 标点补全（sherpa-onnx CT-Transformer，中英；只有逗号/句号/问号/顿号，无感叹号）。 */
    val PUNCT_ZH_EN = Spec(
        id = "punct_zh_en_int8",
        label = "标点模型 · 中英",
        zipName = "punct-ct-transformer-zh-en-vocab272727-int8.zip",
        bytes = 75_519_326L,
        sha256 = "1d1708995f5c557d7efec0e31cdbd25ee32f2e0f89bc61317686e91b525b145b",
        files = listOf("model.int8.onnx" to 75_519_198L),
        primaryFile = "model.int8.onnx",
    )

    val ALL: List<Spec> = listOf(PARAFORMER, SENSE_VOICE, X_ASR, PUNCT_ZH_EN)

    fun spec(id: String): Spec? = ALL.firstOrNull { it.id == id }

    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 所有模型的父目录。 */
    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 某个模型的落盘目录。 */
    fun modelDir(context: Context, id: String): File = File(dir(context), id)

    fun sources(spec: Spec): List<Source> = listOf(
        Source("CNB", "$CNB_BASE/${spec.zipName}"),
    )

    /** 已安装且文件齐全时返回目录，否则 null。 */
    fun installedDir(context: Context, id: String): File? {
        val spec = spec(id) ?: return null
        val dir = modelDir(context, id)
        if (!dir.isDirectory) return null
        for ((name, size) in spec.files) {
            val f = File(dir, name)
            if (!f.isFile || f.length() != size) return null
        }
        return dir
    }

    fun isInstalled(context: Context, id: String): Boolean = installedDir(context, id) != null

    /** 已安装模型的占用字节数（用于设置页副标题）。 */
    fun installedBytes(context: Context): Long {
        val root = dir(context)
        if (!root.isDirectory) return 0L
        return ALL.sumOf { spec ->
            val d = modelDir(context, spec.id)
            if (d.isDirectory) d.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L else 0L
        }
    }

    fun summary(context: Context): String {
        val installed = ALL.filter { isInstalled(context, it.id) }
        if (installed.isEmpty()) return "未安装"
        val mb = installedBytes(context) / (1024 * 1024)
        return "已安装 ${installed.size} 个 · ${mb} MB"
    }

    /** 下载需要预留的空间：压缩包 + 解压后（约 2.1 倍）留余量。 */
    fun requiredBytes(spec: Spec): Long = spec.bytes + spec.files.sumOf { it.second } + 64L * 1024 * 1024

    fun hasEnoughSpace(context: Context, spec: Spec): Boolean {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        return stat.availableBytes >= requiredBytes(spec)
    }

    fun delete(context: Context, id: String): Boolean {
        val dir = modelDir(context, id)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    fun deleteAll(context: Context): Boolean {
        val root = dir(context)
        if (!root.exists()) return true
        return root.deleteRecursively()
    }

    /**
     * 下载并安装。
     *
     * @param onProgress 第一个参数 0..1，第二个参数是给人看的阶段文字
     * @return 安装成功返回 true
     */
    suspend fun downloadAndInstall(
        context: Context,
        spec: Spec,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Boolean {
        val ordered = sources(spec)
        var lastError: Exception? = null
        for ((index, source) in ordered.withIndex()) {
            val tag = if (ordered.size > 1) "（${index + 1}/${ordered.size}）" else ""
            try {
                onProgress(0f, "下载源：${source.label}$tag")
                val zip = download(context, spec, source, onProgress)
                installZip(context, spec, zip, onProgress)
                zip.delete()
                return true
            } catch (e: Exception) {
                lastError = e
                onProgress(0f, "${source.label} 下载失败：${e.message}")
            }
        }
        throw lastError ?: IOException("没有可用的下载源")
    }

    /** 从系统文件选择器导入一个 zip。 */
    suspend fun installFromUri(
        context: Context,
        spec: Spec,
        uri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "sherpa_import_${System.nanoTime()}.zip")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                    }
                }
            } ?: throw IOException("无法读取所选文件")
            installZip(context, spec, staging, onProgress)
            true
        } finally {
            staging.delete()
        }
    }

    private suspend fun download(
        context: Context,
        spec: Spec,
        source: Source,
        onProgress: (Float, String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "sherpa_${spec.id}_${System.nanoTime()}.zip")
        val request = Request.Builder().url(source.url).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("响应为空")
            val total = body.contentLength().takeIf { it > 0 } ?: spec.bytes
            var copied = 0L
            BufferedInputStream(body.byteStream(), BUFFER).use { input ->
                BufferedOutputStream(FileOutputStream(target), BUFFER).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        val ratio = (copied.toFloat() / total).coerceIn(0f, 0.99f)
                        onProgress(ratio, "下载 ${source.label} ${copied / (1024 * 1024)}/${total / (1024 * 1024)} MB")
                    }
                }
            }
        }
        if (target.length() != spec.bytes) {
            target.delete()
            throw IOException("大小不符（${target.length()} ≠ ${spec.bytes}）")
        }
        val digest = sha256(target)
        if (!digest.equals(spec.sha256, ignoreCase = true)) {
            target.delete()
            throw IOException("校验失败（sha256 不符）")
        }
        target
    }

    private suspend fun installZip(
        context: Context,
        spec: Spec,
        zip: File,
        onProgress: (Float, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        onProgress(0.99f, "解包 ${spec.label}")
        val staging = File(context.cacheDir, "sherpa_staging_${spec.id}_${System.nanoTime()}")
        staging.mkdirs()
        try {
            ZipInputStream(BufferedInputStream(zip.inputStream(), BUFFER)).use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    if (name.isEmpty() || name == "." || name == "..") continue
                    val output = File(staging, name)
                    BufferedOutputStream(FileOutputStream(output), BUFFER).use { out ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            for ((name, size) in spec.files) {
                val f = File(staging, name)
                if (!f.isFile) throw IOException("包内缺少 $name")
                if (f.length() != size) throw IOException("$name 大小不符（${f.length()} ≠ $size）")
            }
            val target = modelDir(context, spec.id)
            if (target.exists()) target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!staging.renameTo(target)) {
                // renameTo 在某些文件系统上跨目录失败，退回复制
                target.mkdirs()
                staging.listFiles()?.forEach { f ->
                    val dst = File(target, f.name)
                    f.copyTo(dst, overwrite = true)
                    f.delete()
                }
                staging.deleteRecursively()
            }
            onProgress(1f, "已安装 ${spec.label}")
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(file.inputStream(), BUFFER).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
