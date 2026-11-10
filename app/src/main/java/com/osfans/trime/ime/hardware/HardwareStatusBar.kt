// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.hardware

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizePx
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.HardwareKeyboardPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.link.AsrkbSpeechClient
import com.osfans.trime.link.AsrkbVoiceHoldSessionController
import com.osfans.trime.link.SherpaSpeechClient
import com.osfans.trime.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import splitties.dimensions.dp
import timber.log.Timber

/**
 * 外接键盘时的浮动状态条。
 *
 * 硬件键盘接上后输入法窗口通常不显示，所以这里用一个 [PopupWindow] 单独浮在屏幕上。
 * 位置按屏幕比例保存；可以收起到只剩中/英，两种状态都能拖。
 */
class HardwareStatusBar(
    private val service: TrimeInputMethodService,
    private val rime: RimeSession,
) {
    private val ctx = service


    private val handler = Handler(Looper.getMainLooper())

    /**
     * 语音会话。外接键盘时没有键盘窗口，波形回调留空；AIDL / 内置 / 第三方怎么走由控制器决定。
     */
    private val voiceController by lazy {
        AsrkbVoiceHoldSessionController(
            service = service,
            showOverlay = {},
            startWave = {},
            updateAmplitude = {},
            hideOverlay = {},
            onSessionFinished = {
                rime.launchOnReady { api -> runCatching { api.setRuntimeOption("_voice_assist", false) } }
            },
            useAidl = { AppPrefs.defaultInstance().voiceInput.asrkbAidlVoiceInputEnabled.getValue() },
        )
    }

    private val row =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private val window =
        PopupWindow(ctx).apply {
            contentView = row
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            isFocusable = false
            isOutsideTouchable = false
            isTouchable = true
            animationStyle = 0
            elevation = 0f
            // 输入法窗口很小时，默认的裁剪会把浮层可移动范围限制住
            isClippingEnabled = false
        }

    private val ticker =
        object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, REFRESH_MS)
            }
        }

    private var capsLock = false

    private var asciiMode = false

    private var fullShape = false

    private var asciiPunct = false

    /** 上次按此时间做的状态刷新，避免连按时反复查 rime。 */
    private var lastRefreshAt = 0L

    /** 上一次真正绘制的内容，用来判断要不要重建视图。 */
    private var lastSignature: String? = null

    /** 订阅 rime 的选项变化，物理键盘切中英/全半角/标点时不必等定时器。 */
    private var optionJob: Job? = null

    val isShowing: Boolean
        get() = runCatching { window.isShowing }.getOrDefault(false)

    fun show() {
        if (!HardwareKeyboardPrefs.statusBarEnabled || isShowing) return
        refresh()
        val anchor = runCatching { service.window.window?.decorView }.getOrNull()
        if (anchor == null) {
            Timber.d("hwstatus: show skipped, no decorView")
            return
        }
        val (x, y) = resolvePosition()
        val ok =
            runCatching {
                window.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
                window.isShowing
            }.onFailure {
                Timber.w(it, "hwstatus: showAtLocation failed")
            }.getOrDefault(false)
        Timber.d("hwstatus: show at (%d,%d) ok=%s", x, y, ok)
        if (!ok) return
        startOptionWatch()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /** 只关心影响状态条显示的三项。 */
    private fun startOptionWatch() {
        if (optionJob != null) return
        optionJob =
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                rime.run { messageFlow }.collect { message ->
                    if (message !is RimeMessage.OptionMessage) return@collect
                    when (message.data.option) {
                        "ascii_mode", "ascii_punct", "full_shape" -> refresh()
                    }
                }
            }
        Timber.d("hwstatus: option watch started")
    }

    private fun stopOptionWatch() {
        if (optionJob == null) return
        optionJob?.cancel()
        optionJob = null
        Timber.d("hwstatus: option watch stopped")
    }

    fun hide() {
        if (isShowing) Timber.d("hwstatus: hide at (%d,%d)", lastX, lastY)
        stopOptionWatch()
        handler.removeCallbacks(ticker)
        runCatching { window.dismiss() }
    }

    /**
     * 由输入法服务在按键事件里喂进来。
     *
     * 不能只读事件自带的修饰位：按下 CAPS 的那一下，系统还没把这一位翻过来；
     * 而之后的普通按键在某些机型上又不带这一位，状态会来回跳。
     * 所以 CAPS 键自己记账，普通按键只在「系统说开着、本地是关」时补一次。
     */
    fun onKeyEvent(event: KeyEvent) {
        if (event.keyCode == KeyEvent.KEYCODE_CAPS_LOCK && event.action == KeyEvent.ACTION_DOWN) {
            capsLock = !capsLock
            Timber.d("hwstatus: caps toggle -> %s", capsLock)
            rebuild()
        } else if (event.isCapsLockOn && !capsLock) {
            capsLock = true
            Timber.d("hwstatus: caps on by meta state")
            rebuild()
        }
        // shift / 中英切换后 ascii_mode 会变，按键后立刻对一次，别等 500 毫秒的定时器
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < 80L) return
        lastRefreshAt = now
        refresh()
    }

    fun refresh() {
        rime.launchOnReady { api ->
            // 三项都直接问引擎：读状态缓存拿到的是上一次处理完的值，点击后立刻刷新会慢一拍。
            val ascii = runCatching { api.getRuntimeOption("ascii_mode") }.getOrDefault(asciiMode)
            val shape = runCatching { api.getRuntimeOption("full_shape") }.getOrDefault(false)
            val punct = runCatching { api.getRuntimeOption("ascii_punct") }.getOrDefault(false)
            handler.post {
                asciiMode = ascii
                fullShape = shape
                asciiPunct = punct
                rebuild()
            }
        }
    }

    // ---------------- 尺寸与颜色 ----------------

    private val scale: Float
        get() = HardwareKeyboardPrefs.statusBarScale / 100f * BASE_SCALE

    private fun s(value: Int): Int = (ctx.dp(value) * scale).toInt().coerceAtLeast(1)

    private fun s(value: Float): Float = value * scale

    private fun rebuild() {
        val itemBg = firstColor("key_back_color", "back_color")
        val fg = firstColor("key_text_color", "text_color")
        // 主题里给「按下高亮」用的键名各家不一，按由专到泛的顺序试
        val activeBg =
            firstColorOrNull("hilited_key_back_color", "hilited_back_color", "hilited_candidate_back_color")
                ?: mix(fg, itemBg, 0.25f)
        val activeFg =
            firstColorOrNull("hilited_key_text_color", "key_text_color", "text_color")
                ?: mix(itemBg, fg, 0.5f)
        Timber.d(
            "hwstatus: colors bg=%s fg=%s active=%s activeFg=%s",
            hex(itemBg),
            hex(fg),
            hex(activeBg),
            hex(activeFg),
        )

        row.setPadding(s(4), s(2), s(4), s(2))
        row.background =
            GradientDrawable().apply {
                setColor(itemBg)
                cornerRadius = s(10).toFloat()
            }

        val collapsed = HardwareKeyboardPrefs.statusBarCollapsed
        val items = mutableListOf<Node>()
        if (collapsed) {
            // 收起时只留中/英，宽度就只有那一格
            items += asciiNode()
        } else {
            if (HardwareKeyboardPrefs.showAscii) items += asciiNode()
            if (HardwareKeyboardPrefs.showCaps) {
                items += Node(text = if (capsLock) "CAPS" else "caps", active = capsLock, onClick = null)
            }
            if (HardwareKeyboardPrefs.showFullShape) {
                items +=
                    Node(
                        text = if (fullShape) "全" else "半",
                        active = fullShape,
                        onClick = {
                            val next = !fullShape
                            Timber.d("hwstatus: click full_shape -> %s", next)
                            rime.launchOnReady { api ->
                                runCatching { api.setRuntimeOption("full_shape", next) }
                                    .onFailure { Timber.w(it, "hwstatus: set full_shape failed") }
                            }
                            refresh()
                        },
                    )
            }
            if (HardwareKeyboardPrefs.showPunct) {
                // 英文编码方式下标点必然是英文标点，这时按钮只显示状态、点了不改
                val englishPunct = asciiPunct || asciiMode
                items +=
                    Node(
                        text = if (englishPunct) ",." else "。，",
                        active = englishPunct,
                        onClick =
                            if (asciiMode) {
                                null
                            } else {
                                {
                                    val next = !asciiPunct
                                    Timber.d("hwstatus: click ascii_punct -> %s", next)
                                    rime.launchOnReady { api ->
                                        runCatching { api.setRuntimeOption("ascii_punct", next) }
                                            .onFailure { Timber.w(it, "hwstatus: set ascii_punct failed") }
                                    }
                                    refresh()
                                }
                            },
                    )
            }
            // 语音：只做唤醒，具体走 AIDL / 内置 / 第三方由 App 自己决定
            val holding = voiceHolding()
            items +=
                Node(
                    text = if (holding) "■" else "音",
                    icon = if (holding) ICON_VOICE_STOP else ICON_VOICE,
                    bgOverride = if (holding) VOICE_RED else null,
                    onClick = {
                        Timber.d("hwstatus: click voice holding=%s", holding)
                        if (holding) {
                            voiceController.stop()
                            rime.launchOnReady { api ->
                                runCatching { api.setRuntimeOption("_voice_assist", false) }
                            }
                        } else {
                            voiceController.start()
                            rime.launchOnReady { api ->
                                runCatching { api.setRuntimeOption("_voice_assist", true) }
                            }
                        }
                        handler.postDelayed({ refresh() }, 300L)
                    },
                )
            if (HardwareKeyboardPrefs.showSettings) {
                items +=
                    Node(
                        text = "设",
                        icon = ICON_SETTINGS,
                        onClick = {
                            Timber.d("hwstatus: click settings")
                            runCatching {
                                ctx.startActivity(
                                    Intent(ctx, MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            }.onFailure { Timber.w(it, "hwstatus: open settings failed") }
                        },
                    )
            }
        }
        // 最右侧的折叠/展开按钮，两种状态都要在
        items +=
            Node(
                triangle = if (collapsed) Triangle.RIGHT else Triangle.LEFT,
                onClick = { toggleCollapsed() },
            )

        val signature =
            items.joinToString("/") {
                (it.text ?: "") + (it.icon ?: "") + (if (it.active) "*" else "") +
                    (it.bgOverride?.toString() ?: "") + (it.triangle?.name ?: "")
            } + "#" + fg + "," + activeBg + "," + s(10)
        if (signature == lastSignature && row.childCount > 0) {
            // 内容没变就不要再摘视图，否则正在拖的手指会丢事件
            return
        }
        lastSignature = signature
        row.removeAllViews()
        items.forEach { node ->
            row.addView(makeNode(node, fg, activeFg, activeBg))
        }
        if (isShowing) {
            runCatching { window.update() }
        }
    }

    private fun asciiNode(): Node =
        Node(
            text = if (asciiMode) "英" else "中",
            active = asciiMode,
            onClick = {
                val next = !asciiMode
                Timber.d("hwstatus: click ascii -> %s", next)
                rime.launchOnReady { api ->
                    runCatching { api.setRuntimeOption("ascii_mode", next) }
                        .onFailure { Timber.w(it, "hwstatus: set ascii_mode failed") }
                    // 同一队列里先设后读，读到的就是刚设进去的值
                    handler.post { refresh() }
                }
            },
        )

    private fun toggleCollapsed() {
        val next = !HardwareKeyboardPrefs.statusBarCollapsed
        Timber.d("hwstatus: collapsed -> %s", next)
        HardwareKeyboardPrefs.statusBarCollapsed = next
        rebuild()
    }

    private fun voiceHolding(): Boolean =
        runCatching { AsrkbSpeechClient.isHolding() || SherpaSpeechClient.isHolding() }.getOrDefault(false)

    private fun firstColor(vararg keys: String): Int = firstColorOrNull(*keys) ?: FALLBACK_BG

    private fun firstColorOrNull(vararg keys: String): Int? {
        for (key in keys) {
            val value = runCatching { ColorManager.getColorOr(key, 0) }.getOrDefault(0)
            if ((value ushr 24) != 0) {
                Timber.d("hwstatus: color %s <- %s", hex(value), key)
                return value or 0xFF000000.toInt()
            }
        }
        return null
    }

    private fun mix(
        foreground: Int,
        background: Int,
        ratio: Float,
    ): Int {
        val r = (Color.red(foreground) * ratio + Color.red(background) * (1 - ratio)).toInt()
        val g = (Color.green(foreground) * ratio + Color.green(background) * (1 - ratio)).toInt()
        val b = (Color.blue(foreground) * ratio + Color.blue(background) * (1 - ratio)).toInt()
        return Color.rgb(r, g, b)
    }

    private fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    private fun makeNode(
        node: Node,
        fg: Int,
        activeFg: Int,
        activeBg: Int,
    ): View {
        node.triangle?.let { direction ->
            return TriangleView(direction).apply {
                tint = fg
                node.onClick?.let { action -> setOnClickListener { action() } }
                setOnTouchListener(dragListener)
            }
        }

        val tint =
            when {
                node.bgOverride != null -> VOICE_FG
                node.active -> activeFg
                else -> fg
            }
        val container =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                minimumWidth = s(34)
                minimumHeight = s(40)
                setPadding(s(7), s(4), s(7), s(4))
                background =
                    GradientDrawable().apply {
                        setColor(node.bgOverride ?: if (node.active) activeBg else Color.TRANSPARENT)
                        cornerRadius = s(7).toFloat()
                    }
                node.onClick?.let { action -> setOnClickListener { action.invoke() } }
                setOnTouchListener(dragListener)
            }

        val drawable = node.icon?.let { iconDrawable(it, tint) }
        if (drawable != null) {
            container.addView(
                ImageView(ctx).apply {
                    setImageDrawable(drawable)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(s(20), s(20)),
            )
        } else {
            container.addView(
                TextView(ctx).apply {
                    text = node.text ?: ""
                    textSize = s(12f)
                    setTextColor(tint)
                    setTypeface(typeface, if (node.active) Typeface.BOLD else Typeface.NORMAL)
                    gravity = Gravity.CENTER
                },
            )
        }
        return container
    }

    private fun iconDrawable(
        name: String,
        color: Int,
    ): Drawable? =
        runCatching {
            IconicsDrawable(ctx, name).apply {
                sizePx = s(20)
                colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            }
        }.getOrNull()

    /** 自绘三角形，避免依赖图标库里方向不一致的形状名。 */
    private inner class TriangleView(
        private val direction: Triangle,
    ) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        var tint: Int = Color.WHITE
            set(value) {
                field = value
                invalidate()
            }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            setMeasuredDimension(s(26), s(40))
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val arm = minOf(width, height) * 0.20f
            path.reset()
            when (direction) {
                Triangle.LEFT -> {
                    path.moveTo(cx + arm * 0.7f, cy - arm)
                    path.lineTo(cx + arm * 0.7f, cy + arm)
                    path.lineTo(cx - arm, cy)
                }

                Triangle.RIGHT -> {
                    path.moveTo(cx - arm * 0.7f, cy - arm)
                    path.lineTo(cx - arm * 0.7f, cy + arm)
                    path.lineTo(cx + arm, cy)
                }
            }
            path.close()
            paint.color = tint
            canvas.drawPath(path, paint)
        }
    }

    // ---------------- 拖动 ----------------

    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    private val dragListener: View.OnTouchListener =
        View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = lastX
                    downWindowY = lastY
                    dragging = false
                    Timber.d(
                        "hwstatus: down raw=(%.0f,%.0f) win=(%d,%d) size=(%d,%d)",
                        downRawX,
                        downRawY,
                        downWindowX,
                        downWindowY,
                        row.width,
                        row.height,
                    )
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(dx) > ctx.dp(6) || kotlin.math.abs(dy) > ctx.dp(6))) {
                        dragging = true
                    }
                    if (dragging) {
                        moveTo((downWindowX + dx).toInt(), (downWindowY + dy).toInt())
                    }
                    dragging
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Timber.d("hwstatus: up dragging=%s at (%d,%d)", dragging, lastX, lastY)
                    if (dragging) {
                        persistPosition()
                    }
                    dragging
                }

                else -> false
            }
        }

    private var lastX = 0
    private var lastY = 0

    init {
        // 拖动手势挂在整条上，按钮之间的空隙也能拖
        row.setOnTouchListener(dragListener)
    }

    private fun moveTo(
        x: Int,
        y: Int,
    ) {
        val metrics = ctx.resources.displayMetrics
        val w = row.width.takeIf { it > 0 } ?: ctx.dp(180)
        val h = row.height.takeIf { it > 0 } ?: ctx.dp(34)
        val nx = x.coerceIn(0, (metrics.widthPixels - w).coerceAtLeast(0))
        val ny = y.coerceIn(0, (metrics.heightPixels - h).coerceAtLeast(0))
        lastX = nx
        lastY = ny
        val ok =
            runCatching {
                window.update(nx, ny, -1, -1)
                true
            }.onFailure { Timber.w(it, "hwstatus: update failed") }
                .getOrDefault(false)
        Timber.d("hwstatus: move to (%d,%d) ok=%s", nx, ny, ok)
    }

    private fun persistPosition() {
        val metrics = ctx.resources.displayMetrics
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return
        HardwareKeyboardPrefs.statusBarX = lastX.toFloat() / metrics.widthPixels
        HardwareKeyboardPrefs.statusBarY = lastY.toFloat() / metrics.heightPixels
    }

    private fun resolvePosition(): Pair<Int, Int> {
        val metrics = ctx.resources.displayMetrics
        val w = ctx.dp(180)
        val h = ctx.dp(34)
        val savedX = HardwareKeyboardPrefs.statusBarX
        val savedY = HardwareKeyboardPrefs.statusBarY
        val x =
            if (savedX.isNaN()) {
                metrics.widthPixels - w - ctx.dp(8)
            } else {
                (savedX * metrics.widthPixels).toInt()
            }
        val y =
            if (savedY.isNaN()) {
                ctx.dp(40)
            } else {
                (savedY * metrics.heightPixels).toInt()
            }
        lastX = x.coerceIn(0, (metrics.widthPixels - w).coerceAtLeast(0))
        lastY = y.coerceIn(0, (metrics.heightPixels - h).coerceAtLeast(0))
        return lastX to lastY
    }

    private class Node(
        val text: String? = null,
        val icon: String? = null,
        val active: Boolean = false,
        val bgOverride: Int? = null,
        val triangle: Triangle? = null,
        val onClick: (() -> Unit)? = null,
    )

    private enum class Triangle { LEFT, RIGHT }

    private companion object {
        const val REFRESH_MS = 500L

        /** 滑块 100% 对应的实际比例：整条按原始尺寸的 80% 排，留出放大余量。 */
        const val BASE_SCALE = 0.80f

        const val ICON_SETTINGS = "cmd_cog"

        const val ICON_VOICE = "cmd_microphone"
        const val ICON_VOICE_STOP = "cmd_stop_circle"

        /** 识别中底色：酒红偏亮一档，不跟主题走。 */
        const val VOICE_RED = 0xFFA0394A.toInt()
        const val VOICE_FG = 0xFFFFFFFF.toInt()

        const val FALLBACK_BG = 0xFF2B2B2B.toInt()
    }
}
