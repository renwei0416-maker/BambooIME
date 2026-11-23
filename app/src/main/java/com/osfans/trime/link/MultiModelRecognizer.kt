/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.osfans.trime.link

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import java.io.File
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.infer.SherpaModelPack

/**
 * 「其它多模型」语音方案的离线识别器（Paraformer / SenseVoice）。
 *
 * 只管一件事：**一段 16 kHz 单声道 PCM16 → 文本**。
 * 采集、分段、上屏、日志、回退记账都由 [WxSpeechClient] 的统一管路负责，
 * 这样三种语音方案的输出行为完全一致（同一处 [com.osfans.trime.actions.VoiceAi.handleFinal]）。
 *
 * 模型包由 [SherpaModelPack] 管理（`filesDir/sherpa-models/<id>/`），
 * 具体用哪个模型看设置里的「语言 → 主模型」。
 */
object MultiModelRecognizer {
    const val SAMPLE_RATE = 16_000

    /** SenseVoice 的语言标记；`auto` 让它自己判中英日韩粤。 */
    private const val SENSE_VOICE_LANGUAGE = "auto"

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var loadedSpecId: String? = null

    /** 标点模型（懒加载，独立于识别器；未安装时为 null）。 */
    private var punctuator: OfflinePunctuation? = null
    @Volatile
    private var punctLoaded = false

    /** 当前设置选中、且已经装好的模型；没装返回 null。 */
    fun currentSpec(context: Context): SherpaModelPack.Spec? {
        val prefs = AppPrefs.defaultInstance().voiceInput
        val wantSenseVoice =
            prefs.multiModelZh.getValue() == AppPrefs.VoiceInput.MultiModelZH.SENSE_VOICE
        val spec = if (wantSenseVoice) SherpaModelPack.SENSE_VOICE else SherpaModelPack.PARAFORMER
        return if (SherpaModelPack.isInstalled(context, spec.id)) spec else null
    }

    fun isReady(context: Context): Boolean = currentSpec(context) != null

    /**
     * 确保识别器就绪；模型包没装或加载失败返回 false。
     * 换模型（设置里改了主模型）时会自动重建。
     */
    @Synchronized
    fun ensure(context: Context): Boolean {
        val spec = currentSpec(context) ?: return false
        if (recognizer != null && loadedSpecId == spec.id) return true
        release()

        val dir = SherpaModelPack.installedDir(context, spec.id) ?: return false
        val tokens = File(dir, "tokens.txt")
        if (!tokens.isFile) return false

        val modelConfig = when (spec.id) {
            SherpaModelPack.PARAFORMER.id -> {
                val model = File(dir, spec.primaryFile)
                if (!model.isFile) return false
                OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(model = model.absolutePath),
                    tokens = tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "paraformer",
                )
            }

            SherpaModelPack.SENSE_VOICE.id -> {
                val model = File(dir, spec.primaryFile)
                if (!model.isFile) return false
                OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = model.absolutePath,
                        language = SENSE_VOICE_LANGUAGE,
                        useInverseTextNormalization = true,
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "sense_voice",
                )
            }

            // X-ASR 离线包目前只作为「中文定稿校正」的候选，还没接进主链路。
            else -> return false
        }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
        )
        val created = runCatching { OfflineRecognizer(null, config) }.getOrNull() ?: return false
        recognizer = created
        loadedSpecId = spec.id
        return true
    }

    @Synchronized
    fun release() {
        runCatching { punctuator?.release() }
        punctuator = null
        punctLoaded = false
        runCatching { recognizer?.release() }
        recognizer = null
        loadedSpecId = null
    }

    /**
     * 给整句补标点。标点模型未安装 / 出错时返回 null（调用方退回「只保留」）。
     * CT-Transformer 是字符级分词，只需要 model.int8.onnx。
     */
    @Synchronized
    fun punctuate(context: Context, text: String): String? {
        val body = text.trim()
        if (body.isEmpty()) return null
        val dir = SherpaModelPack.installedDir(context, SherpaModelPack.PUNCT_ZH_EN.id) ?: return null
        if (!punctLoaded) {
            val model = File(dir, SherpaModelPack.PUNCT_ZH_EN.primaryFile)
            if (!model.isFile) return null
            val config = OfflinePunctuationConfig(
                model = OfflinePunctuationModelConfig(
                    ctTransformer = model.absolutePath,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
            val created = runCatching { OfflinePunctuation(null, config) }.getOrNull() ?: return null
            punctuator = created
            punctLoaded = true
        }
        val engine = punctuator ?: return null
        return runCatching { engine.addPunctuation(body) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 一段完整语音 → 文本；失败或无模型时返回空串（调用方按「这段没结果」处理）。 */
    fun decode(segment: ShortArray): String {
        val engine = recognizer ?: return ""
        if (segment.size < SAMPLE_RATE / 5) return ""
        var stream: OfflineStream? = null
        return try {
            val samples = FloatArray(segment.size) { segment[it] / 32768.0f }
            stream = engine.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } catch (e: Throwable) {
            ""
        } finally {
            runCatching { stream?.release() }
        }
    }
}
