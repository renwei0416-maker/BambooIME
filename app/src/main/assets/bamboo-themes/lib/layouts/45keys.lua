--[[
layouts.45keys.lua  LyraIME灵韵输入法 万象45键基础布局脚本
依赖文件：
1. key_style.lua：safe_require加载，S包含全套按键样式模板（number/base/mantle/crust/shift/backspace等）
2. 配色脚本 latte.lua / mocha.lua：key_style内部使用颜色名字查表，控制按键背景、文字颜色
3. preset_keys.lua：提供全部内置命令：SelectAll、Page_Up、Cut、Copy、Paste、ZiTools、Deploy、ThemeReload等
字段释义：
merge(A, key{})：继承模板A的颜色、字号、文字偏移配置，后面key{}的配置会覆盖继承来的属性
click：单击触发，输出字符 / 内置命令
double_click：双击触发动作
long_click：长按触发动作
swipe_up：按键向上滑动手势
swipe_down：按键向下滑动手势
popup = { ... }：长按弹出菜单列表，可以是字符，也可以是preset_keys定义的内置命令
label：按键主显示文字
label_symbol：按键右上角上档符号/矢量图标；align="justify" 两端分散对齐
hint：按键底部助记提示文字/图标，可通过HintSwitch手势开关显示/隐藏
spacer = true：空白占位按键，用于制造左右边距缩进
width：按键宽度，0~1，代表占键盘总宽度的比例
height：行高度，0~1，代表占键盘总高度比例
ascii_mode = false：切换到此布局默认中文输入
label_transform = "NONE"：中文模式不自动把小写字母转大写，完全由label手动控制显示
lock = false：切换App焦点，不会强制锁定本布局
ic@xxx：灵韵内置矢量图标，依赖CarlsonFont.ttf字体，缺失会显示方框□
]]

-- 安全加载按键样式模板 key_style.lua，得到S对象，包含所有预定义按键样式
local KS = safe_require("keyboard_switch")
local S = safe_require("key_style") -- 按键样式

