/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.ocr

import android.Manifest
import com.osfans.trime.util.BambooLog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
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
import android.view.ScaleGestureDetector
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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.osfans.trime.actions.bamboo.BambooActions
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.util.forceShowSelf
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.osfans.trime.util.memory.IdleReclaimer
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
class OcrWindow : BoardWindow.BarBoardWindow() {
    // 要顶栏（← 返回键盘）；没有这层时主题那颗齿轮会留在原位，用户会以为返回不了。
    override val title: String = "拍照识字"
    private val service: TrimeInputMethodService
        get() = TrimeInputMethodService.current
            ?: error("ocr: IME service is not available")

    private val windowManager: BoardWindowManager
        get() = BoardWindowManager.current
            ?: error("ocr: board window manager is not available")

    private lateinit var previewView: PreviewView
    private lateinit var maskView: ScanMaskView

    /** true = 单字模式（居中方格取景，只认一个字）。 */
    private var singleCharMode = false
    private var modeButton: TextView? = null
    private lateinit var hintText: TextView
    private lateinit var permissionBox: LinearLayout
    private lateinit var actionsRow: LinearLayout

    private var rootView: View? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var started = false

    /** 实时取帧的用例；只在相机可用时存在。 */
    private var analysis: ImageAnalysis? = null

    private var camera: androidx.camera.core.Camera? = null

    /** 可切换的后置镜头（只有一颗时为空，右上角不显示切换条） */
    private data class LensOption(
        val selector: CameraSelector,
        val label: String,
        val isMain: Boolean,
        /** 相对主摄的光学倍率（0.6 / 1.0 / 3.0…）；参数拿不到时为 0f */
        val ratio: Float,
    )

    private var lensOptions: List<LensOption> = emptyList()
    private var lensIndex = 0
    private var lensRow: LinearLayout? = null
    private var boundGroup: UseCaseGroup? = null

    /** 「画面有点糊，换个镜头」每次进入只提示一次 */
    private var lensHintShown = false

    /** 用户想要的总倍率（相对主摄）；由捏合手势和镜头胶囊共同决定 */
    private var desiredZoom = 1f
    private var scaleDetector: ScaleGestureDetector? = null
    private var zoomBadge: TextView? = null

    /**
     * true = 只绑「逻辑相机」，缩放完全交给 HAL 换镜头（大多数手机的默认路径，切换不闪）；
     * false = 逐颗绑物理镜头（回退路径，画质确定但切换会闪一下）。
     */
    private var zoomPresetMode = false
    private var zoomPresets: List<Float> = emptyList()

    /** 已创建的档位胶囊；捏合时只改高亮，不再重复 addView */
    private val presetChips = mutableListOf<TextView>()
    private var zoomMin = 1f
    private var zoomMax = 1f

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

    // 相册识字：选中图片后顶上相机预览的位置（取景框与手势都在 OcrGalleryView 里）
    /** 取景区容器（相机预览 + 相册画布 + 提示浮层）。 */
    private var stageRef: FrameLayout? = null

    private var galleryWrap: FrameLayout? = null
    private var galleryView: OcrGalleryView? = null
    private var galleryMode = false
    private var shootButton: TextView? = null
    private var albumButton: TextView? = null

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

        /** 拍照面板当前是否挂上；空闲释放器据此判断能否释放识别模型。 */
        @Volatile
        var isPanelVisible: Boolean = false

        /**
         * 把外部传进来的一张图直接送进「相册识图」界面（截图模块的「识字」用）。
         *
         * 成功返回 true，调用方可以关掉自己；输入法窗口还没建起来时返回 false，调用方自行退路。
         */
        fun openGalleryWith(bitmap: Bitmap, fromShotSource: Boolean = false): Boolean {
            val manager = BoardWindowManager.current ?: return false
            fromShot = fromShotSource
            galleryBitmap = bitmap
            galleryActive = true
            galleryRestorePending = false
            galleryMatrix = null
            gallerySingle = false
            return runCatching { manager.attachWindow(instance) }.isSuccess
        }

        /** 主题里用的固定动作名。 */
        /** 相册图片解码上限（长边像素）：再大对模型没帮助，只会吃内存。 */
        private const val MAX_GALLERY_SIDE = 2000

        /**
         * 相册选中的那张图。
         *
         * 从系统相册选图时输入法会被收起，我们的窗口随之 detach、回来时视图是新建的；
         * 图放在单例这里才不会丢（视图里存不住）。
         */
        private var galleryBitmap: Bitmap? = null

        /** detach 时置上，重新挂载时据此把相册模式接回来。 */
        private var galleryRestorePending = false

        /**
         * 相册模式是否还没被用户主动退出（「返回」/「收起」才算退出）。
         *
         * 与 [galleryRestorePending] 的分工：pending 只在「窗口被系统收起」那一瞬间为真，
         * 恢复后立刻清掉；这个标记一直挂着，所以输入法窗口每次重建（选图回来、切 App 回来）
         * 都还能把「拍」面板接回来，而不是掉回字母键盘。
         */
        @Volatile
        private var galleryActive = false

        /** 被顶掉时记下照片的缩放/旋转/平移，重拍回来照着还原。 */
        private var galleryMatrix: FloatArray? = null

        /** 同上，记下单字/整段模式。 */
        private var gallerySingle = false

        /**
         * 这次识字会话是否还开着（相机、相册、卡片三种状态都算）。
         *
         * 只有面板「返回」和卡片「收起」把它清掉；切 App、换输入框、被系统收起都要保持，
         * `InputView.startInput()` 据此把面板接回来，和手写板的 active 一个思路。
         */
        @Volatile
        private var ocrSessionActive = false

        /** 面板被挂上（点「拍」开面板、卡片「返回」回来都走这里）：会话开始。 */
        private fun markSessionActive() {
            ocrSessionActive = true
        }

        /**
         * `InputView.startInput()` 据此把本窗口接回来，而不是无脑切回字母键盘
         * （否则用户得再点一次「拍」，图还在但面板没了）。
         */
        fun shouldRestore(): Boolean = ocrSessionActive

    /**
     * 一次性标记：卡片「返回」触发的重新挂载。
     *
     * 为真时 [onAttached] 不再把取景模式压回「整段」，而是保留刚才的整段/单字；
     * 其它进入方式（键盘点「拍」、系统重新显示）保持原来的复位行为。
     */
    @Volatile
    private var returnToFrame = false

    /**
     * 这次识字是不是从「截图」模块送进来的。
     *
     * 为真时面板的「返回」「收起」直接结束识字、回键盘，而不是退回「拍照识字」取景——
     * 因为用户是从截图界面跳过来的，没打算继续拍照。
     */
    @Volatile
    private var fromShot = false

    /** 由 [OcrCard.retake] 调用：下一个 onAttached 属于「卡片返回」。 */
    fun markReturnToFrame() {
        returnToFrame = true
    }

        const val TOKEN = "BambooOcr"

        fun isToken(text: String): Boolean = text.equals(TOKEN, ignoreCase = true)

        /** 取景框的归一化位置：横向几乎占满，纵向取中间一条。 */
        private const val FRAME_LEFT = 0.05f
        private const val FRAME_TOP = 0.32f
        private const val FRAME_RIGHT = 0.95f
        private const val FRAME_BOTTOM = 0.68f

