// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.actions.handwriting

import android.util.Log
import com.osfans.trime.BuildConfig
import com.osfans.trime.data.base.DataManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 手写板诊断日志。
 *
 * 只有 debug 包会写文件（release 包连方法体都直接返回），因为日志里含有
 * 「挂了哪个窗口 / 有没有输入连接 / 识别出几个候选」这类状态，不适合留在正式版里。
 *
 * 文件位置：`<Documents/BambooIME[Debug]>/logs/handwriting-yyyyMMdd.log`，
 * 用户可以直接从文件管理/共享目录取出来。
 */
object HandwritingLog {
    private const val TAG = "handwriting"

    private val enabled = BuildConfig.DEBUG
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val lock = Any()

    @Volatile
    private var file: File? = null

    private fun target(): File? {
        if (!enabled) return null
        file?.let { return it }
        return runCatching {
            val dir = File(DataManager.defaultDataDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "handwriting-${dayFmt.format(Date())}.log").also { file = it }
        }.getOrNull()
    }

    fun d(tag: String, msg: String) {
        if (!enabled) return
        Log.d(TAG, "$tag: $msg")
        append("D", tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (!enabled) return
        Log.w(TAG, "$tag: $msg", t)
        val detail = t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        append("W", tag, msg + detail)
    }

    private fun append(level: String, tag: String, msg: String) {
        val f = target() ?: return
        val line = "${timeFmt.format(Date())} $level/$tag: $msg\n"
        synchronized(lock) {
            runCatching { f.appendText(line) }
        }
    }
}
