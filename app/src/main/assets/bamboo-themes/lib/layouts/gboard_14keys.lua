--[[
layouts.37keys_zrm.lua  灵韵LyraIME 万象18键（37键）双拼布局脚本
依赖：
1. key_style.lua：通过safe_require("key_style_gboard")加载按键样式模板S
2. 配色文件latte.lua / mocha.lua：key_style内部使用颜色名字符串查表获取色值
3. 预设命令来自 preset_keys.lua，例如：SelectAll、Page_Up、WindowClipboard、Deploy、ThemeReload等
字段说明：
· merge(A, key{})：继承A模板的颜色、字号、偏移属性，覆盖当前key自定义配置
· click：单击触发输出字符/命令
· double_click：双击触发动作
· long_click：长按动作
· swipe_up：上滑手势动作
· swipe_down：下滑手势动作
· popup = { ... }：长按弹出候选菜单，可放字符或preset_keys里的命令名
· label：按键主显示文字
· label_symbol：按键右上角上档符号，支持justify两端对齐布局；支持ic@xxx矢量图标
· hint：按键底部助记提示文字/图标
· spacer = true：空白占位按键，用来制造左右边距
· width：按键宽度，0‑1 相对总键盘宽度比例
· height：行高度比例
· ascii_mode = false：加载此布局默认中文输入模式
· label_transform = "NONE"：中文模式不自动把字母转为大写，完全由label手动控制显示文本
· lock = false：切换app时不锁定此布局，可随配置重置
]]

-- 安全加载按键样式模板 key_style.lua，得到S对象，包含S.number/S.base/S.mantle/S.crust/S.shift/S.backspace等全部样式
-- 14 键：由 18 键布局改出，字母键统一 0.175，Shift/退格/底栏与 18 键同尺寸（BambooIME）
local KS = safe_require("keyboard_switch")
local S = safe_require("key_style2_gboard") -- 14 键专用样式（字母 22）

