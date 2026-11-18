// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.actions.handwriting

import android.widget.ListView
import com.osfans.trime.util.BambooLog
import android.widget.BaseAdapter
import android.widget.AbsListView
import android.graphics.drawable.ColorDrawable
import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizePx
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.actions.bamboo.BambooActions
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.dependency.InputDependencyManager
import org.kodein.di.instance
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.osfans.trime.util.memory.IdleReclaimer
import com.osfans.trime.data.prefs.MemoryPrefs
import com.osfans.trime.util.memory.MemoryProbe
import timber.log.Timber

/**
 * 手写板：占住键盘区域的一块窗口，可以被主题的键盘切换弹窗调起。
 *
 * 主题里给弹窗加一项即可（`click` 是固定标识，`label` 随便写）：
 * ```
 * popup = { { label = "手写", click = "Handwriting" } }
 * ```
 *
 * 配色跟随当前主题（`HandwritingPrefs.themeColors` 关掉后退回内置配色）：
 * 画布底 = `key_back_color`，笔迹 = `key_text_color`，候选条/符号列底 = `back_color`，
 * 次要文字 = `key_symbol_color`，高亮 = `hilited_key_back_color`。
 *
 * 结构：候选条 / [画布 + 右侧符号列（⌫ + 可滑符号）] / 数字·标点·符号面板（覆盖画布）/ 底部工具栏。
 * 面板是**同一容器里的一层**，不使用 PopupWindow（独立窗口在 IME 里容易 BadToken）。
 */
class HandwritingWindow : BoardWindow.NoBarBoardWindow() {
    /**
     * 不能用 `by di.instance()` 把这些缓存住。
     *
     * `InputDependencyManager.initialize()` 每次创建 InputView 都会新建一整套依赖
     * （BoardWindowManager / KeyboardWindow 都是它自己的 singleton），而本类是进程级单例。
     * 一旦缓存，切主题等导致 InputView 重建之后我们手里就是旧管理器：它的 currentWindow
     * 停在键盘上，`attachWindow` 直接 early-return，表现就是「返回点了没反应」。
     * 所以每次都从「最近一次」的那套依赖里取。
     */
    private val service: TrimeInputMethodService
        get() = TrimeInputMethodService.current
            ?: error("handwriting: IME service is not available")

    private val windowManager: BoardWindowManager
        get() = BoardWindowManager.current
            ?: error("handwriting: board window manager is not available")

    private lateinit var board: HandwritingBoardView
    private lateinit var candidateRow: LinearLayout
    private lateinit var messageText: TextView
    private lateinit var symbolColumn: LinearLayout
    private lateinit var symbolColumnCn: LinearLayout
    private lateinit var symbolColumnEn: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelGrid: LinearLayout
    private lateinit var panelScroll: ScrollView
    private var symbolButton: ToolKey? = null
    private var numberButton: ToolKey? = null

    // 「数字/符号」面板的来源：手写画布点进来 / 键盘 token 唤起来。
    // 这个决定「返回」「收起」回哪儿：回画布，还是回字母键盘。
    private var panelOpen = false
    private var panelOrigin = ORIGIN_CANVAS
    private var panelOpenedBy = 0 // 0 = 数字按钮开的，3 = 符号按钮开的
    private lateinit var panelTabs: LinearLayout
    private lateinit var panelTabsScroll: HorizontalScrollView
    // 子分类下拉：就地覆盖在面板内的列表（不用 PopupWindow，避免弹到上方 / 配色不跟主题 / 抢焦点崩）
    private var groupOverlay: FrameLayout? = null
    /** 当前页的子分类下标（0 = 常用）；切页时归零。 */
    private var currentGroup = 0

    private var recognizeJob: Job? = null
    private var modelLoaded = false
    private var lastCandidates: List<HandwritingRecognizer.Candidate> = emptyList()

    /** 候选条里「当前项」的下标：空格上屏它，高亮也画在它身上。 */
    private var selectedIndex = 0

    /** 每个候选格子的视图引用，滑动时只改颜色不重建视图。 */
    private val cells = mutableListOf<CandidateCell>()

    /** 真正承载候选条的横向滚动容器（滚动时据此算「当前项」）。 */
    private var candidateScroll: HorizontalScrollView? = null

    private data class CandidateCell(
        val item: android.view.View,
        val text: android.widget.TextView,
        val pinyin: android.widget.TextView?,
        val charCount: Int,
        val textValue: String,
    )

    /**
     * 已累计的字（多种假设，按优先后顺序）。
     *
     * 手写板的行为是「没上屏的字累在候选条里」：写完一个字、笔迹清掉，候选留着；
     * 再写一个字时，把新字的候选接在每个旧假设后面（`东西`、`乐西`…），
     * 这样一次没上屏也能连着写好几个字。点候选上屏、或点「清除」时清空。
     */
    /** 每段笔迹的候选（逐字删要按段退，所以存成「段 -> 该段候选列表」而不是拼好的串）。 */
    private val segs = mutableListOf<List<String>>()

