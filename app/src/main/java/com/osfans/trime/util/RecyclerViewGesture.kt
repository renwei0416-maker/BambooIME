package com.osfans.trime.util

import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 列表拖到边界（最顶或最底）松手后，RecyclerView 有可能停在 SCROLL_STATE_SETTLING / DRAGGING。
 * 下一个 ACTION_DOWN 进来时，它会把这次触摸当成“继续滑动”直接拦截（onInterceptTouchEvent 返回
 * true），子项（候选行、符号格、分类页签）根本收不到 DOWN，表现就是“整片区域点不动”，
 * 再拖一下留出滑动余量、让它真正滚一次之后又恢复正常。
 *
 * 这里做两件事：
 *  1. 关掉边界回弹（OVER_SCROLL_NEVER），去掉最容易把状态卡在 SETTLING 的那条路径；
 *  2. 每次按下时若状态不是 IDLE，先把滚动停掉并复位成 IDLE，再让事件正常往下传。
 */
fun RecyclerView.resetStuckScrollOnTouch() {
    overScrollMode = View.OVER_SCROLL_NEVER
    addOnItemTouchListener(
        object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN &&
                    rv.scrollState != RecyclerView.SCROLL_STATE_IDLE
                ) {
                    // stopScroll() 内部就是 setScrollState(IDLE) + 停掉回弹/惯性，正是我们要的。
                    // （setScrollState 本身是包内可见，外部调不到。）
                    runCatching { rv.stopScroll() }
                }
                // 返回 false：不参与拦截，事件该怎么走还怎么走
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
        },
    )
}
