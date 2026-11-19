// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

import android.util.Log
import com.osfans.trime.BuildConfig
import com.osfans.trime.data.base.DataManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 分模块诊断日志。
 *
 * 用法：`BambooLog.ocr.d("tag", "message")`、`BambooLog.hw.w("tag", "...", e)`。
 *
 * 每个模块的开关在 [LogSwitches] 里，默认全关 —— 关着的模块连字符串都不会被用上，
 * 也不写文件、不刷 logcat。排查时只开需要的那一个。
 *
 * 打开后行为与以前的 `HandwritingLog` 一致：
 * - logcat 前缀 `bamboo/<module>`；
 * - 同时追加到 `<Documents/BambooIME[Debug]>/logs/handwriting-yyyyMMdd.log`
 *   （沿用旧文件名，方便以前的取日志脚本继续用）。
 *
 * 文件写入只在 `BuildConfig.BAMBOO_LOG_TO_FILE`（debug 为 true、release 为 false）时发生，
 * 且还要该模块开关为 true。
 */
object BambooLog {
    private const val TAG = "bamboo"

    /** 是否允许落盘：debug 为编译期 true，release 为 false（R8 会把整段折叠掉）。 */
    internal const val FILE_ENABLED: Boolean = BuildConfig.BAMBOO_LOG_TO_FILE

    // ---- 各模块入口 ----------------------------------------------------------
    @JvmField val hw = BambooLogModule("hw", LogSwitches.HANDWRITING || LogSwitches.ALL)
    @JvmField val ocr = BambooLogModule("ocr", LogSwitches.OCR || LogSwitches.ALL)
    @JvmField val ai = BambooLogModule("ai", LogSwitches.AI || LogSwitches.ALL)
    @JvmField val wq = BambooLogModule("wq", LogSwitches.WENQUXING || LogSwitches.ALL)
    @JvmField val tips = BambooLogModule("tips", LogSwitches.TIPS || LogSwitches.ALL)
    @JvmField val gl = BambooLogModule("gl", LogSwitches.GLOSSARY || LogSwitches.ALL)
    @JvmField val wx = BambooLogModule("wx", LogSwitches.WANXIANG || LogSwitches.ALL)
    @JvmField val voice = BambooLogModule("voice", LogSwitches.VOICE || LogSwitches.ALL)
    @JvmField val core = BambooLogModule("core", LogSwitches.CORE || LogSwitches.ALL)
    @JvmField val shot = BambooLogModule("shot", LogSwitches.SHOT || LogSwitches.ALL)

    // ---- 落盘 ----------------------------------------------------------------
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val lock = Any()

    @Volatile
    private var file: File? = null

    private fun target(): File? {
        if (!FILE_ENABLED) return null
        file?.let { return it }
        return runCatching {
            val dir = File(DataManager.defaultDataDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "handwriting-${dayFmt.format(Date())}.log").also { file = it }
        }.getOrNull()
    }

    internal fun append(module: String, level: String, tag: String, msg: String) {
        val f = target() ?: return
        val line = "${timeFmt.format(Date())} $level/$module/$tag: $msg\n"
        synchronized(lock) {
            runCatching { f.appendText(line) }
        }
    }
}

/** 单个模块的日志出口；[enabled] 为 false 时所有方法第一行就返回。 */
class BambooLogModule internal constructor(
    private val module: String,
    private val enabled: Boolean,
) {
    fun d(tag: String, msg: String) {
        if (!enabled) return
        Log.d(TAG_OF, "$module/$tag: $msg")
        BambooLog.append(module, "D", tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (!enabled) return
        Log.w(TAG_OF, "$module/$tag: $msg", t)
        val detail = t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        BambooLog.append(module, "W", tag, msg + detail)
    }

    private companion object {
        const val TAG_OF = "bamboo"
    }
}
