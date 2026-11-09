# 待做与已评估的想法（backlog）

> 这个文件用来记录「先记着、以后要做」的需求，以及评估过但暂不做的功能。
> 每条都写清楚：想要什么 / 现状 / 怎么做 / 工作量 / 风险。要做的时候直接从这里取。

---

## 1. 外接键盘：候选窗「贴底通栏 / 跟随光标」+ 可拖动的状态条

**来源**：用户 2026-11-08 提出，参照 CyIME 的外接键盘形态。

### 想要什么

1. 候选区可以 **跟随光标**（浮在光标下方，放不下翻上方）；
2. 候选区也可以 **贴底整宽通栏**（一行 9–10 个、带编号）；
3. 一个 **可以随意拖动的小状态条**（像电脑输入法的状态浮条），显示当前中英文 / 方案 / AI 模式。

### 现状（我方已有部分）

| 能力 | 现状 |
| --- | --- |
| 跟随光标 | **已有**：`ime/composition/PopupPosition.FOLLOW` + `CandidatesView.updateCursorAnchor()`，`TrimeInputMethodService.onUpdateCursorAnchorInfo`（:482-512）已在做坐标映射 |
| 四角固定 | **已有**：`BOTTOM_LEFT`（默认）/`BOTTOM_RIGHT`/`TOP_LEFT`/`TOP_RIGHT` |
| 贴底通栏 | **没有** |
| 可拖动状态条 | **没有** |
| 外接键盘时只显示候选窗 | **已有**：`PopupCandidatesMode.INPUT_DEVICE` + `InputDeviceManager`（`useVirtualKeyboard=false`、`useCandidatesView=true`） |
| 设置入口 | **已有**：`ui/main/settings/CandidatesSettingsFragment.kt`（11 行，直接绑 `AppPrefs.candidates`）+ `NavigationRoute.CandidatesWindow` |

`AppPrefs.Candidates`（`data/prefs/AppPrefs.kt:461-477`）现有键：`show_candidates_window`(MODE)、`candidates_layout`、`candidates_window_position`(POSITION)、`disable_window_on_landscape`、`reverse_vertical_candidates`。

### 怎么做

**第 1 步（小，约半天）候选窗加一档 + FOLLOW 避让**
- `PopupPosition` 新增 `BOTTOM_DOCKED`；`CandidatesView.updatePosition()` 里整宽贴底、水平排布、编号。
- 修 `CandidatesView.updatePosition()` 的 FOLLOW 分支。现在是：
  ```kotlin
  y = if (bottom + selfHeight > bottomLimit) top - selfHeight - spacingDp else bottom + spacingDp
  ```
  只有「放不下翻上方」。补两条：先试左右偏移再翻；不要压住光标所在那一行。

**第 2 步（中，1–2 天）状态条**
- 新建 `BambooStatusBar`，放在 **InputView 覆盖层**里（复用 OCR 卡片那套：`OcrCard` + `onComputeInsets` 触摸区）。
- 内容：`中/英 · 方案名 · AI 模式`；可拖动；比例坐标存 SharedPreferences；拖到底边 ±32dp 吸附贴底、左右贴边。
- 只在 `InputDeviceManager.useVirtualKeyboard == false` 时显示。

**第 3 步（小）设置项**
- `候选窗` 页加：位置档（含「贴底通栏」）、「跟随光标时避开输入行」、「显示状态条」、「状态条位置还原默认」。
- 复用现有 `BambooSwitch` + 行尾 `↺` 恢复按钮（`ui/common/PrefRestore.kt`）。

### 风险

1. `onComputeInsets` 已被 OCR 卡片改过（`getOcrCardTouchRegion`）—— 状态条要能叠加，别互相清空触摸区。
2. `TouchEventReceiverWindow`（PopupWindow 转发触摸）与状态条同时存在时可能抢焦点 → 状态条**不要**用 PopupWindow，直接放 InputView 里。
3. 横屏时随 `disable_window_on_landscape` 一起隐藏。
4. 拖动状态条必须 `onTouchEvent` 返回 `true` 吃掉事件，否则会带动下面键盘滑动。

### 刻意不做（相比 CyIME 砍掉）

- 工具条「三种宽度形态自适应」（≥232dp FULL / ≥184dp COMPACT / 更窄只剩切换键）
- 工具条竖排形态
- 「联想词归工具条、拼写候选归浮卡」这种双通道分工

---

## 2. T9 九键输入（评估结论：暂不做）

**问题**：能不能把 CyIME 的 T9 模型合并进来，用于万象方案？

**CyIME 的 T9 真相**（源码 `C:\Users\Administrator\Desktop\Ai_Work\_cyime2\app\src\main\jni\librime-t9`）：

