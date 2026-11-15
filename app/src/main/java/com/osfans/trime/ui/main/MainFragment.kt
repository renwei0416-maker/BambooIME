/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceGroup
import com.osfans.trime.R
import com.osfans.trime.actions.wanxiang.WanxiangPrefs
import com.osfans.trime.data.packaging.SchemaPackStore
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.texteditor.FileBrowserActivity
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addAccentPreference
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

class MainFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onStart() {
        super.onStart()
        viewModel.enableTopOptionsMenu()
    }

    override fun onStop() {
        viewModel.disableTopOptionsMenu()
        super.onStop()
    }
    private fun PreferenceGroup.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        route: NavigationRoute,
    ) {
        addPreference(title, icon = icon) {
            findNavController().navigateWithAnim(route)
        }
    }

    private fun wanxiangTitle(): String =
        if (SchemaPackStore.isInstalled() && WanxiangPrefs.wizardDone) "万象拼音" else "万象拼音（还没配置）"

    private fun wanxiangSummary(): String {
        if (!SchemaPackStore.isInstalled()) {
            return "第一次用？点这里，1 分钟配好：内置全拼/双拼 + 九键，67 MB 不用担心下载"
        }
        if (!WanxiangPrefs.wizardDone) {
            return "点这里选键盘形态和编码方式，1 分钟完成；也可以用「方案设置」逐项调"
        }
        val layout = when (WanxiangPrefs.layout) {
            WanxiangPrefs.LAYOUT_9 -> "九键"
            WanxiangPrefs.LAYOUT_18 -> "18 键"
            WanxiangPrefs.LAYOUT_14 -> "14 键"
            else -> "26 键"
        }
        val encode =
            if (WanxiangPrefs.encode == WanxiangPrefs.ENCODE_QUANPIN) "全拼" else "双拼 · ${WanxiangPrefs.scheme}"
        return "已配置：$layout · $encode（点这里改设置、换下载源、启用/切换方案包）"
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            // 万象拼音放最顶部：它是「第一次用要配的东西」，不能藏在中间。
            // 副标题按状态说人话：没就绪 -> 第一次用；就绪但没走向导 -> 还没配；配好了 -> 显示当前形态。
            addAccentPreference(
                wanxiangTitle(),
                wanxiangSummary(),
                R.drawable.ic_baseline_text_fields_24,
            ) {
                findNavController().navigateWithAnim(NavigationRoute.Wanxiang)
            }
            addDestinationPreference(
                R.string.packages_title,
                R.drawable.ic_baseline_folder_24,
                NavigationRoute.PackageList,
            )
            addDestinationPreference(
                R.string.user_dictionary,
                R.drawable.ic_baseline_book_24,
                NavigationRoute.UserDict,
            )
            addDestinationPreference(
                R.string.profile_and_synchronization,
                R.drawable.ic_baseline_folder_sync_24,
                NavigationRoute.Profile,
            )
            addCategory("") {
                isIconSpaceReserved = false
                addDestinationPreference(
                    R.string.general,
                    R.drawable.ic_baseline_tune_24,
                    NavigationRoute.General,
                )
                addDestinationPreference(
                    R.string.virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    NavigationRoute.VirtualKeyboard,
                )
                addDestinationPreference(
                    R.string.voice_input,
                    R.drawable.ic_baseline_mic_24,
                    NavigationRoute.VoiceInput,
                )
                addDestinationPreference(
                    R.string.input_statistics,
                    R.drawable.ic_baseline_bar_chart_24,
                    NavigationRoute.InputStats,
                )
                addDestinationPreference(
                    R.string.candidates_window,
                    R.drawable.ic_baseline_list_alt_24,
                    NavigationRoute.CandidatesWindow,
                )
                addDestinationPreference(
                    R.string.theme,
                    R.drawable.ic_baseline_color_lens_24,
                    NavigationRoute.Theme,
                )
                addDestinationPreference(
                    R.string.clipboard,
                    R.drawable.ic_baseline_clipboard_24,
                    NavigationRoute.Clipboard,
                )
                addDestinationPreference(
                    R.string.advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    NavigationRoute.Advanced,
                )
            }
            addCategory("") {
                isIconSpaceReserved = false
                addPreference("回退与AI", "回退、AI 模式（触发码、提示词、接口）", R.drawable.ic_bamboo_24) {
                    findNavController().navigateWithAnim(NavigationRoute.BambooAction)
                }
                addPreference("手写", "手写板：停顿时长、候选个数、笔宽、配色", R.drawable.ic_baseline_edit_24) {
                    findNavController().navigateWithAnim(NavigationRoute.Handwriting)
                }
                addPreference("拍照识字", "相机取景、单字模式、实时预览与识别门槛", R.drawable.ic_baseline_photo_camera_24) {
                    findNavController().navigateWithAnim(NavigationRoute.OcrSettings)
                }
                addPreference("外观", "设置界面是否跟随键盘主题配色、深浅色与取色自检", R.drawable.ic_baseline_color_lens_24) {
                    findNavController().navigateWithAnim(NavigationRoute.BambooAppearance)
                }
                addPreference("外接键盘", "接硬件键盘时的浮动候选条（个数、横排/竖排）与状态条", R.drawable.ic_baseline_keyboard_24) {
                    findNavController().navigateWithAnim(NavigationRoute.HardwareKeyboard)
                }
                addPreference("文曲星", "诗词提示：候选/剪贴板/光标前文本命中名句、唐诗宋词时在编码后给出摘要", R.drawable.ic_baseline_book_24) {
                    findNavController().navigateWithAnim(NavigationRoute.Wenquxing)
                }
                addPreference("输入增强", "成对符号自动补全：打左半边自动补右半边，光标停在中间", R.drawable.ic_baseline_spellcheck_24) {
                    findNavController().navigateWithAnim(NavigationRoute.InputEnhance)
                }
                addPreference("外语学习", "让你边打字边学习外语", R.drawable.ic_baseline_language_24) {
                    findNavController().navigateWithAnim(NavigationRoute.Glossary)
                }
                addPreference("语音发AI", "按住空格说完话，识别结果直接交给当前 AI 模式处理", R.drawable.ic_baseline_mic_24) {
                    findNavController().navigateWithAnim(NavigationRoute.VoiceAi)
                }
                addPreference(
                    "按键动作调用说明",
                    "全部动作名（token）的总表、写法和注意事项，供自定义键位参照",
                    R.drawable.ic_baseline_list_alt_24,
                ) {
                    findNavController().navigateWithAnim(NavigationRoute.BambooTokens)
                }
            }
            addCategory("") {
                isIconSpaceReserved = false
                addPreference(
                    title = R.string.text_editor_title,
                    icon = R.drawable.ic_baseline_file_document_edit_outline_24,
                ) {
                    requireContext().startActivity(android.content.Intent(requireContext(), FileBrowserActivity::class.java))
                }
                addPreference(
                    title = R.string.custom_title,
                    icon = R.drawable.ic_baseline_download_24,
                ) {
                    findNavController().navigateWithAnim(NavigationRoute.CustomTaskSettings)
                }
            }
        }
    }
}
