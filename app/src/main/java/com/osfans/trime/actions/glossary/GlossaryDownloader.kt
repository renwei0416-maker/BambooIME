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
        onProgress: (done: Long, total: Long) -> Unit,
    ): String? {
        Timber.i("glossary: download %s", url)
        val tmp = File(TrimeApplication.getInstance().cacheDir, "glossary-download.part")
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return "服务器返回 ${resp.code}"
                val body = resp.body ?: return "响应为空"
                val total = body.contentLength()
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
            val err = withContext(Dispatchers.IO) { download(candidate.url, onProgress) }
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
            download(url) { done, total ->
                val ratio = if (total > 0) (done.toFloat() / total) else null
                onProgress(ratio)
            }
        }
}
