# 主题可视化编辑器 —— 需求与现状（临时基线）

> **本文件的用途**：这是一份写给「其他 AI Agent / 其他开发者」的交接文档。目标是让对方**不读代码也能理解**：我们被要求做什么、做到哪一步、每一处改动为什么这么改、以及哪些路已经试过并失败了（避免重复踩坑）。
>
> **临时声明**：本次推送（分支 `theme-editor`、release `v3.3.1-theme-editor`）是临时基线，**后续会被删除**。删除时只需删分支与 release，不影响 `lyraime` 主干。
>
> 项目身份：苍竹输入法 / BambooIME（`applicationId: com.bambooime`），派生自 LyraIME（Trime 5.5.2 分支）。Kotlin 代码仍位于 `com.osfans.trime` 命名空间下（仅包名，与显示名无关）。
>
> 最后更新：2026-11-17

---

## 0. 先读这一段

如果你只有 10 分钟，读这三节即可接手：

1. **§1 目标** —— 我们到底要做什么。
2. **§7 已知问题与失败记录** —— 已经试错的四条路，别再走一遍。
3. **§8 现状表 + §9 下一步** —— 现在缺什么。

技术栈速览：

| 层 | 技术 | 位置 |
|---|---|---|
| 输入法核心 | librime（C++，子模块） | `app/src/main/jni/librime` |
| JNI 桥 | C++ + Kotlin（`RimeApi`） | `app/src/main/jni/*`、`data/Rime*.kt` |
| 应用层 | Kotlin + Android View 体系（**非 Compose**） | `app/src/main/java/com/osfans/trime/` |
| 主题 | **Lua 脚本**（不是同文 yaml），沙箱执行后序列化成 JSON | `theme/`、`jni/lua_theme_jni/` |
| 设置页 | `androidx.preference`（部分页面已改为纯 Fragment 手写布局） | `ui/main/settings/` |

---

## 1. 目标（一句话）

> 让用户能在 App 内**可视化**地修改当前键盘主题（配色、尺寸、字号、候选栏、阴影、单个按键），**所见即所得**，并能把改好的主题导出给其他人用。

核心约束（来自用户）：

- 预览必须「和真机虚拟键盘长得一样」，不是示意图。
- 只有改了某项设置，对应部分才变化；没改的部分必须与真机完全一致。
- 不能为了做预览而改动真机的显示效果。

---

## 2. 需求原文（按时间顺序）

保留原话，便于理解取舍。分段标题是本文件作者加的。

### 2.1 最初需求（主题编辑器）

> 加一个功能用于配置主题的样式和显示效果……需要可视化设计的同时我们随时可以看到变更后的效果。另外重新设计好的主题可以导出，并给其它用户直接使用。

### 2.2 第一轮返工（布局）

> 1. 现在看不到预览的键盘。
> 2. 布局/设置项要像普通设置页从上排到下，那用户滑到下面设置、还得滑回去看效果。
> 3. 设置页上方当预览区，预览区固定住不会被滑动消失。
> 4. 预览区下方是一条可横向滑动的选项卡条，把「配色」「键盘」「字号」「候选栏」等分类当 tab。
> 5. 点某个 tab 后下方显示对应设置项，下方是可独立上下滑动的区域……每个设置项上下间距不要太大、字号不要这么大；颜色设置点开后最好显示可滑动取色的色条或圆形取色盘。
> 6. 缺少阴影的颜色设置和阴影大小。
> 7. 要有审美，像设计商业网站一样合理规划各元素大小位置排列。

### 2.3 第二轮返工（对齐真机）

> 你这次改动反而让显示和上次比更不对了。你不应该每次说完就去改动些什么，而是要想着是哪里和原本「虚拟键盘」的显示上哪里有区别，**从而直接拿过来用**。而不是修修改改。

### 2.4 第三轮（单键编辑 + 交互）

