/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.ocr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * 只为申请相机权限而存在的一个 1×1 透明 Activity。
 *
 * 输入法（Service）自己弹不出权限框，所以拍照识字面板点「去授权」时拉起这个 Activity：
 * 申请完立即 finish，用户回到输入法再点一次「拍照」即可。
 *
 * 不注册 launcher，`exported=false`，界面上什么都看不到（透明主题 + 0 尺寸内容）。
 */
class OcrPermissionActivity : Activity() {
    companion object {
        private const val REQ_CAMERA = 0x0C01
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 空内容，仅作为权限申请的宿主
        setContentView(FrameLayout(this))

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        runCatching {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }.onFailure {
            Timber.w(it, "ocr: request camera permission failed")
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // 被永久拒绝时给一条去设置的入口，否则用户会一直看到「需要相机权限」
            showSettingsHint()
        } else {
            finish()
        }
    }

    private fun showSettingsHint() {
        val box =
            FrameLayout(this).apply {
                setBackgroundColor(0xE6000000.toInt())
                setOnClickListener {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", packageName, null)),
                        )
                    }.onFailure { Timber.w(it, "ocr: open settings failed") }
                    finish()
                }
            }
        box.addView(
            TextView(this).apply {
                text = "未获得相机权限，点这里去设置里打开"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                visibility = View.VISIBLE
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER,
            ),
        )
        setContentView(box)
    }
}
