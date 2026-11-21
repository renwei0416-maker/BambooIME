/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.link

import com.osfans.trime.TrimeApplication
import com.osfans.trime.actions.ids.IdsPrefs
import com.osfans.trime.data.ResourceUrls
import com.osfans.trime.util.FileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 部件反查的数据包（`ids_data.bin`，约 26 MB）。
 *
 * 插件已经在 `librime_jni.so` 里，唯一缺的就是这份 mmap 数据；不随 APK 发，
 * 用户点设置页里的「下载」时才拉，装到 App 私有目录 `filesDir/ids/`。
 */
object IdsDataManager {
    private const val CACHE_MS = 10 * 60 * 1000L
    private const val TAG = "Ids data"

    @Volatile private var cachedFastestHost: Int? = null
    @Volatile private var cachedAt = 0L

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun dir(): File = File(TrimeApplication.getInstance().filesDir, "ids").apply { mkdirs() }

    fun dataFile(): File = File(dir(), ResourceUrls.IDS_DATA_FILE_NAME)

    /** 大小对得上才算装好（不每次算 sha256，太慢）。 */
    fun isInstalled(): Boolean = runCatching {
        val f = dataFile()
        f.isFile && f.length() == ResourceUrls.IDS_DATA_SIZE
    }.getOrDefault(false)

    fun installedSize(): Long = runCatching {
        dataFile().let { if (it.isFile) it.length() else 0L }
    }.getOrDefault(0L)

    fun delete(): Boolean = runCatching { dataFile().delete() }.getOrDefault(false)

    /** 从本地挑到的文件导入（大小校验）。 */
    fun importFrom(source: File): Boolean = runCatching {
        if (!source.isFile) return false
        if (source.length() != ResourceUrls.IDS_DATA_SIZE) return false
        val dest = dataFile()
        dest.delete()
        source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        dest.length() == ResourceUrls.IDS_DATA_SIZE
    }.getOrDefault(false)

    data class Candidate(val label: String, val url: String, val host: Int)

    /**
     * 要下载的源，按优先级排列：始终两个都返回，前一个失败自动换下一个。
     *
     * - 自动：先测速，快的在前；
     * - 指定 GitHub / CNB：指定的在前，另一个兜底 —— 避免某一源被墙时直接下不动。
     */
    suspend fun orderedCandidates(): List<Candidate> {
        val gh = Candidate("GitHub", ResourceUrls.buildIdsDataUrl(cnb = false), IdsPrefs.HOST_GITHUB)
        val cnb = Candidate("CNB", ResourceUrls.buildIdsDataUrl(cnb = true), IdsPrefs.HOST_CNB)
        return when (IdsPrefs.host) {
            IdsPrefs.HOST_GITHUB -> listOf(gh, cnb)
            IdsPrefs.HOST_CNB -> listOf(cnb, gh)
            else -> if (fastestIsCnb()) listOf(cnb, gh) else listOf(gh, cnb)
        }
    }

    /** 自动测速：两个源各发一个 `Range: bytes=0-0`，谁先回谁排前面；都超时倾向 CNB。 */
    private suspend fun fastestIsCnb(): Boolean {
        val now = System.currentTimeMillis()
        cachedFastestHost?.let { if (now - cachedAt < CACHE_MS) return it == IdsPrefs.HOST_CNB }
        val ghUrl = ResourceUrls.buildIdsDataUrl(cnb = false)
        val cnbUrl = ResourceUrls.buildIdsDataUrl(cnb = true)
        val result = coroutineScope {
            val g = async(Dispatchers.IO) { measure(ghUrl) }
            val c = async(Dispatchers.IO) { measure(cnbUrl) }
            val ghMs = g.await()
            val cnbMs = c.await()
            when {
                ghMs == null && cnbMs == null -> true
                ghMs == null -> true
                cnbMs == null -> false
                else -> cnbMs <= ghMs
            }
        }
        cachedFastestHost = if (result) IdsPrefs.HOST_CNB else IdsPrefs.HOST_GITHUB
        cachedAt = now
        return result
    }

    private fun measure(url: String): Long? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", ResourceUrls.USER_AGENT)
            .build()
        val start = System.currentTimeMillis()
        probeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) return null
            response.body?.bytes()
            System.currentTimeMillis() - start
        }
    }.getOrNull()

    fun invalidateCache() {
        cachedFastestHost = null
        cachedAt = 0L
    }

    /**
     * 下载到 `filesDir/ids/ids_data.bin`，返回成功用的源名。
     * 失败会抛异常，调用方负责提示。
     */
    suspend fun download(
        onProgress: ((Float, Long, Long) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        onAttempt: (label: String, index: Int, total: Int) -> Unit = { _, _, _ -> },
    ): String {
        val candidates = orderedCandidates()
        var lastError: Throwable? = null
        val dest = dataFile()
        candidates.forEachIndexed { index, candidate ->
            onAttempt(candidate.label, index + 1, candidates.size)
            try {
                FileDownloader.download(
                    url = candidate.url,
                    destFile = dest,
                    expectedSha256 = ResourceUrls.IDS_DATA_SHA256,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
                if (isInstalled()) {
                    Timber.i("$TAG installed from ${candidate.label} (${installedSize()} bytes)")
                    return candidate.label
                }
                lastError = IllegalStateException("下载完成但大小不对：${installedSize()}")
            } catch (e: Throwable) {
                Timber.w(e, "$TAG download failed via ${candidate.label}")
                dest.delete()
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("没有可用的下载源")
    }
}