> 1. 增加一个选项卡，这个是当我们点击了某个按键后，这个显示卡下面的配置区显示的就是可以配置的所有选项，可以包含，配色，字号……只是说明它能够设置的项就在那里显示。
> 2. 另外，要在显示上 1:1 对齐的话，也要能够像正常「虚拟键盘」那样能够点击，能够长按。只是背后的输入相关的逻辑不需要。

### 2.5 第四轮（模块一一对应）

> 认真的核对原本 APP 显示出这个「虚拟键盘」用到了什么模块，模块里的所有相关的都要**建一个一一对应的实例**。

### 2.6 第五轮（空白事故后）

> 现在更不对了都没有显示了，预览区看不到键盘了。你现在重新回溯下我的需求，以及重读一下我给你发的 md，重新思考下到底是什么问题。该怎么实现。

---

## 3. 架构：真机键盘 vs 编辑器预览

### 3.1 真机显示链路（事实，已核对）

```
Theme(JSON)                      ← 由 Lua 主题渲染而来（见 §5.1）
   │
   ├─ ColorManager               （全局单例：配色解析 + Drawable 缓存）
   ├─ FontManager                （全局单例：字体）
   └─ Keyboard(context, theme, selfConfig: TextKeyboard?)
         │  recalculateLayout(width)  → 算出每个 Key 的 x/y/width/height/edgeFlags
         ▼
      KeyboardView(context, theme, keyboard, popup, service, listener, enterKeyDisplay, sidebar)
         │  buildKeyViews() → 每个 Key 一个 KeyView
         ▼
      KeyView.onDraw
         ├─ drawBackground()  → 已抽离为 KeyRenderer.renderBackground(...)
         └─ KeyRenderer.renderText(KeyRenderCtx)   ← 文字/符号/图标/基线/对齐
         ▼
      InputView（外层：back_color 底板、候选栏 CandidatesView、工具栏）
```

关键事实：

| 事实 | 证据 |
|---|---|
| `Keyboard` 是纯数据类，**不依赖 service** | `Keyboard.kt`：`class Keyboard(private val context: Context, private val theme: Theme, selfConfig: TextKeyboard? = null)` |
| `Keyboard` 构造会读全局 `KeyboardPending.containerWidth`，故**非纯函数** | `Keyboard.kt` 的 `allowedWidth` 计算 |
| 任意一排 `width` 权重和 > 1.0 会**中止整块键盘**（表现为全白，且无提示） | `Keyboard.kt`：`Timber.e("Row %d: key width weights sum to %.3f, must be <= 1.0")` 后 `return@run` |
| `KeyboardView` 构造**强制**要求非空 `TrimeInputMethodService` | `KeyboardView.kt` 构造签名 |
| `KeyView` 的**绘制**不碰 service（只有触摸会） | `KeyView.onDraw` → `drawBackground` + `KeyRenderer.renderText` |
| 键的文字在 `label` 为空时**从 `KeyAction` 回退取**（字母来自 KeyAction，不是 key.label） | `Key.getLabelSegments()` |
| `Key` 上 `keyBackColor/keyTextColor/...` 是 **private**（`ResettableLazy`），只有 `keyBorderColor: String?` 公开 | `Key.kt` |
| 颜色解析顺序：`activeColorScheme.colors[key]` → `theme.fallbackColors[key]` → `BuiltinFallbackColors[key]` → **抛 `IllegalArgumentException("$key not found")`** | `ColorManager.resolveValue` |
| `ColorManager.switchTheme()` 会 `evictAll()` 三处缓存（bitmap / gradientDrawable / color） | `ColorManager.kt` |

### 3.2 编辑器预览链路（当前实现）

```
ThemeStyleFragment（整屏 Fragment，手写布局）
   │  持有 ThemeDraft（style + schemes + keyboardPatches）
   ▼
ThemePreviewView（自绘 View）
   │  Keyboard(context, draftTheme, selfConfig=按 patch 重建的 TextKeyboard)
   │    ↑ 独立实例、独立宽度重算（不复用真机那个 Keyboard）
   ├─ 底色/描边：自己按草稿配色解析后 drawRoundRect
   └─ 文字层：调用同一个 KeyRenderer.renderText(KeyRenderCtx)
```

