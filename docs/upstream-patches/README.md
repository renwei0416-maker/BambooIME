# 我们对上游文件的改动记录

这个目录是**给「以后合并上游」用的档案**。规则很简单：

- 每改一个上游文件，就在这里放三样东西：
  1. `README.md`（本文件）里加一节：**上游原来是什么行为、我们改成了什么、为什么、怎么验证**；
  2. `<文件名>.orig`：**上游原文件快照**（`git show <分叉点>:<路径>`），合并时可以拿来逐行对照；
  3. `<文件名>.patch`：我们的改动（`git diff <分叉点> -- <路径>`）。
- 全量清单（哪些文件被我们改过）自动生成在 [`../UPSTREAM-DIFF.md`](../UPSTREAM-DIFF.md)，重跑 `python tools/upstream-diff.py` 即可刷新。

分叉点：`094e6894`（我们第一个提交 `b5d3a195 bamboo: 1.1.0 baseline` 的父提交，上游 `Carlson24/LyraIME`，分支 `lyraime`）。

---

## 1. `PackageListFragment.kt`（方案包管理 · 多选框）

**文件**：`app/src/main/java/com/osfans/trime/ui/main/settings/PackageListFragment.kt`

### 上游原行为（`PackageListFragment.kt.orig`）

对话框点「确定」时执行三件事：

```kotlin
rime.runOnReady {
    selectSchemaCrossPackage(selectedIds.first())   // ← 按“方案名”反查包
    setEnabledSchemata(selectedIds.toTypedArray())  // ← 写进“活动包”的文件
    selectSchema(selectedIds.first())
}
```

而 `Rime.selectSchemaCrossPackage()` 的第一句是：

```kotlin
val pkgId = SchemaPackageManager.findPackageForSchema(schemaId) ?: return false
```

`findPackageForSchema()` 的实现是：

```kotlin
discoverPackages().find { pkg -> pkg.schemas.any { it.id == schemaId } }?.id
```

`discoverPackages()` 按**包名排序**，所以两个包里存在同名方案时（例如都叫 `wanxiang_pro`），它**永远命中排在前面的那个包**。

### 症状

- 点 `wanxiang-pro` 的对话框、勾选方案、确定 → 配置写进了「另一个包」的文件，活动包也被切到那个包，并且用那个包重新部署了一次；
- 因为 `setEnabledSchemata()` 也写「活动包」的文件，所以看起来像「勾了取消不掉」；
- 该对话框漏了 `setOnDismissListener { refreshPackages() }`，勾完列表里的 `(已启用/总数)` 不刷新（重命名、删除都挂了，只有它没挂）；
- 全不勾时 `return@setPositiveButton`（静默无操作，窗口照关）。

### 我们改成了什么（`PackageListFragment.patch`）

核心原则：**点谁改谁，按包名（目录名）定位，不再按方案名反查。**

1. 新增 `data/packaging/PackageSchemaList.kt`：把 `schema_list` 写进**指定包**的 `default.custom.yaml`。
   - 只替换 `patch:` 下的 `schema_list` 这一个键，其余内容（其它 patch 键、顶层键、注释）原样保留；
   - 改写前备份成 `<文件名>.bak-yyyyMMdd-HHmmss`，最多留 3 份；
   - `patch:` 下子键缩进按实际内容自适应（2 空格 / 4 空格 / tab 都能认）。
2. `showSchemaMultiSelectDialog()` 的确定分支改为 `applySelection(pkg, selectedIds)`：
   - 先 `PackageSchemaList.write(pkg.id, selectedIds)`（写**被点开的那个包**）；
   - 若 `pkg.id == activePackageId` → 只 `selectSchema(first)`（不重新部署）；
   - 否则 → `switchPackage(pkg.id)`（它内部会记住旧包的 `lastSchema`、切活动包、重启 librime 并触发部署）+ `selectSchema(first)`；
   - 最后 `PackageStateManager.updateSchemaAndTheme(...)` + `refreshPackages()`。
3. 对话框补上 `setOnDismissListener { refreshPackages() }`。
4. 「全不勾」保持上游行为（不写盘、不切包、直接返回），只是加了注释说明。

### 怎么验证

1. 准备两个包，且**都含同名方案**（例如 `wanxiang` 和 `wanxiang-pro`，都含 `wanxiang_pro`）；
2. 当前活动包设为 A；进「方案包管理」，点 B 的条目，勾几个方案，确定；
3. 期望：`B/default.custom.yaml` 的 `schema_list` 变成你勾的那几个；活动包切到 B 并部署；A 的文件一个字节都没动；
4. 再点 A 的条目勾选 → 切回 A，B 不再被改；
5. 勾完列表里的 `(n/m)` 应立即刷新。

### 合并上游时注意

- 上游如果重构了 `selectSchemaCrossPackage` / `findPackageForSchema`（比如改成优先当前活动包、或支持「包 + 方案」二元组），**我们这层可以整体丢掉**，改回调用上游 API；
- 上游如果改了对话框类名/布局（`buildDialog`、`setMultiChoiceItems`），只需保留 `applySelection()` 的语义（写对包 + 按包名切包）；
- 注意 `switchPackage()` 与 `selectSchemaCrossPackage()` 的差别：前者按**包名**，后者按**方案名**——这正是问题的分界线。

