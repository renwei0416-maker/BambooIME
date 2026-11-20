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

## 5. 候选旁「译词」（学英语用，用户 2026-11-10 决定先记着、暂不做）

- 数据来自青简 `qingjian-team/qingjian`（GPL-3.0-or-later）：`assets/glossary/glossary-en.tsv`，6,556,053 B / 232,213 条，
  格式 `词\t[词性. ]译词\t…`；同目录另有 `glossary-zh.tsv`(980,956 B，英→中，约 4.5 万词) 与日/西/越/德四张表。
- 显示位就是我们候选的 `comment`（`core/RimeProto.kt:14`、`ime/candidates/CandidateItemUi.kt:268`），
  主题已有 `comment_text_color` / `comment_size` / `commentPosition = RIGHT|TOP|OVERLAY`，显示端基本不用改。
- 关键取舍：**不要全量 HashMap**（23 万条在 Kotlin 下约 25–40 MB 堆）；走 mmap + 二分/哈希索引，
  或仿青简 `.qj`（字符串 arena + 定长表 + 文件内哈希索引），常驻内存≈0。
- 候选注入点在 `core/Rime.kt:472` 的 `CandidateListMessage` 分支：方案自带 comment 优先，空则填译词；加开关。
- 估计 2–3 天（不含「点译词直接上屏英文」，那项要 ClickableSpan，另加半天）。

## 6. 外语学习：点词上屏后编码行被清空（2026-11-19 已按方案 1 修复：不再清组合）

- 现象：点提示块里的英文词后，编码行立刻变空；此时退格删的是编辑器正文，删不到编码。
- 直接原因（已定位，不是引擎设计）：`PreeditUi.commitPayload()` 第一行就是 `clearComposition()`，
  它调用 librime 的 `clear_composition` 再 `emitResponse()` 推一次空组合，编码行随即清空。
  引擎本身不会因为 `InputConnection.commitText()` 结束组合。
- 当初这么写的原因：上屏「不是引擎产出的文字」（英文词 / AI 文本 / 表情 / 剪贴板）之前必须先收尾组合，
  否则编辑器里的 composing 区还挂着，会出现下划线残留、光标错位、连续两次提交重复上屏。
- 若将来要改，候选做法（按优先级）：
  1. 直接不清组合，只 `service.commitText(payload)`，让引擎组合留着；风险是候选列表不刷新、
     下一次上屏可能把整段组合再提交一次，需要真机验证。
  2. 不清组合但把 `input` 标记为「已消费」，让编码行继续显示、退格仍走引擎。
  3. 提交后用 `simulateKeySequence` 重放原输入（等于「清了再喂回来」，会多触发一轮候选，最不优雅）。
- **2026-11-19 结论：按方案 1 做了**（提交 `1044a06c`，`GlossaryTipsView.commitPayload()` 只 `commitText`）。
  同时解决了实体键盘下的连带问题：候选窗的 `evaluateVisibility()` 以「组合非空 或 候选非空」判定，
  清组合会让候选窗收起、提示块整条消失。方案 2/3 未采用。
- 相关位置：`ime/composition/PreeditUi.kt` 的 `commitPayload()`（外语学习与文曲星各一份）、
  `core/Rime.kt` 的 `clearComposition()`。

## 7. 技术债：文曲星提示块仍是「手写命中 + 浮层按钮」（2026-11-19 记录，暂不动）

对照对象是同期做的「外语学习」提示块。两者是两份独立实现（用户要求互不影响），
但实现路线不同，外语学习那份被验证更稳，文曲星仍是早期写法。

| 维度 | 文曲星 `WenquxingTipsView`（`ime/composition/PreeditUi.kt:319`，FrameLayout） | 外语学习 `GlossaryTipsView`（同文件 `:1139`，LinearLayout 纵向） |
| --- | --- | --- |
| 按钮 | 「收起」绝对定位右上角（top 4dp / right 8dp），滚动区用 34dp 顶部内边距让位 | 真实一行 headerRow（全部上屏 + 收起），高 26+12dp |
| 点击 | 覆写 `dispatchTouchEvent`，用 `ev.rawY` + `getLocationOnScreen` 手工算命中（`childIndexAtScreen:876`） | 每个 View 原生 `setOnClickListener`（行 / 词块 / 两个按钮） |
| 展开内容 | 单个 TextView，只能看和滚 | 词块网格，每个词可点、单独上屏 |
| 展开高度 | `EXPAND_RATIO_WQ = 0.28f` | `EXPAND_RATIO_GL = 0.17f` |
| 状态 | 每实例私有（expandedIndex / scrollBeforeExpand / snapshot） | 文件级 `GlossaryTipsState` 两实例共享（含 txn / undoLength） |
| 撤回上屏 | 无 | 有，事务号 + InputConnection 返回值校验 |

结论：**外语学习那套更好**，判断依据是可靠性与可扩展性，不是新旧。

