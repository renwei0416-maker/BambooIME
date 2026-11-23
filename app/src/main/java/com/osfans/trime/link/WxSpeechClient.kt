/*
 * BambooIME — WeChat offline speech client.
 *
 * 一次「按住说话」= 一次会话。会话内部按 **自然停顿分段**：每段攒够语音后整段送进模型
 * 做一次整段解码（reset -> push -> finish），把各段结果按顺序拼起来，松手时只提交一遍
 * 最终文本。这是 CyIME 对非流式模型的做法，也是我们这条链路不再「滚窗 + commit() 硬截断」
 * 之后不再在接缝处吞字的原因。
 *
 * 为什么不用「一边说一边解」的滚窗：我们的模型是 40 层 attention、100 Hz、非流式，
 * 只能靠 PREV/BLK/POST 滑窗 + 找连续两个 blank 硬切；切点落在词中间就必然缺字，
 * 参数调不掉。分段解码把「切」交给停顿（人说话本来就在那里断开），段内完整上下文。
 *
 * 代价：段内不再实时出字，停顿后才出（Sherpa 是流式模型所以能实时）。可接受。
 *
 * 生命周期（全部在 IO 调度器）：
 *   permission -> load（模型 native 侧缓存）-> record loop（只喂分段器，不碰 native）
 *     -> 每闭合一段：worker 里 reset/push/finish，把该段文字追加进 composing
 *     -> 松手：读净录音缓冲 -> 补 200 ms 静音 -> flush 最后一段 -> 等 worker 排空
 *     -> 撤回 composing 里那份预览 -> VoiceAi.handleFinal 提交唯一一份
 *
 * 下一轮 `join()` 上一轮的 job，两轮永不重叠；`sessionId` 变更则作废旧轮的提交。
 */
