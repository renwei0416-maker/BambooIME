/*
 * SPDX-FileCopyrightText: 2026 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.osfans.trime.R
import com.osfans.trime.data.prefs.ThemeStylePrefs
import com.osfans.trime.data.theme.BambooThemeOverrides
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.ui.settings.theme.ColorPickerDialog
import com.osfans.trime.ui.settings.theme.ThemeDraft
import com.osfans.trime.ui.settings.theme.KeyPath
import com.osfans.trime.ui.settings.theme.ThemePreviewView
import com.osfans.trime.ui.settings.theme.ThemeLuaExporter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.ArrayDeque

/**
 * 主题样式编辑器。
 *
 * 布局是「固定预览 + 可横滑分类页签 + 独立滚动内容区」，不再用 PreferenceFragmentCompat：
 * 那个方案把预览和设置项塞进同一个 RecyclerView，改下面的值时预览会滚出屏幕。
 *
 * 所有改动只写进草稿（[style] / [schemes]），点「应用」才落盘成覆盖层 JSON 并重载主题。
 */
class ThemeStyleFragment : Fragment() {

    private val bgColor = 0xFF15171C.toInt()
    private val textColor = 0xFFE3E6EB.toInt()
    private val subColor = 0xFF8A8F98.toInt()
    private val accentColor = 0xFFE8C87A.toInt()
    private val dividerColor = 0x1FFFFFFF

    private lateinit var previewBox: FrameLayout
    private lateinit var preview: ThemePreviewView
    private lateinit var chipsRow: LinearLayout
    private var titleView: TextView? = null
    private var modeChip: TextView? = null

    /** 下拉覆盖层的宿主（createView 里把整页包了一层 FrameLayout）。 */
    private var overlayHost: FrameLayout? = null
    private var pressedDemo = false
    private val expandedColorGroups = linkedSetOf("基础")
    private lateinit var tabsRow: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var applyBtn: TextView
    private lateinit var undoBtn: TextView

    private var themeId: String = ""
    private lateinit var style: GeneralStyle
    private var schemes: MutableList<ColorScheme> = mutableListOf()
    private var schemeId: String = ""
    private var keyboardName: String = "default"
    private var tab: Int = 0
    private lateinit var draft: ThemeDraft
    private var selectedKey: KeyPath? = null
    private var dirty = false
    private var lastY = 0f

    /** true = 预览框高度跟随内容自动调整；用户拖过手柄或双击后就进入手动/恢复自动。 */
    private var previewAutoHeight = true
    private val undoStack = ArrayDeque<Snapshot>()

