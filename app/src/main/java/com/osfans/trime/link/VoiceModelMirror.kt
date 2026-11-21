/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.link

import com.osfans.trime.data.ResourceUrls
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 语音模型的下载源选择：GitHub 上游 + CNB 国内镜像。
 *
 * 算法与万象方案的 `WanxiangMirror` 一致：两个源都探一次（`Range: bytes=0-0` 取耗时），
 * 谁快就先用谁，失败自动换另一个；测速结果缓存 10 分钟，避免每次点下载都重新探测。
 *
 * QNN 版模型暂未镜像，此时候选列表里只有 GitHub 一项，行为与改造前完全一致。
 */
object VoiceModelMirror {
    data class Candidate(
        val label: String,
        val url: String,
        val host: AppPrefs.VoiceInput.VoiceDownloadHost,
    )

    private const val CACHE_MS = 10 * 60 * 1000L

    @Volatile
    private var cachedFastest: AppPrefs.VoiceInput.VoiceDownloadHost? = null

    @Volatile
    private var cachedAt = 0L

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 按「先快后慢」的顺序返回候选源。第一个是首选，下载失败时由调用方依次换下一个。
     */
    suspend fun orderedCandidates(
        backend: VoiceBackend = VoiceBackendSelector.preferred(),
    ): List<Candidate> {
        val github =
            Candidate(
                label = "GitHub",
                url = VoiceModelManager.getDownloadUrl(backend),
                host = AppPrefs.VoiceInput.VoiceDownloadHost.GITHUB,
            )
        val cnbUrl = VoiceModelManager.getCnbDownloadUrl(backend)
            ?: return listOf(github)

        val cnb =
            Candidate(
                label = "CNB",
                url = cnbUrl,
                host = AppPrefs.VoiceInput.VoiceDownloadHost.CNB,
            )

        return when (AppPrefs.defaultInstance().voiceInput.voiceDownloadHost.getValue()) {
            AppPrefs.VoiceInput.VoiceDownloadHost.GITHUB -> listOf(github, cnb)
            AppPrefs.VoiceInput.VoiceDownloadHost.CNB -> listOf(cnb, github)
            AppPrefs.VoiceInput.VoiceDownloadHost.AUTO -> {
                if (fastest(github.url, cnb.url) == AppPrefs.VoiceInput.VoiceDownloadHost.CNB) {
                    listOf(cnb, github)
                } else {
                    listOf(github, cnb)
                }
            }
        }
    }

    /** 测速选源；两边都失败时倾向于 CNB（国内网络更常见的情况）。 */
    private suspend fun fastest(
        githubUrl: String,
        cnbUrl: String,
    ): AppPrefs.VoiceInput.VoiceDownloadHost {
        val now = System.currentTimeMillis()
        cachedFastest?.let { cached ->
            if (now - cachedAt < CACHE_MS) return cached
        }

        val (githubMs, cnbMs) = coroutineScope {
            val g = async(Dispatchers.IO) { measure(githubUrl) }
            val c = async(Dispatchers.IO) { measure(cnbUrl) }
            g.await() to c.await()
        }

        val result = when {
            githubMs == null && cnbMs == null -> AppPrefs.VoiceInput.VoiceDownloadHost.CNB
            githubMs == null -> AppPrefs.VoiceInput.VoiceDownloadHost.CNB
            cnbMs == null -> AppPrefs.VoiceInput.VoiceDownloadHost.GITHUB
            cnbMs <= githubMs -> AppPrefs.VoiceInput.VoiceDownloadHost.CNB
            else -> AppPrefs.VoiceInput.VoiceDownloadHost.GITHUB
        }

        Timber.i("Voice model mirror: github=$githubMs ms, cnb=$cnbMs ms -> $result")
        cachedFastest = result
        cachedAt = now
        return result
    }

    private fun measure(url: String): Long? = try {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", ResourceUrls.USER_AGENT)
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-0")
                .get()
                .build()
        val started = System.nanoTime()
        probeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                // 读掉响应体，确保连接真正建立（预签名直链对 HEAD 不友好）
                runCatching { response.body.bytes() }
                (System.nanoTime() - started) / 1_000_000L
            }
        }
    } catch (e: Exception) {
        Timber.d(e, "Voice model mirror: probe failed for %s", url)
        null
    }

    /** 手动测速一次（设置页「重新测速」用；目前没有 UI，保留给以后的诊断）。 */
    fun invalidateCache() {
        cachedFastest = null
        cachedAt = 0L
    }
}