-- 定义键盘布局对象 keyboard{}
local keyboard = keyboard {
  name = "Gboard 14 键",       -- 布局对外显示名称
  author = "Carlson24(鹤衔春雪)", -- 作者
  ascii_mode = false,        -- 打开布局默认中文模式；true则默认英文
  reset_ascii_mode = true,            -- 每次进入这个键盘都强制回中文（不回退到上一次的英文状态）
  label_transform = "NONE",  -- 文字转换策略：NONE=不自动大小写；uppercase=中文下字母强制大写
  lock = false,              -- false：切换应用焦点时不锁定该键盘布局

  rows = { -- 键盘所有行集合，一行对应一个 row{}

    -- ==========第一行：数字行 1‑0，使用S.number数字按键模板 ==========
    row {
      height = 0.14, -- 当前行高度，占键盘总高度比例0.13
      keys = {
        -- 1键：单击输出1；右上角justify布局，空格+!；上滑输出!；继承S.number样式
        key(merge(S.number, key { click = "1", label_symbol = { { text = { " ", "!" }, align = "justify" } }, swipe_up = "!", swipe_down = "BambooTip1" })),
        -- 2键：单击输出2，上滑@
        key(merge(S.number, key { click = "2", label_symbol = { { text = { " ", "@" }, align = "justify" } }, swipe_up = "@", swipe_down = "BambooTip2" })),
        -- 3键：单击输出3，上滑#
        key(merge(S.number, key { click = "3", label_symbol = { { text = { " ", "#" }, align = "justify" } }, swipe_up = "#", swipe_down = "BambooTip3" })),
        -- 4键：单击输出4，上滑$
        key(merge(S.number, key { click = "4", label_symbol = { { text = { " ", "$" }, align = "justify" } }, swipe_up = "$", swipe_down = "BambooTip4" })),
        -- 5键：单击输出5，上滑%
        key(merge(S.number, key { click = "5", label_symbol = { { text = { " ", "%" }, align = "justify" } }, swipe_up = "%", swipe_down = "BambooTip5" })),
        -- 6键：单击输出6，上滑^
        key(merge(S.number, key { click = "6", label_symbol = { { text = { " ", "^" }, align = "justify" } }, swipe_up = "^", swipe_down = "BambooTip6" })),
        -- 7键：单击输出7，上滑&
        key(merge(S.number, key { click = "7", label_symbol = { { text = { " ", "&" }, align = "justify" } }, swipe_up = "&" })),
        -- 8键：单击输出8，上滑*
        key(merge(S.number, key { click = "8", label_symbol = { { text = { " ", "*" }, align = "justify" } }, swipe_up = "*" })),
        -- 9键：单击输出9，上滑(
        key(merge(S.number, key { click = "9", label_symbol = { { text = { " ", "(" }, align = "justify" } }, swipe_up = "(" })),
        -- 0键：单击输出0，上滑)
        key(merge(S.number, key { click = "0", label_symbol = { { text = { " ", ")" }, align = "justify" } }, swipe_up = ")" }))
      }
    },
    row {
      keys = {
        key { spacer = true, width = 0.02083 }, -- 左侧留白 0.02083（原 0.0625 的三分之一）
        key(merge(S.mantle, key { label = { { text = "QW" } }, click = "q", swipe_left = "q", swipe_right = "w", label_symbol = { { text = "`" } }, swipe_up = "`", popup = { "Q", "W" }, width = 0.18333 })),
        key { spacer = true, width = 0.01042 }, -- 字母之间的间隔
        key(merge(S.mantle, key { label = { { text = "ER" } }, click = "e", swipe_left = "e", swipe_right = "r", label_symbol = { { text = "~" } }, swipe_up = "~", popup = { "E", "R" }, width = 0.18333 })),
        key { spacer = true, width = 0.01042 }, -- 字母之间的间隔
        key(merge(S.mantle, key { label = { { text = "TY" } }, click = "t", swipe_left = "t", swipe_right = "y", label_symbol = { { text = "+" } }, swipe_up = "+", popup = { "T", "Y" }, width = 0.18333 })),
        key { spacer = true, width = 0.01042 }, -- 字母之间的间隔
        key(merge(S.mantle, key { label = { { text = "UI" } }, click = "u", swipe_left = "u", swipe_right = "i", label_symbol = { { text = "=" } }, swipe_up = "=", popup = { "U", "I" }, width = 0.18333 })),
        key { spacer = true, width = 0.01042 }, -- 字母之间的间隔
        key(merge(S.mantle, key { label = { { text = "OP" } }, click = "o", swipe_left = "o", swipe_right = "p", label_symbol = { { text = "{" } }, swipe_up = "{", popup = { "O", "P" }, width = 0.18333 })),
        key { spacer = true, width = 0.02083 } -- 右侧留白
      }
    },
    row {
      keys = {
        key { spacer = true, width = 0.02083 }, -- 左侧留白 0.02083（原 0.0625 的三分之一）
        key(merge(S.mantle, key { label = { { text = "AS" } }, click = "a", swipe_left = "a", swipe_right = "s", label_symbol = { { text = { " ", ":", "ic@select-all", " " }, align = "justify" } }, swipe_up = ":", popup = { "A", "S", "SelectAll" }, width = 0.18333 })),
        key { spacer = true, width = 0.01042 }, -- 字母之间的间隔
        key(merge(S.mantle, key { label = { { text = "DF" } }, click = "d", swipe_left = "d", swipe_right = "f", label_symbol = { { text = "-" } }, swipe_up = "-", popup = { "D", "F" }, width = 0.18333 })),
        key { spacer = true, width = 0.01042 }, -- 字母之间的间隔
        key(merge(S.mantle, key { label = { { text = "GH" } }, click = "g", swipe_left = "g", swipe_right = "h", label_symbol = { { text = { " ", "_", "ic@page-last", " " }, align = "justify" } }, swipe_up = "_", popup = { "G", "H", "Page_Down" }, width = 0.18333 })),
        key { spacer = true, width = 0.01042 }, -- 字母之间的间隔
        key(merge(S.mantle, key { label = { { text = "JK" } }, click = "j", swipe_left = "j", swipe_right = "k", label_symbol = { { text = "|" } }, swipe_up = "|", popup = { "J", "K" }, width = 0.18333 })),
        key { spacer = true, width = 0.01042 }, -- 字母之间的间隔
        key(merge(S.mantle, key { label = { { text = " L " } }, click = "l", label_symbol = { { text = ";" } }, swipe_up = ";", popup = { "L", "·", "§" }, width = 0.18333 })),
        key { spacer = true, width = 0.02083 } -- 右侧留白
      }
    },
    row {
      keys = {
        key(merge(S.shift, key { click = "Shift", double_click = "CapsLock", label_symbol = { { text = "ic@keyboard-caps" } }, width = 0.15 })),
        key(merge(S.crust, key { label = { { text = "ZX" } }, click = "z", swipe_left = "z", swipe_right = "x", label_symbol = { { text = "\\" } }, swipe_up = "\\", popup = { "Z", "X" }, width = 0.175 })),
        key(merge(S.crust, key { label = { { text = "CV" } }, click = "c", swipe_left = "c", swipe_right = "v", label_symbol = { { text = { " ", "\"", "ic@content-paste", " " }, align = "justify" } }, swipe_up = "\"", popup = { "C", "V", "Copy", "Paste" }, swipe_down = "WindowClipboard", width = 0.175 })),
        key(merge(S.crust, key { label = { { text = "BN" } }, click = "b", swipe_left = "b", swipe_right = "n", label_symbol = { { text = "<" } }, swipe_up = "<", popup = { "B", "N", "ZiTools", "BaiduSearch" }, width = 0.175 })),
        key(merge(S.crust, key { label = { { text = " M " } }, click = "m", label_symbol = { { text = ">" } }, swipe_up = ">", popup = { "M" }, width = 0.175 })),
        key(merge(S.backspace, key { click = "BackSpace", swipe_left = "DeleteLine", swipe_right = "BambooRevert", swipe_up = "WordDelete", swipe_down = "Escape", width = 0.15 }))
      }
    },
    row {
      keys = {
        -- 数字键盘切换键：S.symbol模板；单击KeyboardNumber切数字布局；长按菜单：计算器、重载主题、部署配置；has_menu=Tab；宽度0.15
        key(merge(S.symbol, key { click = "KeyboardNumber", label_symbol = { { text = "ic@calculator-variant" } }, popup = { "BambooSymbol", "Calculator", "ThemeReload", "Deploy" }, has_menu = "Tab", width = 0.15 })),
        -- 逗号键 S.punct模板；显示"，"；单击逗号,；上滑[；hint提示图标；下滑 SchemeList切换配色方案列表
        key(merge(S.punct, key { label = { { text = "，" } }, click = ",", label_symbol = { { text = "[" } }, swipe_up = "[", hint = { { text = "ic@list-box-outline" } }, swipe_down = "SchemeList", key_text_offset_y = -5 })),
        -- 句号键 S.punct模板；显示"。"；单击.；上滑]；hint调色板图标；下滑 ThemeList切换主题列表
        key(merge(S.punct, key { label = { { text = "。" } }, click = ".", label_symbol = { { text = "]" } }, swipe_up = "]", hint = { { text = "ic@palette-swatch-outline" } }, swipe_down = "ThemeList", key_text_offset_y = -5 })),
        -- 空格键：宽度0.3；主标签"指尖文流"；单击空格Space；长按VoiceAssist语音输入；hint提示--；下滑LiquidEmoji打开表情面板；文字纵向偏移+1
        key(merge(S.space, key { label = { { text = "␣" } }, click = "Space", long_click = "VoiceAi", swipe_down = "LiquidEmoji", width = 0.3 })),
        -- /斜杠按键 S.slash模板；单击/；上滑单引号'；hint灯泡图标；下滑HideSymbols关符号/顶部图标，左滑HintSwitch开关助记提示
        key(merge(S.slash, key { click = "/", label_symbol = { { text = "'" } }, swipe_up = "BambooQuoteSingle", hint = { { text = "ic@lightbulb-outline" } }, swipe_down = "HideSymbols", swipe_left = "HintSwitch" })),
        -- 模式切换 S.switch模板；单击ModeSwitch；设置图标；长按弹出：英文、45键、9键布局；hint网页图标；下滑IMESwitch切换Rime输入方案
        key(merge(S.switch, key { click = "ModeSwitch", label_symbol = { { text = "ic@keyboard-settings-outline" } }, popup = KS.popup_for("Keyboard14key"), hint = { { text = "ic@web" } }, swipe_down = "IMESwitch" })),
        -- 回车键 S.enter模板；使用enter_labels动态图标；单击Enter；宽度0.15
        key(merge(S.enter, key { swipe_up = "AI", swipe_right = "ClipboardAI", label = { { text = "enter_labels" } }, click = "Enter", width = 0.15 }))
      }
    }
  }
}

return keyboard -- 将此键盘布局对象导出，NekoCatppuccin.lua通过safe_require加载这个布局
