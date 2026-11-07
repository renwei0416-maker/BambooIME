/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.ocr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.text.InputType
import android.text.TextUtils
import android.widget.TextView
import android.widget.Toast
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.osfans.trime.actions.bamboo.BambooActions
import com.osfans.trime.actions.handwriting.HandwritingLog
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * 拍照识字面板（BambooOcr）。
 *
 * 主题里任何一个键位指向动作名即可打开（大小写不敏感）：
 * ```
 * key(merge(S.symbol, key { click = "BambooOcr", label = "识字" }))
 * ```
 *
 * 结构：顶部提示条 / 相机预览 + 取景框遮罩 / 底部「返回 · 拍照 · 相册」。
 * 本阶段（S1）只做骨架：能开、能预览、能关；识别链路在后续阶段接入。
 *
 * 与手写板一样，**不能用 `by di.instance()` 缓存 service / windowManager**：
 * `InputDependencyManager` 每次重建 InputView 都会换一整套依赖，而本类是进程级单例，
 * 缓存下来就会拿到旧管理器，`attachWindow` 直接 early-return（返回键失灵）。
 */
class OcrWindow : BoardWindow.NoBarBoardWindow() {
    private val service: TrimeInputMethodService
        get() = TrimeInputMethodService.current
            ?: error("ocr: IME service is not available")

    private val windowManager: BoardWindowManager
        get() = BoardWindowManager.current
            ?: error("ocr: board window manager is not available")

    private lateinit var previewView: PreviewView
    private lateinit var maskView: ScanMaskView
    private lateinit var hintText: TextView
    private lateinit var permissionBox: LinearLayout
    private lateinit var actionsRow: LinearLayout

    private var rootView: View? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var started = false

    /** 实时取帧的用例；只在相机可用时存在。 */
    private var analysis: ImageAnalysis? = null

    private var camera: androidx.camera.core.Camera? = null

