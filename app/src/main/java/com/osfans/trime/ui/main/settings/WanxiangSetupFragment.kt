/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Intent
import android.net.Uri
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
import androidx.navigation.fragment.findNavController
import com.osfans.trime.actions.wanxiang.WanxiangCustom
import com.osfans.trime.actions.wanxiang.WanxiangPrefs
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.packaging.SchemaPackageManager
import com.osfans.trime.data.packaging.SchemaPackStore
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.util.navigateWithAnim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 万象拼音「新用户设置向导」。
 *
 * 五页：
 *  0. 分流 —— 我是新用户 / 我是老用户（方案包自己放）
 *  1. 键盘形态（26 / 18 / 14 / 9）—— 选完立刻在后台解包，后面几页不用等
 *  2. 编码（全拼 / 双拼）—— 九键固定全拼
 *  3. 顺手设置（候选个数 / 用户词典 / 模糊音说明）
 *  4. 确认并应用 —— 把要写的 YAML 原样列出来，点「完成并应用」才写盘 + 部署
 *
 * 规则：
 *  * 辅助码不在这里选：内置的 Base 词库不含辅码，装上 Pro 变体后才在「方案设置」里开；
 *  * 14 键用 26 键键盘模拟（主题里没有 14 键布局），页面明确提示；
 *  * 老用户分支不碰任何文件，只给路径和跳转。
 */
class WanxiangSetupFragment : Fragment() {

    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var progress: TextView
    private lateinit var prev: Button
    private lateinit var next: Button

    private var page = 0
    private var busy = false
    private var releaseStarted = false
    private var releaseOk = false

    private val pages = 5

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
        prev.visibility = if (page == 0) View.INVISIBLE else View.VISIBLE
        next.text = if (page == pages - 1) "完成并应用" else "下一步"

