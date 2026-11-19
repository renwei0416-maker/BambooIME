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
import com.osfans.trime.actions.bamboo.AiClient
import com.osfans.trime.actions.bamboo.BambooPrefs
import com.osfans.trime.actions.ocr.OcrRecognizer
import com.osfans.trime.util.BambooLog
import java.io.ByteArrayOutputStream
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
    private lateinit var ball: TextView
    private lateinit var menu: LinearLayout
    private lateinit var mask: View
    private lateinit var resultBox: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var busyBox: LinearLayout

    private val items = listOf("取消", "全屏", "识字", "问 AI", "保存", "复制")
    private var itemViews = ArrayList<TextView>()

    private var menuOpen = false
    private var selected = -1
    private var busy = false

    /** 整屏底图：takePending() 会把 ShotCapture 里的引用清掉，这里自己留一份。 */
    private var full: Bitmap? = null
    private var itemH = 0
    private var panelTop = 0

    private var ballDownRawX = 0f
    private var ballDownRawY = 0f
    private var ballStartX = 0f
    private var ballStartY = 0f
    private var ballMoved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        buildMenu()
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
        super.onDestroy()
    }

    // ---------------- 菜单球 ----------------

    private fun buildBall() {
        ball = TextView(this)
        ball.text = "菜单"
        ball.setTextColor(Color.WHITE)
        ball.textSize = 15f
        ball.gravity = Gravity.CENTER
        ball.background = round(0xFF2F4F43.toInt(), dp(32).toFloat())
        ball.elevation = dp(8).toFloat()
        ball.isClickable = true
        val lp = FrameLayout.LayoutParams(dp(64), dp(64))
        lp.gravity = Gravity.BOTTOM or Gravity.END
        lp.rightMargin = dp(18)
        lp.bottomMargin = dp(56)
        root.addView(ball, lp)
        ball.x = prefsX(lp.rightMargin.toFloat())
        ball.y = prefsY(lp.bottomMargin.toFloat())

        ball.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ballDownRawX = e.rawX
                    ballDownRawY = e.rawY
                    ballStartX = v.x
                    ballStartY = v.y
                    ballMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - ballDownRawX
                    val dy = e.rawY - ballDownRawY
                    if (!ballMoved && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                        ballMoved = true
                        if (!menuOpen) openMenu()
                    }
                    if (ballMoved && !menuOpen) {
                        v.x = (ballStartX + dx).coerceIn(0f, (root.width - v.width).toFloat())
                        v.y = (ballStartY + dy).coerceIn(0f, (root.height - v.height).toFloat())
                    }
                    if (menuOpen) updateSelectedByY(e.rawY)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (menuOpen) {
                        val hit = selected
                        closeMenu()
                        if (hit >= 0) perform(hit) else openMenu()
                    } else if (!ballMoved) {
                        v.x = ballStartX
                        v.y = ballStartY
                        openMenu()
                    } else {
                        snapBall()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapBall() {
        val margin = dp(18).toFloat()
        val toLeft = ball.x + ball.width / 2f < root.width / 2f
        val targetX = if (toLeft) margin else root.width - ball.width - margin
        ball.animate().x(targetX).setDuration(160L).start()
        savePrefs(targetX, ball.y)
    }

    private fun prefsX(fallback: Float): Float {
        val v = getSharedPreferences(PREF, MODE_PRIVATE).getFloat("ballX", -1f)
        return if (v >= 0f && v <= max(0f, root.width.toFloat())) v else fallback
    }

    private fun prefsY(fallback: Float): Float {
        val v = getSharedPreferences(PREF, MODE_PRIVATE).getFloat("ballY", -1f)
        return if (v >= 0f) v else fallback
    }

    private fun savePrefs(x: Float, y: Float) {
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putFloat("ballX", x).putFloat("ballY", y).apply()
    }

    // ---------------- 菜单 ----------------

    private fun buildMenu() {
        itemH = dp(52)
        menu = LinearLayout(this)
        menu.orientation = LinearLayout.VERTICAL
        menu.visibility = View.GONE
        for (label in items) {
            val t = TextView(this)
            t.text = label
            t.setTextColor(Color.WHITE)
            t.textSize = 16f
            t.gravity = Gravity.CENTER
            t.background = round(0xF2FFFFFF.toInt(), dp(22).toFloat())
            t.setTextColor(0xFF1F2A26.toInt())
            val lp = LinearLayout.LayoutParams(dp(96), itemH)
            lp.bottomMargin = dp(6)
            menu.addView(t, lp)
            itemViews.add(t)
        }
        val lp = FrameLayout.LayoutParams(WRAP, WRAP)
        lp.gravity = Gravity.BOTTOM or Gravity.END
        root.addView(menu, lp)
    }

    private fun openMenu() {
        menuOpen = true
        selected = -1
        val panelH = itemH * items.size + dp(6) * (items.size - 1)
        var right = (root.width - ball.x - ball.width).toInt()
        var bottom = (root.height - ball.y).toInt() + dp(10)
        right = right.coerceIn(dp(12), root.width - dp(96) - dp(12))
        bottom = bottom.coerceIn(panelH + dp(12), root.height - dp(12))
        val lp = menu.layoutParams as FrameLayout.LayoutParams
        lp.rightMargin = right
        lp.bottomMargin = bottom
        menu.layoutParams = lp
        menu.visibility = View.VISIBLE
        menu.alpha = 0f
        menu.animate().alpha(1f).setDuration(120L).start()
        panelTop = root.height - bottom - panelH
        refreshItems()
        ball.text = "≡"
    }

    private fun closeMenu() {
        menuOpen = false
        menu.visibility = View.GONE
        ball.text = "菜单"
        selected = -1
        refreshItems()
    }

    private fun updateSelectedByY(rawY: Float) {
        if (itemH <= 0) return
        val idx = ((rawY - panelTop) / itemH).toInt()
        val clamped = if (idx < 0 || idx >= items.size) -1 else idx
        if (clamped == selected) return
        selected = clamped
        refreshItems()
    }

    private fun refreshItems() {
        for (i in itemViews.indices) {
            val t = itemViews[i]
            val on = i == selected
            t.textSize = if (on) 18f else 15f
            t.alpha = if (selected < 0) 1f else if (on) 1f else 0.55f
            t.setTextColor(if (on) Color.WHITE else 0xFF1F2A26.toInt())
            t.background = round(if (on) 0xFF4FA46A.toInt() else 0xF2FFFFFF.toInt(), dp(22).toFloat())
        }
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

    private fun perform(index: Int) {
        if (busy) return
        when (index) {
            0 -> finish()
            1 -> {
                if (selector.isFullSelection()) selector.setDefault() else selector.selectAll()
            }
            2 -> doOcr()
            3 -> doAskAi()
            4 -> saveImage()
            5 -> copyImage()
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

    private fun doOcr() {
        val bmp = currentCrop() ?: return
        if (!OcrRecognizer.isReady()) {
            Toast.makeText(this, "识字模型还没加载过，先用一次「拍照识字」再来", Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true)
        scope.launch {
            val text = withContext(Dispatchers.Default) { runCatching { OcrRecognizer.recognize(bmp) }.getOrNull() }
            setBusy(false)
            if (text.isNullOrBlank()) {
                Toast.makeText(this@ShotActivity, "没认出文字", Toast.LENGTH_SHORT).show()
            } else {
                showResult("识字结果（" + text.length + " 字）", text)
            }
        }
    }

    private fun doAskAi() {
        if (BambooPrefs.aiApiKey.isBlank()) {
            Toast.makeText(this, "还没填 AI Key，先去「回退与AI」里填一下", Toast.LENGTH_LONG).show()
            return
        }
        val bmp = currentCrop() ?: return
        val bytes =
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        setBusy(true)
        scope.launch {
            val r = AiClient.vision(DEFAULT_PROMPT, bytes)
            setBusy(false)
            r.fold(
                onSuccess = { showResult("问 AI", it) },
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
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        private const val PREF = "bamboo_shot"
        private const val DEFAULT_PROMPT =
            "请看图回答：如果图里有文字，先把文字原样列出来；再用一两句话说明这张图的内容。用简体中文。"
    }
}
