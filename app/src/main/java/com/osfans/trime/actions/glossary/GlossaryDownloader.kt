/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.glossary

import com.osfans.trime.TrimeApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 从网络下载词库包。
 *
 * 先落临时文件、校验通过再交给 [GlossaryStore]，所以下载中断不会留下半截包。
 * 地址可改：GitHub 直链在国内可能很慢，用户可以在设置里换成任意镜像。
 */
object GlossaryDownloader {
    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 同步下载（调用方负责放后台线程）。
     *
     * @param onProgress 已下载字节 / 总字节（总字节未知时为 -1）
     * @return 失败原因，成功返回 `null`
     */
    fun download(
        url: String,
        expectSize: Long = 0L,
        onProgress: (done: Long, total: Long) -> Unit,
    ): String? {
        Timber.i("glossary: download %s", url)
        // 放在词库目录内：保证后面的 renameTo 是同目录改名（跨目录改名在某些机型上会失败）
        val tmp = File(GlossaryStore.dir, "glossary-download.part")
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                if (!resp.isSuccessful) {
                    val peek = runCatching {
                        resp.body?.bytes()?.toString(Charsets.UTF_8)?.take(180)
                    }.getOrNull().orEmpty()
                    Timber.w("glossary: HTTP %s for %s (final=%s) body=%s", resp.code, url, finalUrl, peek)
                    return "服务器返回 ${resp.code}"
                }
                val body = resp.body ?: return "响应为空"
                val total = body.contentLength()
                Timber.i("glossary: 200 final=%s length=%s", finalUrl, total)
                tmp.delete()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            // 每 128 KB 报一次进度，避免把主线程刷爆
                            if (done - lastReport >= 128 * 1024) {
                                lastReport = done
                                onProgress(done, total)
                            }
                        }
                        onProgress(done, total)
                    }
                }
            }
            val name = url.substringAfterLast('/').ifBlank { "glossary.bge" }
            val got = tmp.length()
            Timber.i("glossary: 落盘 %s 字节（%s）", got, name)
            // 大小对不上多半是下载被截断：宁可报错，也不要装一个坏包进去
            if (expectSize > 0 && got != expectSize) {
                return "下载不完整：$got / $expectSize 字节，请重试"
            }
            val err = tmp.inputStream().use { GlossaryStore.install(it, name) }
            Timber.i("glossary: download done, install=%s", err)
            return err
        } catch (e: Exception) {
            Timber.w(e, "glossary: download failed")
            return e.message ?: e.javaClass.simpleName
        } finally {
            tmp.delete()
        }
    }

    /**
     * 自动选源下载：先测速，再按顺序试，失败自动换下一个源。
     *
     * @param lang 目标语言（决定 GitHub / CNB 的具体文件）
     * @param customUrl 用户自填的镜像地址；非空时只用它，不自动换源
     * @param onAttempt 每次开始试某个源时回调它的显示名（供 UI 显示「正在从 CNB 下载」）
     * @return 失败原因；全部成功时返回 `null`
     */
    suspend fun downloadAuto(
        lang: String,
        customUrl: String,
        onProgress: (done: Long, total: Long) -> Unit,
        onAttempt: (label: String) -> Unit,
    ): String? {
        var last: String? = null
        for (candidate in GlossaryMirror.orderedCandidates(lang, customUrl)) {
            onAttempt(candidate.label)
            val err = withContext(Dispatchers.IO) {
                download(
                    url = candidate.url,
                    expectSize = GlossaryPrefs.expectedSizeFor(lang),
                    onProgress = onProgress,
                )
            }
            if (err == null) return null
            last = err
            Timber.w("glossary: %s 源失败，换下一个：%s", candidate.label, err)
        }
        return last ?: "没有可用的下载源"
    }


    suspend fun downloadAsync(
        url: String,
        onProgress: (Float?) -> Unit,
    ): String? =
        withContext(Dispatchers.IO) {
            download(url, onProgress = { done, total ->
                val ratio = if (total > 0) (done.toFloat() / total) else null
                onProgress(ratio)
            })
        }
}
