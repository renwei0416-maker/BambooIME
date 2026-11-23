local S = safe_require("neko.key_style") -- 按键样式

local keyboard = keyboard {
  name = "数字布局",
  author = "Carlson24(鹤衔春雪)",
  ascii_mode = false,
  label_transform = "NONE",
  lock = false,
  rows = {
    row {
      keys = {
        key(merge(S.crust, { click = "+", long_click = "%" })),
        key { click = "7" },
        key { click = "8" },
        key { click = "9" },
        key(merge(S.crust, { click = "*", long_click = "×" }))
      }
    },
    row {
      keys = {
        key(merge(S.crust, { click = "-", long_click = "$" })),
        key { click = "4" },
        key { click = "5" },
        key { click = "6" },
        key(merge(S.crust, { click = "Slash", long_click = "÷" }))
      }
    },
    row {
      keys = {
        key(merge(S.crust, { click = "Paren", long_click = "Bracket" })),
        key { click = "1" },
        key { click = "2" },
        key { click = "3" },
        key(merge(S.crust, { click = "BackSpace", key_text_size = 23 }))
      }
    },
    row {
      keys = {
        key(merge(S.crust, { ascii_label = { { text = "返回" } }, label = { { text = "返回" } }, click = "KeyboardDefault" })),
        key(merge(S.crust, { click = "Space", label_symbol = { { text = "日期" } }, long_click = "N", key_text_size = 23 })),
        key { click = "0" },
        key(merge(S.crust, { click = ".", label_symbol = { { text = "金额" } }, long_click = "R" })),
        key(merge(S.enter, { swipe_up = "AI", swipe_right = "ClipboardAI", click = "Enter" }))
      }
    }
  }
}

return keyboard
