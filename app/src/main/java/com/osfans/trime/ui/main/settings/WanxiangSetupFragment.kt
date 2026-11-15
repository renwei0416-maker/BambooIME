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

    /** 进向导前的设置快照，取消时还原。 */
    private var backup: Map<String, *> = emptyMap<String, Any>()

    /** 是否已经点过「完成并应用」。 */
    private var applied = false


    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var progress: TextView
    private lateinit var prev: Button
    private lateinit var next: Button

    private var page = 0
    private var busy = false

    private val pages = 6

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
        // 向导不记忆上一次：每次进来都是同一套初始值（26 键 / 全拼 / 无辅助码）。
        // 先留一份快照，若最后没点「完成并应用」就原样还原，避免显示值和实际文件不一致。
        backup = WanxiangPrefs.snapshotAll()
        applied = false
        WanxiangPrefs.resetToDefaults()
        page = 0
        render()
    }

    override fun onDestroyView() {
        // 没应用就退出：把进入向导前的设置还原回去。
        if (!applied) runCatching { WanxiangPrefs.restoreAll(backup) }
        super.onDestroyView()
    }

    /** 九宫格只能用全拼；这个组合一旦成立就不允许继续。 */
    private fun t9Conflict(): Boolean =
        WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9 &&
            WanxiangPrefs.encode == WanxiangPrefs.ENCODE_SHUANGPIN

    private fun onNext() {
        if (busy) return
        if (t9Conflict()) {
            toast("九宫格只能用全拼：先把编码改成全拼，或换个键盘")
            return
        }
        if (page < pages - 1) {
            page ++
            // 九键只能是全拼、也不带辅助码 → 直接进收尾；全拼跳过双拼页
            if (page == 3 && (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9 ||
                    WanxiangPrefs.encode == WanxiangPrefs.ENCODE_QUANPIN)
            ) {
                page = 4
            }
            if (page == 4 && WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) {
                WanxiangPrefs.aux = WanxiangPrefs.AUX_NONE
                page = 5
            }
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
        // 「九宫格 + 双拼」是不允许的组合：不让点下一步，并在上方说清楚怎么改
        if (t9Conflict()) {
            content.addView(
                hint("九宫格只能用全拼：请把编码改成「全拼」，或者把键盘换成 26 / 18 / 14 键。"),
            )
        }
        next.isEnabled = !t9Conflict()

        when (page) {
            0 -> renderGate()
            1 -> renderEncode()
            2 -> renderLayout()
            3 -> renderScheme()
            4 -> renderAux()
            else -> renderConfirm()
        }
    }

    /** 第 0 页：先问一句「你是谁」。老用户不该被我们的方案包挡住。 */
    private fun renderGate() {
        title.text = "先确认一下"
        content.addView(
            hint("给新用户一分钟配好万象拼音（全拼/双拼 + 九键，67 MB 已内置）。自己放好了方案包的走老用户。"),
        )
        content.addView(
            bigButton("我是新用户，帮我一分钟配好") {
                page = 1
                render()
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
            hint("放到这个目录，一个包一个文件夹：\n\n  ${DataManager.userDataBaseDir.absolutePath}"),
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
                "九宫格（9 键）用的是官方「万象九键」方案，固定全拼。",
            ),
        )
        content.addView(
            radio(
                listOf(
                    26 to "全键盘（26 键）",
                    18 to "18 键",
                    14 to "14 键",
                    9 to "九宫格（9 键）— 固定全拼",
                ),
                WanxiangPrefs.layout,
            ) {
                // 九键只能全拼：冲突在「选键盘」这一刻处理，而不是在第 2 步拦人。
                WanxiangPrefs.layout = it
                render()
            },
        )
    }

    private fun renderEncode() {
        title.text = "全拼还是双拼"
        // 选项永远显示：九键只是「选了也不生效」，不该把整页变成一句话。
        if (WanxiangPrefs.layout == WanxiangPrefs.LAYOUT_9) {
            content.addView(hint("九键固定用全拼。想用双拼，在第 3 步把键盘换成 26 / 18 / 14 键。"))
        }
        content.addView(
            radio(
                listOf(
                    WanxiangPrefs.ENCODE_QUANPIN to "全拼",
                    WanxiangPrefs.ENCODE_SHUANGPIN to "双拼",
                ),
                WanxiangPrefs.encode,
            ) {
                WanxiangPrefs.encode = it
                render()
            },
        )
    }

    private fun renderScheme() {
        title.text = "用哪套双拼"
        content.addView(
            radio(
                WanxiangPrefs.SCHEMES.map { it to it },
                WanxiangPrefs.scheme,
            ) { WanxiangPrefs.scheme = it; render() },
        )
    }

    private fun renderAux() {
        title.text = "用辅助码吗"
        val proReady = SchemaPackStore.isInstalled(SchemaPackStore.PRO_ID)
        content.addView(
            hint(
                if (proReady) {
                    "辅助码配 Pro 词库（已装）。选它会切到 Pro，首次部署 2–4 分钟。"
                } else {
                    "辅助码配 Pro 词库（约 30 MB，还没装）。要用就先下载，回来再完成。"
                },
            ),
        )
        content.addView(
            radio(
                listOf(
                    WanxiangPrefs.AUX_NONE to "不用",
                    WanxiangPrefs.AUX_DIRECT to "直接辅助码",
                    WanxiangPrefs.AUX_INDIRECT to "间接辅助码",
                ),
                WanxiangPrefs.aux,
            ) { WanxiangPrefs.aux = it; render() },
        )
        if (WanxiangPrefs.aux != WanxiangPrefs.AUX_NONE && !proReady) {
            content.addView(
                bigButton("去下载 Pro 词库") {
                    runCatching { findNavController().navigateWithAnim(NavigationRoute.WanxiangDownload) }
                        .onFailure { toast("跳转失败，请返回上一页从菜单进入") }
                },
            )
            content.addView(
                bigButton("先不用辅助码") {
                    WanxiangPrefs.aux = WanxiangPrefs.AUX_NONE
                    render()
                },
            )
        }
    }

    private fun renderConfirm() {
        val usePro = WanxiangPrefs.aux != WanxiangPrefs.AUX_NONE &&
            WanxiangPrefs.layout != WanxiangPrefs.LAYOUT_9
        val pkgId = if (usePro) SchemaPackStore.PRO_ID else SchemaPackStore.BASE_ID
        title.text = "确认并应用"
        content.addView(hint(if (usePro) "将使用 Pro（带辅助码词库）" else "将使用 Base（内置）"))
        content.addView(
            hint(
                "会写进 ${SchemaPackStore.dir(pkgId).name}/：\n\n" +
                    WanxiangCustom.algebraNodes(pkgId).joinToString("\n") { "  - $it" } +
                    "\n  menu/page_size: ${WanxiangPrefs.pageSize}\n\n" +
                    "点「完成并应用」后部署一次（1–4 分钟）。",
            ),
        )
    }

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

        // 辅助码是选包的唯一开关（Pro 没有「不用辅助码」这一档；九键只能 Base）
        val pkgId = WanxiangCustom.targetPackage()

        viewLifecycleOwner.lifecycleScope.launch {
            if (pkgId == SchemaPackStore.BASE_ID) {
                val ready = withContext(Dispatchers.IO) {
                    SchemaPackStore.ensureBaseCopied(requireContext())
                }
                if (!ready) {
                    resetApplyButton()
                    toast("释放内置包失败：检查 Documents 目录是否可写")
                    return@launch
                }
            } else if (!SchemaPackStore.isInstalled(SchemaPackStore.PRO_ID)) {
                resetApplyButton()
                toast("还没装 Pro 词库：先去「方案包与下载」装一个带辅助码的")
                return@launch
            }

            val ok = withContext(Dispatchers.IO) { WanxiangCustom.writeAll(pkgId) }
            Timber.d("wanxiang wizard: pkg=$pkgId writeAll=$ok")
            if (!ok) {
                resetApplyButton()
                toast("写入配置失败：检查 Documents 目录是否可写")
                return@launch
            }

            // 按包 id 激活：`selectSchemaCrossPackage` 是按方案 id 反查包的，
            // 用户自己的包里也有同名方案时会被切到人家的包（踩过）。
            SchemaPackageManager.setActivePackageId(pkgId)
            withContext(Dispatchers.IO) {
                runCatching { RimeDaemon.restartRime(fullCheck = true) }
                    .onFailure { Timber.w(it, "wanxiang wizard: deploy failed") }
            }

            WanxiangPrefs.wizardDone = true
            applied = true
            busy = false
            toast("已应用。键盘布局记得自己在键盘弹窗里切一下")
            runCatching { parentFragmentManager.popBackStack() }
        }
    }

    // ------------------------------------------------------------ 小工具

    private fun resetApplyButton() {
        busy = false
        next.isEnabled = true
        next.text = "完成并应用"
    }

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
