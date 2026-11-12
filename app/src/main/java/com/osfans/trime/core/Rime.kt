/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import com.osfans.trime.BuildConfig
import com.osfans.trime.actions.glossary.GlossaryDecorator
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.opencc.OpenCCDictManager
import com.osfans.trime.data.packaging.PackageStateManager
import com.osfans.trime.data.packaging.SchemaPackageManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InlinePreeditMode
import com.osfans.trime.util.appContext
import com.osfans.trime.util.isStorageAvailable
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Rime JNI and instance methods
 *
 * @see [librime](https://github.com/rime/librime)
 */
class Rime :
    RimeApi,
    RimeLifecycleOwner {
    private val lifecycleRegistry = RimeLifecycleRegistry()
    override val lifecycle get() = lifecycleRegistry

    override val messageFlow = messageFlow_.asSharedFlow()

    override val isReady: Boolean
        get() = lifecycle.currentState == RimeLifecycle.State.READY

    override var schemaCached = RimeSchema(".default")
        private set

    override var statusCached = StatusProto()
        private set

    override var compositionCached = CompositionProto()
        private set

    override var hasMenu: Boolean = false
        private set

    override var paging: Boolean = false
        private set

    private val dispatcher =
        RimeDispatcher(
            object : RimeDispatcher.RimeController {
                override fun nativeStartup() {
                    startRime(false)
                    lifecycleRegistry.emitState(RimeLifecycle.State.READY)
                }

                override fun nativeFinalize() {
                    exitRime()
                }
            },
        )

    private val inlinePreeditMode by AppPrefs.defaultInstance().general.inlinePreeditMode
    private val showAsciiSwitchTips by AppPrefs.defaultInstance().general.asciiSwitchTips

    private var asciiSwitchTipsJob: Job? = null
    private var isNullInputType = true
    private var lastAsciiTipsText = ""
    private var pagingMode = false

    init {
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            throw IllegalStateException("Rime has already been created!")
        }
    }

    private suspend inline fun <T> withRimeContext(crossinline block: suspend () -> T): T = withContext(dispatcher) {
        block()
    }

    override suspend fun isEmpty(): Boolean = withRimeContext {
        getCurrentRimeSchema() == ".default" // 無方案
    }

    override suspend fun deploy() = withRimeContext {
        try {
            exitRime()
            startRime(true)
        } catch (e: Exception) {
            Timber.w(e, "deploy failed")
        }
    }

    override suspend fun switchPackage(packageId: String): Boolean = withRimeContext {
        try {
            val oldId = SchemaPackageManager.activePackageId
            if (oldId == packageId) return@withRimeContext false

            if (oldId.isNotEmpty()) {
                PackageStateManager.updateSchemaAndTheme(
                    oldId,
                    lastSchemaId = getCurrentRimeSchema(),
                    lastThemeId = ThemeManager.prefs.selectedTheme.getValue(),
                )
            }

            SchemaPackageManager.setActivePackageId(packageId)
            exitRime()
            startRime()

            val state = PackageStateManager.getState(packageId)
            if (state.lastSchemaId.isNotEmpty()) {
                selectRimeSchema(state.lastSchemaId)
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "switchPackage failed")
            false
        }
    }

    override suspend fun updateConfig() = withRimeContext {
        try {
            exitRime()
            startRime(false)
        } catch (e: Exception) {
            Timber.w(e, "updateConfig failed")
        }
    }

    override suspend fun syncUserData(): Boolean = withRimeContext {
        try {
            syncRimeUserData()
        } catch (e: Exception) {
            Timber.w(e, "syncUserData failed")
            false
        }
    }

    override suspend fun processKey(
        value: Int,
        modifiers: UInt,
        isVirtual: Boolean,
    ): Boolean = withRimeContext {
        processKeyInner(value, modifiers.toInt(), isVirtual)
    }

    override suspend fun processKey(
        value: KeyValue,
        modifiers: KeyModifiers,
        isVirtual: Boolean,
    ): Boolean = withRimeContext {
        processKeyInner(value.value, modifiers.toInt(), isVirtual)
    }

    override suspend fun simulateKeySequence(sequence: String): Boolean = withRimeContext {
        Timber.d("simulateKeySequence: $sequence")
        if (simulateRimeKeySequence(sequence)) {
            val commit = getRimeCommit()
            val input = getRimeRawInput()
            if (!commit.text.isNullOrEmpty() || input.isNotEmpty()) {
                emitResponse { commit }
                true
            } else {
                emitResponse { CommitProto(sequence) }
                false
            }
        } else {
            false
        }.also { Timber.d("simulateKeySequence ${if (it) "success" else "failed"}") }
    }

    override suspend fun selectCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        selectRimeCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun highlightCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        highlightRimeCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun deleteCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        deleteRimeCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun changeCandidatePage(backward: Boolean): Boolean = withRimeContext {
        changeRimeCandidatePage(backward).also { emitResponse() }
    }

    override suspend fun moveCursorPos(position: Int) = withRimeContext {
        setRimeCaretPos(position)
        emitResponse()
    }

    override suspend fun getCaretPos(): Int = withRimeContext { getRimeCaretPos() }

    override suspend fun availableSchemata(): Array<SchemaItem> = withRimeContext { getAvailableRimeSchemaList() }

    override suspend fun enabledSchemata(): Array<SchemaItem> = withRimeContext { getSelectedRimeSchemaList() }

    override suspend fun setEnabledSchemata(schemaIds: Array<String>) = withRimeContext { selectRimeSchemas(schemaIds) }

    override suspend fun selectedSchemata(): Array<SchemaItem> = withRimeContext { getRimeSchemaList() }

    override suspend fun selectedSchemaId(): String = withRimeContext { getCurrentRimeSchema() }

    override suspend fun selectSchema(schemaId: String) = withRimeContext { selectRimeSchema(schemaId) }

    override suspend fun selectSchemaCrossPackage(schemaId: String): Boolean = withRimeContext {
        try {
            val pkgId = SchemaPackageManager.findPackageForSchema(schemaId) ?: return@withRimeContext false
            val currentPkg = SchemaPackageManager.activePackageId

            if (pkgId == currentPkg) {
                selectRimeSchema(schemaId)
            } else {
                if (currentPkg.isNotEmpty()) {
                    PackageStateManager.updateSchemaAndTheme(
                        currentPkg,
                        lastSchemaId = getCurrentRimeSchema(),
                        lastThemeId = ThemeManager.prefs.selectedTheme.getValue(),
                    )
                }

                SchemaPackageManager.setActivePackageId(pkgId)
                exitRime()
                startRime()

                selectRimeSchema(schemaId)
            }

            PackageStateManager.updateSchemaAndTheme(
                pkgId,
                lastSchemaId = schemaId,
                lastThemeId = ThemeManager.prefs.selectedTheme.getValue(),
            )
            true
        } catch (e: Exception) {
            Timber.w(e, "selectSchemaCrossPackage failed")
            false
        }
    }

    override suspend fun currentSchema(): RimeSchema = withRimeContext {
        RimeSchema(getCurrentRimeSchema())
    }

    override suspend fun commitComposition(appendText: String?): Boolean = withRimeContext {
        val committed = commitRimeComposition()
        if (committed || !appendText.isNullOrEmpty()) {
            val commit = getRimeCommit()
            val text = buildString {
                append(commit.text.orEmpty())
                append(appendText.orEmpty())
            }
            emitResponse { CommitProto(text) }
        }
        committed
    }

    override suspend fun clearComposition() = withRimeContext {
        clearRimeComposition()
        runCatching { com.osfans.trime.actions.wenquxing.WenquxingTips.clear() }
        emitResponse()
    }

    override suspend fun getRawInput(): String = withRimeContext {
        getRimeRawInput()
    }

    /**
     * 重新发一次组合/候选消息。
     *
     * 外部（如文曲星）改写了编码后面要显示的内容、但引擎状态没变时，用它把界面刷一遍，
     * 否则要等下一次按键才会重绘。
     */
    suspend fun refreshComposition() = withRimeContext {
        emitResponse()
    }

    override suspend fun setRawInput(input: String): Boolean = withRimeContext {
        setRimeRawInput(input).also {
            if (it) emitResponse()
        }
    }

    override suspend fun setRuntimeOption(
        option: String,
        value: Boolean,
    ): Unit = withRimeContext {
        setRimeOption(option, value)
    }

    override suspend fun getRuntimeOption(option: String): Boolean = withRimeContext {
        getRimeOption(option)
    }

    override suspend fun setNullInputType(value: Boolean) = withRimeContext {
        isNullInputType = value
    }

    override suspend fun getCandidates(
        startIndex: Int,
        limit: Int,
    ): Array<CandidateProto> = withRimeContext {
        getRimeCandidates(startIndex, limit)
    }

    override suspend fun setCandidatePagingMode(enabled: Boolean) = withRimeContext {
        pagingMode = enabled
    }

    private fun startRime(fullCheck: Boolean? = null) {
        DataManager.sync()
        // Must run before `stagingDir`/`userDataDir` are resolved below: an empty
        // active package would otherwise point the whole session at the shared dir.
        SchemaPackageManager.ensureActivePackage()
        val needsDeploy = fullCheck ?: run {
            val staging: File = DataManager.stagingDir
            !staging.exists() || (staging.list()?.isEmpty() ?: true)
        }
        // glog only writes files when the directory already exists；正式包不开文件日志，
        // 目录也不建，避免在用户目录里留空文件夹。
        if (BuildConfig.BAMBOO_LOG_TO_FILE) {
            File(DataManager.userDataDir, "logs").mkdirs()
        }
        val sharedDataDir = DataManager.sharedDataDir.absolutePath
        val userDataDir = DataManager.userDataDir.absolutePath
        Timber.d(
            """
            Starting rime with:
            sharedDataDir: $sharedDataDir
            userDataDir: $userDataDir
            fullCheck: $needsDeploy
            """.trimIndent(),
        )
        // 发行版版本号：调试包固定成 "dev"。librime 只在它变化时才重建
        // installation.yaml 并全量部署（deployment_tasks.cc 的 InstallationUpdate），
        // 而调试包的版本号跟着 buildVersionName 走的话，每次升版本都白部署一次。
        // 数据文件（schema/词典/主题）真的变了仍会由 DataManager.sync() 触发部署。
        val distroVersion = if (BuildConfig.DEBUG) "dev" else BuildConfig.BUILD_VERSION_NAME
        startupRime(
            sharedDataDir,
            userDataDir,
            distroVersion,
            needsDeploy,
            // glog 文件日志只在调试包里开；正式包只写 logcat，避免用户目录被日志撑爆。
            BuildConfig.DEBUG,
        )
    }

    private fun processKeyInner(value: Int, modifiers: Int, isVirtual: Boolean): Boolean {
        lastAsciiTipsText = asciiTipsText
        val handled = processRimeKey(value, modifiers)
        emitResponseFast()
        if (!handled) {
            handleRimeMessage(
                10, // RimeMessage.MessageType.Key,
                arrayOf(value, modifiers, isVirtual),
            )
        }
        return handled
    }

    private fun emitResponseFast() {
        val response = getRimeResponse()
        val commit = response[0] as CommitProto
        handleRimeMessage(4, arrayOf(commit))
        val context = response[1] as ContextProto
        handlePreedit(context.composition)
        val status = response[5] as StatusProto
        val newTips = computeAsciiTips(status)
        if (context.composition.length <= 0 && lastAsciiTipsText != newTips) {
            asciiTipsTextCached = newTips
            showAsciiSwitchTips()
        } else {
            asciiTipsTextCached = newTips
        }
        if (pagingMode) {
            handleRimeMessage(7, arrayOf(context.menu))
        } else {
            val bulk = arrayOf(response[2], response[3], response[4])
            handleRimeMessage(9, bulk)
        }
        handleRimeMessage(8, arrayOf(status))
    }

    private fun computeAsciiTips(status: StatusProto): String = if (status.isAsciiMode) {
        "En"
    } else if (status.schemaName.isNotEmpty() &&
        !status.schemaName.startsWith('.')
    ) {
        status.schemaName.take(2)
    } else {
        ""
    }

    private fun emitResponse(
        commit: (() -> CommitProto) = { getRimeCommit() },
    ) {
        handleRimeMessage(4, arrayOf(commit.invoke()))
        val context = getRimeContext()
        handlePreedit(context.composition)
        if (context.composition.length <= 0 && lastAsciiTipsText != asciiTipsText) {
            showAsciiSwitchTips()
        }
        if (pagingMode) {
            handleRimeMessage(7, arrayOf(context.menu))
        } else {
            val bulk = getRimeBulkCandidates()
            handleRimeMessage(9, bulk)
        }
        handleRimeMessage(8, arrayOf(getRimeStatus()))
    }

    private val asciiTipsText: String
        get() = asciiTipsTextCached

    private var asciiTipsTextCached: String = ""

    private fun handlePreedit(composition: CompositionProto) {
        runCatching {
            if (composition.length == 0) {
                com.osfans.trime.actions.wenquxing.WenquxingTips.onCompositionCleared()
            }
        }
        val mode = if (isNullInputType) {
            InlinePreeditMode.DISABLE
        } else {
            inlinePreeditMode
        }
        val inlinePreedit = when (mode) {
            InlinePreeditMode.DISABLE -> ""
            InlinePreeditMode.COMPOSING_TEXT -> composition.preedit ?: ""
            InlinePreeditMode.COMMIT_TEXT_PREVIEW -> composition.commitTextPreview ?: ""
        }
        val compositionCursorPos = composition.cursorPos
        val compositionMsg = if (mode == InlinePreeditMode.COMPOSING_TEXT) {
            CompositionProto()
        } else {
            composition
        }
        handleRimeMessage(5, arrayOf(inlinePreedit, compositionCursorPos))
        handleRimeMessage(6, arrayOf(compositionMsg))
    }

    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                statusCached = getRimeStatus()
                schemaCached = RimeSchema(it.data.id)
            }

            is RimeMessage.OptionMessage -> {
                // Option change won't trigger response update
                val status = getRimeStatus()
                statusCached = status
                updateSchemaCached(status)
                if (it.data.option == "ascii_mode") {
                    asciiTipsTextCached = computeAsciiTips(status)
                    showAsciiSwitchTips()
                }
            }

            is RimeMessage.DeployMessage -> {
                if (it.data == RimeMessage.DeployMessage.State.Start) {
                    OpenCCDictManager.buildOpenCCDict()
                }
            }

            is RimeMessage.CompositionMessage -> {
                val composition = it.data
                compositionCached = composition
            }

            is RimeMessage.CandidateMenuMessage -> {
                val menu = it.data
                paging = menu.pageNumber != 0
                hasMenu = menu.candidates.isNotEmpty()
            }

            is RimeMessage.CandidateListMessage -> {
                hasMenu = it.data.candidates.isNotEmpty()
            }

            is RimeMessage.StatusMessage -> {
                statusCached = it.data
                updateSchemaCached(it.data)
            }

            else -> {}
        }
    }

    private fun updateSchemaCached(status: StatusProto) {
        val (schemaId, schemaName) = status
        // Engine response update won't send SchemaMessage, but usually update RimeStatus
        if (schemaId != schemaCached.schemaId) {
            schemaCached = RimeSchema(schemaId)
            // notify downstream consumers that schema has changed
            messageFlow_.tryEmit(
                RimeMessage.SchemaMessage(
                    SchemaItem(schemaId, schemaName),
                ),
            )
        }
    }

    private fun showAsciiSwitchTips() {
        if (!showAsciiSwitchTips) return
        val tipsText = asciiTipsText
        if (tipsText.isEmpty()) return

        lastAsciiTipsText = tipsText

        val tips = CompositionProto(tipsText)
        messageFlow_.tryEmit(RimeMessage.CompositionMessage(tips))
        compositionCached = tips
        asciiSwitchTipsJob?.cancel()
        asciiSwitchTipsJob = lifecycleScope.launch {
            delay(1000L)
            val ctx = getRimeContext()
            handleRimeMessage(6, arrayOf(ctx.composition))
        }
    }

    fun startup() {
        if (!appContext.isStorageAvailable()) {
            Timber.w("Skip starting rime: storage not available!")
            return
        }
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            Timber.w("Skip starting rime: not at stopped state!")
            return
        }
        registerRimeMessageHandler(::handleRimeMessage)
        lifecycleRegistry.emitState(RimeLifecycle.State.STARTING)
        dispatcher.start()
    }

    fun finalize() {
        if (lifecycle.currentState != RimeLifecycle.State.READY) {
            Timber.w("Skip stopping rime: not at ready state!")
            return
        }
        lifecycleRegistry.emitState(RimeLifecycle.State.STOPPING)
        Timber.i("Rime finalize()")
        dispatcher.stop().let {
            if (it.isNotEmpty()) {
                Timber.w("${it.size} job(s) didn't get a chance to run!")
            }
        }
        lifecycleRegistry.emitState(RimeLifecycle.State.STOPPED)
        unregisterRimeMessageHandler(::handleRimeMessage)
    }

    companion object {
        private val messageFlow_ =
            MutableSharedFlow<RimeMessage<*>>(
                extraBufferCapacity = 15,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        private val rimeMessageHandlers = ArrayList<(RimeMessage<*>) -> Unit>()

        init {
            System.loadLibrary("rime_jni")
        }

        // init
        @JvmStatic
        external fun startupRime(
            sharedDir: String,
            userDir: String,
            versionName: String,
            fullCheck: Boolean,
            logToFile: Boolean,
        )

        @JvmStatic
        external fun exitRime()

        @JvmStatic
        external fun deployRimeSchemaFile(schemaFile: String): Boolean

        @JvmStatic
        external fun deployRimeConfigFile(
            fileName: String,
            versionKey: String,
        ): Boolean

        @JvmStatic
        external fun syncRimeUserData(): Boolean

        // input
        @JvmStatic
        external fun processRimeKey(
            keycode: Int,
            mask: Int,
        ): Boolean

        @JvmStatic
        external fun commitRimeComposition(): Boolean

        @JvmStatic
        external fun clearRimeComposition()

        // output
        @JvmStatic
        external fun getRimeCommit(): CommitProto

        @JvmStatic
        external fun getRimeContext(): ContextProto

        @JvmStatic
        external fun getRimeStatus(): StatusProto

        // runtime options
        @JvmStatic
        external fun setRimeOption(
            option: String,
            value: Boolean,
        )

        @JvmStatic
        external fun getRimeOption(option: String): Boolean

        @JvmStatic
        external fun getRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getCurrentRimeSchema(): String

        @JvmStatic
        external fun selectRimeSchema(schemaId: String): Boolean

        // testing
        @JvmStatic
        external fun simulateRimeKeySequence(keySequence: String): Boolean

        @JvmStatic
        external fun getRimeRawInput(): String

        @JvmStatic
        external fun setRimeRawInput(input: String): Boolean

        @JvmStatic
        external fun getRimeCaretPos(): Int

        @JvmStatic
        external fun setRimeCaretPos(caretPos: Int)

        @JvmStatic
        external fun selectRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun highlightRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun deleteRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun changeRimeCandidatePage(backward: Boolean): Boolean

        @JvmStatic
        external fun getAvailableRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getSelectedRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun selectRimeSchemas(schemaIds: Array<String>): Boolean

        @JvmStatic
        external fun getRimeCandidates(
            startIndex: Int,
            limit: Int,
        ): Array<CandidateProto>

        @JvmStatic
        external fun getRimeBulkCandidates(): Array<Any>

        @JvmStatic
        external fun getRimeResponse(): Array<Any>

        @JvmStatic
        fun handleRimeMessage(
            type: Int,
            params: Array<Any>,
        ) {
            val message = RimeMessage.nativeCreate(type, params)
            // 候选英文：在消息进 UI 之前把释义填进 comment（没有命中时原样返回同一对象）。
            // 外面再兜一层：词库模块就算类初始化失败，也不能让整条消息通道炸掉。
            val decorated =
                runCatching { GlossaryDecorator.decorate(message) }
                    .getOrElse {
                        Timber.e(it, "glossary: decorate call failed")
                        message
                    }
            Timber.d("Handling $decorated")
            rimeMessageHandlers.forEach { it.invoke(decorated) }
            messageFlow_.tryEmit(decorated)
        }

        @JvmStatic
        fun registerRimeMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            if (rimeMessageHandlers.contains(handler)) return
            rimeMessageHandlers.add(handler)
        }

        @JvmStatic
        fun unregisterRimeMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            rimeMessageHandlers.remove(handler)
        }
    }
}
