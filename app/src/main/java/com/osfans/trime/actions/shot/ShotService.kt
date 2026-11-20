/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
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
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.d("shot: accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        Timber.d("shot: accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
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
