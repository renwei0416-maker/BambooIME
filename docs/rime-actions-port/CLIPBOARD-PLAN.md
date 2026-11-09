# 剪贴板增强：从 CyIME 搬什么、怎么搬

> 用途：把「要加的 CyIME 剪贴板功能」一次性理清楚，作为后续动手的唯一依据。
> 本文只讲**差距与做法**，不重复已完成的发版内容。
> 日期：2026-11-09

---

## 一、我们现在有什么（逐项有代码依据）

| 能力 | 现状 | 位置 |
| --- | --- | --- |
| 持久化 | ✅ Room 两张表：`t_data`（`DatabaseBean`：文本/图片/文件、固定、来源、`sensitive`）+ `t_clipboard_sync` | `app/src/main/java/com/osfans/trime/data/db/DatabaseBean.kt`、`ClipboardHelper.kt` |
| 分类 tab | ✅ **按来源**：全部 / 收藏 / 本地 / 媒体 / 远程 | `app/src/main/java/com/osfans/trime/data/db/ClipboardCategory.kt` |
| 收藏 / 固定 | ✅ `pin()` / `unpin()`，`pinned` 列 | `ClipboardHelper.kt:151-153` |
| 拖动排序 | ✅ `ItemTouchHelper` | `ime/clipboard/ClipboardAdapter.kt` |
| 文本搜索 | ⚠️ 只有 `LIKE`，**没有拼音** | `ClipboardHelper.kt:149` `searchEntriesPager()` |
| 条数上限 | ✅ `clipboardLimit`（int，默认 **1024 条**），超限删最旧、固定不删 | `data/prefs/AppPrefs.kt`、`ClipboardHelper.kt:379-382` |
| 去重规则 | ✅ 可配 `clipboardCompareRules` | `ClipboardHelper.kt:79` |
| 输出过滤 | ✅ 可配 `clipboardOutputRules` | `ClipboardHelper.kt:98` |
| 提取规则 | ❌ **设置项存在，但没人调用**：`ClipboardExtractor` 全仓无引用（死代码） | `ime/clipboard/ClipboardExtractor.kt` |
| 敏感内容 | ⚠️ 仅 `EXTRA_IS_SENSITIVE`（API 33+）+ 关键词表；能打点遮罩；**不拦搜索/导出，无过期** | `ClipboardHelper.kt:86`、`DatabaseBean.sensitive` |
| 图片 / 文件条目 | ✅ `ClipboardImageLoader`、`ClipboardFileManager`、`Media` 类目 | `ime/clipboard/`、`data/clipboard/` |
| 截图监听 | ✅ 截屏后自动进剪贴板（带媒体权限校验） | `ime/clipboard/ScreenshotClipboardWatcher.kt` |
| 条目动作 | ✅ 粘贴/固定/编辑/分享/**分词**/看图/开链接/网页搜索/复制/删除 | `ClipboardAdapter.kt:177-223` |
| 多选删除 | ✅ 选择模式 + 确认框 | `ClipboardWindow.kt` |
| 分词 | ✅ `SegmentsWindow`（NativeTokenizer 分词 + 拖选） | `ime/segments/SegmentsWindow.kt` |
| 跨设备同步 | ✅ SyncClipboard / WebDAV / S3 + SignalR | `data/clipboard/sync/` |
| 自定义分组 | ❌ | — |
| 保留时间（按天过期） | ❌ | — |
| 拼音首字母搜索 / 输入中实时过滤 | ❌ | — |
| 导出 / 导入 | ❌ | — |
| 左右滑动切分类 | ❌ | — |
| 验证码识别 | ❌ | — |
| 物理键盘导航面板 | ❌ | — |
| 候选栏「干净链接 / BV 号」 | ❌ | — |

---

## 二、CyIME 有什么（可搬的具体件）

