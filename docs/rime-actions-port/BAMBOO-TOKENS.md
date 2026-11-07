# 苍竹动作名（主题 token）

把「回退」「AI 发送」这类动作**直接绑到键位上**，不用再敲 `qq/`、`jt;` 这些字母码。

- 名字**大小写不敏感**（`BambooRevert` / `bamboorevert` 都认）。
- 绑定后**任何键位槽位**都能用：单击、双击、长按、上下左右滑、弹窗条目。
- 不需要改方案、不需要改 App、不需要重启输入法（改完主题重新载入主题即可）。

---

## 一、怎么绑

在主题的键盘布局 Lua 里，给某个键的**动作字段**填 token 名字即可。例如把退格的右滑绑成「回退」：

```lua
key(merge(S.backspace, key {
    click       = "del",           -- 单击：退格
    swipe_right = "BambooRevert",  -- 右滑：回退最近一笔上屏（和敲 qq/ 一样）
}))
```

常用的动作字段：

| 字段 | 触发方式 |
| --- | --- |
| `click` | 单击 |
| `double_click` | 双击（主题支持时） |
| `long_click` | 长按 |
| `swipe_left` / `swipe_right` / `swipe_up` / `swipe_down` | 四向滑动 |
| `popup` | 长按弹窗里的一项（写进列表） |

弹窗里加一项：

```lua
popup = { "BambooRevert", "BambooAiSend" }
```

（`popup` 里也可以写字面显示名，具体写法以你的主题为准；token 本身就是一个普通字符串。）

---

## 二、可用的 token

| token | 作用 | 等价于 |
| --- | --- | --- |
| `BambooRevert` | 回退**最近一笔**上屏 | 敲 `qq/` |
| `BambooClear` | **清空编辑区**（光标前后的内容全删） | — |
| `BambooDeleteLine` | 删到行首；已经在行首时并入上一行 | — |
| `BambooDeleteWord` | 删掉光标前的一个词 | — |
| `BambooAiSend` | 用当前 AI 模式发送 | 双击发送键 |
| `BambooAiClip` | 用当前 AI 模式发送剪贴板内容 | 敲 `jt;` |
| `BambooAiOff` | 退出 AI 模式 | 敲 `tc;` |
| `BambooSettings` | 打开「苍竹增强」设置页 | 设置 → 苍竹增强 |

### BambooRevert

- 只删**最近一笔**上屏的内容，删多少由账本记录决定，受「最多删除字符数」上限约束；
- 如果当前正在拼写（候选还没上屏），**先丢弃这次拼写，再回退**（等价于先按一下 Esc 再回退）；
- 账本为空（换过输入框、手动删过）时提示「没有可回退的内容」，不会去删别的文字。

### BambooClear / BambooDeleteLine / BambooDeleteWord

这三个是**编辑区动作**，跟输入法自己的候选、编码无关，直接用 Android 的 `InputConnection`
读文本、算范围、一次删掉——所以比主题里「连发 N 次全选再连发 N 次退格」稳得多。

- `BambooClear`：读光标前后的文本，**前、后都删光**（不是全选后删，也不依赖全选是否生效）；
- `BambooDeleteLine`：删到光标所在行的行首；光标本来就在行首时删掉一个换行（并入上一行）；
- `BambooDeleteWord`：按 Unicode 词边界删掉光标前的一个词（跳过尾部空格）；
- 三者都会**先丢掉正在拼写的组合**，再动编辑区；
- 密码框等标记为敏感、或编辑器不给读文本时**不动手**，提示「当前输入框不支持……」；
- 读不到文本时内部会自动退回「按键序列」方案（全选+退格 / Shift+Home+退格 / Ctrl+退格），
  所以你看到的提示可能是「已清空」，但底层走的仍是兜底路径。

### BambooAiSend / BambooAiClip

- 需要先在设置里开启「AI」；发送到哪里、发什么内容由设置决定（拼写候选 / 最后一句 / 上一次上屏 / 剪贴板）；
- 没进入 AI 模式时：若开了「未进入模式时也用默认模式」就用默认模式发送；**该开关关着就什么都不做**（与组合键完全一致，不弹提示）；
- 发送成功后**模式保持**，可以连续发；
- `BambooAiClip` 会把剪贴板内容发出去，不改变当前模式。

