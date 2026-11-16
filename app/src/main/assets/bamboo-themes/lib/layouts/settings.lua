local S = safe_require("key_style") -- 按键样式

local keyboard = keyboard(merge(S.settings, keyboard {
  name = "设置与导航",
  author = "Carlson24(鹤衔春雪)",
  ascii_mode = false,
  reset_ascii_mode = true, -- 每次进入这个键盘都强制回到中文/非英文状态（否则会把上一个键盘残留的英文模式带进来）
  label_transform = "NONE",
  lock = false,
  navbar = true,
  keyboard_padding_top = 0,
  rows = {
    row {
      height = 0.3,
      keys = {
        key { spacer = true, width = 0.1 },
        key(merge(S.settings, key { click = "SchemeList", hint = { { text = "方案" } } })),
        key(merge(S.settings, key { click = "ThemeList", hint = { { text = "主题" } } })),
        key(merge(S.settings, key { click = "ColorList", hint = { { text = "配色" } } })),
        key(merge(S.settings, key { click = "WindowMenu", hint = { { text = "开关" } } })),
        key(merge(S.settings, key { click = "Settings", hint = { { text = "设置" } } })),
        key { spacer = true, width = 0.1 }
      }
    },
    row {
      height = 0.3,
      keys = {
        key { spacer = true, width = 0.1 },
        key(merge(S.settings, key { click = "Deploy", hint = { { text = "部署" } } })),
        key(merge(S.settings, key { click = "Sync", hint = { { text = "同步" } } })),
        key(merge(S.settings, key { click = "ThemeReload", hint = { { text = "刷新" } } })),
        key(merge(S.settings, key { click = "Restart", hint = { { text = "重启" } } })),
        key(merge(S.settings, key { click = "Info", hint = { { text = "关于" } } })),
        key { spacer = true, width = 0.1 }
      }
    },
    row {
      height = 0.3,
      keys = {
        key { spacer = true, width = 0.1 },
        key(merge(S.settings, key { click = "SchemaWanxiang", hint = { { text = "小鹤" } } })),
        key(merge(S.settings, key { click = "Schema18key", hint = { { text = "18 键" } } })),
        key(merge(S.settings, key { click = "Schema9key", hint = { { text = "T9" } } })),
        key(merge(S.settings, key { click = "SchemaEnglish", hint = { { text = "English" } } })),
        key { spacer = true, width = 0.1 }
      }
    }
  }
}))

return keyboard
