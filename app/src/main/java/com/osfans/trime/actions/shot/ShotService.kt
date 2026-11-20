/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * 只为「截图」存在的无障碍服务。
 *
 * 为什么需要它：Android 里抓到整屏位图只有三条路——无障碍的 takeScreenshot()（API 30+，无弹窗）、
 * MediaProjection（每次都要弹一次系统的确认框）、以及让用户自己按电源键+音量。只有第一条能做到
 * 「点一下、直接就截到」。它不读取窗口内容、不监听按键，只在内存里留一个静态引用给 ShotCapture 用。
 *
 * 用户在系统设置里手动开启后，本服务随系统常驻；关掉授权就相当于关掉截图功能（其余功能不受影响）。
 */
class ShotService : AccessibilityService() {
    /** 灭屏/解锁广播；只在服务连接期间注册。 */
    private var screenReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.d("shot: accessibility service connected")
        registerScreenReceiver()
        // 用户开过「截图浮球」就顺手把它挂回来（进程重启/系统回收后浮球会跟着服务一起没）
        // 但锁屏状态下不要挂：无障碍浮层默认会画在锁屏上，用户明确要求「锁屏不显示」
        if (ShotBallPrefs.enabled && !isLocked()) ShotBallOverlay.show()
    }

    /**
     * 锁屏时收起浮球、解锁后再放回来。
     *
     * 无障碍浮层（TYPE_ACCESSIBILITY_OVERLAY）默认在锁屏上也绘制，这是系统行为；
     * 只能自己监听灭屏/解锁广播来藏。用 [ContextCompat.registerReceiver] 是为了兼容
     * Android 13 的 RECEIVER_NOT_EXPORTED 要求（这些是系统广播，不走导出）。
     */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val r =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> ShotBallOverlay.hide()
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                            if (ShotBallPrefs.enabled && !isLocked()) ShotBallOverlay.show()
                        }
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        runCatching { ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onSuccess { screenReceiver = r }
            .onFailure { Timber.w(it, "shot: register screen receiver failed") }
    }

    private fun unregisterScreenReceiver() {
        val r = screenReceiver ?: return
        runCatching { unregisterReceiver(r) }
        screenReceiver = null
    }

    private fun isLocked(): Boolean =
        runCatching {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        }.getOrDefault(false)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        unregisterScreenReceiver()
        ShotBallOverlay.hide()
        if (instance === this) instance = null
        Timber.d("shot: accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregisterScreenReceiver()
        ShotBallOverlay.hide()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: ShotService? = null
            private set

        /** 服务实例是否已经连上（真正能调 takeScreenshot 的前提）。 */
        fun isReady(): Boolean = instance != null

        /**
         * 系统设置里是否已经勾选了本服务。
         *
         * 为什么要和 [isReady] 分开：无障碍服务实例由系统绑定，进程被杀或系统回收后会短暂断开，
         * 此时权限明明还开着，只看 [instance] 会误报「没开权限」（实测报过一次就再也进不去截图）。
         */
        fun isGranted(context: Context): Boolean {
            val enabled =
                runCatching {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                }.getOrNull() ?: return false
            val me = ComponentName(context, ShotService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
