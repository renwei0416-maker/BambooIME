# 苍竹输入法 / BambooIME — 品牌化改动清单

本文件记录把 LyraIME 派生成 **BambooIME（苍竹输入法）** 所做的全部标识改动，
便于以后核对「哪些是上游的、哪些是我们改的」。

上游：LyraIME 5.5.2（分支 `lyraime`，基线 commit `094e6894`），GPL-3.0。
本仓库与上游**可共存安装**，互不覆盖。

## 标识

| 项目 | 上游 | 本仓库 |
| --- | --- | --- |
| `applicationId`（release） | `com.carlson.lyraime` | `com.bambooime` |
| `applicationId`（debug） | `com.carlson.lyraime.debug` | `com.bambooime.debug` |
| 显示名（英文） | LyraIME | BambooIME |
| 显示名（简中） | 灵韵输入法 | 苍竹输入法 |
| 显示名（繁中） | 靈韻輸入法 | 蒼竹輸入法 |
| debug 显示名 | LyraIME (Debug) | BambooIME (Debug) |
| `versionName` | 5.5.2 | 1.0.0 |
| `versionCode` | 日期式 `yyyyMMdd` | 保留日期式（自增，升级不会冲突） |
| 用户数据目录 | `Documents/LyraIME[Debug]` | `Documents/BambooIME[Debug]` |
| `app_name`（Rime 侧） | `rime.lyraime` | `rime.bambooime` |
| `distribution_name` | `LyraIme` | `BambooIME` |
| `distribution_code_name` | `lyraime` | `bambooime` |
| 广播 action | `<id>.action.DEPLOY`（由 `BuildConfig.APPLICATION_ID` 拼接） | `com.bambooime.action.DEPLOY`（自动跟随） |
| provider authority | `${applicationId}.provider` / `.fileprovider` | `com.bambooime.provider` / `.fileprovider`（自动跟随） |
| 图标 | Rime 社区图标 | 墨绿圆角方底 + 楷体「竹」（见下） |

Java/Kotlin 内部命名空间 **保持 `com.osfans.trime` 不变**（用户不可见，
改动它需要动 441 个文件与全部 import，风险高、收益为零）。

## 图标

源图与生成脚本在仓库外的 `C:\Users\Administrator\Desktop\Ai_Work\logo2\`：

- `make_icons.py` — 生成全部图标资源
- `A_…`–`F_…png` — 设计稿（F 为选定方案）
- `icon_play_512.png` / `icon_disc_512.png` — 512×512 展示图

写入 App 的资源：

| 资源 | 说明 |
| --- | --- |
| `values/ic_app_icon_background.xml` | 背景色 `#2F4F43` |
| `mipmap-*/ic_app_icon_foreground.png` | Adaptive Icon 前景（108dp 画布，字形在 72dp 安全区内），同时用作 `monochrome` 单色层 |
| `mipmap-*/ic_app_icon.png` | 传统方形图标（圆角 + 实底） |
| `mipmap-*/ic_app_icon_round.png` | 圆形图标兜底 |
| `drawable-nodpi/ic_stat_bamboo.png` | 通知栏单色小图标（白「竹」透明底） |
| `mipmap-anydpi-v26/ic_app_icon{,_round}.xml` | Adaptive Icon 描述，`foreground`/`monochrome` 指向上面的前景 PNG |

设计：墨绿 `#2F4F43` 底 + 米白 `#E8EFE4` 楷体「竹」，字形做了轻微加粗
（`stroke_width`）以保证 48 px 下笔画不糊。

## 签名

release 使用自建 keystore：`C:\Users\Administrator\Desktop\Ai_Work\keys\bambooime.jks`
（alias `bambooime`，RSA 2048，10000 天），凭据在同目录 `README.txt` 与仓库根
`keystore.properties`（已被 `.gitignore` 忽略）。

> 该 keystore 与官方 LyraIME **不同**，因此不能覆盖安装官方版；
> 但正因为包名不同，两者可以**并存**，输入法列表里会同时出现
> 「苍竹输入法」和「灵韵输入法」。

## 数据迁移

包名与用户目录都变了，系统视为全新应用。旧数据不会自动带过来；
如需保留词库/方案，把 `/sdcard/Documents/LyraIME*` 整个目录复制成
`/sdcard/Documents/BambooIME*` 即可（本仓库刻意**不做**自动迁移，
避免动到用户既有数据）。
