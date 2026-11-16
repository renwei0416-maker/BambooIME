# 更新上游操作手册

场景：上游 `Carlson24/LyraIME` 有新提交，我们要把它的改动并进来。

> 现状（2026-11-17）：上游 `lyraime` 相对我们的分叉点只有 **2 个提交 / 9 个文件**，
> 其中只有 `app/src/main/java/com/osfans/trime/ime/core/InputView.kt` 与我们重叠。
> 完整清单见 [`UPSTREAM-DIFF.md`](./UPSTREAM-DIFF.md)（`python tools/upstream-diff.py` 重新生成）。
>
> **2026-11-17 实测：`git fetch origin lyraime` 已可用**（不再需要第 0 节的 bundle 迂回），
> `git ls-remote` 与 `api.github.com` 都通。第 0 节保留作为「万一又拉不到」的备用方案。

## 0. 备用：万一本机又拉不到 github.com

本机 `github.com` 主机不可达，`git fetch` 会超时。两条路：

- **A. 在能上网的机器上** `git clone --mirror` 或 `git bundle`，拷回来 `git fetch ./lyraime.bundle 'refs/*:refs/upstream/*'`；
- **B. 只看清单**：`python tools/upstream-diff.py` 会调 `api.github.com` 拉「上游在分叉点之后改了什么」（API 可达），
  足够判断冲突面；真正合并时仍需拿到对象（走 A）。

## 1. 先看清冲突面

```bash
# 我们的分叉点
BASE=094e6894

# 上游后来改过的文件
git diff --name-only $BASE...upstream/lyraime

# 我们也改过的文件
git diff --name-only $BASE...HEAD

# 交集 = 合并时唯一可能冲突的文件
comm -12 <(git diff --name-only $BASE...upstream/lyraime | sort) \
         <(git diff --name-only $BASE...HEAD | sort)
```

先只看这个交集，交集外的文件按上游覆盖就行。

## 2. 合并（两条路线，选一条）

### 路线一：merge（保留血缘，历史清楚）

```bash
git switch -c merge-upstream
git merge upstream/lyraime
# 只处理交集文件；我们的改动大多在“新增文件”里，冲突会集中在：
#   ime/core/InputView.kt
#   ime/composition/PreeditUi.kt
#   ime/keyboard/CommonKeyboardActionListener.kt
#   core/Rime.kt
git commit
```

每个冲突文件都能在 `docs/upstream-patches/` 找到对应记录：

- 先看 `<文件名>.orig` 确认上游原样；
- 再看 `<文件名>.patch` 看我们加了什么；
- 最后按 `README.md` 里「上游原行为 / 我们改成了什么 / 怎么验证」逐条对照。

### 路线二：rebase 我们的提交到上游（历史线性，但要一个个解冲突）

```bash
git switch -c rebase-upstream
git rebase --onto upstream/lyraime 094e6894
```

107 个提交逐个 rebase 会很痛苦，**除非**你打算把我们的改动压缩成十几个语义提交，否则用路线一。

## 3. 合并后的验收清单

- [ ] `python tools/upstream-diff.py` 重跑，确认交集文件都已处理、没有再冒出新的 `⭐` 文件；
- [ ] `./gradlew :app:assembleDebug` 通过；
- [ ] 冒烟：中文打字 / 九键 / 手写 / 拍照识字 / 剪贴板 / 文曲星 / 外语学习 / 语音发AI / AI 面板 / 万象设置向导；
- [ ] 两个方案包切换（「方案包管理」点 B 包 → 确认写的是 B 的文件）。

## 4. 合并完记得

- 把新的分叉点写进 `tools/upstream-diff.py` 的默认值（或发版时用 `UPSTREAM_BASE=<新分叉点>` 生成一次），
  这样 `UPSTREAM-DIFF.md` 就只统计「最近一次合并之后我们又改了什么」；
- 如果上游把 `findPackageForSchema()` 改好了（例如优先当前活动包），把 `docs/upstream-patches/` 里第 1 节标记为「已并入上游，可回退」，
  并把 `PackageListFragment` 的 `applySelection()` 简化回调用上游 API。

## 5. 升级 `librime` 子模块前必读（本地 11 行补丁）

