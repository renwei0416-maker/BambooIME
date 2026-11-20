/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.shot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 桌面常驻的「截图浮球」。
 *
 * 独立于 [ShotActivity] 里那个菜单球：这里是无障碍浮层，没有输入框也能用。
 * 点开只有三项（自下而上）：退出 / 返回 / 截图；「退出」会顺手关掉设置里的开关。
 */
object ShotBallOverlay {
    private fun dp(v: Int): Float = v * densityOf()
    private fun densityOf(): Float =
        com.osfans.trime.TrimeApplication.getInstance().resources.displayMetrics.density

    private const val BALL_DP = 48
    private const val LABEL_EXIT = "退出"
    private const val LABEL_BACK = "返回"
    private const val LABEL_SHOT = "截图"

    private var wm: WindowManager? = null
    private var ballView: BallView? = null
    private var ballLp: WindowManager.LayoutParams? = null
    private var fanHost: FrameLayout? = null
    private var fan: ShotFanMenu? = null
    private var screenW = 0
    private var screenH = 0
    private var ballSize = 0

    val isShowing: Boolean get() = ballView != null

    /** 显示浮球；没连上无障碍服务时什么也不做。 */
    fun show() {
        val service = ShotService.instance ?: return
        if (ballView != null) return
        val w = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        wm = w
        val metrics = service.resources.displayMetrics
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        ballSize = dp(BALL_DP).toInt()

        val view = BallView(service)
        val lp =
            WindowManager.LayoutParams(
                ballSize,
                ballSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
        lp.gravity = Gravity.TOP or Gravity.START
        val defX = screenW - ballSize - dp(6).toInt()
        val defY = (screenH * 2f / 3f).toInt()
        val maxX = (screenW - ballSize).coerceAtLeast(1)
        val maxY = (screenH - ballSize).coerceAtLeast(1)
        lp.x = if (ShotBallPrefs.posX >= 0f) (ShotBallPrefs.posX * maxX).toInt() else defX
        lp.y = if (ShotBallPrefs.posY >= 0f) (ShotBallPrefs.posY * maxY).toInt() else defY
        view.tag = Point(lp.x, lp.y)

        view.onTap = { toggleMenu(service) }
        view.onDragged = { dx, dy ->
            val p = view.tag as Point
            lp.x = (p.x + dx).coerceIn(0, maxX)
            lp.y = (p.y + dy).coerceIn(0, maxY)
            // 必须把新位置写回 tag：否则下一次移动还从旧起点加上这小段位移，位置被反复抵消，
            // 表现就是「浮球拖不动、只原地抖」。
            p.x = lp.x
            p.y = lp.y
            runCatching { w.updateViewLayout(view, lp) }
        }
        view.onDragEnd = {
            val p = view.tag as Point
            // 吸附到更近的一侧，让浮球别压着正文
            lp.x = if (lp.x + ballSize / 2 < screenW / 2) 0 else maxX
            runCatching { w.updateViewLayout(view, lp) }
            ShotBallPrefs.posX = lp.x.toFloat() / maxX
            ShotBallPrefs.posY = lp.y.toFloat() / maxY
            p.x = lp.x
            p.y = lp.y
        }

        runCatching { w.addView(view, lp) }
            .onFailure {
                ballView = null
                return
            }
        ballView = view
        ballLp = lp
    }

    /** 收起浮球（不碰设置里的开关）。 */
    fun hide() {
        closeMenu()
        val view = ballView ?: return
        ballView = null
        ballLp = null
        runCatching { wm?.removeView(view) }
    }

    private fun toggleMenu(service: Context) {
        if (fanHost != null) {
            closeMenu()
        } else {
            openMenu(service)
        }
    }

    private fun openMenu(service: Context) {
        val w = wm ?: return
        val lp = ballLp ?: return
        val menu = ShotFanMenu(service)
        val host = FrameLayout(service)
        host.addView(
            menu,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val hostLp =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
        hostLp.gravity = Gravity.TOP or Gravity.START
        menu.onSelect = { index ->
            when (index) {
                0 -> exitBall()
                1 -> closeMenu()
                2 -> {
                    closeMenu()
                    // 先把浮球窗口移除再抓屏，否则浮球会被一起截进图里；
                    // removeView 有一两帧延迟，所以交给 ShotActions 再等一下抓。
                    hide()
                    ShotActions.start(null)
                }
            }
        }
        menu.onDismiss = { closeMenu() }
        runCatching { w.addView(host, hostLp) }.onFailure { return }
        fanHost = host
        fan = menu
        ballView?.opened = true
        val cx = lp.x + ballSize / 2f
        val cy = lp.y + ballSize / 2f
        menu.show(cx, cy, listOf(LABEL_EXIT, LABEL_BACK, LABEL_SHOT))
    }

    private fun closeMenu() {
        val host = fanHost ?: return
        fanHost = null
        fan = null
        runCatching { wm?.removeView(host) }
        ballView?.opened = false
    }

    /** 「退出」：关浮球，并把设置里的开关一起关掉（否则下次开输入法又冒出来）。 */
    private fun exitBall() {
        closeMenu()
        ShotBallPrefs.enabled = false
        hide()
    }
}

/** 浮球本体：48dp 圆 + 自绘裁剪框图标；按下拖动、松手轻点开关菜单。 */
private class BallView(context: Context) :
    View(context) {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFFFFFFFF.toInt()
        }

    var opened = false
        set(v) {
            field = v
            invalidate()
        }

    var onTap: (() -> Unit)? = null
    var onDragged: ((Int, Int) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var moved = false

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        body.color = if (opened) 0xFFB0472A.toInt() else 0xCC000000.toInt()
        canvas.drawCircle(width / 2f, height / 2f, r, body)

        // 裁剪框：四个角括号（和"截图框选"这个动作对得上）
        val half = r * 0.52f
        val arm = r * 0.30f
        val left = width / 2f - half
        val top = height / 2f - half
        val right = width / 2f + half
        val bottom = height / 2f + half
        icon.strokeWidth = r * 0.12f
        // 左上
        canvas.drawLine(left, top + arm, left, top, icon)
        canvas.drawLine(left, top, left + arm, top, icon)
        // 右上
        canvas.drawLine(right - arm, top, right, top, icon)
        canvas.drawLine(right, top, right, top + arm, icon)
        // 右下
        canvas.drawLine(right, bottom - arm, right, bottom, icon)
        canvas.drawLine(right, bottom, right - arm, bottom, icon)
        // 左下
        canvas.drawLine(left + arm, bottom, left, bottom, icon)
        canvas.drawLine(left, bottom, left, bottom - arm, icon)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (kotlin.math.abs(event.rawX - downRawX) > (4f * resources.displayMetrics.density) || kotlin.math.abs(event.rawY - downRawY) > (4f * resources.displayMetrics.density))) {
                    moved = true
                }
                if (moved) {
                    onDragged?.invoke((event.rawX - lastRawX).toInt(), (event.rawY - lastRawY).toInt())
                }
                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) onDragEnd?.invoke() else onTap?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (moved) onDragEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
