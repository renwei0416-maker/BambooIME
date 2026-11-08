package com.osfans.trime.actions.ocr

import android.content.Context
import android.content.SharedPreferences
import com.osfans.trime.TrimeApplication

/**
 * 拍照识字的可调设置。
 *
 * 和手写、AI 一样放独立的 SharedPreferences（`bamboo_ocr`），改完立即生效，不需要重新部署方案。
 */
object OcrPrefs {
    private const val FILE = "bamboo_ocr"

    private const val K_SINGLE_RATIO = "single_frame_ratio"
    private const val K_LIVE_ENABLED = "live_enabled"
    private const val K_LIVE_INTERVAL = "live_interval_ms"
    private const val K_CONFIDENCE = "confidence"
    private const val K_DET = "det_threshold"
    private const val K_BOX = "box_score"
    private const val K_CARD_OPACITY = "card_opacity"
    private const val K_HINT_ALPHA = "hint_alpha"
    private const val K_FRAME_WIDTH = "frame_width"
    private const val K_FRAME_HEIGHT = "frame_height"
    private const val K_FRAME_OFFSET = "frame_offset"

    /** 单字模式方框边长占预览高度的比例。 */
    const val DEFAULT_SINGLE_RATIO = 0.32f
    const val DEFAULT_LIVE_INTERVAL = 550
    const val DEFAULT_CONFIDENCE = 0.50f
    const val DEFAULT_DET = 0.30f
    const val DEFAULT_BOX = 0.50f

    /** 确认卡片的不透明度百分比（100＝完全不透明）。 */
    const val DEFAULT_CARD_OPACITY = 75

    /** 提示文字背景的不透明度（0 = 纯文字浮在画面上）。 */
    const val DEFAULT_HINT_ALPHA = 60

    /** 整段取景框：占预览宽/高的百分比，以及相对垂直居中位置的偏移（百分比）。 */
    const val DEFAULT_FRAME_WIDTH = 80
    const val DEFAULT_FRAME_HEIGHT = 30
    const val DEFAULT_FRAME_OFFSET = -10

    @Volatile
    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) {
            synchronized(this) {
                if (sp == null) {
                    sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun p(): SharedPreferences {
        sp?.let { return it }
        val ctx = TrimeApplication.getInstance()
        init(ctx)
        return sp ?: error("OcrPrefs init failed")
    }

    /** 单字框大小（0.20–0.50）。 */
    var singleFrameRatio: Float
        get() = p().getFloat(K_SINGLE_RATIO, DEFAULT_SINGLE_RATIO).coerceIn(0.20f, 0.50f)
        set(v) = p().edit().putFloat(K_SINGLE_RATIO, v.coerceIn(0.20f, 0.50f)).apply()

    /** 是否边写边认（实时识别）。 */
    var liveEnabled: Boolean
        get() = p().getBoolean(K_LIVE_ENABLED, true)
        set(v) = p().edit().putBoolean(K_LIVE_ENABLED, v).apply()

    /** 实时识别间隔（毫秒，250–2000）。 */
    var liveIntervalMs: Int
        get() = p().getInt(K_LIVE_INTERVAL, DEFAULT_LIVE_INTERVAL).coerceIn(250, 2000)
        set(v) = p().edit().putInt(K_LIVE_INTERVAL, v.coerceIn(250, 2000)).apply()

    /** 整行识别的最低置信度（0.30–0.90）。 */
    var confidence: Float
        get() = p().getFloat(K_CONFIDENCE, DEFAULT_CONFIDENCE).coerceIn(0.30f, 0.90f)
        set(v) = p().edit().putFloat(K_CONFIDENCE, v.coerceIn(0.30f, 0.90f)).apply()

    /** det 概率图二值化阈值（0.10–0.70）。 */
    var detThreshold: Float
        get() = p().getFloat(K_DET, DEFAULT_DET).coerceIn(0.10f, 0.70f)
        set(v) = p().edit().putFloat(K_DET, v.coerceIn(0.10f, 0.70f)).apply()

    /** 文字框评分下限（0.10–0.90）。 */
    var boxScore: Float
        get() = p().getFloat(K_BOX, DEFAULT_BOX).coerceIn(0.10f, 0.90f)
        set(v) = p().edit().putFloat(K_BOX, v.coerceIn(0.10f, 0.90f)).apply()

    /**
     * 确认卡片的不透明度（30–100）。
     *
     * 卡片浮在键盘上方，会挡住 App 自己的输入框（QQ 里尤其明显），调低能透出底下的内容。
     */
    /** 提示文字背景不透明度，0–100。 */
    var hintAlpha: Int
        get() = p().getInt(K_HINT_ALPHA, DEFAULT_HINT_ALPHA).coerceIn(0, 100)
        set(value) = p().edit().putInt(K_HINT_ALPHA, value.coerceIn(0, 100)).apply()

    /** 整段取景框宽度，占预览宽度的百分比。 */
    var frameWidth: Int
        get() = p().getInt(K_FRAME_WIDTH, DEFAULT_FRAME_WIDTH).coerceIn(20, 100)
        set(value) = p().edit().putInt(K_FRAME_WIDTH, value.coerceIn(20, 100)).apply()

    /** 整段取景框高度，占预览高度的百分比。 */
    var frameHeight: Int
        get() = p().getInt(K_FRAME_HEIGHT, DEFAULT_FRAME_HEIGHT).coerceIn(5, 60)
        set(value) = p().edit().putInt(K_FRAME_HEIGHT, value.coerceIn(5, 60)).apply()

    /** 整段取景框相对垂直居中的偏移，占预览高度的百分比（负数上移）。 */
    var frameOffset: Int
        get() = p().getInt(K_FRAME_OFFSET, DEFAULT_FRAME_OFFSET).coerceIn(-25, 25)
        set(value) = p().edit().putInt(K_FRAME_OFFSET, value.coerceIn(-25, 25)).apply()

    var cardOpacity: Int
        get() = p().getInt(K_CARD_OPACITY, DEFAULT_CARD_OPACITY).coerceIn(30, 100)
        set(v) = p().edit().putInt(K_CARD_OPACITY, v.coerceIn(30, 100)).apply()
}
