--[[
layouts.37keys_zrm.lua  灵韵LyraIME 万象18键（37键）双拼布局脚本
依赖：
1. key_style.lua：通过safe_require("cjn.key_style")加载按键样式模板S
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
local KS = safe_require("keyboard_switch")
local S = safe_require("cjn.key_style2") -- 按键样式

-- 定义键盘布局对象 keyboard{}
local keyboard = keyboard {
  name = "出彩 18 键",       -- 布局对外显示名称
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

    -- ==========第二行：q w‑e r‑t y‑u i‑o p 【18键合并双字母行】，继承S.base浅底色字母按键模板 ==========
    row {
      keys = {
        -- Q键：显示文字" Q "；单击q；上滑输出`；长按弹出菜单Q
        key(merge(S.base, key { key_back_color = "zmu_q", label = { { text = " Q " } }, click = "q", label_symbol = { { text = "`" } }, swipe_up = "`", popup = { "Q" } })),
        -- W‑E合并键：主标签 W E；单击w；上滑~；长按弹出 W / E两个字符
        key(merge(S.base, key { key_back_color = "zmu_w", label = { { text = "W E" } }, click = "w", swipe_left = "w", swipe_right = "e", label_symbol = { { text = "~" } }, swipe_up = "~", popup = { "W", "E" } })),
        -- R‑T合并键：主标签 R T；单击r；上滑+；长按R/T
        key(merge(S.base, key { key_back_color = "zmu_r", label = { { text = "R T" } }, click = "r", swipe_left = "r", swipe_right = "t", label_symbol = { { text = "+" } }, swipe_up = "+", popup = { "R", "T" } })),
        -- Y键：主标签 Y；单击y；上滑=；长按Y
        key(merge(S.base, key { key_back_color = "zmu_y", label = { { text = " Y " } }, click = "y", label_symbol = { { text = "=" } }, swipe_up = "=", popup = { "Y" } })),
        -- U键：主标签 U；单击u；上滑{；长按U
        key(merge(S.base, key { key_back_color = "zmu_u", label = { { text = " U " } }, click = "u", label_symbol = { { text = "{" } }, swipe_up = "{", popup = { "U" } })),
        -- I‑O合并键：主标签 I O；单击i；上滑}；长按I/O
        key(merge(S.base, key { key_back_color = "zmu_i", label = { { text = "I O" } }, click = "i", swipe_left = "i", swipe_right = "o", label_symbol = { { text = "}" } }, swipe_up = "}", popup = { "I", "O" } })),
        -- P键：主标签 P；单击p；上滑?；长按弹出 P / ? / ¿
        key(merge(S.base, key { key_back_color = "zmu_p", label = { { text = " P " } }, click = "p", label_symbol = { { text = "?" } }, swipe_up = "?", popup = { "P", "?", "¿" } }))
      }
    },

    -- ==========第三行：A‑S‑D‑F‑G‑H‑J‑K‑L；左右spacer制造缩进，S.mantle中间层底色模板 ==========
    row {
      keys = {
        key { spacer = true, width = 0.05 }, -- 左侧空白占位，占总宽度5%，制造左边缩进
        -- A键：主标签 A；单击a；右上角图标ic@select‑all全选；长按 A / SelectAll全选命令
        key(merge(S.mantle, key { key_back_color = "zmu_a", label = { { text = " A " } }, click = "a", label_symbol = { { text = "ic@select-all" } }, popup = { "A", "SelectAll" } })),
        -- S‑D合并键：S D；单击s；上滑:；长按S D
        key(merge(S.mantle, key { key_back_color = "zmu_s", label = { { text = "S D" } }, click = "s", swipe_left = "s", swipe_right = "d", label_symbol = { { text = ":" } }, swipe_up = ":", popup = { "S", "D" } })),
        -- F‑G合并键：F G；单击f；justify布局右上角：减号 + 上翻页图标；上滑输出‑；长按 F G Page_Up上翻页
        key(merge(S.mantle, key { key_back_color = "zmu_f", label = { { text = "F G" } }, click = "f", swipe_left = "f", swipe_right = "g", label_symbol = { { text = { " ", "ic@page-first", "-", " " }, align = "justify" } }, swipe_up = "-", popup = { "F", "G", "Page_Up" } })),
        -- H键：H；单击h；justify布局下划线_ + 下翻页图标；上滑_；长按 H / Page_Down下翻页
        key(merge(S.mantle, key { key_back_color = "zmu_h", label = { { text = " H " } }, click = "h", label_symbol = { { text = { " ", "_", "ic@page-last", " " }, align = "justify" } }, swipe_up = "_", popup = { "H", "Page_Down" } })),
        -- J‑K合并键：J K；单击j；上滑|；长按 J K
        key(merge(S.mantle, key { key_back_color = "zmu_j", label = { { text = "J K" } }, click = "j", swipe_left = "j", swipe_right = "k", label_symbol = { { text = "|" } }, swipe_up = "|", popup = { "J", "K" } })),
        -- L键：L；单击l；上滑;；长按 L · §
        key(merge(S.mantle, key { key_back_color = "zmu_l", label = { { text = " L " } }, click = "l", label_symbol = { { text = ";" } }, swipe_up = ";", popup = { "L", "·", "§" } })),
        key { spacer = true, width = 0.05 } -- 右侧空白占位，制造右边缩进
      }
    },

    -- ==========第四行 Z‑X‑C‑V‑B‑N‑M + Shift + Backspace，S.crust最深底色字母模板 ==========
    row {
      keys = {
        -- Shift大小写键：宽度0.15；单击Shift；双击CapsLock大写锁定；显示caps图标；继承S.shift彩色功能键模板
        key(merge(S.shift, key { click = "Shift", double_click = "CapsLock", label_symbol = { { text = "ic@keyboard-caps" } }, width = 0.15 })),
        -- Z键：Z；单击z；上滑反斜杠\；长按Z
        key(merge(S.crust, key { key_back_color = "zmu_z", label = { { text = " Z " } }, click = "z", label_symbol = { { text = "\\" } }, swipe_up = "\\", popup = { "Z" } })),
        -- X‑C合并键：X C；单击x；右上角复制图标ic@content‑copy；长按 X / Copy复制命令
        key(merge(S.crust, key { key_back_color = "zmu_x", label = { { text = "X C" } }, click = "x", swipe_left = "x", swipe_right = "c", label_symbol = { { text = "ic@content-copy" } }, popup = { "X", "Copy" } })),
        -- V键：V；单击v；justify布局引号"+粘贴图标；上滑"；长按V/Paste粘贴；**下滑打开剪贴板窗口 WindowClipboard**
        key(merge(S.crust, key { key_back_color = "zmu_v", label = { { text = " V " } }, click = "v", label_symbol = { { text = { " ", "\"", "ic@content-paste", " " }, align = "justify" } }, swipe_up = "\"", popup = { "V", "Paste" }, swipe_down = "WindowClipboard" })),
        -- B‑N合并键：B N；单击b；上滑<；长按B / ZiTools查字工具
        key(merge(S.crust, key { key_back_color = "zmu_b", label = { { text = "B N" } }, click = "b", swipe_left = "b", swipe_right = "n", label_symbol = { { text = "<" } }, swipe_up = "<", popup = { "B", "ZiTools", "BaiduSearch" } })),
        -- M键：M；单击m；上滑>；长按M
        key(merge(S.crust, key { key_back_color = "zmu_m", label = { { text = " M " } }, click = "m", label_symbol = { { text = ">" } }, swipe_up = ">", popup = { "M" } })),
        -- BackSpace退格键，宽度0.15，继承S.backspace彩色功能键模板
        key(merge(S.backspace, key { click = "BackSpace", swipe_left = "DeleteLine", swipe_right = "BambooRevert", swipe_up = "WordDelete", swipe_down = "Escape", width = 0.15 }))
      }
    },

    -- ==========第五行底栏：数字切换 / 标点 / 空格 /斜杠 /布局切换 /回车 ==========
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
        key(merge(S.slash, key { click = "/", label_symbol = { { text = "'" } }, swipe_up = "'", hint = { { text = "ic@lightbulb-outline" } }, swipe_down = "HideSymbols", swipe_left = "HintSwitch" })),
        -- 模式切换 S.switch模板；单击ModeSwitch；设置图标；长按弹出：英文、45键、9键布局；hint网页图标；下滑IMESwitch切换Rime输入方案
        key(merge(S.switch, key { click = "ModeSwitch", label_symbol = { { text = "ic@keyboard-settings-outline" } }, popup = KS.popup_for("Keyboard18key"), hint = { { text = "ic@web" } }, swipe_down = "IMESwitch" })),
        -- 回车键 S.enter模板；使用enter_labels动态图标；单击Enter；宽度0.15
        key(merge(S.enter, key { swipe_up = "AI", swipe_right = "ClipboardAI", label = { { text = "enter_labels" } }, click = "Enter", width = 0.15 }))
      }
    }
  }
}

return keyboard -- 将此键盘布局对象导出，NekoCatppuccin.lua通过safe_require加载这个布局