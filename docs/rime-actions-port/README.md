# rime-actions 移植记录（已归档）

本目录记录把 [rime-actions](https://github.com/renwei0416-maker/rime-actions) 的
功能搬进 **苍竹输入法 / BambooIME** 的过程，以及中间走过的弯路。

> **现状**：早期那套「Lua 业务层 + 原生 `actions` 模块 + InputConnection 桥」的方案
> **已经整体移除**。现在生效的是纯 Kotlin 的「苍竹增强」，见
> [`BAMBOO-ACTIONS.md`](BAMBOO-ACTIONS.md)。

## 为什么放弃 Lua 方案

1. **接线要动用户的方案文件**。Rime 的 Lua 处理器必须靠 `lua_processor@*xxx` 接进
   `engine/processors`，而那块配置属于用户自己的方案包（如万象）。我们不愿意，
   也不应该去改用户的方案文件。
2. **模块名冲突**。`actions.lua` 的模块名就是 `actions`，和我们编译进 APK 的原生
   `actions` 模块撞车，`package.preload` 抢先命中，报 `attempt to call a nil value`。
3. **接口版本漂移**。上游 `actions.lua` 后来换成了任务化接口
   （`job_start` / `job_result` / `job_release`），而我们实现的是上一版
   `press_backspace` 那一套，运行时会直接报
   `actions.lua: actions.dll is too old (no job_start)`。
4. **部署成本高**。改一行配置要重编整个方案（万象包里仅语法模型就 400 MB），
   动辄十几分钟。
5. 我们真正需要的**只有两件事**：连续回退、AI 模式。这两件事 Kotlin 侧都能直接做，
   不需要 Lua，也不需要原生模块。

## 已删除的东西（2026-11 清理）

| 类别 | 内容 |
| --- | --- |
| 原生 | `app/src/main/jni/librime_jni/actions_module.cc`（12 个 Lua 函数）、`librime_jni/CMakeLists.txt` 里的 lua 头文件路径、`rime_jni.cc` 里的 `actions_module_init` |
| 注入补丁 | `patches/lyraime-actions.patch`、`build-logic/.../NativeBaseConventionPlugin.kt` 的第 5 条补丁与哨兵、`Makefile` 的对应行、`librime-lua/src/lib/lua.cc` 里的 `package.preload` 注入（已还原成上游原样） |
| Kotlin | `actions/ActionsBridge.kt`、`actions/ActionsLuaDeployer.kt`、`proguard-rules.pro` 里对应的 keep 规则 |
| 资源 | `app/src/main/assets/rime-actions/`（`actions.lua`、两个 AI 拼音过滤器、示例配置、上游 LICENSE） |

清理后的验证：`:app:assembleDebug` 通过；`librime_jni.so` 里
`luaopen_actions` 与 `Java_..._ActionsBridge_*` 符号均为 0 个；
APK 内 `assets/rime-actions/` 已不存在。

## 保留的历史结论（仍然有效）

- 回删用 `InputConnection.deleteSurroundingTextInCodePoints`，并按 **UTF-16 code unit**
  换算回退方案；账本按「用户感知字符（码点）」记账，和上游 `utf8.len` 口径一致。
- 账本只在**动作结果确认后**扣减；无法确认就记「结果不确定」，不猜。
- 动作前校验编辑会话（`editorEpoch`），换编辑器/切 App 时丢弃过期结果。
- 不在输入线程上做网络请求；AI 请求在 `Dispatchers.IO`，回来时校验 epoch 再上屏。
- 不伪造成功：上游那套「部分删除也报完整成功」的缺陷没有照抄。
