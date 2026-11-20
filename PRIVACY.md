<!--
SPDX-FileCopyrightText: 2026 BambooIME contributors

SPDX-License-Identifier: GPL-3.0-or-later
-->

# 隐私政策 · Privacy Policy

最后更新：2026-11-20

苍竹输入法（BambooIME，以下称「本应用」）是基于同文输入法（Trime）与灵韵输入法（LyraIME）的开源安卓输入法。
本应用**没有账号体系、没有广告、没有统计/埋点 SDK**，默认**不收集、不上传**你的输入内容。

## 一、我们不收集什么

- **不收集、不上传你的键盘输入内容**（包括候选、上屏文字、词频习惯）。
- 不收集联系人、短信、通话记录、位置、IMEI / Android ID / 广告标识等设备标识。
- 不做用户画像、不接入任何第三方统计或广告服务。

用户词典、词频、剪贴板历史、主题与配置**全部只存在本机**，你可以随时在设置里清空或直接删除对应目录。

## 二、申请了哪些权限，分别用来做什么

| 权限 / 能力 | 用途 | 数据是否离开本机 |
|---|---|---|
| 存储（`READ/WRITE_EXTERNAL_STORAGE`、`READ_MEDIA_IMAGES`、`MANAGE_EXTERNAL_STORAGE`） | 读写 `Documents/BambooIME/` 下的配置、词库、主题、日志与截图；从相册选图做「拍照识字」 | 否 |
| 麦克风（`RECORD_AUDIO`） | 语音输入。语音识别由**内置离线模型（sherpa-onnx）在本机完成** | 否（除非你另外开启「语音发 AI」，那也只是把识别出的**文字**交给 AI） |
| 相机（`CAMERA`） | 「拍照识字」取景。OCR 由**内置离线模型（PP-OCRv5）在本机完成** | 否（除非你主动点「问 AI / 翻译」，那时只发送你框选的那张图） |
| 无障碍服务（`ShotService`，`BIND_ACCESSIBILITY_SERVICE`） | **仅**用于「截图」功能的取景与悬浮按钮。本应用只调用系统截图 API，不读取界面文字、不模拟点击其它应用、不录制屏幕 | 否 |
| 网络（`INTERNET`、`ACCESS_NETWORK_STATE`） | 只在你**主动使用**下列功能时才联网：AI 文本处理（润色/翻译/改写等）、AI 识图、下载方案包 / 主题包、检查更新 | 是，见第四节 |
| 通知（`POST_NOTIFICATIONS`） | 前台服务与状态提示 | 否 |
| 开机启动（`RECEIVE_BOOT_COMPLETED`） | 重启后恢复输入法状态 | 否 |
| 振动（`VIBRATE`） | 按键反馈 | 否 |
| 接收外部语音 App 的文字（`com.brycewg.asrkb.EXTERNAL_SPEECH` 等） | 让第三方语音输入 App 把识别结果交给本应用上屏（需要你显式授权） | 否 |

关于「存储」的说明：`MANAGE_EXTERNAL_STORAGE`（所有文件访问权限）在系统层面确实能访问全部存储。
本应用申请它只是为了在 `Documents/BambooIME/` 里稳定读写配置、词库、主题与截图，**不会扫描或上传你其它的文件**。
如果你不给这个权限，核心输入功能仍然可用，只是导入/导出与截图保存会受限。

关于「无障碍服务」的说明：这是安卓对「读取屏幕」类能力的唯一正规入口，因此系统会给出较强的提示。
本应用只使用其中的 `takeScreenshot()`，用于截图浮球与截图界面；**没有注册任何界面事件回调做内容采集**。

## 三、数据存在哪里

| 内容 | 位置 | 怎么删 |
|---|---|---|
| 配置、方案、词库、主题、日志、截图 | `Documents/BambooIME/` | 直接删目录，或设置页里清空 |
| 缓存与临时文件 | 应用私有目录（卸载即清） | 系统「清除缓存」或卸载 |
| 剪贴板历史 | 本机数据库（应用私有） | 剪贴板面板里逐条删除或一键清空 |
| AI 的 API Key | Android Keystore 加密后存本机 | 设置页点「清除」 |

API Key 不写进代码、不随系统备份或换机迁移，换机需要重新填写。

## 四、什么时候会联网、发什么

只有你**主动触发**下面这些操作时才会联网，其它任何时刻本应用不发起网络请求：

1. **AI 文本处理**：把你选中的文字（或当前候选/剪贴板内容）和你的提示词，发到**你自己在设置里填的接口地址**（默认 `https://api.deepseek.com`，也可以换成任何兼容 OpenAI 接口的服务）。
2. **AI 识图 / 翻译**：额外发送你框选（或拍下）的**那一张图**，会先缩放到长边 1024 像素再发。
3. **方案包 / 主题包下载、检查更新**：只发 HTTP 请求获取文件或版本信息。

发送给谁、留存多久，取决于**你选择的那个服务商**，与本应用无关；请自行阅读对方的隐私政策。
不填 API Key、不使用这些功能时，本应用就是**完全离线**的。

语音识别、手写识别、拍照识字用的是随安装包内置的模型文件，**全部在本机运算**。

## 五、儿童

本应用不面向儿童收集任何信息，也不存在账号与个人资料。

## 六、变更与联系方式

政策有实质变化时会更新本文件的「最后更新」日期，并写进 release notes。
问题、疑问或数据删除请求：<https://github.com/renwei0416-maker/BambooIME/issues>

> 本文按事实陈述实现行为，不构成法律意见；如需用于应用商店上架，请自行按当地法规复核。

---

# Privacy Policy (English, condensed)

Last updated: 2026-11-20

BambooIME is an open-source Android IME based on Trime and LyraIME. There is **no account, no ads, and no analytics SDK**.
We **do not collect or upload your typing** (candidates, committed text, typing habits), nor contacts, location, or device identifiers.
Dictionaries, clipboard history, themes and settings live **only on your device** and can be deleted at any time.

Permissions and why we need them:

- **Storage / All-files access** — read and write `Documents/BambooIME/` (config, dictionaries, themes, logs, screenshots) and pick images for on-device OCR. We do not scan or upload other files.
- **Microphone** — voice input, recognized **on-device** by a bundled sherpa-onnx model. Audio never leaves the device.
- **Camera** — on-device OCR (PP-OCRv5). The image is only sent if you explicitly tap “Ask AI / Translate”.
- **Accessibility service** — used **solely** for the screenshot feature; we only call the system screenshot API and do not read screen content or interact with other apps.
- **Network** — only when you actively use AI text/vision features, download schema/theme packs, or check for updates. Requests go to the endpoint **you configure** (default `https://api.deepseek.com`).
- **Notifications / boot-completed / vibrate / external speech** — status notification, restoring the IME after reboot, key feedback, and accepting text from third-party voice apps you authorize.

Your API key is stored encrypted with Android Keystore and is never included in backups.

Contact: <https://github.com/renwei0416-maker/BambooIME/issues>

> This document states how the app actually behaves; it is not legal advice.
