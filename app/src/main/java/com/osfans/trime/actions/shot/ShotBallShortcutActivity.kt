/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * 桌面长按图标 →「截图浮球」快捷方式的落点。
 *
 * 只做两件事：打开开关、把浮球挂出来；**不截屏**。
 * 没授权时什么都不改，只提示路径并跳到系统无障碍页。
 */
class ShotBallShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ShotService.isGranted(this)) {
            Toast.makeText(this, PERMISSION_HINT, Toast.LENGTH_LONG).show()
            ShotActions.openAccessibilitySettings(this)
            finish()
            return
        }
        ShotBallPrefs.enabled = true
        ShotBallOverlay.show()
        val text =
            if (ShotBallOverlay.isShowing) {
                "截图浮球已开启，桌面上点它就能截图"
            } else {
                "浮球要等无障碍服务连上才会出现，稍等一下"
            }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val PERMISSION_HINT = "截图浮球需要无障碍权限：设置 → 辅助功能 → 无障碍 → 已下载的服务 → BambooIME → 打开「截图」"
    }
}