`app/src/main/jni/librime` 里有 **11 行未提交进子模块的本地改动**：给 C API 的 `RimeCandidate`
补 `char* type` 字段（`src/rime_api.h`），并在 `src/rime_api_impl.h` 的拷贝/释放路径上同步管理这块内存。

这 11 行不是可选项——`librime_jni` 里 `helper-types.h:51`、`:129-130`、`objconv.h:65` 早在分叉点
`094e6894` 就在读 `candidate.type`，没有它 JNI 编译不过。

**所以每次动子模块指针（包括上游 `59d508e3 update librime` 那次）都要按这个顺序：**

```powershell
# 1. 切指针
git submodule update --init app/src/main/jni/librime
# 2. 立刻重打补丁（否则编译报错会落在 librime_jni，容易误判）
$P = (Resolve-Path docs/upstream-patches/librime).Path
git -C app/src/main/jni/librime apply "$P/rime_api.h.patch"
git -C app/src/main/jni/librime apply "$P/rime_api_impl.h.patch"
# 3. 全量重编（librime 十几分钟）+ 回归：部署时长 / 九键 / 辅助码 / 长句 / 候选操作菜单
```

细节与验证步骤见 [`upstream-patches/README.md`](./upstream-patches/README.md) 第 3 节；
补丁与上游原始快照在 [`upstream-patches/librime/`](./upstream-patches/librime/)。

若上游换了这套 C 结构（例如正式加了 `type` 或改名），**以上游为准**，删掉这两个补丁并按新结构适配 JNI。

## 6. 合并记录

### 2026-11-17 · `e5b2f010 add syncinput button`（Kotlin 部分，已合）

| 项 | 值 |
| --- | --- |
| 方式 | `git cherry-pick e5b2f010`（**不整支 merge**，避免把 `59d508e3` 的子模块指针一起带进来） |
| 分支 | `merge/syncinput` |
| 合并提交 | `04631e05` |
| 冲突 | **无**（`InputView.kt` 自动合并成功） |
| 上游改动 | 7 文件 +208/−38：`SyncInput.kt`(+1)、`SyncInputBox.kt`(+167/−33，改 `HorizontalScrollView` + 按钮)、`SyncInputTextFormatter.kt`(+12)、`ToolButton.kt`(+6, 修 NPE)、`InputView.kt`(+20, `syncInputBox` 改 `by lazy` + `onAction` 回调 + `updateButtonStyle()`)、`catppuccin.lua`(+21)、新增 `SyncInputTextFormatterTest.kt`(+19) |

验证（2026-11-17）：

- [x] `InputView.kt` 相对合并前**只有新增 20 行，没有删掉我们任何一行**（`git diff ef7c12fe..HEAD -- …/InputView.kt` 逐行确认）
- [x] 我们的 13 个 hunk 符号全部还在：`ocrCardHost`(23)/`showOcrCard`/`hideOcrCard`/`getOcrCardTouchRegion`/`setInputBarSuppressed`/`wantHandwriting`(7)/`wantAiPanel`(5)/`wantKeyPanel`/`schemaHintHost`(11)/`maybeShowSchemaHint`/`dismissSchemaHint`(4)/`isSchemaHintVisible`/`refreshPreedit`/`endOfParent()`(6)/`PreeditUi.tipsTouchable`/`OcrCard.deactivate`
- [x] `:app:assembleDebug` 通过（1m59s）；`:app:assembleRelease` 通过（2m15s，75,791,420 B）
- [x] `:app:testDebugUnitTest --tests "*SyncInputTextFormatter*"` 通过（27s，含上游新增测试）
- [x] debug dex 探针：`BambooKeyPanel`/`BambooAiPanel`/`BambooGlossary`/`BambooVoiceAi`/`wanxiang-base`/`wanxiang_14key`/`wanxiang_18key`/`wanxiang_t9`/`last_keyboard`/`restore_keyboard`/`panel_long_press`/`exitAll`/`rememberedKeyboard`/`CustomSignature`/`SchemaPackStore`/`WenquxingTips`/`GlossaryTips`/`PieceTokenizer`/`ClipboardExtractScan`/`AutoPair` 全部命中
- [x] release APK 资产完整：`assets/wanxiang-base` 83 条目、OCR det 4,819,576 B
- [ ] 真机冒烟（打字/九键/手写/拍照/两个面板/文曲星/外语学习/语音/AI 面板/万象向导）——待用户实测