### 3.3 「同源程度」现状（重要）

| 层 | 是否与真机同源 | 说明 |
|---|---|---|
| 键盘几何（x/y/width/height/间距/padding） | ✅ 同源 | 都用 `Keyboard` 算，padding 公式照抄 `KeyboardView` |
| 文字/符号/图标/基线/对齐 | ✅ 同源 | 都是 `KeyRenderer.renderText` |
| 背景/描边/圆角/阴影 | ❌ 未同源 | 预览自己画（因为没有隔离的取色上下文，见 §7.3） |
| 配色解析 | ❌ 未同源 | 预览用「草稿配色自己解析」，真机用 `ColorManager` |
| 候选栏/工具栏/底板 | ❌ 未实现 | 只有键区 |

**这就是当前最大缺口**：`ThemeRenderContext` 还没抽出来（§9 阶段 1）。

---

## 4. 文件清单

### 4.1 新增文件

| 路径 | 行数 | 职责 |
|---|---|---|
| `app/src/main/java/com/osfans/trime/ime/keyboard/KeyRenderer.kt` | 528 | **真机与预览共用的绘制核心**：`KeyRenderCtx`（参数容器）+ `KeyRenderer.renderText()`（文字/符号/提示/图标/四栏对齐/三套基线）+ `KeyRenderer.renderBackground()`（键底 + 阴影） |
| `app/src/main/java/com/osfans/trime/ui/settings/theme/ThemePreviewView.kt` | 489 | 预览视图：按草稿重建 `Keyboard`、FIT/CROP 缩放、触摸命中、按下态、长按气泡、选中描边、**绘制异常可视化** |
| `app/src/main/java/com/osfans/trime/ui/settings/theme/ThemeDraft.kt` | 87 | 草稿数据模型：`style` + `schemes` + `patches: Map<KeyPath, Map<String, JsonElement?>>`；`KeyPath(keyboard,row,column)`；`patchedKeyboard()` / `keyboardJsonFor()` |
| `app/src/main/java/com/osfans/trime/ui/settings/theme/ColorPickerView.kt` | 159 | 取色器：横向色相条 + 饱和度/明度方块 |
| `app/src/main/java/com/osfans/trime/ui/settings/theme/ColorPickerDialog.kt` | 198 | 取色对话框：色相条 + 透明度 + `#AARRGGBB` 输入 + 12 预设色 |
| `app/src/main/java/com/osfans/trime/ui/main/settings/ThemeStyleFragment.kt` | 1080 | 「主题样式」整屏页面：固定预览 + chips + 6 个 tab + 应用/撤销/恢复出厂 |
| `app/src/main/java/com/osfans/trime/data/theme/BambooThemeOverrides.kt` | 67 | 覆盖层读写：`userDataBaseDir/bamboo/theme-overrides/<themeId>.json`，深合并进主题 JSON |
| `app/src/main/java/com/osfans/trime/data/prefs/ThemeStylePrefs.kt` | 39 | 编辑器自身偏好（预览高度/模式/选中的键盘与配色） |
| `app/src/main/res/layout/fragment_bamboo_theme_style.xml` | — | 页面骨架：header / preview_box / chips_scroll / handle / tabs_scroll / content_scroll |
| `app/src/main/res/drawable/bg_theme_style_*.xml`（6 个） | — | 按钮、chip、卡片、拖动把手背景 |

### 4.2 修改的上游文件（每处都说明原因）