源码：`C:\Users\Administrator\Desktop\Ai_Work\_cyime2\app\src\main\java\com\kingzcheung\xime\clipboard\`（23 个文件 + `db/` + `sync/`，主逻辑 `ClipboardManager.kt` 1048 行）

### 1. 内容类型筛选 `ClipboardBoard.kt`
```kotlin
enum class ClipboardFilter { ALL, TEXT, LINK, ADDRESS, PHONE, EMAIL, IMAGE }
link   = "(?:https?://|www\\.)\\S+"
email  = "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}"
phone  = "(?:\\+?[0-9][0-9 ()-]{7,}[0-9])"   // 再要求数字位数 10..15
address= "〒\\s*[0-9]{3}|[都道府県].*[市区町村]|[省市自治区].*[路街巷弄号]|[0-9]+丁目|[0-9]+番地"
```

### 2. 用户分组 `ClipboardGroups.kt` + `ClipboardGroupStore.kt` + `db/ClipboardEntry.kt:grouped`
- 组名 ≤10 字、不能与内置分类同名；编码为 `id\tname`（tab 顺序）、成员 `groupId\trecordKey`。
- **组内条目不算固定、不置顶**，但**不参与条数裁剪、不过期**（`grouped` 列）。
- tab 顺序：内置分类（可隐藏）→ 用户分组。

### 3. 敏感条目 `ClipboardEntry.sensitive`
- 打点显示 `••••••••`、私密粘贴、**不进搜索、不进导出**、**1 小时后自动删除**（固定除外）。
- `copyReceivedCodeToSystemClipboard(code, ttlMs)`：给验证码做临时剪贴板。

### 4. 搜索 `ClipboardSearch.kt`
- 新列 `searchKey`：前 2000 字，每个汉字取拼音首字母、字母数字转小写、其他变空格；空键存 `" "` 防重复回填。
- 拼音来自 `android.icu.text.Transliterator("Han-Latin; Latin-ASCII")`，**不用装词库**。
- `composingInitials(preedit)`：还能在**未选词**时按组合串实时过滤（`di'zhi` → `dz`；`zh/ch/sh` 算一个）。
- T9：`T9_KEY_SQL` 把 key 的字母换成数字后匹配（九键用）。
- `backfillSearchKeys()` 后台分批补齐老记录。

### 5. 容量与保留
| 件 | 数值 |
| --- | --- |
| `ClipboardCapacity` | 文本总上限 **2 MB**、再取空闲盘 2%、单条 ≤ 2MB−64KB；**设备来件单独 100 条 / 1 MB** |
| `ClipboardRetention` | 条数 **100/300/1000/5000/不限**（默认 1000）；天数 **1/7/30/90/不过期**（默认不过期）；固定与分组不计 |
| `ClipboardImageRetention` | 近期图 **50 张**、缓存 **500 MB**、固定不淘汰 |

### 6. 导出 / 导入 `ClipboardExport.kt` / `ClipboardImport.kt`
- 格式 **TXT / Markdown / JSON**；JSON 与 MD 带固定标记、编码 `code`、分组名、时间。
- 导入**按内容判断格式**（不认扩展名），上限 16 MB，去重计数，导完按限额裁剪，返回「已导入 N 条（其中固定 M）／已有 K 条／放不下 J 条／裁剪 T 条」。

### 7. 其它小件
| 件 | 作用 |
| --- | --- |
| `VerificationCodeExtractor.kt` | 多语言关键词（验证码/OTP/verification code…）+ 4–8 位数字 + 距离评分；**同分不猜**，返回 null |
| `ClipboardKeyboardNavigation.kt` | 外接键盘方向键/空格菜单/删除，按网格实际几何找下一格 |
| `ClipboardLinkChoices.kt` | 候选栏胶囊：干净链接（去 utm_/spm/share_* 等）、B 站 BV 号；`b23.tv` 不展开 |
| `ClipboardCategorySwipe.kt` | 水平锥形判定，一次滑动最多切一个 tab |
| `ClipboardDraftSave.kt` | 编辑草稿的保存校验（空文本 / 记录已删） |

### 8. 刻意不搬：设备配对整套
`sync/DeviceHost.kt`(512) `WindowsDevices.kt`(511) `WindowsSecureTransport.kt`(272) `WindowsNearbyPairing.kt` / `WindowsPairingProtocol.kt` / `PairingQrDecoder.kt` / `RemoteTyping.kt`
—— 我们已经有三套同步后端（SyncClipboard/WebDAV/S3 + SignalR），重写等于自废武功。

---

## 三、三期计划

### P1（半天，不动数据库，零迁移风险）

1. **内容类型筛选**：把 `链接 / 电话 / 邮箱 / 地址` 做出来。**UI 方案待定**（见下）。
2. **左右滑动切分类**：照抄 `ClipboardCategorySwipe`（33 行），加在 `ClipboardWindow` 的列表外层。
3. **把「提取规则」接上**：`ClipboardExtractor` 目前是死代码。在条目菜单加「提取内容」，用现成的 `clipboardExtractRules` 跑正则，结果进 `SegmentsWindow` 风格的选择窗。
4. **验证码识别（纯本地、不要权限）**：照抄 `VerificationCodeExtractor`，识别出的条目加「验证码」角标 + 一键复制（复制后 60 秒清剪贴板可留作 P3）。

