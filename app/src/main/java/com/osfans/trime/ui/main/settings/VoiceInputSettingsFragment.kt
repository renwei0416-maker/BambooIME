/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.infer.SherpaModelPack
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.prefs.PreferenceDelegateProvider
import com.osfans.trime.infer.WxVoicePack
import com.osfans.trime.link.SherpaSpeechClient
import com.osfans.trime.link.VoiceBackend
import com.osfans.trime.link.VoiceBackendSelector
import com.osfans.trime.link.VoiceModelManager
import com.osfans.trime.ui.common.buildDialog
import com.osfans.trime.ui.common.confirmDialog
import com.osfans.trime.ui.common.progressDialog
import com.osfans.trime.util.InputMethodUtils
import com.osfans.trime.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

class VoiceInputSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().voiceInput) {

    private val voiceInputPrefs = AppPrefs.defaultInstance().voiceInput
    private var downloadPref: Preference? = null
    private var importPref: Preference? = null
    private var deletePref: Preference? = null
    private var downloadJob: Job? = null
    private var wxDownloadPref: Preference? = null
    private val mmPrefs = LinkedHashMap<String, Preference>()
    private val mmBusy = mutableSetOf<String>()
    private var wxImportPref: Preference? = null
    private var wxDeletePref: Preference? = null
    private var wxJob: Job? = null
    private var pendingImportForWx = false
    private var pendingImportSpecId: String? = null

    /** onPreferenceUiCreated 拿到的 screen：那时 preferenceScreen 尚未赋值，findPreference 会返回 null。 */
    private var cachedScreen: PreferenceScreen? = null

    private val importMimeTypes = arrayOf(
        "application/zip",
        "application/x-bzip2",
        "application/x-tar",
        "application/octet-stream",
        "*/*",
    )