    /** 单线程做识别，避免和相机线程抢。 */
    private val ocrExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bamboo-ocr")
    }

    /** 最新一帧（已按显示方向旋正），拍照时从这里裁取景框。 */
    @Volatile
    private var lastFrame: Bitmap? = null

    private var lastFrameAt = 0L

    /** 实时预览：连续两帧一致才显示，画面一变就清空。 */
    private val stability = OcrPreviewStability()

    private var liveRunning = false

    private var lastLiveAt = 0L

    private var frameCount = 0

    private var frameErrors = 0

    /** 实时识别只 dump 第一张裁切图，避免刷屏。 */
    private var liveDumped = false

    /** 预览容器：显示确认卡片时整块让位。 */
    private var previewWrap: FrameLayout? = null

    /** 确认卡片：可编辑的识别结果 + 重拍/复制/上屏。 */
    private var confirmCard: LinearLayout? = null

    private var confirmEdit: EditText? = null

    /** 取到结果后显示在取景框上方的条里（S4 会换成可编辑确认页）。 */
    private var lastResult: String = ""

    /** 已经按下「拍照」确认，实时循环暂停，避免把确认后的内容又覆盖掉。 */
    private var scanned = false

    /** CameraX 需要 LifecycleOwner；输入法窗口没有，自己造一个跟着 attach/detach 走。 */
    private val lifecycleOwner =
        object : LifecycleOwner {
            val registry = LifecycleRegistry(this)

            override val lifecycle: Lifecycle
                get() = registry
        }

    private var density = 1f
    private var colorBack = 0xFF202420.toInt()
    private var colorKeyBg = 0xFF2B302B.toInt()
    private var colorKeyText = 0xFFF2F0E8.toInt()
    private var colorSub = 0xFF8A8A80.toInt()
    private var colorAccent = 0xFF6FAE8F.toInt()
    private var cornerPx = 8f

    companion object {
        /** 全局唯一实例：与手写板同样的理由（保持状态、避免重复占窗口）。 */
        val instance: OcrWindow by lazy { OcrWindow() }

        /** 主题里用的固定动作名。 */
        const val TOKEN = "BambooOcr"

        fun isToken(text: String): Boolean = text.equals(TOKEN, ignoreCase = true)

        /** 取景框的归一化位置：横向几乎占满，纵向取中间一条。 */
        private const val FRAME_LEFT = 0.05f
        private const val FRAME_TOP = 0.32f
        private const val FRAME_RIGHT = 0.95f
        private const val FRAME_BOTTOM = 0.68f
    }

    override fun onCreateView(): View {
        HandwritingLog.d("ocr", "onCreateView")
        val created = try {
            buildView()
        } catch (t: Throwable) {
            Timber.e(t, "ocr: buildView failed")
            HandwritingLog.w("ocr", "buildView threw", t)
            plainView()
        }
        rootView = created
        return created
    }

    override fun onAttached() {
        HandwritingLog.d("ocr", "onAttached")
        // 只在这个窗口挂载时隐藏顶栏，其它窗口完全不受影响
        runCatching { service.currentInputView?.setInputBarSuppressed(true) }
            .onFailure { Timber.w(it, "ocr: hide input bar failed") }
        // 每次挂上都重新起相机：切走再回来要能继续看画面
        startCamera()
    }

    override fun onDetached() {
        HandwritingLog.d("ocr", "onDetached")
        runCatching { service.currentInputView?.setInputBarSuppressed(false) }
            .onFailure { Timber.w(it, "ocr: restore input bar failed") }
        stability.reset()
        scanned = false
        stopCamera()
    }

    override fun toString(): String = "OcrWindow"

    // ---------------------------------------------------------------- 视图

    private fun buildView(): View {
        val dm = context.resources.displayMetrics
        density = dm.density
        val gap = dp(4)

        fun color(key: String, fallback: Int): Int =
            runCatching { ColorManager.getColorOr(key, fallback) }
                .onFailure { Timber.w(it, "ocr: color '$key' unavailable") }
                .getOrDefault(fallback)

        colorBack = color("back_color", 0xFF202420.toInt())
        colorKeyBg = color("key_back_color", 0xFF2B302B.toInt())
        colorKeyText = color("key_text_color", 0xFFF2F0E8.toInt())
        colorSub = color("key_symbol_color", 0xFF8A8A80.toInt())
        colorAccent = color("hilited_key_back_color", 0xFF6FAE8F.toInt())
        cornerPx = dp(8).toFloat()

        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(gap, gap, gap, gap)
                background =
                    GradientDrawable().apply {
                        setColor(colorBack)
                        cornerRadius = dp(12).toFloat()
                    }
                clipToOutline = true
            }

        // 识别结果就显示在这里（和 CyIME 一样：有结果就顶掉提示语）。
        // 最多 3 行 / 96dp：内容一多，下面的相机预览会自动让位（它是 weight = 1）。
        hintText =
            TextView(context).apply {
                text = "将文字对准中间框"
                textSize = 15f
                setTextColor(colorSub)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setLineSpacing(dp(2).toFloat(), 1f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                minHeight = dp(28)
                maxHeight = dp(96)
                setPadding(dp(6), dp(3), dp(6), dp(3))
            }
        root.addView(hintText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val previewWrap =
            FrameLayout(context).apply {
                background =
                    GradientDrawable().apply {
                        setColor(colorKeyBg)
                        cornerRadius = cornerPx
                    }
                clipToOutline = true
            }
        previewView =
            PreviewView(context).apply {
                // COMPATIBLE = TextureView：输入法窗口里比 SurfaceView 稳
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        previewWrap.addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        maskView =
            ScanMaskView(context).apply {
                maskColor = 0xB3000000.toInt()
                borderColor = 0xFFE8E4D8.toInt()
                accentColor = colorAccent
                strokePx = dp(2).toFloat()
            }
        previewWrap.addView(maskView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        permissionBox =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(
                    TextView(context).apply {
                        text = "需要相机权限才能拍照识字"
                        textSize = 14f
                        setTextColor(colorKeyText)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                    },
                )
                addView(
                    button("去授权") { requestCameraPermission() },
                    LinearLayout.LayoutParams(WRAP_CONTENT, dp(36)).apply { topMargin = dp(10) },
                )
            }
        previewWrap.addView(permissionBox, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        this.previewWrap = previewWrap
        root.addView(previewWrap, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // 识别完成后盖住相机预览：可编辑的结果 + 重拍/复制/上屏。
        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                background =
                    GradientDrawable().apply {
                        setColor(colorKeyBg)
                        cornerRadius = cornerPx
                    }
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
        val edit =
            EditText(context).apply {
                setTextColor(colorKeyText)
                setHintTextColor(colorSub)
                hint = "识别结果，可修改"
                textSize = 16f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = Gravity.TOP or Gravity.START
                includeFontPadding = false
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background =
                    GradientDrawable().apply {
                        setColor(colorBack)
                        cornerRadius = cornerPx
                    }
            }
        card.addView(edit, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // 输入法自己的窗口里调不出系统软键盘，所以给最小编辑能力：
        // 删字 / 清空 / 左右挪光标。要整段重写，就上屏后再用正常键盘改。
        val editRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun editKey(label: String, action: () -> Unit) {
            editRow.addView(
                button(label, 1f) { action() },
                LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(2), dp(6), dp(2), 0) },
            )
        }
        editKey("删字") { confirmEdit?.let { deleteCharBeforeCursor(it) } }
        editKey("清空") { confirmEdit?.setText("") }
        editKey("←") { confirmEdit?.let { moveCursor(it, -1) } }
        editKey("→") { confirmEdit?.let { moveCursor(it, 1) } }
        card.addView(editRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val cardActions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        cardActions.addView(
            button("重拍", 1f) { hideConfirm() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(6), dp(2), 0) },
        )
        cardActions.addView(
            button("复制", 1f) { copyToClipboard() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(6), dp(2), 0) },
        )
        cardActions.addView(
            button("上屏", 2f) { commitConfirm() },
            LinearLayout.LayoutParams(0, dp(44), 2f).apply { setMargins(dp(2), dp(6), dp(2), 0) },
        )
        card.addView(cardActions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        this.confirmCard = card
        this.confirmEdit = edit
        root.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        actionsRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        actionsRow.addView(button("返回", 1f) { leaveToKeyboard() })
        actionsRow.addView(button("拍照", 2f) { shutter() })
        actionsRow.addView(button("相册", 1f) { pickGallery() })
        root.addView(
            actionsRow,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = gap },
        )

        return root
    }

    /** 主题完全不可用时的兜底：一块提示 + 返回，不闪退。 */
    private fun plainView(): View {
        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xFF202420.toInt())
            }
        root.addView(
            TextView(context).apply {
                text = "识字面板不可用"
                setTextColor(0xFFF2F0E8.toInt())
                textSize = 14f
            },
        )
        root.addView(
            button("返回") { leaveToKeyboard() },
            LinearLayout.LayoutParams(WRAP_CONTENT, dp(36)).apply { topMargin = dp(10) },
        )
        return root
    }

    private fun button(
        label: String,
        weight: Float = 0f,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background =
                GradientDrawable().apply {
                    setColor(colorKeyBg)
                    setStroke(dp(1), colorSub)
                    cornerRadius = cornerPx
                }
            setOnClickListener { onClick() }
            layoutParams =
                if (weight > 0f) {
                    LinearLayout.LayoutParams(0, MATCH_PARENT, weight).apply { setMargins(dp(2), 0, dp(2), 0) }
                } else {
                    LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { setMargins(dp(2), 0, dp(2), 0) }
                }
        }

    // ------------------------------------------------------------ 确认卡片

    /** 显示确认卡片：预览整块让位，卡片拿走它的 weight。 */
    private fun showConfirm(text: String) {
        val card = confirmCard ?: return
        val edit = confirmEdit ?: return
        edit.setText(text)
        edit.setSelection(edit.text?.length ?: 0)
        card.visibility = View.VISIBLE
        previewWrap?.visibility = View.GONE
        stopCamera()
        HandwritingLog.d("ocr", "confirm shown ${text.length} chars")
    }

    /** 收起卡片，回到取景。 */
    private fun hideConfirm() {
        confirmCard?.visibility = View.GONE
        previewWrap?.visibility = View.VISIBLE
        setMessage("将文字对准中间框")
        startCamera()
    }

    private fun confirmText(): String = confirmEdit?.text?.toString()?.trim().orEmpty()

    private fun deleteCharBeforeCursor(edit: EditText) {
        val value = edit.text ?: return
        val start = maxOf(0, edit.selectionStart)
        val end = maxOf(0, edit.selectionEnd)
        if (start != end) {
            value.delete(minOf(start, end), maxOf(start, end))
        } else if (start > 0) {
            // 按码点删，避免把表情/生僻字切一半。
            val from = value.toString().offsetByCodePoints(start, -1)
            value.delete(from, start)
        }
    }

    private fun moveCursor(edit: EditText, delta: Int) {
        val value = edit.text?.toString().orEmpty()
        val target = (edit.selectionEnd + delta).coerceIn(0, value.length)
        edit.setSelection(target)
    }

    private fun copyToClipboard() {
        val text = confirmText()
        if (text.isEmpty()) {
            toast("没有可复制的内容")
            return
        }
        val ok =
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@runCatching false
                cm.setPrimaryClip(ClipData.newPlainText("ocr", text))
                true
            }.getOrDefault(false)
        toast(if (ok) "已复制到剪贴板" else "复制失败")
        HandwritingLog.d("ocr", "copy ${if (ok) "ok" else "failed"} ${text.length} chars")
    }

    /** 上屏：走 App 现有提交链路 + 记一笔账本，这样 qq/ 也能回退它。 */
    private fun commitConfirm() {
        val text = confirmText()
        if (text.isEmpty()) {
            toast("内容为空")
            return
        }
        runCatching {
            BambooActions.onCommitted(text)
            service.commitText(text)
        }.onFailure { HandwritingLog.w("ocr", "commit failed", it) }
        HandwritingLog.d("ocr", "commit ${text.length} chars, back to keyboard")
        lastResult = text
        hideConfirm()
        leaveToKeyboard()
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    // ---------------------------------------------------------------- 相机

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (started) return
        if (!hasCameraPermission()) {
            previewView.visibility = View.GONE
            permissionBox.visibility = View.VISIBLE
            hintText.text = "需要相机权限"
            return
        }
        previewView.visibility = View.VISIBLE
        permissionBox.visibility = View.GONE
        hintText.text = "将文字对准中间框"
        started = true
        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.INITIALIZED) {
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        }
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider =
                    runCatching { future.get() }.onFailure {
                        Timber.w(it, "ocr: camera provider unavailable")
                        HandwritingLog.w("ocr", "camera provider unavailable", it)
                    }.getOrNull() ?: return@addListener
                cameraProvider = provider
                // 旋转角与预览一致，否则帧是传感器方向，按取景框裁会裁错位置
                val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                val preview =
                    Preview.Builder()
                        .setTargetRotation(targetRotation)
                        .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                // 分辨率与 CyIME 相同：1920x1080 优先取不超过它的档位
                val resolution =
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build()
                val analysisUseCase =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(targetRotation)
                        .setResolutionSelector(resolution)
                        .build()
                analysisUseCase.setAnalyzer(ocrExecutor) { proxy -> handleFrame(proxy) }
                analysis = analysisUseCase
                runCatching {
                    provider.unbindAll()
                    // ViewPort 让「取景框坐标 → 图像坐标」严格对应
                    val group =
                        UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(analysisUseCase)
                            .also { builder -> previewView.viewPort?.let(builder::setViewPort) }
                            .build()
                    camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                    previewView.setOnTouchListener { view, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            runCatching {
                                val point =
                                    previewView.meteringPointFactory
                                        .createPoint(event.x, event.y)
                                camera?.cameraControl?.startFocusAndMetering(
                                    FocusMeteringAction.Builder(point)
                                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                        .build(),
                                )
                                view.performClick()
                            }
                        }
                        true
                    }
                    HandwritingLog.d("ocr", "camera bound rot=" + targetRotation)
                }.onFailure {
                    Timber.w(it, "ocr: bind preview failed")
                    HandwritingLog.w("ocr", "bind preview failed", it)
                    setMessage("相机无法启动，请关闭后重试")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun stopCamera() {
        if (!started) return
        started = false
        analysis?.clearAnalyzer()
        analysis = null
        lastFrame = null
        runCatching { cameraProvider?.unbindAll() }
            .onFailure { Timber.w(it, "ocr: unbind failed") }
        lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        HandwritingLog.d("ocr", "camera released")
    }

    private fun requestCameraPermission() {
        // 输入法本身不能弹权限框，借一个 1×1 的透明 Activity 申请
        runCatching {
            context.startActivity(
                Intent(context, OcrPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            Timber.w(it, "ocr: launch permission activity failed")
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Timber.w(it, "ocr: open app settings failed") }
    }

    // ---------------------------------------------------------------- 动作

    private fun shutter() {
        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }
        val frame = lastFrame
        if (frame == null) {
            HandwritingLog.d("ocr", "shutter: no frame yet ok=" + frameCount + " err=" + frameErrors)
            toast("还没有画面，稍等一下再拍")
            return
        }
        val crop = cropFrame(frame)
        if (crop == null) {
            HandwritingLog.d(
                "ocr",
                "shutter: crop=null view=" + previewView.width + "x" + previewView.height +
                    " frame=" + frame.width + "x" + frame.height,
            )
            toast("取景框还没准备好")
            return
        }
        HandwritingLog.d("ocr", "shutter: frame=" + frame.width + "x" + frame.height + " crop=" + crop.width + "x" + crop.height)
        dumpDebug(frame, "shutter-frame-" + System.currentTimeMillis())
        dumpDebug(crop, "shutter-crop-" + System.currentTimeMillis())
        setMessage("识别中…")
        val appContext = context.applicationContext
        ocrExecutor.execute {
            val ready = OcrRecognizer.ensureReady(appContext)
            if (!ready) HandwritingLog.w("ocr", "shutter: model not ready -> " + OcrRecognizer.lastError.orEmpty())
            val value = if (ready) OcrRecognizer.recognize(crop) else null
            HandwritingLog.d("ocr", "shutter: ready=" + ready + " chars=" + (value?.length ?: -1) + " text=" + value.orEmpty().take(40).replace("\n", "/"))
            val message = if (!ready) OcrRecognizer.lastError else null
            Handler(Looper.getMainLooper()).post { onRecognized(value, message) }
        }
    }

    private fun onRecognized(value: String?, error: String?) {
        val clean = value?.trim().orEmpty()
        if (clean.isEmpty()) {
            setMessage(if (error != null) "识别不可用：" + error else "框内未识别到文字，请对准后重拍")
            lastResult = ""
            return
        }
        lastResult = clean
        HandwritingLog.d("ocr", "recognized ${clean.length} chars")
        // 识别成功后离开相机，换成「键盘 + 上方确认卡片」（卡片可编辑、可重拍）
        val inputView = TrimeInputMethodService.current?.currentInputView
        if (inputView == null) {
            setMessage("识别完成，确认后上屏")
            showConfirm(clean)
        } else {
            inputView.showOcrCard(clean)
        }
    }

    /** 把相机的某一帧转成位图，并按显示方向旋正；随后触发一次实时识别。 */
    private fun handleFrame(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (lastFrame != null && now - lastFrameAt < 250L) return
            val bitmap = frameToBitmap(proxy)
            if (bitmap == null) {
                frameErrors++
                return
            }
            lastFrame = bitmap
            lastFrameAt = now
            frameCount++
            if (frameCount % 20 == 1) {
                HandwritingLog.d(
                    "ocr",
                    "frame #" + frameCount + " " + bitmap.width + "x" + bitmap.height +
                        " errors=" + frameErrors,
                )
            }
            scheduleLive()
        } catch (error: Throwable) {
            frameErrors++
            Timber.w(error, "ocr: read frame failed")
        } finally {
            runCatching { proxy.close() }
        }
    }

    /** 每 700ms 跑一次实时识别（与 CyIME 的节奏一致）。 */
    private fun scheduleLive() {
        if (scanned) return
        val now = System.currentTimeMillis()
        if (liveRunning || now - lastLiveAt < 700L) return
        val frame = lastFrame ?: return
        liveRunning = true
        lastLiveAt = now
        val appContext = context.applicationContext
        ocrExecutor.execute {
            try {
                val crop = cropFrame(frame)
                if (crop == null) {
                    HandwritingLog.d(
                        "ocr",
                        "live: crop=null view=" + previewView.width + "x" + previewView.height +
                            " frame=" + frame.width + "x" + frame.height,
                    )
                    return@execute
                }
                val quality = OcrFrameQuality.assess(crop)
                if (quality != OcrFrameQuality.Verdict.READY) {
                    HandwritingLog.d(
                        "ocr",
                        "live: crop=" + crop.width + "x" + crop.height + " quality=" + quality,
                    )
                    Handler(Looper.getMainLooper()).post { showQualityVerdict(quality) }
                    return@execute
                }
                val ready = OcrRecognizer.ensureReady(appContext)
                if (!ready) {
                    HandwritingLog.w(
                        "ocr",
                        "live: model not ready -> " + OcrRecognizer.lastError.orEmpty(),
                    )
                }
                val value = if (ready) OcrRecognizer.recognize(crop) else null
                HandwritingLog.d(
                    "ocr",
                    "live: crop=" + crop.width + "x" + crop.height + " q=READY ready=" + ready +
                        " chars=" + (value?.length ?: -1) +
                        " text=" + value.orEmpty().take(24).replace("\n", "/"),
                )
                Handler(Looper.getMainLooper()).post { showLive(value.orEmpty()) }
            } catch (error: Throwable) {
                HandwritingLog.w("ocr", "live: recognize threw", error)
                Timber.w(error, "ocr: live recognize failed")
            } finally {
                liveRunning = false
            }
        }
    }

    /**
     * 提示行状态机，顺序与 CyIME 一致：
     * 质量不合格（太暗 / 没细节）→ 结果为空 → 结果还没稳定 → 显示结果。
     */
    private fun showQualityVerdict(verdict: OcrFrameQuality.Verdict) {
        if (scanned) return
        stability.reset()
        setMessage(
            if (verdict == OcrFrameQuality.Verdict.DARK) "光线不足，请对准有光的文字" else "将文字对准中间框",
        )
    }

    /** 实时结果：连续两帧一致才显示；认不出就提示，画面一变自动退回提示语。 */
    private fun showLive(text: String) {
        if (scanned) return
        val stable = stability.update(text)
        when {
            text.isBlank() -> {
                setMessage("框内未识别到清晰文字")
            }
            stable.isEmpty() -> {
                setMessage("将文字对准中间框")
            }
            else -> {
                setMessage(stable)
            }
        }
    }

    /** 只在内容真的变了的时候改文字，避免每 700ms 触发一次重绘。 */
    private fun setMessage(message: String) {
        if (hintText.text?.toString() != message) hintText.text = message
    }

    /** 用 CameraX 自带的转换（YUV→位图它自己处理），再按 cropRect + 旋转角摆正。 */
    private fun frameToBitmap(proxy: ImageProxy): Bitmap? {
        val raw = runCatching { proxy.toBitmap() }.getOrNull() ?: return null
        val rotation = proxy.imageInfo.rotationDegrees
        val rect = proxy.cropRect
        val cropOk =
            rect.width() in 1..raw.width && rect.height() in 1..raw.height &&
                (rect.width() != raw.width || rect.height() != raw.height)
        if (rotation == 0 && !cropOk) return raw
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val upright =
            if (cropOk) {
                runCatching {
                    Bitmap.createBitmap(raw, rect.left, rect.top, rect.width(), rect.height(), matrix, true)
                }.getOrNull()
            } else {
                runCatching { Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true) }.getOrNull()
            }
        if (upright == null || upright === raw) return raw
        raw.recycle()
        return upright
    }

    /**
     * 按取景框（相对预览视图的归一化坐标）从帧里裁一块。
     * 预览用 FILL_CENTER，所以要先按 max 比例缩放着色再反算偏移。
     */
    private fun cropFrame(source: Bitmap): Bitmap? {
        val viewWidth = previewView.width
        val viewHeight = previewView.height
        if (viewWidth <= 0 || viewHeight <= 0) return null
        if (source.width <= 0 || source.height <= 0) return null
        val scale = maxOf(viewWidth.toFloat() / source.width, viewHeight.toFloat() / source.height)
        val offsetX = (source.width * scale - viewWidth) / 2f
        val offsetY = (source.height * scale - viewHeight) / 2f
        fun mapX(value: Float): Int =
            ((value + offsetX) / scale).toInt().coerceIn(0, source.width - 1)
        fun mapY(value: Float): Int =
            ((value + offsetY) / scale).toInt().coerceIn(0, source.height - 1)
        val left = mapX(viewWidth * FRAME_LEFT)
        val top = mapY(viewHeight * FRAME_TOP)
        val right = mapX(viewWidth * FRAME_RIGHT)
        val bottom = mapY(viewHeight * FRAME_BOTTOM)
        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth < 16 || cropHeight < 16) return null
        return runCatching { Bitmap.createBitmap(source, left, top, cropWidth, cropHeight) }
            .getOrNull()
    }

    private fun pickGallery() {
        toast("相册识别正在接入")
    }

    /**
     * 把图存到 `<Documents/BambooIME[Debug]>/ocr-debug/`，只用于 debug 排查。
     * 用户可以直接从文件管理取出来，我们据此判断「图本身没字」还是「引擎认不出」。
     */
    private fun dumpDebug(bitmap: Bitmap, name: String) {
        if (!com.osfans.trime.BuildConfig.DEBUG) return
        runCatching {
            val dir = File(DataManager.defaultDataDir, "ocr-debug")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name + ".png")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            HandwritingLog.d("ocr", "dump -> " + file.absolutePath)
        }.onFailure { HandwritingLog.w("ocr", "dump failed", it) }
    }

    private fun leaveToKeyboard() {
        stopCamera()
        val onScreen = rootView?.isAttachedToWindow == true
        if (!windowManager.isAttached(this) && !onScreen) {
            HandwritingLog.d("ocr", "leave ignored (already on keyboard)")
            return
        }
        runCatching {
            // 用 Key 重载：拿到的永远是「当前这套依赖」里缓存的键盘窗口
            windowManager.attachWindow(KeyboardWindow)
        }.onFailure {
            Timber.w(it, "ocr: leave failed")
            HandwritingLog.w("ocr", "leave failed", it)
        }
        // 兜底：管理器 early-return 或视图脱节时，直接从父容器摘掉
        val v = rootView
        if (v != null && v.isAttachedToWindow) {
            runCatching { (v.parent as? android.view.ViewGroup)?.removeView(v) }
        }
    }

    private fun toast(text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    /** 取景框遮罩：框外压暗，框边描一条线。 */
    private class ScanMaskView(context: Context) : View(context) {
        var maskColor: Int = 0xB3000000.toInt()
        var borderColor: Int = Color.WHITE
        var accentColor: Int = Color.GREEN
        var strokePx: Float = 2f

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            rect.set(w * FRAME_LEFT, h * FRAME_TOP, w * FRAME_RIGHT, h * FRAME_BOTTOM)

            paint.color = maskColor
            canvas.drawRect(0f, 0f, w, rect.top, paint)
            canvas.drawRect(0f, rect.bottom, w, h, paint)
            canvas.drawRect(0f, rect.top, rect.left, rect.bottom, paint)
            canvas.drawRect(rect.right, rect.top, w, rect.bottom, paint)

            paint.color = borderColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokePx
            canvas.drawRect(rect, paint)

            // 四角重点标记
            val len = minOf(rect.width(), rect.height()) * 0.12f
            paint.color = accentColor
            paint.strokeWidth = strokePx * 2.5f
            canvas.drawLine(rect.left, rect.top, rect.left + len, rect.top, paint)
            canvas.drawLine(rect.left, rect.top, rect.left, rect.top + len, paint)
            canvas.drawLine(rect.right, rect.top, rect.right - len, rect.top, paint)
            canvas.drawLine(rect.right, rect.top, rect.right, rect.top + len, paint)
            canvas.drawLine(rect.left, rect.bottom, rect.left + len, rect.bottom, paint)
            canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - len, paint)
            canvas.drawLine(rect.right, rect.bottom, rect.right - len, rect.bottom, paint)
            canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - len, paint)

            paint.style = Paint.Style.FILL
        }
    }
}
