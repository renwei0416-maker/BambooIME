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