    private val selectFileLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri ?: return@registerForActivityResult
            val forWx = pendingImportForWx
            val specId = pendingImportSpecId
            pendingImportForWx = false
            pendingImportSpecId = null
            when {
                forWx -> handleSelectedWxFile(uri)
                specId != null -> handleSelectedModelPack(uri, specId)
                else -> handleSelectedFile(uri)
            }
        }

    private val onVoiceInputChangeListener = PreferenceDelegateProvider.OnChangeListener { key ->
        if (key == AppPrefs.VoiceInput.PREFERRED_VOICE_INPUT ||
            key == AppPrefs.VoiceInput.ASRKB_AIDL_VOICE_INPUT
        ) {
            updateLocalVoiceEnabled()
        }
        if (key == AppPrefs.VoiceInput.VOICE_ENGINE) {
            applyEngineVisibility()
        }
    }

    private fun updateLocalVoiceEnabled() {
        val isBuiltin = voiceInputPrefs.preferredVoiceInput.getValue() == InputMethodUtils.BUILTIN_VOICE_INPUT
        val isAidlEnabled = voiceInputPrefs.asrkbAidlVoiceInputEnabled.getValue()
        val enabled = isBuiltin && !isAidlEnabled
        downloadPref?.isEnabled = enabled
        importPref?.isEnabled = enabled
        refreshDeletePrefState()
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        voiceInputPrefs.registerOnChangeListener(onVoiceInputChangeListener)
    }

    override fun onDestroy() {
        voiceInputPrefs.unregisterOnChangeListener(onVoiceInputChangeListener)
        cachedScreen = null
        super.onDestroy()
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        cachedScreen = screen
        downloadPref =
            Preference(requireContext()).apply {
                key = "voice_model_download"
                isIconSpaceReserved = false
                title = getString(R.string.voice_model_download_action)
                setOnPreferenceClickListener {
                    maybeConfirmThenDownload()
                    true
                }
            }.also { screen.addPreference(it) }

        importPref =
            Preference(requireContext()).apply {
                key = "voice_model_import"
                isIconSpaceReserved = false
                title = getString(R.string.voice_model_import_action)
                setOnPreferenceClickListener {
                    maybeConfirmThenImport()
                    true
                }
            }.also { screen.addPreference(it) }

        deletePref =
            Preference(requireContext()).apply {
                key = "voice_model_delete"
                isIconSpaceReserved = false
                title = getString(R.string.voice_model_delete_action)
                setOnPreferenceClickListener {
                    maybeConfirmThenDelete()
                    true
                }
            }.also { screen.addPreference(it) }

        wxDownloadPref =
            Preference(requireContext()).apply {
                key = "wx_pack_download"
                isIconSpaceReserved = false
                title = getString(R.string.wx_pack_download)
                setOnPreferenceClickListener {
                    maybeConfirmThenWxDownload()
                    true
                }
            }.also { screen.addPreference(it) }

        wxImportPref =
            Preference(requireContext()).apply {
                key = "wx_pack_import"
                isIconSpaceReserved = false
                title = getString(R.string.wx_pack_import)
                setOnPreferenceClickListener {
                    pendingImportForWx = true
                    selectFileLauncher.launch(importMimeTypes)
                    true
                }
            }.also { screen.addPreference(it) }

        wxDeletePref =
            Preference(requireContext()).apply {
                key = "wx_pack_delete"
                isIconSpaceReserved = false
                title = getString(R.string.wx_pack_delete)
                setOnPreferenceClickListener {
                    requireContext().confirmDialog(
                        title = R.string.wx_pack_delete_title,
                        message = R.string.wx_pack_delete_message,
                        onConfirm = { doDeleteWxPack() },
                    )
                    true
                }
            }.also { screen.addPreference(it) }

        listOf(
            SherpaModelPack.PARAFORMER to "mm_pack_paraformer",
            SherpaModelPack.SENSE_VOICE to "mm_pack_sensevoice",
            SherpaModelPack.X_ASR to "mm_pack_xasr",
            SherpaModelPack.PUNCT_ZH_EN to "mm_pack_punct",
        ).forEach { (spec, key) ->
            val item =
                Preference(requireContext()).apply {
                    this.key = key
                    isIconSpaceReserved = false
                    title = spec.label
                    setOnPreferenceClickListener {
                        onModelPackClicked(spec)
                        true
                    }
                }.also { screen.addPreference(it) }
            mmPrefs[spec.id] = item
        }

        downloadPref?.title = getString(R.string.voice_model_download_sherpa)
        refreshModelUi()
        updateLocalVoiceEnabled()
        refreshWxUi()
        updateWxEnabled()
        refreshMmUi()
        applyEngineVisibility()
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppPrefs.VoiceInput.VOICE_CHUNK_SIZE) {
            refreshModelUi()
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences
            ?.registerOnSharedPreferenceChangeListener(prefsListener)
        refreshModelUi()
        applyEngineVisibility()
    }

    override fun onPause() {
        super.onPause()
        preferenceManager.sharedPreferences
            ?.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun refreshModelUi() {
        val installed = VoiceModelManager.checkModelFiles()
        val installedSummary = installedModelSummary()

        downloadPref?.title = getString(
            if (installed) {
                R.string.voice_model_download_action_reinstall
            } else {
                R.string.voice_model_download_action
            },
        )
        downloadPref?.summary = if (installed) installedSummary else getString(R.string.voice_model_status_not_installed)

        importPref?.title = getString(R.string.voice_model_import_action)
        importPref?.summary = if (installed) installedSummary else getString(R.string.voice_model_status_not_installed)

        refreshDeletePrefState()
    }

    private fun installedModelSummary(): String {
        val backend = getString(
            when (VoiceModelManager.resolveActiveModelFiles()?.backend ?: VoiceBackendSelector.preferred()) {
                VoiceBackend.QNN -> R.string.voice_backend_qnn
                VoiceBackend.CPU -> R.string.voice_backend_cpu
            },
        )
        return "${getString(R.string.voice_model_status_installed)} · $backend"
    }

    private fun refreshDeletePrefState() {
        val installed = VoiceModelManager.checkModelFiles()
        val builtinVoice =
            voiceInputPrefs.preferredVoiceInput.getValue() == InputMethodUtils.BUILTIN_VOICE_INPUT &&
                !voiceInputPrefs.asrkbAidlVoiceInputEnabled.getValue()
        deletePref?.isEnabled = builtinVoice && installed
        deletePref?.summary = if (installed) installedModelSummary() else getString(R.string.voice_model_status_not_installed)
    }

    private fun maybeConfirmThenDelete() {
        requireContext().confirmDialog(
            title = R.string.voice_model_delete_title,
            message = R.string.voice_model_delete_message,
            onConfirm = { doDeleteModel() },
        )
    }

    private fun doDeleteModel() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                SherpaSpeechClient.resetEngine()
                VoiceModelManager.deleteModel()
            }
            requireContext().toast(R.string.voice_model_deleted)
            refreshModelUi()
        }
    }

    private fun maybeConfirmThenDownload() {
        val fileName = VoiceModelManager.getDownloadFileName()
        if (VoiceModelManager.checkModelFiles()) {
            requireContext().confirmDialog(
                title = R.string.voice_model_exists_title,
                message = getString(R.string.voice_model_exists_re_download) + "\n\n" + fileName,
                onConfirm = { startDownload() },
            )
        } else {
            requireContext().confirmDialog(
                title = R.string.voice_model_download_action,
                message = fileName,
                onConfirm = { startDownload() },
            )
        }
    }

    private fun maybeConfirmThenImport() {
        if (VoiceModelManager.checkModelFiles()) {
            requireContext().confirmDialog(
                title = R.string.voice_model_exists_title,
                message = R.string.voice_model_exists_re_import,
                onConfirm = { selectFileLauncher.launch(importMimeTypes) },
            )
        } else {
            selectFileLauncher.launch(importMimeTypes)
        }
    }

    private fun startDownload() {
        val ctx = requireContext()
        val downloadUrl = VoiceModelManager.getDownloadUrl()
        val ext = VoiceModelManager.archiveExtensionFromUrl(downloadUrl)
        val destName = "voice_model_download$ext"
        val taskTitle = getString(R.string.voice_model_download_action)
        val dp = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val taskText = TextView(ctx).apply {
            textSize = 14f
            text = taskTitle
        }
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            max = 100
            isIndeterminate = true
        }
        val taskContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 16.dp(), 24.dp(), 16.dp())
            addView(taskText)
            addView(progressBar)
        }

        val dialog = ctx.buildDialog(R.string.voice_model_downloading)
            .setView(ScrollView(ctx).also { it.addView(taskContainer) })
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> downloadJob?.cancel() }
            .create()
        dialog.show()

        downloadJob = lifecycleScope.launch {
            try {
                val destFile = File(ctx.cacheDir, destName)
                if (!VoiceModelManager.hasEnoughSpace(VoiceModelManager.getRequiredFreeSpaceBytes())) {
                    throw RuntimeException(getString(R.string.voice_model_not_enough_space))
                }
                var usedUrl = downloadUrl
                withContext(Dispatchers.IO) {
                    usedUrl = VoiceModelManager.downloadWithMirror(
                        destFile = destFile,
                        onProgress = { progress, downloaded, total ->
                            launch(Dispatchers.Main) {
                                val status = formatDownloadStatus(progress, downloaded, total)
                                taskText.text = getString(
                                    R.string.custom_dl_progress_line,
                                    taskTitle,
                                    status,
                                )
                                if (progress < 0f) {
                                    progressBar.isIndeterminate = true
                                } else {
                                    progressBar.isIndeterminate = false
                                    progressBar.progress = (progress * 100).toInt().coerceIn(0, 100)
                                }
                            }
                        },
                        isCancelled = { downloadJob?.isActive != true },
                        onAttempt = { label, index, total ->
                            launch(Dispatchers.Main) {
                                taskText.text = getString(R.string.voice_download_source_line, label, index, total)
                            }
                        },
                    )
                }

                taskText.text = getString(
                    R.string.custom_dl_progress_line,
                    taskTitle,
                    getString(R.string.voice_model_extracting),
                )
                progressBar.isIndeterminate = true
                withContext(Dispatchers.IO) {
                    VoiceModelManager.installArchive(destFile, usedUrl.substringAfterLast('/'))
                    destFile.delete()
                }

                taskText.text = getString(
                    R.string.custom_dl_progress_line,
                    taskTitle,
                    getString(R.string.voice_model_initializing),
                )
                withContext(Dispatchers.IO) {
                    SherpaSpeechClient.initializeAfterInstall()
                }

                progressBar.isIndeterminate = false
                progressBar.progress = 100
                taskText.text = getString(
                    R.string.custom_dl_progress_line,
                    taskTitle,
                    getString(R.string.voice_model_installed),
                )

                val button = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                button.text = getString(android.R.string.ok)
                button.setOnClickListener { dialog.dismiss() }
            } catch (_: CancellationException) {
                withContext(Dispatchers.IO) {
                    File(ctx.cacheDir, destName).delete()
                }
                dialog.dismiss()
            } catch (e: Exception) {
                Timber.e(e, "Voice model download/install failed")
                withContext(Dispatchers.IO) {
                    File(ctx.cacheDir, destName).delete()
                }
                dialog.dismiss()
                ctx.toast(e.message ?: getString(R.string.voice_model_download_failed, ""))
            }
        }

        dialog.setOnDismissListener {
            refreshModelUi()
        }
    }

    private fun handleSelectedFile(uri: Uri) {
        val ctx = requireContext()
        val displayName = queryDisplayName(ctx, uri) ?: "voice_model_selected"
        doImportSelectedFile(ctx, uri, displayName)
    }

    private fun doImportSelectedFile(
        ctx: android.content.Context,
        uri: Uri,
        displayName: String,
    ) {
        val progressDialog = ctx.progressDialog(
            message = ctx.getString(R.string.voice_model_importing),
        ).show()

        lifecycleScope.launch {
            val tempName = "voice_selected_$displayName"
            try {
                val tempFile = File(ctx.cacheDir, tempName)
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw RuntimeException("Cannot open file")
                }

                progressDialog.text = ctx.getString(R.string.voice_model_verifying)
                val valid =
                    withContext(Dispatchers.IO) {
                        VoiceModelManager.verifySha256AnyVariant(tempFile)
                    }
                if (!valid) {
                    tempFile.delete()
                    throw SecurityException(getString(R.string.voice_model_verification_failed))
                }
                if (!VoiceModelManager.hasEnoughSpace(tempFile.length() * 2)) {
                    tempFile.delete()
                    throw RuntimeException(getString(R.string.voice_model_not_enough_space))
                }

                progressDialog.text = ctx.getString(R.string.voice_model_extracting)
                val installedBackend =
                    withContext(Dispatchers.IO) {
                        VoiceModelManager.installArchive(tempFile, displayName).also { tempFile.delete() }
                    }

                progressDialog.text = ctx.getString(R.string.voice_model_initializing)
                withContext(Dispatchers.IO) {
                    SherpaSpeechClient.initializeAfterInstall()
                }

                progressDialog.dismiss()
                if (installedBackend != VoiceBackendSelector.preferred()) {
                    ctx.toast(R.string.voice_model_wrong_backend)
                } else {
                    ctx.toast(R.string.voice_model_installed)
                }
                refreshModelUi()
            } catch (e: Exception) {
                Timber.e(e, "Voice model selection/install failed")
                progressDialog.dismiss()
                ctx.toast(e.message ?: getString(R.string.voice_model_download_failed, ""))
            }
        }
    }

    // ---------------- 其它多模型 -------------------------------------------

    private fun refreshMmUi() {
        val ctx = requireContext()
        val multi = voiceInputPrefs.voiceEngine.getValue() == AppPrefs.VoiceInput.VoiceEngine.MULTI
        SherpaModelPack.ALL.forEach { spec ->
            val pref = mmPrefs[spec.id] ?: return@forEach
            pref.isEnabled = multi
            if (mmBusy.contains(spec.id)) return@forEach
            pref.summary =
                if (SherpaModelPack.isInstalled(ctx, spec.id)) {
                    val mb = packBytes(ctx, spec.id) / 1024 / 1024
                    getString(R.string.multi_model_state_installed) + " · " + mb + " MB"
                } else {
                    val mb = spec.bytes / 1024 / 1024
                    getString(R.string.multi_model_state_missing, mb) + " · " + getString(R.string.multi_model_pack_import_hint)
                }
        }
    }

    /** 单个模型包已安装占用（字节）。 */
    private fun packBytes(context: android.content.Context, id: String): Long {
        val dir = SherpaModelPack.installedDir(context, id) ?: return 0L
        return dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    private fun onModelPackClicked(spec: SherpaModelPack.Spec) {
        if (voiceInputPrefs.voiceEngine.getValue() != AppPrefs.VoiceInput.VoiceEngine.MULTI) {
            requireContext().toast(R.string.voice_engine_multi)
            return
        }
        showModelPackActions(spec)
    }

    private fun showModelPackActions(spec: SherpaModelPack.Spec) {
        val installed = SherpaModelPack.isInstalled(requireContext(), spec.id)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions +=
            getString(
                if (installed) R.string.voice_model_download_action_reinstall else R.string.voice_model_download_action,
            ) to { downloadModelPack(spec) }
        actions +=
            getString(R.string.multi_model_action_import) to {
                pendingImportSpecId = spec.id
                selectFileLauncher.launch(importMimeTypes)
            }
        if (installed) {
            actions +=
                getString(R.string.multi_model_action_delete) to {
                    SherpaModelPack.delete(requireContext(), spec.id)
                    refreshMmUi()
                    requireContext().toast(R.string.multi_model_deleted)
                }
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(spec.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .show()
    }

    private fun downloadModelPack(spec: SherpaModelPack.Spec) {
        val ctx = requireContext()
        if (!SherpaModelPack.hasEnoughSpace(ctx, spec)) {
            ctx.toast(R.string.multi_model_no_space)
            return
        }
        val pref = mmPrefs[spec.id]
        mmBusy.add(spec.id)
        pref?.summary = getString(R.string.multi_model_pack_progress, 0)
        viewLifecycleOwner.lifecycleScope.launch {
            val appCtx = ctx.applicationContext
            var message: String? = null
            val ok =
                runCatching {
                    SherpaModelPack.downloadAndInstall(appCtx, spec) { ratio, stage ->
                        val pct = (ratio * 100).toInt().coerceIn(0, 100)
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            if (mmBusy.contains(spec.id)) {
                                mmPrefs[spec.id]?.summary =
                                    if (pct > 0) {
                                        getString(R.string.multi_model_pack_progress, pct)
                                    } else {
                                        stage
                                    }
                            }
                        }
                    }
                }.getOrElse { e ->
                    message = e.message
                    false
                }
            mmBusy.remove(spec.id)
            withContext(Dispatchers.Main) {
                refreshMmUi()
                if (ok) {
                    ctx.toast(R.string.multi_model_download_ok)
                } else {
                    val detail = message
                    ctx.toast(
                        if (detail.isNullOrBlank()) {
                            getString(R.string.multi_model_download_failed_short)
                        } else {
                            getString(R.string.multi_model_download_failed, detail)
                        },
                    )
                }
            }
        }
    }

    private fun handleSelectedModelPack(uri: android.net.Uri, specId: String) {
        val spec = SherpaModelPack.spec(specId) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val ok =
                runCatching {
                    SherpaModelPack.installFromUri(ctx, spec, uri) { _, _ -> }
                }.isSuccess
            withContext(Dispatchers.Main) {
                refreshMmUi()
                requireContext().toast(
                    if (ok) R.string.multi_model_install_ok else R.string.multi_model_download_failed_short,
                )
            }
        }
    }

    private fun refreshWxUi() {
        val ctx = requireContext()
        val installed = WxVoicePack.isInstalled(ctx)
        val status = if (installed) {
            val mb = WxVoicePack.info(ctx)?.megabytes ?: 0L
            getString(R.string.wx_pack_status_installed_fmt, mb.toString())
        } else {
            getString(R.string.wx_pack_status_missing)
        }
        val title = "${getString(R.string.wx_pack_title)}：$status"
        wxDownloadPref?.title = getString(if (installed) R.string.wx_pack_reinstall else R.string.wx_pack_download)
        wxDownloadPref?.summary = title
        wxImportPref?.summary = status
        wxDeletePref?.isEnabled = installed
        wxDeletePref?.summary = status
    }

    private fun applyEngineVisibility() {
        val engine = voiceInputPrefs.voiceEngine.getValue()
        val wechat = engine == AppPrefs.VoiceInput.VoiceEngine.WECHAT
        val multi = engine == AppPrefs.VoiceInput.VoiceEngine.MULTI
        val sherpa = !wechat && !multi
        listOf(
            AppPrefs.VoiceInput.VOICE_SENSITIVITY,
            AppPrefs.VoiceInput.VOICE_CHUNK_SIZE,
            "voice_model_download",
            "voice_model_import",
            "voice_model_delete",
        ).forEach { key -> cachedScreen?.findPreference<Preference>(key)?.isVisible = sherpa }
        listOf(
            "wx_pack_download",
            "wx_pack_import",
            "wx_pack_delete",
        ).forEach { key -> cachedScreen?.findPreference<Preference>(key)?.isVisible = wechat }
        listOf(
            AppPrefs.VoiceInput.MULTI_MODEL_ZH,
            AppPrefs.VoiceInput.MULTI_MODEL_JA,
            AppPrefs.VoiceInput.MULTI_MODEL_EN,
            AppPrefs.VoiceInput.VOICE_PUNCT_AUTO,
            AppPrefs.VoiceInput.VOICE_RECORRECT_ZH,
            AppPrefs.VoiceInput.VOICE_RECORRECT_EN,
            AppPrefs.VoiceInput.VOICE_FINALIZE_ZH,
            "mm_pack_paraformer",
        "mm_pack_sensevoice",
        "mm_pack_xasr",
        "mm_pack_punct",
        ).forEach { key -> cachedScreen?.findPreference<Preference>(key)?.isVisible = multi }
        updateWxEnabled()
        refreshMmUi()
    }

    private fun updateWxEnabled() {
        val engine = voiceInputPrefs.voiceEngine.getValue() == AppPrefs.VoiceInput.VoiceEngine.WECHAT
        wxDownloadPref?.isVisible = engine
        wxImportPref?.isVisible = engine
        wxDeletePref?.isVisible = engine
        if (engine) refreshWxUi()
    }

    private fun maybeConfirmThenWxDownload() {
        val ctx = requireContext()
        if (WxVoicePack.isInstalled(ctx)) {
            ctx.confirmDialog(
                title = R.string.wx_pack_exists_title,
                message = R.string.wx_pack_exists_re_download,
                onConfirm = { startWxDownload() },
            )
        } else {
            startWxDownload()
        }
    }

    private fun startWxDownload() {
        val ctx = requireContext()
        if (!WxVoicePack.hasEnoughSpace(ctx)) {
            ctx.toast(getString(R.string.voice_model_not_enough_space))
            return
        }
        val dialog = ctx.progressDialog(message = getString(R.string.wx_pack_downloading)).show()
        wxJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WxVoicePack.downloadAndInstall(
                        ctx,
                        onProgress = { progress, status ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                dialog.text =
                                    getString(R.string.wx_pack_downloading) + " " +
                                        status + " " + formatDownloadStatus(progress, 0L, 0L)
                            }
                        },
                    )
                }
                dialog.dismiss()
                ctx.toast(R.string.voice_model_installed)
                refreshWxUi()
            } catch (_: CancellationException) {
                dialog.dismiss()
            } catch (e: Exception) {
                Timber.e(e, "Wx speech pack download failed")
                dialog.dismiss()
                ctx.toast(e.message ?: getString(R.string.voice_model_download_failed, ""))
            }
        }
    }

    private fun handleSelectedWxFile(uri: Uri) {
        val ctx = requireContext()
        val dialog = ctx.progressDialog(message = getString(R.string.wx_pack_importing)).show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { WxVoicePack.installFromUri(ctx, uri) }
                dialog.dismiss()
                ctx.toast(R.string.voice_model_installed)
                refreshWxUi()
            } catch (_: CancellationException) {
                dialog.dismiss()
            } catch (e: Exception) {
                Timber.e(e, "Wx speech pack import failed")
                dialog.dismiss()
                ctx.toast(e.message ?: getString(R.string.voice_model_download_failed, ""))
            }
        }
    }

    private fun doDeleteWxPack() {
        val ctx = requireContext()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { WxVoicePack.delete(ctx) }
            ctx.toast(R.string.wx_pack_deleted)
            refreshWxUi()
        }
    }

    private fun queryDisplayName(ctx: android.content.Context, uri: Uri): String? = ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
        } else {
            null
        }
    }

    private fun formatDownloadStatus(progress: Float, downloaded: Long, total: Long): String {
        val pct = (progress * 100).toInt()
        return if (progress >= 0f && total > 0) {
            "$pct% — ${formatBytes(downloaded)}/${formatBytes(total)}"
        } else if (progress >= 0f) {
            "$pct%"
        } else {
            "…"
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format("%.1fM", bytes / (1024.0 * 1024.0))
    }
}
