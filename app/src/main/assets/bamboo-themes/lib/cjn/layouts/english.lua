-- 出彩英文：复用本主题的 45 键布局，强制英文模式；切换弹窗排除自身（英）
local keyboard = merge(safe_require("cjn.layouts.45keys"), keyboard {
  name = "出彩英文",
  ascii_mode = true,          -- 进这个键盘即英文
  reset_ascii_mode = true,    -- 每次进入都重新按上面的 ascii_mode 设定
})

safe_require("keyboard_switch").apply(keyboard, "KeyboardEnglish")

return keyboard