| 文件 | 改动 | 为什么 |
|---|---|---|
| `ime/keyboard/KeyView.kt` | `onDraw` 里把 `drawSymbolSegments/drawLabelSegments/drawSegmentsImpl/segmentsToRichTextLines/calculateTextPosition/drawRichText/drawRichTextLine/drawIconAt` 全部删掉，改为组装 `KeyRenderCtx` 调 `KeyRenderer.renderText`；`drawBackground` 改为委托 `KeyRenderer.renderBackground` | 目标是「预览与真机共用同一份绘制代码」。删完 974 行 → 493 行 |
| `data/theme/ThemeManager.kt` | ①`loadThemeFromLua` 里把主题 JSON 与覆盖层深合并；②`themeCache` 的 key 混入覆盖层 mtime | 让「覆盖层」生效且改了立刻能重载（否则同 id 命中旧缓存） |
| `ui/main/NavigationRoute.kt` | 新增 `@Serializable data object ThemeStyle : NavigationRoute()` + `fragment<ThemeStyleFragment, ThemeStyle> { label = "主题样式" }` | 导航入口。**注意：`data object` 必须加 `@Serializable`**，否则 `MainActivity.onCreate` 抛 `Serializer for class 'X' is not found`，输入法一起崩 |
| `ui/main/MainFragment.kt` | 在「输入增强」之后加一行入口 | 用户要求一级入口 |
| `app/build.gradle.kts` | 资产目录 `assets.srcDir(...)`：`ocr-assets`、`wenquxing-assets`、`glossary-assets`、`schema-assets` | 让仓库 clone 后能直接构建（这些目录原本被 `.git/info/exclude` 排除） |

---

## 5. 关键实现（逐模块）

### 5.1 主题加载与覆盖层

**主题本质**：`theme/*.lua` 是脚本，执行后返回一张 table；`jni/lua_theme_jni/lua_sandbox.cc` 的 `load_and_serialize` 用 `lua_to_json` 序列化成 JSON，再 `Theme.json.decodeFromString<Theme>`。

**`Theme.json` 配置**（`data/theme/Theme.kt`）：

```kotlin
Json {
    namingStrategy = JsonNamingStrategy.SnakeCase   // key_back_color ↔ keyBackColor
    ignoreUnknownKeys = true
    coerceInputValues = true
}
```

**覆盖层实现**（`ThemeManager.loadThemeFromLua`）：

```kotlin
val baseObj = Theme.json.parseToJsonElement(jsonStr).jsonObject
val mergedObj = BambooThemeOverrides.merged(id, baseObj)   // ← 我们插的这一行
val sorted = sortJsonKeys(mergedObj)                       // 上游原逻辑
```

`BambooThemeOverrides.merge(base, override)` 语义：

- 两边都是对象 → 递归合并
- 其它情况（数组/标量）→ **整体替换**
- 覆盖层里值为 `null` 的键 → 视为「删除该键」，回退给基线

**缓存**：`themeCache[id]` 的 key 是 `file.lastModified() xor BambooThemeOverrides.stampOf(id)`。**这两个 mtime 必须一起混入**，否则改覆盖层不会重载。

**覆盖层文件位置**：`userDataBaseDir/bamboo/theme-overrides/<themeId>.json`
> ⚠️ 不能放 `themes/` 目录下：`DataManager` 会遍历该目录取 `latestMtime` 判断是否需要重跑 Lua，放进去会误触发主题重渲染。

### 5.2 `KeyRenderer`（最重要的一块）

`KeyRenderCtx` 字段（节选，完整见源码）：

```kotlin
class KeyRenderCtx(
    val context: Context, val canvas: Canvas,
    val key: Key, val keyboard: Keyboard,
    val width: Float, val height: Float,               // 键的完整占位（含 extraWidth）
    val paddingLeft: Int, val paddingTop: Int, val paddingRight: Int, val paddingBottom: Int,
    val dp: (Float) -> Float, val sp: (Float) -> Float, // 单位换算（sp 含 fontScale）
    val textPaint: TextPaint, val symbolPaint: Paint, val richTextPaint: TextPaint,
    val iconCache: LruCache<String, IconicsDrawable>,
    val textColor: Int, val symbolColor: Int,
    val showSymbols: Boolean, val showHints: Boolean,
    val enterLabel: String, val schemaName: String,
    val keyTextSizeSp: Float, val keyLongTextSizeSp: Float,
    val symbolTextSizeSp: Float, val hintTextSizeSp: Float,
    val colorResolver: (String, Int) -> Int,           // 段内颜色覆盖（如 icon 段自带 color）
    val baseTypeface: Typeface, val boldTypeface: Typeface,
)
```

