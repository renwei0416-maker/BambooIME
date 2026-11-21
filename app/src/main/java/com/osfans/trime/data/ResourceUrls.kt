/*
 * SPDX-FileCopyrightText: 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

object ResourceUrls {
    const val USER_AGENT = "Mozilla/5.0"

    // ---- Voice Model (punctuation always enabled) ----
    const val VOICE_MODEL_QNN_RELEASE_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn"

    fun buildQnnVoiceModelUrl(chunkMs: Int): String = "$VOICE_MODEL_QNN_RELEASE_BASE/sherpa-onnx-qnn-x-asr-streaming-zipformer-transducer-zh-en-punct-2026-06-05-chunk-size-${chunkMs}ms-android-aarch64.tar.bz2"

    /** CPU-only (fp32) variant of the same X-ASR streaming punct model. */
    const val VOICE_MODEL_CPU_RELEASE_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    fun buildCpuVoiceModelUrl(chunkMs: Int): String = "$VOICE_MODEL_CPU_RELEASE_BASE/${cpuVoiceModelFileName(chunkMs)}"

    // ---- Voice model mirror (CNB) ----
    /**
     * 语音模型的国内加速镜像。CNB 发行版 `voice-models-x-asr-2026-06-05` 里放的是
     * k2-fsa 上游同名的 CPU 版 tar.bz2，文件名与内容都未做修改，sha256 与上游一致；
     * QNN 版暂未镜像，命中不到时自动只用 GitHub。
     */
    const val VOICE_MODEL_CNB_RELEASE_BASE =
        "https://cnb.cool/renwei.co/BambooIME/-/releases/download/voice-models-x-asr-2026-06-05"

    /** 单个语音模型包的期望大小与 sha256，用于下载校验，也省掉一次 GitHub API 请求。 */
    data class VoiceModelAsset(val size: Long, val sha256: String)

    fun cpuVoiceModelFileName(chunkMs: Int): String =
        "sherpa-onnx-x-asr-${chunkMs}ms-streaming-zipformer-transducer-zh-en-punct-2026-06-05.tar.bz2"

    /** CPU 后端四档的权威大小与 sha256（取自上游 release 资产，与 CNB 镜像逐一对应）。 */
    val VOICE_MODEL_CPU_ASSETS: Map<Int, VoiceModelAsset> =
        mapOf(
            160 to VoiceModelAsset(584120261L, "342764b59446de0a52cbc51c5b135d767b62f552bc0f26fc5b21ddd80027d16a"),
            480 to VoiceModelAsset(584120230L, "7e82f0a26f2bf8630141ebec658760b0fa585575fea2fee3d8edc4d3cb637b88"),
            960 to VoiceModelAsset(584119349L, "96bd82b638a64068ea80913779cad0a196f60bf9d16aa94770ec894ad7d61753"),
            1920 to VoiceModelAsset(584116380L, "0aaf966d32e047561678719ce8d4b3582cc208294e4eef222dc641999ee45540"),
        )

    /** CNB 上已镜像的 CPU 包直链；未镜像的档位返回 null。 */
    fun buildCnbCpuVoiceModelUrl(chunkMs: Int): String? =
        if (VOICE_MODEL_CPU_ASSETS.containsKey(chunkMs)) {
            "$VOICE_MODEL_CNB_RELEASE_BASE/${cpuVoiceModelFileName(chunkMs)}"
        } else {
            null
        }

    // ---- GitHub Release API URLs ----
    private const val RELEASE_API_K2FSA_SHERPA_ONNX =
        "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags"

    const val VOICE_MODEL_QNN_RELEASE_API =
        "$RELEASE_API_K2FSA_SHERPA_ONNX/asr-models-qnn"

    const val VOICE_MODEL_CPU_RELEASE_API =
        "$RELEASE_API_K2FSA_SHERPA_ONNX/asr-models"

    // ---- SHA256 / size cache (fetched from GitHub release API) ----
    object GitHubAssetCache {
        private val cache = ConcurrentHashMap<String, String>()
        private val sizeCache = ConcurrentHashMap<String, Long>()
        private val client = OkHttpClient()

        private suspend fun fetchReleaseAssets(releaseApiUrl: String) {
            val request = Request.Builder()
                .url(releaseApiUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val content = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("GitHubAssetCache: fetch failed ${response.code}")
                        return@withContext null
                    }
                    response.body.string()
                }
            } ?: return
            val json = JSONObject(content)
            val assets = json.optJSONArray("assets") ?: return
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val url = asset.optString("browser_download_url", "")
                val digest = asset.optString("digest", "")
                val size = asset.optLong("size", 0L)
                if (url.isNotEmpty() && digest.startsWith("sha256:")) {
                    cache[url] = digest.removePrefix("sha256:")
                }
                if (url.isNotEmpty() && size > 0) {
                    sizeCache[url] = size
                }
            }
        }

        suspend fun getSha256(downloadUrl: String, releaseApiUrl: String): String? {
            cache[downloadUrl]?.let { return it }
            fetchReleaseAssets(releaseApiUrl)
            return cache[downloadUrl]
        }

        suspend fun getAssetSize(downloadUrl: String, releaseApiUrl: String): Long? {
            sizeCache[downloadUrl]?.let { return it }
            fetchReleaseAssets(releaseApiUrl)
            return sizeCache[downloadUrl]
        }

        suspend fun getAllKnownSha256s(vararg releaseApiUrls: String): List<String> {
            for (apiUrl in releaseApiUrls) {
                fetchReleaseAssets(apiUrl)
            }
            return cache.values.toList()
        }

        fun isCached(downloadUrl: String): Boolean = cache.containsKey(downloadUrl)
    }
}
