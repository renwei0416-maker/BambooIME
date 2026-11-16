local KS = safe_require("keyboard_switch")
local S = safe_require("cjn.key_style") -- 按键样式

local keyboard = keyboard {
  name = "出彩 T9",
  author = "Carlson24(鹤衔春雪)",
  ascii_mode = false,
  label_transform = "NONE",
  lock = false,
  -- 侧栏（九宫格左侧那一列符号）。2026-08-22 起字段由 t9_* 改名为 sidebar_*
  sidebar_mode = true,
  sidebar_layout = "t9",
  sidebar_width = 0.15,
  sidebar_position = "left",
  sidebar_span_rows = 3,
  sidebar_show_items = 4,
  sidebar_symbols = { "，", "。", "？", "！", "、", "：", "；" },
  rows = {
    row {
      keys = {
        key { spacer = true, width = 0.15 },
        key(merge(S.number, key { label = { { text = "分词" } }, click = "1", label_symbol = { { text = "`" } }, swipe_up = "`", hint = { { text = "1" } } })),
        key(merge(S.number, key { key_back_color = "zmu_a", label = { { text = "ABC" } }, click = "2", label_symbol = { { text = "#" } }, swipe_up = "#", popup = { "a", "b", "c", "A", "B", "C" }, hint = { { text = "2" } } })),
        key(merge(S.number, key { key_back_color = "zmu_d", label = { { text = "DEF" } }, click = "3", label_symbol = { { text = "@" } }, swipe_up = "@", popup = { "d", "e", "f", "D", "E", "F" }, hint = { { text = "3" } } })),
        -- 退格：单击删除；左滑删到行首、右滑回退最近一笔、上滑删一个词、下滑 Esc（与 45 键一致）
        key(merge(S.backspace, key {
          click       = "BackSpace",
          swipe_left  = "DeleteLine",
          swipe_right = "BambooRevert",
          swipe_up    = "WordDelete",
          swipe_down  = "Escape",
          width       = 0.15,
        }))
      }
    },
    row {
      keys = {
        key { spacer = true, width = 0.15 },
        key(merge(S.number, key { key_back_color = "zmu_g", label = { { text = "GHI" } }, click = "4", label_symbol = { { text = "$" } }, swipe_up = "$", popup = { "g", "h", "i", "G", "H", "I" }, hint = { { text = "4" } } })),
        key(merge(S.number, key { key_back_color = "zmu_j", label = { { text = "JKL" } }, click = "5", label_symbol = { { text = "%" } }, swipe_up = "%", popup = { "j", "k", "l", "J", "K", "L" }, hint = { { text = "5" } } })),
        key(merge(S.number, key { key_back_color = "zmu_m", label = { { text = "MNO" } }, click = "6", label_symbol = { { text = "&" } }, swipe_up = "&", popup = { "m", "n", "o", "M", "N", "O" }, hint = { { text = "6" } } })),
        key(merge(S.clean, key { click = "T9Clean", width = 0.15 }))
      }
    },
    row {
      keys = {
        key { spacer = true, width = 0.15 },
        key(merge(S.number, key { key_back_color = "zmu_p", label = { { text = "PQRS" } }, click = "7", label_symbol = { { text = "+" } }, swipe_up = "+", popup = { "p", "q", "r", "s", "P", "Q", "R", "S" }, hint = { { text = "7" } } })),
        key(merge(S.number, key { key_back_color = "zmu_t", label = { { text = "TUV" } }, click = "8", label_symbol = { { text = "-" } }, swipe_up = "-", popup = { "t", "u", "v", "T", "U", "V" }, hint = { { text = "8" } } })),
        key(merge(S.number, key { key_back_color = "zmu_w", label = { { text = "WXYZ" } }, click = "9", label_symbol = { { text = "=" } }, swipe_up = "=", popup = { "w", "x", "y", "z", "W", "X", "Y", "Z" }, hint = { { text = "9" } } })),
        key(merge(S.func, key { click = "ZiTools", width = 0.15 }))
      }
    },
    row {
      keys = {
        key(merge(S.symbol, key { click = "KeyboardNumber", label_symbol = { { text = "ic@calculator-variant" } }, popup = { "BambooSymbol", "Calculator", "ThemeReload", "Deploy" }, has_menu = "Tab", width = 0.15 })),
        key(merge(S.slash, key { click = "/", label_symbol = { { text = "[" } }, swipe_up = "[", hint = { { text = "ic@list-box-outline" } }, swipe_down = "SchemeList", swipe_left = "HideSymbols", width = 0.13 })),
        key(merge(S.space, key { label = { { text = "␣" } }, click = "Space", long_click = "VoiceAi", swipe_down = "LiquidEmoji", key_text_offset_y = 1 })),
        key(merge(S.switch, key { click = "ModeSwitch", label_symbol = { { text = "]" } }, swipe_up = "]", popup = KS.popup_for("Keyboard9key"), hint = { { text = "ic@web" } }, swipe_down = "IMESwitch", width = 0.13 })),
        key(merge(S.enter, key { swipe_up = "AI", swipe_right = "ClipboardAI", label = { { text = "enter_labels" } }, click = "Enter", width = 0.15 }))
      }
    }
  }
}

return keyboard
