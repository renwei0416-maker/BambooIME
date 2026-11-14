# 相对上游改了哪些文件

> 本文件由 `python tools/upstream-diff.py` 生成，**不要手改**。每次准备发版前重跑一次。

- 上游：`https://github.com/Carlson24/LyraIME`（分支 `lyraime`）
- 分叉点：`094e6894`（我们第一个提交 `b5d3a195` 的父提交）
- 提交数：`108`（`git rev-list --count 094e6894..HEAD`）
- 变更文件：**195** 个（修改 71 / 新增 122 / 删除 2）
- 代码行：**+55428 / -608**

## 上游在分叉点之后又动了什么

| 提交 | 说明 |
|---|---|
| `e5b2f010` | add syncinput button |
| `59d508e3` | update librime |

| 上游改的文件 | 增删 | 与我们重叠 |
|---|---|---|
| `app/src/main/assets/themes/catppuccin.lua` | +20/-1 | 否 |
| `app/src/main/java/com/osfans/trime/data/theme/model/SyncInput.kt` | +1/-0 | 否 |
| `app/src/main/java/com/osfans/trime/ime/bar/ui/ToolButton.kt` | +4/-2 | 否 |
| `app/src/main/java/com/osfans/trime/ime/composition/SyncInputBox.kt` | +134/-33 | 否 |
| `app/src/main/java/com/osfans/trime/ime/composition/SyncInputTextFormatter.kt` | +11/-1 | 否 |
| `app/src/main/java/com/osfans/trime/ime/core/InputView.kt` | +19/-1 | **是** |
| `app/src/main/jni/librime` | +1/-1 | 否 |
| `app/src/main/jni/librime-plugins/librime-ids-reverse` | +1/-1 | 否 |
| `app/src/test/java/com/osfans/trime/ime/composition/SyncInputTextFormatterTest.kt` | +19/-0 | 否 |

## ⚠ 合并时会冲突的文件

- `app/src/main/java/com/osfans/trime/ime/core/InputView.kt`

## 我们改过的上游文件（合并冲突只可能出在这些里）

