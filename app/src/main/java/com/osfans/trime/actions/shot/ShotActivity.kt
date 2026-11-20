package com.osfans.trime.actions.shot

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.osfans.trime.BuildConfig
import com.osfans.trime.actions.ocr.OcrWindow
import com.osfans.trime.actions.ocr.OcrRecognizer
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.util.BambooLog
import com.osfans.trime.util.forceShowSelf
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 截图工作台。
 *
 * 只留三样东西：全屏底图（可拖框）、一条会淡出的提示、右下角可拖的圆形菜单球。
 * 点开菜单后功能项自球向上叠出，手指滑到哪项哪项放大变绿，松手执行。
 */
class ShotActivity : Activity() {

    private val scope: CoroutineScope = MainScope()

    private lateinit var root: FrameLayout
    private lateinit var selector: ShotSelectorView
    private lateinit var hint: TextView
    private lateinit var ball: ShotBallView
    private lateinit var fan: ShotFanMenu
    private lateinit var mask: View
    private lateinit var resultBox: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var busyBox: LinearLayout
    /** 菜单打开时的全屏拦截层：吃掉所有触摸，避免穿透到下面的选区。 */
    private lateinit var shield: View

    /** 第一层固定项；有启用的 AI 动作时末尾再加「更多」。 */
    private val baseItems = listOf("取消", "全屏", "识字", "保存", "复制", "退出")

    /** 当前层：0＝固定项，1+＝AI 动作层。 */
    private var fanLayer = 0

    /** 当前层实际显示的文字（含「更多」「返回」「退出」）。 */
    private var layerItems: List<String> = emptyList()

    private var menuOpen = false
    private var busy = false

    /** 是否跳去了「识字」面板；是的话退出时别恢复浮球（会压在识字面板上）。 */

    /** 整屏底图：takePending() 会把 ShotCapture 里的引用清掉，这里自己留一份。 */
    private var full: Bitmap? = null

