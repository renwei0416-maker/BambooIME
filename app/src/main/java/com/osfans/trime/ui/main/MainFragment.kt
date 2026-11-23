/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceGroup
import com.osfans.trime.R
import com.osfans.trime.actions.wanxiang.WanxiangPrefs
import com.osfans.trime.data.packaging.SchemaPackStore
import com.osfans.trime.data.theme.BambooThemeSeed
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.confirmDialog
import com.osfans.trime.ui.texteditor.FileBrowserActivity
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addAccentChipPreference
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

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

    /** 首页入口标题固定，状态只在「万象拼音」页内顶部显示。 */
    private fun wanxiangTitle(): String = "万象拼音"

    /** 首页入口说明：固定一句话，不随状态变化。 */
    private fun wanxiangSummary(): String =
        "不用自己的方案包、也不懂改 custom 的，直接进「设置向导」配置就好。"

    /**
     * 「释放内置主题」：把 5 套出厂主题（.lua + lib/<id>/）与内置字体无条件写回主题目录。
     * 会覆盖同名文件，故先确认；写完后若当前主题就是内置主题则重载它。
     */
    private fun releaseBundledThemes() {
        val appContext = requireContext().applicationContext
        requireContext().confirmDialog(
            R.string.release_bundled_themes_title,
            R.string.release_bundled_themes_message,
        ) {
            lifecycleScope.launch {
                val count = withContext(Dispatchers.IO) {
                    runCatching { BambooThemeSeed.releaseAll(appContext) }.getOrDefault(-1)
                }
                if (count < 0) {
                    requireContext().toast(R.string.release_bundled_themes_failed)
                    return@launch
                }
                val current = runCatching { ThemeManager.prefs.selectedTheme.getValue() }.getOrNull()
                if (current != null && current in BambooThemeSeed.BUNDLED_THEME_IDS) {
                    withContext(Dispatchers.IO) {
                        runCatching { ThemeManager.selectTheme(current, true) }
                            .onFailure { Timber.w(it, "releaseBundledThemes: 重载主题失败") }
                    }
                }
                requireContext().toast(
                    requireContext().getString(R.string.release_bundled_themes_done, count),
                )
            }
        }
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            // 万象拼音放最顶部：它是「第一次用要配的东西」，不能藏在中间。
            // 副标题按状态说人话：没就绪 -> 第一次用；就绪但没走向导 -> 还没配；配好了 -> 显示当前形态。
            addAccentChipPreference(
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
            addDestinationPreference(
                R.string.update_title,
                R.drawable.ic_baseline_refresh_reversed_24,
                NavigationRoute.Update,
            )
            addPreference(
                getString(R.string.release_bundled_themes_title),
                getString(R.string.release_bundled_themes_summary),
                R.drawable.ic_baseline_refresh_reversed_24,
            ) {
                releaseBundledThemes()
            }
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
                addPreference("截图", "截取屏幕任意区域：保存、复制、识字、AI 看图（需先开无障碍截图权限）", R.drawable.ic_baseline_photo_camera_24) {
                    findNavController().navigateWithAnim(NavigationRoute.ShotSettings)
                }

                addPreference("文曲星", "诗词提示：候选/剪贴板/光标前文本命中名句、唐诗宋词时在编码后给出摘要", R.drawable.ic_baseline_book_24) {
                    findNavController().navigateWithAnim(NavigationRoute.Wenquxing)
                }
                addPreference("输入增强", "成对符号自动补全：打左半边自动补右半边，光标停在中间", R.drawable.ic_baseline_spellcheck_24) {
                    findNavController().navigateWithAnim(NavigationRoute.InputEnhance)
                }
                addPreference("主题编辑", "可视化改当前主题的配色、尺寸与字号，改完能导出给别的用户", R.drawable.ic_baseline_color_lens_24) {
                    findNavController().navigateWithAnim(NavigationRoute.ThemeStyle)
                }
                addPreference("外语学习", "让你边打字边学习外语", R.drawable.ic_baseline_language_24) {
                    findNavController().navigateWithAnim(NavigationRoute.Glossary)
                }
                addPreference("语音发AI", "按住空格说完话，识别结果直接交给当前 AI 模式处理", R.drawable.ic_baseline_mic_24) {
                    findNavController().navigateWithAnim(NavigationRoute.VoiceAi)
                }
                addPreference("设置页外观", "固定浅/深配色，强调色可跟随键盘主题；含取色自检", R.drawable.ic_baseline_color_lens_24) {
                    findNavController().navigateWithAnim(NavigationRoute.BambooAppearance)
                }
                addPreference(
                    "部件反查（IDS）",
                    "打 I木口 查「木+口」的字；打 O 前缀可拼音与部件混着打",
                    R.drawable.ic_baseline_search_24,
                ) {
                    findNavController().navigateWithAnim(NavigationRoute.IdsReverse)
                }
                addPreference("其它扩展功能设置", "面板行为、键盘形态与零散开关", R.drawable.ic_baseline_tune_24) {
                    findNavController().navigateWithAnim(NavigationRoute.PanelQuick)
                }
                addPreference("外接键盘", "接硬件键盘时的浮动候选条（个数、横排/竖排）与状态条", R.drawable.ic_baseline_keyboard_24) {
                    findNavController().navigateWithAnim(NavigationRoute.HardwareKeyboard)
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
