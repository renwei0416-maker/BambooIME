/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.infer

import android.content.Context
import android.net.Uri
import com.osfans.trime.data.ResourceUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 微信输入法离线语音包（`ime_asr_decoder_model.apk`）的获取与安装。
 *
 * 这个包是**腾讯的资源包**，我们不分发、只让用户自己下载或导入：
 *  - 直接源：腾讯官方 CDN（无需任何鉴权，普通 GET 即可）
 *  - 镜像源：CNB release（国内更快，可选）
 *  - 本地导入：用户自己保存过的 `.apk` / `.zip`
 *
 * 包是「两层 zip」：
 *  - 外层是签名过的 apk（zip）：里面只有 `ime_asr_decoder_model.zip` + META-INF
 *  - 内层扁平放着 6 个文件：`*.xnet`（int8 权重，129.6 MB）、`dict.decoder.utf8.txt`、
 *    `dict.decoder.gbk.txt`、`feature.conf`、`sr_online.conf`、`CMS.40.bin`
 *
 * 安装后落在 `filesDir/wxasr/`，运行时由 native 层直接读 `.xnet`。
 * 下载/导入都要先校验「大小 + MD5」，再解包到暂存目录，验证通过才原子替换，
 * 避免半成品覆盖掉能用的旧包。
 */
object WxVoicePack {
    /** 官方包的精确大小，用来在解包前做第一道校验。 */
    const val EXPECTED_SIZE: Long = 102_920_639L

    /** 官方包的 MD5（WowType 里也是这个常量）。 */
    const val EXPECTED_MD5: String = "28386481eb94ff73b28ee3949c0cf690"

    /** 解包后允许的最大总字节数，防止畸形包把磁盘写满。 */
    private const val MAX_UNPACKED: Long = 220L * 1024 * 1024

    private const val OUTER_MODEL_ZIP_SUFFIX = ".zip"
    private const val DICT_NAME = "dict.decoder.utf8.txt"
    private const val XNET_SUFFIX = ".xnet"
    private const val XNET_MAGIC = "XNET"

    /** 腾讯官方 CDN（WowType 同样用它）。 */
    const val TENCENT_URL: String =
        "https://download.z.weixin.qq.com/publish_words/v1/ime_asr_decoder_model/" +
            "20260710220526250_ime_asr_decoder_model.apk"

    /** CNB 镜像的 release tag：包体放这里，国内下载更快。 */
    private const val CNB_RELEASE_TAG = "wxasr-pack-2026-07"

    /** CNB 镜像直链（与 [TENCENT_URL] 内容一致，文件名保持原样）。 */
    val CNB_URL: String =
        "https://cnb.cool/renwei.co/BambooIME/-/releases/download/$CNB_RELEASE_TAG/" +
            "ime_asr_decoder_model.apk"

    private const val APK_FILE_NAME = "ime_asr_decoder_model.apk"

    /** 一个下载源。 */
    data class Source(
        val label: String,
        val url: String,
    )

    /** 安装好之后的包信息。 */
    data class Info(
        val dir: File,
        val xnet: File,
        val dict: File,
        val bytes: Long,
    ) {
        val megabytes: Long get() = bytes / (1024 * 1024)
    }

    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // 100 MB 的包不设整体超时，靠读超时保证不会永久卡死
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 包目录：`filesDir/wxasr`。 */
    fun dir(context: Context): File = File(context.filesDir, "wxasr")

    /** 候选下载源，默认 CNB 优先、腾讯兜底（调用方可自行决定顺序）。 */
    fun sources(): List<Source> =
        listOf(
            Source("CNB 镜像", CNB_URL),
            Source("腾讯官方", TENCENT_URL),
        )