    private var ballDownRawX = 0f
    private var ballDownRawY = 0f
    private var ballStartX = 0f
    private var ballStartY = 0f
    private var ballMoved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 截图期间不允许常驻浮球留在画面上：会被一起截进去，也会和菜单球重叠。
        runCatching { ShotBallOverlay.hide() }
        // 内置动作的提示词升过版，老用户存的是旧文本：进截图界面时同步一次
        ShotAiPrefs.ensureDefaults()
        val bmp = ShotCapture.takePending()
        if (bmp == null) {
            Toast.makeText(this, "没有可用的截图", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        full = bmp

        root = FrameLayout(this)
        selector = ShotSelectorView(this)
        selector.setBitmap(bmp)
        root.addView(selector, FrameLayout.LayoutParams(MATCH, MATCH))

        hint = pill("拖动选择截图范围", 15f, 0xF2000000.toInt(), Color.WHITE)
        val hintLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        hintLp.topMargin = dp(40)
        root.addView(hint, hintLp)
        hint.postDelayed({ fadeOut(hint, 1200L) }, 200L)

        buildFan()
        buildBall()
        buildBusy()
        buildResult()

        setContentView(root)
        // 华为 API 31 上 DecorView 未 attach 时取 insetsController 会 NPE，
        // 所以等首帧布局完成后再进入沉浸式
        root.post { goImmersive() }
        selector.onSelectionChanged = { rect ->
            BambooLog.shot.d("sel", "bitmap=" + rect.toShortString())
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // 退出即清干净：整屏图、裁好的图都不留到下一次
        ShotCapture.reset()
        // 退出截图后把常驻浮球挂回来：
        // - 用户点过「退出」时开关已是 false，不会再出现；
        // - 去识字的这条路径不恢复，避免浮球压在识字面板上。
        if (ShotBallPrefs.enabled) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (ShotBallPrefs.enabled) runCatching { ShotBallOverlay.show() }
            }, 260L)
        }
        super.onDestroy()
    }

    // ---------------- 菜单球 ----------------

    private fun buildBall() {
        ball = ShotBallView(this)
        ball.elevation = dp(10).toFloat()
        ball.isClickable = true
        val lp = FrameLayout.LayoutParams(dp(BALL_DP), dp(BALL_DP), Gravity.TOP or Gravity.START)
        root.addView(ball, lp)
        ball.post {
            // 每次进入都回到默认位置（不记忆上一次拖到哪）。
            // 纵向取屏幕下三分之一处：球在底部时扇形会有一截伸出屏幕，放这里刚好整段都在屏内。
            ball.x = (root.width - dp(BALL_DP) - dp(18)).toFloat()
            ball.y = (root.height * 2f / 3f - dp(BALL_DP) / 2f)
            clampBall()
        }
        ball.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ballDownRawX = e.rawX
                    ballDownRawY = e.rawY
                    ballStartX = v.x
                    ballStartY = v.y
                    ballMoved = false
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90L).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - ballDownRawX
                    val dy = e.rawY - ballDownRawY
                    if (!ballMoved && (abs(dx) > dp(6).toFloat() || abs(dy) > dp(6).toFloat())) {
                        ballMoved = true
                    }
                    if (ballMoved) {
                        v.x = ballStartX + dx
                        v.y = ballStartY + dy
                        clampBall()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                    if (ballMoved) {
                        snapBall()
                    } else {
                        if (menuOpen) closeMenu() else openMenu()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun clampBall() {
        ball.x = ball.x.coerceIn(0f, (root.width - ball.width).coerceAtLeast(0).toFloat())
        ball.y = ball.y.coerceIn(0f, (root.height - ball.height).coerceAtLeast(0).toFloat())
    }

    private fun snapBall() {
        val margin = dp(14).toFloat()
        val toLeft = ball.x + ball.width / 2f < root.width / 2f
        val targetX = if (toLeft) margin else (root.width - ball.width - margin).coerceAtLeast(0f)
        clampBall()
        ball.animate().x(targetX).setDuration(160L).start()
    }

    // ---------------- 菜单 ----------------

    private fun buildFan() {
        fan = ShotFanMenu(this)
        fan.visibility = View.GONE
        fan.onSelect = { index -> onPick(index) }
        fan.onDismiss = { closeMenu() }
        root.addView(fan, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** 扇形菜单以球心为中心散开，球在左半边就朝右开，反之朝左开。 */
    private fun openMenu() {
        menuOpen = true
        ball.opened = true
        showLayer(0)
    }

    /**
     * 打开第 [layer] 层。
     *
     * 0 层是固定动作（加「更多」），1 层起是 AI 动作（末尾固定「返回」「退出」）。
     * 每层最多 8 项；扇形菜单自己会按项数缩小半径和项直径，一屏放得下就不分层。
     */
    /**
     * 菜单下标映射回真实动作。
     *
     * 第二层把 `动作 + 返回 + 退出` 整表倒序显示，所以下标不能直接当动作列表下标：
     * 最前面两个是「退出」「返回」，后面才是倒着排的动作（最后一个下标对应第一个动作）。
     */
    private fun actionAt(index: Int): ShotAiPrefs.Action? {
        if (fanLayer == 0) return null
        val actions = ShotAiPrefs.enabledActions()
        if (actions.isEmpty()) return null
        val list = layerItems
        if (index !in list.indices) return null
        val pos = index - (list.size - actions.size)
        if (pos < 0 || pos >= actions.size) return null
        return actions[actions.size - 1 - pos]
    }

    private fun showLayer(layer: Int) {
        fanLayer = layer
        val actions = ShotAiPrefs.enabledActions()
        layerItems =
            if (layer == 0) {
                if (actions.isEmpty()) baseItems else baseItems + MORE
            } else {
                // 扇形是「从下往上」编号的（第 0 项落在最下方），所以第二层整表倒序：
                // 显示上自下而上变成 退出 / 返回 / 新增… / 翻译 / 问 AI —— 问 AI 落在最上面。
                (actions.map { it.name } + listOf(BACK, EXIT)).asReversed()
            }
        fan.show(ball.x + ball.width / 2f, ball.y + ball.height / 2f, layerItems)
    }

    /** 点中某一项：先处理「更多 / 返回 / 退出」这类导航，再按文字分发。 */
    private fun onPick(index: Int) {
        val label = layerItems.getOrNull(index) ?: return
        if (fanLayer == 0) {
            if (label == MORE) {
                showLayer(1)
                return
            }
            closeMenu()
            perform(label)
            return
        }
        when (label) {
            BACK -> {
                showLayer(0)
                return
            }
            EXIT -> {
                closeMenu()
                finish()
                return
            }
        }
        val action = actionAt(index)
        closeMenu()
        action?.let { runAction(it) }
    }

    private fun closeMenu() {
        menuOpen = false
        ball.opened = false
        fan.hide()
    }

    // ---------------- 忙碌 / 结果 ----------------

    private fun buildBusy() {
        busyBox = LinearLayout(this)
        busyBox.orientation = LinearLayout.HORIZONTAL
        busyBox.gravity = Gravity.CENTER
        busyBox.background = round(0xF2000000.toInt(), dp(24).toFloat())
        busyBox.setPadding(dp(18), dp(12), dp(18), dp(12))
        busyBox.visibility = View.GONE
        val t = TextView(this)
        t.text = "处理中…"
        t.setTextColor(Color.WHITE)
        t.textSize = 15f
        busyBox.addView(t)
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
        root.addView(busyBox, lp)
    }

    private fun buildResult() {
        mask = View(this)
        mask.setBackgroundColor(0x99000000.toInt())
        mask.visibility = View.GONE
        mask.isClickable = true
        root.addView(mask, FrameLayout.LayoutParams(MATCH, MATCH))

        resultBox = LinearLayout(this)
        resultBox.orientation = LinearLayout.VERTICAL
        resultBox.background = round(0xFFF7F8F7.toInt(), dp(16).toFloat())
        resultBox.setPadding(dp(16), dp(14), dp(16), dp(12))
        resultBox.visibility = View.GONE
        val title = TextView(this)
        title.text = "结果"
        title.setTextColor(0xFF2F4F43.toInt())
        title.textSize = 16f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        resultBox.addView(title)

        val sv = ScrollView(this)
        resultText = TextView(this)
        resultText.setTextColor(0xFF1F2A26.toInt())
        resultText.textSize = 15f
        resultText.setLineSpacing(0f, 1.25f)
        resultText.setTextIsSelectable(true)
        sv.addView(resultText)
        val svLp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        svLp.topMargin = dp(10)
        resultBox.addView(sv, svLp)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.END
        val copy = actionChip("复制")
        copy.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("BambooIME", resultText.text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        val close = actionChip("关闭")
        close.setOnClickListener { hideResult() }
        val cLp = LinearLayout.LayoutParams(WRAP, WRAP)
        cLp.rightMargin = dp(10)
        bar.addView(copy, cLp)
        bar.addView(close, WRAPLP())
        val barLp = LinearLayout.LayoutParams(MATCH, WRAP)
        barLp.topMargin = dp(12)
        resultBox.addView(bar, barLp)

        val lp = FrameLayout.LayoutParams(MATCH, dp(360))
        lp.gravity = Gravity.CENTER
        lp.leftMargin = dp(16)
        lp.rightMargin = dp(16)
        root.addView(resultBox, lp)
    }

    private fun WRAPLP(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(WRAP, WRAP)

    private fun actionChip(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.textSize = 15f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(18), dp(8), dp(18), dp(8))
        t.background = round(0xFF2F4F43.toInt(), dp(20).toFloat())
        return t
    }

    private fun showResult(title: String, body: String) {
        (resultBox.getChildAt(0) as TextView).text = title
        resultText.text = body
        mask.visibility = View.VISIBLE
        resultBox.visibility = View.VISIBLE
        resultBox.alpha = 0f
        resultBox.animate().alpha(1f).setDuration(140L).start()
    }

    private fun hideResult() {
        mask.visibility = View.GONE
        resultBox.visibility = View.GONE
    }

    // ---------------- 六项动作 ----------------

    private fun perform(label: String) {
        if (busy) return
        when (label) {
            "取消" -> {
                selector.clearSelection()
                closeMenu()
                Toast.makeText(this, "已取消选框", Toast.LENGTH_SHORT).show()
            }
            "全屏" -> {
                if (selector.isFullSelection()) selector.setDefault() else selector.selectAll()
            }
            "识字" -> doOcr()
            "保存" -> saveImage()
            "复制" -> copyImage()
            EXIT -> {
                // 退出截图模块：不保存、不问，直接回原来的 App
                closeMenu()
                finish()
            }
        }
    }

    private fun currentCrop(): Bitmap? {
        val rect = selector.selectionInBitmap()
        if (rect == null) {
            Toast.makeText(this, "先拖一个框", Toast.LENGTH_SHORT).show()
            return null
        }
        val src = full ?: return null
        return runCatching { Bitmap.createBitmap(src, rect.left, rect.top, rect.width(), rect.height()) }.getOrNull()
    }

    /**
     * 识字：先按需加载识别模型（首次会慢一点），把裁好的图放进交接位，然后立刻关掉截图界面。
     *
     * 输入法窗口回来时（[InputView.startInput]）会把这张图交给识字面板并直接进相册识图，
     * 所以用户看到的是「截图界面消失 → 识字面板已经弹出来」。
     */
    private fun doOcr() {
        val bmp = currentCrop() ?: return
        setBusy(true)
        scope.launch {
            val ready = withContext(Dispatchers.IO) {
                runCatching { OcrRecognizer.ensureReady(this@ShotActivity) }.getOrDefault(false)
            }
            setBusy(false)
            if (!ready) {
                Toast.makeText(this@ShotActivity, "识字模型加载失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            ShotCapture.putPendingOcr(bmp)
            val svc = TrimeInputMethodService.current
            finish()
            if (svc != null) bringUpOcrPanel(svc)
        }
    }

    /**
     * 把输入法叫回来，并让它直接把识字面板打开。
     *
     * 只把图放进交接位、等用户点编辑区是不够的——截图界面关掉后输入法窗口未必会自动回来。
     * 所以这里主动 `forceShowSelf()`，再在几次重试里把图交给 [OcrWindow]：
     * 输入法窗口刚回来时 `BoardWindowManager.current` 可能还没就绪，第一次挂不上就等一下再来。
     */
    private fun bringUpOcrPanel(service: TrimeInputMethodService) {
        val handler = Handler(Looper.getMainLooper())
        var done = false
        for (delay in longArrayOf(200L, 700L, 1500L, 2600L)) {
            handler.postDelayed({
                if (done) return@postDelayed
                runCatching { service.forceShowSelf() }
                val pending = ShotCapture.takePendingOcr() ?: return@postDelayed
                done = OcrWindow.openGalleryWith(pending, fromShotSource = true)
                if (!done) ShotCapture.putPendingOcr(pending)
            }, delay)
        }
    }

    /**
     * 执行一个 AI 动作：把当前选框里的图画出来，连同该动作的提示词一起发给「视觉 AI」。
     *
     * 请求在 IO 线程做（缩图 + 上传 + 等模型），期间显示「处理中…」并挡住触摸。
     */
    private fun runAction(action: ShotAiPrefs.Action) {
        if (ShotAiPrefs.apiKey.isBlank()) {
            Toast.makeText(this, "还没填「截图 → AI 视觉」的 API Key，先去设置里填一下", Toast.LENGTH_LONG).show()
            return
        }
        val bmp = currentCrop() ?: return
        setBusy(true)
        scope.launch {
            val r = withContext(Dispatchers.IO) { ShotVisionClient.ask(action.prompt, bmp) }
            setBusy(false)
            r.fold(
                onSuccess = { showResult(action.name, it) },
                onFailure = { Toast.makeText(this@ShotActivity, it.message ?: "请求失败", Toast.LENGTH_LONG).show() },
            )
        }
    }

    private fun saveImage() {
        val bmp = currentCrop() ?: return
        val name = "BambooIME_" + System.currentTimeMillis() + ".png"
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BambooIME")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }.onSuccess {
            Toast.makeText(this, "已保存到相册 Pictures/BambooIME", Toast.LENGTH_SHORT).show()
            finish()
        }.onFailure {
            Toast.makeText(this, "保存失败：" + (it.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyImage() {
        val bmp = currentCrop() ?: return
        runCatching {
            val dir = File(cacheDir, "shot").apply { mkdirs() }
            val f = File(dir, "shot_" + System.currentTimeMillis() + ".png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", f)
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(ClipData.newUri(contentResolver, "BambooIME", uri))
        }.onSuccess {
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            finish()
        }.onFailure {
            Toast.makeText(this, "复制失败：" + (it.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- 小工具 ----------------

    private fun setBusy(on: Boolean) {
        busy = on
        busyBox.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun goImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            runCatching {
                val c = window.decorView.windowInsetsController ?: return@runCatching
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }.onFailure { BambooLog.shot.d("ui", "immersive failed: " + it.javaClass.simpleName) }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun pill(text: String, size: Float, bg: Int, fg: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(fg)
        t.textSize = size
        t.gravity = Gravity.CENTER
        t.setPadding(dp(16), dp(8), dp(16), dp(8))
        t.background = round(bg, dp(20).toFloat())
        return t
    }

    private fun fadeOut(v: View, delay: Long) {
        v.animate().alpha(0f).setStartDelay(0L).setDuration(260L).start()
    }

    private fun round(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 菜单球直径：比默认的 60dp 小一圈，不挡内容。 */
        private const val BALL_DP = 48
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        private const val PREF = "bamboo_shot"

        /** 第一层末尾的「更多」（有启用的 AI 动作时才出现）。 */
        private const val MORE = "更多"

        /** 第二层末尾的「返回」（回第一层，不退出）。 */
        private const val BACK = "返回"

        /** 「退出」：关掉截图回原来的 App；两层都有，不允许隐藏。 */
        private const val EXIT = "退出"
    }
}
