/*
 * SPDX-FileCopyrightText: 2026 BambooIME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.annotation.StringRes
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.update.UpdateCenter
import com.osfans.trime.update.UpdateManifest
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 设置 → 更新。
 *
 * 只做四件事：查清单、下载 APK 交系统安装器、下载主题 zip 解到用户目录、记住「忽略此版本」。
 * 不做静默安装（系统不允许），也不联网上传任何东西。
 */
class UpdateFragment : PaddingPreferenceFragment() {
    private var manifest: UpdateManifest? = null
    private var busy = false

    private var checkPref: Preference? = null
    private var latestPref: Preference? = null
    private var apkPref: Preference? = null
    private var themePref: Preference? = null
    private var ignorePref: Preference? = null
    private var autoPref: SwitchPreferenceCompat? = null
    private var sourcePref: ListPreference? = null

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory("") {
                isIconSpaceReserved = false
                addPreference(R.string.update_current, UpdateCenter.currentVersionName())
                addPreference(R.string.update_check, R.string.update_check_summary) { doCheck() }
                latestPref = Preference(requireContext()).apply {
                    isIconSpaceReserved = false
                    title = getString(R.string.update_latest)
                    summary = getString(R.string.update_not_checked)
                }
                addPreference(latestPref!!)
            }
            addCategory("") {
                isIconSpaceReserved = false
                apkPref = actionPreference(
                    R.string.update_download_apk,
                    R.string.update_download_apk_summary,
                ) { doInstallApk() }
                themePref = actionPreference(
                    R.string.update_download_theme,
                    R.string.update_download_theme_summary,
                ) { doInstallThemes() }
                ignorePref = actionPreference(
                    R.string.update_ignore,
                    R.string.update_ignore_summary,
                ) { doIgnore() }
                addPreference(apkPref!!)
                addPreference(themePref!!)
                addPreference(ignorePref!!)
            }
            addCategory("") {
                isIconSpaceReserved = false
                autoPref = SwitchPreferenceCompat(requireContext()).apply {
                    isIconSpaceReserved = false
                    key = KEY_AUTO
                    title = getString(R.string.update_auto_check)
                    summary = getString(R.string.update_auto_check_summary)
                    isChecked = UpdateCenter.isAutoCheck(requireContext())
                    setOnPreferenceChangeListener { _, value ->
                        UpdateCenter.setAutoCheck(requireContext(), value as Boolean)
                        true
                    }
                }
                addPreference(autoPref!!)
                sourcePref = ListPreference(requireContext()).apply {
                    isIconSpaceReserved = false
                    key = KEY_SOURCE
                    title = getString(R.string.update_source)
                    entries = arrayOf(
                        getString(R.string.update_source_auto),
                        getString(R.string.update_source_cnb),
                        getString(R.string.update_source_github),
                    )
                    entryValues = arrayOf("0", "1", "2")
                    val current = UpdateCenter.source(requireContext()).toString()
                    value = current
                    summary = when (UpdateCenter.source(requireContext())) {
                        UpdateCenter.SOURCE_CNB -> getString(R.string.update_source_cnb)
                        UpdateCenter.SOURCE_GITHUB -> getString(R.string.update_source_github)
                        else -> getString(R.string.update_source_auto)
                    }
                    setOnPreferenceChangeListener { _, value ->
                        val v = (value as String).toIntOrNull() ?: 0
                        UpdateCenter.setSource(requireContext(), v)
                        summary = when (v) {
                            UpdateCenter.SOURCE_CNB -> getString(R.string.update_source_cnb)
                            UpdateCenter.SOURCE_GITHUB -> getString(R.string.update_source_github)
                            else -> getString(R.string.update_source_auto)
                        }
                        true
                    }
                }
                addPreference(sourcePref!!)
            }
            addCategory("") {
                isIconSpaceReserved = false
                checkPref = null
            }
        }
        refreshActions()
    }

    private fun setLatest(summary: String) {
        latestPref?.summary = summary
    }

    private fun refreshActions() {
        val m = manifest
        val has = m != null && UpdateCenter.hasUpdate(requireContext(), m)
        apkPref?.isVisible = has && m?.apk != null
        themePref?.isVisible = has && m?.themes != null
        ignorePref?.isVisible = has
    }

    private fun actionPreference(
        @StringRes titleRes: Int,
        @StringRes summaryRes: Int,
        action: () -> Unit,
    ): Preference = Preference(requireContext()).apply {
        isIconSpaceReserved = false
        title = getString(titleRes)
        summary = getString(summaryRes)
        setOnPreferenceClickListener {
            action()
            true
        }
    }

    private fun doCheck() {
        if (busy) return
        busy = true
        setLatest(getString(R.string.update_checking))
        lifecycleScope.launch {
            val result = UpdateCenter.fetchManifest(requireContext())
            busy = false
            result.onSuccess { m ->
                manifest = m
                val has = UpdateCenter.hasUpdate(requireContext(), m)
                val head = if (has) {
                    getString(R.string.update_found, m.versionName.ifBlank { m.versionCode.toString() })
                } else {
                    getString(R.string.update_up_to_date)
                }
                val notes = m.notes.trim()
                setLatest(if (notes.isEmpty()) head else "$head\n$notes")
                refreshActions()
            }.onFailure { t ->
                Timber.w(t, "Update: check failed")
                setLatest(getString(R.string.update_check_failed, t.message ?: t.javaClass.simpleName))
                refreshActions()
            }
        }
    }

    private fun ensureCanInstall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (requireContext().packageManager.canRequestPackageInstalls()) return true
        requireContext().toast(R.string.update_need_permission)
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:${requireContext().packageName}"),
                ),
            )
        }
        return false
    }

    private fun doInstallApk() {
        val m = manifest ?: return
        val asset = m.apk ?: return
        if (busy) return
        if (!ensureCanInstall()) return
        busy = true
        lifecycleScope.launch {
            val result = runCatching {
                UpdateCenter.download(requireContext(), asset, UpdateCenter.apkFileName(m)) { percent ->
                    apkPref?.summary = getString(R.string.update_downloading, percent)
                }
            }
            busy = false
            result.onSuccess { file ->
                apkPref?.summary = getString(R.string.update_download_apk_summary)
                runCatching { UpdateCenter.installApk(requireContext(), file) }
                    .onFailure { t ->
                        Timber.w(t, "Update: launch installer failed")
                        requireContext().toast(getString(R.string.update_install_failed, t.message ?: ""))
                    }
            }.onFailure { t ->
                Timber.w(t, "Update: apk download failed")
                apkPref?.summary = getString(R.string.update_download_apk_summary)
                requireContext().toast(getString(R.string.update_install_failed, t.message ?: ""))
            }
        }
    }

    private fun doInstallThemes() {
        val m = manifest ?: return
        val asset = m.themes ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val result = runCatching {
                val zip = UpdateCenter.download(requireContext(), asset, UpdateCenter.themeZipName(m)) { percent ->
                    themePref?.summary = getString(R.string.update_downloading, percent)
                }
                UpdateCenter.installThemes(requireContext(), zip) { percent ->
                    themePref?.summary = getString(R.string.update_downloading, percent)
                }
            }
            busy = false
            result.onSuccess { count ->
                themePref?.summary = getString(R.string.update_download_theme_summary)
                requireContext().toast(getString(R.string.update_theme_done, count))
            }.onFailure { t ->
                Timber.w(t, "Update: themes failed")
                themePref?.summary = getString(R.string.update_download_theme_summary)
                requireContext().toast(getString(R.string.update_install_failed, t.message ?: ""))
            }
        }
    }

    private fun doIgnore() {
        val m = manifest ?: return
        val code = if (m.versionCode > 0) m.versionCode else UpdateCenter.currentVersionCode() + 1
        UpdateCenter.ignore(requireContext(), code)
        requireContext().toast(R.string.update_ignored)
        refreshActions()
    }

    companion object {
        private const val KEY_AUTO = "bamboo_update_auto_check"
        private const val KEY_SOURCE = "bamboo_update_source"
    }
}
