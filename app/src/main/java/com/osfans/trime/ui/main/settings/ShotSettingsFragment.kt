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
 * 所有值都存在 [ShotAiPrefs] 里（独立 SharedPreferences `bamboo_shot_ai`），改完立即生效。
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
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addPreference(permissionCategory(ctx))
            addPreference(visionCategory(ctx))
            addPreference(actionsCategory(ctx))
            addPreference(hintCategory(ctx))
        }
    }

    // ---------------- 权限与状态 ----------------

    private fun permissionCategory(context: Context): PreferenceCategory =
        PreferenceCategory(context).apply {
            title = "权限与状态"
            addPreference(
                Preference(context).apply {
                    title = "无障碍截图权限"
                    summary =
                        if (isShotServiceEnabled(context)) {
                            "已开启，可以直接截图"
                        } else {
                            "未开启。点这里去「设置 → 辅助功能 → 无障碍 → 已下载的服务 → BambooIME」里打开「截图」"
                        }
                    isIconSpaceReserved = false
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

    private fun visionCategory(context: Context): PreferenceCategory =
        PreferenceCategory(context).apply {
            title = "AI 视觉（截图问图用）"
            addPreference(
                Preference(context).apply {
                    title = "接口地址"
                    summary = ShotAiPrefs.baseUrl
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        editTextDialog(context, "接口地址", "OpenAI 兼容的 /chat/completions 地址", ShotAiPrefs.baseUrl) {
                            ShotAiPrefs.baseUrl = it
                            rebuildScreen()
                        }
                        true
                    }
                },
            )
            addPreference(
                Preference(context).apply {
                    title = "模型"
                    summary = ShotAiPrefs.model + "（必须支持看图）"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        editTextDialog(context, "模型", "例如 deepseek-flash / gpt-4o-mini", ShotAiPrefs.model) {
                            ShotAiPrefs.model = it
                            rebuildScreen()
                        }
                        true
                    }
                },
            )
            addPreference(
                Preference(context).apply {
                    title = "API Key"
                    val has = ShotAiPrefs.apiKey.isNotBlank()
                    summary = if (has) "已设置（加密保存在本机），点这里修改" else "未设置，点这里填写"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        editTextDialog(context, "API Key", "只保存在本机（Android Keystore 加密）", "") {
                            ShotAiPrefs.apiKey = it
                            rebuildScreen()
                        }
                        true
                    }
                },
            )
            addPreference(
                Preference(context).apply {
                    title = "清除 API Key"
                    summary = "换机或怀疑泄露时点这里"
                    isIconSpaceReserved = false
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

    private fun actionsCategory(context: Context): PreferenceCategory =
        PreferenceCategory(context).apply {
            title = "AI 动作"
            addPreference(
                Preference(context).apply {
                    title = "动作就是扇形菜单上的按钮"
                    summary = "名称最多 4 个字，字号会自动缩；停用的动作不会出现在菜单里"
                    isIconSpaceReserved = false
                    isSelectable = false
                },
            )
            ShotAiPrefs.actions.forEach { a ->
                addPreference(
                    Preference(context).apply {
                        title = a.name + if (a.enabled) "" else "（已停用）"
                        summary = a.prompt.replace("\n", " ").take(60)
                        isIconSpaceReserved = false
                        setOnPreferenceClickListener {
                            actionDialog(context, a.id)
                            true
                        }
                    },
                )
            }
            addPreference(
                Preference(context).apply {
                    title = "＋ 新增动作"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        addActionDialog(context)
                        true
                    }
                },
            )
            addPreference(
                Preference(context).apply {
                    title = "恢复内置动作"
                    summary = "恢复成「问 AI」「翻译」两条"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        ShotAiPrefs.resetActions()
                        rebuildScreen()
                        true
                    }
                },
            )
        }

    private fun hintCategory(context: Context): PreferenceCategory =
        PreferenceCategory(context).apply {
            title = "说明"
            addPreference(
                Preference(context).apply {
                    title = "菜单项多了会自动缩小"
                    summary = "扇形半径和圆的大小会先自动缩小保证一屏放得下；超过容量才会出现「更多」分页"
                    isIconSpaceReserved = false
                    isSelectable = false
                },
            )
        }

    // ---------------- 对话框 ----------------

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
                    minLines = 3
                }
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
        val wrap =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
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

    private companion object {
        private const val TAG = "ShotSettings"
    }
}