    /** 读取已安装的包；`.xnet` 头部不对或字典缺一个都算没装。 */
    fun info(context: Context): Info? {
        val dir = dir(context)
        if (!dir.isDirectory) return null
        val xnet = dir.listFiles { f -> f.isFile && f.name.endsWith(XNET_SUFFIX, ignoreCase = true) }
            ?.maxByOrNull { it.length() } ?: return null
        val dict = File(dir, DICT_NAME)
        if (!xnet.isFile || !dict.isFile) return null
        if (!hasXnetMagic(xnet)) {
            Timber.w("WxVoicePack: %s 不是合法的 xnet", xnet.name)
            return null
        }
        val bytes = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        return Info(dir, xnet, dict, bytes)
    }

    fun isInstalled(context: Context): Boolean = info(context) != null

    /** 解包后大约要占多少空间，用于安装前判断磁盘。 */
    fun requiredBytes(): Long = EXPECTED_SIZE + 140L * 1024 * 1024

    fun hasEnoughSpace(context: Context): Boolean =
        context.filesDir.usableSpace >= requiredBytes()

    /** 删除已安装的包。 */
    fun delete(context: Context): Boolean {
        val dir = dir(context)
        if (!dir.exists()) return true
        val ok = dir.deleteRecursively()
        Timber.i("WxVoicePack: delete %s -> %s", dir.absolutePath, ok)
        return ok
    }

    /**
     * 依次尝试 [sources] 下载并安装；全部失败时抛出最后一个异常。
     *
     * @param ordered 由调用方排序；不传则用 [sources] 的默认顺序
     * @param onProgress 0f..1f + 一句状态文案
     */
    suspend fun downloadAndInstall(
        context: Context,
        ordered: List<Source> = sources(),
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Info = withContext(Dispatchers.IO) {
        if (!hasEnoughSpace(context)) {
            throw IOException("存储空间不足，还需要约 ${requiredBytes() / (1024 * 1024)} MB")
        }
        var last: Throwable? = null
        for (source in ordered) {
            try {
                onProgress(0f, "正在连接 ${source.label}…")
                val apk = download(context, source, onProgress)
                val info = installApk(context, apk, onProgress)
                apk.delete()
                return@withContext info
            } catch (e: Throwable) {
                last = e
                Timber.w(e, "WxVoicePack: 源 %s 失败", source.label)
                onProgress(0f, "${source.label} 失败，换下一个源…")
            }
        }
        throw last ?: IOException("没有可用的下载源")
    }

    /** 从本地文件（用户自己保存的 `.apk` / 内层 `.zip`）安装。 */
    suspend fun installFromFile(
        context: Context,
        file: File,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Info = withContext(Dispatchers.IO) {
        if (!hasEnoughSpace(context)) {
            throw IOException("存储空间不足，还需要约 ${requiredBytes() / (1024 * 1024)} MB")
        }
        installApk(context, file, onProgress)
    }

    /** 从 SAF 选中的 Uri 安装（用户从文件管理器挑包）。 */
    suspend fun installFromUri(
        context: Context,
        uri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Info = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "wxasr_import_${System.nanoTime()}.bin")
        try {
            onProgress(0.02f, "正在读取文件…")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(staging).use { out -> input.copyTo(out, 256 * 1024) }
            } ?: throw IOException("无法读取所选文件")
            installApk(context, staging, onProgress)
        } finally {
            staging.delete()
        }
    }

    // ---------------------------------------------------------------- 下载