**验收**：新 tab 能筛出对应条目；滑动切 tab 不误触列表滚动；「提取内容」在一条含电话+链接的文本上给出可选项；短信样本文本能在条目上看到验证码角标。

### P2（1 天，Room 迁移一次）

5. `t_data` 加三列：`searchKey TEXT DEFAULT ''`、`grouped INTEGER DEFAULT 0`、`expiresAt INTEGER DEFAULT 0`。
6. 拼音首字母搜索 + 后台回填 + 输入中实时过滤（复用 `RimeSession` 的组合串）。
7. **保留时间**（按天过期）+ 条数上限改成下拉（100/300/1000/5000/不限），固定与分组不计。
8. **自定义分组**：分组表或 prefs 存 `id\tname`；长按条目「加入分组」；组内不裁剪、不置顶；分组 tab 排在内置分类之后。

**验收**：迁移后老数据全在、条数不变；输入 `dz` 能找到「地址」；设 7 天后过期，7 天前的非固定条目被清；建两个分组、条目归组后清空历史，组内条目仍在。

### P3（1.5 天，可缓）

9. **敏感强化**：自动识别（关键词 + 卡号/身份证正则）+ 1 小时过期 + 不进搜索/导出 + 打点。
10. **导出/导入**（JSON+MD+TXT，含固定、分组、编码）。
11. **图片容量与保留**（单条上限 + 50 张近期 + 总容量）。

---

## 四、动手前必须先定的事

1. **类型筛选的 UI 方案**（三选一）：
   - A：把 tab 行做成两段——上行「来源」（全部/收藏/本地/媒体/远程），下行「类型」（全部/链接/电话/邮箱/地址/图片）。信息最全，占两行高度。
   - B：**tab 行只保留内容类型**（全部/文本/链接/电话/邮箱/地址/图片），来源维度并进搜索/筛选菜单。最接近 CyIME，但会动现有习惯。
   - C：tab 仍是来源，类型做成 tab 右侧一个「筛选」漏斗按钮，点开选类型。改动最小，但多一步操作。
2. **条数上限口径**：我们现在是「1024 条」，CyIME 是「2 MB 字节 + 条数 + 天数」三套并存。建议 P2 起改成「条数 + 天数」两套，字节上限只做内部保护（2 MB），**保留现有 1024 条作为默认**，不做成用户可见选项。
3. **验证码要不要短信接收**：`VerificationCodeSmsReceiver` 需要 `RECEIVE_SMS` 权限，商店审核与信任成本高。建议只做「本地文本识别」，不做短信接收。

---

## 五、注意事项（搬的时候容易踩）

1. **「拆分」只保留我们的「分词」**：`SegmentsWindow`（NativeTokenizer 切词、可拖选）。CyIME 的 `splitItem()`（把一条文本逐字拆成 N 条）**不做**。
2. **不要重复造**：图片/文件条目、截图监听、收藏、拖动排序、跨设备同步都已存在，新功能只在它们之上加维度。
3. **Room 迁移必须写 `Migration` 并测老库**：现网用户已有数据，不能 `fallbackToDestructiveMigration`。
4. **`DatabaseBean` 已有 `sensitive` 列**，敏感功能不需要新列（P2 只加 `searchKey`/`grouped`/`expiresAt`）。
5. **拼音不要引第三方库**：`Transliterator("Han-Latin; Latin-ASCII")` 是系统 ICU，零体积。
6. **往回填要考虑老用户**：`backfillSearchKeys()` 要分批 + 可中断，否则一万条会卡启动。
7. **图片拖慢列表**：加类型 tab 后「图片」页要懒加载缩略图，沿用现有 `ClipboardImageLoader`。

---

## 六、界面前后对照（用户 2026-11-09 追问）

### 1. 顶部分类 tab

**现在**（`ClipboardCategory.kt`，按**来源**分）：`全部 / 收藏 / 本地 / 媒体 / 远程`

**做完之后**（按下的方案不同）：