        when (page) {
            0 -> renderGate()
            1 -> renderLayout()
            2 -> renderEncode()
            3 -> renderExtras()
            else -> renderConfirm()
        }
    }

    /** 第 0 页：先问一句「你是谁」。老用户不该被我们的方案包挡住。 */
    private fun renderGate() {
        title.text = "先确认一下"
        content.addView(
            hint(
                "这个向导会给新用户配好「万象拼音」：内置全拼/双拼 + 九键，共 67 MB，不用另外下载。\n" +
                    "已经自己放好方案包、或想继续用自己的方案的话，走下面的「老用户」就行，向导不会动你的任何文件。",
            ),
        )
        content.addView(
            bigButton("我是新用户，帮我一分钟配好") {
                page = 1
                render()
                startBackgroundRelease()
            },
        )
        content.addView(
            bigButton("我是老用户，方案包我自己放") {
                WanxiangPrefs.wizardDone = true
                showVeteranHelp()
            },
        )
    }

    private fun showVeteranHelp() {
        content.removeAllViews()
        title.text = "老用户：方案包自己放"
        content.addView(
            hint(
                "把方案包文件夹放到：\n\n" +
                    "  ${DataManager.userDataBaseDir.absolutePath}\n\n" +
                    "一个方案包 = 一个文件夹，里面要有 default.yaml 和至少一个 *.schema.yaml。\n" +
                    "放好后回到「方案包管理」就能看到它，选中即用。",
            ),
        )
        content.addView(
            bigButton("打开这个文件夹") {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            Uri.parse("file://" + DataManager.userDataBaseDir.absolutePath),
                            "resource/folder",
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    requireContext().startActivity(intent)
                }.onFailure { toast("这个系统没有文件管理器能打开文件夹，请手动进 Documents/BambooIME") }
            },
        )
        content.addView(
            bigButton("去「方案包管理」") {
                runCatching { findNavController().navigateWithAnim(NavigationRoute.PackageList) }
                    .onFailure { toast("跳转失败，请返回上一页从菜单进入") }
            },
        )
        content.addView(
            bigButton("我用我自己的方案，先不用万象") {
                runCatching { parentFragmentManager.popBackStack() }
            },
        )
    }

    private fun renderLayout() {
        title.text = "用哪种键盘"
        content.addView(
            hint(
                "九宫格（9 键）用官方的「万象九键」方案；18 键用 18 键折叠；" +
                    "14 键目前只能用 26 键键盘模拟（主题没有 14 键布局），按键会有重复。\n\n" +
                    "键盘布局本身要在键盘弹窗里切（9 键选「T9」，18 键选「18」），自动切会放到后面的主题设置里做。",
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
        content.addView(releaseStatusView())
    }

    private fun renderEncode() {
        title.text = "全拼还是双拼"
        if (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) {
            content.addView(
                hint(
                    "九键固定用全拼：九宫格每个键对应 3–4 个字母，双拼的键位在九键上无法输入。\n" +
                        "切回全键盘后可以再来这里改双拼。",
                ),
            )
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
        if (WanxiangPrefs.encode == WanxiangPrefs.ENCODE_SHUANGPIN) {
            content.addView(
                hint(
                    "小提示：双拼下打单音节字要打两下（啊 = aa、哦 = oo），这是双拼本身的规则，" +
                        "全拼没有这个限制。",
                ),
            )
            content.addView(sectionLabel("用哪套双拼"))
            content.addView(
                radio(
                    WanxiangPrefs.SCHEMES.map { it to it },
                    WanxiangPrefs.scheme,
                ) { WanxiangPrefs.scheme = it; render() },
            )
        }
    }

    private fun renderExtras() {
        title.text = "顺手调两个"
        content.addView(hint("这两项以后也能在「方案设置」里改。"))
        content.addView(sectionLabel("候选个数"))
        content.addView(
            radio(
                listOf(3, 4, 5, 6).map { it to "$it 个" },
                WanxiangPrefs.pageSize,
            ) { WanxiangPrefs.pageSize = it; render() },
        )
        content.addView(sectionLabel("用户词典（记住你常打的词）"))
        content.addView(
            radio(
                listOf(false to "不开（官方默认，换词序更稳定）", true to "开（越用越顺手）"),
                WanxiangPrefs.userDict,
            ) { WanxiangPrefs.userDict = it; render() },
        )
        content.addView(sectionLabel("模糊音"))
        content.addView(
            hint(
                "默认全关（官方也是全关）。需要的话配好后到「万象拼音 → 方案设置 → 模糊音」逐项打开：" +
                    "n/l、r/l、h/f、k/g、en/eng、in/ing、c/ch、z/zh、s/sh。",
            ),
        )
        content.addView(
            hint(
                "辅助码这次不选：内置的 Base 词库不含辅码，等你在「方案设置」里装上 Pro 变体（约 30 MB）后再开。",
            ),
        )
    }

    private fun renderConfirm() {
        title.text = "确认并应用"
        content.addView(hint(WanxiangCustom.summary()))
        content.addView(
            hint(
                "将写入这些设置：\n\n" + WanxiangCustom.algebraNodes().joinToString("\n") { "  - $it" } +
                    "\n  menu/page_size: ${WanxiangPrefs.pageSize}\n" +
                    "  translator/enable_user_dict: ${WanxiangPrefs.userDict}\n\n" +
                    "点「完成并应用」后会重新部署一次（约 1–2 分钟）。",
            ),
        )
        content.addView(releaseStatusView())
    }

    // ------------------------------------------------------------ 解包

    private fun startBackgroundRelease() {
        if (releaseStarted) return
        releaseStarted = true
        viewLifecycleOwner.lifecycleScope.launch {
            // 第 1 页选完键盘就开始解包，走到第 5 页时基本已经好了
            releaseOk = withContext(Dispatchers.IO) { SchemaPackStore.ensureCopied(requireContext()) }
            Timber.d("wanxiang wizard: release=$releaseOk")
            if (page == 1 || page == 4) render()
        }
    }

    private fun releaseStatusView(): TextView = hint(
        when {
            releaseOk -> "内置方案包已就绪，继续往下就行。"
            releaseStarted -> "正在后台准备方案包（67 MB），不用等它，继续往下点就好。"
            else -> "点「下一步」会开始在后台准备方案包（67 MB）。"
        },
    )

    override fun onDestroy() {
        RimeDaemon.destroySession(javaClass.name)
        super.onDestroy()
    }

    // ------------------------------------------------------------ 应用

    private fun applyAndDeploy() {
        // 九键/全拼的互斥项在写盘前统一收干净
        WanxiangPrefs.sanitize()
        if (busy) return
        busy = true
        next.isEnabled = false
        next.text = "正在应用…"

        viewLifecycleOwner.lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) { SchemaPackStore.ensureCopied(requireContext()) }
            if (!ready) {
                busy = false
                next.isEnabled = true
                next.text = "完成并应用"
                toast("释放方案包失败：检查 Documents 目录是否可写")
                return@launch
            }

            val ok = withContext(Dispatchers.IO) { WanxiangCustom.writeAll() }
            Timber.d("wanxiang wizard: writeAll=$ok")
            if (!ok) {
                busy = false
                next.isEnabled = true
                next.text = "完成并应用"
                toast("写入配置失败：检查 Documents 目录是否可写")
                return@launch
            }

            // 按包 id 激活：`selectSchemaCrossPackage` 是按方案 id 反查包的，
            // 用户自己的包里也有同名方案时会被切到人家的包（踩过）。
            SchemaPackageManager.setActivePackageId(SchemaPackStore.PACKAGE_ID)
            withContext(Dispatchers.IO) {
                runCatching { RimeDaemon.restartRime(fullCheck = true) }
                    .onFailure { Timber.w(it, "wanxiang wizard: deploy failed") }
            }

            WanxiangPrefs.wizardDone = true
            busy = false
            toast("已应用。键盘布局记得自己在键盘弹窗里切一下")
            runCatching { parentFragmentManager.popBackStack() }
        }
    }

    // ------------------------------------------------------------ 小工具

    private fun bigButton(label: String, onClick: () -> Unit): Button = Button(requireContext()).apply {
        text = label
        textSize = 15f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) }
    }

    private fun sectionLabel(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFFF2EFE6.toInt())
        setPadding(0, dp(14), 0, dp(4))
    }

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
