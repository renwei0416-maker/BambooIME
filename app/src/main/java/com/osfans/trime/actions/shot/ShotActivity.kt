/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.osfans.trime.BuildConfig
import com.osfans.trime.util.BambooLog
import java.io.File

/**
 * 截图界面：整屏冻结 → 拖框 → 存图 / 复制。
 *
 * 为什么是独立 Activity（而不是键盘里的浮层）：截图要盖住「整个屏幕」，包括输入法自己；
 * 用 Activity 才能保证盖在状态栏和导航栏之上，并且不受输入法窗口尺寸限制。
 *
 * 画面由 [ShotSelectorView] 自己画（位图 + 压暗 + 选框），保证屏幕坐标和像素坐标只有一处换算。
 */
class ShotActivity : Activity() {
    private lateinit var selector: ShotSelectorView
    private var full: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bitmap = ShotCapture.takePending()
        if (bitmap == null) {
            Toast.makeText(this, "没有可用的截图", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        full = bitmap
        // 全屏沉浸：截图本身已经包含状态栏，界面必须铺满整屏，否则选框会和画面错位
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val root = FrameLayout(this)
        selector = ShotSelectorView(this)
        selector.setBitmap(bitmap)
        root.addView(selector, FrameLayout.LayoutParams(MATCH, MATCH))

        // 顶部：提示 + 取消
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(30), dp(12), dp(10))
            setBackgroundColor(0x99000000.toInt())
        }
        topBar.addView(
            TextView(this).apply {
                text = "拖动选择范围"
                setTextColor(Color.WHITE)
                textSize = 15f
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        topBar.addView(smallButton("取消") { finish() })
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // 底部：全屏 / 保存 / 复制
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(26))
            setBackgroundColor(0x99000000.toInt())
        }
        bottomBar.addView(smallButton("全屏") { selector.selectAll() }, wrapWithGap())
        bottomBar.addView(smallButton("保存") { save() }, wrapWithGap())
        bottomBar.addView(smallButton("复制") { copyToClipboard() }, wrapWithGap())
        root.addView(bottomBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        setContentView(root)
        selector.onSelectionChanged = { rect ->
            BambooLog.shot.d("ui", "selection w=${rect.width()} h=${rect.height()}")
        }
        BambooLog.shot.d("ui", "activity shown ${bitmap.width}x${bitmap.height}")
    }

    private fun currentCrop(): Bitmap? {
        val src = full ?: return null
        val rect = selector.selectionInBitmap()
        if (rect == null) {
            Toast.makeText(this, "先拖一个框", Toast.LENGTH_SHORT).show()
            return null
        }
        return runCatching { Bitmap.createBitmap(src, rect.left, rect.top, rect.width(), rect.height()) }
            .getOrNull()
    }

    /** 存到相册 Pictures/BambooIME；Android 10+ 走 MediaStore，不申请存储权限。 */
    private fun save() {
        val bmp = currentCrop() ?: return
        runCatching {
            val name = "bamboo-shot-${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BambooIME")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore 插入失败")
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            Toast.makeText(this, "已保存到相册 Pictures/BambooIME", Toast.LENGTH_SHORT).show()
            BambooLog.shot.d("save", "ok ${bmp.width}x${bmp.height}")
            finish()
        }.onFailure {
            BambooLog.shot.d("save", "failed: ${it.message}")
            Toast.makeText(this, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 复制成图片剪贴板：写缓存文件再以 content:// 交出去，避免把几 MB 位图塞进 Binder。 */
    private fun copyToClipboard() {
        val bmp = currentCrop() ?: return
        runCatching {
            val dir = File(cacheDir, "shot").apply { mkdirs() }
            val file = File(dir, "shot-${System.currentTimeMillis()}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newUri(contentResolver, "BambooIME 截图", uri))
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            BambooLog.shot.d("copy", "ok ${bmp.width}x${bmp.height}")
            finish()
        }.onFailure {
            BambooLog.shot.d("copy", "failed: ${it.message}")
            Toast.makeText(this, "复制失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun smallButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 14f
            setOnClickListener { onClick() }
        }

    private fun wrapWithGap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(6); marginEnd = dp(6) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
