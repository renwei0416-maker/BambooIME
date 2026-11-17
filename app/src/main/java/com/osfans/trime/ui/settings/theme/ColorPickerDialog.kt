/*
 * SPDX-FileCopyrightText: 2026 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settings.theme

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 取色对话框：色相条 + 饱和度/明度方块 + 透明度滑条 + 十六进制输入 + 几个预设色。
 */
object ColorPickerDialog {
    private val presets = intArrayOf(
        0xFF1F2023.toInt(), 0xFF33363B.toInt(), 0xFF15171C.toInt(), 0xFFE3E6EB.toInt(),
        0xFF8AB4F8.toInt(), 0xFFF07E6C.toInt(), 0xFFF9CD9C.toInt(), 0xFF93AAD6.toInt(),
        0xFFC9B6D4.toInt(), 0xFF8E3B30.toInt(), 0xFFFFFFFF.toInt(), 0x00000000,
    )

    fun show(
        context: Context,
        title: String,
        initial: Int,
        onPick: (Int) -> Unit,
    ) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
        }

        val picker = ColorPickerView(context).apply { color = initial }
        root.addView(
            picker,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // 透明度
        val alphaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val alphaLabel = TextView(context).apply {
            text = "透明度"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFB9BEC7.toInt())
        }
        val alphaBar = SeekBar(context).apply {
            max = 255
            progress = Color.alpha(initial)
        }
        val alphaValue = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFB9BEC7.toInt())
            minWidth = dp(34)
            gravity = Gravity.END
            text = alphaBar.progress.toString()
        }
        alphaRow.addView(alphaLabel, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        alphaRow.addView(alphaBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        alphaRow.addView(alphaValue, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(alphaRow)

        // 十六进制 + 预览块
        val hexRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val hex = EditText(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setSingleLine(true)
            setText(hexOf(initial))
            setSelection(text.length)
        }
        val swatch = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(initial)
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        hexRow.addView(hex, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hexRow.addView(swatch, LinearLayout.LayoutParams(dp(44), dp(28)).apply { marginStart = dp(10) })
        root.addView(hexRow)

        // 预设色
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        var current = initial
        fun sync(fromPicker: Boolean) {
            swatch.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(current)
                setStroke(dp(1), 0x33FFFFFF)
            }
            if (!fromPicker) {
                val t = hexOf(current)
                if (hex.text.toString() != t) {
                    hex.setText(t)
                    hex.setSelection(t.length)
                }
            } else {
                val t = hexOf(current)
                if (hex.text.toString() != t) {
                    hex.setText(t)
                    hex.setSelection(t.length)
                }
            }
            picker.alphaValue = Color.alpha(current)
            alphaBar.progress = Color.alpha(current)
            alphaValue.text = Color.alpha(current).toString()
        }

        for (i in presets.indices) {
            val c = presets[i]
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat()
                    setColor(c)
                    setStroke(dp(1), 0x33FFFFFF)
                }
            }
            val lp = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                marginEnd = dp(8)
            }
            chips.addView(dot, lp)
            dot.setOnClickListener {
                current = c
                picker.color = c
                sync(fromPicker = false)
            }
            if (i == presets.lastIndex) lp.marginEnd = 0
        }
        root.addView(chips)

        picker.onColorChanged = {
            current = it
            sync(fromPicker = true)
        }
        alphaBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                current = Color.argb(progress, Color.red(current), Color.green(current), Color.blue(current))
                alphaValue.text = progress.toString()
                sync(fromPicker = false)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        hex.addTextChangedListener(object : TextWatcher {
            private var self = false
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (self) return
                val parsed = parseHex(s?.toString()) ?: return
                self = true
                current = parsed
                picker.color = parsed
                swatch.background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(parsed)
                    setStroke(dp(1), 0x33FFFFFF)
                }
                picker.alphaValue = Color.alpha(parsed)
                alphaBar.progress = Color.alpha(parsed)
                alphaValue.text = Color.alpha(parsed).toString()
                self = false
            }
        })

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("确定") { _, _ -> onPick(current) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun hexOf(c: Int): String =
        String.format("#%08X", c)

    private fun parseHex(raw: String?): Int? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.startsWith("0x", true)) s = "#" + s.substring(2)
        if (!s.startsWith("#")) s = "#$s"
        val hex = s.substring(1)
        return runCatching {
            when (hex.length) {
                8 -> hex.toLong(16).toInt()
                6 -> 0xFF000000.toInt() or hex.toLong(16).toInt()
                else -> null
            }
        }.getOrNull()
    }
}
