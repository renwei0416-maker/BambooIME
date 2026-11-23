/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.osfans.trime.BuildConfig
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.util.Const
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

@Serializable
data class UpdateAsset(
    val name: String = "",
    val size: Long = 0L,
    val sha256: String = "",
    val urls: List<String> = emptyList(),
)

@Serializable
data class UpdateManifest(
    val versionName: String = "",
    val versionCode: Int = 0,
    val publishedAt: String = "",
    val notes: String = "",
    val apk: UpdateAsset? = null,
    val themes: UpdateAsset? = null,
)

/**
 * 检查/下载/安装更新。
 *
 * 设计约束（见 repo\docs\version.json 的字段说明）：
 * - 清单是纯静态 json，放仓库 docs/ 下，GitHub release 资产为主源、GitHub raw 为备源；
 * - 普通用户不需要 token（raw 与 release 资产都是公开直链）；
 * - 只能拉起系统安装器，不做静默安装；
 * - 主题包必须是 zip（app 内只有 ZipInputStream）。
 */
object UpdateCenter {
    /** 主源：GitHub 最新 release 里的 version.json 资产（地址稳定，每次发版覆盖上传） */
    private const val GH_RELEASE_RAW =
        "https://github.com/renwei0416-maker/BambooIME/releases/latest/download/version.json"
    /** 备源：GitHub main 分支 docs/version.json */
    private const val GH_RAW =
        "https://raw.githubusercontent.com/renwei0416-maker/BambooIME/main/docs/version.json"

    const val SOURCE_AUTO = 0
    const val SOURCE_CNB = 1
    const val SOURCE_GITHUB = 2

    private const val PREF = "bamboo_update"
    private const val KEY_AUTO = "auto_check"
    private const val KEY_LAST = "last_check_ms"
    private const val KEY_IGNORE = "ignored_version_code"
    private const val KEY_SOURCE = "source"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun sp(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isAutoCheck(context: Context): Boolean = sp(context).getBoolean(KEY_AUTO, true)

    fun setAutoCheck(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO, value).apply()
    }

    fun lastCheckMs(context: Context): Long = sp(context).getLong(KEY_LAST, 0L)

    fun source(context: Context): Int = sp(context).getInt(KEY_SOURCE, SOURCE_AUTO)

    fun setSource(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_SOURCE, value).apply()
    }

    fun ignoredVersionCode(context: Context): Int = sp(context).getInt(KEY_IGNORE, 0)

    fun ignore(context: Context, versionCode: Int) {
        sp(context).edit().putInt(KEY_IGNORE, versionCode).apply()
    }

    fun currentVersionCode(): Int = BuildConfig.VERSION_CODE

    fun currentVersionName(): String = Const.VERSION_NAME

    /** 有更新且没被忽略。 */
    fun hasUpdate(context: Context, manifest: UpdateManifest): Boolean {
        if (manifest.versionCode > 0) {
            return manifest.versionCode > currentVersionCode() &&
                manifest.versionCode != ignoredVersionCode(context)
        }
        // 清单没写 versionCode 时退化为版本名字符串比较
        val latest = manifest.versionName.trim()
        return latest.isNotEmpty() && latest != currentVersionName().trim()
    }

    private fun urls(context: Context, cnb: String, github: String): List<String> = when (source(context)) {
        SOURCE_CNB -> listOf(cnb)
        SOURCE_GITHUB -> listOf(github)
        else -> listOf(cnb, github)
    }

    private fun manifestUrls(context: Context) = urls(context, GH_RELEASE_RAW, GH_RAW)

    suspend fun fetchManifest(context: Context): Result<UpdateManifest> = withContext(Dispatchers.IO) {
        val errors = StringBuilder()
        for (url in manifestUrls(context)) {
            val result = runCatching {
                val request = Request.Builder().url(url).header("Accept", "application/json").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) error("空响应")
                    json.decodeFromString<UpdateManifest>(body)
                }
            }
            result.onSuccess {
                sp(context).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                return@withContext Result.success(it)
            }.onFailure {
                errors.append(url.substringBefore("/-/").substringBefore("/main")).append(": ")
                    .append(it.message).append('\n')
            }
        }
        Result.failure(IllegalStateException(errors.toString().trim().ifEmpty { "没有可用的更新源" }))
    }

    /** 启动时静默检查：返回带更新的清单，没有则 null。 */
    suspend fun silentCheck(context: Context): UpdateManifest? {
        if (!isAutoCheck(context)) return null
        val manifest = fetchManifest(context).getOrNull() ?: return null
        return manifest.takeIf { hasUpdate(context, it) }
    }

    suspend fun download(
        context: Context,
        asset: UpdateAsset,
        targetName: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (url in asset.urls.ifEmpty { urls(context, "", "") }) {
            if (url.isBlank()) continue
            try {
                val target = File(context.cacheDir, targetName)
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("空响应")
                    val total = body.contentLength().takeIf { it > 0 } ?: asset.size
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var copied = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                output.write(buffer, 0, n)
                                copied += n
                                if (total > 0) {
                                    onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
                                }
                            }
                        }
                    }
                }
                if (asset.size > 0 && target.length() != asset.size) {
                    error("大小不符：${target.length()} != ${asset.size}")
                }
                if (asset.sha256.isNotBlank()) {
                    val actual = sha256(target)
                    if (!actual.equals(asset.sha256, ignoreCase = true)) {
                        error("sha256 不符：$actual")
                    }
                }
                Timber.i("Update: downloaded %s -> %s", url, target.absolutePath)
                return@withContext target
            } catch (t: Throwable) {
                Timber.w(t, "Update: download failed from %s", url)
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    fun installApk(context: Context, apk: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 解 zip 到用户主题目录；返回解出的文件数。 */
    suspend fun installThemes(
        context: Context,
        zip: File,
        onProgress: (Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val root = DataManager.userThemesDir
        root.mkdirs()
        val totalEntries = runCatching {
            ZipInputStream(zip.inputStream()).use { input ->
                var count = 0
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (!entry.isDirectory) count++
                }
                count
            }
        }.getOrDefault(0)
        var written = 0
        ZipInputStream(zip.inputStream()).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                var name = entry.name.replace('\\', '/')
                // 主题包可能是 themes/xxx.lua 这种带前缀的，剥掉顶层 themes/
                if (name.startsWith("themes/")) name = name.removePrefix("themes/")
                if (name.isBlank() || name.contains("..")) continue
                val target = File(root, name)
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                    }
                }
                written++
                if (totalEntries > 0) {
                    onProgress(((written * 100) / totalEntries).coerceIn(0, 100))
                }
            }
        }
        // 重新加载当前主题，让新文件立刻生效
        val currentId = runCatching { ThemeManager.prefs.selectedTheme.getValue() }.getOrNull()
        if (!currentId.isNullOrBlank()) {
            runCatching { ThemeManager.selectTheme(currentId, forceReload = true) }
        }
        Timber.i("Update: themes installed, %d files", written)
        written
    }

    fun themeZipName(manifest: UpdateManifest): String =
        manifest.themes?.name?.takeIf { it.isNotBlank() } ?: "themes-${manifest.versionName}.zip"

    fun apkFileName(manifest: UpdateManifest): String =
        manifest.apk?.name?.takeIf { it.isNotBlank() } ?: "bamboo-${manifest.versionName}.apk"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }
}