- T9 **不是一个模型，是一整个 librime C++ 插件**：约 50 个源文件 / 600 KB+，核心 `t9_processor.cc`(71 KB)、`t9_letter_buffer_strategy.cc`(44 KB)、`t9_undo_model.cc`(45 KB)、`t9_right_commit_handler.cc`(29 KB)、`t9_filter.cc`(17 KB)、`t9_ngram_model.cc`(12 KB)、`t9_sentence_scorer.cc`(9 KB)、`pinyin_syllable_selection.h`(27 KB)。它注册了 `t9_processor` / `t9_filter` / `t9_date_translator` / `t9_english_translator` 四个扩展点。
- 依赖 CyIME 自己的 librime fork（`https://github.com/ximeiorg/librime.git`），我们用的是 LyraIME 的 librime + `librime-lua` / `librime-octagram` / `librime-predict`，**内部 API 不一致**，移植要逐个文件对版本。
- 那个 `assets/rime_chinese/t9_sentence.onnx`（33.66 MB，`t9_sentence.vocab` 128 KB）是**整句重排打分器**：UER-py Chinese RoBERTa L=4/H=512 的 masked-position log-prob 头，导出 ONNX opset 17 + INT8 量化，Apache-2.0。
- **但它现在是关着的**：`t9_pinyin.schema.yaml` 里明确写着「停顿后的长句模型重排已停用（2026-10-09 回放：首选只多对 3/488 字，同时拖慢逐键解码）」，恢复方法是加回 `sentence_model: t9_sentence`。也就是说 CyIME 自己都不用这个 onnx。
- 方案侧另有 `cylm`（CyLM 语言模型，仅「装到我的设备」包含）与 `t9.ngram_model: t9_ngram`（词二元模型），都是可选文件，缺了自动退回原算法。
- 它的 `grammar:` 段用的就是**万象**的 `wanxiang-lts-zh-hans`（amzxyz/RIME-LMDG, CC-BY-4.0）—— 这部分我们已经有（`librime-octagram`）。

**结论**：

| 目标 | 可行性 | 代价 |
| --- | --- | --- |
| 只想「九键能打万象」 | 可行，**零 C++** | 抄一份万象 schema，改 `speller/algebra` 把数字 derive 成字母（CyIME 里就那 8 行 `derive/[abc]/2/` …），1–2 天；但只有 rime 原生九键，没有 CyIME 的数字语法对齐 / 左右选区 / undo / 英文数字后缀 |
| 想要 CyIME 级九键 | 要移植 `librime-t9` 整个插件 | 1–2 周，且要跟我们的 librime 版本对齐，风险高 |
| 单独搬 `t9_sentence.onnx` | **不建议** | CyIME 自己关着；还要 vocab + ONNX 胶水 + 重排逻辑 |

**如果以后要做**：先做「纯 schema 九键」验证有没有人用，再决定是否移植插件。

---

## 3. 剪贴板增强（合并 CyIME 的分组 / 类型分类 / 敏感过期）

**来源**：用户 2026-11-09 提出，看完 CyIME 剪贴板全部功能后决定「先记着，暂时不做」。
**详细方案已单独成文：`docs/rime-actions-port/CLIPBOARD-PLAN.md`**（现状盘点 / CyIME 能力清单 / 三期计划 / 注意事项）。
本节只留结论：

- **P1（半天，不动数据库）**：内容类型筛选（链接/电话/邮箱/地址）、左右滑动切分类、把「提取规则」接上（`ClipboardExtractor` 目前是死代码）、验证码本地识别。
- **P2（1 天，Room 迁移）**：`searchKey` 拼音首字母搜索、保留时间（按天过期）、条数上限改下拉、自定义分组。
- **P3（1.5 天，可缓）**：敏感条目自动识别 + 1 小时过期、导出/导入、图片容量与保留。
- **刻意不做**：CyIME 的设备配对/Windows 邻近/扫码/安全传输整套（我们已有 SyncClipboard、WebDAV、S3 三后端）；短信验证码接收（要 `RECEIVE_SMS` 权限）。

**动手前要先定**：类型筛选的 UI 方案（两行 tab / 只留内容类型 / 漏斗按钮三选一）；条数上限口径（建议「条数 + 天数」，字节上限只作内部保护）。

---

## 4. 其它评估过、先不做的

| 功能 | CyIME 的做法 | 我方现状 | 判断 |
| --- | --- | --- | --- |
| 悬浮工具条候选 | `HardwareKeyboardToolbar`（Compose，可拖、三档宽度、竖排） | 无 | 已在上面第 1 条里降级实现 |
| 拼音滑行输入（glide） | `glide/T9Glide.kt` + `assets/glide_pinyin.txt`(317 KB) | 无 | 有意思，但精度与手势冲突都不好调，暂不做 |
| 联想 / 联系人建议 | `association/lccc-base-v1.bin`(2.3 MB) + `contacts/ContactIndex.kt` | 无 | 中等价值；联系人建议对中文输入法偏鸡肋 |
| 剪贴板历史 + 搜索 | `clipboard/db` Room + `ClipboardSearch` | 只有系统剪贴板窗 + 跨设备同步 DAO | 可做，但和现有剪贴板窗要合并 UI |
| 打符号名出符号 | `opencc/cyime_symbols.json` + `simplifier@cyime_symbols` | 有 OpenCC 基础设施 | 便宜（纯 schema + 词表），可做 |
| 简繁 / 台湾正体 | `opencc/cyime_s2tw.json` + `simplifier@traditionalize` | 有 `OpenCCDictManager` | 便宜，可做 |
| 日期/时间/农历翻译器 | `t9_date_translator.cc`(C++) | 万象方案里通常自带 `date_translator` | 用 schema 版即可，不必写 C++ |
| 公式识别（手写转 LaTeX） | `InkSeq2SeqModel` + katex | 无 | 低优先级 |
| 语音输入 | sherpa-onnx | **已有**（`KeywordSpotter` / 语音设置页） | 跳过 |
| 输入统计 | 有 | **已有**（`InputStatsFragment`） | 跳过 |
| 游戏键盘（按键直通） | `gamePassthrough` | 无 | 受众小 |
