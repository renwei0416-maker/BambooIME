/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewAnimator
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.preference.PreferenceManager
import com.osfans.trime.data.db.ClipboardSource
import com.osfans.trime.data.db.ClipboardTab
import com.osfans.trime.data.db.ClipboardTabFilter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.osfans.trime.util.resetStuckScrollOnTouch
import com.osfans.trime.R
import com.osfans.trime.data.db.ClipboardCategory
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.segments.SegmentsWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import kotlin.time.Duration.Companion.milliseconds

class ClipboardWindow(
    private val initialCategory: ClipboardCategory? = null,
) : BoardWindow.BarBoardWindow() {

    companion object {
        private const val LAST_OPENED_CATEGORY_KEY = "clipboard_last_opened_category"
    private const val LAST_OPENED_TAB_KEY = "clipboard_last_opened_tab"
    private const val LAST_FAVORITES_ONLY_KEY = "clipboard_last_favorites_only"
    private const val LAST_SOURCE_KEY = "clipboard_last_source"
        private const val UNDO_TIMEOUT_MS = 5000L
    }

    private val service: TrimeInputMethodService by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private val theme: Theme by di.instance()

    private val prefs = AppPrefs.defaultInstance().clipboard
    private val sharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    private val clipboardEnabledPref = prefs.clipboardListening
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste
    private val clipboardMaskSensitive by prefs.clipboardMaskSensitive

    private var currentTab = initialCategory.toInitialTab()
    private var favoritesOnly = initialCategory == ClipboardCategory.Favorites
    private var sourceFilter = initialCategory.toInitialSource()
    private var adapterSubmitJob: Job? = null

    private fun restoreFilters() {
        val savedTab = sharedPreferences.getString(LAST_OPENED_TAB_KEY, null)
        currentTab = ClipboardTab.entries.firstOrNull { it.name == savedTab } ?: ClipboardTab.All
        favoritesOnly = sharedPreferences.getBoolean(LAST_FAVORITES_ONLY_KEY, false)
        val savedSource = sharedPreferences.getString(LAST_SOURCE_KEY, null)
        sourceFilter = ClipboardSource.entries.firstOrNull { it.name == savedSource } ?: ClipboardSource.All
    }

    private fun persistFilters() {
        sharedPreferences.edit {
            putString(LAST_OPENED_TAB_KEY, currentTab.name)
            putBoolean(LAST_FAVORITES_ONLY_KEY, favoritesOnly)
            putString(LAST_SOURCE_KEY, sourceFilter.name)
        }
    }

    private val adapter by lazy {
        object : ClipboardAdapter(theme, clipboardMaskSensitive) {
            init {
                onLongPressEnterSelectMode = { id -> enterMultiSelectMode(id) }
                onSelectionChanged = { updateMultiSelectToolbar() }
            }
            override fun onPaste(entry: DatabaseBean) {
                if (entry.isUriEntry() && entry.type.startsWith("image/")) {
                    // 图片走 commitContent：编辑器里它不是字符，按 DEL 不保证能删，
                    // 所以不记账本（回退对图片不可靠）。
                    val uri = entry.text.toUri()
                    val mimeType = entry.type.takeIf { it.isNotBlank() }
                        ?: context.contentResolver.getType(uri)
                        ?: "image/*"
                    service.commitImage(uri, mimeType)
                } else {
                    // 和其它上屏路径一样先记一笔账本，qq/ 才能把粘贴进来的内容删掉
                    com.osfans.trime.actions.bamboo.BambooActions.onCommitted(entry.text)
                    service.commitText(entry.text)
                }
                if (clipboardReturnAfterPaste) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            override fun onPin(id: Int) {
                service.lifecycleScope.launch { ClipboardHelper.pin(id) }
            }

            override fun onUnpin(id: Int) {
                service.lifecycleScope.launch { ClipboardHelper.unpin(id) }
            }

            override fun onEdit(id: Int) {
                AppUtils.launchClipEdit(context, id)
            }

            override fun onShare(entry: DatabaseBean) {
                val target = if (entry.isUriEntry()) {
                    val uri = entry.text.toUri()
                    val mimeType = runCatching { context.contentResolver.getType(uri) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: entry.type.takeIf {
                            it.isNotBlank() && it != ClipDescription.MIMETYPE_TEXT_URILIST
                        }
                        ?: "*/*"
                    Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        clipData = ClipData.newUri(context.contentResolver, "clipboard", uri)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, entry.text)
                    }
                }
                val chooser = Intent.createChooser(target, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(chooser)
            }

            override fun onSplitText(text: String) {
                windowManager.attachWindow(SegmentsWindow(text))
            }

            override fun onExtract(entry: DatabaseBean) {
                ClipboardExtractor(context, service, windowManager).showExtractDialog(entry.text)
            }

            override fun onCopyText(entry: DatabaseBean) {
                copyToSystemClipboard(entry.text, R.string.clipboard_copy_text)
            }

            override fun onCopyCode(entry: DatabaseBean) {
                val code = ClipboardTabFilter.verificationCode(entry.text)
                if (code == null) {
                    Toast.makeText(context, R.string.clipboard_no_verification_code, Toast.LENGTH_SHORT).show()
                } else {
                    copyToSystemClipboard(code, R.string.clipboard_copy_code)
                }
            }

            override fun onSearch(query: String) {
                val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    service.startActivity(webSearchIntent)
                }
            }

            override fun onDial(number: String) {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.fromParts("tel", number, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    service.startActivity(intent)
                }
            }

            override fun onOpenLink(uri: Uri) {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    service.startActivity(intent)
                }
            }

            override fun onViewImage(uri: Uri) {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    service.startActivity(intent)
                }
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch {
                    ClipboardHelper.delete(id)
                    showDeleteToast(id)
                }
            }
        }
    }

    /**
     * 有 DAO 分页就直接用，正则类页签落到内存筛选。
     *
     * 注意：Pager 的工厂闭包每被调用一次都必须**新建** PagingSource。若把
     * entriesPagerOrNull() 的结果先存进 val 再交给闭包，列表一次失效（例如批量
     * 插入多条）就会触发 Paging 的复用检测：
     *   IllegalStateException: An instance of PagingSource was re-used
     */
    private fun pagerFlow(): Flow<PagingData<DatabaseBean>> {
        val tab = currentTab
        val onlyFavorites = favoritesOnly
        val source = sourceFilter
        return if (ClipboardHelper.supportsDaoPager(tab, onlyFavorites, source)) {
            Pager(
                PagingConfig(
                    pageSize = 16,
                    enablePlaceholders = false,
                ),
            ) {
                // supportsDaoPager() 已保证这里非空，现造一个新实例
                ClipboardHelper.entriesPagerOrNull(tab, onlyFavorites, source)!!
            }.flow
        } else {
            flow { emit(PagingData.from(ClipboardHelper.filteredEntries(tab, onlyFavorites, source))) }
        }
    }

    private fun submitTab(tab: ClipboardTab) {
        currentTab = tab
        persistFilters()
        reloadEntries()
        ui.setSelectedTab(tab)
    }

    private fun reloadEntries() {
        adapterSubmitJob?.cancel()
        // 提交新数据前把 ItemTouchHelper 摘了重挂：它会 cancel() 掉还挂着的滑动恢复动画，
        // 那些动画期间列表不响应点击（用户看到的就是「点了没反应」）。
        runCatching {
            itemTouchHelper?.attachToRecyclerView(null)
            itemTouchHelper?.attachToRecyclerView(ui.recyclerView)
        }
        adapterSubmitJob = service.lifecycleScope.launch {
            // 切页签很密集时，上一次 submitData 可能还没真正收尾；
            // 这里兜住"重复提交"这类异常，不让它把输入法带走。
            pagerFlow().collect {
                runCatching { adapter.submitData(it) }
                    .onFailure { android.util.Log.w("clipboard", "submitData failed", it) }
            }
        }
    }

    private val ui by lazy {
        ClipboardUi(context, theme).apply {
            recyclerView.apply {
                layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                itemAnimator = null
                adapter = this@ClipboardWindow.adapter
                // 拖到顶/底松手后若停在 SETTLING，下一次按下会被当成滑动拦截 → 整片点不动
                resetStuckScrollOnTouch()
            }
            setSelectedTab(currentTab)
            setOnTabSelectedListener(::submitTab)
            itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int = makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean = false

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    // 提交数据的过程中 bindingAdapterPosition 会是 NO_POSITION，
                    // 位置失效时把这一行还原，别让它停在半拖状态里吃掉后续点击。
                    val position = viewHolder.absoluteAdapterPosition
                    val entry =
                        if (position == RecyclerView.NO_POSITION) null else adapter.getEntryAt(position)
                    if (entry == null) {
                        if (position != RecyclerView.NO_POSITION) {
                            runCatching { recyclerView.adapter?.notifyItemChanged(position) }
                        }
                        return
                    }
                    service.lifecycleScope.launch {
                        ClipboardHelper.delete(entry.id)
                        showDeleteToast(entry.id)
                    }
                }
            }).also { it.attachToRecyclerView(recyclerView) }
            enableUi.enableButton.setOnClickListener {
                clipboardEnabledPref.setValue(true)
            }
        }
    }

    private var itemTouchHelper: ItemTouchHelper? = null

    override fun onCreateView(): View {
        if (initialCategory == null) {
            restoreFilters()
            ui.setSelectedTab(currentTab)
        }
        return ui.root
    }

    private val pendingDeleteIds = arrayListOf<Int>()
    private var undoTimeoutJob: Job? = null

    private fun showDeleteToast(vararg ids: Int) {
        undoTimeoutJob?.cancel()
        if (pendingDeleteIds.isNotEmpty()) {
            service.lifecycleScope.launch { ClipboardHelper.realDelete() }
            pendingDeleteIds.clear()
        }
        pendingDeleteIds.addAll(ids.toList())
        val count = pendingDeleteIds.size
        Toast.makeText(context, context.getString(R.string.num_items_deleted, count), Toast.LENGTH_SHORT).show()
        undoTimeoutJob = service.lifecycleScope.launch {
            delay(UNDO_TIMEOUT_MS.milliseconds)
            if (pendingDeleteIds.isNotEmpty()) {
                ClipboardHelper.realDelete()
                pendingDeleteIds.clear()
            }
            undoTimeoutJob = null
            updateUndoButtonVisibility()
        }
        updateUndoButtonVisibility()
    }

    private fun undoLastDelete() {
        undoTimeoutJob?.cancel()
        undoTimeoutJob = null
        if (pendingDeleteIds.isNotEmpty()) {
            service.lifecycleScope.launch {
                ClipboardHelper.undoDelete(*pendingDeleteIds.toIntArray())
                pendingDeleteIds.clear()
                updateUndoButtonVisibility()
            }
        }
    }

    private val barFilterButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_baseline_tune_24)
        imageTintList = android.content.res.ColorStateList.valueOf(ColorManager.getColor("key_text_color"))
        background = null
        setOnClickListener { showFilterDialog() }
    }

    private fun refreshFilterButton() {
        val active = favoritesOnly || sourceFilter != ClipboardSource.All
        val color = if (active) {
            ColorManager.getColor("clipboard_category_selected_text_color")
        } else {
            ColorManager.getColor("key_text_color")
        }
        barFilterButton.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun showFilterDialog() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
        }
        val favSwitch = android.widget.Switch(context).apply {
            text = context.getString(R.string.clipboard_only_favorites)
            isChecked = favoritesOnly
            setPadding(0, dp(4), 0, dp(8))
        }
        column.addView(favSwitch)
        val group = android.widget.RadioGroup(context).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val sourceIds = ClipboardSource.entries.map { src ->
            val button = android.widget.RadioButton(context).apply {
                id = View.generateViewId()
                text = context.getString(src.titleRes)
                isChecked = src == sourceFilter
            }
            group.addView(button)
            button.id to src
        }
        column.addView(group)
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(R.string.clipboard_filter)
            .setView(column)
            .setPositiveButton(R.string.ok) { _, _ ->
                favoritesOnly = favSwitch.isChecked
                sourceFilter = sourceIds.firstOrNull { it.first == group.checkedRadioButtonId }?.second
                    ?: ClipboardSource.All
                persistFilters()
                refreshFilterButton()
                reloadEntries()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        service.showDialog(dialog)
    }

    private fun copyToSystemClipboard(text: String, labelRes: Int) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(labelRes), text))
        Toast.makeText(context, context.getString(R.string.clipboard_copy_text), Toast.LENGTH_SHORT).show()
    }

    private val barDeleteAllButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_baseline_delete_sweep_24)
        imageTintList = android.content.res.ColorStateList.valueOf(ColorManager.getColor("key_text_color"))
        background = null
        setOnClickListener { showDeleteAllConfirm() }
    }

    private val barUsageText = TextView(context).apply {
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        textSize = theme.generalStyle.fonts.clipboard_category_size
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setTextColor(ColorManager.getColor("key_text_color"))
        typeface = FontManager.getTypeface()
    }

    private val barSelectButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_baseline_check_circle_24)
        imageTintList = android.content.res.ColorStateList.valueOf(ColorManager.getColor("key_text_color"))
        background = null
        setOnClickListener { enterMultiSelectMode() }
    }

    private val barSearchButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_baseline_search_24)
        imageTintList = android.content.res.ColorStateList.valueOf(ColorManager.getColor("key_text_color"))
        background = null
        setOnClickListener { AppUtils.launchClipSearch(context) }
    }

    private val barUndoButton = TextView(context).apply {
        text = context.getString(R.string.undo)
        gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        textSize = theme.generalStyle.fonts.clipboard_category_size
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setTextColor(ColorManager.getColor("key_text_color"))
        typeface = FontManager.getTypeface()
        visibility = View.GONE
        setOnClickListener { undoLastDelete() }
    }

    private val barSelectedCountText = TextView(context).apply {
        gravity = android.view.Gravity.CENTER or android.view.Gravity.CENTER_VERTICAL
        textSize = theme.generalStyle.fonts.clipboard_category_size
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setTextColor(ColorManager.getColor("key_text_color"))
        typeface = FontManager.getTypeface()
    }

    private val barCancelSelectButton = TextView(context).apply {
        text = context.getString(R.string.clipboard_exit_select)
        gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        textSize = theme.generalStyle.fonts.clipboard_category_size
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setTextColor(ColorManager.getColor("key_text_color"))
        typeface = FontManager.getTypeface()
        setOnClickListener { exitMultiSelectMode() }
    }

    private val barDeleteSelectedButton = TextView(context).apply {
        text = context.getString(R.string.clipboard_delete_selected)
        gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        textSize = theme.generalStyle.fonts.clipboard_category_size
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setTextColor(ColorManager.getColor("key_text_color"))
        typeface = FontManager.getTypeface()
        setOnClickListener { showDeleteSelectedConfirm() }
    }

    private fun showDeleteAllConfirm() {
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(R.string.delete_all)
            .setMessage(R.string.ask_to_delete_all)
            .setPositiveButton(R.string.ok) { _, _ ->
                service.lifecycleScope.launch {
                    ClipboardHelper.deleteAllMatching(currentTab, favoritesOnly, sourceFilter, skipPinned = true)
                    Toast.makeText(context, context.getString(R.string.delete_all_done), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        service.showDialog(dialog)
    }

    private fun enterMultiSelectMode(initialId: Int = -1) {
        adapter.multiSelectMode = true
        if (initialId > 0) {
            adapter.selectedIds.add(initialId)
        }
        adapter.notifyDataSetChanged()
        toolbarAnimator.displayedChild = 1
        updateMultiSelectToolbar()
    }

    private fun exitMultiSelectMode() {
        adapter.multiSelectMode = false
        adapter.selectedIds.clear()
        adapter.notifyDataSetChanged()
        toolbarAnimator.displayedChild = 0
        updateUndoButtonVisibility()
    }

    private fun updateMultiSelectToolbar() {
        val count = adapter.selectedIds.size
        barSelectedCountText.text = context.getString(R.string.clipboard_selected_count, count)
        barDeleteSelectedButton.isEnabled = count > 0
    }

    private fun showDeleteSelectedConfirm() {
        val count = adapter.selectedIds.size
        if (count == 0) return
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(R.string.clipboard_delete_selected)
            .setMessage(context.getString(R.string.clipboard_confirm_delete_selected, count))
            .setPositiveButton(R.string.ok) { _, _ ->
                service.lifecycleScope.launch {
                    deleteSelectedEntries()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        service.showDialog(dialog)
    }

    private suspend fun deleteSelectedEntries() {
        val ids = adapter.selectedIds.toIntArray()
        if (ids.isEmpty()) return
        undoTimeoutJob?.cancel()
        if (pendingDeleteIds.isNotEmpty()) {
            ClipboardHelper.realDelete()
            pendingDeleteIds.clear()
            updateUndoButtonVisibility()
        }
        ClipboardHelper.markAsDeleted(*ids)
        ClipboardHelper.realDelete()
        adapter.selectedIds.clear()
        exitMultiSelectMode()
        Toast.makeText(context, context.getString(R.string.delete_all_done), Toast.LENGTH_SHORT).show()
    }

    private fun updateUndoButtonVisibility() {
        barUndoButton.visibility =
            if (pendingDeleteIds.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateUsageText() {
        val used = ClipboardHelper.itemCount
        val limit = prefs.clipboardLimit.getValue()
        barUsageText.text = "($used/$limit)"
    }

    private val usageCountListener = ClipboardHelper.OnClipboardUpdateListener {
        barUsageText.post {
            updateUsageText()
        }
    }

    private lateinit var stateMachine: ClipboardStateMachine

    override fun onAttached() {
        updateUsageText()
        val isEmpty = ClipboardHelper.itemCount == 0
        val isListening = clipboardEnabledPref.getValue()
        val initialState = when {
            !isListening -> ClipboardStateMachine.State.EnableListening
            isEmpty -> ClipboardStateMachine.State.AddMore
            else -> ClipboardStateMachine.State.Normal
        }
        stateMachine = ClipboardStateMachine(isEmpty, isListening)
        stateMachine.onStateChanged = { state -> ui.switchUiByState(state) }
        ui.switchUiByState(initialState)
        adapter.addLoadStateListener {
            val empty = it.append.endOfPaginationReached && adapter.itemCount < 1
            stateMachine.updateEmpty(empty)
        }
        submitTab(currentTab)
        clipboardEnabledPref.registerOnChangeListener(clipboardEnabledListener)
        updateUndoButtonVisibility()
        ClipboardHelper.addOnUpdateListener(usageCountListener)
    }

    override fun onDetached() {
        ClipboardHelper.removeOnUpdateListener(usageCountListener)
        clipboardEnabledPref.unregisterOnChangeListener(clipboardEnabledListener)
        adapter.onDetached()
        adapterSubmitJob?.cancel()
        undoTimeoutJob?.cancel()
    }

    @Suppress("unused")
    private val clipboardEnabledListener = PreferenceDelegate.OnChangeListener<Boolean> { _, it ->
        stateMachine.updateListening(it)
    }

    override val title: String
        get() = context.getString(R.string.clipboard)

    private lateinit var toolbarAnimator: ViewAnimator

    override fun onCreateBarView(): View {
        val normalBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        normalBar.addView(barUsageText)
        normalBar.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        normalBar.addView(barFilterButton)
        normalBar.addView(barUndoButton)
        normalBar.addView(barSelectButton)
        normalBar.addView(barSearchButton)
        normalBar.addView(barDeleteAllButton)

        val selectBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        selectBar.addView(barSelectedCountText)
        selectBar.addView(barCancelSelectButton)
        selectBar.addView(barDeleteSelectedButton)

        return ViewAnimator(context).apply {
            addView(normalBar)
            addView(selectBar)
            toolbarAnimator = this
        }
    }
}

/** 旧入口（剪贴板窗口带分类打开）映射到新的内容页签。 */
private fun ClipboardCategory?.toInitialTab(): ClipboardTab = when (this) {
    ClipboardCategory.Media -> ClipboardTab.Image
    else -> ClipboardTab.All
}

private fun ClipboardCategory?.toInitialSource(): ClipboardSource = when (this) {
    ClipboardCategory.Local -> ClipboardSource.Local
    ClipboardCategory.Remote -> ClipboardSource.Remote
    else -> ClipboardSource.All
}