    private fun download(
        context: Context,
        source: Source,
        onProgress: (Float, String) -> Unit,
    ): File {
        val target = File(context.cacheDir, "wxasr_${System.nanoTime()}.apk")
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", ResourceUrls.USER_AGENT)
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: EXPECTED_SIZE
            body.byteStream().use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var lastNotify = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        if (copied - lastNotify > 2L * 1024 * 1024) {
                            lastNotify = copied
                            val ratio = (copied.toFloat() / total).coerceIn(0f, 0.99f)
                            onProgress(ratio, "下载中 ${copied / (1024 * 1024)} / ${total / (1024 * 1024)} MB")
                        }
                    }
                }
            }
        }
        onProgress(0.99f, "下载完成，正在校验…")
        return target
    }

    // ---------------------------------------------------------------- 安装

    private fun installApk(
        context: Context,
        apk: File,
        onProgress: (Float, String) -> Unit,
    ): Info {
        // 1) 大小 + MD5：官方包唯一确定，不用猜
        if (apk.length() != EXPECTED_SIZE) {
            throw IOException("包大小不对：${apk.length()} B（应为 $EXPECTED_SIZE B）")
        }
        onProgress(0.99f, "正在校验完整性…")
        val md5 = md5(apk)
        if (!md5.equals(EXPECTED_MD5, ignoreCase = true)) {
            throw IOException("包校验失败：MD5 $md5")
        }

        // 2) 解两层 zip 到暂存目录
        val staging = File(context.cacheDir, "wxasr_staging_${System.nanoTime()}")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("无法创建暂存目录")
        try {
            onProgress(0.99f, "正在解包…")
            unpackTwoLayers(apk, staging)
            val info = validate(staging)
                ?: throw IOException("包里没有可用的模型文件")

            // 3) 原子替换
            onProgress(1f, "正在安装…")
            val dest = dir(context)
            val backup = File(context.cacheDir, "wxasr_old_${System.nanoTime()}")
            if (dest.exists() && !dest.renameTo(backup)) {
                dest.deleteRecursively()
            }
            if (!staging.renameTo(dest)) {
                // rename 失败（跨挂载点）时退化成复制
                dest.mkdirs()
                staging.listFiles()?.forEach { f ->
                    f.copyTo(File(dest, f.name), overwrite = true)
                }
                staging.deleteRecursively()
            }
            backup.deleteRecursively()

            val installed = info(context) ?: throw IOException("安装后校验失败")
            Timber.i(
                "WxVoicePack: installed %s (%d MB)",
                installed.xnet.name,
                installed.megabytes,
            )
            return installed
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    /** 外层 apk → 取内层 zip → 把内层所有文件平铺解到 [dest]。 */
    private fun unpackTwoLayers(apk: File, dest: File) {
        val innerZip = File(dest.parentFile, "wxasr_inner_${System.nanoTime()}.zip")
        try {
            var found = false
            ZipInputStream(FileInputStream(apk).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory &&
                        entry.name.endsWith(OUTER_MODEL_ZIP_SUFFIX, ignoreCase = true)
                    ) {
                        FileOutputStream(innerZip).use { out -> zip.copyTo(out, 256 * 1024) }
                        found = true
                        break
                    }
                    zip.closeEntry()
                }
            }
            if (!found) {
                // 用户也可能直接给了内层 zip
                apk.copyTo(innerZip, overwrite = true)
            }

            var total = 0L
            ZipInputStream(FileInputStream(innerZip).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val name = entry.name.substringAfterLast('/')
                    if (name.isBlank()) {
                        zip.closeEntry()
                        continue
                    }
                    val out = File(dest, name)
                    FileOutputStream(out).use { fos -> zip.copyTo(fos, 256 * 1024) }
                    total += out.length()
                    if (total > MAX_UNPACKED) throw IOException("解包后体积异常，已中止")
                    zip.closeEntry()
                }
            }
        } finally {
            innerZip.delete()
        }
    }

    private fun validate(dir: File): Info? {
        val files = dir.listFiles()?.filter { it.isFile } ?: return null
        val xnet = files.firstOrNull { it.name.endsWith(XNET_SUFFIX, ignoreCase = true) } ?: return null
        val dict = files.firstOrNull { it.name == DICT_NAME } ?: return null
        if (!hasXnetMagic(xnet)) return null
        val bytes = files.sumOf { it.length() }
        return Info(dir, xnet, dict, bytes)
    }

    private fun hasXnetMagic(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 && String(head, Charsets.US_ASCII) == XNET_MAGIC
        }
    } catch (e: IOException) {
        false
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 给设置页显示用：有没有装、占多大、模型名。 */
    fun summary(context: Context): String {
        val info = info(context) ?: return "未安装（约 103 MB 下载 / 130 MB 占用）"
        return "${info.xnet.name} · ${info.megabytes} MB"
    }

    /** 用户手动保存到本地时建议的文件名。 */
    fun suggestedFileName(): String = APK_FILE_NAME
}
