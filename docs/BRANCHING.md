# 分支与远端约定

本仓库**只有一个主干分支：`main`**。所有源码、主题、文档的提交都落在它上面。

## 远端

| remote | 地址 | 用途 |
| --- | --- | --- |
| `bamboo` | `https://github.com/renwei0416-maker/BambooIME.git` | 我们自己的 GitHub 仓库（发布、README、docs、release 资产） |
| `cnb` | `https://cnb.cool/renwei.co/BambooIME` | 国内镜像（release 资产、模型包、更新清单） |
| `origin` | `https://github.com/Carlson24/LyraIME.git` | **上游**（LyraIME），只用于查看/对照/cherry-pick，**不向它推送** |

CNB 若要用命名 remote，可执行：

```bash
git remote add cnb https://cnb:<token>@cnb.cool/renwei.co/BambooIME.git
```

## 不要再使用 `theme-editor` 这个名字

`theme-editor` 是早期做主题编辑器时误建的分支名，它曾经承载过全部源码历史。
2026-11-24 已做本地整理：

```bash
git branch -m main docs-main-archive   # 旧的 docs 线存档
git branch -m theme-editor main        # 源码线成为唯一主干
```

因此：**提交、推送、文档、对话里一律只写 `main`**，不要再出现 `theme-editor`。

## 远端 main 与本地 main 的关系（重要）

- 远端 `bamboo/main` 目前只有 `README.md` 与 `docs/`，**没有源码**（历史是另起的一条 docs 线）。
- 本地 `main` 是完整的源码历史（数千个提交）。
- 两者没有共同祖先，所以将来第一次把源码推上去时，需要 `git push --force` 覆盖远端 main，
  并且**保留完整历史**（不要 squash 成一个提交）。
- 推送源码属于独立动作，需要单独确认后再做；本项目当前**只维护 release 资产与文档**。

## 发布流程

1. 改版本号：`repo/app/build.gradle.kts`（`versionName`）与 `repo/gradle.properties`（`buildVersionName`，必要时 `buildVersionCodeSeq`）。
2. `./gradlew :app:assembleRelease`，产物在 `repo/app/build/outputs/apk/release/`。
3. 组 `dist/release-<版本>/`：APK + 主题包 + `whats-new-<版本>.md`。
4. 更新 `docs/version.json` 并提交到 main。
5. GitHub：`gh release create` / `gh release upload … --clobber`；CNB：`-/releases` 上传同名资产（`overwrite: true`）。
6. 更新清单（`version.json`）三处都要更新：仓库 `docs/`、GitHub release 资产、CNB `update-manifest` 资产。
