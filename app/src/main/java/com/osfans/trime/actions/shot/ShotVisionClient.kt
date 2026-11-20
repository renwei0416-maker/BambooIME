/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 截图专用的「看图」请求。
 *
 * 和 [com.osfans.trime.actions.bamboo.AiClient] 走同一个 OpenAI 兼容格式（`image_url` + data URL），
 * 但**配置、Key、模型全部独立**（见 [ShotAiPrefs]），因为看图必须用支持视觉的模型。
 *
 * 会先把图缩到长边不超过 [MAX_EDGE] 再压成 JPEG：既省流量，也避免 prompt token 暴涨
 * （原图直传时一张 1080×2280 就要 ~2000 tokens，缩完约 700）。
 */
object ShotVisionClient {
    private const val MAX_EDGE = 1024
    private const val JPEG_QUALITY = 88
    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 120_000
    private const val MAX_TOKENS = 1500

    /** 同步请求，调用方必须放在 IO 线程。 */
    fun ask(
        prompt: String,
        bitmap: Bitmap,
    ): Result<String> =
        runCatching {
            val key = ShotAiPrefs.apiKey
            require(key.isNotBlank()) { "还没填「视觉 AI」的 API Key，先去设置里填一下" }
            val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(toJpeg(bitmap), Base64.NO_WRAP)
            val payload =
                JSONObject()
                    .put("model", ShotAiPrefs.model)
                    .put("temperature", 0.2)
                    .put("max_tokens", MAX_TOKENS)
                    .put(
                        "messages",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put(
                                    "content",
                                    JSONArray()
                                        .put(JSONObject().put("type", "text").put("text", prompt))
                                        .put(
                                            JSONObject()
                                                .put("type", "image_url")
                                                .put("image_url", JSONObject().put("url", dataUrl)),
                                        ),
                                ),
                        ),
                    )
            post(ShotAiPrefs.baseUrl, key, payload.toString())
        }

    /** 缩图 + 压缩。解码失败时退回原图。 */
    private fun toJpeg(bitmap: Bitmap): ByteArray {
        val long = maxOf(bitmap.width, bitmap.height)
        val scaled =
            if (long <= MAX_EDGE) {
                bitmap
            } else {
                val scale = MAX_EDGE.toFloat() / long
                runCatching {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                }.getOrDefault(bitmap)
            }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    private fun post(
        url: String,
        key: String,
        body: String,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text =
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
            if (code !in 200..299) {
                error("接口返回 $code：" + shorten(text))
            }
            val content =
                runCatching {
                    JSONObject(text)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content")
                }.getOrNull()
            if (content.isNullOrBlank()) {
                error("接口没有返回内容：" + shorten(text))
            }
            return content.trim()
        } finally {
            conn.disconnect()
        }
    }

    private fun shorten(
        s: String,
        limit: Int = 200,
    ): String = if (s.length <= limit) s else s.take(limit) + "…"
}
