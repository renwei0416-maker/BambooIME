package com.osfans.trime.actions.bamboo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * OpenAI 风格 `chat/completions` 客户端。
 *
 * 只做一件事：把「提示词 + 文本」发出去，把 `choices[0].message.content` 取回来。
 * 所有网络 IO 都在 [Dispatchers.IO]，调用方负责不阻塞按键线程。
 */
object AiClient {
    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    private data class Cfg(
        val url: String,
        val model: String,
        val key: String,
        val temp: Float,
        val maxTokens: Int,
        val timeoutMs: Int,
    )

    private fun normalizeUrl(base: String): String {
        var b = base.trim()
        if (b.isEmpty()) return ""
        b = b.trimEnd('/')
        if (b.endsWith("/chat/completions")) return b
        return "$b/chat/completions"
    }

    suspend fun chat(
        prompt: String,
        text: String,
        temperature: Float = BambooPrefs.aiTemperature,
        maxTokens: Int = BambooPrefs.aiMaxTokens,
        timeoutMs: Int = BambooPrefs.aiTimeoutMs,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val cfg =
                Cfg(
                    url = normalizeUrl(BambooPrefs.aiBaseUrl),
                    model = BambooPrefs.aiModel,
                    key = BambooPrefs.aiApiKey,
                    temp = temperature,
                    maxTokens = maxTokens,
                    timeoutMs = timeoutMs,
                )
            if (cfg.url.isEmpty()) return@withContext Result.failure(AiError("还没填 AI 接口地址"))
            if (cfg.model.isEmpty()) return@withContext Result.failure(AiError("还没填模型名"))

            val payload =
                buildJsonObject {
                    put("model", cfg.model)
                    put("temperature", cfg.temp)
                    put("max_tokens", cfg.maxTokens)
                    put("stream", false)
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("role", "system")
                                    put("content", prompt)
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("role", "user")
                                    put("content", text)
                                },
                            )
                        },
                    )
                }.toString()

            val builder =
                Request
                    .Builder()
                    .url(cfg.url)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Accept", "application/json")
            if (cfg.key.isNotEmpty()) builder.header("Authorization", "Bearer ${cfg.key}")

            val call = client.newBuilder().readTimeout(cfg.timeoutMs.toLong(), TimeUnit.MILLISECONDS).build().newCall(builder.build())
            try {
                call.execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Timber.w("ai http %d: %s", resp.code, body.take(200))
                        return@withContext Result.failure(AiError("接口返回 ${resp.code}"))
                    }
                    val content = extractContent(body)
                    if (content.isNullOrBlank()) {
                        Result.failure(AiError("接口返回里没有内容"))
                    } else {
                        Result.success(content.trim())
                    }
                }
            } catch (e: Throwable) {
                Timber.w(e, "ai request failed")
                Result.failure(AiError(e.message ?: e.javaClass.simpleName))
            }
        }

    /**
     * 视觉请求：把一张图连同提示词发给模型。
     *
     * 与 [chat] 的区别：user 消息是内容数组（文本 + image_url），图以 base64 data URL 内联；
     * **不传 max_tokens**（交给服务端默认，避免推理 token 把正文吃光）。
     */
    suspend fun vision(
        prompt: String,
        image: ByteArray,
        mime: String = "image/png",
        timeoutMs: Int = 90_000,
        temperature: Float = 0.2f,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val cfg =
                Cfg(
                    url = normalizeUrl(BambooPrefs.aiBaseUrl),
                    model = BambooPrefs.aiModel,
                    key = BambooPrefs.aiApiKey,
                    temp = temperature,
                    maxTokens = 0,
                    timeoutMs = timeoutMs,
                )
            if (cfg.url.isEmpty()) return@withContext Result.failure(AiError("还没填 AI 接口地址"))
            if (cfg.model.isEmpty()) return@withContext Result.failure(AiError("还没填模型名"))
            if (image.isEmpty()) return@withContext Result.failure(AiError("没有可发送的图片"))

            val dataUrl = "data:" + mime + ";base64," + Base64.encodeToString(image, Base64.NO_WRAP)
            fun payload(limit: Int?): String =
                buildJsonObject {
                    put("model", cfg.model)
                    put("temperature", cfg.temp)
                    put("stream", false)
                    if (limit != null) put("max_tokens", limit)
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("role", "user")
                                    put(
                                        "content",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("type", "text")
                                                    put("text", prompt)
                                                },
                                            )
                                            add(
                                                buildJsonObject {
                                                    put("type", "image_url")
                                                    put(
                                                        "image_url",
                                                        buildJsonObject { put("url", dataUrl) },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                }.toString()

            fun post(body: String): Pair<Int, String> {
                val builder =
                    Request
                        .Builder()
                        .url(cfg.url)
                        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .header("Accept", "application/json")
                if (cfg.key.isNotEmpty()) builder.header("Authorization", "Bearer " + cfg.key)
                val call = client.newBuilder().readTimeout(cfg.timeoutMs.toLong(), TimeUnit.MILLISECONDS).build().newCall(builder.build())
                return call.execute().use { it.code to (it.body?.string().orEmpty()) }
            }

            try {
                val first = post(payload(null))
                if (first.first !in 200..299) {
                    Timber.w("ai vision http %d: %s", first.first, first.second.take(200))
                    return@withContext Result.failure(AiError("接口返回 " + first.first))
                }
                var text = extractContent(first.second)
                if (text.isNullOrBlank() && finishReason(first.second) == "length") {
                    // 推理把 token 吃光了：带一个大上限重试一次
                    val second = post(payload(8000))
                    if (second.first !in 200..299) {
                        return@withContext Result.failure(AiError("接口返回 " + second.first))
                    }
                    text = extractContent(second.second)
                }
                if (text.isNullOrBlank()) Result.failure(AiError("接口返回里没有内容")) else Result.success(text.trim())
            } catch (e: Throwable) {
                Timber.w(e, "ai vision failed")
                Result.failure(AiError(e.message ?: e.javaClass.simpleName))
            }
        }

    private fun finishReason(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val first = ((root as? JsonObject)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return (first["finish_reason"] as? JsonPrimitive)?.contentOrNullSafe()
    }

    /** 从 `choices[0].message.content`（或 `choices[0].text`）里取正文。 */
    private fun extractContent(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val obj = root as? JsonObject ?: return null
        val choices = obj["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val message = first["message"] as? JsonObject
        val content = message?.get("content")
        return when (content) {
            is JsonPrimitive -> content.contentOrNullSafe()
            is JsonArray -> content.joinToString("") { (it as? JsonPrimitive)?.contentOrNullSafe().orEmpty() }
            else -> (first["text"] as? JsonPrimitive)?.contentOrNullSafe()
        }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else content

    class AiError(message: String) : Exception(message)
}