产物：`dist\BambooIME-3.0.0-dev-syncinput-arm64-v8a-debug.apk`（113,981,191 B，sha256 `33b1ba98c62b3a7f674f2924e4d78ae501a0915a7732e682180ddf75a1c341b9`）

### 2026-11-17 · `59d508e3 update librime`（子模块指针，已合）

| 项 | 值 |
| --- | --- |
| 方式 | 直接切子模块指针（`59d508e3` 只改 2 个指针，无代码冲突） |
| `app/src/main/jni/librime` | `3f778e97` → **`4dcfb5f3`**（17 个提交，含 `Merge librime-official/master`） |
| `app/src/main/jni/librime-plugins/librime-ids-reverse` | `d1069442` → **`9d7a67e2`**（`ids_variants`、按字件搜索；我们不使用，跟随上游） |
| 嵌套依赖 | `deps/googletest` `063de7e9`、`deps/leveldb` `bb74ef73`、`deps/yaml-cpp` `56e3bb55`（yaml-cpp 0.9.0） |
| 我们的 11 行补丁 | `git apply --check` 在 `4dcfb5f3` 上**仍然通过**，已重打（`src/rime_api.h` +1、`src/rime_api_impl.h` +10） |

上游 librime 里对我们最有价值的四条：`e930f57b`（**并行编译独立词库**，直指部署耗时）、`9777cb2f`（用户词典内存排序缓存）、`0ed3f95b`（编译期降内存）、`0e6b3090`（按运行时条件启用组件，新增 `compile_unit.cc`）。

验证（2026-11-17）：

- [x] `git apply --check` 两个补丁均通过；应用后 `git diff --stat` = `src/rime_api.h +1`、`src/rime_api_impl.h +10`
- [x] `:app:assembleDebug` 通过（3m59s），日志里可见 `src/rime/lever/compile_unit.cc` 被编译 → 确认是**新** librime 源码；无 `error:`
- [x] `:app:assembleRelease` 通过（5m22s），APK 77,712,223 B，V3 签名 `CN=BambooIME` `b24cf2f7…17d127`，`versionCode=20261117 versionName=3.0.0`
- [x] dex 探针（debug 33.9 MB dex / release 6.7 MB dex）：`BambooKeyPanel`/`BambooAiPanel`/`BambooGlossary`/`wanxiang-base`/`wanxiang_14key`/`exitAll`/`CustomSignature`/`WenquxingTips`/`GlossaryTips`/`AutoPair`/`PieceTokenizer`/`ClipboardExtractScan` 全部命中
- [x] native 产物齐全：`librime_jni.so`（debug 13.6 MB / release 5.3 MB）、`libbamboo_handwriting.so`、`libbamboo_ocr.so`
- [ ] 真机冒烟 + **部署耗时对比**（预期明显变短）：打字 / 九键 / 辅助码 / 长句 / 手写 / 拍照 / 两个面板 / 文曲星 / 外语学习

产物：`dist\BambooIME-3.0.0-dev-upstream-merged-arm64-v8a-debug.apk`（116,902,576 B，sha256 `725a89e8…74f0e6cd`）、
`dist\BambooIME-3.0.0-dev-upstream-merged-arm64-v8a-release.apk`（77,712,223 B，sha256 `9716eacc…d98`）

合并完成状态：**上游 `lyraime` 的 2 个提交已全部并入并建立血缘**。

- 内容合并：`04631e05`（cherry-pick `e5b2f010`）+ `11bd4409`（子模块指针 → `4dcfb5f3` / `9d7a67e2`）
- 血缘合并：`73ec74b3`（`git merge --no-ff origin/lyraime`，**空树改动**，仅把上游两个提交变成 HEAD 的祖先）
- 结果：`git rev-list --left-right --count HEAD...origin/lyraime` = **155 / 0**（领先 155，落后 0）；
  `merge-base --is-ancestor e5b2f010 HEAD` 与 `59d508e3 HEAD` 均为真
- 下一次上游有新提交时，直接 `git merge origin/lyraime` 即可，不会再重复提示这两个旧提交

**已合回主干（2026-11-17）**：`git merge --no-ff merge/syncinput` → `408a7147`；随后 `chore(release): 3.1.0` → `06b0a0a0`，
标签 `bamboo-3.1.0`。主干 `lyraime` 现在领先上游 157 / 落后 0。