三条不变量（违反就会错位）：

1. **`width/height` 是「含 extraWidth 的完整占位」**，键内容按 `paddingLeft..width-paddingRight` 排版；不是 `key.width`。
2. **单位**：`Keyboard` 里的 `horizontalGap / verticalGap / keyboardPaddingTop / extraWidth*` **已经是像素**，不能再乘 density；字号必须走 SP 路径（`applyDimension(COMPLEX_UNIT_SP, size, metrics)`）。
3. **基线**（照抄真机 `KeyView.calculateTextPosition`）：
   - `TOP` → `paddingTop - fontMetrics.top + sp(offsetY)`
   - `BOTTOM` → `height - paddingBottom - fontMetrics.bottom + sp(offsetY)`
   - `CENTER` → `centerY + -(ascent+descent)/2 - (totalHeight - baseLineHeight)/2`

排版：一段 `label` 可含多个 `seg`（`LabelSegment`），每个 `seg` 有 `align`（`LEFT / CENTER / RIGHT / JUSTIFY`）；`JUSTIFY` 用「剩余宽度 / (段数+1)」均分留白。`label` 为空时回退到 `keyActions[CLICK].labelSegments`（字母键就是这种情况）。

特殊段：`enter_labels` → 替换成「回车」；`schema_name` → 替换当前方案名；`ic@xxx` → 用 `IconicsDrawable` 画图标（`sizePx = 文字大小`，`iconOffsetYPx = 2`，`colorFilter = PorterDuffColorFilter(color, SRC_IN)`）。

### 5.3 `ThemePreviewView` 的几何

```kotlin
// 参考宽度：与真机竖屏同源
val referenceWidth = resources.displayMetrics.widthPixels - 2 * keyboardPadding
kb = Keyboard(context, draftTheme, selfConfig)      // 独立实例
kb.recalculateLayout(referenceWidth)

// 绘制时
scale = if (mode == FIT) min(w / kb.minWidth, h / kb.height) else max(...)
translate((w - kb.minWidth * scale) / 2, (h - kb.height * scale) / 2)
scale(scale, scale)

// 每个键
translate(key.x - key.extraWidthLeft, key.y)
width  = key.width + extraWidthLeft + extraWidthRight
padL   = gapH / 2 + extraWidthLeft
padT   = if (edgeFlags & EDGE_TOP != 0) max(keyboardPaddingTop, gapV / 2) else gapV / 2
padR   = gapH / 2 + extraWidthRight
padB   = if (edgeFlags & EDGE_BOTTOM == 0) gapV / 2 else 0
```

命中检测（点击选键）用同一套坐标反算：

```kotlin
cx = (x - drawTx) / drawScale
cy = (y - drawTy) / drawScale
// 再遍历 keys，用 (key.x - extraWidthLeft) .. (+width+extra) 判定；spacer 跳过
```

### 5.4 单键 patch（`ThemeDraft`）

为什么需要它：`TextKey` 的颜色字段（`key_back_color` 等）在 `Key` 上是 private，且 `Keyboard` 只在构造时读一次，**改内存对象不会重排**。所以单键修改走「重建一份 TextKeyboard」：

```kotlin
data class KeyPath(val keyboard: String, val row: Int, val column: Int)  // column 含 spacer

// patches[path][field] = JsonElement 或者 null（null = 删除该字段 = 恢复继承）
fun patchedKeyboard(base: TextKeyboard): TextKeyboard {
    val json = Theme.json.encodeToJsonElement(TextKeyboard.serializer(), base)
    // 逐条 patch 到 json 里（rows 是数组，缺项会被解析成空行，所以必须整份写回）
    return Theme.json.decodeFromJsonElement(TextKeyboard.serializer(), patched)
}
```

### 5.5 「主题样式」页面结构