- 点击可靠性：原生分发不依赖坐标换算；`ev.y` 经弹窗转发不可信这件事在提示卡上已经踩过。
- 能力上限：文曲星目前**无法**「点某一句 / 某个词单独上屏」，要做只能照外语侧改。
- 状态归属：外语侧把跨实例状态收进一个 object，双实例不再各说各话；文曲星仍是实例字段，
  一旦也出现双实例（键盘 + 悬浮候选窗），同样的「穿透 / 虚假显示」会复现。
- 文曲星唯一更优的点是简单：没有撤回事务、没有词块，出错面小。

若哪天允许重构（用户此前明确要求「文曲星那侧一行都别动」，故仅记录）：
1. 容器 `FrameLayout` → `LinearLayout(VERTICAL)` + `headerRow`，删掉 `dispatchTouchEvent` /
   `childIndexAtScreen` / `childIndexAt` 三个方法（约 90 行），行上挂原生点击；
2. 28% 高度的测量路径要按外语侧 `onMeasure` 的方式重算（否则展开高度会跳）；
3. 顺带可把「点句子上屏」做成可选项（词块化才支持）；
4. 风险：列表态三角列、展开态截断（字符数档位）都要跟着适配，属于「能跑就别动」的重构。

## 8. 符号面板抽成独立模块（待办，用户 2026-11-19 已批准记录）

现状：符号/数字面板与手写画布在同一个 `HandwritingWindow` 里（`HandwritingWindow.kt`），
面板只是盖在画布上的一个子 View，所以两者共用同一套底部工具栏（返回·数字·空格·符号·回车）与候选区。

已做（第一步）：把「加载手写模型」和「打开面板」解耦——`requestPanelOnly()` 在挂窗口前登记
`panelOnlyPage`，`ensureModel()` 见到它就跳过加载。开面板不再等 7 MB 模型。

待办（第二步）：真要抽成独立模块时，需要一并解决：

1. 独立窗口会丢失手写板底部工具栏与候选区，必须在面板里补一套（返回/空格/回车/切输入法），
   否则「返回动作和现在一致」这条要求做不到；
2. 抽出 `SymbolPanelView` + `SymbolPanelWindow`（`actions/symbol/`），把 `SYMBOL_PAIRS`、
   `SymbolPages` 页签、常用页、子分类下拉、符号列一起搬过去；
3. 入口改为两处：主题 token（`BambooNumber`/`BambooSymbol`）与手写板工具栏的「数字/符号」；
4. `InputView.startInput()` 的恢复链要加一个分支（当前位置：OCR 卡片 → OCR → AiPanel →
   Handwriting → KeyPanel → 键盘）；
5. 抽完才能安全删掉 `HandwritingWindow` 里那批面板函数（约 20 个，见文档中行号清单）。

风险：面板逻辑目前仍在迭代（子分类下拉、常用页排序、页签宽度），建议等它稳定后再做第二步。

## 9. 截图二级菜单候选（2026-11-20 记录，未开工）

第一层固定 6 项（取消 / 全屏 / 识字 / 保存 / 复制 / 退出）+「更多」进第二层。第二层候选：

1. **翻译**（框内文字 → 中文/英文）——复用离线 OCR + 现有 AI 翻译模式，成本最低；
2. **提取链接**（正则扫文本里的 URL，点一下复制）——几乎零成本；
3. **取色**（吸管取框内某点颜色，出 HEX，一键复制）——自绘，成本低；
4. 提取数字 / 表格（OCR 后按行规整）；
5. 标注 / 涂鸦（自绘层 + 导出 PNG）；
6. 扫码（需引入 zxing 或系统扫码 API 34+，有依赖成本）；
7. 长截图（滚动拼接，成本高，**建议不做**）。

推荐先做 1 / 2 / 3。

## 10. 主题编辑器：预览补工具栏（2026-11-20 记录）

预览已画：键盘、候选栏、预编辑、键盘下方留白；**唯一还缺工具栏**。
难点：真键盘工具栏按钮是动态的（由 `tool_bar` 与当前键盘决定），预览若要 1:1 得先定：

1. 画几个「示意按钮」（建议 4–5 个：键盘样式 / 方案 / 剪贴板 / 设置 / 收起）；
2. 高度取 `tool_bar.height`（或工具栏实际测量高度），颜色取 `keyboard/toolbar` 相关色键；
3. 是否参与点击（建议不参与，仅示意）。

## 11. 主题编辑器：`batchNumberRow` 的 ↺（2026-11-20 记录）

批量数字滑块（如「字号 N 档一起改」）目前没有 ↺。它只在松手时 `onApply` 一次，
没有「当前值」的实时状态，所以原点需要先定义：

- a) 打开页面时滑块显示的基准值（最简单，和 `sliderRow` 一致）；
- b) 这批键里最小的那个值（更符合「批量」语义，但要现算）。

建议 a)。等用户确认后再做。
