# 苍竹输入法 / BambooIME 发版说明

## 当前版本

| 项目 | 值 |
| --- | --- |
| 版本名 | `1.1.0` |
| 版本号 | `20261108`（日期式，每天自增） |
| 包名（release） | `com.bambooime` |
| 包名（debug） | `com.bambooime.debug` |
| ABI | `arm64-v8a` |
| minSdk / targetSdk / compileSdk | 29 / 37 / 37 |
| release APK 体积 | 29.21 MB（30,623,813 B） |
| release SHA-256 | `c0ba6efe264b9f806e80d9c3f33514b5feee7ba9d5b7abfad231386178aa258b` |
| 签名 | 自建 keystore（`bambooime.jks`，alias `bambooime`），APK Signature Scheme v3 |

## 1.1.0 相对 1.0.0

**新增：手写板**（见 [`BAMBOO-HANDWRITING.md`](BAMBOO-HANDWRITING.md)）

- 内置 `ochwpro` 中文字迹模型（7.0 MB，MIT），离线、不联网；`+PbundleHandwritingModel=false` 可出无模型包。
- 手写板占输入法区域，走 `Handwriting` 动作名进入；抬笔 500ms 自动识别、候选按段累加、空格上屏。
- ⌫ 三层语义（清候选 / 清笔迹 / 删编辑器字）+ 长按连删；右侧符号列按点击频率排序。
- 面板四页签（数字 / 中文 / 英文 / 符号）共 96 个符号；数字页带 `+ - × / = ( ) % √ ^ π ∞`，可直接列算式。
- 手写设置独立一级：停顿时长、候选个数、显示拼音、笔宽、跟随主题配色、模型信息。

**新增：主题动作名（token）**（见 [`BAMBOO-TOKENS.md`](BAMBOO-TOKENS.md)）

`BambooRevert` / `BambooClear` / `BambooDeleteLine` / `BambooDeleteWord` / `BambooAiSend` / `BambooAiClip` /
`BambooAiOff` / `BambooSettings`，直接写在键位的 `click` / `swipe_*` / `popup` 里即可，大小写不敏感。

**改进**

- 回退与 `BambooRevert` 完全等效，删完按编辑器**实际**删除量扣账（异步回读 + 60ms 等待），修复「一个字一个字啃」「字数不准」「一直弹没有可回退的内容」。
- 长按连发 + 滑动改判不再互相打架；带弹窗的键（切换键）长按后可以正常拖动选择。
- AI 发送合并成唯一出口 `aiDispatch()`：动作名、快捷组合键、双击、`jt;` 四个入口行为一致；修掉「先清组合再取内容」导致编码候选发不出去的问题。
- AI 模式增到 **9 套**（新增「聊天」`lt;`）；设置页可直接改码、改提示词、恢复默认。
- 双击 `/` 发送与快捷组合键发送拆成**两个独立开关**（默认：组合键开、双击关）。
- AI 默认地址 `https://api.deepseek.com/v1/chat/completions`、默认模型 `deepseek-flash`，留空即用默认值。
- 系统「选择输入法」里显示的名字改为**应用名**（原先误用的是口号「击响中文之韵」）。

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

无模型包：

```powershell
.\gradlew.bat :app:assembleRelease -PbundleHandwritingModel=false
```

产物：

- `app/build/outputs/apk/debug/com.bambooime-<buildVersionName>-arm64-v8a-debug.apk`
- `app/build/outputs/apk/release/com.bambooime-<buildVersionName>-arm64-v8a-release.apk`

发布时统一改名成 `BambooIME-1.1.0-release-arm64-v8a.apk`（asset 名必须是 ASCII）。

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
- [x] `apksigner verify --print-certs` 通过（v3，`CN=BambooIME`）
- [x] R8 后 `com.osfans.trime.actions.bamboo.**`、`com.osfans.trime.actions.handwriting.**` 全部保留
- [x] 包内含 `assets/handwriting/ochwpro/ochwpro.onnx`(7,001,270 B)、`char_index.json`、`NOTICE-ochwpro.txt`、
      `assets/handwriting/hanzi_pinyin.tsv`、`lib/arm64-v8a/libbamboo_handwriting.so`
- [x] dex 内可见 `BambooRevert` / `BambooClear` / `BambooDeleteLine` / `BambooDeleteWord` /
      `BambooAiSend` / `BambooAiClip` / `BambooAiOff` / `BambooSettings`、`doRevert`、`aiDispatch`
- [x] `res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml` 已打进包
- [x] APK 内无 `assets/rime-actions/`、native 无 `luaopen_actions`（Lua 方案已移除）
- [x] 真机验证：手写板进/出与保持、候选与拼音、⌫ 三层、面板四页签、回退、AI 四个入口（1.1.0 已完成）
- [ ] 无模型包（`-PbundleHandwritingModel=false`）真机验证

## 已知限制（1.1.0）

- 不内置任何 Rime 方案：首次安装需要把方案包放进 `/sdcard/Documents/BambooIME/` 再在设置里启用。
- AI 模式只支持 OpenAI 兼容的 `/chat/completions` 接口。
- API Key 用 Android Keystore 加密，不随备份迁移，换机需重填。
- 手写一次识别一个字；抬笔识别，不是边写边识（见 `docs/ROADMAP.md`）。
- 仅 arm64-v8a（x86_64 需改 `gradle.properties` 的 `buildABI`）。