-- 定义45键键盘布局对象
local keyboard = keyboard {
  name = "45 键布局",                     -- 布局对外显示名称
  author = "Carlson24(鹤衔春雪)",         -- 布局作者
  ascii_mode = false,                      -- 打开布局默认中文输入模式；true=默认英文
  reset_ascii_mode = true,            -- 每次进入这个键盘都强制回中文（不回退到上一次的英文状态）
  label_transform = "UPPERCASE",                -- 文字转换策略：NONE不自动大小写；uppercase强制中文模式大写字母
  lock = false,                            -- false：切换应用不锁定当前布局

  rows = { -- 键盘全部行集合，每一个row{}代表键盘一行

    -- ==========第1行：数字行 1‑0，使用S.number数字按键样式模板 ==========
    row {
      height = 0.14, -- 当前行高度占键盘总高度比例
      keys = {
        -- 1键：单击输出1；右上角justify分散对齐，空格+!；上滑输出!
        key(merge(S.number, key { click = "1", label_symbol = { { text = { " ", "!" }, align = "justify" } }, swipe_up = "!", swipe_down = "BambooTip1" })),
        -- 2键：单击输出2，上滑输出@
        key(merge(S.number, key { click = "2", label_symbol = { { text = { " ", "@" }, align = "justify" } }, swipe_up = "@", swipe_down = "BambooTip2" })),
        -- 3键：单击输出3，上滑输出#
        key(merge(S.number, key { click = "3", label_symbol = { { text = { " ", "#" }, align = "justify" } }, swipe_up = "#", swipe_down = "BambooTip3" })),
        -- 4键：单击输出4，上滑输出$
        key(merge(S.number, key { click = "4", label_symbol = { { text = { " ", "$" }, align = "justify" } }, swipe_up = "$", swipe_down = "BambooTip4" })),
        -- 5键：单击输出5，上滑输出%
        key(merge(S.number, key { click = "5", label_symbol = { { text = { " ", "%" }, align = "justify" } }, swipe_up = "%", swipe_down = "BambooTip5" })),
        -- 6键：单击输出6，上滑输出^
        key(merge(S.number, key { click = "6", label_symbol = { { text = { " ", "^" }, align = "justify" } }, swipe_up = "^", swipe_down = "BambooTip6" })),
        -- 7键：单击输出7，上滑输出&
        key(merge(S.number, key { click = "7", label_symbol = { { text = { " ", "&" }, align = "justify" } }, swipe_up = "&" })),
        -- 8键：单击输出8，上滑输出*
        key(merge(S.number, key { click = "8", label_symbol = { { text = { " ", "*" }, align = "justify" } }, swipe_up = "*" })),
        -- 9键：单击输出9，上滑输出(
        key(merge(S.number, key { click = "9", label_symbol = { { text = { " ", "(" }, align = "justify" } }, swipe_up = "(" })),
        -- 0键：单击输出0，上滑输出)
        key(merge(S.number, key { click = "0", label_symbol = { { text = { " ", ")" }, align = "justify" } }, swipe_up = ")" }))
      }
    },

    -- ==========第2行 q‑p字母行，S.base模板（最浅底色按键） ==========
    row {
      keys = {
        -- q键：单击q；右上角`；上滑输出`；长按弹出大写Q
        key(merge(S.base, key { click = "q", label_symbol = { { text = { " ", "`" }, align = "justify" } }, swipe_up = "`", popup = { "Q" } })),
        -- w键：单击w；右上角~；上滑输出~；长按弹出W
        key(merge(S.base, key { click = "w", label_symbol = { { text = { " ", "~" }, align = "justify" } }, swipe_up = "~", popup = { "W" } })),
        -- e键：单击e；右上角+；上滑输出+；长按弹出E
        key(merge(S.base, key { click = "e", label_symbol = { { text = { " ", "+" }, align = "justify" } }, swipe_up = "+", popup = { "E" } })),
        -- r键：单击r；右上角‑；上滑输出‑；长按弹出R
        key(merge(S.base, key { click = "r", label_symbol = { { text = { " ", "-" }, align = "justify" } }, swipe_up = "-", popup = { "R" } })),
        -- t键：单击t；右上角=；上滑输出=；长按弹出T
        key(merge(S.base, key { click = "t", label_symbol = { { text = { " ", "=" }, align = "justify" } }, swipe_up = "=", swipe_down = "BambooAiOff", popup = { "T" } })),
        -- y键：单击y；右上角_；上滑输出_；长按弹出Y
        key(merge(S.base, key { click = "y", label_symbol = { { text = { " ", "_" }, align = "justify" } }, swipe_up = "_", popup = { "Y" } })),
        -- u键：单击u；右上角{；上滑输出{；长按弹出U
        key(merge(S.base, key { click = "u", label_symbol = { { text = { " ", "{" }, align = "justify" } }, swipe_up = "{", popup = { "U" } })),
        -- i键：单击i；右上角}；上滑输出}；长按弹出I
        key(merge(S.base, key { click = "i", label_symbol = { { text = { " ", "}" }, align = "justify" } }, swipe_up = "}", popup = { "I" } })),
        -- o键：单击o；右上角:；上滑输出:；长按弹出O
        key(merge(S.base, key { click = "o", label_symbol = { { text = { " ", ":" }, align = "justify" } }, swipe_up = ":", popup = { "O" } })),
        -- p键：单击p；右上角?；上滑输出?；长按弹出 P ? ¿
        key(merge(S.base, key { click = "p", label_symbol = { { text = { " ", "?" }, align = "justify" } }, swipe_up = "?", popup = { "P", "?", "¿" } }))
      }
    },

    -- ==========第3行 a‑l字母行，S.mantle模板（中间底色），左右spacer制造缩进 ==========
    row {
      keys = {
        key { spacer = true, width = 0.05 }, -- 左侧空白占位，占总宽度5%，产生左边距
        -- a键：单击a；右上角全选图标ic@select‑all；长按弹出A / SelectAll（全选命令）
        key(merge(S.mantle, key { click = "a", label_symbol = { { text = { " ", "ic@select-all" }, align = "justify" } }, popup = { "A", "SelectAll" } })),
        -- s键：单击s；右上角乘号×；上滑输出×；长按弹出S
        key(merge(S.mantle, key { click = "s", label_symbol = { { text = { " ", "×" }, align = "justify" } }, swipe_up = "×", popup = { "S" } })),
        -- d键：单击d；右上角除号÷；上滑输出÷；长按弹出D
        key(merge(S.mantle, key { click = "d", label_symbol = { { text = { " ", "÷" }, align = "justify" } }, swipe_up = "÷", popup = { "D", "ClearAll" } })),
        -- f键：单击f；右上角上翻页图标；长按弹出F / Page_Up（向上翻页）
        key(merge(S.mantle, key { click = "f", label_symbol = { { text = { " ", "ic@page-first" }, align = "justify" } }, popup = { "F", "Page_Up" } })),
        -- g键：单击g；右上角应用菜单图标；长按G / WindowMenu；**下滑打开键盘编辑器 KeyboardEditor**
        key(merge(S.mantle, key { click = "g", label_symbol = { { text = { " ", "ic@apps" }, align = "justify" } }, popup = { "G", "WindowMenu" }, swipe_down = "KeyboardEditor" })),
        -- h键：单击h；右上角下翻页图标；长按 H / Page_Down（向下翻页）
        key(merge(S.mantle, key { click = "h", label_symbol = { { text = { " ", "ic@page-last" }, align = "justify" } }, popup = { "H", "Page_Down" } })),
        -- j键：单击j；右上角双引号\"；上滑输出\"；长按弹出J
        key(merge(S.mantle, key { click = "j", label_symbol = { { text = { " ", "\"" }, align = "justify" } }, swipe_up = "BambooQuote", popup = { "J" } })),
        -- k键：单击k；右上角竖线|；上滑输出|；长按弹出K
        key(merge(S.mantle, key { click = "k", label_symbol = { { text = { " ", "|" }, align = "justify" } }, swipe_up = "|", popup = { "K" } })),
        -- l键：单击l；右上角分号;；上滑输出;；长按弹出 L · §
        key(merge(S.mantle, key { click = "l", label_symbol = { { text = { " ", ";" }, align = "justify" } }, swipe_up = ";", popup = { "L", "·", "§" } })),
        key { spacer = true, width = 0.05 } -- 右侧空白占位，产生右边距
      }
    },

    -- ==========第4行 z‑m + Shift + Backspace，S.crust最深底色模板 ==========
    row {
      keys = {
        -- Shift大小写键：宽度0.15；单击Shift；双击CapsLock大写锁定；显示caps图标；S.shift彩色功能键模板
        key(merge(S.shift, key { click = "Shift", double_click = "CapsLock", label_symbol = { { text = "ic@keyboard-caps" } }, width = 0.15 })),
        -- z键：单击z；右上角反斜杠\；上滑输出\；长按弹出Z
        key(merge(S.crust, key { click = "z", label_symbol = { { text = { " ", "\\" }, align = "justify" } }, swipe_up = "\\", popup = { "Z" } })),
        -- x键：单击x；右上角剪切图标；长按 X / Cut（剪切命令）
        key(merge(S.crust, key { click = "x", label_symbol = { { text = { " ", "ic@content-cut" }, align = "justify" } }, popup = { "X", "Cut" } })),
        -- c键：单击c；右上角复制图标；长按 C / Copy（复制命令）
        key(merge(S.crust, key { click = "c", label_symbol = { { text = { " ", "ic@content-copy" }, align = "justify" } }, popup = { "C", "Copy" } })),
        -- v键：单击v；右上角粘贴图标；长按 V / Paste（粘贴）；**下滑打开剪贴板窗口 WindowClipboard**
        key(merge(S.crust, key { click = "v", label_symbol = { { text = { " ", "ic@content-paste" }, align = "justify" } }, popup = { "V", "Paste" }, swipe_down = "WindowClipboard" })),
        -- b键：单击b；右上角放大镜查字图标；长按 B / ZiTools（梓工具查字网页）
        key(merge(S.crust, key { click = "b", label_symbol = { { text = { " ", "ic@magnify" }, align = "justify" } }, popup = { "B", "ZiTools", "BaiduSearch" } })),
        -- n键：单击n；右上角<；上滑输出<；长按弹出N
        key(merge(S.crust, key { click = "n", label_symbol = { { text = { " ", "<" }, align = "justify" } }, swipe_up = "<", popup = { "N" } })),
        -- m键：单击m；右上角>；上滑输出>；长按弹出M
        key(merge(S.crust, key { click = "m", label_symbol = { { text = { " ", ">" }, align = "justify" } }, swipe_up = ">", popup = { "M" } })),
        -- BackSpace退格删除键，宽度0.15，S.backspace彩色功能键模板
        key(merge(S.backspace, key { click = "BackSpace", swipe_left = "DeleteLine", swipe_right = "BambooRevert", swipe_up = "WordDelete", swipe_down = "Escape", width = 0.15 }))
      }
    },

    -- ==========第5行：底栏，数字切换 / 标点 / 空格 /斜杠 /布局切换 /回车 ==========
    row {
      keys = {
        -- 数字布局切换键 S.symbol模板；单击切数字键盘；长按菜单：计算器、重载主题、部署配置
        key(merge(S.symbol, key { click = "KeyboardNumber", label_symbol = { { text = "ic@calculator-variant" } }, popup = { "BambooSymbol", "Calculator", "ThemeReload", "Deploy" }, has_menu = "Tab", width = 0.15 })),
        -- 逗号键 S.punct模板；主标签"，"；上滑输出[；hint提示图标；下滑 SchemeList：打开配色方案列表
        key(merge(S.punct, key { label = { { text = "，" } }, click = ",", label_symbol = { { text = "[" } }, swipe_up = "[", hint = { { text = "ic@list-box-outline" } }, swipe_down = "SchemeList", key_text_offset_y = -5 })),
        -- 句号键 S.punct模板；主标签"。"；上滑输出]；hint调色板图标；下滑 ThemeList：打开主题列表
        key(merge(S.punct, key { label = { { text = "。" } }, click = ".", label_symbol = { { text = "]" } }, swipe_up = "]", hint = { { text = "ic@palette-swatch-outline" } }, swipe_down = "ThemeList", key_text_offset_y = -5 })),
        -- 空格键：宽度0.3；主标签"指尖文流"；单击空格Space；长按VoiceAssist语音输入；hint提示--；下滑LiquidEmoji打开表情面板；文字纵向偏移+1
        key(merge(S.space, key { label = { { text = "␣" } }, click = "Space", long_click = "VoiceAi", swipe_down = "LiquidEmoji", width = 0.3 })),
        -- key { label = { { text = "␣" } }, click = "Space", long_click = "VoiceAi", swipe_down = "LiquidEmoji", width = 0.4 },
        -- /斜杠按键 S.slash模板；上滑输出单引号'；hint灯泡图标；下滑HideSymbols：关掉按键右上角符号与顶部小图标；左滑HintSwitch：开关按键底部hint助记提示；单引号放大1.5倍加粗
        key(merge(S.slash, key { click = "/", label_symbol = { { text = "'", bold = true, scale = 1.5 } }, swipe_up = "BambooQuoteSingle", hint = { { text = "ic@lightbulb-outline" } }, swipe_down = "HideSymbols", swipe_left = "HintSwitch" })),
        -- 模式切换 S.switch模板；单击ModeSwitch；长按弹出切换键盘布局：英文/18键/9键；hint网页图标；下滑IMESwitch切换Rime输入方案
        key(merge(S.switch, key { click = "ModeSwitch" or "KeyboardEnglish", label_symbol = { { text = "ic@keyboard-settings-outline" } }, popup = KS.popup_for("Keyboard45key"), hint = { { text = "ic@web" } }, swipe_down = "IMESwitch" })),
        -- 回车键 S.enter模板；label使用enter_labels，根据上下文自动切换图标（搜索/发送/完成）；单击Enter；宽度0.15
        key(merge(S.enter, key { swipe_up = "AI", swipe_right = "ClipboardAI", label = { { text = "enter_labels" } }, click = "Enter", width = 0.15 }))
      }
    }
  }
}

return keyboard -- 输出45键布局对象，NekoCatppuccin.lua通过safe_require加载此布局