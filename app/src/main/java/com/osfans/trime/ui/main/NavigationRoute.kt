/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Parcelable
import androidx.navigation.NavController
import androidx.navigation.createGraph
import androidx.navigation.fragment.fragment
import com.osfans.trime.R
import com.osfans.trime.ui.main.settings.AdvancedSettingsFragment
import com.osfans.trime.ui.main.settings.BambooActionFragment
import com.osfans.trime.ui.main.settings.BambooAppearanceFragment
import com.osfans.trime.ui.main.settings.BambooTokensFragment
import com.osfans.trime.ui.main.settings.WenquxingFragment
import com.osfans.trime.ui.main.settings.HardwareKeyboardFragment
import com.osfans.trime.ui.main.settings.CandidatesSettingsFragment
import com.osfans.trime.ui.main.settings.ClipboardSettingsFragment
import com.osfans.trime.ui.main.settings.CustomTasksFragment
import com.osfans.trime.ui.main.settings.GeneralSettingsFragment
import com.osfans.trime.ui.main.settings.HandwritingSettingsFragment
import com.osfans.trime.ui.main.settings.GlossaryFragment
import com.osfans.trime.ui.main.settings.InputEnhanceFragment
import com.osfans.trime.ui.main.settings.ThemeStyleFragment
import com.osfans.trime.ui.main.settings.InputStatsFragment
import com.osfans.trime.ui.main.settings.KeyboardSettingsFragment
import com.osfans.trime.ui.main.settings.OcrSettingsFragment
import com.osfans.trime.ui.main.settings.ShotSettingsFragment
import com.osfans.trime.ui.main.settings.PackageListFragment
import com.osfans.trime.ui.main.settings.IdsReverseFragment
import com.osfans.trime.ui.main.settings.PanelQuickFragment
import com.osfans.trime.ui.main.settings.ProfileSettingsFragment
import com.osfans.trime.ui.main.settings.VoiceInputSettingsFragment
import com.osfans.trime.ui.main.settings.VoiceAiFragment
import com.osfans.trime.ui.main.settings.WanxiangDownloadFragment
import com.osfans.trime.ui.main.settings.WanxiangFragment
import com.osfans.trime.ui.main.settings.WanxiangSetupFragment
import com.osfans.trime.ui.main.settings.schema.SchemaListFragment
import com.osfans.trime.ui.main.settings.theme.ThemeSettingsFragment
import com.osfans.trime.ui.main.settings.userdict.UserDictionaryFragment
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
sealed class NavigationRoute : Parcelable {

    @Serializable
    data object Main : NavigationRoute()

    @Serializable
    data object PackageList : NavigationRoute()

    @Serializable
    data object SchemaList : NavigationRoute()

    @Serializable
    data object UserDict : NavigationRoute()

    @Serializable
    data object Profile : NavigationRoute()

    @Serializable
    data object General : NavigationRoute()

    @Serializable
    data object BambooAction : NavigationRoute()
    @Serializable
    data object BambooTokens : NavigationRoute()

    @Serializable
    data object Wenquxing : NavigationRoute()


    @Serializable
    data object Handwriting : NavigationRoute()

    @Serializable
    data object OcrSettings : NavigationRoute()

    @Serializable
    data object BambooAppearance : NavigationRoute()

    @Serializable
    data object HardwareKeyboard : NavigationRoute()

    @Serializable
    data object InputEnhance : NavigationRoute()

    @Serializable
    data object ThemeStyle : NavigationRoute()

    @Serializable
    data object Glossary : NavigationRoute()

    @Serializable
    data object VoiceAi : NavigationRoute()

    @Serializable
    data object PanelQuick : NavigationRoute()

@Serializable
data object IdsReverse : NavigationRoute()

    @Serializable
    data object Wanxiang : NavigationRoute()

    @Serializable
    data object WanxiangDownload : NavigationRoute()

    @Serializable
    data object WanxiangSetup : NavigationRoute()

    @Serializable
    data object VirtualKeyboard : NavigationRoute()

    @Serializable
    data object VoiceInput : NavigationRoute()

    @Serializable
    data object InputStats : NavigationRoute()

    @Serializable
    data object CandidatesWindow : NavigationRoute()