```
LinearLayout(vertical)
├── header(40dp)          : 标题（`themeId · schemeId`）+ 撤销 + 应用 + ⋯
├── preview_box(默认200dp) : ThemePreviewView（**固定，不随下方滚动**）
├── chips_scroll(38dp)     : 键盘切换 26/18/14/9/Pro/小鹤/七键 + 配色 + 等比/1:1 + 按下态
├── handle(18dp)          : 拖动条（96–420dp，双击复位，拖过 80dp 折叠到 56dp）
├── tabs_scroll(42dp)     : 配色 / 键盘 / 字号 / 候选栏 / 阴影 / 按键
└── content_scroll(weight=1) : 当前 tab 的设置项（独立滚动）
```

行控件三种：`colorRow`（44dp 行高 + `#RRGGBB` + 色块）、`sliderRow`（标题 + SeekBar）、`multiChipsRow`。

「应用」做的事：

```kotlin
BambooThemeOverrides.write(themeId, json)      // {"style": ..., "preset_color_schemes": [...], "preset_keyboards": {...}}
ThemeManager.selectTheme(themeId, forceReload = true)
```

### 5.6 取色器

`ColorPickerView`：横向 7 色色相条 + 饱和度/明度方块（底色 = 当前色相的全饱和色，叠「白→透明」与「透明→黑」两层），`var color: Int` 的 setter 保留 HSV 分量以避免抖动。

`ColorPickerDialog`：色相条 + 透明度 SeekBar + `#AARRGGBB` 输入框（双向同步）+ 12 个预设色 + 取消/确定。

---

## 6. 数据流

```
用户改一个值
   ↓
ThemeStyleFragment 修改 draft.style / draft.schemes / draft.patches（入撤销栈）
   ↓
preview.setDraft(draft)  → rebuild Keyboard（草稿 Theme）→ invalidate
   ↓
点「应用」
   ↓
BambooThemeOverrides.write(themeId, {style, preset_color_schemes, preset_keyboards})
   ↓
ThemeManager.selectTheme(id, forceReload = true)   → 真机整棵 InputView 重建（可见变化）
   ↓
下次进页面 / 清缓存后：loadThemeFromLua 读 JSON → 深合并 override → decode
```

---

## 7. 已知问题与失败记录（**必读**）

这一节记录了 4 次返工的根因，请勿重复。

### 7.1 单位错误：sp 当 px 用（改了不生效）

`bodyTextSize()` 返回 **sp**，而 `Paint.textSize` 需要 **px**。直接把 sp 塞进去 → 量出来的字只有实际 1/3 大 → 行数永远算不够 → 截断永不触发。
**修法**：`TypedValue.applyDimension(COMPLEX_UNIT_SP, size, resources.displayMetrics)`。

### 7.2 跨配色兜底：把别的配色的颜色借来用（颜色错）

早期取色链多了一条真机没有的兜底：

```kotlin
?: schemes.firstNotNullOfOrNull { it.colors[key] }   // ← 错
```

后果：`back_color` / `key_text_color` 这类「当前配色自己有」的键**看起来正常**，但一旦当前配色缺失就会被别的配色顶替；而 per-key 的色键名（`zmu_q` 等）又依赖它才能找回 → 于是出现「每键底色对、底板与文字色错」的诡异组合。

真机语义只有一条：`当前配色 → theme.fallback_colors → 内置兜底表`。

### 7.3 临时切全局 `ColorManager`（**最严重，直接把预览变成空白**）

曾试过在 `onDraw` 里：

```kotlin
ColorManager.switchTheme(draftTheme(), suppressFireChange = true)
try { ...画... } finally { ColorManager.switchTheme(liveTheme) }
```

结果：**预览整个空白**。

原因：`ColorManager.switchTheme()` 每次都 `bitmapCache.evictAll()` + `gradientDrawableCache.evictAll()` + `colorCache.evictAll()`；而 `Key` 的颜色/Drawable 是 `ResettableLazy` 缓存的。每帧切两次 → 帧内缓存代际错乱 → `getBackgroundDrawable()` 拿到失效对象 → 一个键都画不出。

**这条被架构文档 §3.1 / §16.2 明令禁止**（"预览不能通过临时篡改全局正式主题状态来实现"），我们违反后才出事。