| 方案 | 做完后的 tab 行 | 说明 |
| --- | --- | --- |
| A 两行 | 上行：`全部 / 收藏 / 本地 / 媒体 / 远程`；下行：`全部 / 文本 / 链接 / 电话 / 邮箱 / 地址 / 图片` | 来源与类型都能点，多占约 44dp |
| B 只留内容类型 | `全部 / 文本 / 链接 / 电话 / 邮箱 / 地址 / 图片` | 最像 CyIME；「收藏」变成 tab 上的星标筛选，「本地/远程」并进设置或搜索菜单 |
| C 漏斗 | tab 行不动；右侧加一个漏斗按钮，点开选 `文本/链接/电话/邮箱/地址/图片` | 改动最小 |

**三种方案都会再加**：P2 的自定义分组作为 tab 追加在最后（如 `常用语`、`工作`），且分组 tab 里**不裁剪、不过期**。

### 2. 长按条目的菜单

**现在**（`ClipboardAdapter.kt:176-225`，**按条目内容动态出现**）：

| 菜单项 | 出现条件 |
| --- | --- |
| 粘贴 | 文本、图片、文件（纯 URI 且非图片时不出现） |
| 添加到收藏 / 取消收藏 | 总是二选一 |
| 编辑 | 文本条目 |
| 分享 | 总是 |
| 分词 | 文本条目（进 `SegmentsWindow`） |
| 看图片 | 图片条目 |
| 打开链接 | 文本里识别到链接 |
| 搜索 | 文本条目（跳系统网页搜索） |
| 复制（实为拨号） | 文本里识别到号码 |
| 删除 | 总是 |

**做完之后新增**：

| 新增项 | 期 | 出现条件 / 作用 |
| --- | --- | --- |
| 提取内容 | P1 | 文本条目，按「剪贴板提取规则」正则抽出电话/链接/邮箱等，弹选择窗（把死代码 `ClipboardExtractor` 接上） |
| 复制验证码 | P1 | 文本里识别出 4–8 位验证码，一键复制（条目上另加「验证码」角标） |
| 复制 | P1 | 文本条目，复制回系统剪贴板（现在缺这一项，见下方 bug） |
| 加入分组 ▸ | P2 | 子菜单：已有分组 / 新建分组 / 移出分组 |
| 设为敏感 / 取消敏感 | P3 | 手动标记，打点显示、不进搜索与导出、1 小时后删除 |
| 导出所选 | P3 | 多选模式下多一个「导出」 |

### 3. 其它行为差别

| 方面 | 现在 | 做完 |
| --- | --- | --- |
| 搜索 | 子串 `LIKE`，中文得打全字 | 拼音首字母（`dz` → 地址）+ 输入中实时过滤 + 结果上限 200 |
| 条数上限 | 单一数字，默认 1024 条 | 下拉：100 / 300 / 1000 / 5000 / 不限 |
| 保留时间 | 无过期 | 1 / 7 / 30 / 90 / 不过期（固定与分组不计） |
| 敏感内容 | 关键词 + 系统标记，打点遮罩 | 自动识别 + 1 小时过期 + 搜索/导出跳过 + 私密粘贴 |
| 图片 | 无总量控制 | 单条上限 + 近期 50 张 + 总容量兜底 |
| 分组 | 无 | 有，组内不裁剪、不置顶 |
| 备份 | 无 | 导出/导入 JSON / Markdown / TXT（含分组、固定） |
| 切分类 | 只能点 tab | 可左右滑动切（一次最多一格） |
| 外接键盘 | 面板不吃方向键 | 方向键移动、空格出菜单、Esc 关闭（P3 可选） |
| 候选栏 | 只有剪贴板提示胶囊 | 链接条目额外给「干净链接 / B 站 BV 号」（可选） |

**完全不动**的部分：跨设备同步三后端（SyncClipboard / WebDAV / S3）、截图监听、分词窗口、收藏与拖动排序、媒体与文件条目。

### 4. 顺带发现的两个小问题

- `ClipboardAdapter.kt:218-221`：识别到号码的那一项，标签用的是 `android.R.string.copy`（「复制」），图标是打电话，实际动作是 `onDial`（拨号）。文案与动作不符，应与「复制」分开。
- 文本条目**没有**「复制」项（只有「粘贴」），要复制回系统剪贴板得靠变通。

---

## 七、附带发现（与本次无关但应修）

- `ClipboardExtractor` 无任何引用 → 设置里「剪贴板提取规则」目前**填了不起作用**。要么按 P1-3 接上，要么删掉这项设置。
