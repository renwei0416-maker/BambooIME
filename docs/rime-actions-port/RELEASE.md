# 苍竹输入法 / BambooIME 发版说明

## 当前版本

| 项目 | 值 |
| --- | --- |
| 版本名 | `1.0.0` |
| 版本号 | `20261106`（日期式，每天自增） |
| 包名（release） | `com.bambooime` |
| 包名（debug） | `com.bambooime.debug` |
| ABI | `arm64-v8a` |
| minSdk / targetSdk / compileSdk | 29 / 37 / 37 |
| release APK 体积 | 25.29 MB |
| release SHA-256 | `740e98288fefba86cc622c141c552404028e33ddbd61faf7766636015153daef` |
| 签名 | 自建 keystore（`bambooime.jks`，alias `bambooime`），APK Signature Scheme v3 |

## 构建

先导入环境（每个新终端都要）：

```powershell
. C:\Users\Administrator\Desktop\Ai_Work\lyraime-env.ps1
Set-Location C:\Users\Administrator\Desktop\Ai_Work\_lyraime_audit\LyraIME
$env:LYRAIME_DL_CACHE = "C:\Users\Administrator\Downloads\lyraime-build-env"
```

```powershell
.\gradlew.bat :app:assembleDebug     # 调试包
.\gradlew.bat :app:assembleRelease   # 正式包（R8 + 签名）
```

产物：

- `app/build/outputs/apk/debug/com.bambooime-1.0.0-arm64-v8a-debug.apk`
- `app/build/outputs/apk/release/com.bambooime-1.0.0-arm64-v8a-release.apk`

### 离线依赖（首次或清理后需要）

本机直连 GitHub 极慢（0.04–0.05 MB/s），构建脚本已按 CMake 期望的文件名预置种子：
需要时把归档放到 `%LYRAIME_DL_CACHE%` 或 `app/src/main/jni/`，详见
`docs/rime-actions-port/README.md` 的「本机构建环境」一节。

## 签名

`keystore.properties`（仓库根，已被 `.gitignore` 忽略）：

```
storeFile=C:\\Users\\Administrator\\Desktop\\Ai_Work\\keys\\bambooime.jks
storePassword=...
keyAlias=bambooime
keyPassword=...
```

> keystore 与密码请另外备份。丢失后无法给已安装的苍竹输入法出升级包。

## 与灵韵（LyraIME）共存

包名不同 → 两个输入法可以同时安装、同时出现在输入法列表里。
Rime 用户数据各自独立：

- 苍竹 release：`/sdcard/Documents/BambooIME/`
- 苍竹 debug：`/sdcard/Documents/BambooIMEDebug/`
- 灵韵：`/sdcard/Documents/LyraIME/`（灵韵 debug 为 `LyraIMEDebug/`）

迁移词库/方案 = 把旧目录整个复制到新目录。

## 发版前的检查清单

- [x] `:app:assembleRelease` 构建通过（R8 + 签名）
- [x] `apksigner verify` 通过（v3）
- [x] R8 后 `com.osfans.trime.actions.bamboo.*` 全部保留（`proguard-rules.pro` 有 keep 规则）
- [x] `res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml` 已打进包
- [x] APK 内无 `assets/rime-actions/`、native 无 `luaopen_actions`（Lua 方案已移除）
- [ ] 真机安装 + 输入法启用 + 方案导入（本机无 adb，需人工验证）
- [ ] 真机验证：`qq/` 回退、`ctrl+;` 发 AI、`ctrl+alt+;` 发剪贴板、AI 模式切换/退出

## 已知限制（1.0.0）

- 不内置任何 Rime 方案：首次安装需要把方案包放进 `/sdcard/Documents/BambooIME/` 再在设置里启用。
- AI 模式只支持 OpenAI 兼容的 `/chat/completions` 接口。
- API Key 用 Android Keystore 加密，不随备份迁移，换机需重填。
- 手写功能不在本版本（计划下一个版本）。
- 仅 arm64-v8a（x86_64 需改 `gradle.properties` 的 `buildABI`）。