### 7.4 一轮改太多事（流程问题）

抽 `renderBackground` + 改取色来源 + 重写 `onDraw` 同时进行 → 任何一处炸都表现为「空白」，无法定位。
**结论：一次只改一件事，并且失败必须可见**（现在 `onDraw` 已包 `runCatching`，异常直接画在预览左上角）。

### 7.5 其他坑

| 坑 | 现象 | 修法 |
|---|---|---|
| `Keyboard` 任意一排宽度和 > 1.0 | 整块键盘空白，logcat 里才有 `Row %d: key width weights sum to %.3f` | 改主题几何后必须自查每排 `Σ ≤ 1.0` |
| `Theme.presetKeyboards` 永远是空 map | 预览报「没有可预览的键盘」 | `loadThemeFromLua` 会把 `preset_keyboards` 抽进 `keyboardJsons` 并置空 → 必须用 `ThemeManager.getKeyboard(name)` |
| `NavigationRoute` 新增 `data object` 不加 `@Serializable` | `MainActivity.onCreate` 抛 `Serializer for class 'X' is not found`，输入法一起崩 | 加注解 |
| `FrameLayout` 子类里裸写 `LayoutParams` | `ClassCastException` | 显式写 `LinearLayout.LayoutParams` / `FrameLayout.LayoutParams` |
| `IconicsDrawable` 的 `sizePx` | 不是普通方法而是扩展属性 | `sizePx = px`；颜色用 `colorFilter = PorterDuffColorFilter(...)` |
| 主题 `.ps1` 脚本丢 BOM | PowerShell 5.1 按 ANSI 解析，中文注释把数组项吃掉 | 含中文的 `.ps1` 必须 **UTF-8 with BOM + CRLF** |

---

## 8. 现状表

| # | 功能 | 状态 |
|---|---|---|
| 1 | 固定预览区（不随下方滚动） | ✅ 完成 |
| 2 | 预览区可拖动改高度（96–420dp，双击复位，折叠态） | ✅ 完成 |
| 3 | 预览显示模式：等比 / 1:1 裁剪 | ✅ 完成 |
| 4 | 键盘 chips：26/18/14/9/Pro/小鹤/七键 | ✅ 完成 |
| 5 | 6 个 tab：配色 / 键盘 / 字号 / 候选栏 / 阴影 / 按键 | ✅ 完成 |
| 6 | 取色器（色相条 + 明度方块 + 透明度 + 手输 hex + 预设） | ✅ 完成 |
| 7 | 每键底色正确（`zmu_*` 色键名解析） | ✅ 完成 |
| 8 | 预览键可点击 → 切到「按键」tab 显示该键全部配置项 | ⚠️ 有代码但未验收（`onKeyTapped`） |
| 9 | 长按显示 `popup` 气泡（只读） | ⚠️ 有代码但未验收 |
| 10 | 单键 patch 写入覆盖层（`preset_keyboards`） | ⚠️ 有代码但未验收 |
| 11 | 撤销 / 恢复出厂 / 导出（导出为占位） | ⚠️ 部分完成 |
| 12 | 每项独立 ↺ 复位 | ❌ 未做（用户提过两次） |
| 13 | 预览与真机「同源」（背景/配色层） | ❌ 未做（§9 阶段 1–2） |
| 14 | 候选栏 / 工具栏 / 底板进预览 | ❌ 未做 |
| 15 | `.bbt` 完整主题包导出 | ❌ 未做（当前覆盖层只能叫「补丁」） |
| 16 | 导出独立 `.lua`（给同文生态） | ❌ 未做 |

---

## 9. 下一步路线（按架构文档 §2.1 / §20）

> 依据：`LyraIME_主题可视化编辑器_架构改造与开发建议.md`（用户提供，1298 行）。核心原则：「不要把『预览看起来像真实键盘』作为最终目标，而应把『预览和真实键盘使用同一套布局与绘制实现』作为架构目标」。