| 文件 | 增删 |
|---|---|
| `README.md` | +223/-179 |
| `app/build.gradle.kts` ⭐ | +57/-2 |
| `app/proguard-rules.pro` | +8/-0 |
| `app/src/main/AndroidManifest.xml` | +16/-0 |
| `app/src/main/java/com/osfans/trime/TrimeApplication.kt` | +65/-37 |
| `app/src/main/java/com/osfans/trime/core/Rime.kt` ⭐ | +68/-5 |
| `app/src/main/java/com/osfans/trime/daemon/RimeDaemon.kt` | +13/-2 |
| `app/src/main/java/com/osfans/trime/data/base/DataManager.kt` | +1/-1 |
| `app/src/main/java/com/osfans/trime/data/db/ClipboardHelper.kt` | +70/-0 |
| `app/src/main/java/com/osfans/trime/data/db/DatabaseDao.kt` | +3/-0 |
| `app/src/main/java/com/osfans/trime/data/packaging/SchemaPackageManager.kt` ⭐ | +20/-0 |
| `app/src/main/java/com/osfans/trime/data/theme/ColorManager.kt` | +13/-0 |
| `app/src/main/java/com/osfans/trime/ime/bar/InputBarDelegate.kt` | +7/-1 |
| `app/src/main/java/com/osfans/trime/ime/candidates/popup/LabeledCandidateItemUi.kt` | +12/-3 |
| `app/src/main/java/com/osfans/trime/ime/candidates/popup/PagedCandidatesUi.kt` | +20/-2 |
| `app/src/main/java/com/osfans/trime/ime/clipboard/ClipboardAdapter.kt` | +21/-1 |
| `app/src/main/java/com/osfans/trime/ime/clipboard/ClipboardEntryUi.kt` | +5/-2 |
| `app/src/main/java/com/osfans/trime/ime/clipboard/ClipboardExtractor.kt` | +4/-12 |
| `app/src/main/java/com/osfans/trime/ime/clipboard/ClipboardUi.kt` | +39/-28 |
| `app/src/main/java/com/osfans/trime/ime/clipboard/ClipboardWindow.kt` | +189/-27 |
| `app/src/main/java/com/osfans/trime/ime/composition/CandidatesView.kt` ⭐ | +55/-4 |
| `app/src/main/java/com/osfans/trime/ime/composition/PreeditDelegate.kt` | +11/-1 |
| `app/src/main/java/com/osfans/trime/ime/composition/PreeditTextView.kt` | +3/-9 |
| `app/src/main/java/com/osfans/trime/ime/composition/PreeditUi.kt` ⭐ | +1964/-26 |
| `app/src/main/java/com/osfans/trime/ime/core/InputView.kt` ⭐ | +211/-2 |
| `app/src/main/java/com/osfans/trime/ime/core/TrimeInputMethodService.kt` ⭐ | +236/-5 |
| `app/src/main/java/com/osfans/trime/ime/keyboard/CommonKeyboardActionListener.kt` ⭐ | +112/-0 |
| `app/src/main/java/com/osfans/trime/ime/keyboard/GestureFrame.kt` | +39/-3 |
| `app/src/main/java/com/osfans/trime/ime/keyboard/KeyAction.kt` ⭐ | +9/-0 |
| `app/src/main/java/com/osfans/trime/ime/keyboard/KeyView.kt` ⭐ | +15/-1 |
| `app/src/main/java/com/osfans/trime/ime/segments/SegmentsAdapter.kt` | +33/-33 |
| `app/src/main/java/com/osfans/trime/ime/segments/SegmentsWindow.kt` | +2/-3 |
| `app/src/main/java/com/osfans/trime/ime/symbol/LiquidItemUi.kt` | +6/-2 |
| `app/src/main/java/com/osfans/trime/ime/symbol/LiquidTabsUi.kt` | +3/-0 |
| `app/src/main/java/com/osfans/trime/ime/symbol/LiquidWindow.kt` | +3/-0 |
| `app/src/main/java/com/osfans/trime/ime/window/BoardWindowManager.kt` ⭐ | +23/-0 |
| `app/src/main/java/com/osfans/trime/link/AsrkbSpeechClient.kt` | +3/-1 |
| `app/src/main/java/com/osfans/trime/link/SherpaSpeechClient.kt` | +4/-3 |
| `app/src/main/java/com/osfans/trime/ui/common/PaddingPreferenceFragment.kt` | +7/-0 |
| `app/src/main/java/com/osfans/trime/ui/main/AboutFragment.kt` | +33/-46 |
| `app/src/main/java/com/osfans/trime/ui/main/MainActivity.kt` | +8/-1 |
| `app/src/main/java/com/osfans/trime/ui/main/MainFragment.kt` ⭐ | +40/-0 |
| `app/src/main/java/com/osfans/trime/ui/main/NavigationRoute.kt` ⭐ | +85/-0 |
| `app/src/main/java/com/osfans/trime/ui/main/settings/PackageListFragment.kt` ⭐ | +47/-7 |
| `app/src/main/java/com/osfans/trime/ui/setup/SetupActivity.kt` | +1/-1 |
| `app/src/main/java/com/osfans/trime/util/Const.kt` | +3/-11 |
| `app/src/main/java/com/osfans/trime/util/PreferenceScreen.kt` | +3/-1 |
| `app/src/main/jni/CMakeLists.txt` | +31/-2 |
| `app/src/main/jni/cmake/Boost.cmake` | +26/-9 |
| `app/src/main/jni/cmake/OnnxRuntime.cmake` | +55/-21 |
| `app/src/main/jni/librime_jni/rime_jni.cc` | +17/-5 |
| `app/src/main/res/mipmap-anydpi-v26/ic_app_icon.xml` | +2/-2 |
| `app/src/main/res/mipmap-anydpi-v26/ic_app_icon_round.xml` | +2/-2 |
| `app/src/main/res/mipmap-hdpi/ic_app_icon.png` | +0/-0 |
| `app/src/main/res/mipmap-hdpi/ic_app_icon_round.png` | +0/-0 |
| `app/src/main/res/mipmap-mdpi/ic_app_icon.png` | +0/-0 |
| `app/src/main/res/mipmap-mdpi/ic_app_icon_round.png` | +0/-0 |
| `app/src/main/res/mipmap-xhdpi/ic_app_icon.png` | +0/-0 |
| `app/src/main/res/mipmap-xhdpi/ic_app_icon_round.png` | +0/-0 |
| `app/src/main/res/mipmap-xxhdpi/ic_app_icon.png` | +0/-0 |
| `app/src/main/res/mipmap-xxhdpi/ic_app_icon_round.png` | +0/-0 |
| `app/src/main/res/mipmap-xxxhdpi/ic_app_icon.png` | +0/-0 |
| `app/src/main/res/mipmap-xxxhdpi/ic_app_icon_round.png` | +0/-0 |
| `app/src/main/res/values-zh-rCN/strings.xml` | +25/-11 |
| `app/src/main/res/values-zh-rTW/strings.xml` | +7/-11 |
| `app/src/main/res/values/ic_app_icon_background.xml` | +1/-1 |
| `app/src/main/res/values/strings.xml` | +25/-11 |
| `app/src/main/res/xml-v30/method.xml` | +1/-1 |
| `app/src/main/res/xml/method.xml` | +1/-1 |
| `gradle.properties` ⭐ | +11/-2 |
| `gradle/libs.versions.toml` | +4/-0 |