package com.osfans.trime.link

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.view.inputmethod.InputConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.infer.SherpaModelPack
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.actions.bamboo.VoiceAi
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.infer.WxAsrInfer
import com.osfans.trime.infer.WxVoicePack
import com.osfans.trime.util.toast
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object WxSpeechClient {

    private const val SAMPLE_RATE = 16_000
    private const val BYTES_PER_SAMPLE = 2

    // --- 采集 -------------------------------------------------------------
    /** 480 ms 一块：够大让 CPU 闲着，够小保证松手时没积压。 */
    private const val CAPTURE_CHUNK_MS = 480

    /** 松手后再读一会儿，把 AudioRecord 里已录未读的样本取出来（「结尾缺字」的元凶）。 */
    private const val DRAIN_MAX_MS = 280

    /** 补的纯静音：特征窗 25 ms / 帧移 10 ms，最后一帧要等下一帧存在才完整。 */
    private const val TAIL_SILENCE_MS = 200

    /** 短于这个当成误触，不上屏。 */
    private const val MIN_SPEECH_MS = 300

    // --- 分段（对齐 CyIME 的非流式模型策略，值按我们的模型调）---------------
    /** 能量判定用的帧长。 */
    private const val FRAME_MS = 100

    /** 段首多带一点静音，避免把字头切掉。 */
    private const val PREROLL_MS = 300

    /** 连续静音超过这个长度就认为一句话说完。 */
    private const val MIN_PAUSE_MS = 400

    /** 单段上限：模型 WHOLE_MAX 是 20 s，这里留足余量，同时避免一次解码等太久。 */
    private const val MAX_SEGMENT_MS = 8_000

    /** 短于这个的段（"嗯""啊"）不单独解，并到下一段。 */
    private const val MIN_SEGMENT_MS = 500

    /** 低于这个有效语音时长的段，只有「像话」（≥2 字且含汉字/字母）才接受。 */
    private const val MIN_ACCEPT_VOICED_MS = 250

    /** 段间去重时最多回看几个字符。 */
    private const val OVERLAP_MAX_CHARS = 6

    /** 「其它多模型」用：连续讲话到这就强切，避免单段塞满模型窗口。 */
    private const val HARD_SEGMENT_MS = 14_000

    /** multi 模式下 [WxAsrInfer] 的句柄不用，用这个哨兵表示「引擎已就绪」。 */
    private const val MULTI_HANDLE = 1L

    /** 单段解码的超时下限；实际取 max(它, 段长 × 3)。 */
    private const val SEGMENT_TIMEOUT_MIN_MS = 4_000

    private val isHolding = AtomicBoolean(false)

    /** 每次按住 +1；旧会话的最终文本会被丢弃，不会重复上屏。 */
    private val sessionId = AtomicInteger(0)

    @Volatile
    private var handle = 0L

    @Volatile
    private var audioJob: Job? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var serviceRef: WeakReference<TrimeInputMethodService>? = null

    /** composing 区当前显示的文字（提交前要按它撤回）。 */
    @Volatile
    private var lastEmittedText = ""

    fun isHolding(): Boolean = isHolding.get()

    fun isEngineLoaded(): Boolean = handle != 0L

    /** Starts recording for one utterance. Safe to call from any thread. */
    fun startHoldSession(service: TrimeInputMethodService) {
        serviceRef = WeakReference(service)
        if (!multiMode() && !WxAsrInfer.available) {
            service.applicationContext.toast(R.string.wxasr_native_unavailable)
            return
        }
        if (!multiMode() && !WxVoicePack.isInstalled(service)) {
            service.applicationContext.toast(R.string.wxasr_pack_missing)
            return
        }

        val myId = sessionId.incrementAndGet()
        val previous = audioJob
        isHolding.set(true)
        lastEmittedText = ""

        audioJob = service.lifecycleScope.launch(Dispatchers.IO) {
            // Never overlap two utterances: the previous one may still be decoding its tail.
            runCatching { previous?.join() }
            if (myId != sessionId.get()) return@launch
            runSession(service, myId)
        }
    }

    /** Button released: stop reading, the session coroutine drains and finishes. */
    fun stopHoldSession() {
        if (!isHolding.getAndSet(false)) return
        // NOTE: the recorder is *not* stopped here. runSession drains the remaining buffer
        // first (see drainTail); stopping it now would drop the last few hundred ms.
    }

    /** Hard stop (keyboard detached / IME destroyed): drop the text as well. */
    fun forceStop() {
        isHolding.set(false)
        sessionId.incrementAndGet() // invalidates an in-flight finish
        audioRecord?.let { rec -> runCatching { rec.stop() } }
        runCatching { audioJob?.cancel() }
        releaseEngine()
        voiceBridgeDone()
    }

    fun releaseEngineForMemoryPressure() {
        if (isHolding.get()) return
        releaseEngine()
    }

    // ------------------------------------------------ 边说边出字（MULTI）

    /** 同一时刻最多一个推测解，和正式解抢 native 会崩（CyIME 也是这么收的）。 */
    private val previewBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val previewScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    // ---------------------------------------------------------------- session

    @SuppressLint("MissingPermission")
    private suspend fun runSession(service: TrimeInputMethodService, myId: Int) {
        val chunkSamples = SAMPLE_RATE * CAPTURE_CHUNK_MS / 1000
        val chunkBytes = chunkSamples * BYTES_PER_SAMPLE

        if (!ensurePermission(service)) return

        val h = ensureEngine(service)
        if (h == 0L) {
            withContext(Dispatchers.Main) {
                service.applicationContext.toast(R.string.wxasr_load_failed)
                VoiceOverlayUiBridge.onDone?.invoke()
            }
            return
        }

        val record = openRecord(chunkBytes)
        if (record == null) {
            withContext(Dispatchers.Main) { VoiceOverlayUiBridge.onDone?.invoke() }
            return
        }
        audioRecord = record

        val segments = Channel<Segment>(Channel.UNLIMITED)
        val parts = ArrayList<String>()
        var segCount = 0
        var segCostMs = 0L
        var accepted = false // 第一段有过文字后用于决定要不要在段间补空格

        val pcm = ShortArray(chunkSamples)
        var peak = 0
        var voicedMs = 0
        var audioMs = 0
        var capturedSamples = 0L
        var drainSamples = 0L
        var startedOverlay = false
        val totalBegin = SystemClock.uptimeMillis()

        coroutineScope {
            val worker = launch(Dispatchers.Default) {
                var lastSegmentTail = ""
                for (seg in segments) {
                    if (myId != sessionId.get()) continue
                    val begin = SystemClock.uptimeMillis()
                    val raw = decodeSegment(h, seg.data)
                    val text = sanitizeSegment(seg, raw, lastSegmentTail) ?: continue
                    lastSegmentTail = text
                    segCostMs += SystemClock.uptimeMillis() - begin
                    if (text.isEmpty()) continue
                    // 中文不加空格，段间用自然停顿即可；中英混说时给英文段补一个。
                    synchronized(parts) {
                        if (accepted && needsSpaceBefore(text)) parts.add(" ")
                        parts.add(applyPunctuationPolicy(text))
                    }
                    accepted = true
                    segCount += 1
                    val shown = synchronized(parts) { joinParts(parts) }
                    lastEmittedText = shown
                    withContext(Dispatchers.Main) {
                        runCatching { service.currentInputConnection?.setComposingText("$shown  ···", 1) }
                    }
                }
            }

            val segmenter = Segmenter { seg -> runCatching { segments.trySend(seg) } }

            try {
                record.startRecording()
                while (currentCoroutineContext().isActive && isHolding.get() && myId == sessionId.get()) {
                    val n = record.read(pcm, 0, pcm.size)
                    if (n < 0) break
                    if (n == 0) {
                        delay(10)
                        continue
                    }
                    capturedSamples += n
                    audioMs += n * 1000 / SAMPLE_RATE
                    val amp = amplitude(pcm, n)
                    if (amp > peak) peak = amp
                    if (amp > 0) voicedMs += n * 1000 / SAMPLE_RATE

                    if (!startedOverlay && amp > 0) {
                        startedOverlay = true
                        withContext(Dispatchers.Main) { VoiceOverlayUiBridge.onRecordingStarted?.invoke() }
                    }
                    withContext(Dispatchers.Main) {
                        VoiceOverlayUiBridge.onAmplitude?.invoke((amp / 32767f).coerceIn(0f, 1f))
                    }

                    segmenter.feed(pcm, n)
                    maybePreview(service, myId, segmenter, parts)
                }

                // 读净录音缓冲：这些是用户真的说出来的音，丢掉就是「结尾缺字」。
                drainSamples = drainTail(record, pcm, myId, segmenter)
                audioMs += (drainSamples * 1000 / SAMPLE_RATE).toInt()
                capturedSamples += drainSamples

                // 最后一段补齐收尾静音后送出。
                segmenter.flush(ShortArray(SAMPLE_RATE * TAIL_SILENCE_MS / 1000))
            } catch (_: Throwable) {
                // 录音被拆掉（路由变化 / 权限撤销）：仍然走正常收尾，尽量保住已识别的部分。
                runCatching { segmenter.flush(ShortArray(SAMPLE_RATE * TAIL_SILENCE_MS / 1000)) }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                audioRecord = null
            }

            segments.close()
            worker.join()
        }

        val current = myId == sessionId.get()
        val text = punctuateIfNeeded(service, joinParts(parts))
        val discard = voicedMs < MIN_SPEECH_MS && text.isEmpty()

        withContext(Dispatchers.Main) {
            val ic = service.currentInputConnection
            // 预览那份文字留在 composing 区（有的编辑器会自动提交成正文），
            // 提交前先撤掉，保证 [VoiceAi.handleFinal] 写进去的是唯一一份。
            retractComposing(ic, lastEmittedText)
            if (current && !discard && text.isNotEmpty()) {
                VoiceAi.handleFinal(service, text)
            }
            VoiceOverlayUiBridge.onDone?.invoke()
        }

        logRecognition(
            service = service,
            session = myId,
            discard = discard,
            segments = segCount,
            audioMs = audioMs,
            capturedSamples = capturedSamples,
            drainSamples = drainSamples,
            costMs = segCostMs,
            wallMs = SystemClock.uptimeMillis() - totalBegin,
            text = text,
        )

        lastEmittedText = ""
    }

    /**
     * 一段音频一次整段解码：`reset` 清掉上一段的帧缓存，`push` 喂完，`finish` 出整段文字。
     * 走的是模型原生 `finish()`（`FinalMode::Whole`），段长上限 8 s 远小于 `WHOLE_MAX`，
     * 不会退化成按块硬切。
     */
    private suspend fun decodeSegment(h: Long, seg: ShortArray): String {
        if (multiMode()) return MultiModelRecognizer.decode(seg)
        WxAsrInfer.reset(h)
        val step = SAMPLE_RATE / 2 // 0.5 s
        var off = 0
        while (off < seg.size) {
            val n = min(step, seg.size - off)
            val chunk = if (n == seg.size) seg else seg.copyOfRange(off, off + n)
            WxAsrInfer.push(h, chunk)
            off += n
        }
        val timeout = max(SEGMENT_TIMEOUT_MIN_MS, seg.size * 1000 / SAMPLE_RATE * 3).toLong()
        return withTimeoutOrNull(timeout) {
            withContext(Dispatchers.Default) { WxAsrInfer.finish(h) }
        }.orEmpty().trim()
    }

    /** 英文段之间补空格；中文（或标点开头）不补。 */
    /** 段间分隔：开「停顿处加空格」时先按空格拼，再把中文之间的空格去掉。 */
    private fun joinParts(parts: List<String>): String {
        val on = AppPrefs.defaultInstance().voiceInput.voicePauseSpace.getValue()
        val joined = parts.joinToString(if (on) " " else "")
        return withoutCjkSpaces(joined).trim()
    }

    /** 去掉中文字符之间的空格（英文/数字之间保留）。 */
    private fun withoutCjkSpaces(text: String): String {
        if (text.indexOf(' ') < 0) return text
        val sb = StringBuilder(text.length)
        for (i in text.indices) {
            val c = text[i]
            if (c == ' ') {
                val prev = text.getOrNull(i - 1)
                val next = text.getOrNull(i + 1)
                if (prev != null && next != null && isCjk(prev) && isCjk(next)) continue
            }
            sb.append(c)
        }
        return sb.toString()
    }

    private fun isCjk(c: Char): Boolean =
        c.code in 0x3400..0x9FFF || c.code in 0xF900..0xFAFF ||
            c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF

    /** 定稿时按需补标点（只有装了标点模型 + 开了自动加标点 + 多模型方案时才跑）。 */
    private suspend fun punctuateIfNeeded(service: TrimeInputMethodService, text: String): String {
        if (text.isEmpty()) return text
        if (!multiMode()) return text
        if (!AppPrefs.defaultInstance().voiceInput.voicePunctAuto.getValue()) return text
        if (!SherpaModelPack.isInstalled(service, SherpaModelPack.PUNCT_ZH_EN.id)) return text
        val out = withContext(Dispatchers.Default) { MultiModelRecognizer.punctuate(service, text) }
        return applyPunctuationPolicy(out ?: text)
    }

    /** 「自动加标点」关掉时，把模型/标点模型带出来的标点全部剥掉。 */
    private fun applyPunctuationPolicy(raw: String): String {
        if (raw.isEmpty()) return raw
        if (AppPrefs.defaultInstance().voiceInput.voicePunctAuto.getValue()) return raw
        return raw.replace(Regex("[，。！？、；：,.!?;:]"), "")
    }

    /**
     * 这一段值不值得上屏（CyIME 四条做严）：太短直接丢；短段只有「像话」才收；
     * 纯符号丢；和上一段尾部重叠的削掉重叠部分。
     */
    private fun sanitizeSegment(seg: Segment, raw: String, previous: String): String? {
        var body = raw.trim()
        if (body.isEmpty()) return null
        val letters = body.count { it.isLetter() }
        val cjk = body.count { isCjk(it) }
        if (letters == 0) return null // 纯标点/纯符号
        val voiced = seg.voicedMs
        if (voiced < MIN_ACCEPT_VOICED_MS) return null
        if (voiced < MIN_SEGMENT_MS && (body.length < 2 || (cjk == 0 && letters < 2))) return null
        if (body.length == 1 && voiced < MIN_SEGMENT_MS) return null
        body = trimOverlap(previous, body)
        return if (body.isEmpty()) null else body
    }

    /** 上一段结尾与新段开头重叠（VAD 前置回带）时，把重叠部分削掉。 */
    private fun trimOverlap(previous: String, next: String): String {
        if (previous.isEmpty() || next.length < 2) return next
        val max = minOf(OVERLAP_MAX_CHARS, previous.length, next.length)
        for (len in max downTo 2) {
            if (previous.endsWith(next.substring(0, len))) return next.substring(len)
        }
        return next
    }

    private fun needsSpaceBefore(text: String): Boolean {
        if (text.isEmpty()) return false
        val c = text[0]
        return c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'
    }

    /**
     * Reads out what is still buffered in the recorder. The finger is already up, so this is
     * real audio the user actually said — dropping it is what used to eat the last syllables.
     */
    private fun drainTail(
        record: AudioRecord,
        buffer: ShortArray,
        myId: Int,
        segmenter: Segmenter,
    ): Long {
        if (myId != sessionId.get()) return 0
        val deadline = SystemClock.uptimeMillis() + DRAIN_MAX_MS
        val step = min(buffer.size, SAMPLE_RATE / 10) // 100 ms slices so we can stop in time
        var total = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            val n = runCatching { record.read(buffer, 0, step) }.getOrDefault(-1)
            if (n <= 0) break
            segmenter.feed(buffer, n)
            total += n
        }
        return total
    }

    /** 识别日志：恒定写盘（外置 Documents，我或用户都能直接读），滚动保留最近 200 行。 */
    private fun logRecognition(
        service: TrimeInputMethodService,
        session: Int,
        discard: Boolean,
        segments: Int,
        audioMs: Int,
        capturedSamples: Long,
        drainSamples: Long,
        costMs: Long,
        wallMs: Long,
        text: String,
    ) {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val rtf = if (audioMs > 0) costMs.toFloat() / audioMs else 0f
        val sb = StringBuilder()
        sb.append(stamp)
        sb.append("  wx  s=").append(session)
        sb.append("  segs=").append(segments)
        sb.append("  audio=").append(audioMs).append("ms")
        sb.append("  captured=").append(capturedSamples)
        sb.append("  drain=").append(drainSamples)
        sb.append("  decode=").append(costMs).append("ms")
        sb.append("  wall=").append(wallMs).append("ms")
        sb.append("  chars=").append(text.length)
        sb.append(String.format(Locale.US, "  rtf=%.2f", rtf))
        if (discard) sb.append("  DROPPED")
        sb.append("  text=").append(text.replace('\n', ' '))
        WxAsrLog.append(service.applicationContext, sb.toString())
    }

    /**
     * Removes the sentence that is already sitting on screen from the preview, so that the
     * later [VoiceAi.handleFinal] commit is the only copy. Two cases:
     *  - the composing region is still alive: clearing it is enough;
     *  - the editor auto-committed it (Huawei Notepad does on blur/long-press): the text is now
     *    ordinary document text, so delete it by matching the tail we remember.
     */
    private fun retractComposing(ic: InputConnection?, lastPartial: String) {
        if (ic == null) return
        runCatching {
            if (lastPartial.isNotEmpty()) {
                val before = ic.getTextBeforeCursor(lastPartial.length + 8, 0)?.toString().orEmpty()
                if (before.endsWith(lastPartial)) {
                    ic.deleteSurroundingText(lastPartial.length, 0)
                }
            }
            ic.setComposingText("", 1)
            ic.finishComposingText()
        }
    }

    /**
     * MULTI 模式「边说边出字」：每采集一块（含前置静音）就把当前段已累计的音频整段重解一次，
     * 把「已定稿段 + 本段推测结果」写进 composing 区。推测解只猜不记账，松手时仍由
     * [VoiceAi.handleFinal] 做唯一一次提交，所以回退账本不受影响。
     *
     * 和 CyIME 一致：不设定时器、不设最小窗口，靠 [previewBusy] 保证「同一时刻最多一个推测解」。
     */
    private fun maybePreview(
        service: TrimeInputMethodService,
        myId: Int,
        segmenter: Segmenter,
        parts: ArrayList<String>,
    ) {
        if (!multiMode()) return
        val snap = segmenter.snapshot() ?: return
        if (snap.size < SAMPLE_RATE * 2 / 5) return
        if (!previewBusy.compareAndSet(false, true)) return
        val prefix = synchronized(parts) { parts.joinToString("") }
        previewScope.launch {
            try {
                val guessed = runCatching { MultiModelRecognizer.decode(snap) }.getOrNull().orEmpty()
                if (guessed.isEmpty()) return@launch
                if (myId != sessionId.get()) return@launch
                val shown = (prefix + applyPunctuationPolicy(guessed)).trim()
                if (shown.isEmpty()) return@launch
                lastEmittedText = shown
                withContext(Dispatchers.Main) {
                    runCatching { service.currentInputConnection?.setComposingText("$shown  ···", 1) }
                }
            } finally {
                previewBusy.set(false)
            }
        }
    }

    // ------------------------------------------------------------- segmenter

    /**
     * 能量分段器。把采集到的样本切成「有声音的一段」，在自然停顿处闭合。
     *
     * 判定：100 ms 一帧算 RMS，静音基线用静音帧做 EMA 自适应（不同手机底噪差很多），
     * 阈值取 `max(0.010, 基线 × 3)`；连续静音 ≥ [MIN_PAUSE_MS] 且本段已够长就切。
     * 连续讲话到 [MAX_SEGMENT_MS] 强切（避免单段超过模型的整段窗口）。
     */
    /** 停顿阈值按句长分三档：短句更灵敏，长句更宽容（抄 CyIME）。 */
    private fun pauseNeededMs(voicedMs: Int): Int = when {
        voicedMs < 3_000 -> 256
        voicedMs < 8_000 -> 384
        else -> 600
    }

    /** 一段音频 + 其中「有声」的毫秒数（用来说服自己这段不是误触）。 */
    private class Segment(val data: ShortArray, val voicedMs: Int)

    private class Segmenter(private val onSegment: (Segment) -> Unit) {
        private val frameSamples = SAMPLE_RATE * FRAME_MS / 1000
        private val prerollSamples = SAMPLE_RATE * PREROLL_MS / 1000

        private var data = ShortArray(SAMPLE_RATE * (MAX_SEGMENT_MS / 1000 + 2))
        private var size = 0

        private var lastVoicedEnd = 0
        private var voicedFrames = 0
        private var silenceFrames = 0
        private var noise = 0.0

        private var pending = ShortArray(frameSamples)
        private var pendingSize = 0

        /** 当前段已累计的音频（含前置静音）的拷贝，给「边说边出字」的推测解用。 */
        fun snapshot(): ShortArray? = if (size <= 0) null else data.copyOfRange(0, size)

        fun feed(pcm: ShortArray, n: Int) {
            var off = 0
            while (off < n) {
                val take = min(frameSamples - pendingSize, n - off)
                System.arraycopy(pcm, off, pending, pendingSize, take)
                pendingSize += take
                off += take
                if (pendingSize == frameSamples) {
                    onFrame(pending, frameSamples)
                    pendingSize = 0
                }
            }
        }

        /** 松手：把残余（不足一帧）+ 收尾静音一起送完，并强制闭合当前段。 */
        fun flush(tailSilence: ShortArray) {
            if (pendingSize > 0) {
                onFrame(pending, pendingSize)
                pendingSize = 0
            }
            // 不再把尾部纯静音喂进解码：模型会把它「听成」嗯/好的之类（CyIME 也不这么做）。
            emitAll()
        }

        private fun onFrame(p: ShortArray, n: Int) {
            val r = rms(p, n)
            val threshold = max(0.010, noise * 3.0)
            val voiced = r > threshold
            if (voiced) {
                voicedFrames += 1
                silenceFrames = 0
            } else {
                silenceFrames += 1
                noise = if (noise <= 0.0) r else noise * 0.9 + r * 0.1
            }
            append(p, n)
            if (voiced) lastVoicedEnd = size

            val totalMs = size * 1000 / SAMPLE_RATE
            val pauseMs = silenceFrames * FRAME_MS
            val voicedMs = voicedFrames * FRAME_MS

            if (voicedMs >= MIN_SEGMENT_MS && pauseMs >= pauseNeededMs(voicedMs)) {
                cutAt(min(size, lastVoicedEnd + prerollSamples))
            } else if (totalMs >= MAX_SEGMENT_MS || totalMs >= HARD_SEGMENT_MS) {
                cutAt(size)
            }
        }

        private fun append(src: ShortArray, n: Int) {
            if (size + n > data.size) {
                data = data.copyOf(max(data.size * 2, size + n))
            }
            System.arraycopy(src, 0, data, size, n)
            size += n
        }

        private fun cutAt(end: Int) {
            val cut = end.coerceIn(0, size)
            if (cut > 0) {
                onSegment(Segment(data.copyOfRange(0, cut), voicedFrames * FRAME_MS))
            }
            val rest = size - cut
            if (rest > 0) System.arraycopy(data, cut, data, 0, rest)
            size = rest
            lastVoicedEnd = 0
            voicedFrames = 0
            silenceFrames = 0
        }

        private fun emitAll() {
            if (size > 0) onSegment(Segment(data.copyOfRange(0, size), voicedFrames * FRAME_MS))
            size = 0
            lastVoicedEnd = 0
            voicedFrames = 0
            silenceFrames = 0
        }

        private fun rms(p: ShortArray, n: Int): Double {
            if (n <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) {
                val v = p[i].toDouble() / 32768.0
                sum += v * v
            }
            return sqrt(sum / n)
        }
    }

    // ------------------------------------------------------------------ infra

    private suspend fun ensurePermission(service: TrimeInputMethodService): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            service,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return true
        withContext(Dispatchers.Main) {
            runCatching {
                service.startActivity(
                    Intent(service, MicPermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            VoiceOverlayUiBridge.onDone?.invoke()
        }
        return false
    }

    /** 当前是否为「其它多模型」语音方案（解码走 [MultiModelRecognizer]）。 */
    private fun multiMode(): Boolean =
        AppPrefs.defaultInstance().voiceInput.voiceEngine.getValue() ==
            AppPrefs.VoiceInput.VoiceEngine.MULTI

    private fun ensureEngine(service: TrimeInputMethodService): Long {
        if (multiMode()) {
            return if (MultiModelRecognizer.ensure(service)) MULTI_HANDLE else 0L
        }
        val existing = handle
        if (existing != 0L) return existing
        val loaded = WxAsrInfer.load(WxVoicePack.dir(service))
        handle = loaded
        return loaded
    }

    private fun releaseEngine() {
        MultiModelRecognizer.release()
        val h = handle
        handle = 0L
        if (h != 0L) WxAsrInfer.release(h)
    }

    private fun voiceBridgeDone() {
        runCatching { VoiceOverlayUiBridge.onDone?.invoke() }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(bufferBytes: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = minBuf.coerceAtLeast(bufferBytes * 2)
        // MIC first, exactly like the Sherpa client: VOICE_RECOGNITION applies the platform's
        // noise suppressor / AGC, which we found eats soft syllables ("很多识别不准").
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
        )
        for (source in sources) {
            val rec = runCatching {
                AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            runCatching { rec.release() }
        }
        return null
    }

    private fun amplitude(pcm: ShortArray, size: Int): Int {
        var peak = 0
        for (i in 0 until size) {
            val v = abs(pcm[i].toInt())
            if (v > peak) peak = v
        }
        return peak
    }
}

/**
 * 语音识别日志。恒定写盘，位置在公共目录，便于和 Sherpa 做 A/B：
 *   Documents/BambooIME[Debug]/logs/wxasr.log
 * 对外置不可写时退回 App 私有目录，保证永不影响识别。
 */
object WxAsrLog {
    private const val MAX_LINES = 200
    private const val DIR = "BambooIME"
    private const val DIR_DEBUG = "BambooIMEDebug"
    private const val FALLBACK_DIR = "wxasr"
    private const val FILE_NAME = "wxasr.log"

    private fun externalFile(): File {
        val dir = if (BuildConfig.APPLICATION_ID.endsWith(".debug")) DIR_DEBUG else DIR
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(File(base, dir), "logs/$FILE_NAME")
    }

    private fun fallbackFile(ctx: Context): File = File(File(ctx.filesDir, FALLBACK_DIR), FILE_NAME)

    private fun resolveFile(ctx: Context): File {
        val ext = externalFile()
        val ok = runCatching {
            ext.parentFile?.mkdirs()
            ext.parentFile?.isDirectory == true
        }.getOrDefault(false)
        return if (ok) ext else fallbackFile(ctx)
    }

    @Synchronized
    fun append(ctx: Context, line: String) {
        runCatching {
            val f = resolveFile(ctx)
            f.parentFile?.mkdirs()
            val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
            lines.add(line)
            while (lines.size > MAX_LINES) lines.removeAt(0)
            f.writeText(lines.joinToString(System.lineSeparator()))
        }
    }

    fun tail(ctx: Context): String = runCatching {
        val f = resolveFile(ctx)
        if (f.exists()) f.readText() else ""
    }.getOrDefault("")

    fun path(ctx: Context): String = runCatching { resolveFile(ctx).absolutePath }.getOrDefault("")

    fun copyToClipboard(ctx: Context): Boolean = runCatching {
        val text = tail(ctx)
        if (text.isBlank()) return false
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BambooIME", text))
        true
    }.getOrDefault(false)

    fun clear(ctx: Context) {
        runCatching { resolveFile(ctx).delete() }
        runCatching { fallbackFile(ctx).delete() }
    }

    /** `Build.VERSION.SDK_INT` guard kept for lint: only used through [append]. */
    val apiLevel: Int get() = Build.VERSION.SDK_INT
}
