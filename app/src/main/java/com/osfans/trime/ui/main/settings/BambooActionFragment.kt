/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import com.osfans.trime.R
import com.osfans.trime.actions.bamboo.AiMode
import com.osfans.trime.actions.bamboo.BambooPrefs
import com.osfans.trime.actions.bamboo.BambooTokens
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.util.addPreference
import com.osfans.trime.ui.common.BambooClick
import com.osfans.trime.ui.common.BambooSwitch
import com.osfans.trime.ui.common.rebuildPreferencesKeepingScroll

/**
 * 「回退与AI」设置页：回退 + AI 模式（拍照识字已拆成独立页面）。
 *
 * 刻意不用 PreferenceFragmentCompat 的自动持久化——这些配置存在独立的
 * SharedPreferences（[BambooPrefs]）里，改完立即生效，不需要部署方案。
 */
class BambooActionFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addRestoreAll()

            addSwitch("启用苍竹增强", "总开关，关掉后所有触发码失效", BambooPrefs.enabled) {
                BambooPrefs.enabled = it
            }

            addBambooCategory("回退", "在拼写框打触发码，把刚上屏的字删掉") {
                addSwitch("启用回退", "在拼写框输入触发码后，删掉最近一次上屏的内容", BambooPrefs.revertEnabled) {
                    BambooPrefs.revertEnabled = it
                }
                addPreference(
                    "回退触发码",
                    BambooPrefs.revertTriggersRaw + "（多个用逗号分隔，末位字符是终结键）",
                ) {
                    editText("回退触发码", BambooPrefs.revertTriggersRaw, hint = "例如 qq/ 或 qq/,ss;") {
                        BambooPrefs.revertTriggersRaw = it
                        rebuild()
                    }
                }
                addPreference("一次最多删除的字符数", "${BambooPrefs.revertMaxCodePoints}") {
                    editText("最多删除字符数", BambooPrefs.revertMaxCodePoints.toString(), numeric = true) {
                        it.toIntOrNull()?.let { n -> BambooPrefs.revertMaxCodePoints = n }
                        rebuild()
                    }
                }
            }

            addBambooCategory("AI 服务与发送", "接口、模型、发送方式与触发码") {
                addSwitch("启用 AI 模式", "输入 AI 触发码进入模式，再双击发送键发送", BambooPrefs.aiEnabled) {
                    BambooPrefs.aiEnabled = it
                }
                addPreference("接口地址", BambooPrefs.aiBaseUrl.ifEmpty { "未设置（例如 https://api.openai.com/v1）" }) {
                    editText("接口地址", BambooPrefs.aiBaseUrl, hint = "https://api.openai.com/v1") {
                        BambooPrefs.aiBaseUrl = it
                        rebuild()
                    }
                }
                addPreference("模型", BambooPrefs.aiModel.ifEmpty { "未设置（例如 gpt-4o-mini）" }) {
                    editText("模型名", BambooPrefs.aiModel, hint = "gpt-4o-mini") {
                        BambooPrefs.aiModel = it
                        rebuild()
                    }
                }
                addPreference("API Key", if (BambooPrefs.aiApiKey.isEmpty()) "未设置" else "已设置（${BambooPrefs.aiApiKey.length} 位）") {
                    editText("API Key", BambooPrefs.aiApiKey, password = true) {
                        BambooPrefs.aiApiKey = it
                        rebuild()
                    }
                }
                if (BambooPrefs.aiApiKey.isNotEmpty()) {
                    addPreference(
                        "已加密保存（Android Keystore）",
                        "密钥不出设备、不随备份外泄；换手机后需要重新填写。点此清除",
                    ) {
                        AlertDialog.Builder(context)
                            .setTitle("清除 API Key？")
                            .setMessage("清除后需要重新填写才能继续使用 AI。")
                            .setPositiveButton("清除") { _, _ ->
                                BambooPrefs.aiApiKey = ""
                                rebuild()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
                addSwitch(
                    "双击发送键发送",
                    "关（默认）＝双击「${BambooPrefs.aiSendKey}」不触发，键位照常输入；开＝在 AI 模式下连按两下「${BambooPrefs.aiSendKey}」发送",
                    BambooPrefs.aiDoubleTapEnabled,
                ) {
                    BambooPrefs.aiDoubleTapEnabled = it
                    rebuild()
                }
                addSwitch(
                    "快捷组合键发送",
                    "开（默认）＝按 ${BambooPrefs.aiCombo} 发送（主要给外接键盘）；关＝不拦这个组合键，直接交给 App",
                    BambooPrefs.aiComboEnabled,
                ) {
                    BambooPrefs.aiComboEnabled = it
                    rebuild()
                }
                addSwitch(
                    "未进模式时也发默认模式",
                    "开（默认）＝没进入任何 AI 模式时，组合键 / 剪贴板码都发给默认的 ai; 模式；关＝不发送（组合键直接放行给 App）",
                    BambooPrefs.aiFallbackDefault,
                ) {
                    BambooPrefs.aiFallbackDefault = it
                    rebuild()
                }
                addPreference(
                    "AI 快捷组合键",
                    "${BambooPrefs.aiCombo}（只对「模拟按键」有效：外接键盘，或键位上写 send: \"Control+semicolon\"；也可以把动作名 BambooAiSend 直接绑到键位上，不受这里影响）",
                ) {
                    editText("AI 快捷组合键", BambooPrefs.aiCombo, hint = "ctrl+;") {
                        BambooPrefs.aiCombo = it
                        rebuild()
                    }
                }
                addPreference("双击键", "${BambooPrefs.aiSendKey}（双击这个键发送；上面「双击发送键发送」开着才生效）") {
                    editText("双击键", BambooPrefs.aiSendKey, hint = "/") {
                        BambooPrefs.aiSendKey = it
                        rebuild()
                    }
                }
                addSwitch(
                    "剪贴板：快捷码生效",
                    "开＝打 ${BambooPrefs.aiClipCode} 就把剪贴板发给当前模式（可关闭）",
                    BambooPrefs.clipCodeEnabled,
                ) {
                    BambooPrefs.clipCodeEnabled = it
                    rebuild()
                }
                addSwitch(
                    "剪贴板：快捷组合键生效",
                    "开＝按下面的组合键把剪贴板发给当前模式（可关闭；与快捷码可以同时开）",
                    BambooPrefs.clipComboEnabled,
                ) {
                    BambooPrefs.clipComboEnabled = it
                    rebuild()
                }
                addPreference(
                    "剪贴板快捷组合键",
                    "${BambooPrefs.clipCombo}（同样只对外接键盘 / send: 绑定有效；也可以用动作名 BambooAiClip 直接绑键位）",
                ) {
                    editText("剪贴板快捷组合键", BambooPrefs.clipCombo, hint = "ctrl+alt+;") {
                        BambooPrefs.clipCombo = it
                        rebuild()
                    }
                }
                addPreference(
                    "退出 AI 模式",
                    "${BambooPrefs.aiExitCode.ifEmpty { "未设置" }}（也可以再打一次进入时的 AI 码退出）",
                ) {
                    editText("退出码", BambooPrefs.aiExitCode, hint = "例如 tc;") {
                        BambooPrefs.aiExitCode = it
                        rebuild()
                    }
                }
                addPreference(
                    "发送剪贴板",
                    "${BambooPrefs.aiClipCode.ifEmpty { "未设置" }}（AI 模式下按一下就把剪贴板发给它）",
                ) {
                    editText("剪贴板码", BambooPrefs.aiClipCode, hint = "例如 jt;") {
                        BambooPrefs.aiClipCode = it
                        rebuild()
                    }
                }
                addPreference("发送内容", sourceLabel()) {
                    chooseSource()
                }
                addSwitch(
                    "非编码时只发结束符号之后",
                    "开＝按结束符号切开，只发最后一句（默认）；关＝发上一次上屏的整段",
                    BambooPrefs.aiSentenceOnly,
                ) {
                    BambooPrefs.aiSentenceOnly = it
                    rebuild()
                }
                addPreference("结束符号", "${BambooPrefs.aiSentenceMarks}（中英标点都能断句）") {
                    editText("结束符号", BambooPrefs.aiSentenceMarks, hint = "。？！!?") {
                        BambooPrefs.aiSentenceMarks = it
                        rebuild()
                    }
                }
                addSwitch(
                    "发送后丢弃拼写候选",
                    "开＝只上屏 AI 结果（默认）；关＝先把候选上屏再上屏结果",
                    BambooPrefs.aiDiscardComposition,
                ) {
                    BambooPrefs.aiDiscardComposition = it
                }
                addPreference("双击间隔", "${BambooPrefs.aiDoubleMs} 毫秒") {
                    editText("双击间隔（毫秒）", BambooPrefs.aiDoubleMs.toString(), numeric = true) {
                        it.toIntOrNull()?.let { n -> BambooPrefs.aiDoubleMs = n }
                        rebuild()
                    }
                }
                addPreference("温度", "${BambooPrefs.aiTemperature}") {
                    editText("温度 0~2", BambooPrefs.aiTemperature.toString()) {
                        it.toFloatOrNull()?.let { f -> BambooPrefs.aiTemperature = f }
                        rebuild()
                    }
                }
                addPreference("最大 token", "${BambooPrefs.aiMaxTokens}") {
                    editText("最大 token", BambooPrefs.aiMaxTokens.toString(), numeric = true) {
                        it.toIntOrNull()?.let { n -> BambooPrefs.aiMaxTokens = n }
                        rebuild()
                    }
                }
                addPreference("超时", "${BambooPrefs.aiTimeoutMs / 1000} 秒") {
                    editText("超时（秒）", (BambooPrefs.aiTimeoutMs / 1000).toString(), numeric = true) {
                        (it.toIntOrNull()?.times(1000))?.let { ms -> BambooPrefs.aiTimeoutMs = ms }
                        rebuild()
                    }
                }
            }

            addBambooCategory("AI 模式与提示词", "每个模式一套提示词；再打一次同一个码就退出") {
                BambooPrefs.aiModes.forEach { mode ->
                    addPreference(
                        "${mode.code}　${mode.name}",
                        mode.prompt.replace('\n', ' ').take(48),
                    ) { editMode(mode) }
                }
                addPreference("＋ 新增模式", "自定义快捷码、模式提示和提示词") {
                    editMode(AiMode("new;", "", ""), isNew = true)
                }
                addPreference("恢复默认提示词", "把快捷码、模式提示和提示词都恢复成内置默认") {
                    BambooPrefs.aiModes = BambooPrefs.defaults()
                    rebuild()
                }
            }

            // 动作名（token）的完整总表已经搬到独立页「按键动作调用」，
            // 这里只留一个去处入口，避免两处各写一份、改一处忘一处。
            addBambooCategory("自定义键位", "想给键位绑动作，看独立页「按键动作调用」") {
                addPreference("动作名总表", "全部 token、写法和注意事项都在「按键动作调用」页") {
                    requireContext().startActivity(
                        Intent(requireContext(), MainActivity::class.java).apply {
                            putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, NavigationRoute.BambooTokens)
                        },
                    )
                }
                addPreference("和「快捷组合键」的关系", "键位绑定 vs 模拟按键，两条路互不影响") {
                    showComboVsToken()
                }
            }

            attachRestores()
        }
    }

    /**
     * 每一项的默认值与恢复动作。
     *
     * 键是设置项标题（与上面 [PreferenceScreen] 里写的完全一致），值的第一项是显示用的
     * 默认值文本，第二项是恢复动作（只改 SharedPreferences，页面由调用方重建）。
     */
    private fun restorePlan(): Map<String, Pair<String, () -> Unit>> =
        linkedMapOf(
            "启用苍竹增强" to ("开" to { BambooPrefs.enabled = true }),
            "启用回退" to ("开" to { BambooPrefs.revertEnabled = true }),
            "回退触发码" to (BambooPrefs.DEFAULT_REVERT_TRIGGERS to {
                BambooPrefs.revertTriggersRaw = BambooPrefs.DEFAULT_REVERT_TRIGGERS
            }),
            "一次最多删除的字符数" to (BambooPrefs.DEFAULT_MAX_DELETE.toString() to {
                BambooPrefs.revertMaxCodePoints = BambooPrefs.DEFAULT_MAX_DELETE
            }),
            "启用 AI 模式" to ("开" to { BambooPrefs.aiEnabled = true }),
            "接口地址" to (BambooPrefs.DEFAULT_AI_BASE_URL to { BambooPrefs.aiBaseUrl = BambooPrefs.DEFAULT_AI_BASE_URL }),
            "模型" to (BambooPrefs.DEFAULT_AI_MODEL to { BambooPrefs.aiModel = BambooPrefs.DEFAULT_AI_MODEL }),
            "双击发送键发送" to ("关" to { BambooPrefs.aiDoubleTapEnabled = false }),
            "快捷组合键发送" to ("开" to { BambooPrefs.aiComboEnabled = true }),
            "未进模式时也发默认模式" to ("开" to { BambooPrefs.aiFallbackDefault = true }),
            "AI 快捷组合键" to (BambooPrefs.DEFAULT_AI_COMBO to { BambooPrefs.aiCombo = BambooPrefs.DEFAULT_AI_COMBO }),
            "双击键" to ("/" to { BambooPrefs.aiSendKey = "/" }),
            "剪贴板：快捷码生效" to ("开" to { BambooPrefs.clipCodeEnabled = true }),
            "剪贴板：快捷组合键生效" to ("开" to { BambooPrefs.clipComboEnabled = true }),
            "剪贴板快捷组合键" to (BambooPrefs.DEFAULT_CLIP_COMBO to {
                BambooPrefs.clipCombo = BambooPrefs.DEFAULT_CLIP_COMBO
            }),
            "退出 AI 模式" to ("tc;" to { BambooPrefs.aiExitCode = "tc;" }),
            "发送剪贴板" to ("jt;" to { BambooPrefs.aiClipCode = "jt;" }),
            "发送内容" to ("自动" to { BambooPrefs.aiSource = BambooPrefs.SOURCE_AUTO }),
            "非编码时只发结束符号之后" to ("开" to { BambooPrefs.aiSentenceOnly = true }),
            "结束符号" to (BambooPrefs.DEFAULT_SENTENCE_MARKS to {
                BambooPrefs.aiSentenceMarks = BambooPrefs.DEFAULT_SENTENCE_MARKS
            }),
            "发送后丢弃拼写候选" to ("开" to { BambooPrefs.aiDiscardComposition = true }),
            "双击间隔" to ("1000 毫秒" to { BambooPrefs.aiDoubleMs = 1000 }),
            "温度" to ("0.7" to { BambooPrefs.aiTemperature = 0.7f }),
            "最大 token" to ("1024" to { BambooPrefs.aiMaxTokens = 1024 }),
            "超时" to ("30 秒" to { BambooPrefs.aiTimeoutMs = 30_000 }),
        )

    /** 页首的「恢复本页默认」。 */
    private fun PreferenceScreen.addRestoreAll() {
        addPreference(
            Preference(context).apply {
                key = "restore_all_bamboo"
                title = "恢复本页默认"
                summary = "把回退、AI 服务与发送的所有设置，以及全部提示词恢复成出厂值"
                isIconSpaceReserved = false
                isPersistent = false
                setOnPreferenceClickListener {
                    AlertDialog.Builder(context)
                        .setTitle("恢复本页默认")
                        .setMessage("回退与 AI 的全部设置、以及所有 AI 模式的提示词都会恢复成默认值，确定吗？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("恢复") { _, _ ->
                            restorePlan().values.forEach { runCatching { it.second() } }
                            BambooPrefs.aiModes = BambooPrefs.defaults()
                            rebuild()
                        }.show()
                    true
                }
            },
        )
    }

    /**
     * 把 [restorePlan] 里的恢复动作挂到每一项**自己的行尾按钮**上。
     *
     * 不做插行：恢复按钮由 [BambooClick] / [BambooSwitch] 在 `onBindViewHolder` 里画在
     * 标题行最右侧（见 `ui/common/PrefRestore.kt`），所以这里只按标题把 lambda 塞进去。
     * 这样设置项再多，行数也不会翻倍。
     */
    private fun PreferenceScreen.attachRestores() {
        val plan = restorePlan()

        fun walk(group: PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                val pref = group.getPreference(i)
                if (pref is PreferenceCategory) {
                    walk(pref)
                    continue
                }
                val entry = plan[pref.title?.toString().orEmpty()] ?: continue
                val action = {
                    runCatching { entry.second() }
                    rebuild()
                }
                when (pref) {
                    is BambooClick -> pref.onRestore = action
                    is BambooSwitch -> pref.onRestore = action
                    else -> {}
                }
            }
        }

        walk(this)
    }

    private fun PreferenceGroup.addSwitch(
        title: String,
        summary: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        addPreference(
            BambooSwitch(context).apply {
                this.title = title
                this.summary = summary
                isChecked = checked
                isIconSpaceReserved = false
                isPersistent = false
                setOnPreferenceChangeListener { _, newValue ->
                    onChange(newValue as Boolean)
                    true
                }
            },
        )
    }


    /** 「动作名一览」：纯展示，内容由 [BambooTokens.docRows] 生成，避免和代码不同步。 */
    private fun showTokenList() {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val body =
            TextView(ctx).apply {
                setTextIsSelectable(true)
                textSize = 13f
                setPadding(pad, pad / 2, pad, pad / 2)
                text =
                    BambooTokens.docRows().joinToString("\n\n") { (name, desc) ->
                        "$name\n    $desc"
                    }
            }
        val scroll =
            ScrollView(ctx).apply {
                addView(
                    body,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        AlertDialog
            .Builder(ctx)
            .setTitle("主题动作名")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** 「和快捷组合键的关系」：解释两条触发通路。 */
    private fun showComboVsToken() {
        val ctx = requireContext()
        val text =
            """
            触发 AI 有两条互不影响的路：

            【一】键位绑定（动作名，推荐）
              · 主题里把 token 直接写在键位上，例如
                swipe_up = "BambooAiSend"
              · 点下去就直接执行，跟下面「触发方式」的设置无关；
              · 主题里回车键的上滑/右滑用的就是这条。

            【二】模拟按键（快捷组合键）
              · 键位上写 send: "Control+semicolon"，等于替用户按了 Ctrl+;，
                再由输入法识别成 AI 发送；
              · 只在「快捷组合键」里配的键有效，改了键位写法就对不上；
              · 主要给外接键盘 / 硬件键盘用，也可以给自定义键位留一条退路。

            两者最终调用的是同一个发送逻辑（同样的模式判定、
            同样的剪贴板取值、同样的上屏），只是入口不同。

            · 想改组合键：本页「AI 快捷组合键」「剪贴板快捷组合键」；
            · 「双击发送键发送」「快捷组合键发送」是两个独立开关，可以都关掉；
              都关掉之后，动作名和快捷码（如 lt;）照常可用 —— 它们不走这两条路；
            · 组合键保留主要是为了外接键盘；屏幕键位建议直接用动作名，
              这样以后改组合键也不会把键位搞失效。
            · 想用动作名：本页「动作名一览」里有全部可绑的名字。
            """.trimIndent()
        AlertDialog
            .Builder(ctx)
            .setTitle("组合键 vs 动作名")
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** 「怎么绑定」：三行示例。 */
    private fun showBindHelp() {
        val ctx = requireContext()
        val sample =
            """
            在主题布局的 Lua 里，把动作字段写成 token 名字：

              key(merge(S.backspace, key {
                  click       = "del",           -- 单击：退格
                  swipe_right = "BambooRevert",  -- 右滑：回退最近一笔
              }))

              key(merge(S.d, key {
                  click      = "d",
                  long_click = "BambooClear",    -- 长按 D：清空编辑区
              }))

              popup = { "BambooAi1", "BambooAi2", "BambooAiOff" }

            可用槽位：click / double_click / long_click /
                      swipe_left / swipe_right / swipe_up / swipe_down / popup

            说明：
            · 名字大小写不敏感，必须以 Bamboo 开头；
            · 改完主题要重新载入主题才生效；
            · BambooClear / BambooDeleteLine / BambooDeleteWord 走
              InputConnection 一次性删除，比连发按键稳。
            """.trimIndent()
        AlertDialog
            .Builder(ctx)
            .setTitle("怎么绑定")
            .setMessage(sample)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun rebuild() {
        // 保持滚动位置：改一项/恢复默认后直接滚回顶部很难受
        rebuildPreferencesKeepingScroll {
            preferenceScreen.removeAll()
            onCreatePreferences(null, null)
        }
    }

    /**
     * 分类头：墨绿竖条 + 主题色标题 + 一行灰色说明。
     * 默认的 PreferenceCategory 只有一行蓝字，放在这里太素。
     */
    private fun PreferenceScreen.addBambooCategory(
        title: String,
        subtitle: String,
        block: PreferenceGroup.() -> Unit,
    ) {
        val header =
            BambooCategoryPreference(context).apply {
                layoutResource = R.layout.preference_bamboo_category
                isIconSpaceReserved = false
                isPersistent = false
                setTitle(title)
                setSubtitle(subtitle)
            }
        addPreference(header)
        header.block()
    }

    private fun sourceLabel(): String =
        when (BambooPrefs.aiSource) {
            BambooPrefs.SOURCE_COMPOSITION -> "只用正在拼写的内容"
            BambooPrefs.SOURCE_COMMITTED -> "只用上次上屏的内容"
            else -> "有拼写用拼写，没有就用上次上屏（默认）"
        }

    private fun chooseSource() {
        val ctx = requireContext()
        val options = arrayOf("有拼写用拼写，没有就用上次上屏", "只用正在拼写的内容", "只用上次上屏的内容")
        val values =
            arrayOf(
                BambooPrefs.SOURCE_AUTO,
                BambooPrefs.SOURCE_COMPOSITION,
                BambooPrefs.SOURCE_COMMITTED,
            )
        val checked = values.indexOf(BambooPrefs.aiSource).coerceAtLeast(0)
        AlertDialog
            .Builder(ctx)
            .setTitle("发送内容")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                BambooPrefs.aiSource = values[which]
                dialog.dismiss()
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editText(
        title: String,
        initial: String,
        hint: String? = null,
        numeric: Boolean = false,
        password: Boolean = false,
        onOk: (String) -> Unit,
    ) {
        val ctx = requireContext()
        val input =
            EditText(ctx).apply {
                setText(initial)
                hint?.let { this.hint = it }
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
                if (password) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                setSingleLine(!password)
            }
        val container =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(
                    input,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        AlertDialog
            .Builder(ctx)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editMode(
        mode: AiMode,
        isNew: Boolean = false,
    ) {
        val ctx = requireContext()
        val codeInput =
            EditText(ctx).apply {
                setText(mode.code)
                hint = "快捷码，例如 rs;"
                setSingleLine()
            }
        val nameInput =
            EditText(ctx).apply {
                setText(mode.name)
                hint = "模式提示，例如 英文翻译"
                setSingleLine()
            }
        val promptInput =
            EditText(ctx).apply {
                setText(mode.prompt)
                hint = "提示词"
                minLines = 3
                maxLines = 12
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
        val container =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(labelView("快捷码："))
                addView(codeInput)
                addView(labelView("模式提示："))
                addView(nameInput)
                addView(labelView("提示词："))
                addView(promptInput)
            }
        AlertDialog
            .Builder(ctx)
            .setTitle(if (isNew) "新增 AI 模式" else "编辑 ${mode.name}")
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = codeInput.text.toString().trim()
                val name = nameInput.text.toString().trim().ifEmpty { code }
                val prompt = promptInput.text.toString().trim()
                if (code.length < 2 || prompt.isEmpty()) {
                    rebuild()
                    return@setPositiveButton
                }
                val list = BambooPrefs.aiModes.toMutableList()
                val idx = list.indexOfFirst { it.code == mode.code && it.key == mode.key }
                val updated = AiMode(code, name, prompt, mode.key)
                if (idx >= 0) list[idx] = updated else list.add(updated)
                BambooPrefs.aiModes = list
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("删除") { _, _ ->
                BambooPrefs.aiModes =
                    BambooPrefs.aiModes.filterNot { it.code == mode.code && it.key == mode.key }
                rebuild()
            }
            .show()
    }

    private fun labelView(text: String): TextView =
        TextView(requireContext()).apply {
            this.text = text
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 2)
        }
}

/**
 * 分类头 Preference：标题 + 灰色说明，样式见 `preference_bamboo_category.xml`。
 */
class BambooCategoryPreference(context: Context) : PreferenceCategory(context) {
    private var subtitleText: CharSequence? = null

    fun setSubtitle(text: CharSequence?) {
        subtitleText = text
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.findViewById<TextView>(R.id.bamboo_cat_title)?.text = title
        val subtitleView = holder.itemView.findViewById<TextView>(R.id.bamboo_cat_subtitle)
        subtitleView?.text = subtitleText
        subtitleView?.isVisible = !subtitleText.isNullOrEmpty()
    }
}