    @Serializable
    data object Theme : NavigationRoute()

    @Serializable
    data object Clipboard : NavigationRoute()

    @Serializable
    data object Advanced : NavigationRoute()

    @Serializable
    data object CustomTaskSettings : NavigationRoute()

    @Serializable
    data object Developer : NavigationRoute()

    @Serializable
    data object About : NavigationRoute()

    @Serializable
    data object License : NavigationRoute()

    @Serializable
    data object ShotSettings : NavigationRoute()

    companion object {
        fun createGraph(controller: NavController) = controller.createGraph(Main) {
            val ctx = controller.context

            fragment<MainFragment, Main> {
                label = ctx.getString(R.string.trime_app_name)
            }

            fragment<PackageListFragment, PackageList> {
                label = ctx.getString(R.string.packages_title)
            }

            fragment<SchemaListFragment, SchemaList> {
                label = ctx.getString(R.string.schemata)
            }
            fragment<UserDictionaryFragment, UserDict> {
                label = ctx.getString(R.string.user_dictionary)
            }
            fragment<ProfileSettingsFragment, Profile> {
                label = ctx.getString(R.string.profile_and_synchronization)
            }

            fragment<GeneralSettingsFragment, General> {
                label = ctx.getString(R.string.general)
            }
            fragment<KeyboardSettingsFragment, VirtualKeyboard> {
                label = ctx.getString(R.string.virtual_keyboard)
            }
            fragment<VoiceInputSettingsFragment, VoiceInput> {
                label = ctx.getString(R.string.voice_input)
            }
            fragment<InputStatsFragment, InputStats> {
                label = ctx.getString(R.string.input_statistics)
            }
            fragment<CandidatesSettingsFragment, CandidatesWindow> {
                label = ctx.getString(R.string.candidates_window)
            }
            fragment<ThemeSettingsFragment, Theme> {
                label = ctx.getString(R.string.theme)
            }
            fragment<ClipboardSettingsFragment, Clipboard> {
                label = ctx.getString(R.string.clipboard)
            }
            fragment<BambooActionFragment, BambooAction> {
                label = "回退与AI"
            }
            fragment<BambooTokensFragment, BambooTokens> {
                label = "按键动作调用"
            }

            fragment<WenquxingFragment, Wenquxing> {
                label = "文曲星"
            }
            fragment<HandwritingSettingsFragment, Handwriting> {
                label = "手写"
            }
            fragment<OcrSettingsFragment, OcrSettings> {
                label = "拍照识字"
            }
            fragment<ShotSettingsFragment, ShotSettings> {
                label = "截图"
            }
            fragment<BambooAppearanceFragment, BambooAppearance> {
                label = "设置页外观"
            }
            fragment<HardwareKeyboardFragment, HardwareKeyboard> {
                label = "外接键盘"
            }
            fragment<InputEnhanceFragment, InputEnhance> {
                label = "输入增强"
            }
            fragment<ThemeStyleFragment, ThemeStyle> {
                label = "主题编辑"
            }
            fragment<GlossaryFragment, Glossary> {
                label = "外语学习"
            }
            fragment<PanelQuickFragment, PanelQuick> {
                label = "其它扩展功能设置"
            }

            fragment<IdsReverseFragment, IdsReverse> {
                label = "部件反查"
            }

            fragment<WanxiangFragment, Wanxiang> {
                label = "万象拼音"
            }
            fragment<WanxiangSetupFragment, WanxiangSetup> {
                label = "万象拼音设置向导"
            }
            fragment<WanxiangDownloadFragment, WanxiangDownload> {
                label = "方案包下载"
            }
            fragment<VoiceAiFragment, VoiceAi> {
                label = "语音发AI"
            }
            fragment<AdvancedSettingsFragment, Advanced> {
                label = ctx.getString(R.string.advanced)
            }
            fragment<CustomTasksFragment, CustomTaskSettings> {
                label = ctx.getString(R.string.custom_title)
            }
            fragment<DeveloperFragment, Developer> {
                label = ctx.getString(R.string.developer)
            }
            fragment<AboutFragment, About> {
                label = ctx.getString(R.string.about)
            }
            fragment<LicenseFragment, License> {
                label = ctx.getString(R.string.license)
            }
        }
    }
}
