/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.actions.wanxiang.WanxiangCustom
import com.osfans.trime.actions.wanxiang.WanxiangPrefs
import com.osfans.trime.core.Rime
import com.osfans.trime.daemon.RimeDaemon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 万象拼音「新用户设置向导」。
 *
 * 四页：①键盘（26/18/14/9）②编码（全拼/双拼）③拼音方案（13 种双拼）④确认并应用。
 *
 * 规则：
 *  * 九键固定全拼 —— 第 ②③ 页在九键下禁用并写明原因（避免「双拼 + 九键」这种废组合）；
 *  * 14 键用 26 键键盘模拟（主题里没有 14 键布局），页面明确提示；
 *  * 第 ④ 页把要写的 YAML 原样展示，点「完成并应用」才写盘 + 部署。
 */
class WanxiangSetupFragment : Fragment() {

    private val rime = RimeDaemon.createSession(javaClass.name)

    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var progress: TextView
    private lateinit var prev: Button
    private lateinit var next: Button

    private var page = 0
    private var busy = false

    private val pages = 4

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        WanxiangPrefs.init(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(0xFF1F1F22.toInt())
        }

        progress = TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(0xFF9A9A9A.toInt())
        }
        title = TextView(requireContext()).apply {
            textSize = 20f
            setTextColor(0xFFF2EFE6.toInt())
            setPadding(0, dp(6), 0, dp(14))
        }

        content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(requireContext()).apply {
            addView(content)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val bar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        prev = Button(requireContext()).apply {
            text = "上一步"
            setOnClickListener { if (page > 0) { page--; render() } }
        }
        next = Button(requireContext()).apply {
            text = "下一步"
            setOnClickListener { onNext() }
        }
        bar.addView(prev, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })

        root.addView(progress)
        root.addView(title)
        root.addView(scroll)
        root.addView(bar)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 从当前生效的组合反推，重复进入时不重置
        page = 0
        render()
    }

    private fun onNext() {
        if (busy) return
        if (page < pages - 1) {
            page++
            render()
            return
        }
        applyAndDeploy()
    }

    // ------------------------------------------------------------ 渲染

    private fun render() {
        content.removeAllViews()
        progress.text = "第 ${page + 1} / $pages 步"
        prev.isEnabled = page > 0
        next.text = if (page == pages - 1) "完成并应用" else "下一步"

        when (page) {
            0 -> renderLayout()
            1 -> renderEncode()
            2 -> renderScheme()
            else -> renderConfirm()
        }
    }

    private fun renderLayout() {
        title.text = "用哪种键盘"
        content.addView(
            hint(
                "九宫格（9 键）用独立的「万象九键 Pro」方案；18 键用 18 键折叠；" +
                    "14 键目前只能用 26 键键盘模拟（主题没有 14 键布局），按键会有重复。",
            ),
        )
        content.addView(
            radio(
                listOf(
                    26 to "全键盘（26 键）— 推荐，能打声调与辅助码",
                    18 to "18 键 — 键位合并，单手更省力",
                    14 to "14 键 — 用 26 键键盘模拟，会有重复按键",
                    9 to "九宫格（9 键）— 固定全拼",
                ),
                WanxiangPrefs.layout,
            ) { WanxiangPrefs.layout = it; render() },
        )
    }

    private fun renderEncode() {
        title.text = "全拼还是双拼"
        if (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) {
            content.addView(hint("九键固定用全拼：九宫格每个键对应 3–4 个字母，双拼的键位在九键上无法输入。\n切回全键盘后可以再来这里改双拼。"))
            return
        }
        content.addView(
            radio(
                listOf(
                    WanxiangPrefs.ENCODE_QUANPIN to "全拼 — 直接打拼音，不用记键位",
                    WanxiangPrefs.ENCODE_SHUANGPIN to "双拼 — 两键一字，需要记键位",
                ),
                WanxiangPrefs.encode,
            ) { WanxiangPrefs.encode = it; render() },
        )
    }

    private fun renderScheme() {
        title.text = "选一个双拼方案"
        val quanpin = WanxiangPrefs.encode == WanxiangPrefs.ENCODE_QUANPIN
        if (quanpin) {
            content.addView(hint("你选的是全拼，这一步不需要选方案。"))
            return
        }
        val items = WanxiangPrefs.SCHEMES.map { it to it }
        content.addView(radio(items, WanxiangPrefs.scheme) { WanxiangPrefs.scheme = it; render() })
    }

    private fun renderConfirm() {
        title.text = "确认并应用"
        content.addView(hint(WanxiangCustom.summary()))
        content.addView(
            hint(
                "将写入这些设置：\n\n" + WanxiangCustom.algebraNodes().joinToString("\n") { "  - $it" } +
                    "\n  menu/page_size: ${WanxiangPrefs.pageSize}\n\n" +
                    "点「完成并应用」后会重新部署一次（约 1–4 分钟，取决于词库大小）。",
            ),
        )
        content.addView(
            hint(
                "注意：键盘布局需要你自己在键盘弹窗里切到对应的那一款（9 键选「T9」，18 键选「18」）。" +
                    "自动切键盘会放到下一步的「主题设置」里做。",
            ),
        )
    }

    override fun onDestroy() {
        RimeDaemon.destroySession(javaClass.name)
        super.onDestroy()
    }

    // ------------------------------------------------------------ 应用

    private fun applyAndDeploy() {
        if (busy) return
        busy = true
        next.isEnabled = false
        next.text = "正在应用…"

        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { WanxiangCustom.writeAll() }
            Timber.d("wanxiang wizard: writeAll=$ok")
            if (!ok) {
                busy = false
                next.isEnabled = true
                next.text = "完成并应用"
                toast("写入失败：方案包可能还没释放，先去「万象拼音」页启用")
                return@launch
            }

            val schema = WanxiangCustom.activeSchema(WanxiangPrefs.layout)
            val enabled = WanxiangCustom.enabledSchemas(WanxiangPrefs.layout)
            rime.runOnReady {
                runCatching {
                    selectSchemaCrossPackage(schema)
                    setEnabledSchemata(enabled)
                }.onFailure { Timber.w(it, "wanxiang wizard: select schema failed") }
            }
            withContext(Dispatchers.IO) {
                runCatching { RimeDaemon.restartRime(fullCheck = true) }
                    .onFailure { Timber.w(it, "wanxiang wizard: deploy failed") }
            }

            WanxiangPrefs.wizardDone = true
            busy = false
            toast("已应用，方案已切换为「万象${if (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) "九键" else "拼音"} Pro」")
            runCatching { parentFragmentManager.popBackStack() }
        }
    }

    // ------------------------------------------------------------ 小工具

    private fun <T> radio(
        items: List<Pair<T, String>>,
        selected: T?,
        onPick: (T) -> Unit,
    ): RadioGroup {
        val group = RadioGroup(requireContext()).apply { orientation = RadioGroup.VERTICAL }
        items.forEach { (value, label) ->
            val rb = RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = label
                textSize = 15f
                setTextColor(0xFFE8E4DA.toInt())
                setPadding(0, dp(8), 0, dp(8))
                isChecked = value == selected
            }
            rb.tag = value
            group.addView(rb)
            rb.setOnCheckedChangeListener { v, checked ->
                if (checked) (v.tag as? T)?.let(onPick)
            }
            if (value == selected) group.check(rb.id)
        }
        return group
    }

    private fun hint(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF9A9A9A.toInt())
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(0, dp(4), 0, dp(12))
    }

    private fun toast(msg: String) {
        runCatching { android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