        /** 单字模式：方格边长占预览高度的比例（设置页可调，这里只是默认值）。 */
        const val SINGLE_FRAME_RATIO = 0.34f

        /** 数字变焦上限：超过这个倍率画面已经没意义了。 */
        const val MAX_ZOOM = 8f
    }

    override fun onCreateView(): View {
        BambooLog.ocr.d("ocr", "onCreateView")
        val created = try {
            buildView()
        } catch (t: Throwable) {
            Timber.e(t, "ocr: buildView failed")
            BambooLog.ocr.w("ocr", "buildView threw", t)
            plainView()
        }
        rootView = created
        return created
    }

    override fun onAttached() {
        BambooLog.ocr.d("ocr", "onAttached")
        isPanelVisible = true
        IdleReclaimer.touch("ocr")
        // 只要面板被挂上就算会话开着：切 App、换输入框回来后靠这个把面板接回来
        markSessionActive()
        // 面板接管后卡片就不再是「当前状态」：清掉留底，避免切 App 时旧卡片又冒出来
        runCatching { OcrCard.clearPending() }
            .onFailure { Timber.w(it, "ocr: clear card pending failed") }
        // 卡片「返回」：保留刚才的取景模式（整段/单字），只把界面同步回来；
        // 其它进入方式（键盘点「拍」、系统重新显示）一律复位成「整段」。
        if (returnToFrame) {
            returnToFrame = false
            syncFrameModeUi()
            BambooLog.ocr.d("ocr", "onAttached keep frame single=" + singleCharMode)
        } else {
            resetFrameMode()
        }
        // 只在这个窗口挂载时隐藏顶栏，其它窗口完全不受影响
        runCatching { service.currentInputView?.setInputBarSuppressed(true) }
            .onFailure { Timber.w(it, "ocr: hide input bar failed") }
        // 选过图且用户还没主动退出：先把那张图接回来，不要重开相机。
        // 不能只看 galleryRestorePending——它是一次性的，第二次切回来就失效了。
        val pending = if (galleryActive) galleryBitmap else null
        if (pending != null) {
            galleryRestorePending = false
            // 单字/整段也跟着回来，别让用户再切一次
            singleCharMode = gallerySingle
            modeButton?.text = if (singleCharMode) "整段" else "单字"
            maskView.single = singleCharMode
            maskView.invalidate()
            BambooLog.ocr.d("ocr", "restore gallery " + pending.width + "x" + pending.height)
            ocrCardHostRestore(pending)
        } else {
            // 每次挂上都重新起相机：切走再回来要能继续看画面
            startCamera()
        }
    }

    override fun onDetached() {
        BambooLog.ocr.d("ocr", "onDetached")
        isPanelVisible = false
        runCatching { service.currentInputView?.setInputBarSuppressed(false) }
            .onFailure { Timber.w(it, "ocr: restore input bar failed") }
        stability.reset()
        scanned = false
        stopCamera()
        // 从相册选图时输入法会被收起，窗口 detach；此时要保住那张图，
        // 等重新挂载时恢复。用户主动退出才真正清掉。
        if (galleryMode && galleryBitmap != null) {
            galleryRestorePending = true
            galleryMatrix = galleryView?.snapshotMatrix()
            gallerySingle = singleCharMode
            galleryMode = false
        } else {
            exitGalleryMode(restartCamera = false)
        }
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
        val textRaw = color("key_text_color", 0xFFF2F0E8.toInt())
        val subRaw = color("key_symbol_color", 0xFF8A8A80.toInt())
        colorAccent = color("hilited_key_back_color", 0xFF6FAE8F.toInt())
        // 文字色按各自底色做可读性兜底：面板底色、按键底色、提示行底色
        colorKeyText = ThemeContrast.readableOn(colorKeyBg, textRaw)
        colorSub = ThemeContrast.readableOn(colorBack, subRaw, ThemeContrast.MIN_SUB)
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

        // 识别结果浮在取景画面上（和 CyIME 一样：有结果就顶掉提示语）。
        // 它不进竖向布局流，否则文字从 1 行变 3 行会把下面的取景区压矮、实时结果一变就抖。
        hintText =
            TextView(context).apply {
                text = "将文字对准中间框"
                textSize = 15f
                val hintAlphaValue = OcrPrefs.hintAlpha
                val hintBackground = Color.argb(hintAlphaValue * 255 / 100, 0, 0, 0)
                setTextColor(
                    ThemeContrast.readableOnAlpha(
                        0xFF000000.toInt(),
                        hintAlphaValue,
                        colorSub,
                        ThemeContrast.MIN_SUB,
                    ),
                )
                // 底色几乎透明时靠描边阴影保底，否则亮照片上会看不见
                if (hintAlphaValue < 60) {
                    setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
                }
                gravity = Gravity.CENTER
                includeFontPadding = false
                setLineSpacing(dp(2).toFloat(), 1f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), dp(4), dp(8), dp(4))
                // 半透明底：压在明亮照片上也要看得清（不透明度在设置里调）
                background =
                    GradientDrawable().apply {
                        setColor(hintBackground)
                        cornerRadius = dp(8).toFloat()
                    }
                // 触摸要穿下去，否则画布上的拖动/缩放会被这层吞掉
                isClickable = false
                isFocusable = false
            }

        // 取景区：相机预览与相册画布同一个位置、同样大小，谁可见切谁；文字浮在它们上面
        val stage = FrameLayout(context)

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

        // 右上角镜头切换：只有多颗后置镜头时才显示，不占底部按钮行
        val lenses =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                setPadding(dp(3), dp(3), dp(3), dp(3))
                background =
                    GradientDrawable().apply {
                        setColor(0x99000000.toInt())
                        cornerRadius = dp(12).toFloat()
                    }
            }
        previewWrap.addView(
            lenses,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(dp(6), dp(6), dp(6), dp(6))
            },
        )
        lensRow = lenses

        // 左上角倍率徽标：只在不是 1× 时出现
        val badge =
            TextView(context).apply {
                visibility = View.GONE
                textSize = 12f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(0xFFF2F0E8.toInt())
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background =
                    GradientDrawable().apply {
                        setColor(0x99000000.toInt())
                        cornerRadius = dp(10).toFloat()
                    }
            }
        previewWrap.addView(
            badge,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(6), dp(6), dp(6), dp(6))
            },
        )
        zoomBadge = badge

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
        stage.addView(previewWrap, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 相册识字：和相机预览同一个位置、同样大小，切过去时只换可见性
        val gallery =
            FrameLayout(context).apply {
                visibility = View.GONE
                background =
                    GradientDrawable().apply {
                        setColor(colorKeyBg)
                        cornerRadius = cornerPx
                    }
                clipToOutline = true
            }
        val galleryCanvas =
            OcrGalleryView(context).apply {
                maskColor = 0xB3000000.toInt()
                borderColor = 0xFFE8E4D8.toInt()
                accentColor = colorAccent
                strokePx = dp(2).toFloat()
            }
        gallery.addView(galleryCanvas, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // 双指旋转不好使时的兜底
        val rotateButton =
            TextView(context).apply {
                text = "转 90°"
                textSize = 12f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(0xFFF2F0E8.toInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background =
                    GradientDrawable().apply {
                        setColor(0x99000000.toInt())
                        cornerRadius = dp(10).toFloat()
                    }
                setOnClickListener { galleryCanvas.rotateBy(90f) }
            }
        gallery.addView(
            rotateButton,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                // 放左上角：右上角留给相机倍数胶囊，提示文字才能左右对称居中
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(6), dp(6), dp(6), dp(6))
            },
        )
        this.galleryWrap = gallery
        this.galleryView = galleryCanvas
        stage.addView(gallery, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 文字浮层压在画面上：宽度跟着内容走、水平居中（半透明底就是文字的「框」），
        // 纵向位置由 updateHintPosition 贴到取景框下面
        stage.addView(
            hintText,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setMargins(dp(4), dp(4), dp(4), 0)
            },
        )
        this.stageRef = stage
        maskView.topReservedPx = OcrFrame.topReserved(density)
        // 取景框大小/位置或提示文字行数一变，就要把提示浮层重新贴到框下面
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateHintPosition() }
        root.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

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
            button("返回", 1f) { hideConfirm() },
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
        actionsRow.addView(button("返回", 1f) { onPanelBackPressed() })
        actionsRow.addView(
            button("单字", 1f) { toggleSingleCharMode() }.also { modeButton = it },
        )
        val shoot = button("拍照", 2f) { shutter() }
        shootButton = shoot
        actionsRow.addView(shoot)
        val album = button("相册", 1f) { pickGallery() }
        albumButton = album
        actionsRow.addView(album)
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
        BambooLog.ocr.d("ocr", "confirm shown ${text.length} chars")
    }

    /** 收起卡片，回到取景。 */
    private fun hideConfirm() {
        confirmCard?.visibility = View.GONE
        previewWrap?.visibility = View.VISIBLE
        setMessage(alignHint())
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
        BambooLog.ocr.d("ocr", "copy ${if (ok) "ok" else "failed"} ${text.length} chars")
    }

    /** 上屏：走 App 现有提交链路 + 记一笔账本，这样 qq/ 也能回退它。 */
    private fun commitConfirm() {
        val text = confirmText()
        if (text.isEmpty()) {
            toast("内容为空")
            return
        }
        runCatching {
            service.commitText(text)
        }.onFailure { BambooLog.ocr.w("ocr", "commit failed", it) }
        BambooLog.ocr.d("ocr", "commit ${text.length} chars, back to keyboard")
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
        hintText.text = alignHint()
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
                        BambooLog.ocr.w("ocr", "camera provider unavailable", it)
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
                    boundGroup = group
                    logCameraInfos(provider)
                    val selector = planCamera(provider)
                    camera = provider.bindToLifecycle(lifecycleOwner, selector, group)
                    installGestures()
                    previewView.setOnTouchListener { view, event ->
                        val handledByScale = scaleDetector?.onTouchEvent(event) ?: false
                        if (event.action == MotionEvent.ACTION_UP &&
                            !handledByScale &&
                            scaleDetector?.isInProgress != true &&
                            event.pointerCount == 1
                        ) {
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
                    updateLensRow()
                    applyDigitalZoom()
                    updateZoomBadge()
                    BambooLog.ocr.d(
                        "ocr",
                        "camera bound rot=" +
                            targetRotation +
                            " mode=" +
                            (if (zoomPresetMode) "zoom-preset" else "lens-switch") +
                            " lens=" +
                            lensOptions.size +
                            " presets=" +
                            zoomPresets.joinToString(",") { formatRatio(it) },
                    )
                }.onFailure {
                    Timber.w(it, "ocr: bind preview failed")
                    BambooLog.ocr.w("ocr", "bind preview failed", it)
                    setMessage("相机无法启动，请关闭后重试")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    // ------------------------------------------------------------ 相机选路

    /**
     * 决定这次怎么用相机：
     * 1. 默认后置是「逻辑相机」（HAL 自己会换镜头）且变焦范围够宽 → 只绑它，档位胶囊＝倍率档；
     * 2. 否则退回逐颗物理镜头（老机型 / 厂商没做逻辑多摄）。
     */
    private fun planCamera(provider: ProcessCameraProvider): CameraSelector {
        zoomPresetMode = false
        zoomPresets = emptyList()
        lensOptions = emptyList()
        zoomMin = 1f
        zoomMax = 1f

        val logical = defaultBackInfo(provider)?.takeIf { isLogicalCamera(it) }
        if (logical != null) {
            val range = zoomRangeOf(logical)
            if (range != null && range.second > range.first * 1.05f) {
                zoomMin = range.first
                zoomMax = range.second
                zoomPresets = buildZoomPresets(zoomMin, zoomMax)
                zoomPresetMode = true
                desiredZoom = 1f.coerceIn(zoomMin, zoomMax)
                BambooLog.ocr.d(
                    "ocr",
                    "plan: logical camera id=" + cameraIdOf(logical) +
                        " zoom=" + zoomMin + ".." + zoomMax,
                )
                return selectorFor(logical)
            }
        }

        // 回退：逐颗物理镜头
        lensOptions = buildLensOptions(provider)
        lensIndex = lensOptions.indexOfFirst { it.isMain }.coerceAtLeast(0)
        BambooLog.ocr.d("ocr", "plan: lens-switch, lenses=" + lensOptions.size)
        return lensOptions.getOrNull(lensIndex)?.selector ?: CameraSelector.DEFAULT_BACK_CAMERA
    }

    /** 系统给的默认后置相机（通常是逻辑相机）。 */
    private fun defaultBackInfo(provider: ProcessCameraProvider): CameraInfo? =
        runCatching {
            val back = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos)
            back.minByOrNull { cameraIdOf(it).toIntOrNull() ?: Int.MAX_VALUE }
        }.getOrNull()

    private fun isLogicalCamera(info: CameraInfo): Boolean {
        val caps: IntArray =
            runCatching {
                Camera2CameraInfo.from(info)
                    .getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            }.getOrNull() ?: return false
        val wanted = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
        return caps.any { it == wanted }
    }

    /** 变焦范围：[minZoomRatio, maxZoomRatio]；拿不到返回 null。 */
    private fun zoomRangeOf(info: CameraInfo): Pair<Float, Float>? =
        runCatching {
            val range =
                Camera2CameraInfo.from(info)
                    .getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE,
                    )
            if (range == null) {
                null
            } else {
                val min = range.lower.coerceAtLeast(0.1f)
                val max = range.upper
                if (max <= min) null else min to max
            }
        }.getOrNull()

    /** 从变焦范围里挑几个好用的档位：最小倍率 / 1× / 2× / 3.5× / 5× / 10×。 */
    private fun buildZoomPresets(min: Float, max: Float): List<Float> {
        val wanted = listOf(min, 1f, 2f, 3.5f, 5f, 10f)
        val result = mutableListOf<Float>()
        wanted.forEach { value ->
            if (value >= min * 0.999f && value <= max * 1.001f) {
                val rounded = kotlin.math.round(value * 10f) / 10f
                if (result.none { kotlin.math.abs(it - rounded) < 0.05f }) result.add(rounded)
            }
        }
        if (result.isEmpty()) result.add(1f.coerceIn(min, max))
        return result.sorted()
    }

    private fun cameraIdOf(info: CameraInfo): String =
        runCatching { Camera2CameraInfo.from(info).cameraId }.getOrDefault("?")

    private fun physicalIdsOf(info: CameraInfo): String {
        val manager =
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return ""
        return runCatching {
            manager.getCameraCharacteristics(cameraIdOf(info)).physicalCameraIds.joinToString(",")
        }.getOrDefault("")
    }

    /** 一次性的相机清单日志：机型差异全靠它判断。 */
    private fun logCameraInfos(provider: ProcessCameraProvider) {
        runCatching {
            provider.availableCameraInfos.forEach { info ->
                val range = zoomRangeOf(info)
                BambooLog.ocr.d(
                    "ocr",
                    "camera id=" + cameraIdOf(info) +
                        " logical=" + isLogicalCamera(info) +
                        " physicalIds=" + physicalIdsOf(info) +
                        " zoom=" + (range?.let { it.first.toString() + ".." + it.second } ?: "-") +
                        " focal=" + String.format(java.util.Locale.US, "%.2f", rawFocalLength(info)) +
                        " equiv=" + String.format(java.util.Locale.US, "%.1f", equivalentFocalLength(info)),
                )
            }
        }.onFailure { Timber.w(it, "ocr: log camera infos failed") }
    }

    private fun selectorFor(info: CameraInfo): CameraSelector =
        runCatching {
            CameraSelector.Builder().addCameraFilter { list -> list.filter { it == info } }.build()
        }.getOrDefault(CameraSelector.DEFAULT_BACK_CAMERA)

    // ------------------------------------------------------------ 镜头

    /**
     * 列出可用的后置镜头。
     *
     * 倍率来源优先级：
     * 1. 等效焦距（focal × 43.27 / 传感器对角）→ 相对主摄的比值；
     * 2. 只有焦距没有传感器尺寸时，用「焦距 / 主摄焦距」当相对倍率（比例是对的，只是缺绝对基准）；
     * 3. 都没有才退回「镜头N」，此时不参与自动切镜头。
     *
     * 主摄 = 等效焦距最接近 24mm 的那颗（没有等效焦距时取焦距中位数那颗）。
     * 非主摄且最近对焦距离很近（屈光度 ≥ 12）标「微距」。
     */
    private fun buildLensOptions(provider: ProcessCameraProvider): List<LensOption> {
        val all = runCatching { provider.availableCameraInfos }.getOrNull().orEmpty()
        if (all.size <= 1) return emptyList()
        val back =
            runCatching { CameraSelector.DEFAULT_BACK_CAMERA.filter(all) }
                .getOrDefault(emptyList())
        if (back.size <= 1) return emptyList()

        fun selectorFor(info: CameraInfo): CameraSelector =
            runCatching {
                CameraSelector.Builder().addCameraFilter { list -> list.filter { it == info } }.build()
            }.getOrDefault(CameraSelector.DEFAULT_BACK_CAMERA)

        val equivalents = back.map { equivalentFocalLength(it) }
        val focals = back.map { rawFocalLength(it) }

        // 主摄下标：优先按等效焦距靠 24mm，退而求其次按焦距靠中位数
        val knownEquiv = equivalents.filter { it > 0f }
        val mainIndex =
            if (knownEquiv.isNotEmpty()) {
                val base = knownEquiv.minByOrNull { kotlin.math.abs(it - 24f) } ?: 0f
                equivalents.indexOfFirst { it > 0f && kotlin.math.abs(it - base) < 1f }
                    .coerceAtLeast(0)
            } else {
                val sorted = focals.filter { it > 0f }.sorted()
                if (sorted.isEmpty()) {
                    0
                } else {
                    val median = sorted[sorted.size / 2]
                    focals.indexOfFirst { it > 0f && kotlin.math.abs(it - median) < 0.01f }
                        .coerceAtLeast(0)
                }
            }

        val baseEquiv = equivalents[mainIndex]
        val baseFocal = focals[mainIndex]

        val ratios =
            back.indices.map { index ->
                when {
                    index == mainIndex -> 1f
                    equivalents[index] > 0f && baseEquiv > 0f -> equivalents[index] / baseEquiv
                    focals[index] > 0f && baseFocal > 0f -> focals[index] / baseFocal
                    else -> 0f
                }
            }

        val labels = mutableListOf<String>()
        back.forEachIndexed { index, info ->
            val ratio = ratios[index]
            val zoomPart =
                when {
                    index == mainIndex -> "1×"
                    ratio > 0f -> formatRatio(ratio)
                    else -> "镜头" + (index + 1)
                }
            labels.add(if (isMacroLens(info) && index != mainIndex) "微距" else zoomPart)
        }
        // 撞名就补后缀，绝不整排退化成「镜头N」（那会连带把自动切镜头关掉）
        val seen = mutableMapOf<String, Int>()
        val unique =
            labels.map { label ->
                val count = (seen[label] ?: 0) + 1
                seen[label] = count
                if (count == 1) label else label + "(" + count + ")"
            }

        val options =
            back.mapIndexed { index, info ->
                LensOption(
                    selector = selectorFor(info),
                    label = unique[index],
                    isMain = index == mainIndex,
                    ratio = ratios[index],
                )
            }

        BambooLog.ocr.d(
            "ocr",
            "lenses: " +
                options.joinToString(" | ") { it.label } +
                " main=" +
                options[mainIndex].label +
                " equiv=" +
                equivalents.joinToString(",") { String.format(java.util.Locale.US, "%.1f", it) } +
                " focal=" +
                focals.joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) } +
                " ratio=" +
                ratios.joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) },
        )
        return options
    }

    /** 0.54 → "0.5×"，1.0 → "1×"，3.7 → "3.7×" */
    private fun formatRatio(ratio: Float): String {
        val rounded = kotlin.math.round(ratio * 10f) / 10f
        return if (kotlin.math.abs(rounded - rounded.toInt()) < 0.05f) {
            rounded.toInt().toString() + "×"
        } else {
            String.format(java.util.Locale.US, "%.1f×", rounded)
        }
    }

    private fun equivalentFocalLength(info: CameraInfo): Float =
        runCatching {
            val focal = rawFocalLength(info)
            if (focal <= 0f) return 0f
            val size = Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?: return 0f
            val diagonal = kotlin.math.sqrt(size.width * size.width + size.height * size.height)
            if (diagonal <= 0f) 0f else focal * 43.27f / diagonal
        }.getOrDefault(0f)

    private fun rawFocalLength(info: CameraInfo): Float =
        runCatching {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: 0f
        }.getOrDefault(0f)

    private fun isMacroLens(info: CameraInfo): Boolean {
        // LENS_INFO_MINIMUM_FOCUS_DISTANCE 是单个 Float（屈光度），不是数组
        val diopter =
            runCatching {
                Camera2CameraInfo.from(info)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    ?: 0f
            }.getOrDefault(0f)
        return diopter >= 12f
    }

    private fun updateLensRow() {
        val row = lensRow ?: return
        row.removeAllViews()
        if (zoomPresetMode) {
            updateZoomPresetRow(row)
            return
        }
        if (lensOptions.size <= 1) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        lensOptions.forEachIndexed { index, option ->
            val active = index == lensIndex
            val chip =
                TextView(context).apply {
                    text = option.label
                    textSize = 12f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(if (active) colorBack else 0xFFF2F0E8.toInt())
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background =
                        GradientDrawable().apply {
                            setColor(if (active) colorAccent else 0)
                            cornerRadius = dp(10).toFloat()
                        }
                }
            chip.setOnClickListener { switchLens(index, zoomToOptical = true) }
            row.addView(
                chip,
                LinearLayout.LayoutParams(WRAP_CONTENT, dp(24)).apply { setMargins(dp(2), 0, dp(2), 0) },
            )
        }
    }

    /**
     * 换镜头：重绑 + 清掉上一颗的识别结果，避免旧文字留在提示行。
     * [zoomToOptical] 为 true 表示跳到这颗镜头的光学倍率（点胶囊），
     * 为 false 表示保留当前总倍率、只换底下的镜头（捏合自动切换走这条）。
     */
    private fun switchLens(index: Int, zoomToOptical: Boolean = false) {
        if (index == lensIndex || index !in lensOptions.indices) return
        val provider = cameraProvider ?: return
        val group = boundGroup ?: return
        lensIndex = index
        runCatching {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, lensOptions[index].selector, group)
        }.onFailure {
            Timber.w(it, "ocr: switch lens failed")
            BambooLog.ocr.w("ocr", "switch lens failed", it)
        }
        lastFrame = null
        stability.reset()
        scanned = false
        hintText.text = alignHint()
        updateLensRow()
        val ratio = lensOptions[index].ratio
        if (zoomToOptical && ratio > 0f) desiredZoom = ratio
        applyDigitalZoom()
        updateZoomBadge()
        BambooLog.ocr.d(
            "ocr",
            "lens -> " + lensOptions[index].label + " zoom=" + desiredZoom,
        )
    }

    /** ① 模式的档位胶囊：点一下就是 setZoomRatio，换不换镜头由 HAL 决定。 */
    private fun updateZoomPresetRow(row: LinearLayout) {
        if (zoomPresets.size <= 1) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        // 档位数量变了才重建；捏合过程中只改高亮，避免每帧都 addView（会把胶囊越堆越长）
        if (presetChips.size != zoomPresets.size) {
            row.removeAllViews()
            presetChips.clear()
            zoomPresets.forEach { preset ->
                val chip =
                    TextView(context).apply {
                        text = formatRatio(preset)
                        textSize = 12f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                    }
                chip.setOnClickListener { applyZoom(preset) }
                row.addView(
                    chip,
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        setMargins(dp(2), 0, dp(2), 0)
                    },
                )
                presetChips.add(chip)
            }
        }
        zoomPresets.forEachIndexed { index, preset ->
            val chip = presetChips.getOrNull(index) ?: return@forEachIndexed
            val active = kotlin.math.abs(preset - desiredZoom) < 0.26f
            chip.setTextColor(if (active) colorBack else 0xFFF2F0E8.toInt())
            chip.background =
                GradientDrawable().apply {
                    setColor(if (active) colorAccent else 0)
                    cornerRadius = dp(10).toFloat()
                }
        }
    }

    // ------------------------------------------------------------ 缩放

    private fun installGestures() {
        if (scaleDetector != null) return
        scaleDetector =
            ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val factor = detector.scaleFactor
                        if (factor.isFinite() && factor > 0f) applyZoom(desiredZoom * factor)
                        return true
                    }
                },
            )
    }

    /** 用户捏合到 [target]（相对主摄的总倍率）：先挑镜头，再补数字变焦。 */
    private fun applyZoom(target: Float) {
        if (zoomPresetMode) {
            // ① 模式：只调倍率，换镜头交给 HAL
            val zoom = target.coerceIn(zoomMin, minOf(zoomMax, MAX_ZOOM))
            desiredZoom = zoom
            applyDigitalZoom()
            updateZoomBadge()
            updateZoomPresetRow(lensRow ?: return)
            return
        }
        if (lensOptions.isEmpty()) return
        val ratios = lensOptions.map { it.ratio }.filter { it > 0f }
        val smallest = ratios.minOrNull() ?: 1f
        val zoom = target.coerceIn(smallest * 0.95f, MAX_ZOOM)
        desiredZoom = zoom
        val want = pickLensIndex(zoom)
        if (want != lensIndex) {
            // 换镜头时预览会闪一下，这是唯一代价
            switchLens(want, zoomToOptical = false)
        } else {
            applyDigitalZoom()
            updateZoomBadge()
        }
    }

    /**
     * 光学优先：挑「光学倍率不超过目标倍率（允许 10% 数字缩）且最大」的那颗。
     * 当前镜头如果还够用（倍率不低于最优的 85%），就留在原地，避免来回切。
     */
    private fun pickLensIndex(z: Float): Int {
        if (lensOptions.size <= 1) return 0
        val allow = z * 1.10f
        var best = 0
        lensOptions.forEachIndexed { index, option ->
            val ratio = option.ratio
            if (ratio > 0f && ratio <= allow && ratio > lensOptions[best].ratio) best = index
        }
        val bestRatio = lensOptions[best].ratio
        if (bestRatio <= 0f) return lensIndex
        val currentRatio = lensOptions.getOrNull(lensIndex)?.ratio ?: 0f
        if (currentRatio >= bestRatio * 0.85f && currentRatio <= allow) return lensIndex
        return best
    }

    /** 把「相对主摄的总倍率」换算成这颗镜头自己的数字变焦值。 */
    private fun applyDigitalZoom() {
        val cam = camera ?: return
        if (zoomPresetMode) {
            val state = runCatching { cam.cameraInfo.zoomState.value }.getOrNull()
            val minZoom = state?.minZoomRatio ?: zoomMin
            val maxZoom = state?.maxZoomRatio ?: zoomMax
            runCatching { cam.cameraControl.setZoomRatio(desiredZoom.coerceIn(minZoom, maxZoom)) }
                .onFailure { Timber.w(it, "ocr: set zoom failed") }
            return
        }
        val ratio = lensOptions.getOrNull(lensIndex)?.ratio ?: 0f
        val wanted = if (ratio > 0f) desiredZoom / ratio else desiredZoom
        val state = runCatching { cam.cameraInfo.zoomState.value }.getOrNull()
        val minZoom = state?.minZoomRatio ?: 1f
        val maxZoom = state?.maxZoomRatio ?: 1f
        runCatching { cam.cameraControl.setZoomRatio(wanted.coerceIn(minZoom, maxZoom)) }
            .onFailure { Timber.w(it, "ocr: set zoom failed") }
    }

    private fun updateZoomBadge() {
        val badge = zoomBadge ?: return
        if (zoomPresetMode) {
            if (kotlin.math.abs(desiredZoom - 1f) < 0.05f) {
                badge.visibility = View.GONE
            } else {
                badge.text = formatRatio(desiredZoom)
                badge.visibility = View.VISIBLE
            }
            return
        }
        val option = lensOptions.getOrNull(lensIndex)
        val main = lensOptions.firstOrNull { it.isMain }?.ratio ?: 1f
        val diff = kotlin.math.abs(desiredZoom - main)
        if (option == null || diff < 0.05f) {
            badge.visibility = View.GONE
            return
        }
        val suffix =
            when {
                option.isMain -> ""
                option.label == "微距" -> " 微距"
                option.ratio >= 2f -> " 长焦"
                option.ratio in 0.1f..0.9f -> " 超广"
                else -> ""
            }
        badge.text = String.format(java.util.Locale.US, "%.1f×", desiredZoom) + suffix
        badge.visibility = View.VISIBLE
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
        boundGroup = null
        lensOptions = emptyList()
        zoomPresets = emptyList()
        zoomPresetMode = false
        presetChips.clear()
        updateLensRow()
        BambooLog.ocr.d("ocr", "camera released")
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
        // 相册模式：不碰相机，按取景框从图片里渲染一块出来识别
        if (galleryMode) {
            shutterGallery()
            return
        }
        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }
        val frame = lastFrame
        if (frame == null) {
            BambooLog.ocr.d("ocr", "shutter: no frame yet ok=" + frameCount + " err=" + frameErrors)
            toast("还没有画面，稍等一下再拍")
            return
        }
        val crop = cropFrame(frame)
        if (crop == null) {
            BambooLog.ocr.d(
                "ocr",
                "shutter: crop=null view=" + previewView.width + "x" + previewView.height +
                    " frame=" + frame.width + "x" + frame.height,
            )
            toast("取景框还没准备好")
            return
        }
        BambooLog.ocr.d("ocr", "shutter: frame=" + frame.width + "x" + frame.height + " crop=" + crop.width + "x" + crop.height)
        // 仅在 debug 包里把原图与裁图落盘，release 常量折叠后这段不存在。
        if (com.osfans.trime.util.LogSwitches.OCR_DUMP) {
            dumpDebug(frame, "shutter-frame-" + System.currentTimeMillis())
            dumpDebug(crop, "shutter-crop-" + System.currentTimeMillis())
        }
        setMessage("识别中…")
        val appContext = context.applicationContext
        ocrExecutor.execute {
            val ready = OcrRecognizer.ensureReady(appContext)
            if (!ready) BambooLog.ocr.w("ocr", "shutter: model not ready -> " + OcrRecognizer.lastError.orEmpty())
            val value = if (ready) OcrRecognizer.recognize(crop, singleCharMode) else null
            BambooLog.ocr.d("ocr", "shutter: ready=" + ready + " chars=" + (value?.length ?: -1) + " text=" + value.orEmpty().take(40).replace("\n", "/"))
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
        BambooLog.ocr.d("ocr", "recognized ${clean.length} chars")
        // 识别成功后离开相机，换成「键盘 + 上方确认卡片」（卡片可编辑、可重拍）
        val inputView = TrimeInputMethodService.current?.currentInputView
        BambooLog.ocr.d(
            "ocr",
            "onRecognized len=" + clean.length +
                " inputView=" + (inputView != null) +
                " service=" + (TrimeInputMethodService.current != null),
        )
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
                BambooLog.ocr.d(
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
        // 相册模式没有相机帧，别让残留的实时循环继续跑（白烧 CPU，还会用旧帧）
        if (scanned || galleryMode) return
        val now = System.currentTimeMillis()
        if (!OcrPrefs.liveEnabled) return
        if (liveRunning || now - lastLiveAt < OcrPrefs.liveIntervalMs) return
        val frame = lastFrame ?: return
        liveRunning = true
        lastLiveAt = now
        val appContext = context.applicationContext
        ocrExecutor.execute {
            try {
                val crop = cropFrame(frame)
                if (crop == null) {
                    BambooLog.ocr.d(
                        "ocr",
                        "live: crop=null view=" + previewView.width + "x" + previewView.height +
                            " frame=" + frame.width + "x" + frame.height,
                    )
                    return@execute
                }
                val quality = OcrFrameQuality.assess(crop)
                if (quality != OcrFrameQuality.Verdict.READY) {
                    BambooLog.ocr.d(
                        "ocr",
                        "live: crop=" + crop.width + "x" + crop.height + " quality=" + quality,
                    )
                    Handler(Looper.getMainLooper()).post { showQualityVerdict(quality) }
                    return@execute
                }
                val ready = OcrRecognizer.ensureReady(appContext)
                if (!ready) {
                    BambooLog.ocr.w(
                        "ocr",
                        "live: model not ready -> " + OcrRecognizer.lastError.orEmpty(),
                    )
                }
                val value = if (ready) OcrRecognizer.recognize(crop, singleCharMode) else null
                BambooLog.ocr.d(
                    "ocr",
                    "crop single=" + singleCharMode + " " + crop.width + "x" + crop.height,
                )
                BambooLog.ocr.d(
                    "ocr",
                    "live: crop=" + crop.width + "x" + crop.height + " q=READY ready=" + ready +
                        " chars=" + (value?.length ?: -1) +
                        " text=" + value.orEmpty().take(24).replace("\n", "/"),
                )
                Handler(Looper.getMainLooper()).post { showLive(value.orEmpty()) }
            } catch (error: Throwable) {
                BambooLog.ocr.w("ocr", "live: recognize threw", error)
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
        val base = if (verdict == OcrFrameQuality.Verdict.DARK) "光线不足，请对准有光的文字" else alignHint()
        setMessage(maybeLensHint(verdict) ?: base)
    }

    /** 画面糊且有多颗镜头时，每次进入只提示一次「换个镜头」（不自动切）。 */
    private fun maybeLensHint(verdict: OcrFrameQuality.Verdict): String? {
        if (lensHintShown || lensOptions.size <= 1) return null
        if (verdict != OcrFrameQuality.Verdict.LOW_DETAIL) return null
        lensHintShown = true
        return "画面有点糊，可以点右上角换个镜头"
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
                setMessage(alignHint())
            }
            else -> {
                setMessage(stable)
            }
        }
    }

    /** 只在内容真的变了的时候改文字，避免每 700ms 触发一次重绘。 */
    /** 取景提示语：单字模式提示对准方格。 */
    private fun alignHint(): String = if (singleCharMode) "将单字对准中间方框" else "将文字对准中间框"

    /** 在「整段」与「单字」之间切换取景框。 */
    /** 把取景模式复位成「整段」，并同步遮罩、按钮文字与提示语。 */
    private fun resetFrameMode() {
        singleCharMode = false
        stability.reset()
        lensHintShown = false
        desiredZoom = 1f
        syncFrameModeUi()
        BambooLog.ocr.d("ocr", "resetFrameMode preview=" + previewView.width + "x" + previewView.height)
    }

    /** 只把界面同步到当前 [singleCharMode]，不改模式本身（卡片返回时用）。 */
    private fun syncFrameModeUi() {
        modeButton?.text = if (singleCharMode) "整段" else "单字"
        maskView.single = singleCharMode
        maskView.invalidate()
        galleryView?.setSingleMode(singleCharMode)
        hintText.text = alignHint()
        updateHintPosition()
    }

    private fun toggleSingleCharMode() {
        singleCharMode = !singleCharMode
        BambooLog.ocr.d(
            "ocr",
            "toggle single=" + singleCharMode +
                " preview=" + previewView.width + "x" + previewView.height,
        )
        modeButton?.text = if (singleCharMode) "整段" else "单字"
        maskView.single = singleCharMode
        maskView.invalidate()
        updateHintPosition()
        galleryView?.let {
            it.singleFrameRatio = OcrPrefs.singleFrameRatio
            it.setSingleMode(singleCharMode)
        }
        stability.reset()
        setMessage(alignHint())
        BambooLog.ocr.d("ocr", "mode single=" + singleCharMode)
    }

    private fun setMessage(message: String) {
        if (hintText.text?.toString() != message) {
            hintText.text = message
            // 行数变了要重新贴位置（浮层贴在取景框下面，不参与布局流）
            hintText.post { updateHintPosition() }
        }
    }

    /**
     * 提示/结果文字浮在取景框**下方**（框上面是倍数胶囊和「转 90°」按钮的地盘）。
     * 下方放不下时退到框内侧底部，不会跑到按钮行里去。
     */
    private fun updateHintPosition() {
        val stage = stageRef ?: return
        val hint = hintText
        val w = stage.width
        val h = stage.height
        if (w <= 0 || h <= 0) return
        if (hint.height <= 0) return
        val reserved = OcrFrame.topReserved(density)
        val frame =
            if (galleryMode) {
                galleryView?.frameRect() ?: OcrFrame.rect(singleCharMode, w.toFloat(), h.toFloat(), reserved)
            } else {
                OcrFrame.rect(singleCharMode, w.toFloat(), h.toFloat(), reserved)
            }
        val gap = dp(8)
        val margin = dp(4)
        hint.maxWidth = (w - margin * 2).coerceAtLeast(1)
        val widthSpec =
            View.MeasureSpec.makeMeasureSpec(
                (w - margin * 2).coerceAtLeast(1),
                View.MeasureSpec.AT_MOST,
            )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        // 框下面剩多少高度就能用多少；放不下就逐级缩小字号（15sp → 10sp）
        val available = (h - margin - (frame.bottom.toInt() + gap)).coerceAtLeast(1)
        var size = 15
        while (size > 10) {
            hint.textSize = size.toFloat()
            hint.measure(widthSpec, heightSpec)
            if (hint.measuredHeight <= available) break
            size--
        }
        if (size == 10) hint.textSize = 10f
        val lp = hint.layoutParams as? FrameLayout.LayoutParams ?: return
        val top =
            (frame.bottom.toInt() + gap)
                .coerceIn(0, maxOf(0, h - hint.measuredHeight - margin))
        if (lp.topMargin != top) {
            lp.topMargin = top
            hint.layoutParams = lp
        }
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
    /** 当前模式下的取景框：整段=横条（长宽/上下位置可调），单字=居中方格。 */
    private fun frameRect(single: Boolean, width: Float, height: Float): RectF =
        OcrFrame.rect(single, width, height, OcrFrame.topReserved(density))

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
        val frame = frameRect(singleCharMode, viewWidth.toFloat(), viewHeight.toFloat())
        val left = mapX(frame.left)
        val top = mapY(frame.top)
        val right = mapX(frame.right)
        val bottom = mapY(frame.bottom)
        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth < 16 || cropHeight < 16) return null
        return runCatching { Bitmap.createBitmap(source, left, top, cropWidth, cropHeight) }
            .getOrNull()
    }

    /**
     * 从系统相册选一张照片。走系统照片选择器，不申请任何权限：
     * Android 13 起用 ACTION_PICK_IMAGES，更老的用 ACTION_OPEN_DOCUMENT。
     * 输入法（Service）自己拿不到 Activity 回调，所以借 OcrPermissionActivity 这个透明宿主。
     */
    private fun pickGallery() {
        OcrPermissionActivity.pickCallback = { uri -> onGalleryPicked(uri) }
        runCatching {
            context.startActivity(
                Intent(context, OcrPermissionActivity::class.java)
                    .setAction(OcrPermissionActivity.ACTION_PICK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            Timber.w(it, "ocr: launch picker failed")
            OcrPermissionActivity.pickCallback = null
            toast("打不开相册")
        }
    }

    /** 选好图回到面板：解码 → 进对齐界面（图片原样进来，朝向由用户自己调）。 */
    private fun onGalleryPicked(uri: Uri) {
        OcrPermissionActivity.pickCallback = null
        val bitmap = runCatching { decodeSampled(uri, MAX_GALLERY_SIDE) }.getOrNull()
        if (bitmap == null) {
            toast("这张图片读不出来，换一张试试")
            return
        }
        BambooLog.ocr.d("ocr", "gallery picked " + bitmap.width + "x" + bitmap.height)
        // 新选的一张图：旧的缩放/旋转对不上，清掉重新居中
        galleryMatrix = null
        gallerySingle = false
        showGallery(bitmap)
        // 系统相册会把输入法收起。相册 Activity 退出与系统收起输入法之间有竞态（日志里出现过
        // 「要回窗口」7ms 后又 onDetached），所以分几档延迟重试，直到窗口真的回来为止。
        val handler = Handler(Looper.getMainLooper())
        for (delay in longArrayOf(300L, 900L, 1800L, 3000L)) {
            handler.postDelayed(
                {
                    val shown = runCatching { service.isInputViewShown }.getOrDefault(true)
                    if (!shown) {
                        runCatching { service.forceShowSelf() }
                            .onFailure { BambooLog.ocr.w("ocr", "forceShowSelf failed", it) }
                        BambooLog.ocr.d("ocr", "gallery: asked IME to show again after " + delay + "ms")
                    }
                },
                delay,
            )
        }
    }

    /** 按长边上限采样解码，避免一张图吃掉几十 MB。 */
    private fun decodeSampled(uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= maxSide) {
            sample *= 2
            longest /= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** 重新挂载后把相册模式接回来：视图刚建好尺寸还是 0，等一次布局再填图。 */
    private fun ocrCardHostRestore(bitmap: Bitmap) {
        val host = rootView ?: galleryWrap
        if (host == null) {
            BambooLog.ocr.w("ocr", "restore: no host view")
            return
        }
        host.post { showGallery(bitmap, ensureAttach = false, restoreTransform = true) }
    }

    /**
     * 选完图回来时，输入法可能已经被系统重新挂过（面板被键盘顶掉）。
     * 这里先把窗口挂回去，再填图，避免把图塞进一个已经 detach 的视图里。
     */
    private fun ensureAttached() {
        val manager = BoardWindowManager.current ?: return
        if (manager.isAttached(this)) return
        runCatching { manager.attachWindow(this) }
            .onFailure { Timber.w(it, "ocr: reattach after pick failed") }
        BambooLog.ocr.d("ocr", "gallery: reattached")
    }

    private fun showGallery(bitmap: Bitmap, ensureAttach: Boolean = true, restoreTransform: Boolean = false) {
        if (ensureAttach) ensureAttached()
        val wrap = galleryWrap
        if (wrap == null) {
            BambooLog.ocr.w("ocr", "gallery: no container, view not built yet")
            toast("面板还没准备好，再点一次相册")
            return
        }
        BambooLog.ocr.d("ocr", "gallery: container=" + wrap.width + "x" + wrap.height)
        galleryMode = true
        galleryActive = true
        galleryBitmap = bitmap
        galleryRestorePending = false
        stopCamera()
        BambooLog.ocr.d("ocr", "gallery: camera stopped")
        previewWrap?.visibility = View.GONE
        wrap.visibility = View.VISIBLE
        BambooLog.ocr.d("ocr", "gallery: visible")
        val canvas = galleryView
        if (canvas == null) {
            BambooLog.ocr.w("ocr", "gallery: no canvas")
            return
        }
        canvas.singleFrameRatio = OcrPrefs.singleFrameRatio
        canvas.setBitmap(bitmap)
        if (restoreTransform) canvas.restoreMatrix(galleryMatrix)
        canvas.setSingleMode(singleCharMode)
        BambooLog.ocr.d("ocr", "gallery: bitmap " + bitmap.width + "x" + bitmap.height)
        shootButton?.text = "识别"
        albumButton?.text = "换一张"
        setMessage(alignHint())
        galleryWrap?.requestLayout()
        galleryWrap?.post { logGalleryLayout("post") }
        galleryWrap?.postDelayed({ logGalleryLayout("post+500") }, 500L)
    }

    /**
     * 打印相册相关视图的真实尺寸（只 debug 写日志）。
     *
     * 用来看「框为什么不在图片中间」：需要同时知道父容器、画布、取景框三者的几何。
     */
    private fun logGalleryLayout(stage: String) {
        val host = rootView
        val wrap = galleryWrap
        val canvas = galleryView
        val frame = canvas?.frameRect()
        val loc = IntArray(2)
        wrap?.getLocationInWindow(loc)
        BambooLog.ocr.d(
            "ocr",
            "gallery[" + stage + "] root=" + (host?.width ?: -1) + "x" + (host?.height ?: -1) +
                " preview=" + (previewWrap?.width ?: -1) + "x" + (previewWrap?.height ?: -1) +
                " pv=" + (previewWrap?.visibility ?: -1) +
                " wrap=" + (wrap?.width ?: -1) + "x" + (wrap?.height ?: -1) + "@y" + loc[1] +
                " canvas=" + (canvas?.width ?: -1) + "x" + (canvas?.height ?: -1) +
                " frame=" + (
                    frame?.let {
                        "" + it.left.toInt() + "," + it.top.toInt() + "," +
                            it.right.toInt() + "," + it.bottom.toInt()
                    } ?: "null"
                ),
        )
    }

    /** 退出相册模式，回到相机预览。 */
    private fun exitGalleryMode(restartCamera: Boolean = true, clearImage: Boolean = true) {
        if (!galleryMode) return
        galleryMode = false
        if (clearImage) {
            galleryActive = false
            galleryBitmap = null
            galleryRestorePending = false
            galleryMatrix = null
            gallerySingle = false
        }
        galleryView?.clear()
        galleryWrap?.visibility = View.GONE
        previewWrap?.visibility = View.VISIBLE
        shootButton?.text = "拍照"
        albumButton?.text = "相册"
        setMessage(alignHint())
        if (restartCamera) startCamera()
        galleryWrap?.requestLayout()
    }

    /** 相册模式的「识别」：先把取景框内容渲染成位图，再走和拍照完全相同的引擎与卡片。 */
    private fun shutterGallery() {
        val crop = galleryView?.cropToFrame()
        if (crop == null) {
            toast("框内没有内容，缩放一下再试")
            return
        }
        BambooLog.ocr.d("ocr", "gallery shutter crop=" + crop.width + "x" + crop.height)
        setMessage("识别中…")
        val appContext = context.applicationContext
        ocrExecutor.execute {
            val ready = OcrRecognizer.ensureReady(appContext)
            if (!ready) {
                BambooLog.ocr.w("ocr", "gallery: model not ready -> " + OcrRecognizer.lastError.orEmpty())
            }
            val value = if (ready) OcrRecognizer.recognize(crop, singleCharMode) else null
            BambooLog.ocr.d(
                "ocr",
                "gallery: ready=" + ready + " chars=" + (value?.length ?: -1) +
                    " text=" + value.orEmpty().take(40).replace("\n", "/"),
            )
            val message = if (!ready) OcrRecognizer.lastError else null
            Handler(Looper.getMainLooper()).post { onRecognized(value, message) }
        }
    }

    /**
     * 把图存到 `<Documents/BambooIME[Debug]>/ocr-debug/`，只用于 debug 排查。
     * 用户可以直接从文件管理取出来，我们据此判断「图本身没字」还是「引擎认不出」。
     */
    private fun dumpDebug(bitmap: Bitmap, name: String) {
        if (!com.osfans.trime.util.LogSwitches.OCR_DUMP) return
        runCatching {
            val dir = File(DataManager.defaultDataDir, "ocr-debug")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name + ".png")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            BambooLog.ocr.d("ocr", "dump -> " + file.absolutePath)
        }.onFailure { BambooLog.ocr.w("ocr", "dump failed", it) }
    }

    /**
     * 结束这次识字会话：连照片一起清掉，下次进「拍」就是默认的相机模式。
     *
     * 用在卡片「收起」上——那是「我用完了」的语义；卡片「返回」不调它，
     * 因为「返回」要保持照片和缩放/旋转，从哪进来就回哪。
     */
    fun resetSession() {
        returnToFrame = false
        fromShot = false
        // 会话结束：下次进「拍」从头开始，也不再跨 App 保持
        ocrSessionActive = false
        galleryActive = false
        galleryBitmap = null
        galleryRestorePending = false
        galleryMatrix = null
        gallerySingle = false
        galleryMode = false
        singleCharMode = false
        modeButton?.text = "单字"
        maskView.single = false
        maskView.invalidate()
        galleryView?.clear()
        galleryWrap?.visibility = View.GONE
        previewWrap?.visibility = View.VISIBLE
        shootButton?.text = "拍照"
        albumButton?.text = "相册"
        setMessage(alignHint())
    }

    /**
     * 面板底部「返回」：相册模式回相机取景并清掉这张照片，相机模式才退到键盘。
     *
     * 相册是「临时看一眼某张图」的入口，退出去就没必要把图留着。
     */
    private fun onPanelBackPressed() {
        if (fromShot) {
            // 从截图进来的：返回就是「结束识字」，直接回键盘，不退回取景
            BambooLog.ocr.d("ocr", "back from shot: leave ocr")
            resetSession()
            leaveToKeyboard()
            return
        }
        if (galleryMode) {
            exitGalleryMode(restartCamera = true, clearImage = true)
        } else {
            // 相机模式的「返回」= 结束这次识字：模式复位整段，下次进来是干净状态
            resetSession()
            leaveToKeyboard()
        }
    }

    private fun leaveToKeyboard() {
        // 用户主动离开面板：清掉相册粘性状态，避免下次进输入框又被接回「拍」
        galleryActive = false
        stopCamera()
        val onScreen = rootView?.isAttachedToWindow == true
        if (!windowManager.isAttached(this) && !onScreen) {
            BambooLog.ocr.d("ocr", "leave ignored (already on keyboard)")
            return
        }
        runCatching {
            // 用 Key 重载：拿到的永远是「当前这套依赖」里缓存的键盘窗口
            windowManager.attachWindow(KeyboardWindow)
        }.onFailure {
            Timber.w(it, "ocr: leave failed")
            BambooLog.ocr.w("ocr", "leave failed", it)
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

        /** true = 单字模式的居中方格。 */
        var single = false

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            rect.set(currentFrameRect())

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

        /** 顶部让位高度（px），由 OcrWindow 按屏幕密度设置。 */
        var topReservedPx: Float = 0f

        private fun currentFrameRect(): RectF =
            OcrFrame.rect(single, width.toFloat(), height.toFloat(), topReservedPx)
    }
}