| 阶段 | 内容 | 验收 |
|---|---|---|
| **1** | 抽 `ThemeRenderContext` 接口：`color(key, fallback)` / `drawable(key)` / `typeface()`。`LiveThemeRenderContext` 内部仍调 `ColorManager`（**真机行为不变**）；`DraftThemeRenderContext` 按「草稿配色 → fallback_colors → 内置表」自己解析，缓存 key = `themeId + revision + schemeId`，**永不触碰 `ColorManager`** | 真机键盘外观零变化 |
| **2** | `KeyRenderer` / `Key` 的背景与颜色改为从 ctx 取（不再 `key.getBackgroundDrawable()` 读全局）→ 预览与真机真正同一份代码 | 并排截图肉眼一致 |
| **3** | Bitmap 像素比对测试（同 Theme/Keyboard/宽高，`KeyView` 渲染 vs 预览渲染，允许 0 像素差） | 从「我觉得像」变成「测试断言」 |
| **4** | 候选栏 / 工具栏 / 底板进预览；单键编辑细化；每项 ↺；`.bbt` 完整包导出 | 逐项对表 |

阶段 2 需要给 `Key.kt` 增加「从 ctx 取色」的入口（如 `getTextColor(ctx)`），属于对上游的最小抽离——架构文档 §16.1 明确允许。

---

## 10. 构建与验证

### 10.1 环境

| 项 | 值 |
|---|---|
| JDK | Temurin 17（`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.1-hotspot`） |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`（platform 37 / build-tools 37.0.0 / ndk 29.0.14206865 / cmake 4.1.2） |
| Gradle | 9.8.2（wrapper 已预置） |
| 子模块 | 13 个，见 `SUBMODULES.txt`（**clone 后必须 `git submodule update --init`**） |
| 本地补丁 | librime 有 1 个未提交改动（`RimeCandidate.char* type`），见 `docs/upstream-patches/` |

### 10.2 命令

```powershell
cd repo
. ..\build\lyraime-env.ps1                       # JAVA_HOME / ANDROID_HOME
$env:LYRAIME_DL_CACHE="C:\Users\Administrator\Downloads\lyraime-build-env"

.\gradlew.bat --console=plain :app:compileDebugKotlin     # 快速语法检查
.\gradlew.bat --console=plain :app:assembleDebug          # debug 包（~130 MB）
.\gradlew.bat --console=plain :app:assembleRelease        # release 包（~88 MB）

# 错误输出到文件再读（GBK 控制台会截断中文）
.\gradlew.bat --console=plain :app:compileDebugKotlin 2>&1 |
    Select-String "^e: " | Out-File -Encoding utf8 ..\scratch\err.txt
```

### 10.3 验证清单

1. 装 debug 包 → 设置 → 「主题样式」→ 预览是否显示键盘；
2. 改一个配色 → 预览立刻变化 → 点「应用」→ 真机键盘同步变化；
3. 切主题（别处改）→ 回到本页 → 预览应随之更新；
4. 删除 `Documents/BambooIME[Debug]/bamboo/theme-overrides/<id>.json` → 回到出厂外观；
5. 若预览空白：**截图左上角红字**（那就是异常信息）。

---

## 11. 给后续 Agent 的提示

1. **别猜，去读真机代码**。所有几何/基线/字号/对齐规则都在 `Keyboard.kt`、`KeyboardView.kt`、`KeyView.kt` 里，抄它们，不要自己推导。
2. **一次只改一件事**，每改一次就编译 + 出包 + 让人验。
3. **失败要可见**：`ThemePreviewView.onDraw` 外部已包 `runCatching`，如果你在里面加了新代码，请保持这个结构。
4. **不要动全局单例**（`ColorManager` / `FontManager` 的 active 状态）。要隔离就抽接口（§9 阶段 1）。
5. **改主题几何后必须自查**：每排宽度和 ≤ 1.0、行高和 ≤ 1.0，否则整块键盘空白且无提示。
6. 本项目所有自建设置页都遵循同一套视觉规范（行高 44dp、标题 14sp、值 12sp、圆角 8dp、强调色走 `SettingsPalette`），新页面请保持一致。
