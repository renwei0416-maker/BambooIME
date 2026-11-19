// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

/**
 * 分模块诊断日志开关。
 *
 * 默认全部关闭：已开发完成的功能模块不再产生任何日志（既不写文件，也不刷 logcat），
 * 避免 debug 包随着功能变多越来越卡。
 *
 * 排查某个模块时，把它对应的那一行改成 `true`，重新出 debug 包即可；
 * 所有模块一起排查就改 [ALL]。
 *
 * 这些常量必须是 `const val`：编译期常量才能让调用点的 `if (开关)` 被折叠掉，
 * 正式包（release）里连同字符串拼接一起消失。
 */
object LogSwitches {
    /** 手写板：识别、笔迹、面板、符号页 */
    const val HANDWRITING = false

    /** 拍照识字：取景、识别、裁剪、卡片 */
    const val OCR = false

    /**
     * 拍照识字把取景图写成 PNG 到 `ocr-debug/`（逐帧写盘，最重的一项）。
     * 只在排查「图本身有没有字」时才打开。
     */
    const val OCR_DUMP = false

    /** 回退与 AI：AI 请求/响应、模式切换、AI 面板 */
    const val AI = false

    /** 文曲星：查询、命中、提示区刷新 */
    const val WENQUXING = false

    /** 提示区（文曲星 + 外语学习共用那块）布局与展开 */
    // debug 排查用：设为 true 时 tips 日志会落盘到 Documents/BambooIME[Debug]/logs
    const val TIPS = false

    /** 外语学习：词库加载、查询 */
    const val GLOSSARY = false

    /** 方案包：释放、覆盖、写 custom、部署 */
    const val WANXIANG = false

    /** 语音：sherpa / asrkb / 语音发 AI */
    const val VOICE = false

    /** 引擎与键盘：输入视图、窗口挂载、按键分发 */
    const val CORE = false

    /** 一键全开：排查时先试这个 */
    const val ALL = false
}
