/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.osfans.trime.util.BambooLog
import java.util.concurrent.Executors

/**
 * 抓屏 + 位图交接。
 *
 * 拿到的位图不经过 Intent（Binder 事务有 1MB 上限），而是先放进 [pending]，
 * 由 [ShotActivity] 在自己的 onCreate 里取走，保证大图不丢。
 */
object ShotCapture {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var pending: Bitmap? = null

    fun isReady(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ShotService.isReady()

    /** 主线程回调；失败时给一句可直接展示给用户的话。 */
    fun take(onResult: (Bitmap?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null, "系统版本过低，截图需要 Android 11 及以上")
            return
        }
        val service = ShotService.instance
        if (service == null) {
            onResult(null, "还没开启截图权限")
            return
        }
        val callback =
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    // wrapHardwareBuffer 拿到的是硬件位图，必须 copy 成软件位图才能裁剪与编码
                    val bitmap =
                        runCatching {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                    runCatching { buffer.close() }
                    io.execute { BambooLog.shot.d("flow", "capture ok size=${bitmap?.width}x${bitmap?.height}") }
                    main.post { onResult(bitmap, if (bitmap == null) "解码屏幕位图失败" else null) }
                }

                override fun onFailure(errorCode: Int) {
                    io.execute { BambooLog.shot.d("flow", "capture failed code=$errorCode") }
                    main.post { onResult(null, "抓屏失败（错误码 $errorCode）") }
                }
            }
        runCatching { service.takeScreenshot(Display.DEFAULT_DISPLAY, io, callback) }
            .onFailure {
                BambooLog.shot.d("flow", "takeScreenshot threw: ${it.message}")
                onResult(null, "抓屏失败：${it.message}")
            }
    }

    fun putPending(bitmap: Bitmap) {
        pending = bitmap
    }

    /** 最近一次抓到的整屏位图（裁剪时当底图用）。 */


    fun takePending(): Bitmap? = pending.also { pending = null }

    /** 截图界面裁好的图，等输入法窗口回来时直接送进识字面板。 */
    private var pendingOcr: Bitmap? = null

    fun putPendingOcr(bitmap: Bitmap) {
        pendingOcr = bitmap
    }

    fun takePendingOcr(): Bitmap? = pendingOcr.also { pendingOcr = null }

    /**
     * 清空所有交接中的位图。
     *
     * 截图界面每次退出（不管是保存、复制、退出还是被系统回收）都会调这里：
     * 上一次的整屏图、裁好的图都不该留到下一次，否则体积大、还可能被下一次误用。
     */
    fun reset() {
        pending = null
        pendingOcr = null
    }
}
