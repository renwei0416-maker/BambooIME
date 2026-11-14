# 更新上游操作手册

场景：上游 `Carlson24/LyraIME` 有新提交，我们要把它的改动并进来。

> 现状（2026-11-15）：上游 `lyraime` 相对我们的分叉点只多了 **2 个提交 / 9 个文件**，
> 其中只有 `app/src/main/java/com/osfans/trime/ime/core/InputView.kt` 与我们重叠。
> 完整清单见 [`UPSTREAM-DIFF.md`](./UPSTREAM-DIFF.md)（`python tools/upstream-diff.py` 重新生成）。

## 0. 前置：本机拉不到 github.com

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
