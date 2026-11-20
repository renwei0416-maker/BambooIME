/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.util.BambooLog

/**
 * 截图的唯一入口。
 *
 * 顺序很重要：先把键盘收起来、等界面重绘，再抓屏 —— 否则截到的是键盘本身。
 * 抓到的位图交给 [ShotActivity] 去框选。
 */
object ShotActions {
    private const val HIDE_DELAY_MS = 300L

    fun start(service: TrimeInputMethodService?) {
        val ctx: Context = service ?: run {
            BambooLog.shot.d("start", "no service")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(ctx, "系统版本过低，截图需要 Android 11 及以上", Toast.LENGTH_LONG).show()
            return
        }
        if (!ShotCapture.isReady()) {
            // 权限开着但服务实例还没连上（进程刚重启、系统回收过）：先等一下重试，别直接说「没权限」
            if (ShotService.isGranted(ctx)) {
                BambooLog.shot.d("start", "granted but not connected, retry")
                retryStart(service, ctx, 0)
            } else {
                BambooLog.shot.d("start", "not granted")
                Toast.makeText(ctx, "请先开启「截图」权限（无障碍）", Toast.LENGTH_LONG).show()
                openAccessibilitySettings(ctx)
            }
            return
        }
        captureFlow(service, ctx)
    }

    /** 已授权但服务未连上时，最多重试两次；仍不行再引导用户去无障碍页重开一次。 */
    private fun retryStart(service: TrimeInputMethodService?, ctx: Context, attempt: Int) {
        if (ShotCapture.isReady()) {
            captureFlow(service, ctx)
            return
        }
        if (attempt >= 2) {
            Toast.makeText(
                ctx,
                "截图服务没就绪：请到「设置 → 辅助功能 → 无障碍」把「截图」关一下再打开",
                Toast.LENGTH_LONG,
            ).show()
            openAccessibilitySettings(ctx)
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ retryStart(service, ctx, attempt + 1) }, 500L)
    }

    private fun captureFlow(service: TrimeInputMethodService?, ctx: Context) {
        // 收起键盘，让下面的 App 露出完整画面
        runCatching { service?.requestHideSelf(0) }
        val decor = service?.window?.window?.decorView
        if (decor == null) {
            captureLater(ctx)
        } else {
            decor.postDelayed({ captureLater(ctx) }, HIDE_DELAY_MS)
        }
    }

    private fun captureLater(ctx: Context) {
        ShotCapture.take { bitmap, error ->
            if (bitmap == null) {
                Toast.makeText(ctx, error ?: "抓屏失败", Toast.LENGTH_SHORT).show()
                return@take
            }
            ShotCapture.putPending(bitmap)
            val intent = Intent(ctx, ShotActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            runCatching { ctx.startActivity(intent) }
                .onFailure {
                    BambooLog.shot.d("start", "startActivity failed: ${it.message}")
                    Toast.makeText(ctx, "打不开截图界面：${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 直接跳到系统「无障碍」页，用户自己找到「苍竹输入法截图」打开。 */
    fun openAccessibilitySettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure { BambooLog.shot.d("settings", "open failed: ${it.message}") }
    }
}
