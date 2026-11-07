/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.R
import com.osfans.trime.actions.bamboo.AiMode
import com.osfans.trime.actions.bamboo.BambooPrefs
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.addPreference

/**
 * 「苍竹增强」设置页：回退 + AI 模式。
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
                addPreference("发送方式", triggerLabel()) { chooseTrigger() }
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
                    "${BambooPrefs.aiCombo}（键位上写 send: \"Control+semicolon\" 这样就能触发）",
                ) {
                    editText("AI 快捷组合键", BambooPrefs.aiCombo, hint = "ctrl+;") {
                        BambooPrefs.aiCombo = it
                        rebuild()
                    }
                }
                addPreference("双击键", "${BambooPrefs.aiSendKey}（双击这个键发送；选「双击按键」时才生效）") {
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
                    "${BambooPrefs.clipCombo}（键位上写 send: \"Control+Alt+semicolon\"）",
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
                addPreference("恢复默认 8 套", "把快捷码、模式提示和提示词都恢复成内置默认") {
                    BambooPrefs.aiModes = BambooPrefs.defaults()
                    rebuild()
                }
            }
        }
    }

    private fun PreferenceGroup.addSwitch(
        title: String,
        summary: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        addPreference(
            SwitchPreferenceCompat(context).apply {
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

    private fun rebuild() {
        preferenceScreen.removeAll()
        onCreatePreferences(null, null)
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

    private fun triggerLabel(): String =
        if (BambooPrefs.aiUsesCombo) {
            "快捷组合键：${BambooPrefs.aiCombo}（默认）"
        } else {
            "双击按键：${BambooPrefs.aiSendKey}${BambooPrefs.aiSendKey}"
        }

    private fun chooseTrigger() {
        val ctx = requireContext()
        val options =
            arrayOf(
                "快捷组合键（推荐：不占用拼写键，一下就能发）",
                "双击按键（连按两下同一个键）",
            )
        val values = arrayOf(BambooPrefs.TRIGGER_COMBO, BambooPrefs.TRIGGER_DOUBLE)
        val checked = values.indexOf(BambooPrefs.aiTriggerMode).coerceAtLeast(0)
        AlertDialog
            .Builder(ctx)
            .setTitle("发送方式")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                BambooPrefs.aiTriggerMode = values[which]
                dialog.dismiss()
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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