# 拍照识字（OCR）对既有代码的改动清单

这份文件只记录**为「拍照识字」而改动/新增的既有代码入口**，方便出问题时精确还原。
功能本身的实现文件放在文末「新增文件」一节，可以直接删除而不影响其它功能。

## 一、改动了哪些既有文件

### 1. `app/src/main/java/com/osfans/trime/ime/core/InputView.kt`

| 位置 | 改动 | 不激活时的行为 |
| --- | --- | --- |
| `private lateinit var ocrCardHost: LinearLayout` | 新增字段：确认卡片的挂载点 | 一直 `GONE`，不参与布局 |
| `init {}` 里 `add(syncInputBox, …)` 之后 | 新增 `add(ocrCardHost, lParams(matchParent, wrapContent) { bottomToTop = preedit.ui.root.id; startOfParent(); endOfParent() })` | 卡片 `GONE`，约束不产生任何位移 |
| `fun showOcrCard(text)` | 新函数：显示卡片 + `requestApplyInsets()` | 不被调用 |
| `fun hideOcrCard()` | 新函数：隐藏卡片 + `requestApplyInsets()` | 不被调用 |
| `fun getOcrCardTouchRegion(outRegion)` | 新函数：键盘矩形 ∪ 卡片矩形 | 只在 `OcrCard.isActive` 时被调用 |
| `private fun logOcrCardLayout(stage)` | 新函数：debug 包打印卡片/键盘坐标 | 只写 debug 日志 |
| `fun setInputBarSuppressed(suppressed)` | 新函数：相机面板挂载时隐藏顶栏 | 只在 `OcrWindow` 挂载时调用 |
| `onDetachedFromWindow()` | 加一行 `OcrCard.deactivate()` | 兜底，避免卡片失焦后键盘打不出字 |

> 变高、裁剪键盘高度、搬 `preedit`、搬 `syncInputBox` 这些尝试**全部已还原**，现在的约束链与上游一致，只有上面这一条新增约束挂在 `preedit` 上。

### 2. `app/src/main/java/com/osfans/trime/ime/core/TrimeInputMethodService.kt`

| 位置 | 改动 | 不激活时的行为 |
| --- | --- | --- |
| `fun commitText(text)` 开头 | `if (OcrCard.isActive && !OcrCard.bypass) { OcrCard.append(text); return }` | 条件恒假，走原逻辑 |
| `handleRimeMessage` 的 `CommitTextMessage ->` | 卡片激活时把文本追加进卡片，不提交给 App | 条件恒假，原逻辑（记账 + commitText） |
| `KeyMessage` 的 `else ->` 分支开头 | 卡片激活且 `KEYCODE_DEL` 时改删卡片里的字 | 条件恒假，原逻辑 |
| `override fun onComputeInsets(outInsets)` | 虚拟键盘分支里追加 `if (OcrCard.isActive) { touchableInsets = TOUCHABLE_INSETS_REGION; getOcrCardTouchRegion(...) }` | 条件恒假，仍是 `TOUCHABLE_INSETS_VISIBLE` |
| `internal val currentInputView: InputView?` | 新属性，供 `OcrWindow` 取 InputView | 只读，无副作用 |

**为什么必须要 `onComputeInsets` 那一段**：上游用 `TOUCHABLE_INSETS_VISIBLE` + `visibleTopInsets = 键盘顶边`，
键盘上方不属于本窗口触摸区；浮层卡片画在那个区域里，触摸会穿透到下面的 App。卡片激活时把
「键盘 ∪ 卡片」并入 `touchableRegion` 才能点到，且不会挡住卡片上方的 App 区域。

## 二、新增文件（删除即完全移除功能）

```
app/src/main/java/com/osfans/trime/actions/ocr/
  OcrWindow.kt          相机面板窗口（取帧、质量闸门、快门、相册入口）
  OcrCard.kt            确认卡片（可编辑 + 删字/清空/光标 + 重拍/复制/上屏/收起）
  OcrFrameQuality.kt    画面质量闸门 + 两帧稳定判定（参数照抄 CyIME）
  OcrRecognizer.kt      模型加载与调用（ARGB→RGB、JNI 封装）
  OcrModelStore.kt      模型安装/读取/删除（assets → filesDir/ocr/ppocr-v5）
  OcrNative.kt          JNI 声明
  OcrPermissionActivity.kt  透明宿主，用于申请相机权限
app/src/main/jni/bamboo_ocr/
  ocr_jni.cc            C++ 引擎（ORT C API：det → DB → 逐行 rec → CTC）
  CMakeLists.txt
```

配套改动（同样只在 OCR 路径生效）：

- `app/src/main/jni/CMakeLists.txt` 加 `add_subdirectory(bamboo_ocr)`
- `app/build.gradle.kts` 加 CameraX 三个依赖、`bundleOcrModel` 开关、`BUILD_CONFIG BUNDLED_OCR_MODEL`
- `gradle/libs.versions.toml` 加 `camera = "1.4.2"`
- `app/src/main/AndroidManifest.xml` 加 `CAMERA` 权限、`<uses-feature camera required="false">`、`OcrPermissionActivity`
- `app/src/main/java/com/osfans/trime/ime/keyboard/CommonKeyboardActionListener.kt` 加 `BambooOcr` token 分支（插在手写判定之后）
- `app/proguard-rules.pro` 预期需要 `-keep class com.osfans.trime.actions.ocr.**`

模型资源不入库（体积大，第三方）：`app/src/main/ocr-assets/ocr/ppocr-v5/` 下的
`ch_PP-OCRv5_det_mobile.onnx`(4.8 MB)、`ch_PP-OCRv5_rec_mobile.onnx`(16.6 MB)、`ppocrv5-characters.txt`(74 KB)，
从 CyIME 仓库 `_cyime2/app/src/main/assets/ocr/ppocr-v5/` 拷入即可（Apache-2.0）。

## 三、还原办法

```powershell
# 一次性回到没有 OCR 的状态（保留其它功能）
git checkout b5d3a195 -- app/src/main/java/com/osfans/trime/ime/core/InputView.kt `
                          app/src/main/java/com/osfans/trime/ime/core/TrimeInputMethodService.kt
Remove-Item -Recurse app/src/main/java/com/osfans/trime/actions/ocr
Remove-Item -Recurse app/src/main/jni/bamboo_ocr
# 再手动回退 build.gradle.kts / libs.versions.toml / AndroidManifest.xml / CMakeLists.txt / CommonKeyboardActionListener.kt
```

只关掉入口而不删代码：把主题里指向 `BambooOcr` 的那一项去掉（`lib/keyboard_switch.lua` 的 `KS.order`、
`lib/preset_keys.lua` 的 `BambooOcr`），卡片与相机都不会再被创建。