    /** 长按 ⌫ 连删编辑区文字用的循环。 */
    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var repeating = false
    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (!repeating) return
            deleteEditorChar()
            repeatHandler.postDelayed(this, 70L)
        }
    }

    // 主题取色：进手写板时定一次，换主题后重新进会刷新
    private var useThemeColors = true
    private var colorKeyBg = 0
    private var colorKeyText = 0
    private var colorBack = 0
    private var colorSub = 0
    private var colorAccent = 0
    private var colorHighlight = 0
    private var colorHighlightText = 0
    private var cornerPx = 0f
    private var density = 1f

    private var currentPage = 0

    /** 只用于诊断日志：第几次 onCreateView。 */
    private var viewSeq = 0

    /** 当前挂上去的那棵树，用于「返回」失效时强制摘掉。 */
    private var rootView: View? = null

    companion object {
        /** 全局唯一实例：切 App 回来要挂回同一个，笔迹和候选才不丢。 */
        val instance: HandwritingWindow by lazy { HandwritingWindow() }

        /** 是否停留在手写板（只有点「返回」才置 false）。 */
        @Volatile
        var active: Boolean = false

        /** 手写板当前是否挂在窗口上；空闲释放器据此判断能否释放模型。 */
        @Volatile
        var isBoardVisible: Boolean = false

        /** 主题弹窗里用的固定标识。 */
        const val TOKEN = "Handwriting"
    const val TOKEN_NUMBER = "BambooNumber"
    const val TOKEN_SYMBOL = "BambooSymbol"

    // 面板来源
    private const val ORIGIN_CANVAS = 0
    private const val ORIGIN_KEYBOARD = 1

        /** 画布与文字的对比度下限（WCAG AA 大字号是 3.0）。 */
        private const val MIN_CONTRAST = 3.0

        private const val PAPER_DARK = 0xFF232823.toInt()
        private const val PAPER_LIGHT = 0xFFFAF8F2.toInt()
        private const val INK_ON_DARK = 0xFFF2F0E8.toInt()
        private const val INK_ON_LIGHT = 0xFF1A1F1C.toInt()

        fun isToken(text: String): Boolean = text.equals(TOKEN, ignoreCase = true)

    /** 主题 token：`BambooNumber` → 数字页，`BambooSymbol` → 符号页。 */
    fun panelTokenPage(text: String): Int? = when {
        text.equals(TOKEN_NUMBER, ignoreCase = true) -> SymbolPages.PAGE_NUMBER
        text.equals(TOKEN_SYMBOL, ignoreCase = true) -> SymbolPages.PAGE_SYMBOL
        else -> null
    }

    /**
     * 从键盘唤起的面板页（-1 = 没有）。进程级保存，切 App / 换输入框回来还能把面板接回来。
     */
    @Volatile
    private var panelOnlyPage = -1

        private const val TAG_SPACE = "bamboo_space"

        // 数字页：0-9 与小数点位置固定；第 4 列紧挨数字块，从上到下就是 + - × /；
        // 其余格子只放能直接拼算式的符号（括号、等号、百分号、根号、乘方、正负、常数）。
        private val PAGE_NUMBER = listOf(
            "1", "2", "3", "+", "(", ")",
            "4", "5", "6", "-", "=", "%",
            "7", "8", "9", "×", "√", "^",
            "0", ".", "±", "/", "π", "∞",
        )
        /**
         * 右侧两列一一对应：左中文、右英文，同一行是同一个符号的全角/半角写法。
         * 顺序按使用频率排序时也是「成对」一起动，所以两列永远对齐。
         */
        private val SYMBOL_PAIRS: List<Pair<String, String>> = listOf(
            // 默认顺序＝使用频率：标点（逗号句号顿号…）在前，括号引号居中，全角运算符号在后。
            // 这里的先后只影响"首次使用"的排序，点过的会按点击次数自动顶上来。
            "，" to ",",
            "。" to ".",
            "、" to ",",
            "？" to "?",
            "！" to "!",
            "；" to ";",
            "：" to ":",
            "…" to "...",
            "—" to "-",
            "“" to "\"",
            "”" to "\"",
            "‘" to "'",
            "’" to "'",
            "（" to "(",
            "）" to ")",
            "《" to "<",
            "》" to ">",
            "【" to "[",
            "】" to "]",
            "「" to "[",
            "」" to "]",
            "『" to "{",
            "』" to "}",
            "〈" to "<",
            "〉" to ">",
            "〔" to "[",
            "〕" to "]",
            "·" to "·",
            "～" to "~",
            "￥" to "$",
            "％" to "%",
            "＃" to "#",
            "＠" to "@",
            "＆" to "&",
            "＊" to "*",
            "／" to "/",
            "＼" to "\\",
            "＿" to "_",
            "＋" to "+",
            "＝" to "=",
        )

        private val PAGE_PUNCT_CN = SYMBOL_PAIRS.map { it.first }
        private val PAGE_PUNCT_EN = SYMBOL_PAIRS.map { it.second }
        // 符号页：按"常用 → 数学 → 单位 → 图形 → 箭头 → 其它"排，靠滚动看全
        private val PAGE_SYMBOL = listOf(
            "／", "＼", "＠", "＃", "＄", "％",
            "＆", "＊", "｜", "＿", "＾", "｀",
            "＋", "－", "＝", "＜", "＞", "～",
            "±", "×", "÷", "≠", "≈", "≡",
            "≤", "≥", "∞", "√", "∑", "∏",
            "∫", "µ", "Ω", "°", "℃", "‰",
            "§", "¶", "†", "‡", "©", "®",
            "™", "※", "′", "″", "·", "•",
            "●", "○", "■", "□", "▲", "△",
            "◆", "◇", "★", "☆", "♥", "♡",
            "♪", "♫", "✓", "✗", "→", "←",
            "↑", "↓", "↔", "↕", "⇒", "⇐",
            "⇔", "①", "②", "③", "④", "⑤",
            "⑥", "⑦", "⑧", "⑨", "⑩", "㈠",
            "㈡", "㈢", "⑴", "⑵", "⑶", "⒈",
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(): View {
        active = true
        BambooLog.hw.d(
            "view",
            "onCreateView #${++viewSeq} this=${hashCode().toUInt().toString(16)} " +
                "active=$active attached=${windowManager.isAttached(this)}",
        )
        val created = try {
            buildView()
        } catch (t: Throwable) {
            // 最后一层兜底：主题再怪也不许闪退，退回到内置配色的极简手写板
            Timber.e(t, "handwriting: build failed, falling back to plain board")
            BambooLog.hw.w("view", "buildView threw, fallback used", t)
            fallbackView()
        }
        rootView = created
        return created
    }

    /** 主题完全不可用时的兜底界面：只有画布 + 三个按钮，配色写死。 */
    private fun fallbackView(): View {
        density = context.resources.displayMetrics.density
        useThemeColors = false
        colorBack = 0xFF202420.toInt()
        colorKeyBg = 0xFF2B302B.toInt()
        colorKeyText = 0xFFF2F0E8.toInt()
        colorSub = 0xFF8A8A80.toInt()
        colorAccent = 0xFF3A403A.toInt()
        colorHighlight = 0xFF3A403A.toInt()
        colorHighlightText = colorKeyText
        cornerPx = dp(8).toFloat()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(colorBack)
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
        }
        candidateRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(candidateRow, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(34)),
        )

        board = HandwritingBoardView(context).apply {
            penWidthDp = HandwritingPrefs.penWidth.toFloat()
            inkColor = colorKeyText
            paperColor = colorKeyBg
            onStrokeStarted = { recognizeJob?.cancel() }
        onStrokeFinished = { scheduleRecognize() }
        }
        root.addView(
            FrameLayout(context).apply {
                addView(board, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )

        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), 0, dp(3), 0)
        }
        fun b(label: String, weight: Float, onClick: (View) -> Unit) = TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                setColor(colorKeyBg)
                setStroke(dp(1), colorSub)
                cornerRadius = cornerPx
            }
            setOnClickListener {
                BambooLog.hw.d("tap", "$label candidates=${lastCandidates.size}")
                onClick(it)
            }
            // 高度 MATCH_PARENT：按钮底边和工具栏行底边重合，右边列的「回车」才能和它齐平
            layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, weight).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
        }
        tools.addView(b("返回", 1f) { leaveToKeyboard() })
        tools.addView(b("空格", 2f) { commitSpace() })
        tools.addView(b("回车", 1f) { sendEnter() })
        root.addView(tools, LinearLayout.LayoutParams(MATCH_PARENT, dp(54)))

        messageText = TextView(context).apply {
            text = "主题配色不可用，已使用内置配色"
            textSize = 12f
            setTextColor(colorSub)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        root.addView(messageText, LinearLayout.LayoutParams(MATCH_PARENT, dp(18)))

        ensureModel()
        return root
    }

    private fun buildView(): View {
        val dm = context.resources.displayMetrics
        density = dm.density
        val gap = (4 * dm.density).toInt()
        gapPx = gap

        useThemeColors = HandwritingPrefs.themeColors
        // 注意：ColorManager.getColorOr 在"这个键解析成图片"时会**故意 rethrow**
        // （见 ColorManager.kt:245 image file 分支），所以图片主题（GMK 等）必须自己再兜一层，
        // 否则取色即崩。
        fun color(key: String, fallback: Int): Int =
            if (!useThemeColors) {
                fallback
            } else {
                runCatching { ColorManager.getColorOr(key, fallback) }
                    .onFailure { Timber.w(it, "handwriting: color '$key' unavailable, fallback used") }
                    .getOrDefault(fallback)
            }

        colorKeyBg = color("key_back_color", 0xFFF1EFE7.toInt())
        colorKeyText = color("key_text_color", 0xFF1A1F1C.toInt())
        colorBack = color("back_color", 0xFFE7E4DA.toInt())
        colorSub = color("key_symbol_color", 0xFF7C7A70.toInt())
        colorAccent = color("hilited_key_back_color", 0xFFDAD4C4.toInt())
        cornerPx = dp(8).toFloat()

        // ---- 手写区配色：跟随主题（带对比度保底）或用户强制深/浅 ----
        val rawPaper = colorKeyBg
        val rawInk = colorKeyText
        when (HandwritingPrefs.inkMode) {
            HandwritingPrefs.INK_DARK -> {
                colorKeyBg = PAPER_DARK
                colorKeyText = INK_ON_DARK
            }
            HandwritingPrefs.INK_LIGHT -> {
                colorKeyBg = PAPER_LIGHT
                colorKeyText = INK_ON_LIGHT
            }
            else -> {
                // GMK 8008 这类主题：key_back_color 取不到 → 纸色是近白，
                // 而 key_text_color 是给深色键帽配的浅色字，铺满整块画布就看不清。
                colorKeyBg = ensureReadable(colorKeyText, colorKeyBg)
            }
        }
        Timber.d(
            "handwriting colors: mode=%s paper=#%06X(raw #%06X) ink=#%06X(raw #%06X) ratio=%.2f",
            HandwritingPrefs.inkMode,
            colorKeyBg and 0xFFFFFF, rawPaper and 0xFFFFFF,
            colorKeyText and 0xFFFFFF, rawInk and 0xFFFFFF,
            contrastRatio(colorKeyText, colorKeyBg),
        )

        // 候选高亮：主题的「选中候选」底色 → 主题实际用的 hilited_candidate_button_color
        // → 按键高亮色 → 都没有就按底色混合算一个（换任何主题都不会看不见高亮）
        fun themeColorOrNull(key: String): Int =
            if (!useThemeColors) 0 else runCatching { ColorManager.getColorOr(key, 0) }.getOrDefault(0)

        colorHighlight = listOf(
            "hilited_candidate_back_color",
            "hilited_candidate_button_color",
            "hilited_key_back_color",
        ).firstNotNullOfOrNull { themeColorOrNull(it).takeIf { c -> c != 0 } }
            ?: blend(colorBack, colorKeyText, 0.16f)

        colorHighlightText = listOf(
            "hilited_candidate_text_color",
            "candidate_text_color",
            "key_text_color",
        ).firstNotNullOfOrNull { themeColorOrNull(it).takeIf { c -> c != 0 } }
            ?: colorKeyText
        colorHighlight = ensureReadable(colorHighlightText, colorHighlight)

        val root = LinearLayout(context).apply {
            // 左右两大块：右边符号列要一路贯通到最底部，所以外层是横向的
            orientation = LinearLayout.HORIZONTAL
            // 全板统一间距：横竖都用 gap，避免"某处 4dp、某处 2dp"
            setPadding(gap, gap, gap, gap)
            // 手写板整体圆角：只做背景圆角 + 裁剪，不画外框
            background = GradientDrawable().apply {
                setColor(colorBack)
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
        }

        // ---------------- 候选条（字号 18sp，整条高度比原来矮 20%） ----------------
        val candTextSize = 18f
        // 原高度 = 20sp×1.12 + 8dp + 8dp
        val barHeightPx = (
            (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, candTextSize, dm) * 1.12f) +
                dp(8) + dp(8)
            ).times(0.8f).toInt()
        candidateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(gap, 0, gap, 0)
        }
        // ---------------- 上部：左列（候选+画布）与右列（⌫+符号）并排，面板盖住整块 ----------------
        contentFrame = FrameLayout(context)

        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val leftColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // 候选条放在左列里面：右列才能从顶部贯通整块，不被候选区截断
        leftColumn.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2), dp(1), dp(2), dp(1))
                background = keyDrawable("key_back_color", colorKeyBg, dp(1), cornerPx)
                clipToOutline = true
                addView(
                    HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        isFillViewport = true
                        // 记住这个才是真正在用的候选滚动容器（上面 L2xx 那个是兜底布局里的）
                        candidateScroll = this
                        setOnScrollChangeListener { _, _, _, _, _ ->
                            // 实时同步：谁的中心进入「第一个候选那块区域 A」的中心 ±15%，谁就是当前项
                            syncSelectionFromScroll()
                        }
                        addView(candidateRow, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
                    },
                    LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
                )
            },
            LinearLayout.LayoutParams(MATCH_PARENT, barHeightPx).apply {
                setMargins(0, 0, 0, gap)
            },
        )

        board = HandwritingBoardView(context).apply {
            penWidthDp = HandwritingPrefs.penWidth.toFloat()
            inkColor = colorKeyText
            paperColor = colorKeyBg
            onStrokeStarted = { recognizeJob?.cancel() }
        onStrokeFinished = { scheduleRecognize() }
        }
        leftColumn.addView(
            FrameLayout(context).apply {
                setPadding(dp(3), dp(2), dp(3), dp(2))
                // 画布容器圆角 + 裁剪：画布自己画的是方角底色，靠这层裁出弧度
                background = GradientDrawable().apply {
                    setColor(colorKeyBg)
                    cornerRadius = dp(10).toFloat()
                }
                clipToOutline = true
                addView(board, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { setMargins(0, 0, 0, gap) },
        )
        body.addView(leftColumn, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))

        // 右侧：可滑符号列在上（占满），⌫ 固定在最下面（大拇指好按）
        val rightColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        symbolColumn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        symbolColumnCn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        symbolColumnEn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        symbolColumn.addView(symbolColumnCn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        symbolColumn.addView(symbolColumnEn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        rightColumn.addView(
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = true
                addView(symbolColumn, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                background = keyDrawable("key_back_color", colorKeyBg, dp(1), cornerPx)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { setMargins(0, 0, 0, gap) },
        )
        rightColumn.addView(
        // 退格在上
            ToolKey("⌫", ToolIcons.BACKSPACE, 1f, colorKeyText, onTap = { backspace() }).apply {
                setOnLongClickListener { onBackspaceLongPress(); true }
                setOnTouchListener { _, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                        event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                    ) {
                        stopRepeating()
                    }
                    false
                }
            },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { setMargins(0, 0, 0, gap) },
        )
        // 回车在下，贴着最底边（不加下外边距，和工具栏同一底边）
        rightColumn.addView(
            ToolKey("回车", ToolIcons.ENTER, 1f, colorKeyText, onTap = { sendEnter() }),
            LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { setMargins(0, 0, 0, 0) },
        )
        contentFrame.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        refillSymbolColumn()

        // 面板（覆盖画布区域，默认隐藏）
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBack)
            visibility = View.GONE
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }
        // 22 个页签一屏放不下，套一层横向滚动
        panelTabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        panelTabsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(panelTabs, ViewGroup.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        panel.addView(panelTabsScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(33)))
        // 网格放进 ScrollView：行高固定，行数再多也只是变长可滑，不会被压扁
        panelGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panelScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            addView(panelGrid, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        panel.addView(panelScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        contentFrame.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 左列容器：候选条 / 画布 / 功能键行都贴这个容器的左右边界
        // 右边留 gap，就是把"左容器"和"右容器"之间的那道缝交给它
        val leftWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, gap, 0)
        }
        leftWrap.addView(contentFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(leftWrap, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        root.addView(rightColumn, LinearLayout.LayoutParams(dp(58), MATCH_PARENT))

        // ---------------- 底部工具栏 ----------------
        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun toolButton(
            label: String,
            icon: String?,
            weight: Float,
            fg: Int,
            onClick: (View) -> Unit,
        ): ToolKey = ToolKey(label, icon, weight, fg, onTap = onClick)

        // 左边 返回 + 数字（各 1 份），中间空格（2 份），右边 符号 + 回车（各 1 份）
        // 这样空格两侧权重相等，位置正好居中
        // 回车挪到右侧竖列（大写回车符号），这里只剩四个：返回 · 数字 · 空格 · 符号面板
        tools.addView(toolButton("返回", ToolIcons.BACK, 1f, colorKeyText) { closePanelOrLeave() })
        val numBtn =
        toolButton("数字", ToolIcons.NUMBER, 1f, colorKeyText) {
            if (numberShowsCollapse()) closePanelOrLeave() else togglePanel(SymbolPages.PAGE_NUMBER)
        }
        numberButton = numBtn
        tools.addView(numBtn)
        val spaceKey = toolButton("空格", ToolIcons.SPACE, 2f, colorKeyText) { commitSpace() }
        spaceKey.tag = TAG_SPACE
        spaceView = spaceKey
        attachVoiceHold(spaceKey)
        tools.addView(spaceKey)
        val symBtn =
        toolButton("符号", ToolIcons.SYMBOL, 1f, colorKeyText) {
            if (symbolShowsCollapse()) closePanelOrLeave() else togglePanel(SymbolPages.PAGE_CN)
        }
        symbolButton = symBtn
        tools.addView(symBtn)
        // 只有「按钮之间」留缝：最左、最右不加横向边距，
        // 否则最右的「符号」会叠加容器 A 的右 padding，比候选区/手写区往里缩
        for (i in 0 until tools.childCount) {
            val lp = tools.getChildAt(i).layoutParams as? LinearLayout.LayoutParams ?: continue
            lp.setMargins(0, 0, if (i == tools.childCount - 1) 0 else gap, 0)
        }
        leftWrap.addView(tools, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))

        // 提示行：没有内容时彻底不占高度
        messageText = TextView(context).apply {
            textSize = 14f
            setTextColor(colorSub)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(dp(13), 0, dp(13), 0)
        }

        ensureModel()
        // 从键盘唤起的数字/符号面板：窗口重建（切 App、换输入框）后把面板接回来
        val restorePage = panelOnlyPage
        if (restorePage >= 0) {
            showPanel(restorePage, ORIGIN_KEYBOARD, if (restorePage == 0) 0 else 3)
            BambooLog.hw.d("panel", "restore standalone page=" + restorePage)
        }
        Timber.d(
            "handwriting: window created themeColors=%s paper=#%06X ink=#%06X",
            useThemeColors, colorKeyBg and 0xFFFFFF, colorKeyText and 0xFFFFFF,
        )
        return root
    }

    // ------------------------------------------------------------ 工具栏按键
    //
    // 图标来自 App 内置的 Community Material 图标字体（7300+ 个，名字带 `cmd_` 前缀、
    // 用下划线连接）。字体里没有的拼写会抛异常，所以每次取图标都 runCatching，
    // 取不到就退回中文文字——不会因为一个图标名把整块面板搞崩。
    private inner class ToolKey(
        label: String,
        icon: String?,
        weight: Float,
        fg: Int,
        onTap: (View) -> Unit,
    ) : LinearLayout(context) {
        private val labelView: TextView
        private val iconView: ImageView
        private var currentLabel: String = label
        private var currentIcon: String? = icon
        private var currentFg: Int = fg

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(1), 0, dp(1), 0)
            background = keyDrawable("key_back_color", colorKeyBg, dp(1), cornerPx)
            iconView =
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    isClickable = false
                    isFocusable = false
                    visibility = View.GONE
                }
            labelView =
                TextView(context).apply {
                    text = label
                    textSize = 13f
                    typeface = Typeface.DEFAULT
                    setTextColor(fg)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = 1
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            addView(iconView, LayoutParams(dp(22), dp(22)))
            addView(labelView, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            setOnClickListener {
                BambooLog.hw.d("tap", "$currentLabel candidates=${lastCandidates.size}")
                onTap(it)
            }
            // 高度 MATCH_PARENT：按钮底边和工具栏行底边重合，右边列的「回车」才能和它齐平
            layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, weight).apply {
                setMargins(0, 0, gapPx, 0)
            }
            applyContent()
        }

        /** 换显示内容；任何一项变了都重新决定「显示图标还是文字」。 */
        fun setContent(label: String, icon: String?) {
            currentLabel = label
            currentIcon = icon
            labelView.text = label
            applyContent()
        }

        /** 临时只显示文字（按住空格说话时的「松手结束」）。 */
        fun setLabelOnly(label: String) = setContent(label, null)

        private fun applyContent() {
            val name = currentIcon
            if (HandwritingPrefs.toolIcons && name != null) {
                val drawable =
                    runCatching {
                        IconicsDrawable(context, name).apply {
                            sizePx = dp(22)
                            colorFilter = PorterDuffColorFilter(currentFg, PorterDuff.Mode.SRC_IN)
                        }
                    }.getOrNull()
                if (drawable != null) {
                    iconView.setImageDrawable(drawable)
                    iconView.visibility = View.VISIBLE
                    labelView.visibility = View.GONE
                    return
                }
            }
            iconView.visibility = View.GONE
            labelView.visibility = View.VISIBLE
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    // ---------------------------------------------------------------- 对比度保底

    private fun channelLuminance(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    private fun relativeLuminance(color: Int): Double =
        0.2126 * channelLuminance((color shr 16) and 0xFF) +
            0.7152 * channelLuminance((color shr 8) and 0xFF) +
            0.0722 * channelLuminance(color and 0xFF)

    private fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * 保证 [fg] 在 [bg] 上看得清：对比度不足就把底色翻到文字色的对立面。
     * 只动底色不动文字色，避免主题里「字色」的语义被破坏。
     */
    private fun ensureReadable(fg: Int, bg: Int): Int {
        if (contrastRatio(fg, bg) >= MIN_CONTRAST) return bg
        val lightText = relativeLuminance(fg) > 0.5
        val flipped = if (lightText) PAPER_DARK else PAPER_LIGHT
        return if (contrastRatio(fg, flipped) >= contrastRatio(fg, bg)) flipped else bg
    }

    // ---------------------------------------------------------------- 主题样式

    private fun keyDrawable(key: String, fallbackColor: Int, borderPx: Int, corner: Float): Drawable {
        if (useThemeColors) {
            // 图片主题下 getDecorDrawable 可能因为键帽图缺失/解码失败抛异常，必须兜住
            runCatching { ColorManager.getDecorDrawable(key, "key_border_color", borderPx, corner) }
                .onFailure { Timber.w(it, "handwriting: drawable '$key' unavailable, fallback used") }
                .getOrNull()
                ?.let { return it }
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fallbackColor)
            setStroke(borderPx, colorSub)
            cornerRadius = corner
        }
    }

    private val inputBar: InputBarDelegate by InputDependencyManager.getInstance().di.instance()

    /** 长按空格 = 按住说话；录音期间空格自己就是状态指示，松手恢复。 */
    private var voiceActive = false
    private var spaceView: ToolKey? = null

    /** 全板统一间距（buildView 里由 density 算出来，工具栏的按键要靠它留缝）。 */
    private var gapPx: Int = 0
    private val voiceLongPress = Runnable { startVoiceHold() }

    private fun attachVoiceHold(button: ToolKey) {
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.postDelayed(voiceLongPress, 400L)
                android.view.MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(voiceLongPress)
                    if (voiceActive) stopVoiceHold() else commitSpace()
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(voiceLongPress)
                    stopVoiceHold()
                }
            }
            true
        }
    }

    private fun startVoiceHold() {
        if (voiceActive) return
        val ok = runCatching { inputBar.startVoiceHoldSession() }
            .onFailure { Timber.w(it, "handwriting: voice start failed") }
            .isSuccess
        if (!ok) return
        voiceActive = true
        spaceButton()?.let {
            it.setLabelOnly("松手结束")
            it.background = GradientDrawable().apply {
                setColor(colorAccent)
                cornerRadius = cornerPx
            }
        }
    }

    private fun stopVoiceHold() {
        if (!voiceActive) return
        voiceActive = false
        runCatching { inputBar.stopAsrkbVoiceFromToolbar() }
            .onFailure { Timber.w(it, "handwriting: voice stop failed") }
        spaceButton()?.let {
            it.setContent("空格", ToolIcons.SPACE)
            it.background = keyDrawable("key_back_color", colorKeyBg, dp(1), cornerPx)
        }
    }

    private fun spaceButton(): ToolKey? = spaceView

    private fun refillSymbolColumn() {
        symbolColumnCn.removeAllViews()
        symbolColumnEn.removeAllViews()
        // 按「一对符号的合计点击次数」排序，两列同步，行始终对得上
        val pairs = SymbolUsage.orderedPairs(SYMBOL_PAIRS)
        pairs.forEach { (cn, en) ->
            symbolColumnCn.addView(symbolCell(cn))
            symbolColumnEn.addView(symbolCell(en))
        }
    }

    private fun symbolCell(sym: String) = TextView(context).apply {
        text = sym
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(colorKeyText)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(38))
        setOnClickListener {
            commit(sym)
            SymbolUsage.record(sym)
            // 点过的这一对（中/英）立刻顶到最上面，其余依次后移
            refillSymbolColumn()
        }
    }

    // ---------------------------------------------------------------- 面板

    /**
     * 工具栏上的「数字」「符号」：同页再点一次 = 收起，换页 = 直接切过去。
     *
     * 换页**不能**动「从哪进来」这个标记（[panelOrigin]）：面板一旦从键盘唤起，
     * 里面怎么切页、点了数字还是符号，返回/收起都该回键盘；只有最初从画布进来的才回画布。
     */
    private fun togglePanel(page: Int) {
        if (panelOpen) {
            if (currentPage == page) {
                closePanelOrLeave()
                return
            }
            showPanel(page, panelOrigin, panelOpenedBy)
        } else {
            showPanel(page, ORIGIN_CANVAS, page)
        }
        syncPanelOnlyPage(page)
    }

    /** 从键盘唤起的面板要记住当前页：窗口被系统重建后按它恢复，切页时也要跟着变。 */
    private fun syncPanelOnlyPage(page: Int) {
        if (panelOpen && panelOrigin == ORIGIN_KEYBOARD) {
            panelOnlyPage = page
        }
    }

    /** 当前在数字页时，「数字」按钮显示「收起」。 */
    private fun numberShowsCollapse(): Boolean = panelOpen && currentPage == SymbolPages.PAGE_NUMBER

    /** 面板开着且不在数字页时，「符号」按钮显示「收起」。 */
    private fun symbolShowsCollapse(): Boolean = panelOpen && currentPage != SymbolPages.PAGE_NUMBER

    /**
     * 打开「数字和符号」面板。
     *
     * @param origin 从哪进来：[ORIGIN_CANVAS] 手写画布 / [ORIGIN_KEYBOARD] 主题 token 从键盘唤起
     * @param openedBy 哪个按钮开的（0 数字 / 3 符号），决定哪个按钮显示「收起」
     */
    private fun showPanel(page: Int, origin: Int, openedBy: Int) {
        hideGroupPopup()
        currentGroup = 0
        panelOpen = true
        panelOrigin = origin
        panelOpenedBy = openedBy
        currentPage = page
        rebuildPanelTabs()
        buildPanelGrid(page)
        panel.visibility = View.VISIBLE
        refreshToolButtons()
        board.clear()
        // 面板里没有候选：清掉手写分段与候选格子，空格就落回「输出空格」
        segs.clear()
        lastCandidates = emptyList()
        cells.clear()
        selectedIndex = 0
        showCandidates(emptyList())
    }

    private fun hidePanel() {
        hideGroupPopup()
        panelOpen = false
        panel.visibility = View.GONE
        refreshToolButtons()
    }

    /** 「返回」和「收起」共用：面板开着就按来源决定回画布还是回键盘。 */
    private fun closePanelOrLeave() {
        if (!panelOpen) {
            leaveToKeyboard()
            return
        }
        if (panelOrigin == ORIGIN_KEYBOARD) {
            // 从键盘唤起的面板：收起 = 回键盘，并把会话状态清掉
            panelOpen = false
            panelOnlyPage = -1
            leaveToKeyboard()
        } else {
            hidePanel()
        }
    }

    /** 哪个按钮开的哪个显示「收起」，另一个保持原名。 */
    private fun refreshToolButtons() {
        // 「收起」跟着**当前页**走，不跟「最初点了哪个按钮」走：
        // 在数字页 → 数字显示收起；在其它页 → 符号显示收起。这样文字与点下去的效果永远一致。
        if (numberShowsCollapse()) {
            numberButton?.setContent("收起", ToolIcons.COLLAPSE)
        } else {
            numberButton?.setContent("数字", ToolIcons.NUMBER)
        }
        if (symbolShowsCollapse()) {
            symbolButton?.setContent("收起", ToolIcons.COLLAPSE)
        } else {
            symbolButton?.setContent("符号", ToolIcons.SYMBOL)
        }
    }

    /**
     * 主题 token（`BambooNumber` / `BambooSymbol`）从键盘唤起面板。
     * 与手写画布进面板的区别：返回/收起都回键盘；候选态整个清空（含 Rime 组合），
     * 否则面板里的数字会直接 commit、而 Rime 还挂着编码，返回键盘时文字与候选对不上。
     */
    fun openPanelStandalone(requested: Int) {
        // 清 Rime 组合要在 rime 线程（RimeApi 只能在 runOnReady 里用）；
        // 视图操作留在主线程，所以分成两步，不把建视图塞进 postKeyJob。
        runCatching { service.postKeyJob { clearComposition() } }
            .onFailure { Timber.w(it, "handwriting: clear composition failed") }
        board.clear()
        segs.clear()
        lastCandidates = emptyList()
        cells.clear()
        selectedIndex = 0
        showCandidates(emptyList())
        val page = resolveEntryPage(requested)
        panelOnlyPage = page
        showPanel(page, ORIGIN_KEYBOARD, if (page == SymbolPages.PAGE_NUMBER) 0 else 3)
    }

    /**
     * 从键盘 token 进来时定落哪一页。
     *
     * 「符号」不是一个固定的页：中文编码时落「中文」页，英文编辑模式落「英文」页；
     * 判不出来（读不到 Rime 状态等）一律兜底「中文」。「数字」和「数字」按钮不变。
     */
    private fun resolveEntryPage(requested: Int): Int {
        if (requested != SymbolPages.PAGE_SYMBOL) return requested
        val ascii = runCatching { RimeDaemon.isAsciiMode }.getOrDefault(false)
        val page = if (ascii) SymbolPages.PAGE_EN else SymbolPages.PAGE_CN
        BambooLog.hw.d("panel", "entry token -> " + (if (ascii) "EN" else "CN") + " page=" + page)
        return page
    }

    /** 面板内切页（页签 / 子分类都走这里）：只换页与分组，不改「从哪进来」。 */
    private fun gotoPage(page: Int, group: Int) {
        currentPage = page
        currentGroup = group
        rebuildPanelTabs()
        buildPanelGrid(page)
        hideGroupPopup()
        syncPanelOnlyPage(page)
        refreshToolButtons()
    }

    private fun rebuildPanelTabs() {
        panelTabs.removeAllViews()
        SymbolPages.PAGES.forEachIndexed { i, page ->
            val selected = i == currentPage
            val group = page.groups.getOrNull(if (selected) currentGroup else 0)
            // 选中且选了子分类时，页签显示「表情·笑脸」
            val label = if (selected && currentGroup > 0 && group != null) {
                "${page.title}·${group.name}"
            } else {
                page.title
            }
            val tab = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            tab.addView(
                TextView(context).apply {
                    text = label
                    textSize = 15f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(if (selected) colorKeyText else colorSub)
                    // 文字居中、左右留白相等（带 ▾ 的页签右边留给 ▾，所以单独收窄）
                    val side = if (page.groups.size > 1) dp(6) else dp(10)
                    setPadding(dp(10), 0, side, 0)
                    setOnClickListener { gotoPage(i, 0) }
                    // 「常用」是记录页，长按就地清空（不另开设置项）
                    if (i == SymbolPages.PAGE_FAVORITE) {
                        setOnLongClickListener {
                            confirmClearFavorites()
                            true
                        }
                    }
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT),
            )
            if (page.groups.size > 1) {
                // ▾ 单独可点：弹子分类下拉
                tab.addView(
                    TextView(context).apply {
                        text = "▾"
                        textSize = 18f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setTextColor(if (selected) colorKeyText else colorSub)
                        setPadding(dp(2), 0, dp(6), 0)
                        contentDescription = "${page.title}的子分类"
                        setOnClickListener { showGroupPopup(i, this) }
                    },
                    LinearLayout.LayoutParams(dp(22), MATCH_PARENT),
                )
            }
            tab.background = if (selected) {
                GradientDrawable().apply {
                    setColor(colorAccent)
                    cornerRadius = cornerPx
                }
            } else {
                null
            }
            panelTabs.addView(
                tab,
                LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply {
                    setMargins(dp(1), 0, dp(1), 0)
                },
            )
        }
        // 让当前页签滚进可见范围
        panelTabs.post {
            val target = panelTabs.getChildAt(currentPage) ?: return@post
            val scroll = (target.left - (panelTabsScroll.width - target.width) / 2)
                .coerceAtLeast(0)
            panelTabsScroll.smoothScrollTo(scroll, 0)
        }
    }

    /** 长按「常用」页签：确认后清空上屏统计。 */
    private fun confirmClearFavorites() {
        if (runCatching { SymbolUsage.favorites() }.getOrDefault(emptyList()).isEmpty()) {
            setMessage("常用记录已经是空的")
            return
        }
        runCatching {
            android.app.AlertDialog.Builder(context)
                .setTitle("清空常用记录？")
                .setMessage("会清掉所有符号的上屏次数，「常用」页和右侧符号列的排序都会回到默认。")
                .setPositiveButton("清空") { _, _ ->
                    SymbolUsage.clear()
                    if (currentPage == SymbolPages.PAGE_FAVORITE) {
                        buildPanelGrid(SymbolPages.PAGE_FAVORITE)
                    }
                    setMessage("已清空常用记录")
                    BambooLog.hw.d("panel", "favorites cleared")
                }
                .setNegativeButton("取消", null)
                .show()
        }.onFailure {
            BambooLog.hw.w("panel", "clear favorites dialog failed", it)
            setMessage("打不开确认框，长按再试一次")
        }
    }

    /** 子分类下拉：就地覆盖在页签正下方，可上下滑，配色跟随主题。 */
    private fun showGroupPopup(page: Int, anchor: View) {
        val pageData = SymbolPages.PAGES.getOrNull(page) ?: return
        if (pageData.groups.size <= 1) return
        hideGroupPopup()

        val names = pageData.groups.map { it.name }
        val scrim = FrameLayout(context).apply {
            // 半透明遮罩：点空白处收起
            setBackgroundColor(0x66000000)
            isClickable = true
            setOnClickListener { hideGroupPopup() }
        }

        val list = ListView(context).apply {
            isVerticalScrollBarEnabled = true
            dividerHeight = dp(1)
            divider = ColorDrawable(colorSub and 0x33FFFFFF)
            setBackgroundColor(colorKeyBg)
            // 宽度跟内容自适应（左右各留 10dp），高度由下面按可用空间夹紧
            minimumWidth = dp(88)
            setPadding(dp(10), 0, dp(10), 0)
        }
        list.adapter = object : BaseAdapter() {
            override fun getCount(): Int = names.size
            override fun getItem(position: Int): Any = names[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(context).apply {
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), 0, dp(14), 0)
                    layoutParams = AbsListView.LayoutParams(WRAP_CONTENT, dp(46))
                }
                val selected = position == currentGroup
                tv.text = if (selected) "✓ " + names[position] else names[position]
                tv.setTextColor(if (selected) colorAccent else colorKeyText)
                tv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                tv.setOnClickListener { gotoPage(page, position) }
                return tv
            }
        }

        // 贴在页签条正下方、右侧对齐；高度绝不超过「下方功能键行」的顶边
        val itemH = dp(46)
        val top = panelTabsScroll.bottom + dp(1)
        val room = (contentFrame.height - top - dp(6)).coerceAtLeast(dp(120))
        val wanted = names.size * itemH + dp(2)
        val height = minOf(wanted, dp(252), room)
        // 宽度按最长选项量出来（含 ✓ 前缀），再夹在 88–220dp 之间，左右各留白 10dp
        val paint = Paint().apply { textSize = dp(15).toFloat() }
        val widest = names.maxOf { paint.measureText(it) }
        val checkW = paint.measureText("✓ ")
        val width = (widest + checkW + dp(28)).toInt().coerceIn(dp(88), dp(220))
        // 水平方向居中到「整个页签」（标题 + ▾ 这个整体）的正下方，再夹在容器内
        val ref = (anchor.parent as? View) ?: anchor
        val refPos = IntArray(2)
        ref.getLocationInWindow(refPos)
        val hostPos = IntArray(2)
        contentFrame.getLocationInWindow(hostPos)
        val refLeft = refPos[0] - hostPos[0]
        val refCenter = refLeft + ref.width / 2
        val maxLeft = (contentFrame.width - width - dp(2)).coerceAtLeast(dp(2))
        val left = (refCenter - width / 2).coerceIn(dp(2), maxLeft)
        val lp = FrameLayout.LayoutParams(width, height).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = top
            leftMargin = left
        }
        scrim.addView(list, lp)
        contentFrame.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        groupOverlay = scrim
    }

    private fun hideGroupPopup() {
        val overlay = groupOverlay ?: return
        groupOverlay = null
        runCatching { contentFrame.removeView(overlay) }
    }

    private fun buildPanelGrid(page: Int) {
        if (page == SymbolPages.PAGE_FAVORITE) {
            buildFavoriteGrid()
            return
        }
        val pageData = SymbolPages.PAGES.getOrNull(page) ?: SymbolPages.PAGES[0]
        val group = pageData.groups.getOrNull(currentGroup) ?: pageData.groups.firstOrNull()
        val items = group?.items ?: pageData.items
        val columns = pageData.columns.coerceIn(1, 8)
        panelGrid.removeAllViews()
        panelScroll.scrollTo(0, 0)
        items.chunked(columns).forEach { row ->
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { item ->
                rowView.addView(panelCell(item), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), 0)
                })
            }
            // 补齐最后一排，保持列宽一致
            repeat(columns - row.size) {
                rowView.addView(View(context), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            }
            // 36dp：数字页 4 行一屏放得下（不用滚动就能点到常用数字），符号页靠滑动看更多
            panelGrid.addView(rowView, LinearLayout.LayoutParams(MATCH_PARENT, dp(36)))
        }
        // 建完行后按可见高度均分：数字页 4 行会自动撑高到几乎铺满，
        // 符号页行多则退回 30dp 靠滑动看
        panelScroll.post { applyRowHeight() }
    }

    /**
     * 「常用」页：按上屏次数取前 20，短条目 6 列并排，长条目（颜文字、邮箱后缀）独占一行。
     *
     * 用「按字符数分档」而不是量宽度，是因为构建时面板宽还是 0（要等布局），
     * 而 6 个字符以上的条目本来就不可能在一格里横排下（见 panelCell 的自动缩字号）。
     */
    private fun buildFavoriteGrid() {
        panelGrid.removeAllViews()
        panelScroll.scrollTo(0, 0)
        val items = runCatching { SymbolUsage.favorites() }.getOrDefault(emptyList())
        if (items.isEmpty()) {
            val hint = TextView(context).apply {
                text = "还没有记录：用过的符号会自动排到这里（最多 20 个）"
                textSize = 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(colorSub)
            }
            panelGrid.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, dp(36)))
            panelScroll.post { applyRowHeight() }
            return
        }
        val columns = 6
        val bucket = mutableListOf<String>()
        fun flush() {
            if (bucket.isEmpty()) return
            addFavoriteRow(bucket.toList(), columns)
            bucket.clear()
        }
        for (item in items) {
            if (item.length >= 6) {
                flush()
                addFavoriteRow(listOf(item), 1)
            } else {
                bucket.add(item)
                if (bucket.size == columns) flush()
            }
        }
        flush()
        panelScroll.post { applyRowHeight() }
    }

    private fun addFavoriteRow(row: List<String>, visualColumns: Int) {
        val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.forEach { item ->
            rowView.addView(
                panelCell(item),
                LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), 0)
                },
            )
        }
        repeat(visualColumns - row.size) {
            rowView.addView(View(context), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        panelGrid.addView(rowView, LinearLayout.LayoutParams(MATCH_PARENT, dp(36)))
    }

    /** 让当前页的行刚好（略小于）铺满滚动区，夹在 30dp~64dp。 */
    private fun applyRowHeight() {
        val rows = panelGrid.childCount
        val available = panelScroll.height
        if (rows <= 0 || available <= 0) return
        val target = (available / rows).coerceIn(dp(30), dp(64))
        for (i in 0 until rows) {
            val child = panelGrid.getChildAt(i) ?: continue
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            lp.height = target
            child.layoutParams = lp
        }
    }

    private fun panelCell(item: String) = TextView(context).apply {
        text = item
        if (item.length >= 6) {
            // 长条目（邮箱后缀这类）：单行 + 字号自动缩，避免折行把 36dp 的行撑破。
            // 用 autoSize 而不是按字符数估算，是因为中英文混排的宽度差得很远。
            isSingleLine = true
            maxLines = 1
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 8, 16, 1, TypedValue.COMPLEX_UNIT_SP,
            )
        } else {
            textSize = 18f
        }
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(colorKeyText)
        background = keyDrawable("key_back_color", colorKeyBg, dp(1), cornerPx)
        setOnClickListener {
            SymbolUsage.record(item)
            commit(item)
        }
    }

    // ---------------------------------------------------------------- 识别与上屏

    private fun ensureModel() {
        if (HandwritingPrefs.showPinyin) HanziPinyin.load(service)
        if (modelLoaded || HandwritingRecognizer.isReady) {
            modelLoaded = true
            return
        }
        val version = HandwritingPrefs.activeVersion
        service.lifecycleScope.launch {
            var loadMb = 0
            val ok =
                withContext(Dispatchers.IO) {
                    var loaded = false
                    val delta =
                        MemoryProbe.measureDeltaMb(service.applicationContext) {
                            loaded = HandwritingRecognizer.load(service, version)
                        }
                    loadMb = delta
                    loaded
                }
            if (ok) MemoryPrefs.setMeasuredMb("handwriting", loadMb)
            modelLoaded = ok
            setMessage(
                if (ok) {
                    null
                } else if (HandwritingRecognizer.isEngineAvailable) {
                    "手写模型未就绪"
                } else {
                    "手写引擎不可用"
                },
            )
        }
    }

    private fun scheduleRecognize() {
        recognizeJob?.cancel()
        if (board.isEmpty) {
            showCandidates(emptyList())
            return
        }
        // 识别过程中不显示任何提示：提示行会把布局撑高，手写区会被压缩
        recognizeJob = service.lifecycleScope.launch {
            delay(HandwritingPrefs.pauseMs.toLong())
            // 计时到了但手指还按在画布上（正在写这一笔）→ 这一轮不算数，等抬笔重新计时
            if (board.isDrawing) return@launch
            if (!HandwritingRecognizer.isReady) {
                setMessage("手写模型未就绪")
                return@launch
            }
            val strokes = board.snapshot()
            val topK = HandwritingPrefs.maxCandidates
            val result = withContext(Dispatchers.Default) {
                runCatching { HandwritingRecognizer.recognize(strokes, topK) }
                    .onFailure { Timber.w(it, "handwriting: recognize failed") }
                    .getOrDefault(emptyList())
            }
            submitSegment(result)
        }
    }

    /**
     * 把这一段笔迹的候选接在已累计的字后面，形成组合候选。
     *
     * 顺序规则：先按旧假设的优先级，再按本段候选的优先级；重复的串去重，
     * 最多保留「候选个数」个，避免候选条无限长。
     */
    private fun submitSegment(result: List<HandwritingRecognizer.Candidate>) {
        if (result.isEmpty()) return
        segs.add(result.map { it.text }.distinct())
        rebuildCandidates()
    }

    /** 按「每段候选做笛卡尔积」拼出候选条，顺序＝先段序、再段内优先级。 */
    private fun rebuildCandidates() {
        if (segs.isEmpty()) {
            renderCandidates(emptyList())
            return
        }
        val cap = HandwritingPrefs.maxCandidates
        var acc = listOf("")
        for (seg in segs) {
            val next = ArrayList<String>(cap)
            outer@ for (base in acc) {
                for (text in seg) {
                    next.add(base + text)
                    if (next.size >= cap) break@outer
                }
            }
            acc = next
            if (acc.isEmpty()) break
        }
        renderCandidates(LinkedHashSet(acc).map { HandwritingRecognizer.Candidate(it, 0f) })
    }

    /**
     * ⌫ 分层退格：
     * 1. 有候选 → 退掉最后一段（多字候选就逐字删）；
     * 2. 没候选但画布有笔迹 → 清笔迹；
     * 3. 都没有 → 删编辑区一个字。
     */
    private fun backspace() {
        recognizeJob?.cancel()
        if (segs.isNotEmpty()) {
            segs.removeAt(segs.lastIndex)
            if (segs.isEmpty()) {
                board.clear()
                renderCandidates(emptyList())
            } else {
                rebuildCandidates()
            }
            return
        }
        if (!board.isEmpty) {
            board.clear()
            renderCandidates(emptyList())
            return
        }
        deleteEditorChar()
    }

    /** 长按 ⌫：候选/笔迹还有东西就全清，否则连删编辑区文字。 */
    private fun onBackspaceLongPress() {
        if (segs.isNotEmpty() || !board.isEmpty) {
            recognizeJob?.cancel()
            segs.clear()
            board.clear()
            renderCandidates(emptyList())
            return
        }
        repeating = true
        repeatHandler.post(repeatRunnable)
    }

    private fun stopRepeating() {
        repeating = false
        repeatHandler.removeCallbacks(repeatRunnable)
    }

    private fun showCandidates(list: List<HandwritingRecognizer.Candidate>) {
        // 内容和上次完全一样就不重画：退出时 leaveToKeyboard 与 onDetached 会连着清两次，
        // 日志里表现为同一毫秒内两条 "candidates=0 segs=0"，纯属白画一帧。
        if (list == lastCandidates) return
        renderCandidates(list)
    }
    private fun renderCandidates(list: List<HandwritingRecognizer.Candidate>) {
        lastCandidates = list
        BambooLog.hw.d(
            "recog",
            "candidates=${list.size} segs=${segs.size} first=${list.firstOrNull()?.text}",
        )
        // 认出候选后立刻清掉笔迹：候选留在条上，手可以直接写下一个字。
        // 只有识别出东西时才清，避免认不出时把用户写的字擦掉。
        if (list.isNotEmpty()) board.clear()
        candidateRow.removeAllViews()
        // 候选清空时必须一起清掉 cells/selectedIndex：空格上屏读的是 cells，
        // 不清就会把上一次上屏过的词再上屏一次（哪怕候选条已经空了）。
        cells.clear()
        selectedIndex = 0
        if (list.isEmpty()) {
            // 失败提示放在候选条内部（那里本来就有固定高度），不新增行
            setMessage(if (board.isEmpty) null else "没认出字，重写一次试试")
            return
        }
        setMessage(null)
        list.forEachIndexed { index, c ->
            val charCount = c.text.codePointCount(0, c.text.length)
            val pinyinText = if (HandwritingPrefs.showPinyin && charCount <= 2) {
                HanziPinyin.ofText(c.text)
            } else {
                null
            }
            val textView = TextView(context).apply {
                text = c.text
                textSize = 18f
                includeFontPadding = false
                maxLines = 1
                setTextColor(colorKeyText)
            }
            val pinyinView = if (!pinyinText.isNullOrEmpty()) {
                TextView(context).apply {
                    text = pinyinText
                    textSize = 10f
                    includeFontPadding = false
                    maxLines = 1
                    setTextColor(colorSub)
                }
            } else {
                null
            }
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), 0, dp(13), 0)
                isClickable = true
                setOnClickListener {
                    // 点谁，高亮先挪到谁身上（A 方案：点即上屏，但视觉上先给到反馈）
                    selectedIndex = index
                    applySelection()
                    commit(c.text)
                }
            }
            item.addView(textView)
            if (pinyinView != null) {
                item.addView(
                    pinyinView,
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(3) },
                )
            }
            candidateRow.addView(
                item,
                LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                },
            )
            cells += CandidateCell(item, textView, pinyinView, charCount, c.text)
        }
        applySelection()
    }

    /**
     * 实时同步「当前项」：以「第一个候选原本占据的那块区域 A」为锚点。
     *
     * 规则：
     * - A = 候选生成后第 1 个候选那一整块，宽度随它有几个字而定，渲染后固定不动；
     * - A 的中心 ±15% A 宽就是「该高亮」的范围；
     * - 左右滑动都按「谁的格心离 A 的中心更近」判定，落在容差内才换；
     * - 没有任何候选落在容差内（正卡在两格之间）→ 保持上一个高亮，避免抖动。
     *
     * 坐标：子 View 的 left/width 是内容坐标，不随 scrollX 变，所以 A 的中心是常量；
     * 滑动后用 scrollX 把它换算成「当前该对齐到的内容位置」。
     */
    private fun syncSelectionFromScroll() {
        if (cells.isEmpty()) return
        val sv = candidateScroll ?: return
        if (sv.width <= 0 || candidateRow.width <= 0) return
        val anchorCell = cells.firstOrNull() ?: return
        if (anchorCell.item.width <= 0) return
        val anchorWidth = anchorCell.item.width
        val anchorCenter = anchorCell.item.left + anchorWidth / 2
        val target = sv.scrollX + anchorCenter
        val tolerance = (anchorWidth * 0.15f).toInt().coerceAtLeast(1)
        var best = -1
        var bestDist = Int.MAX_VALUE
        cells.forEachIndexed { i, cell ->
            if (cell.item.width == 0) return@forEachIndexed
            val dist = kotlin.math.abs(cell.item.left + cell.item.width / 2 - target)
            if (dist <= tolerance && dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        if (best < 0) return
        if (best != selectedIndex) {
            selectedIndex = best
            applySelection()
            BambooLog.hw.d(
                "hl",
                "anchor select idx=$best dist=$bestDist tol=$tolerance anchorW=$anchorWidth x=${sv.scrollX}",
            )
        }
    }

    /**
     * 把高亮画到 selectedIndex 上。
     *
     * 只改背景/文字色，不重建视图，滑动时不会跳动。
     */
    private fun applySelection() {
        if (cells.isEmpty()) return
        selectedIndex = selectedIndex.coerceIn(0, cells.lastIndex)
        cells.forEachIndexed { i, cell ->
            val on = i == selectedIndex
            cell.item.background = if (on) {
                GradientDrawable().apply {
                    setColor(colorHighlight)
                    cornerRadius = dp(6).toFloat()
                }
            } else {
                null
            }
            cell.text.setTextColor(if (on) colorHighlightText else colorKeyText)
            cell.pinyin?.let { pv ->
                pv.setTextColor(if (on) colorHighlightText else colorSub)
                // 单字：每个候选都带读音；两字：只有「当前项」带读音；三字及以上不显示
                val showPinyin = cell.charCount == 1 || (on && cell.charCount == 2)
                pv.visibility = if (showPinyin) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    /** 在候选条里显示一句灰字（占候选条原有高度，不改变手写区大小）。 */
    private fun setMessage(text: String?) {
        if (!this::messageText.isInitialized) return
        candidateRow.removeAllViews()
        if (text.isNullOrEmpty()) return
        candidateRow.addView(
            messageText.apply { this.text = text },
            LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT),
        )
    }

    /** 空格：有候选时上屏首选，否则上普通空格。 */
    private fun commitSpace() {
        // 空格上屏「当前高亮」那一项（滑动后就是滑到 A 位置的那个），没有候选时打普通空格
        // 没有候选时一定走普通空格：不能因为 cells 里残留旧值又把上次的词上屏一遍
        val picked = if (lastCandidates.isEmpty()) {
            null
        } else {
            cells.getOrNull(selectedIndex)?.textValue ?: lastCandidates.firstOrNull()?.text
        }
        if (picked != null) commit(picked) else commit(" ")
    }

    private fun commit(text: String) {
        if (text.isEmpty()) return
        if (service.currentInputConnection == null) {
            // 输入连接断掉时上屏必然失败，先把原因显示出来，别让用户以为按钮坏了
            BambooLog.hw.w("commit", "no input connection, dropped len=${text.length}")
            setMessage("输入框已断开，点一下输入框再试")
            return
        }
        BambooLog.hw.d("commit", "text=\"$text\" attached=${board.isAttachedToWindow}")
        board.clear()
        segs.clear()
        showCandidates(emptyList())
        // 注意：不在这里收起面板——数字/符号面板要一直留着，直到点「收起」或再点同一个按钮
        // 和服务层 commit 同一条路（立刻上屏），并手动记账，保证 qq/ 回退能删到手写的字
        service.commitText(text)
    }

    /** 深色往文字色方向混合，用作没有主题高亮色时的兜底。 */
    private fun blend(base: Int, target: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val b = (base shr shift) and 0xFF
            val t = (target shr shift) and 0xFF
            return (b + (t - b) * r).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** 真正删除编辑区的一个字符（同时把这笔算进回退账本，避免 qq/ 多删一个字）。 */
    private fun deleteEditorChar() {
        val ic = service.currentInputConnection
        if (ic == null) {
            BambooLog.hw.w("delete", "no input connection")
            return
        }
        val units = unitsOfLastChar(ic)
        val ok = runCatching { ic.deleteSurroundingText(units, 0) }.getOrDefault(false)
        if (ok) runCatching { BambooActions.onBackspaceReplayed() }
    }

    /**
     * 光标前「一个完整字符」占几个 UTF-16 单元。
     *
     * 直接删 1 个单元会把 emoji 的代理对切成两半，剩下孤立的半个码位，
     * 编辑器只能画成「黑框里一个问号」。用 BreakIterator 取字符边界，
     * 顺带把 👨‍👩‍👧‍👦、👋🏽 这种多码位拼出来的 emoji 也一次删干净。
     */
    private fun unitsOfLastChar(ic: InputConnection): Int {
        val before = runCatching { ic.getTextBeforeCursor(64, 0)?.toString() }.getOrNull()
        if (before.isNullOrEmpty()) return 1
        return runCatching {
            val boundary = java.text.BreakIterator.getCharacterInstance()
            boundary.setText(before)
            val end = before.length
            val start = boundary.preceding(end)
            (end - start).coerceIn(1, 8)
        }.getOrDefault(1)
    }

    private fun sendEnter() {
        val ic = service.currentInputConnection
        if (ic == null) {
            BambooLog.hw.w("enter", "no input connection")
            setMessage("输入框已断开，点一下输入框再试")
            return
        }
        BambooLog.hw.d("enter", "send KEYCODE_ENTER")
        runCatching {
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        }.onFailure { BambooLog.hw.w("enter", "sendKeyEvent failed", it) }
    }

    /**
     * 回到按键键盘。所有离开手写板的路径都走这里，
     * 保证 `active` 一定被复位——否则下一次 InputView.startInput() 会把手写板又挂回来，
     * 用户看到的现象就是「点了返回 / 切走再回来，还是手写板」。
     */
    /**
     * 结束这次手写：清掉笔迹、候选、已累计的段。
     *
     * 「点了返回」和「被别的窗口顶掉」都算结束，所以要清干净；
     * 而切 App（收起输入法）不会 detach，因此「切走再回来接着写」仍然保留。
     */
    private fun resetHandwritingState() {
        recognizeJob?.cancel()
        segs.clear()
        if (this::board.isInitialized) board.clear()
        if (this::candidateRow.isInitialized) {
            // 不要在这里预置 lastCandidates，否则 showCandidates 的「内容没变就不重画」会把清空吃掉
            runCatching { renderCandidates(emptyList()) }
                .onFailure { BambooLog.hw.w("life", "reset candidates failed", it) }
        }
    }

    private fun leaveToKeyboard() {
        hideGroupPopup()
        panelOpen = false
        panelOnlyPage = -1
        runCatching { refreshToolButtons() }
        val self = windowManager.isAttached(this)
        val onScreen = rootView?.isAttachedToWindow == true
        BambooLog.hw.d(
            "life",
            "leaveToKeyboard active=$active self=$self onScreen=$onScreen " +
                "kb=${windowManager.isKeyboardAttached()} segs=${segs.size}",
        )
        // 已经在键盘上（连点返回的第二、第三下）：什么都不用做
        if (!self && !onScreen) {
            BambooLog.hw.d("life", "leaveToKeyboard ignored (already on keyboard)")
            return
        }
        active = false
        resetHandwritingState()
        runCatching {
            if (this::panel.isInitialized) hidePanel()
            // 用 Key 重载：这样拿到的永远是「当前这套依赖」里缓存的那个键盘窗口
            windowManager.attachWindow(KeyboardWindow)
        }.onFailure {
            BambooLog.hw.w("life", "leaveToKeyboard failed", it)
            Timber.w(it, "handwriting: leaveToKeyboard failed")
        }
        // 兜底：不管管理器怎么说，只要这棵树还挂在窗口上，就直接从父容器摘掉。
        // （旧管理器 early-return、或者视图与 currentWindow 脱节时，这是唯一可靠的退出方式）
        val v = rootView
        if (v != null && v.isAttachedToWindow) {
            BambooLog.hw.w("life", "view still on screen after attachWindow(keyboard), force removing")
            runCatching {
                (v.parent as? ViewGroup)?.removeView(v)
            }.onFailure { BambooLog.hw.w("life", "force remove view failed", it) }
        }
    }

    override fun onAttached() {
        BambooLog.hw.d("life", "onAttached active=$active")
        isBoardVisible = true
        IdleReclaimer.touch("handwriting")
        ensureModel()
    }

    override fun onDetached() {
        isBoardVisible = false
        hideGroupPopup()
        // 不管从哪条路离开（返回键 / 切输入法 / 换输入框 / 打开剪贴板等其它面板），
        // 都算这一次手写结束：清候选、清笔迹，避免下次进来接着拼。
        // 切 App（收起输入法）不会 detach，所以「切走再回来接着写」不受影响。
        BambooLog.hw.d("life", "onDetached active=$active segs=${segs.size}")
        active = false
        resetHandwritingState()
        if (this::panel.isInitialized) hidePanel()
    }

    override fun toString(): String = "HandwritingWindow"
}

/**
 * 底部工具栏用到的图标名。
 *
 * 来自 Community Material 图标字体（`com.mikepenz:community-material-typeface`），
 * 名字是下划线连接的全小写形式；写错不会崩，只会退回文字（见 ToolKey.applyContent）。
 */
private object ToolIcons {
    const val BACK = "cmd_arrow_left"
    const val NUMBER = "cmd_numeric"
    const val SPACE = "cmd_keyboard_space"
    const val SYMBOL = "cmd_symbol"
    const val COLLAPSE = "cmd_keyboard_close"
    const val ENTER = "cmd_keyboard_return"
    const val BACKSPACE = "cmd_backspace"
}