    private var exportLuaContent: String = ""
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-lua"),
    ) { uri ->
        if (uri != null) {
            val ok = writeToUri(uri, exportLuaContent)
            toast(if (ok) "已导出主题" else "导出失败")
        }
    }

    private data class Snapshot(
        val style: GeneralStyle,
        val schemes: List<ColorScheme>,
        val patches: Map<KeyPath, Map<String, kotlinx.serialization.json.JsonElement?>>,
    )

    private val tabs = listOf(
        "键盘", "候选栏", "预编辑", "气泡", "工具栏", "液态键盘", "配色", "按键", "高级",
    )

    private companion object {
        const val OVERLAY_TAG = "theme_style_picker_overlay"
        const val SCOPE_ROW = 0
        const val SCOPE_LETTERS = 1
        const val SCOPE_FUNCS = 2
        const val SCOPE_ALL = 3
    }

    private val keyboardLabels = mapOf(
        "default" to "26键",
        "wanxiang_18key" to "18键",
        "wanxiang_14key" to "14键",
        "wanxiang_t9" to "9键",
        "wanxiang_pro" to "Pro",
        "xiaohe" to "小鹤",
        "qijian" to "七键",
        "wanxiang_english" to "英文",
        "number" to "数字",
        "editor" to "编辑",
        "calculator" to "计算",
        "settings" to "设置",
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.fragment_bamboo_theme_style, container, false)
        // 包一层 FrameLayout：下拉覆盖层（遮罩+列表）要能盖在整页之上
        val host = FrameLayout(inflater.context)
        host.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlayHost = host
        return host
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(bgColor)

        previewBox = view.findViewById(R.id.theme_style_preview_box)
        preview = view.findViewById(R.id.theme_style_preview)
        chipsRow = view.findViewById(R.id.theme_style_chips)
        titleView = view.findViewById(R.id.theme_style_title)
        tabsRow = view.findViewById(R.id.theme_style_tabs)
        content = view.findViewById(R.id.theme_style_content)
        scroll = view.findViewById(R.id.theme_style_scroll)
        applyBtn = view.findViewById(R.id.theme_style_apply)
        undoBtn = view.findViewById(R.id.theme_style_undo)
        modeChip = view.findViewById(R.id.theme_style_mode)

        loadState()
        setupPreviewBox(view)
        setupActions(view)
        setupTabs()
        rebuildChips()
        renderTab(0)
        refreshPreview()
    }

    private fun loadState() {
        // 主题 id 只有 ThemePrefs 知道（Theme 对象里没有 id 字段）；配色不匹配的问题已在预览侧用「传 scheme 对象」修掉
        themeId = runCatching { ThemeManager.prefs.selectedTheme.getValue() }.getOrDefault("catppuccin")
        val active = ThemeManager.activeTheme
        style = active.generalStyle
        schemes = active.colorSchemes.toMutableList()
        schemeId = schemes.firstOrNull()?.id ?: "default"
        keyboardName = pickDefaultKeyboard()
        draft = ThemeDraft(themeId, style, schemes, schemeId, keyboardName)
        selectedKey = null
        undoStack.clear()
        dirty = false
    }

    private fun pickDefaultKeyboard(): String {
        val names = ThemeManager.keyboardNames
        for (candidate in listOf("default", "wanxiang_18key", "wanxiang_t9")) {
            if (names.contains(candidate)) return candidate
        }
        return names.firstOrNull() ?: "default"
    }

    // ---------------------------------------------------------------- 预览区

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPreviewBox(root: View) {
        val saved = ThemeStylePrefs.previewHeight
        applyPreviewHeight(saved)
        preview.mode = if (ThemeStylePrefs.previewMode == 1) {
            ThemePreviewView.Mode.CROP
        } else {
            ThemePreviewView.Mode.FIT
        }

        preview.onKeyTapped = { path ->
            selectedKey = path
            if (tab != 5) renderTab(7) else renderTab(7)
        }

        val handle = root.findViewById<FrameLayout>(R.id.theme_style_handle)
        val detector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // 双击 = 回到「自动跟随内容」
                    previewAutoHeight = true
                    syncPreviewHeightToContent()
                    return true
                }
            },
        )
        handle.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> lastY = ev.rawY
                MotionEvent.ACTION_MOVE -> {
                    val delta = ((ev.rawY - lastY) / resources.displayMetrics.density).toInt()
                    if (delta != 0) {
                        setPreviewHeight(currentPreviewDp() - delta)
                        lastY = ev.rawY
                    }
                }

                MotionEvent.ACTION_UP -> ThemeStylePrefs.previewHeight = currentPreviewDp()
            }
            true
        }
    }

    private fun currentPreviewDp(): Int =
        (previewBox.layoutParams.height / resources.displayMetrics.density).toInt()

    /** 手动拖动/双击：记下高度并进入「手动」模式，之后不再自动跟随内容。 */
    private fun setPreviewHeight(dpValue: Int) {
        previewAutoHeight = false
        // 手动调高度 → 键盘等比缩放到框里（恢复拖动即缩放的行为）
        preview.fitToBox = true
        applyPreviewHeight(dpValue)
    }

    private fun applyPreviewHeight(dpValue: Int) {
        val v = dpValue.coerceIn(96, 420)
        if (previewBox.layoutParams.height == dp(v)) return
        previewBox.layoutParams = previewBox.layoutParams.apply { height = dp(v) }
        previewBox.requestLayout()
        ThemeStylePrefs.previewHeight = v
    }

    /** 按预览内容的真实高度撑开预览框（方案 A：最高 420dp，超出裁剪）。 */
    private fun syncPreviewHeightToContent() {
        if (!previewAutoHeight) return
        // 自动模式 → 宽度定标（调参只变高不变窄）
        preview.fitToBox = false
        preview.post {
            val need = preview.neededHeightDp()
            if (need > 0) applyPreviewHeight(need)
        }
    }

    private fun rebuildChips() {
        chipsRow.removeAllViews()

        // ① 键盘：一个带 ▾ 的下拉页签，标题显示「键盘 · 当前」
        val kbNames = ThemeManager.keyboardNames.ifEmpty { listOf("default") }
            .sortedBy { keyboardOrder(it) }
        chipsRow.addView(
            dropdownChip("键盘", keyboardLabels[keyboardName] ?: keyboardName) {
                showPickerOverlay(
                    "选择键盘",
                    kbNames.map { PickerItem(keyboardLabels[it] ?: it, it == keyboardName) },
                ) { index ->
                    val name = kbNames.getOrNull(index) ?: return@showPickerOverlay
                    keyboardName = name
                    ThemeStylePrefs.previewKeyboard = name
                    rebuildChips()
                    refreshPreview()
                }
            },
            chipParams(),
        )

        // ② 配色：单独一个带 ▾ 的下拉页签
        val schemeIds = schemes.map { it.id }
        chipsRow.addView(
            dropdownChip("配色", schemeId) {
                showPickerOverlay(
                    "选择配色",
                    schemeIds.map { PickerItem(it, it == schemeId) },
                ) { index ->
                    val id = schemeIds.getOrNull(index) ?: return@showPickerOverlay
                    schemeId = id
                    ThemeStylePrefs.previewScheme = id
                    rebuildChips()
                    renderTab(tab)
                    refreshPreview()
                }
            },
            chipParams(),
        )

        // ③ 等比 / 1:1：固定在最右的独立按钮（不在 chipsRow 里，见布局）
        val crop = preview.mode == ThemePreviewView.Mode.CROP
        modeChip?.let { chipView ->
            chipView.text = if (crop) "1:1" else "等比"
            chipView.setTextColor(if (crop) 0xFF1B1207.toInt() else textColor)
            chipView.background = if (crop) {
                GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat()
                    setColor(accentColor)
                }
            } else {
                requireContext().getDrawable(R.drawable.bg_theme_style_chip)
            }
            chipView.setOnClickListener {
                val nowCrop = preview.mode == ThemePreviewView.Mode.CROP
                preview.mode = if (nowCrop) ThemePreviewView.Mode.FIT else ThemePreviewView.Mode.CROP
                ThemeStylePrefs.previewMode = if (nowCrop) 0 else 1
                rebuildChips()
            }
        }
    }

    private data class PickerItem(val label: String, val selected: Boolean)

    /** 带 ▾ 的下拉页签：左边「组名 · 当前值」，右边 ▾，两处都可点。 */
    private fun dropdownChip(group: String, value: String, onOpen: () -> Unit): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = requireContext().getDrawable(R.drawable.bg_theme_style_chip)
            // 高度与「等比」按钮一致：靠内容 + 上下 5dp 撑起来，而不是写死高度
            setPadding(dp(11), dp(5), dp(2), dp(5))
            addView(
                TextView(requireContext()).apply {
                    text = "$group · $value"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(textColor)
                    includeFontPadding = false
                    setOnClickListener { onOpen() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                TextView(requireContext()).apply {
                    text = "▾"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(textColor)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setPadding(dp(6), 0, dp(8), 0)
                    setOnClickListener { onOpen() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    /**
     * 下拉覆盖层：半透明遮罩 + 居中列表（与符号面板的子分类下拉同一种手感）。
     * 点遮罩或选中某一项后自动移除。
     */
    private fun showPickerOverlay(
        title: String,
        items: List<PickerItem>,
        onPick: (Int) -> Unit,
    ) {
        val host = overlayHost ?: return
        // 只允许一层
        for (i in host.childCount - 1 downTo 0) {
            val child = host.getChildAt(i)
            if (child.tag == OVERLAY_TAG) host.removeViewAt(i)
        }
        if (items.isEmpty()) return

        val ctx = requireContext()
        val scrim = FrameLayout(ctx).apply {
            tag = OVERLAY_TAG
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            setOnClickListener { host.removeView(this) }
        }

        val rowH = dp(42)
        val headerH = dp(28)
        // 自适应：宽度用最宽那行文字算（上下限夹紧），高度按条目数、最多占屏高 45%
        val textPaint = android.text.TextPaint().apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                resources.displayMetrics,
            )
        }
        val widest = items.maxOf { textPaint.measureText(if (it.selected) "✓ ${it.label}" else "   ${it.label}") }
        val titlePaint = android.text.TextPaint().apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                11f,
                resources.displayMetrics,
            )
        }
        val widestTitle = titlePaint.measureText(title)
        val panelW = (maxOf(widest, widestTitle) + dp(36)).toInt().coerceIn(dp(132), dp(260))
        val maxList = (resources.displayMetrics.heightPixels * 0.45f).toInt() - headerH
        val contentH = (items.size * rowH).coerceAtMost(maxList)

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF0202429.toInt())
            }
            setPadding(0, dp(4), 0, dp(4))
        }
        panel.addView(
            TextView(ctx).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(subColor)
                includeFontPadding = false
                setPadding(dp(16), dp(4), dp(16), dp(6))
            },
            LinearLayout.LayoutParams(
                panelW,
                headerH,
            ),
        )

        val listBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        items.forEachIndexed { index, item ->
            listBox.addView(
                TextView(ctx).apply {
                    text = if (item.selected) "✓ ${item.label}" else "   ${item.label}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(if (item.selected) accentColor else textColor)
                    setTypeface(null, if (item.selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    setPadding(dp(16), 0, dp(16), 0)
                    setOnClickListener {
                        host.removeView(scrim)
                        onPick(index)
                    }
                },
                LinearLayout.LayoutParams(panelW, rowH),
            )
        }
        val listScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            addView(
                listBox,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panel.addView(
            listScroll,
            LinearLayout.LayoutParams(panelW, contentH),
        )

        scrim.addView(
            panel,
            FrameLayout.LayoutParams(
                panelW,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
            },
        )
        host.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun keyboardOrder(name: String): Int =
        keyboardLabels.keys.indexOf(name).let { if (it < 0) 99 else it }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit): TextView =
        TextView(requireContext()).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (on) 0xFF1B1207.toInt() else textColor)
            background = if (on) {
                GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat()
                    setColor(accentColor)
                }
            } else {
                requireContext().getDrawable(R.drawable.bg_theme_style_chip)
            }
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(5), dp(11), dp(5))
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun chipParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(6) }

    private fun dividerChipParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(1), dp(16)).apply {
            marginStart = dp(2)
            marginEnd = dp(8)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun dividerChip(): View = View(requireContext()).apply {
        setBackgroundColor(0x33FFFFFF)
    }

    // ---------------------------------------------------------------- 顶部动作

    private fun setupActions(root: View) {
        applyBtn.setOnClickListener { applyOverrides() }
        undoBtn.setOnClickListener { undo() }
        root.findViewById<TextView>(R.id.theme_style_more).setOnClickListener { showMoreMenu() }
        updateActionState()
    }

    private fun updateActionState() {
        applyBtn.alpha = if (dirty) 1f else 0.45f
        undoBtn.alpha = if (undoStack.isEmpty()) 0.45f else 1f
    }

    private fun showMoreMenu() {
        val items = arrayOf("恢复主题出厂样式", "导出为完整主题文件")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("更多")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> confirmResetFactory()
                    else -> exportTheme()
                }
            }
            .show()
    }

    private fun confirmResetFactory() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("恢复主题出厂样式")
            .setMessage("会删掉这个主题的所有自定义（$themeId），恢复成主题自带的样子。")
            .setPositiveButton("恢复") { _, _ ->
                BambooThemeOverrides.clear(themeId)
                ThemeManager.selectTheme(themeId, forceReload = true)
                loadState()
                rebuildChips()
                renderTab(tab)
                refreshPreview()
                updateActionState()
                toast("已恢复出厂样式")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把「基础主题 + 草稿覆盖」合并成 effective theme，导出为自包含 .lua。 */
    private fun exportTheme() {
        val active = ThemeManager.activeTheme
        val effective = active.copy(
            generalStyle = style,
            colorSchemes = schemes,
            presetKeyboards = active.presetKeyboards.mapValues { (name, kb) ->
                if (name == keyboardName) draft.patchedKeyboard(kb) else kb
            },
        )
        val lua = ThemeLuaExporter.buildLua(effective)
        val suggested = (effective.name.takeIf { it.isNotBlank() } ?: themeId) + ".lua"
        exportLuaContent = lua
        exportLauncher.launch(suggested)
    }

    private fun writeToUri(uri: Uri, content: String): Boolean = runCatching {
        val out = requireContext().contentResolver.openOutputStream(uri) ?: return false
        out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- 页签

    private fun setupTabs() {
        tabsRow.removeAllViews()
        tabs.forEachIndexed { index, title ->
            val tv = TextView(requireContext()).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                text = title
                setOnClickListener {
                    if (tab != index) renderTab(index)
                }
            }
            tabsRow.addView(
                tv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        paintTabs()
    }

    private fun paintTabs() {
        for (i in 0 until tabsRow.childCount) {
            val tv = tabsRow.getChildAt(i) as TextView
            val on = i == tab
            tv.setTextColor(if (on) accentColor else subColor)
            tv.typeface = if (on) {
                android.graphics.Typeface.DEFAULT_BOLD
            } else {
                android.graphics.Typeface.DEFAULT
            }
            tv.background = if (on) {
                GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(0x1AE8C87A)
                }
            } else {
                null
            }
        }
    }

    private fun renderTab(index: Int) {
        tab = index
        paintTabs()
        content.removeAllViews()
        when (index) {
            0 -> renderKeyboardAll()
            1 -> renderCandidateAll()
            2 -> renderPreedit()
            3 -> renderBubble()
            4 -> renderToolbarTab()
            5 -> renderLiquidTab()
            6 -> renderColors()
            7 -> renderKeyTab()
            else -> renderAdvanced()
        }
        scroll.scrollTo(0, 0)
    }

    // ---------------------------------------------------------------- 配色

    private fun renderColors() {
        val scheme = schemes.firstOrNull { it.id == schemeId } ?: schemes.firstOrNull()
        if (scheme == null) {
            content.addView(hint("这个主题没有配色表"))
            return
        }
        content.addView(sectionTitle("配色 · ${scheme.id}"))
        content.addView(
            hint(
                "共 ${colorGroups().sumOf { it.second.size }} 个颜色键，按用途分成 " +
                    "${colorGroups().size} 组；括号里 ← 后面是「没设置时跟谁」，只改当前这套配色。",
            ),
        )

        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(2), dp(16), dp(8))
        }
        actions.addView(
            chip("全部展开", false) {
                colorGroups().forEach { (g, _) -> expandedColorGroups.add(g) }
                renderTab(6)
            },
            chipParams(),
        )
        actions.addView(
            chip("全部折叠", false) {
                expandedColorGroups.clear()
                renderTab(6)
            },
            chipParams(),
        )
        content.addView(actions, rowParams())

        for ((group, items) in colorGroups()) {
            val open = group in expandedColorGroups
            val mark = if (open) "▾" else "▸"
            content.addView(plainRow("$mark $group（${items.size}）") {
                if (open) expandedColorGroups.remove(group) else expandedColorGroups.add(group)
                renderTab(6)
            }, rowParams())
            if (!open) continue
            for (item in items) {
                val key = item.first
                val label = item.second
                val fallback = item.third
                val title = if (fallback.isEmpty()) label else "$label（←$fallback）"
                content.addView(
                    colorRow(title, parseColor(scheme.colors[key])) { picked ->
                        val colors = LinkedHashMap(scheme.colors)
                        colors[key] = "0x" + String.format("%08x", picked)
                        replaceScheme(scheme.copy(colors = colors))
                        renderTab(6)
                        refreshPreview()
                    },
                    rowParams(),
                )
            }
        }

        content.addView(sectionTitle("删除自定义颜色"))
        content.addView(
            plainRow("清除本套配色里的所有「非标准」颜色键") {
                val standard = colorGroups().flatMap { g -> g.second.map { it.first } }.toSet()
                val colors = LinkedHashMap(scheme.colors.filterKeys { it in standard })
                replaceScheme(scheme.copy(colors = colors))
                dirty = true
                updateActionState()
                renderTab(6)
                toast("已清理，共保留 ${colors.size} 个标准键")
            },
            rowParams(),
        )
    }

    /** 配色分组：组名 → [(键, 显示名, 回退目标)]。回退目标来自 `theme-dsl.lua` 的 SchemeColors 注释。 */
    private fun colorGroups(): List<Pair<String, List<Triple<String, String, String>>>> = listOf(
        "基础" to listOf(
            Triple("back_color", "键盘底色", ""),
            Triple("text_color", "正文文字", ""),
            Triple("border_color", "描边色", "back_color"),
            Triple("shadow_color", "阴影色", "border_color"),
            Triple("key_shadow_color", "按键阴影", "shadow_color"),
            Triple("text_back_color", "编码区背景", "back_color"),
            Triple("root_background", "根背景", "back_color"),
        ),
        "候选栏" to listOf(
            Triple("candidate_text_color", "候选文字", "text_color"),
            Triple("comment_text_color", "注释文字", "candidate_text_color"),
            Triple("label_color", "候选序号", "candidate_text_color"),
            Triple("candidate_background", "候选栏背景图", "back_color"),
            Triple("candidate_border_color", "候选边框", "border_color"),
            Triple("candidate_separator_color", "候选分隔线", "border_color"),
            Triple("candidate_separator_color_unrolled", "候选分隔线(单行)", "candidate_separator_color"),
            Triple("hilited_text_color", "编码高亮文字", "text_color"),
            Triple("hilited_back_color", "编码高亮背景", "back_color"),
            Triple("hilited_candidate_text_color", "首选词文字", "hilited_text_color"),
            Triple("hilited_candidate_back_color", "首选词背景", "hilited_back_color"),
            Triple("hilited_candidate_button_color", "候选按钮高亮", "hilited_candidate_back_color"),
            Triple("hilited_label_color", "首选词序号", "hilited_candidate_text_color"),
            Triple("hilited_comment_text_color", "首选词注释", "comment_text_color"),
        ),
        "按键" to listOf(
            Triple("key_back_color", "按键底色", "back_color"),
            Triple("key_text_color", "按键文字", "candidate_text_color"),
            Triple("key_symbol_color", "按键符号", "comment_text_color"),
            Triple("key_border_color", "按键描边", "border_color"),
            Triple("hilited_key_back_color", "按下底色", "hilited_candidate_back_color"),
            Triple("hilited_key_text_color", "按下文字", "hilited_candidate_text_color"),
            Triple("hilited_key_symbol_color", "按下符号", "hilited_comment_text_color"),
            Triple("keyboard_back_color", "键盘底板", "border_color"),
            Triple("keyboard_background", "键盘背景图", "keyboard_back_color"),
        ),
        "开关按键" to listOf(
            Triple("on_key_back_color", "开启-底色", "back_color"),
            Triple("on_key_text_color", "开启-文字", "key_text_color"),
            Triple("on_key_symbol_color", "开启-符号", "key_symbol_color"),
            Triple("off_key_back_color", "关闭-底色", "key_back_color"),
            Triple("off_key_text_color", "关闭-文字", "key_text_color"),
            Triple("off_key_symbol_color", "关闭-符号", "key_symbol_color"),
            Triple("hilited_on_key_back_color", "按下开启-底色", "hilited_key_back_color"),
            Triple("hilited_on_key_text_color", "按下开启-文字", "hilited_key_text_color"),
            Triple("hilited_on_key_symbol_color", "按下开启-符号", "hilited_key_symbol_color"),
            Triple("hilited_off_key_back_color", "按下关闭-底色", "hilited_key_back_color"),
            Triple("hilited_off_key_text_color", "按下关闭-文字", "hilited_key_text_color"),
            Triple("hilited_off_key_symbol_color", "按下关闭-符号", "hilited_key_symbol_color"),
        ),
        "气泡与预览" to listOf(
            Triple("popup_back_color", "气泡底色", "key_back_color"),
            Triple("popup_text_color", "气泡文字", "key_text_color"),
            Triple("hilited_popup_back_color", "气泡选中底色", "hilited_key_back_color"),
            Triple("hilited_popup_text_color", "气泡选中文字", "hilited_key_text_color"),
            Triple("preview_back_color", "长按预览底色", "key_back_color"),
            Triple("preview_text_color", "长按预览文字", "key_text_color"),
            Triple("hilited_preview_back_color", "预览选中底色", "hilited_key_back_color"),
            Triple("hilited_preview_text_color", "预览选中文字", "hilited_key_text_color"),
        ),
        "长文本" to listOf(
            Triple("long_text_color", "长文本文字", "text_color"),
            Triple("long_text_back_color", "长文本背景", "back_color"),
        ),
        "液态键盘" to listOf(
            Triple("liquid_keyboard_background", "面板背景", "back_color"),
            Triple("liquid_keyboard_board", "面板底板", "keyboard_back_color"),
            Triple("liquid_keyboard_divider_color", "面板分隔线", "border_color"),
        ),
        "侧栏(T9)" to listOf(
            Triple("sidebar_back_color", "侧栏底色", "key_back_color"),
            Triple("sidebar_hilited_back_color", "侧栏选中底色", "hilited_key_back_color"),
            Triple("sidebar_text_color", "侧栏文字", "key_text_color"),
            Triple("sidebar_border_color", "侧栏描边", "key_border_color"),
            Triple("sidebar_spacing_color", "侧栏间隙", "border_color"),
        ),
        "剪贴板" to listOf(
            Triple("clipboard_category_back_color", "分类标签底色", "back_color"),
            Triple("clipboard_category_selected_back_color", "分类选中底色", "hilited_candidate_back_color"),
            Triple("clipboard_category_selected_text_color", "分类选中文字", "hilited_candidate_text_color"),
            Triple("clipboard_entry_back_color", "条目底色", "key_back_color"),
            Triple("hilited_clipboard_entry_back_color", "条目选中底色", "hilited_key_back_color"),
            Triple("clipboard_checkbox_color", "多选框颜色", "key_text_color"),
        ),
        "其它" to listOf(
            Triple("highlight_color", "高亮底色(非标准)", ""),
        ),
    )

    private fun replaceScheme(scheme: ColorScheme) {
        val i = schemes.indexOfFirst { it.id == scheme.id }
        if (i >= 0) schemes[i] = scheme
    }

    // ---------------------------------------------------------------- 键盘尺寸

    private fun renderKeyboard() {
        content.addView(sectionTitle("键盘尺寸"))
        content.addView(sliderRow("键盘高度", style.keyboardHeight.toFloat(), 180f, 360f, 1f, "dp") {
            style = style.copy(keyboardHeight = it.toInt())
            onChange()
        })
        content.addView(sliderRow("两侧留白", style.keyboardPadding.toFloat(), 0f, 20f, 1f, "dp") {
            style = style.copy(keyboardPadding = it.toInt())
            onChange()
        })
        content.addView(sliderRow("顶部留白", style.keyboardPaddingTop.toFloat(), 0f, 24f, 1f, "dp") {
            style = style.copy(keyboardPaddingTop = it.toInt())
            onChange()
        })
        content.addView(sliderRow("底部留白", style.keyboardPaddingBottom.toFloat(), 0f, 40f, 1f, "dp") {
            style = style.copy(keyboardPaddingBottom = it.toInt())
            onChange()
        })
        content.addView(sliderRow("横向间距", style.horizontalGap.toFloat(), 0f, 16f, 1f, "dp") {
            style = style.copy(horizontalGap = it.toInt())
            onChange()
        })
        content.addView(sliderRow("纵向间距", style.verticalGap.toFloat(), 0f, 16f, 1f, "dp") {
            style = style.copy(verticalGap = it.toInt())
            onChange()
        })
        content.addView(sliderRow("按键圆角", style.roundCorner, 0f, 24f, 0.5f, "dp") {
            style = style.copy(roundCorner = it)
            onChange()
        })
        content.addView(sliderRow("按键描边", style.keyBorder.toFloat(), 0f, 6f, 1f, "dp") {
            style = style.copy(keyBorder = it.toInt())
            onChange()
        })
    }

    // ---------------------------------------------------------------- 字号

    private fun renderFonts() {
        content.addView(sectionTitle("字号"))
        content.addView(hint("单位 sp；改完上方预览立刻变。"))
        val f = style.fonts
        content.addView(sliderRow("按键文字", f.key_size, 10f, 34f, 0.5f, "sp") {
            style = style.copy(fonts = style.fonts.copy(key_size = it))
            onChange()
        })
        content.addView(sliderRow("长按文字", f.key_long_size, 8f, 30f, 0.5f, "sp") {
            style = style.copy(fonts = style.fonts.copy(key_long_size = it))
            onChange()
        })
        content.addView(sliderRow("按键标签", f.label_size, 0f, 30f, 0.5f, "sp") {
            style = style.copy(fonts = style.fonts.copy(label_size = it))
            onChange()
        })
        content.addView(sliderRow("按键符号", f.symbol_size, 0f, 20f, 0.5f, "sp") {
            style = style.copy(fonts = style.fonts.copy(symbol_size = it))
            onChange()
        })
        content.addView(sliderRow("候选词", f.candidate_size, 10f, 30f, 0.5f, "sp") {
            style = style.copy(fonts = style.fonts.copy(candidate_size = it))
            onChange()
        })
        content.addView(sliderRow("释义", f.comment_size, 6f, 22f, 0.5f, "sp") {
            style = style.copy(fonts = style.fonts.copy(comment_size = it))
            onChange()
        })
        content.addView(sliderRow("提示", f.hint_size, 0f, 22f, 0.5f, "sp") {
            style = style.copy(fonts = style.fonts.copy(hint_size = it))
            onChange()
        })
        content.addView(sliderRow("弹窗", f.popup_size, 0f, 30f, 0.5f, "sp") {
            style = style.copy(fonts = style.fonts.copy(popup_size = it))
            onChange()
        })
    }

    // ---------------------------------------------------------------- 候选栏

    private fun renderCandidate() {
        content.addView(sectionTitle("候选栏"))
        content.addView(sliderRow("候选栏高度", style.candidateViewHeight.toFloat(), 20f, 56f, 1f, "dp") {
            style = style.copy(candidateViewHeight = it.toInt())
            onChange()
        })
        content.addView(sliderRow("候选间距", style.candidateSpacing, 0f, 24f, 0.5f, "dp") {
            style = style.copy(candidateSpacing = it)
            onChange()
        })
        content.addView(sliderRow("候选圆角", style.candidateCornerRadius, 0f, 16f, 0.5f, "dp") {
            style = style.copy(candidateCornerRadius = it)
            onChange()
        })
        content.addView(sliderRow("候选描边", style.candidateBorder.toFloat(), 0f, 4f, 1f, "dp") {
            style = style.copy(candidateBorder = it.toInt())
            onChange()
        })
    }

    // ---------------------------------------------------------------- 阴影

    private fun renderShadow() {
        content.addView(sectionTitle("阴影"))
        content.addView(hint("阴影大小 0 就是关掉；方向可多选。"))
        content.addView(sliderRow("阴影大小", style.keyShadowRadius, 0f, 12f, 0.5f, "dp") {
            style = style.copy(keyShadowRadius = it)
            onChange()
        })

        val dirs = listOf("up" to "上", "down" to "下", "left" to "左", "right" to "右")
        content.addView(
            multiChipsRow("阴影方向", dirs, style.keyShadowDirection.toSet()) { picked ->
                style = style.copy(
                    keyShadowDirection = dirs.map { it.first }.filter { it in picked },
                )
                onChange()
            },
        )

        val scheme = schemes.firstOrNull { it.id == schemeId } ?: schemes.firstOrNull()
        content.addView(
            colorRow("阴影颜色", parseColor(scheme?.colors?.get("shadow_color"))) { picked ->
                if (scheme != null) {
                    val colors = LinkedHashMap(scheme.colors)
                    colors["shadow_color"] = "0x" + String.format("%08x", picked)
                    replaceScheme(scheme.copy(colors = colors))
                    renderTab(tab)
                    refreshPreview()
                }
            },
        )
    }

    // ---------------------------------------------------------------- 行控件

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    private fun sectionTitle(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(accentColor)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(16), dp(12), dp(16), dp(2))
    }

    private fun hint(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(subColor)
        setPadding(dp(16), 0, dp(16), dp(6))
    }

    private fun colorRow(title: String, color: Int?, onPick: (Int) -> Unit): View {
        val line = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            minimumHeight = dp(44)
        }
        val name = TextView(requireContext()).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textColor)
        }
        val value = TextView(requireContext()).apply {
            text = color?.let { String.format("#%08X", it) } ?: "未设置"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(subColor)
            gravity = Gravity.END
            setPadding(0, 0, dp(10), 0)
        }
        val swatch = View(requireContext()).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(color ?: 0x33FFFFFF)
                setStroke(dp(1), 0x44FFFFFF)
            }
        }
        line.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(
            value,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        line.addView(swatch, LinearLayout.LayoutParams(dp(30), dp(30)))
        line.setOnClickListener {
            ColorPickerDialog.show(requireContext(), title, color ?: 0xFF000000.toInt()) { picked ->
                pushUndo()
                onPick(picked)
                dirty = true
                updateActionState()
            }
        }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(line, rowParams())
            addView(divider())
        }
    }

    private fun divider(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        }
        setBackgroundColor(dividerColor)
    }

    private fun sliderRow(
        title: String,
        value: Float,
        min: Float,
        max: Float,
        step: Float,
        unit: String,
        onChange: (Float) -> Unit,
    ): View {
        val wrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(2))
        }
        val head = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = TextView(requireContext()).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textColor)
        }
        val valView = TextView(requireContext()).apply {
            text = fmt(value) + unit
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(accentColor)
            gravity = Gravity.END
        }
        head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(
            valView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        wrap.addView(head)

        val steps = (((max - min) / step).toInt()).coerceAtLeast(1)
        val bar = SeekBar(requireContext()).apply {
            this.max = steps
            progress = (((value - min) / step).toInt()).coerceIn(0, steps)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + progress * step
                valView.text = fmt(v) + unit
                onChange(v)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                pushUndo()
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                dirty = true
                updateActionState()
            }
        })
        wrap.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)),
        )
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(wrap, rowParams())
            addView(divider())
        }
    }

    private fun multiChipsRow(
        title: String,
        options: List<Pair<String, String>>,
        selected: Set<String>,
        onChange: (Set<String>) -> Unit,
    ): View {
        val wrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        wrap.addView(
            TextView(requireContext()).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(textColor)
            },
        )
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val state = selected.toMutableSet()
        for ((key, label) in options) {
            val on = key in state
            val tv = TextView(requireContext()).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (on) 0xFF1B1207.toInt() else textColor)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(5), dp(14), dp(5))
                background = if (on) {
                    GradientDrawable().apply {
                        cornerRadius = dp(13).toFloat()
                        setColor(accentColor)
                    }
                } else {
                    requireContext().getDrawable(R.drawable.bg_theme_style_chip)
                }
                setOnClickListener {
                    pushUndo()
                    if (state.contains(key)) state.remove(key) else state.add(key)
                    dirty = true
                    updateActionState()
                    onChange(state.toSet())
                    renderTab(tab)
                    refreshPreview()
                }
            }
            row.addView(tv, chipParams())
        }
        wrap.addView(row)
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(wrap, rowParams())
            addView(divider())
        }
    }

    // ---------------------------------------------------------------- 单键

    private fun renderKeyTab() {
        content.addView(sectionTitle("按键"))
        val path = selectedKey
        if (path == null) {
            content.addView(hint("点上面预览里的一个键，这里显示它所有可配置项；每项右边的 ↺ 恢复「继承」。"))
            return
        }
        val tk = ThemeManager.getKeyboard(path.keyboard)
            ?.rows?.getOrNull(path.row)?.keys?.getOrNull(path.column)
        content.addView(
            hint(
                (keyboardLabels[path.keyboard] ?: path.keyboard) +
                    " › 第 " + (path.row + 1) + " 行 › 第 " + (path.column + 1) + " 键",
            ),
        )
        if (tk != null) {
            val info = StringBuilder()
            info.append("click = ").append(tk.click.ifEmpty { "无" })
            if (tk.spacer) info.append("\n这是占位键（spacer），只占宽度不显示")
            val pop = runCatching { tk.popup }.getOrNull()
            if (!pop.isNullOrEmpty()) info.append("\n长按弹出 = ").append(pop.joinToString(" / "))
            content.addView(hint(info.toString()))
        }

        content.addView(sectionTitle("颜色"))
        content.addView(hint("「继承」= 不写这个字段，跟着整套配色走；改成固定值后，整套配色再变它不会跟着变。"))
        for ((label, field) in keyColorFields()) {
            content.addView(keyColorRow(label, path, field), rowParams())
        }

        content.addView(sectionTitle("字号（sp，继承 = 跟随全局）"))
        for ((label, field) in keySizeFields()) {
            content.addView(keyNumberRow(label, path, field, 6f, 40f, 0.5f), rowParams())
        }

        content.addView(sectionTitle("位置微调（dp）"))
        for ((label, field) in keyOffsetFields()) {
            content.addView(keyNumberRow(label, path, field, -20f, 20f, 0.5f), rowParams())
        }

        content.addView(sectionTitle("形状"))
        content.addView(keyNumberRow("按键圆角", path, "round_corner", 0f, 24f, 1f, allowInherit = true), rowParams())
        content.addView(keyNumberRow("描边宽度", path, "key_border", 0f, 6f, 1f, allowInherit = true), rowParams())

        content.addView(sectionTitle("按范围批量改色"))
        content.addView(
            hint(
                "一次给同一范围内的所有键写同一个颜色。写的是键盘里的字面值（等于这些键各自" +
                    "「不再继承」），预览立刻可见，切配色也不会变。",
            ),
        )
        content.addView(batchRow("整行（第 " + (path.row + 1) + " 行）", SCOPE_ROW), rowParams())
        content.addView(batchRow("本键盘所有字母键", SCOPE_LETTERS), rowParams())
        content.addView(batchRow("本键盘所有功能键", SCOPE_FUNCS), rowParams())
        content.addView(batchRow("本键盘全部键", SCOPE_ALL), rowParams())
        content.addView(
            plainRow("清除本键盘所有按键的覆盖") {
                pushUndo()
                draft.patches.keys.filter { it.keyboard == keyboardName }.forEach { draft.clearAll(it) }
                onChange()
                renderTab(7)
                toast("已清空本键盘的按键覆盖")
            },
            rowParams(),
        )

        content.addView(
            plainRow("清除这个键的全部覆盖") {
                pushUndo()
                draft.clearAll(path)
                onChange()
                renderTab(7)
            },
            rowParams(),
        )
    }

    private fun keyColorFields(): List<Pair<String, String>> = listOf(
        "底色" to "key_back_color",
        "文字色" to "key_text_color",
        "符号色" to "key_symbol_color",
        "描边色" to "key_border_color",
        "按下底色" to "hilited_key_back_color",
        "按下文字色" to "hilited_key_text_color",
        "按下符号色" to "hilited_key_symbol_color",
    )

    private fun keySizeFields(): List<Pair<String, String>> = listOf(
        "文字字号" to "key_text_size",
        "符号字号" to "symbol_text_size",
        "提示字号" to "hint_text_size",
    )

    private fun keyOffsetFields(): List<Pair<String, String>> = listOf(
        "文字 X" to "key_text_offset_x",
        "文字 Y" to "key_text_offset_y",
        "上档 X" to "key_symbol_offset_x",
        "上档 Y" to "key_symbol_offset_y",
        "提示 X" to "key_hint_offset_x",
        "提示 Y" to "key_hint_offset_y",
    )

    /** 本键盘里符合范围的非 spacer 键。 */
    private fun keysInScope(scope: Int): List<KeyPath> {
        val kb = ThemeManager.getKeyboard(keyboardName) ?: return emptyList()
        val out = ArrayList<KeyPath>()
        kb.rows.forEachIndexed { rowIndex, row ->
            row.keys.forEachIndexed { colIndex, key ->
                if (key.spacer) return@forEachIndexed
                val click = runCatching { key.click }.getOrDefault("")
                val isLetter = click.length == 1 && click[0] in 'a'..'z'
                val take = when (scope) {
                    SCOPE_ROW -> selectedKey?.row == rowIndex
                    SCOPE_LETTERS -> isLetter
                    SCOPE_FUNCS -> !isLetter
                    else -> true
                }
                if (take) out.add(KeyPath(keyboardName, rowIndex, colIndex))
            }
        }
        return out
    }

    private fun batchRow(title: String, scope: Int): View {
        val targets = keysInScope(scope)
        val shown = if (targets.isEmpty()) "没有可改的键" else "${targets.size} 个键"
        return fieldRow(title, shown, null, onRestore = null) {
            if (targets.isEmpty()) {
                toast("这个范围里没有可改的键")
                return@fieldRow
            }
            val fields = arrayOf("按键底色", "按键文字色", "按下底色", "按键符号色")
            val jsonFields = arrayOf("key_back_color", "key_text_color", "hilited_key_back_color", "key_symbol_color")
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("$title · 改哪个颜色")
                .setItems(fields) { _, fi -> pickBatchColor(title, targets, jsonFields[fi]) }
                .show()
        }
    }

    private fun pickBatchColor(title: String, targets: List<KeyPath>, field: String) {
        val items = arrayOf("自定义颜色…", "引用配色里的色键…")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("$title · $field")
            .setItems(items) { _, which ->
                if (which == 0) {
                    ColorPickerDialog.show(requireContext(), "$title · $field", 0xFF33363B.toInt()) { picked ->
                        applyBatch(targets, field, "0x" + String.format("%08x", picked))
                    }
                } else {
                    val keys = schemeNow()?.colors?.keys?.sorted() ?: emptyList()
                    if (keys.isEmpty()) {
                        toast("当前配色没有可引用的色键")
                        return@setItems
                    }
                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("选一个色键")
                        .setItems(keys.toTypedArray()) { _, i -> applyBatch(targets, field, keys[i]) }
                        .show()
                }
            }
            .show()
    }

    private fun applyBatch(targets: List<KeyPath>, field: String, value: String) {
        pushUndo()
        for (p in targets) draft.setField(p, field, JsonPrimitive(value))
        onChange()
        renderTab(7)
        toast("已写入 ${targets.size} 个键的 $field")
    }

    private fun rawText(path: KeyPath, field: String): String? =
        draft.rawField(path, field)?.let { el ->
            runCatching { el.toString().trim('"') }.getOrNull()
        }

    private fun keyColorRow(title: String, path: KeyPath, field: String): View {
        val raw = rawText(path, field)
        val shown = when {
            raw == null -> "继承"
            raw.isEmpty() -> "继承"
            else -> raw
        }
        val color = runCatching { parseColor(raw) }.getOrNull()
        return fieldRow(title, shown, color, onRestore = {
            pushUndo()
            draft.clearField(path, field)
            onChange()
            renderTab(7)
        }) {
            val items = arrayOf("继承（跟随整套配色）", "指定配色里的色键…", "自定义颜色…")
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> {
                            pushUndo()
                            draft.clearField(path, field)
                            onChange()
                            renderTab(7)
                        }

                        1 -> {
                            val scheme = schemeNow()
                            val keys = scheme?.colors?.keys?.sorted() ?: emptyList()
                            if (keys.isEmpty()) {
                                toast("当前配色没有可引用的色键")
                                return@setItems
                            }
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("选一个色键")
                                .setItems(keys.toTypedArray()) { _, i ->
                                    pushUndo()
                                    draft.setField(path, field, JsonPrimitive(keys[i]))
                                    onChange()
                                    renderTab(7)
                                }
                                .show()
                        }

                        else -> {
                            ColorPickerDialog.show(requireContext(), title, color ?: 0xFF000000.toInt()) { picked ->
                                pushUndo()
                                draft.setField(path, field, JsonPrimitive("0x" + String.format("%08x", picked)))
                                onChange()
                                renderTab(7)
                            }
                        }
                    }
                }
                .show()
        }
    }

    private fun keyNumberRow(
        title: String,
        path: KeyPath,
        field: String,
        min: Float,
        max: Float,
        step: Float,
        allowInherit: Boolean = true,
    ): View {
        val raw = rawText(path, field)
        val shown = if (raw.isNullOrEmpty() || raw == "-1" || raw == "-1.0") {
            if (allowInherit) "继承" else "0"
        } else {
            raw
        }
        return fieldRow(title, shown, null, onRestore = if (allowInherit) {
            {
                pushUndo()
                draft.clearField(path, field)
                onChange()
                renderTab(7)
            }
        } else {
            null
        }) {
            val input = android.widget.EditText(requireContext()).apply {
                setText(if (shown == "继承") "" else shown)
                hint = "留空 = 继承"
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val text = input.text.toString().trim()
                    pushUndo()
                    if (text.isEmpty()) {
                        draft.clearField(path, field)
                    } else {
                        val v = text.toFloatOrNull()
                        if (v == null) {
                            toast("请输入数字")
                            return@setPositiveButton
                        }
                        val clamped = v.coerceIn(min, max)
                        draft.setField(
                            path, field,
                            JsonPrimitive(if (clamped % (step * 10) == 0f) clamped.toInt() else clamped),
                        )
                    }
                    onChange()
                    renderTab(7)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 通用「标题 + 值 + ↺」行。 */
    private fun fieldRow(
        title: String,
        value: String,
        swatch: Int?,
        onRestore: (() -> Unit)?,
        onClick: () -> Unit,
    ): View {
        val line = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            minimumHeight = dp(44)
        }
        line.addView(
            TextView(requireContext()).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(textColor)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        line.addView(
            TextView(requireContext()).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(subColor)
                gravity = Gravity.END
                setPadding(0, 0, dp(10), 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        if (swatch != null) {
            line.addView(
                View(requireContext()).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(7).toFloat()
                        setColor(swatch)
                        setStroke(dp(1), 0x44FFFFFF)
                    }
                },
                LinearLayout.LayoutParams(dp(28), dp(28)),
            )
        }
        if (onRestore != null) {
            line.addView(
                TextView(requireContext()).apply {
                    text = "↺"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(subColor)
                    gravity = Gravity.CENTER
                    setPadding(dp(10), 0, 0, 0)
                    setOnClickListener { onRestore() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        line.setOnClickListener { onClick() }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(line, rowParams())
            addView(divider())
        }
    }

    private fun plainRow(title: String, onClick: () -> Unit): View {
        val line = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            minimumHeight = dp(46)
        }
        line.addView(
            TextView(requireContext()).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFFE0857A.toInt())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        line.setOnClickListener { onClick() }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(line, rowParams())
            addView(divider())
        }
    }

    private fun schemeNow(): com.osfans.trime.data.theme.model.ColorScheme? =
        schemes.firstOrNull { it.id == schemeId } ?: schemes.firstOrNull()

    // ---------------------------------------------------------------- 数据流

    private fun onChange() {
        dirty = true
        updateActionState()
        refreshPreview()
    }

    private fun refreshPreview() {
        draft.style = style
        draft.schemes.clear()
        draft.schemes.addAll(schemes)
        draft.schemeId = schemeId
        draft.keyboardName = keyboardName
        if (pressedDemo) preview.selectedKey = preview.selectedKey else preview.selectedKey = selectedKey
        preview.setDraft(draft)
        syncPreviewHeightToContent()
        titleView?.text = themeId + " · " + schemeId
        preview.post { titleView?.text = themeId + " · " + preview.colorSummary() }
    }

    private fun pushUndo() {
        if (undoStack.size >= 40) undoStack.removeFirst()
        undoStack.addLast(
            Snapshot(
                style, schemes.toList(),
                draft.patches.mapValues { LinkedHashMap(it.value) },
            ),
        )
        updateActionState()
    }

    private fun undo() {
        val snap = undoStack.pollLast() ?: return
        style = snap.style
        schemes = snap.schemes.toMutableList()
        draft.patches.clear()
        snap.patches.forEach { (k, v) -> draft.patches[k] = LinkedHashMap(v) }
        dirty = true
        updateActionState()
        renderTab(tab)
        refreshPreview()
    }

    private fun applyOverrides() {
        val styleJson = Theme.json.encodeToJsonElement(GeneralStyle.serializer(), style)
        val schemesJson = JsonArray(
            schemes.map { Theme.json.encodeToJsonElement(ColorScheme.serializer(), it) },
        )
        val obj = buildJsonObject {
            put("style", styleJson)
            put("preset_color_schemes", schemesJson)
            val baseKb = runCatching { ThemeManager.getKeyboard(keyboardName) }.getOrNull()
            if (baseKb != null) {
                draft.keyboardJsonFor(baseKb)?.let { kbJson ->
                    put(
                        "preset_keyboards",
                        buildJsonObject { put(keyboardName, kbJson) },
                    )
                }
            }
            // 界面模块（preedit / sync_input / window / tool_bar / candidates_tool / liquid_keyboard）
            for ((moduleName, moduleJson) in moduleDrafts) {
                put(moduleName, moduleJson)
            }
        }
        if (!BambooThemeOverrides.write(themeId, obj)) {
            toast("写入失败，检查存储权限")
            return
        }
        ThemeManager.selectTheme(themeId, forceReload = true)
        dirty = false
        undoStack.clear()
        updateActionState()
        toast("已应用，键盘已更新")
    }

    // ---------------------------------------------------------------- 键盘（样式全字段）

    private fun renderKeyboardAll() {
        renderKeyboard()
        content.addView(sectionTitle("横屏"))
        content.addView(
            sliderRow("横屏键盘高度", style.keyboardHeightLand.toFloat(), 120f, 360f, 1f, "dp") {
                style = style.copy(keyboardHeightLand = it.toInt())
                onChange()
            },
        )
        content.addView(
            sliderRow("横屏左右边距", style.keyboardPaddingLand.toFloat(), 0f, 20f, 1f, "dp") {
                style = style.copy(keyboardPaddingLand = it.toInt())
                onChange()
            },
        )
        content.addView(
            sliderRow("横屏底部边距", style.keyboardPaddingLandBottom.toFloat(), 0f, 40f, 1f, "dp") {
                style = style.copy(keyboardPaddingLandBottom = it.toInt())
                onChange()
            },
        )
        content.addView(
            sliderRow("面板内容内缩", style.contentPadding.toFloat(), 0f, 40f, 1f, "dp") {
                style = style.copy(contentPadding = it.toInt())
                onChange()
            },
        )
        content.addView(hint("「面板内容内缩」用于图片背景的透明边框，普通主题保持 0。"))

        renderShadow()

        content.addView(sectionTitle("按键字号"))
        content.addView(hint("单位 sp。0 = 跟随「按键文字」。"))
        val f = style.fonts
        content.addView(
            sliderRow("按键文字", f.key_size, 10f, 34f, 0.5f, "sp") {
                style = style.copy(fonts = style.fonts.copy(key_size = it))
                onChange()
            },
        )
        content.addView(
            sliderRow("长按文字", f.key_long_size, 8f, 30f, 0.5f, "sp") {
                style = style.copy(fonts = style.fonts.copy(key_long_size = it))
                onChange()
            },
        )
        content.addView(
            sliderRow("副标签", f.label_size, 0f, 30f, 0.5f, "sp") {
                style = style.copy(fonts = style.fonts.copy(label_size = it))
                onChange()
            },
        )
        content.addView(
            sliderRow("上滑符号", f.symbol_size, 0f, 20f, 0.5f, "sp") {
                style = style.copy(fonts = style.fonts.copy(symbol_size = it))
                onChange()
            },
        )
        content.addView(
            sliderRow("下滑提示", f.hint_size, 0f, 22f, 0.5f, "sp") {
                style = style.copy(fonts = style.fonts.copy(hint_size = it))
                onChange()
            },
        )

        content.addView(sectionTitle("文字与图标位置微调"))
        content.addView(hint("正数向右/向下；一般只在主题本身没对齐时才需要动。"))
        content.addView(
            sliderRow("按键文字 X", style.keyTextOffsetX, -20f, 20f, 1f, "dp") {
                style = style.copy(keyTextOffsetX = it)
                onChange()
            },
        )
        content.addView(
            sliderRow("按键文字 Y", style.keyTextOffsetY, -20f, 20f, 1f, "dp") {
                style = style.copy(keyTextOffsetY = it)
                onChange()
            },
        )
        content.addView(
            sliderRow("符号 X", style.keySymbolOffsetX, -20f, 20f, 1f, "dp") {
                style = style.copy(keySymbolOffsetX = it)
                onChange()
            },
        )
        content.addView(
            sliderRow("符号 Y", style.keySymbolOffsetY, -20f, 20f, 1f, "dp") {
                style = style.copy(keySymbolOffsetY = it)
                onChange()
            },
        )
        content.addView(
            sliderRow("提示 X", style.keyHintOffsetX, -20f, 20f, 1f, "dp") {
                style = style.copy(keyHintOffsetX = it)
                onChange()
            },
        )
        content.addView(
            sliderRow("提示 Y", style.keyHintOffsetY, -20f, 20f, 1f, "dp") {
                style = style.copy(keyHintOffsetY = it)
                onChange()
            },
        )
        content.addView(
            sliderRow("按下位移 X", style.keyPressOffsetX, -10f, 10f, 1f, "dp") {
                style = style.copy(keyPressOffsetX = it)
                onChange()
            },
        )
        content.addView(
            sliderRow("按下位移 Y", style.keyPressOffsetY, -10f, 10f, 1f, "dp") {
                style = style.copy(keyPressOffsetY = it)
                onChange()
            },
        )
    }

    // ---------------------------------------------------------------- 候选栏（含注释）

    private fun renderCandidateAll() {
        renderCandidate()
        content.addView(
            sliderRow("候选项内边距", style.candidatePadding.toFloat(), 0f, 24f, 1f, "dp") {
                style = style.copy(candidatePadding = it.toInt())
                onChange()
            },
        )
        content.addView(
            sliderRow("候选间距", style.candidateSpacing, 0f, 24f, 1f, "dp") {
                style = style.copy(candidateSpacing = it)
                onChange()
            },
        )
        content.addView(
            sliderRow("候选文字垂直偏移", style.candidateTextVerticalBias, 0f, 1f, 0.05f, "") {
                style = style.copy(candidateTextVerticalBias = it)
                onChange()
            },
        )
        content.addView(
            sliderRow("候选边框粗细", style.candidateBorder.toFloat(), 0f, 6f, 1f, "dp") {
                style = style.copy(candidateBorder = it.toInt())
                onChange()
            },
        )
        content.addView(
            sliderRow("候选项圆角", style.candidateCornerRadius, 0f, 24f, 0.5f, "dp") {
                style = style.copy(candidateCornerRadius = it)
                onChange()
            },
        )
        content.addView(
            sliderRow("候选边框圆角", style.candidateBorderRound, 0f, 24f, 0.5f, "dp") {
                style = style.copy(candidateBorderRound = it)
                onChange()
            },
        )
        content.addView(
            multiChipsRow(
                "显示候选序号",
                listOf("on" to "显示", "off" to "不显示"),
                if (style.candidateLabel) setOf("on") else setOf("off"),
            ) { picked ->
                style = style.copy(candidateLabel = "on" in picked)
                onChange()
            },
        )

        content.addView(sectionTitle("注释 / 释义"))
        content.addView(
            sliderRow("注释区高度", style.commentHeight.toFloat(), 0f, 40f, 1f, "dp") {
                style = style.copy(commentHeight = it.toInt())
                onChange()
            },
        )
        content.addView(
            multiChipsRow(
                "注释位置",
                listOf("RIGHT" to "右侧", "TOP" to "上方", "OVERLAY" to "叠加"),
                setOf(
                    when (style.commentPosition.name.uppercase()) {
                        "TOP" -> "TOP"
                        "OVERLAY" -> "OVERLAY"
                        else -> "RIGHT"
                    },
                ),
            ) { picked ->
                val v = picked.firstOrNull() ?: "RIGHT"
                style = style.copy(
                    commentPosition = com.osfans.trime.data.theme.model.GeneralStyle.CommentPosition.valueOf(v),
                )
                onChange()
            },
        )
        content.addView(
            sliderRow("注释垂直偏移", style.commentVerticalBias, 0f, 1f, 0.05f, "") {
                style = style.copy(commentVerticalBias = it)
                onChange()
            },
        )
        content.addView(
            sliderRow("候选序号字号", style.fonts.candidate_label_size, 0f, 24f, 0.5f, "sp") {
                style = style.copy(fonts = style.fonts.copy(candidate_label_size = it))
                onChange()
            },
        )
        content.addView(
            sliderRow("注释字号", style.fonts.comment_size, 6f, 22f, 0.5f, "sp") {
                style = style.copy(fonts = style.fonts.copy(comment_size = it))
                onChange()
            },
        )
    }

    // ---------------------------------------------------------------- 预编辑区 / 同步输入框（模块）

    private fun renderPreedit() {
        content.addView(sectionTitle("预编辑区"))
        content.addView(hint("输入框上方那行编码区的样式。这些字段属于主题的 preedit 模块，点「应用」后生效。"))
        content.addView(moduleSliderRow("preedit", "horizontal_padding", "左右内边距", 0f, 32f, 1f, "dp", 8f))
        content.addView(moduleSliderRow("preedit", "alpha", "不透明度", 0.1f, 1f, 0.05f, "", 0.8f))
        content.addView(moduleSliderRow("preedit", "top_end_radius", "上方圆角", 0f, 32f, 1f, "dp", 0f))
        content.addView(moduleSliderRow("preedit", "foreground.font_size", "字号", 10f, 30f, 0.5f, "sp", 16f))

        content.addView(sectionTitle("悬浮同步输入框"))
        content.addView(hint("外接键盘「同步输入框」用的那条悬浮输入条。"))
        content.addView(moduleSliderRow("sync_input", "font_size", "字号", 10f, 30f, 0.5f, "sp", 16f))
        content.addView(moduleSliderRow("sync_input", "corner_radius", "圆角", 0f, 24f, 1f, "dp", 8f))
        content.addView(moduleSliderRow("sync_input", "horizontal_padding", "左右内边距", 0f, 32f, 1f, "dp", 8f))
        content.addView(moduleSliderRow("sync_input", "vertical_padding", "上下内边距", 0f, 24f, 1f, "dp", 6f))
        content.addView(moduleSliderRow("sync_input", "alpha", "不透明度", 0.1f, 1f, 0.05f, "", 0.8f))
        content.addView(moduleSliderRow("sync_input", "max_height", "最大高度（0=不限制）", 0f, 400f, 10f, "dp", 0f))
    }

    // ---------------------------------------------------------------- 气泡 / 候选窗口

    private fun renderBubble() {
        content.addView(sectionTitle("按键气泡"))
        content.addView(hint("长按按键时弹出来的那个提示框。"))
        content.addView(
            sliderRow("气泡宽度", style.popupWidth.toFloat(), 20f, 90f, 1f, "dp") {
                style = style.copy(popupWidth = it.toInt())
                onChange()
            },
        )
        content.addView(
            sliderRow("气泡高度", style.popupHeight.toFloat(), 20f, 90f, 1f, "dp") {
                style = style.copy(popupHeight = it.toInt())
                onChange()
            },
        )
        content.addView(
            sliderRow("气泡按键高", style.popupKeyHeight.toFloat(), 20f, 90f, 1f, "dp") {
                style = style.copy(popupKeyHeight = it.toInt())
                onChange()
            },
        )
        content.addView(
            sliderRow("气泡底部边距", style.popupBottomMargin.toFloat(), 0f, 40f, 1f, "dp") {
                style = style.copy(popupBottomMargin = it.toInt())
                onChange()
            },
        )
        content.addView(
            sliderRow("气泡字号", style.fonts.popup_size, 0f, 30f, 0.5f, "sp") {
                style = style.copy(fonts = style.fonts.copy(popup_size = it))
                onChange()
            },
        )

        content.addView(sectionTitle("候选窗口"))
        content.addView(hint("悬浮候选窗口（不是候选栏）的四周边距与字号；window 模块。"))
        content.addView(moduleSliderRow("window", "insets.vertical", "上下外距", 0f, 40f, 1f, "dp", 4f))
        content.addView(moduleSliderRow("window", "insets.horizontal", "左右外距", 0f, 40f, 1f, "dp", 4f))
        content.addView(moduleSliderRow("window", "item_padding.vertical", "项上下内距", 0f, 24f, 1f, "dp", 2f))
        content.addView(moduleSliderRow("window", "item_padding.horizontal", "项左右内距", 0f, 24f, 1f, "dp", 4f))
        content.addView(moduleSliderRow("window", "min_width", "最小宽度", 0f, 400f, 4f, "dp", 0f))
        content.addView(moduleSliderRow("window", "corner_radius", "圆角", 0f, 32f, 1f, "dp", 0f))
        content.addView(moduleSliderRow("window", "border", "边框粗细", 0f, 8f, 1f, "dp", 0f))
        content.addView(moduleSliderRow("window", "alpha", "不透明度", 0.1f, 1f, 0.05f, "", 1f))
        content.addView(moduleSliderRow("window", "foreground.text_font_size", "候选字号", 8f, 32f, 0.5f, "sp", 20f))
        content.addView(moduleSliderRow("window", "foreground.comment_font_size", "注释字号", 6f, 24f, 0.5f, "sp", 20f))
        content.addView(moduleSliderRow("window", "foreground.label_font_size", "序号字号", 6f, 24f, 0.5f, "sp", 20f))
    }

    // ---------------------------------------------------------------- 工具栏 / 候选工具栏

    private fun renderToolbarTab() {
        content.addView(sectionTitle("工具栏"))
        content.addView(hint("键盘上方那条工具条；按钮列表（buttons）是数组，这里只调样式，增删按钮请改主题文件。"))
        content.addView(moduleTextRow("tool_bar", "back_style", "返回按钮图标"))
        content.addView(moduleSliderRow("tool_bar", "primary_button.background.corner_radius", "主按钮圆角", 0f, 32f, 1f, "dp", 10f))
        content.addView(moduleSliderRow("tool_bar", "primary_button.background.vertical_inset", "主按钮上下内缩", 0f, 16f, 1f, "dp", 4f))
        content.addView(moduleSliderRow("tool_bar", "primary_button.background.horizontal_inset", "主按钮左右内缩", 0f, 16f, 1f, "dp", 4f))
        content.addView(moduleSliderRow("tool_bar", "primary_button.foreground.font_size", "主按钮字号", 8f, 30f, 0.5f, "sp", 18f))
        content.addView(moduleSliderRow("tool_bar", "primary_button.foreground.padding", "主按钮内边距", 0f, 16f, 1f, "dp", 4f))
        content.addView(moduleColorRow("tool_bar", "primary_button.foreground.normal", "主按钮图标色"))

        content.addView(sectionTitle("候选工具栏"))
        content.addView(hint("候选栏右侧那排小按钮；主题里没有这个模块时保持默认。"))
        content.addView(moduleSliderRow("candidates_tool", "nav_width", "导航条宽度", 0f, 120f, 1f, "dp", 44f))
        content.addView(moduleSliderRow("candidates_tool", "popup_width", "弹出宽度", 0f, 240f, 2f, "dp", 0f))
        content.addView(moduleSliderRow("candidates_tool", "popup_text_size", "弹出字号", 8f, 24f, 0.5f, "sp", 14f))
        content.addView(moduleColorRow("candidates_tool", "popup_text_color", "弹出文字色"))
        content.addView(moduleColorRow("candidates_tool", "popup_background_color", "弹出背景色"))
        content.addView(moduleColorRow("candidates_tool", "separator_color", "分隔线颜色"))
    }

    // ---------------------------------------------------------------- 液态键盘

    private fun renderLiquidTab() {
        content.addView(sectionTitle("液态键盘（符号面板）"))
        content.addView(hint("就是左侧「符号」按钮打开的那个网格面板的尺寸。"))
        content.addView(moduleSliderRow("liquid_keyboard", "columns", "列数", 3f, 9f, 1f, "", 6f))
        content.addView(moduleSliderRow("liquid_keyboard", "rows", "行数", 2f, 8f, 1f, "", 5f))
        content.addView(moduleSliderRow("liquid_keyboard", "bottom_padding", "底部边距", 0f, 40f, 1f, "dp", 0f))
        content.addView(hint("面板列表（keyboards）里每一项是「单页/符号/分页/历史/变长」之一，增删页签请改主题文件。"))
    }

    // ---------------------------------------------------------------- 高级（其它样式）

    private fun renderAdvanced() {
        content.addView(sectionTitle("行为开关"))
        content.addView(
            multiChipsRow(
                "自动句首大写",
                listOf("on" to "开", "off" to "关"),
                if (style.autoCaps) setOf("on") else setOf("off"),
            ) { picked ->
                style = style.copy(autoCaps = "on" in picked)
                onChange()
            },
        )
        content.addView(
            multiChipsRow(
                "切换焦点时重置英文模式",
                listOf("on" to "开", "off" to "关"),
                if (style.resetAsciiModeOnFocusChange) setOf("on") else setOf("off"),
            ) { picked ->
                style = style.copy(resetAsciiModeOnFocusChange = "on" in picked)
                onChange()
            },
        )
        content.addView(
            multiChipsRow(
                "回车键文字模式",
                listOf("0" to "不显示", "1" to "仅动作名", "2" to "优先", "3" to "回退"),
                setOf(style.enterLabelMode.coerceIn(0, 3).toString()),
            ) { picked ->
                val v = picked.firstOrNull()?.toIntOrNull() ?: 1
                style = style.copy(enterLabelMode = v)
                onChange()
            },
        )

        content.addView(sectionTitle("回车键文案"))
        content.addView(hint("上面模式选「不显示」时这里无效。"))
        val el = style.enterLabel
        content.addView(moduleTextRow("__enter_label__", "send", "发送（send）", el.send))
        content.addView(moduleTextRow("__enter_label__", "search", "搜索（search）", el.search))
        content.addView(moduleTextRow("__enter_label__", "default", "默认（default）", el.default))

        content.addView(sectionTitle("侧栏与其它"))
        content.addView(
            sliderRow("侧栏圆角（-1 跟随按键圆角）", style.sidebarRoundCorner, -1f, 32f, 1f, "dp") {
                style = style.copy(sidebarRoundCorner = it)
                onChange()
            },
        )
        content.addView(moduleTextRow("__style__", "background_folder", "背景图子目录"))
        content.addView(moduleTextRow("__style__", "font_list", "字体列表（逗号分隔）"))

        content.addView(sectionTitle("其它字号"))
        val f = style.fonts
        content.addView(
            sliderRow("剪贴板分类字号", f.clipboard_category_size, 8f, 24f, 0.5f, "sp") {
                style = style.copy(fonts = style.fonts.copy(clipboard_category_size = it))
                onChange()
            },
        )
        content.addView(
            sliderRow("剪贴板字号", f.clipboard_size, 8f, 24f, 0.5f, "sp") {
                style = style.copy(fonts = style.fonts.copy(clipboard_size = it))
                onChange()
            },
        )
        content.addView(
            sliderRow("侧栏字号（-1 跟随按键）", f.sidebar_size, -1f, 34f, 0.5f, "sp") {
                style = style.copy(fonts = style.fonts.copy(sidebar_size = it))
                onChange()
            },
        )
        content.addView(
            sliderRow("液态键盘页签字号（-1 跟随候选）", f.liquid_tabs_size, -1f, 34f, 0.5f, "sp") {
                style = style.copy(fonts = style.fonts.copy(liquid_tabs_size = it))
                onChange()
            },
        )
    }

    // ---------------------------------------------------------------- 模块字段读写（preedit / sync_input / window / tool_bar / candidates_tool / liquid_keyboard）

    private var themeRootCache: JsonObject? = null
    private val moduleDrafts = LinkedHashMap<String, JsonObject>()

    private fun themeRoot(): JsonObject {
        themeRootCache?.let { return it }
        val j = runCatching {
            Theme.json.encodeToJsonElement(Theme.serializer(), ThemeManager.activeTheme).jsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
        themeRootCache = j
        return j
    }

    private fun moduleOf(name: String): JsonObject =
        moduleDrafts[name] ?: (themeRoot()[name] as? JsonObject) ?: JsonObject(emptyMap())

    private fun digJson(obj: JsonObject?, path: String): JsonElement? {
        var cur: JsonElement? = obj
        for (seg in path.split('.')) {
            cur = (cur as? JsonObject)?.get(seg) ?: return null
        }
        return cur
    }

    private fun moduleText(name: String, path: String): String {
        if (name == "__style__") {
            return when (path) {
                "background_folder" -> style.backgroundFolder
                "font_list" -> style.fonts.font_list.joinToString(",")
                else -> ""
            }
        }
        if (name == "__enter_label__") {
            val el = style.enterLabel
            return when (path) {
                "send" -> el.send
                "search" -> el.search
                else -> el.default
            }
        }
        return (digJson(moduleOf(name), path) as? JsonPrimitive)?.content ?: ""
    }

    private fun moduleNum(name: String, path: String, def: Float): Float =
        (digJson(moduleOf(name), path) as? JsonPrimitive)?.content?.toFloatOrNull() ?: def

    private fun moduleSet(name: String, path: String, value: JsonElement) {
        val root = moduleOf(name)
        val parts = path.split('.')
        fun build(depth: Int, node: JsonObject): JsonObject {
            val key = parts[depth]
            val next: JsonElement = if (depth == parts.size - 1) {
                value
            } else {
                build(depth + 1, (node[key] as? JsonObject) ?: JsonObject(emptyMap()))
            }
            return JsonObject(node.toMutableMap().also { it[key] = next })
        }
        moduleDrafts[name] = build(0, root)
    }

    private fun moduleSliderRow(
        name: String,
        path: String,
        title: String,
        min: Float,
        max: Float,
        step: Float,
        unit: String,
        def: Float,
    ): View = sliderRow(title, moduleNum(name, path, def), min, max, step, unit) { v ->
        val integral = v % 1f == 0f
        moduleSet(name, path, JsonPrimitive(if (integral) v.toInt() else v))
        dirty = true
        updateActionState()
    }

    private fun moduleColorRow(name: String, path: String, title: String): View =
        colorRow(title, parseColor(moduleText(name, path))) { picked ->
            moduleSet(name, path, JsonPrimitive("0x" + String.format("%08x", picked)))
            dirty = true
            updateActionState()
        }

    private fun moduleTextRow(name: String, path: String, title: String, preset: String? = null): View {
        val wrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        wrap.addView(
            TextView(requireContext()).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(textColor)
            },
        )
        val valueView = TextView(requireContext()).apply {
            text = (preset ?: moduleText(name, path)).ifEmpty { "（未设置）" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(accentColor)
            setPadding(0, dp(4), 0, 0)
        }
        wrap.addView(valueView)
        wrap.setOnClickListener {
            val edit = android.widget.EditText(requireContext()).apply {
                setText(preset ?: moduleText(name, path))
                setSelection(text.length)
            }
            AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(edit)
                .setPositiveButton("确定") { _, _ ->
                    val v = edit.text.toString()
                    when (name) {
                        "__style__" -> {
                            style = when (path) {
                                "background_folder" -> style.copy(backgroundFolder = v)
                                "font_list" -> style.copy(
                                    fonts = style.fonts.copy(
                                        font_list = v.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                    ),
                                )
                                else -> style
                            }
                            onChange()
                        }

                        "__enter_label__" -> {
                            val el = style.enterLabel
                            style = style.copy(
                                enterLabel = when (path) {
                                    "send" -> el.copy(send = v)
                                    "search" -> el.copy(search = v)
                                    else -> el.copy(default = v)
                                },
                            )
                            onChange()
                        }

                        else -> {
                            moduleSet(name, path, JsonPrimitive(v))
                            dirty = true
                            updateActionState()
                        }
                    }
                    renderTab(tab)
                }
                .setNegativeButton("取消", null)
                .show()
        }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(wrap, rowParams())
            addView(divider())
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmt(v: Float): String =
        if (v % 1f == 0f) v.toInt().toString() else String.format("%.1f", v)

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun parseColor(raw: String?): Int? {
        raw ?: return null
        val s = raw.trim()
        if (s.isEmpty()) return null
        return runCatching {
            when {
                s.startsWith("0x", true) -> {
                    val hex = s.substring(2)
                    if (hex.length >= 8) {
                        hex.substring(0, 8).toLong(16).toInt()
                    } else {
                        0xFF000000.toInt() or hex.toLong(16).toInt()
                    }
                }

                s.startsWith("#") -> Color.parseColor(s)
                else -> null
            }
        }.getOrNull()
    }
}
