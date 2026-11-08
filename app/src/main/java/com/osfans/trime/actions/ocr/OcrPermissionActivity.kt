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
import android.provider.MediaStore
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
        private const val REQ_PICK = 0x0C02
        private const val REQ_PICK_LEGACY = 0x0C03

        /** 用这个 Action 启动＝去相册选图，而不是申请相机权限。 */
        const val ACTION_PICK = "com.osfans.trime.action.OCR_PICK"

        /** 选完图后回调给拍照识字面板（面板会把回调置回 null）。 */
        @Volatile
        var pickCallback: ((Uri) -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 空内容，仅作为权限申请的宿主
        setContentView(FrameLayout(this))

        if (intent?.action == ACTION_PICK) {
            startPicker()
            return
        }

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

    /**
     * 拉起系统照片选择器。两个都是零权限的：
     * - Android 13+：MediaStore.ACTION_PICK_IMAGES（系统照片选择器）
     * - 更老的：Intent.ACTION_OPEN_DOCUMENT + image 类型，退化成文件选择器
     */
    private fun startPicker() {
        val picker =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
            } else {
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*")
            }
        // 输入法场景下用单次授权即可；选不到就静默回面板，不打断用户
        runCatching { startActivityForResult(picker, REQ_PICK) }.onFailure {
            Timber.w(it, "ocr: launch photo picker failed")
            finish()
        }
    }

    @Deprecated("Activity#onActivityResult 仍是拿到选择器结果最省事的方式（本类不依赖 AppCompat）")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK && requestCode != REQ_PICK_LEGACY) return
        if (resultCode == RESULT_OK) {
            val uri = data?.data
            val callback = pickCallback
            if (uri != null && callback != null) {
                runCatching { callback.invoke(uri) }
                    .onFailure { Timber.w(it, "ocr: pick callback failed") }
            }
        }
        finish()
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