---

## 2. `data/packaging/SchemaPackageManager.kt`

**未改动 `findPackageForSchema()` 本身**（它是上游代码，改它会波及所有调用方）。

`ensureActivePackage()` 是我们新增的（首次安装、只有一个包时自动选中，避免 `userDataDir` 退回 shared 目录导致部署失败）。详见 `../UPSTREAM-DIFF.md` 里该文件的增删行数。

---

## 3. `librime` 子模块 · 给 `RimeCandidate` 补 `type` 字段

**子模块**：`app/src/main/jni/librime`（`https://github.com/Carlson24/librime-custom.git`，当前指针 `3f778e9`「fix: 优化长句转换性能」）
**文件**：`src/rime_api.h`、`src/rime_api_impl.h`（快照与补丁放在 [`librime/`](./librime/)：`rime_api.h.orig` / `rime_api.h.patch` / `rime_api_impl.h.orig` / `rime_api_impl.h.patch`）

### 上游原行为

C++ 侧的 `Candidate` 一直有来源标记（`src/rime/candidate.h`）：

```cpp
const string& type() const { return type_; }   // recognized by translators in learning phase
```

但 **C API 的 `RimeCandidate` 没有透出它**：

```c
typedef struct rime_candidate_t {
  char* text;
  char* comment;
  void* reserved;
} RimeCandidate;
```

而我们的 JNI（`app/src/main/jni/librime_jni/`，**这部分在分叉点 `094e6894` 里就存在，不是我们改的**）已经有三处在读 `candidate.type`：

- `helper-types.h:51` `type(c.type ? c.type : "") {}`
- `helper-types.h:129-130` `if (candidate.type) { destCandidates.back().type = candidate.type; }`
- `objconv.h:65` `*JString(env, candidate.type)` → 构造 Kotlin 的 `CandidateProto`

`CandidateProto` 的 `type` 会被候选视图用来右键/长按操作（`PagedCandidatesUi.kt:119 onCandidateAction(position, text, candidate.type, v)`、`BaseUnrolledCandidateWindow.kt:143 showCandidateActionMenu(..., type = holder.type)`）。

### 症状

子模块指针 `3f778e9` 的 `src/rime_api.h` **没有** `char* type`，所以：

- 干净 clone / `git submodule update` 之后，JNI 里那句 `c.type` **编译不过**；
- 只有把下面这 11 行补回去才能构建。

也就是说：这 11 行是「让子模块 HEAD 与我们的 JNI 期望对齐」的**必需品**，不是可选优化。

### 我们补了什么（`librime/*.patch`，共 11 行）

1. `src/rime_api.h`：结构体加一个字段

   ```c
   typedef struct rime_candidate_t {
     char* text;
     char* comment;
     char* type;      // ← 新增
     void* reserved;
   } RimeCandidate;
   ```

2. `src/rime_api_impl.h`：
   - `rime_candidate_copy()` 里把 `src->type()` 拷进 `dest->type`（空串则给 `nullptr`）；
   - `RimeFreeContext()`、`RimeCandidateListNext()`、`RimeCandidateListEnd()` 三处释放路径各补 `delete[] ...candidates[i].type`，避免泄漏。

### 怎么验证

```powershell
# 1. 子模块是否干净（应只剩 plugins/ 未跟踪）
git -C app/src/main/jni/librime status --porcelain

# 2. 补丁能重新打上
git -C app/src/main/jni/librime stash            # 或手动恢复干净
$P = (Resolve-Path docs/upstream-patches/librime).Path
git -C app/src/main/jni/librime apply "$P/rime_api.h.patch"
git -C app/src/main/jni/librime apply "$P/rime_api_impl.h.patch"

# 3. 编译 app 的 native 部分，确认 JNI 里 c.type 不再报错
.\gradlew :app:assembleDebug
```

功能侧验证：随便打几个字，长按/右键候选 → 候选操作菜单能弹出（它需要 `type` 才能判断候选来源）。

### 合并上游时注意（**升级 librime 前必读**）

- 上游 `e5b2f010` 只动 Kotlin，**不影响这里**；
- 但上游 `59d508e3 update librime` 会把子模块指针从 `3f778e9` 推到 `4dcfb5f3`（librime 17 个提交）。我核对过 `4dcfb5f3` 的 `rime_api.h` **同样没有** `char* type`，所以：
  1. 先 `git submodule` 切到目标提交；
  2. **立刻重打这两个补丁**（否则编译直接失败，而且报错点会落在 `helper-types.h` / `objconv.h`，很容易误以为是 JNI 的问题）；
  3. 再全量重编（librime 全量编译十几分钟）并回归：部署时长、九键、辅助码、长句转换、候选操作菜单。
- 如果上游哪天在 C API 里正式加了 `type`（或换了字段名），**以他们的为准**，把这两个补丁删掉、改为按新结构适配 JNI；
- 这个补丁目前只存在于工作区、没有提交进子模块（`git -C app/src/main/jni/librime status` 会显示 ` M src/rime_api.h`、` M src/rime_api_impl.h`），所以**换机 clone 或 `submodule update` 都会丢**——这也是本节存在的原因。

