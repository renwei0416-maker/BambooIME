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
import kotlinx.serialization.json.JsonPrimitive
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

    private val tabs = listOf("配色", "键盘", "字号", "候选栏", "阴影", "按键")

    private companion object {
        const val OVERLAY_TAG = "theme_style_picker_overlay"
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
        setPreviewHeight(saved)
        preview.mode = if (ThemeStylePrefs.previewMode == 1) {
            ThemePreviewView.Mode.CROP
        } else {
            ThemePreviewView.Mode.FIT
        }

        preview.onKeyTapped = { path ->
            selectedKey = path
            if (tab != 5) renderTab(5) else renderTab(5)
        }

        val handle = root.findViewById<FrameLayout>(R.id.theme_style_handle)
        val detector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    setPreviewHeight(if (currentPreviewDp() > 120) 96 else 200)
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

    private fun setPreviewHeight(dpValue: Int) {
        val v = dpValue.coerceIn(96, 420)
        previewBox.layoutParams = previewBox.layoutParams.apply { height = dp(v) }
        previewBox.requestLayout()
        ThemeStylePrefs.previewHeight = v
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
            0 -> renderColors()
            1 -> renderKeyboard()
            2 -> renderFonts()
            3 -> renderCandidate()
            4 -> renderShadow()
            else -> renderKeyTab()
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
        content.addView(hint("只改当前这套配色；点右侧色块用色相条取色。"))
        for ((key, label) in colorKeys()) {
            content.addView(
                colorRow(label, parseColor(scheme.colors[key])) { picked ->
                    val colors = LinkedHashMap(scheme.colors)
                    colors[key] = "0x" + String.format("%08x", picked)
                    replaceScheme(scheme.copy(colors = colors))
                    renderTab(0)
                    refreshPreview()
                },
                rowParams(),
            )
        }
    }

    private fun colorKeys(): List<Pair<String, String>> = listOf(
        "back_color" to "键盘底色",
        "key_back_color" to "按键底色",
        "key_text_color" to "按键文字",
        "key_symbol_color" to "按键符号",
        "highlight_color" to "高亮底色",
        "text_color" to "普通文字",
        "hilited_candidate_back_color" to "首选词底色",
        "hilited_candidate_text_color" to "首选词文字",
        "candidate_back_color" to "候选栏底色",
        "candidate_text_color" to "候选词文字",
        "comment_text_color" to "释义文字",
        "shadow_color" to "阴影颜色",
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
        content.addView(
            plainRow("清除这个键的全部覆盖") {
                pushUndo()
                draft.clearAll(path)
                onChange()
                renderTab(5)
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
            renderTab(5)
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
                            renderTab(5)
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
                                    renderTab(5)
                                }
                                .show()
                        }

                        else -> {
                            ColorPickerDialog.show(requireContext(), title, color ?: 0xFF000000.toInt()) { picked ->
                                pushUndo()
                                draft.setField(path, field, JsonPrimitive("0x" + String.format("%08x", picked)))
                                onChange()
                                renderTab(5)
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
                renderTab(5)
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
                    renderTab(5)
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
