/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.osfans.trime.actions.shot.ShotAiPrefs
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.rebuildPreferencesKeepingScroll

/**
 * 「截图」设置页。
 *
 * 三块内容：
 * 1. 权限与状态：无障碍截图是否已开启，点一下跳到系统无障碍设置。
 * 2. AI 视觉：截图问图用的接口地址 / 模型 / Key。**和「回退与AI」的文本配置分开**，
 *    因为看图必须用支持视觉的模型，和文本那套经常不是同一个。
 * 3. AI 动作：每个动作＝扇形菜单上的一项（名称最多 4 字 + 提示词），可增可改可停用可删。
 *
 * 注意：`PreferenceCategory(context)` 建出来时还没有 `PreferenceManager`，
 * 必须先 `screen.addPreference(category)` 挂到屏幕上，再往里加子项，
 * 否则 `addPreference` 会抛 NPE（`getNextId()` on a null reference）。
 */
class ShotSettingsFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        rebuildPreferencesKeepingScroll { buildScreen() }
    }

    private fun buildScreen() {
        val ctx = requireContext()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(ctx).apply {
                addCategory(ctx, "权限与状态") { fillPermission(ctx, it) }
                addCategory(ctx, "AI 视觉（截图问图用）") { fillVision(ctx, it) }
                addCategory(ctx, "AI 动作") { fillActions(ctx, it) }
                addCategory(ctx, "说明") { fillHints(ctx, it) }
            }
    }

    /** 先挂到屏幕上（此时才有 PreferenceManager），再填子项。 */
    private fun PreferenceScreen.addCategory(
        context: Context,
        title: String,
        fill: (PreferenceCategory) -> Unit,
    ) {
        val category = PreferenceCategory(context).apply { this.title = title }
        addPreference(category)
        fill(category)
    }

    // ---------------- 权限与状态 ----------------

    private fun fillPermission(
        context: Context,
        category: PreferenceCategory,
    ) {
        category.addPreference(
            row(context, "无障碍截图权限") {
                summary =
                    if (isShotServiceEnabled(context)) {
                        "已开启，可以直接截图"
                    } else {
                        "未开启。点这里去「设置 → 辅助功能 → 无障碍 → 已下载的服务 → BambooIME」里打开「截图」"
                    }
                setOnPreferenceClickListener {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }.onFailure {
                        Toast.makeText(context, "打不开系统无障碍设置", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            },
        )
    }

    // ---------------- AI 视觉配置 ----------------

    private fun fillVision(
        context: Context,
        category: PreferenceCategory,
    ) {
        category.addPreference(
            row(context, "接口地址") {
                summary = ShotAiPrefs.baseUrl
                setOnPreferenceClickListener {
                    editTextDialog(context, "接口地址", "OpenAI 兼容的 /chat/completions 地址", ShotAiPrefs.baseUrl) {
                        ShotAiPrefs.baseUrl = it
                        rebuildScreen()
                    }
                    true
                }
            },
        )
        category.addPreference(
            row(context, "模型") {
                summary = ShotAiPrefs.model + "（必须支持看图）"
                setOnPreferenceClickListener {
                    editTextDialog(context, "模型", "例如 deepseek-flash / gpt-4o-mini", ShotAiPrefs.model) {
                        ShotAiPrefs.model = it
                        rebuildScreen()
                    }
                    true
                }
            },
        )
        category.addPreference(
            row(context, "API Key") {
                summary =
                    if (ShotAiPrefs.apiKey.isNotBlank()) {
                        "已设置（加密保存在本机），点这里修改"
                    } else {
                        "未设置，点这里填写"
                    }
                setOnPreferenceClickListener {
                    editTextDialog(context, "API Key", "只保存在本机（Android Keystore 加密）", "") {
                        ShotAiPrefs.apiKey = it
                        rebuildScreen()
                    }
                    true
                }
            },
        )
        category.addPreference(
            row(context, "清除 API Key") {
                summary = "换机或怀疑泄露时点这里"
                setOnPreferenceClickListener {
                    ShotAiPrefs.apiKey = ""
                    Toast.makeText(context, "已清除", Toast.LENGTH_SHORT).show()
                    rebuildScreen()
                    true
                }
            },
        )
    }

    // ---------------- AI 动作 ----------------

    private fun fillActions(
        context: Context,
        category: PreferenceCategory,
    ) {
        category.addPreference(
            row(context, "动作就是扇形菜单上的按钮") {
                summary = "名称最多 4 个字，字号会自动缩；停用的动作不会出现在菜单里"
                isSelectable = false
            },
        )
        ShotAiPrefs.actions.forEach { a ->
            category.addPreference(
                row(context, a.name + if (a.enabled) "" else "（已停用）") {
                    summary = a.prompt.replace("\n", " ").take(60)
                    setOnPreferenceClickListener {
                        actionDialog(context, a.id)
                        true
                    }
                },
            )
        }
        category.addPreference(
            row(context, "＋ 新增动作") {
                setOnPreferenceClickListener {
                    addActionDialog(context)
                    true
                }
            },
        )
        category.addPreference(
            row(context, "恢复内置动作") {
                summary = "恢复成「问 AI」「翻译」两条"
                setOnPreferenceClickListener {
                    ShotAiPrefs.resetActions()
                    rebuildScreen()
                    true
                }
            },
        )
    }

    private fun fillHints(
        context: Context,
        category: PreferenceCategory,
    ) {
        category.addPreference(
            row(context, "菜单项多了会自动缩小") {
                summary = "扇形半径和圆的大小会先自动缩小保证一屏放得下；超过容量才会出现「更多」分页"
                isSelectable = false
            },
        )
        category.addPreference(
            row(context, "识字与 AI 动作的图从哪里来") {
                summary = "都是当前选框里的那一块；没选框时点它们会先提示你拖一个框"
                isSelectable = false
            },
        )
    }

    // ---------------- 小工具 ----------------

    private fun row(
        context: Context,
        title: String,
        block: Preference.() -> Unit = {},
    ): Preference =
        Preference(context).apply {
            this.title = title
            isIconSpaceReserved = false
            block()
        }

    private fun actionDialog(
        context: Context,
        id: String,
    ) {
        val a = ShotAiPrefs.actions.firstOrNull { it.id == id } ?: return
        val items =
            arrayOf(
                "改名称（当前：" + a.name + "）",
                "改提示词",
                if (a.enabled) "停用" else "启用",
                "删除",
            )
        AlertDialog
            .Builder(context)
            .setTitle(a.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 ->
                        editTextDialog(context, "名称", "最多 4 个字", a.name) {
                            ShotAiPrefs.updateAction(id, name = it.take(4))
                            rebuildScreen()
                        }
                    1 ->
                        editTextDialog(context, "提示词", "发给视觉模型的指令", a.prompt, multiline = true) {
                            ShotAiPrefs.updateAction(id, prompt = it)
                            rebuildScreen()
                        }
                    2 -> {
                        ShotAiPrefs.updateAction(id, enabled = !a.enabled)
                        rebuildScreen()
                    }
                    3 -> {
                        ShotAiPrefs.removeAction(id)
                        rebuildScreen()
                    }
                }
            }.setNegativeButton("取消", null)
            .show()
    }

    private fun addActionDialog(context: Context) {
        editTextDialog(context, "新增动作", "名称（最多 4 个字）", "") { name ->
            val n = name.trim().take(4)
            if (n.isEmpty()) {
                Toast.makeText(context, "名称不能为空", Toast.LENGTH_SHORT).show()
            } else {
                editTextDialog(context, "提示词", "发给视觉模型的指令", "") { prompt ->
                    ShotAiPrefs.addAction(n, prompt)
                    rebuildScreen()
                }
            }
        }
    }

    private fun editTextDialog(
        context: Context,
        title: String,
        hint: String,
        value: String,
        multiline: Boolean = false,
        onOk: (String) -> Unit,
    ) {
        val input =
            EditText(context).apply {
                setText(value)
                this.hint = hint
                setSingleLine(!multiline)
                if (multiline) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 4
                }
            }
        val wrap =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input)
            }
        AlertDialog
            .Builder(context)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("确定") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun rebuildScreen() {
        rebuildPreferencesKeepingScroll { buildScreen() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 无障碍里是否已经启用了我们自己的截图服务。 */
    private fun isShotServiceEnabled(context: Context): Boolean {
        val enabled =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
        return enabled.contains("ShotService")
    }
}
