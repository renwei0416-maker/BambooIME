/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.glossary

import com.osfans.trime.data.ResourceUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 词库包的下载源选择：GitHub 上游 + CNB 国内镜像。
 *
 * 与万象方案包的做法一致：两个源各探一次（`Range: bytes=0-0` 取耗时），谁快先用谁，
 * 失败自动换另一个；测速结果缓存 10 分钟。**不给用户选项**——用户不需要知道源在哪，
 * 只希望点「下载」能下完。
 *
 * 用户自己填了镜像地址（[GlossaryPrefs.customDownloadUrl]）时只用那一个，不再自动换源，
 * 避免「指定了地址却装到别处的包」。
 */
object GlossaryMirror {
    enum class Host { GITHUB, CNB }

    data class Candidate(
        val label: String,
        val url: String,
        val host: Host,
    )

    private const val CACHE_MS = 10 * 60 * 1000L

    @Volatile
    private var cachedFastest: Host? = null

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

    /** 按「先快后慢」返回候选源；第一个是首选，下载失败由调用方依次换下一个。 */
    suspend fun orderedCandidates(
        lang: String,
        customUrl: String = "",
    ): List<Candidate> {
        if (customUrl.isNotBlank()) {
            return listOf(Candidate("自定义", customUrl, Host.GITHUB))
        }
        val github = Candidate("GitHub", GlossaryPrefs.githubUrlFor(lang), Host.GITHUB)
        val cnb = Candidate("CNB", GlossaryPrefs.cnbUrlFor(lang), Host.CNB)
        return if (fastest(github.url, cnb.url) == Host.CNB) listOf(cnb, github) else listOf(github, cnb)
    }

    /** 测速选源；两边都失败时倾向 CNB（国内网络更常见的情况）。 */
    private suspend fun fastest(
        githubUrl: String,
        cnbUrl: String,
    ): Host {
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
            githubMs == null && cnbMs == null -> Host.CNB
            githubMs == null -> Host.CNB
            cnbMs == null -> Host.GITHUB
            cnbMs <= githubMs -> Host.CNB
            else -> Host.GITHUB
        }

        Timber.i("Glossary mirror: github=%s ms, cnb=%s ms -> %s", githubMs, cnbMs, result)
        cachedFastest = result
        cachedAt = now
        return result
    }

    private fun measure(url: String): Long? = try {
        val request = Request.Builder()
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
                runCatching { response.body.bytes() }
                (System.nanoTime() - started) / 1_000_000L
            }
        }
    } catch (e: Exception) {
        Timber.d(e, "Glossary mirror: probe failed for %s", url)
        null
    }

    /** 清掉测速缓存（设置页手动重试时可用）。 */
    fun invalidateCache() {
        cachedFastest = null
        cachedAt = 0L
    }
}
