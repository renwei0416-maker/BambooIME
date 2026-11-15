/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.wanxiang

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「打不出字」时贴在键盘上方那张一次性提示卡。
 *
 * 只在 [com.osfans.trime.core.Rime.isEmpty] 为真（当前没有任何可用方案）时显示，
 * 用户选过一次之后由 `WanxiangPrefs.hintState` 记住，不再打扰。
 *
 * 颜色固定（深灰底 + 米白字 + 酒红主按钮），不跟主题，避免在浅色主题上糊成一片。
 */
object SchemaHintCard {
    private const val BG = 0xFF232326.toInt()
    private const val TITLE = 0xFFF2EFE6.toInt()
    private const val BODY = 0xFFCFCAC0.toInt()
    private const val ACCENT = 0xFF8E3B30.toInt()

    fun bind(
        host: LinearLayout,
        hasPackage: Boolean,
        onGoWizard: () -> Unit,
        onOpenFolder: () -> Unit,
        onOpenPackageList: () -> Unit,
        onSelfConfigConfirmed: () -> Unit,
    ) {
        val ctx = host.context
        host.removeAllViews()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG)
                cornerRadius = dp(ctx, 12).toFloat()
            }
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }

        // 标题行：标题 + 右上角 ×
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(ctx).apply {
                text = if (hasPackage) "存在方案包，还需配置" else "还没有输入方案"
                setTextColor(TITLE)
                textSize = 15f
                isSingleLine = true
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        titleRow.addView(
            TextView(ctx).apply {
                text = "×"
                setTextColor(BODY)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 8), 0, dp(ctx, 4), 0)
                setOnClickListener { onSelfConfigConfirmed() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(titleRow, matchWidth())

        card.addView(
            TextView(ctx).apply {
                text = "进设置向导设置好才能打字"
                setTextColor(BODY)
                textSize = 13f
                setPadding(0, dp(ctx, 4), 0, 0)
            },
            matchWidth(),
        )

        // 两个按钮同款（用户要求「我自己配置方案」也是一个正常选项，不做弱化）
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 10), 0, 0)
        }
        buttons.addView(
            actionButton(ctx, "去配置", ACCENT, Color.WHITE, onGoWizard),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(ctx, 8)
            },
        )
        buttons.addView(
            actionButton(
                ctx,
                "我自己配置方案",
                Color.TRANSPARENT,
                TITLE,
                null,
            ).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#33333A"))
                    cornerRadius = dp(ctx, 10).toFloat()
                }
                setOnClickListener { showSelfConfig(ctx, card, buttons, onOpenFolder, onOpenPackageList, onSelfConfigConfirmed) }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.addView(buttons, matchWidth())

        host.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 8))
            },
        )
        host.visibility = View.VISIBLE
    }

    /** 「我自己配置方案」展开的那一小段：路径 + 打开文件夹 / 去方案包管理 / 知道了。 */
    private fun showSelfConfig(
        ctx: Context,
        card: LinearLayout,
        buttons: LinearLayout,
        onOpenFolder: () -> Unit,
        onOpenPackageList: () -> Unit,
        onSelfConfigConfirmed: () -> Unit,
    ) {
        buttons.visibility = View.GONE

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 10), 0, 0)
        }
        box.addView(
            TextView(ctx).apply {
                text = "方案包放在一个目录里，一个包一个文件夹：\n" +
                    com.osfans.trime.data.base.DataManager.userDataBaseDir.absolutePath
                setTextColor(BODY)
                textSize = 12f
            },
            matchWidth(),
        )
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 8), 0, 0)
        }
        row.addView(
            actionButton(ctx, "打开文件夹", Color.parseColor("#33333A"), TITLE, onOpenFolder),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(ctx, 6)
            },
        )
        row.addView(
            actionButton(ctx, "去方案包管理", Color.parseColor("#33333A"), TITLE, onOpenPackageList),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(ctx, 6)
            },
        )
        row.addView(
            actionButton(ctx, "知道了", ACCENT, Color.WHITE, onSelfConfigConfirmed),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        box.addView(row, matchWidth())
        card.addView(box, matchWidth())
    }

    private fun actionButton(
        ctx: Context,
        label: String,
        bg: Int,
        fg: Int,
        onClick: (() -> Unit)?,
    ): Button = Button(ctx).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(fg)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(ctx, 10).toFloat()
        }
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