⭐ = 语义改动较大、合并时优先人工过一遍；逐个文件的「为什么改」见 `docs/upstream-patches/`。

## 我们新增的文件（一般不会冲突）

<details><summary>122 个，点开</summary>

- `app/src/main/assets/handwriting/hanzi_pinyin.tsv`
- `app/src/main/handwriting-assets/handwriting/ochwpro/NOTICE-ochwpro.txt`
- `app/src/main/handwriting-assets/handwriting/ochwpro/char_index.json`
- `app/src/main/handwriting-assets/handwriting/ochwpro/ochwpro.onnx`
- `app/src/main/java/com/osfans/trime/actions/bamboo/AiClient.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/AiPanelWindow.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/AiPrompts.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/AutoPair.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/AutoPairPrefs.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/BambooActions.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/BambooPrefs.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/BambooTokens.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/EditorEdit.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/KeyCombo.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/RevertLedger.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/SecretStore.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/VoiceAi.kt`
- `app/src/main/java/com/osfans/trime/actions/bamboo/VoiceAiPrefs.kt`
- `app/src/main/java/com/osfans/trime/actions/glossary/GlossaryDecorator.kt`
- `app/src/main/java/com/osfans/trime/actions/glossary/GlossaryDownloader.kt`
- `app/src/main/java/com/osfans/trime/actions/glossary/GlossaryFormatter.kt`
- `app/src/main/java/com/osfans/trime/actions/glossary/GlossaryPack.kt`
- `app/src/main/java/com/osfans/trime/actions/glossary/GlossaryPrefs.kt`
- `app/src/main/java/com/osfans/trime/actions/glossary/GlossaryStore.kt`
- `app/src/main/java/com/osfans/trime/actions/glossary/GlossaryTips.kt`
- `app/src/main/java/com/osfans/trime/actions/handwriting/HandwritingBoardView.kt`
- `app/src/main/java/com/osfans/trime/actions/handwriting/HandwritingLog.kt`
- `app/src/main/java/com/osfans/trime/actions/handwriting/HandwritingModelStore.kt`
- `app/src/main/java/com/osfans/trime/actions/handwriting/HandwritingNative.kt`
- `app/src/main/java/com/osfans/trime/actions/handwriting/HandwritingPrefs.kt`
- `app/src/main/java/com/osfans/trime/actions/handwriting/HandwritingRecognizer.kt`
- `app/src/main/java/com/osfans/trime/actions/handwriting/HandwritingWindow.kt`
- `app/src/main/java/com/osfans/trime/actions/handwriting/HanziPinyin.kt`
- `app/src/main/java/com/osfans/trime/actions/handwriting/SymbolPages.kt`
- `app/src/main/java/com/osfans/trime/actions/handwriting/SymbolUsage.kt`
- `app/src/main/java/com/osfans/trime/actions/ocr/OcrCard.kt`
- `app/src/main/java/com/osfans/trime/actions/ocr/OcrFrame.kt`
- `app/src/main/java/com/osfans/trime/actions/ocr/OcrFrameQuality.kt`
- `app/src/main/java/com/osfans/trime/actions/ocr/OcrGalleryView.kt`
- `app/src/main/java/com/osfans/trime/actions/ocr/OcrModelStore.kt`
- `app/src/main/java/com/osfans/trime/actions/ocr/OcrNative.kt`
- `app/src/main/java/com/osfans/trime/actions/ocr/OcrPermissionActivity.kt`
- `app/src/main/java/com/osfans/trime/actions/ocr/OcrPrefs.kt`
- `app/src/main/java/com/osfans/trime/actions/ocr/OcrRecognizer.kt`
- `app/src/main/java/com/osfans/trime/actions/ocr/OcrWindow.kt`
- `app/src/main/java/com/osfans/trime/actions/ocr/ThemeContrast.kt`
- `app/src/main/java/com/osfans/trime/actions/tips/TipBlocks.kt`
- `app/src/main/java/com/osfans/trime/actions/wanxiang/WanxiangCustom.kt`
- `app/src/main/java/com/osfans/trime/actions/wanxiang/WanxiangPrefs.kt`
- `app/src/main/java/com/osfans/trime/actions/wenquxing/WenquxingPack.kt`
- `app/src/main/java/com/osfans/trime/actions/wenquxing/WenquxingPrefs.kt`
- `app/src/main/java/com/osfans/trime/actions/wenquxing/WenquxingStore.kt`
- `app/src/main/java/com/osfans/trime/actions/wenquxing/WenquxingTips.kt`
- `app/src/main/java/com/osfans/trime/data/db/ClipboardTabs.kt`
- `app/src/main/java/com/osfans/trime/data/packaging/PackageSchemaList.kt`
- `app/src/main/java/com/osfans/trime/data/packaging/SchemaPackStore.kt`
- `app/src/main/java/com/osfans/trime/data/prefs/AppearancePrefs.kt`
- `app/src/main/java/com/osfans/trime/data/prefs/HardwareKeyboardPrefs.kt`
- `app/src/main/java/com/osfans/trime/ime/clipboard/ClipboardExtractScan.kt`
- `app/src/main/java/com/osfans/trime/ime/hardware/HardwareStatusBar.kt`
- `app/src/main/java/com/osfans/trime/ime/segments/PieceTokenizer.kt`
- `app/src/main/java/com/osfans/trime/ui/common/PrefRestore.kt`
- `app/src/main/java/com/osfans/trime/ui/common/SettingsDividerDecoration.kt`
- `app/src/main/java/com/osfans/trime/ui/common/SettingsPalette.kt`
- `app/src/main/java/com/osfans/trime/ui/common/SettingsStyler.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/BambooActionFragment.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/BambooAppearanceFragment.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/BambooTokensFragment.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/GlossaryFragment.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/HandwritingSettingsFragment.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/HardwareKeyboardFragment.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/InputEnhanceFragment.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/OcrSettingsFragment.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/ValueSliderPreference.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/VoiceAiFragment.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/WanxiangFragment.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/WanxiangSetupFragment.kt`
- `app/src/main/java/com/osfans/trime/ui/main/settings/WenquxingFragment.kt`
- `app/src/main/java/com/osfans/trime/util/RecyclerViewGesture.kt`
- `app/src/main/jni/bamboo_handwriting/CMakeLists.txt`
- `app/src/main/jni/bamboo_handwriting/handwriting_jni.cc`
- `app/src/main/jni/bamboo_ocr/CMakeLists.txt`
- `app/src/main/jni/bamboo_ocr/ocr_jni.cc`
- `app/src/main/res/drawable-nodpi/ic_stat_bamboo.png`
- `app/src/main/res/drawable/ic_bamboo_24.xml`
- `app/src/main/res/drawable/ic_baseline_photo_camera_24.xml`
- `app/src/main/res/layout/preference_bamboo_category.xml`
- `app/src/main/res/mipmap-hdpi/ic_app_icon_foreground.png`
- `app/src/main/res/mipmap-mdpi/ic_app_icon_foreground.png`
- `app/src/main/res/mipmap-xhdpi/ic_app_icon_foreground.png`
- `app/src/main/res/mipmap-xxhdpi/ic_app_icon_foreground.png`
- `app/src/main/res/mipmap-xxxhdpi/ic_app_icon_foreground.png`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/main/wenquxing-assets/wenquxing/chuci.bwx`
- `app/src/main/wenquxing-assets/wenquxing/duilian.bwx`
- `app/src/main/wenquxing-assets/wenquxing/mingju.bwx`
- `app/src/main/wenquxing-assets/wenquxing/songci1000.bwx`
- `app/src/main/wenquxing-assets/wenquxing/tangshi1000.bwx`
- `app/src/test/java/com/osfans/trime/ime/clipboard/ClipboardExtractScanTest.kt`
- `app/src/test/java/com/osfans/trime/ime/segments/PieceTokenizerTest.kt`
- `docs/BACKLOG.md`
- `docs/MERGE-UPSTREAM.md`
- `docs/ROADMAP.md`
- `docs/UPSTREAM-DIFF.md`
- `docs/rime-actions-port/BAMBOO-ACTIONS.md`
- `docs/rime-actions-port/BAMBOO-HANDWRITING.md`
- `docs/rime-actions-port/BAMBOO-TOKENS.md`
- `docs/rime-actions-port/BRANDING.md`
- `docs/rime-actions-port/CLIPBOARD-PLAN.md`
- `docs/rime-actions-port/OCR-TOUCHPOINTS.md`
- `docs/rime-actions-port/README.md`
- `docs/rime-actions-port/RELEASE.md`
- `docs/rime-actions-port/WHATS-NEW-2.0.0.md`
- `docs/upstream-patches/PackageListFragment.kt.orig`
- `docs/upstream-patches/PackageListFragment.patch`
- `docs/upstream-patches/README.md`
- `tools/__pycache__/glossary_pack.cpython-312.pyc`
- `tools/glossary_gen.py`
- `tools/glossary_pack.py`
- `tools/upstream-diff.py`
- `tools/wenquxing_pack.py`

</details>

## 我们删掉的上游文件

- `app/src/main/java/com/osfans/trime/util/NativeTokenizer.kt`
- `app/src/main/res/drawable/ic_app_icon_foreground.xml`

