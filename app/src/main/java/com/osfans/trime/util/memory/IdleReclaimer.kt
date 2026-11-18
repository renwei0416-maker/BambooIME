/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util.memory

import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.osfans.trime.data.prefs.MemoryPrefs
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * 空闲释放器：几个「加载贵、加载慢、但不是一直在用」的模型靠它统一管理。
 *
 * 每个模块注册一条 [Entry]，说明：
 *  * 空闲多久可以释放（统一由设置项「保留时长」给出，[Entry.pinned] 为真时不释放）；
 *  * 当前是否正在被使用（[Entry.isBusy]），使用中一律跳过；
 *  * 怎么释放（[Entry.release]）。
 *
 * 释放时机有四个来源：周期扫描、系统内存紧张、息屏、设置页的「立即释放」。
 * 释放后不主动重新加载，下一次真正需要时由各模块自行懒加载。
 */
object IdleReclaimer {
    /** 最近一次使用的时间（[SystemClock.elapsedRealtime]）。 */
    private val lastUsed = ConcurrentHashMap<String, Long>()

    /** 释放后允许再次被自动释放的冷却期，避免反复加载抖动。 */
    private const val RELEASE_COOLDOWN_MS = 30_000L

    /** 一次使用后至少保留这么久，避免「用一下放一下」。 */
    private const val MIN_ALIVE_MS = 60_000L

    private const val SWEEP_INTERVAL_MS = 30_000L

    private val released = ConcurrentHashMap<String, Long>()

    class Entry(
        val name: String,
        val label: String,
        val isBusy: () -> Boolean,
        val release: () -> Unit,
        /** 常驻：跳过空闲释放（仍可被「立即释放」清掉）。 */
        val pinned: () -> Boolean = { false },
    )

    private val entries = LinkedHashMap<String, Entry>()

    private val handler = Handler(Looper.getMainLooper())

    private var started = false

    private val sweeper =
        object : Runnable {
            override fun run() {
                sweep()
                handler.postDelayed(this, SWEEP_INTERVAL_MS)
            }
        }

    /** 注册一个可回收的模块；重复注册同名模块以最后一次为准。 */
    fun register(entry: Entry) {
        entries[entry.name] = entry
        if (!lastUsed.containsKey(entry.name)) {
            lastUsed[entry.name] = SystemClock.elapsedRealtime()
        }
    }

    /** 启动周期扫描；重复调用无副作用。 */
    fun start() {
        if (started) return
        started = true
        handler.postDelayed(sweeper, SWEEP_INTERVAL_MS)
    }

    /** 记录一次真实使用。 */
    fun touch(name: String) {
        lastUsed[name] = SystemClock.elapsedRealtime()
        released.remove(name)
        start()
    }

    /** 立即释放某个模块；正在使用中则跳过。 */
    fun releaseNow(name: String): Boolean {
        val entry = entries[name] ?: return false
        if (entry.isBusy()) return false
        val ok =
            runCatching { entry.release() }
                .onFailure { Timber.w(it, "IdleReclaimer: release %s failed", name) }
                .isSuccess
        if (ok) {
            released[name] = SystemClock.elapsedRealtime()
            Timber.i("IdleReclaimer: released %s", name)
        }
        return ok
    }

    /** 释放所有空闲模块（忽略「常驻」开关，用于息屏与「立即释放」）。 */
    fun releaseAll(ignorePinned: Boolean = false): List<String> {
        val done = mutableListOf<String>()
        entries.values.forEach { entry ->
            if (!ignorePinned && entry.pinned()) return@forEach
            if (entry.isBusy()) return@forEach
            if (releaseNow(entry.name)) done.add(entry.label)
        }
        return done
    }

    /** 系统内存紧张时调用：只释放非繁忙模块，不受保留时长限制。 */
    fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        val done = releaseAll()
        if (done.isNotEmpty()) Timber.i("IdleReclaimer: trim(%d) released %s", level, done)
    }

    /** 当前持有（已加载）的模块中文名，供设置页显示。 */
    /** 模型是否仍驻留在内存（已加载且未被释放）。 */
    fun isHeld(name: String): Boolean = lastUsed.containsKey(name) && !released.containsKey(name)

    fun heldLabels(): List<String> = entries.values.filter { lastUsed.containsKey(it.name) && !released.containsKey(it.name) }.map { it.label }

    private fun sweep() {
        if (!MemoryPrefs.idleRelease) return
        val keepAliveMs = MemoryPrefs.keepAliveMinutes * 60_000L
        val now = SystemClock.elapsedRealtime()
        entries.values.forEach { entry ->
            if (entry.pinned()) return@forEach
            val used = lastUsed[entry.name] ?: return@forEach
            val idle = now - used
            val releasedAt = released[entry.name]
            if (releasedAt != null && now - releasedAt < RELEASE_COOLDOWN_MS) return@forEach
            if (idle < keepAliveMs || idle < MIN_ALIVE_MS) return@forEach
            if (entry.isBusy()) return@forEach
            releaseNow(entry.name)
        }
    }
}
