/*
 * SPDX-FileCopyrightText: 2026 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.wanxiang

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 方案包与语法模型的多源下载。
 *
 * 两个源提供同一批文件：
 *  * GitHub：`amzxyz/rime-wanxiang`（方案包）+ `amzxyz/RIME-LMDG`（语法模型）
 *  * cnb.cool：`amzxyz/rime-wanxiang` 的镜像（方案包与语法模型都在这个仓库下，
 *    语法模型走 `model` 这个 tag，内容随官方每天更新，与版本号无关）
 *
 * 下载前对两个源各发一次 1 字节的 Range 请求，取「connect + 首包」更短的那个；
 * 选中的结果缓存 [CACHE_MS]，期间不再重复测速。首选源下载失败时自动换另一个重试一次。
 */
object WanxiangMirror {

    enum class Host { GITHUB, CNB }

    private const val GH_BASE = "https://github.com/amzxyz/rime-wanxiang/releases/download/"
    private const val CNB_BASE = "https://cnb.cool/amzxyz/rime-wanxiang/-/releases/download/"
    private const val GH_GRAM_BASE = "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/"
    private const val CNB_GRAM_BASE = "https://cnb.cool/amzxyz/rime-wanxiang/-/releases/download/model/"

    /** 测速用的最小样本：方案包用 base 包，语法模型用它自己。 */
    private const val PROBE_PACK = "rime-wanxiang-base.zip"

    private const val CACHE_MS = 10 * 60 * 1000L

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private var preferredHost: Host? = null
    private var preferredAt = 0L

    /** 方案包在某个源上的下载目录（已带版本号）。 */
    fun packBase(host: Host): String =
        if (host == Host.CNB) CNB_BASE + WanxiangVariants.TAG + "/" else GH_BASE + WanxiangVariants.TAG + "/"

    /** 语法模型在某个源上的下载目录。 */
    fun gramBase(host: Host): String = if (host == Host.CNB) CNB_GRAM_BASE else GH_GRAM_BASE

    /**
     * 下载一个方案包。自定义下载地址优先；否则按测速结果挑源，失败自动换另一个源。
     *
     * @return 成功时 true；两个源都失败时 false。
     */
    suspend fun downloadPack(
        variant: WanxiangVariants.Variant,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val custom = WanxiangPrefs.downloadBase
        if (custom.isNotBlank()) {
            val url = WanxiangVariants.urlOf(variant, custom)
            return WanxiangInstaller.download(url, dest, onProgress)
        }
        return tryHosts(PROBE_PACK, dest, onProgress) { host ->
            WanxiangVariants.urlOf(variant, packBase(host))
        }
    }

    /** 下载语法模型（多源、无自定义地址）。 */
    suspend fun downloadGram(dest: File, onProgress: (Long, Long) -> Unit): Boolean =
        tryHosts(WanxiangVariants.GRAM_FILE, dest, onProgress) { host ->
            gramBase(host) + WanxiangVariants.GRAM_FILE
        }

    private suspend fun tryHosts(
        probeFile: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        urlFor: (Host) -> String,
    ): Boolean {
        val first = fastest(probeFile)
        val order = listOf(first, if (first == Host.GITHUB) Host.CNB else Host.GITHUB)
        for (host in order) {
            val url = urlFor(host)
            Timber.d("wanxiang: try $host $url")
            if (WanxiangInstaller.download(url, dest, onProgress)) return true
            runCatching { dest.delete() }
            Timber.w("wanxiang: $host failed, next source")
        }
        return false
    }

    /**
     * 对两个源各发一次 1 字节 Range 请求，返回更快的源。
     *
     * 两个都失败时返回 [Host.GITHUB]（让上层走原有的失败提示），
     * 只有一个可用时直接用可用的那个。缓存 [CACHE_MS]。
     */
    private suspend fun fastest(probeFile: String): Host = withContext(Dispatchers.IO) {
        val cached = preferredHost
        if (cached != null && System.currentTimeMillis() - preferredAt < CACHE_MS) return@withContext cached

        val gh = measure(GH_BASE + WanxiangVariants.TAG + "/" + probeFile)
        val cnb = measure(CNB_BASE + WanxiangVariants.TAG + "/" + probeFile)
        val picked = when {
            gh < 0 && cnb < 0 -> Host.GITHUB
            gh < 0 -> Host.CNB
            cnb < 0 -> Host.GITHUB
            else -> if (cnb <= gh) Host.CNB else Host.GITHUB
        }
        preferredHost = picked
        preferredAt = System.currentTimeMillis()
        Timber.d("wanxiang: mirror probe gh=${gh}ms cnb=${cnb}ms -> $picked")
        picked
    }

    /** 返回 1 字节探测的耗时毫秒；不可用返回 -1。 */
    private fun measure(url: String): Long = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .build()
        val started = System.nanoTime()
        probeClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return -1L
            val elapsed = (System.nanoTime() - started) / 1_000_000
            elapsed
        }
    }.getOrElse { -1L }
}