### BambooAiOff

- 任何时候都能退出 AI 模式；本来就不在模式里时提示「本来就不在 AI 模式」。

### BambooSettings

- 直接跳到「苍竹增强」设置页，方便把 token 换一种绑法、或临时关掉回退 / AI。

---

## 三、几个完整例子

**1. 退格：单击退格，右滑回退，长按连删**

```lua
key(merge(S.backspace, key {
    click       = "del",
    swipe_right = "BambooRevert",
}))
```

**2. 一个键专门做「AI 发送」**

```lua
key(merge(S.enter, key {
    click      = "Return",
    long_click = "BambooAiSend",
}))
```

**3. 空格左滑 = 发送剪贴板给 AI**

```lua
key(merge(S.space, key {
    click      = "space",
    swipe_left = "BambooAiClip",
}))
```

**4. 一个键退出 AI 模式**

```lua
key(merge(S.slash, key {
    click      = "/",
    swipe_down = "BambooAiOff",
}))
```

> 进入 / 切换模式请用**快捷码**（`ai;`、`lt;` 这类），不要再给每个模式单独绑 token：
> 模式可以自由增删改名，序号 token 会跟着错位，所以没有提供。

**5. 从键盘一键进设置页**

```lua
key(merge(S.settings, key { click = "BambooSettings" }))
```

---

## 四、注意事项

1. **名字要以 `Bamboo` 开头**：App 只认这个前缀，避免把用户真打出来的普通文字误当成动作吞掉。
2. **不要和现有键名冲突**：`del`、`backspace`、`space`、`Return` 这些是键名，token 都带 `Bamboo` 前缀，不会撞。
3. **回退依赖账本**：只对本输入法自己上屏、且没被手动删过的内容有效。换输入框会清账（密码框完全不记账）。
4. **AI 相关 token 受设置开关约束**：AI 没开、或没进模式又没开兜底，会提示而不是静默失败。
5. **改主题后需要重新载入主题**（切换一次主题或重启输入法），token 才会随新布局生效。

---

## 五、和「快捷组合键」的关系

触发 AI 有两条**互不影响**的路，别把它们混在一起：

| | 键位绑定（动作名，**推荐**） | 模拟按键（快捷组合键） |
| --- | --- | --- |
| 主题里怎么写 | `swipe_up = "BambooAiSend"` | `send = "Control+semicolon"` |
| 谁在识别 | 输入法直接认名字 | 输入法把按键流识别成 Ctrl+; 再匹配 |
| 受「触发方式」设置影响 | **不受** | **受**（要 `aiUsesCombo` 开、且组合键字符串一致） |
| 改组合键后 | 不受影响 | 立刻失效 |
| 主要用途 | 屏幕键位 | 外接键盘 / 硬件键盘 |

**主题里的回车键走的是第一条**：

```lua
key(merge(S.enter, key {
    click       = "Enter",
    swipe_up    = "BambooAiSend",    -- 上滑：AI 发送
    swipe_right = "BambooAiClip",    -- 右滑：AI 发送剪贴板
}))
```

（`lib/preset_keys.lua` 里 `AI` / `ClipboardAI` 两个 preset 用的就是 `text = "BambooAiSend"` / `text = "BambooAiClip"`。）

**快捷组合键保留着，专门给外接键盘用**。设置页「苍竹增强 → AI 服务与发送 → AI 快捷组合键 / 剪贴板快捷组合键」里配的值，只对「按下真实组合键」和「键位上写 `send:` 模拟按键」这两种情况生效；你在设置里改它，不会影响已经用动作名绑好的键位。

两条路的终点是同一个发送函数：同样的模式判定、同样的剪贴板取值、同样的上屏逻辑，只是入口不同。

---

## 六、和字母码的关系

两套触发方式并存，互不冲突：

| 方式 | 适合 |
| --- | --- |
| `qq/`、`jt;`、`ai;` 等字母码 | 键盘上没有空余键位时，随手敲两个字母 |
| `Bamboo*` token | 想「一指完成」时，绑到滑动 / 长按上 |

字母码受方案影响（得先离开中文候选状态），token 不受方案影响，**任何输入状态下都能触发**。
