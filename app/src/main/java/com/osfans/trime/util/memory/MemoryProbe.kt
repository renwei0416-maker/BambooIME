/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process

/**
 * 进程内存采样。
 *
 * Android 只能给出整进程的内存（PSS），无法按模块拆分，所以「某个模型占多少」
 * 用「加载前后各采一次、求差值」的方式实测，得到的是该次加载的增量而不是绝对值。
 */
object MemoryProbe {
    /** 加载后等多久再采第二次（毫秒）：让 mmap 的页真正读入。 */
    private const val SETTLE_DELAY_MS = 300L

    class Snapshot(
        val totalMb: Int,
        val nativeMb: Int,
        val javaMb: Int,
        val codeMb: Int,
    )

    /** 采样当前进程内存；失败时返回全 0，调用方按「未知」处理。 */
    fun snapshot(context: Context): Snapshot {
        val info = memoryInfo(context) ?: return Snapshot(0, 0, 0, 0)
        return Snapshot(
            totalMb = info.totalPss / 1024,
            nativeMb = info.nativePss / 1024,
            javaMb = info.dalvikPss / 1024,
            codeMb = info.nativePrivateDirty / 1024,
        )
    }

    /**
     * 执行 [block] 并返回这次加载带来的内存增量（MB，四舍五入，最小 0）。
     *
     * 增量口径是整进程 PSS 差值，因此会受同一时刻其它分配影响；作为参考值使用。
     */
    fun measureDeltaMb(
        context: Context,
        block: () -> Unit,
    ): Int {
        val before = memoryInfo(context)?.totalPss ?: run {
            block()
            return 0
        }
        block()
        val first = memoryInfo(context)?.totalPss ?: return 0
        // 模型权重多半是 mmap 进来的，加载那一刻页还没真正读入（按需缺页），
        // 立刻采样经常是 0。等一会儿让缺页落定，再采一次，取两次里较大的。
        runCatching { Thread.sleep(SETTLE_DELAY_MS) }
        val second = memoryInfo(context)?.totalPss ?: first
        val deltaKb = maxOf(first, second) - before
        return if (deltaKb <= 0) 0 else (deltaKb + 512) / 1024
    }

    private fun memoryInfo(context: Context): Debug.MemoryInfo? =
        runCatching {
            val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
        }.getOrNull()
}
