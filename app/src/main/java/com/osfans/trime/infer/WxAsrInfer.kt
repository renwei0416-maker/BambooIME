/*
 * BambooIME — WeChat offline ASR bridge (JNI).
 *
 * The native side lives in `app/src/main/rust/bamboo-wxasr-infer` (crate
 * `bamboo-wxasr-infer`, library `libbamboo_wxasr_infer.so`). It only *reads* the model
 * pack the user installed; nothing is bundled in the APK.
 *
 * Model dir layout (produced by [WxVoicePack]):
 *   files/wxasr/embed_*.xnet        int8 weights (magic "XNET")
 *   files/wxasr/dict.decoder.utf8.txt
 *   files/wxasr/feature.conf        FBank 40-dim + CMS
 *   files/wxasr/sr_online.conf      decoding params
 *   files/wxasr/CMS.40.bin
 *
 * Usage:
 *   val h = WxAsrInfer.load(dir)            // 1–3 s, must be off the UI thread
 *   WxAsrInfer.push(h, pcmChunk)            // 16 kHz mono i16
 *   if (WxAsrInfer.fresh(h) >= 30) text = WxAsrInfer.partial(h)
 *   val final = WxAsrInfer.finish(h)
 *   WxAsrInfer.release(h)
 */
package com.osfans.trime.infer

import java.io.File

object WxAsrInfer {

    /** 10 ms of audio per feature frame, must match the native side. */
    const val FRAME_MS = 10

    /** A partial result is worth refreshing after this many new frames (≈300 ms). */
    const val PARTIAL_FRAMES = 30

    private const val LIB = "bamboo_wxasr_infer"

    @Volatile
    private var loadState = 0 // 0 = untried, 1 = ok, -1 = failed

    @Volatile
    private var loadError: String? = null

    /** True when the native library could be loaded on this ABI. */
    val available: Boolean
        get() = ensureLoaded()

    /** Last load failure, for the settings page to show instead of crashing. */
    val error: String?
        get() {
            ensureLoaded()
            return loadError
        }

    private fun ensureLoaded(): Boolean {
        if (loadState != 0) return loadState == 1
        synchronized(this) {
            if (loadState != 0) return loadState == 1
            loadState = try {
                System.loadLibrary(LIB)
                1
            } catch (t: Throwable) {
                loadError = t.message ?: t.javaClass.simpleName
                -1
            }
            return loadState == 1
        }
    }

    /** Loads the model; returns 0 when it failed (see [error]). */
    fun load(dir: File): Long {
        if (!ensureLoaded()) return 0L
        return try {
            nativeLoad(dir.absolutePath)
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            0L
        }
    }

    /**
     * Starts a new utterance on an existing handle (the parsed model stays cached).
     *
     * Must be called before feeding the first chunk of every hold session: the native
     * `Stream` keeps the feature cache, the committed-block cursor and the stabilizer, so
     * reusing it would make the next `finish()` decode the previous sentence again.
     */
    fun reset(handle: Long) {
        if (handle == 0L) return
        try {
            nativeReset(handle)
        } catch (_: Throwable) {
            // Same contract as load(): a failed reset just means stale text, never a crash.
        }
    }

    /** Applies decode tuning; pass 0 frames / -1 policy to keep the defaults. */
    fun config(handle: Long, wholeMaxFrames: Int, policy: Int) {
        if (handle == 0L) return
        try {
            nativeConfig(handle, wholeMaxFrames, policy)
        } catch (_: Throwable) {
            // Tuning is best-effort: never break the session for it.
        }
    }

    fun push(handle: Long, pcm: ShortArray) {
        if (handle == 0L || pcm.isEmpty()) return
        nativePush(handle, pcm)
    }

    fun fresh(handle: Long): Int = if (handle == 0L) 0 else nativeFresh(handle)

    fun partial(handle: Long): String =
        if (handle == 0L) "" else (nativePartial(handle) ?: "")

    fun finish(handle: Long): String =
        if (handle == 0L) "" else (nativeFinish(handle) ?: "")

    fun release(handle: Long) {
        if (handle == 0L) return
        try {
            nativeRelease(handle)
        } catch (_: Throwable) {
            // Releasing must never throw on the teardown path.
        }
    }

    @JvmStatic private external fun nativeLoad(dir: String): Long
    @JvmStatic private external fun nativeReset(handle: Long): Int
    @JvmStatic private external fun nativeConfig(handle: Long, wholeMaxFrames: Int, policy: Int): Int
    @JvmStatic private external fun nativePush(handle: Long, pcm: ShortArray)
    @JvmStatic private external fun nativeFresh(handle: Long): Int
    @JvmStatic private external fun nativePartial(handle: Long): String?
    @JvmStatic private external fun nativeFinish(handle: Long): String?
    @JvmStatic private external fun nativeRelease(handle: Long)
}
