/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.Const
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.formatDateTime

class AboutFragment : PaddingPreferenceFragment() {

    @SuppressLint("UseKtx")
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.current_version, Const.VERSION_NAME) {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("${BuildConfig.BUILD_GIT_REPO}/commit/${BuildConfig.BUILD_COMMIT_HASH}"),
                    ),
                )
            }
            addPreference(R.string.update_check, R.string.update_check_summary) {
                findNavController().navigate(NavigationRoute.Update)
            }
            addPreference(R.string.librime_version, BuildConfig.LIBRIME_VERSION) {
                val hash = getCommitFromVersionName(BuildConfig.LIBRIME_VERSION)
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("${Const.LIBRIME_URL}/commit/$hash"),
                    ),
                )
            }
            addPreference(R.string.opencc_version, BuildConfig.OPENCC_VERSION) {
                val hash = getCommitFromVersionName(BuildConfig.OPENCC_VERSION)
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("${Const.OPENCC_URL}/commit/$hash"),
                    ),
                )
            }
            addPreference(R.string.sherpa_onnx_version, BuildConfig.SHERPA_ONNX_VERSION) {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(Const.SHERPA_ONNX_URL),
                    ),
                )
            }
            addPreference(
                Preference(requireContext()).apply {
                    isIconSpaceReserved = false
                    isCopyingEnabled = true
                    setTitle(R.string.build_info)
                    summary = requireContext().getString(
                        R.string.build_info_format,
                        BuildConfig.BUILDER,
                        BuildConfig.BUILD_COMMIT_HASH,
                        formatDateTime(BuildConfig.BUILD_TIMESTAMP),
                    )
                },
            )
            addCategory("") {
                isIconSpaceReserved = false
                addPreference(R.string.source_code, R.string.git_repo) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(BuildConfig.BUILD_GIT_REPO),
                        ),
                    )
                }
                addPreference(R.string.license, Const.LICENSE_SPDX_ID) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(Const.LICENSE_URL),
                        ),
                    )
                }
                addPreference(
                    R.string.open_source_licenses,
                    R.string.licenses_of_third_party_libraries,
                ) {
                    findNavController().navigate(NavigationRoute.License)
                }
            }
            addCategory("") {
                isIconSpaceReserved = false
                addPreference(R.string.qq_group_1, Const.QQ_GROUP_1_NUM) {
                    // 先让 QQ 直接打开加群页；装不上 QQ 再退回网页链接；都不行就复制群号
                    val opened = runCatching {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(Const.QQ_GROUP_1_URL),
                            ),
                        )
                    }.isSuccess || runCatching {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(Const.QQ_GROUP_1_WEB),
                            ),
                        )
                    }.isSuccess
                    if (!opened) {
                        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(
                                getString(R.string.qq_group_1),
                                Const.QQ_GROUP_1_NUM,
                            ),
                        )
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.qq_group_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    companion object {
        private val DASH_G_PATTERN = Regex("^(.*-g)([0-9a-f]+)(.*)$")
        private val COMMON_PATTERN = Regex("^([^-]*)(-.*)$")

        private fun getCommitFromVersionName(versionCode: String): String {
            val dashG = DASH_G_PATTERN.find(versionCode)?.groupValues?.get(2)
            val common = COMMON_PATTERN.find(versionCode)?.groupValues?.get(1)
            return dashG ?: common ?: versionCode
        }
    }
}
