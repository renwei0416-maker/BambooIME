/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.segments

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.osfans.trime.data.theme.Theme

class SegmentsAdapter(
    private val theme: Theme,
    private val onItemClick: () -> Unit,
) : BaseQuickAdapter<String, SegmentsAdapter.ViewHolder>() {

    class ViewHolder(val ui: SegmentUi) : RecyclerView.ViewHolder(ui.root)

    private val selection = mutableSetOf<Int>()

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(SegmentUi(context, theme))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
        item: String?,
    ) {
        val text = item ?: return
        // 空白块本身就是一个块：显示成可见记号，选它就是选那段空白
        holder.ui.textView.text = displayOf(text)
        // 空白写得淡一点，一眼能看出来“这不是词”
        holder.ui.textView.alpha = if (isBlankPiece(text)) 0.55f else 1f
        val isSelected = selection.contains(position)
        holder.ui.update(isSelected)
        holder.itemView.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                val newState = !isSegmentSelected(currentPos)
                setSegmentSelected(currentPos, newState)
                onItemClick.invoke()
            }
        }
    }

    /** 空白块在界面上画成 `␣`（空格）、`⇥`（制表符）、`↵`（换行），太长就折中显示。 */
    private fun displayOf(text: String): String {
        if (text.isEmpty() || !isBlankPiece(text)) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' -> {
                    sb.append('↵')
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                '\n' -> sb.append('↵')
                '\t' -> sb.append('⇥')
                else -> sb.append('␣')
            }
            i++
        }
        return if (sb.length > 6) sb.substring(0, 5) + "…" else sb.toString()
    }

    private fun isBlankPiece(text: String): Boolean =
        text.isNotEmpty() && text.all { it.isWhitespace() }

    val selectionSnapshot: Set<Int>
        get() = selection.toSet()

    fun isSegmentSelected(position: Int): Boolean = selection.contains(position)

    fun setSegmentSelected(position: Int, isSelected: Boolean) {
        if (isSelected) {
            selection.add(position)
        } else {
            selection.remove(position)
        }
        notifyItemChanged(position)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun selectAll() {
        if (isAllSelected) return
        selection.clear()
        selection.addAll(items.indices)
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun deselect() {
        if (selection.isEmpty()) return
        selection.clear()
        notifyDataSetChanged()
    }

    val isAllSelected: Boolean
        get() = selection.size == items.size

    /**
     * 选中的块按原顺序直接拼起来。
     *
     * 块里已经包含空白，所以不需要再补分隔符 —— 选哪几块，上屏的就是那几块的原样。
     */
    val joinedSegments: String
        get() = buildString {
            items.forEachIndexed { i, item ->
                if (isSegmentSelected(i)) append(item)
            }
        }
}
