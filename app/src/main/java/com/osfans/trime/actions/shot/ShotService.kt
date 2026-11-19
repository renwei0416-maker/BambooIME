/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import android.accessibilityservice.AccessibilityService
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

        /** 授权状态；没开就是 false，截图入口据此提示用户去开。 */
        fun isReady(): Boolean = instance != null
    }
}
