-- 加载按键样式模板，使用S.S.hint_offset（hint文字偏移配置）
local S = safe_require("neko.key_style3")

-- merge：继承 layouts.45keys（45键基础布局），只覆盖修改本脚本写出的配置，其余全部保留
local keyboard = keyboard(merge(safe_require("neko.layouts.45keys_hint"), keyboard {
  name = "小鹤万象",                          -- 布局对外显示名称
  reset_ascii_mode = true,            -- 每次进入这个键盘都强制回中文（不回退到上一次的英文状态）
  label_transform = "UPPERCASE",              -- 中文模式按键字母强制转为大写显示

  rows = {
    -- 修改【第2行 q‑p字母行】rows[2]，只追加hint多层提示，click/swipe/popup/样式全部继承原45keys
    [2] = row {
      keys = {
        [1] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "犭\n" }, align = "justify" },
          { text = { "求", "气", "犬\n" } },
          { text = "iu" }
        } })),
        [2] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "亠", "文", "夂\n" } },
          { text = { "攵", "瓦", "戊\n" } },
          { text = "ei" }
        } })),
        [3] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "彐", "山", "\n" }, align = "justify" },
          { text = { "阝", "卩", "耳\n" } },
          { text = "e" }
        } })),
        [4] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "亻", "人", "冉\n" } },
          { text = { "入", "壬", "刃\n" } },
          { text = "uan" }
        } })),
        [5] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "田", "土\n" } },
          { text = { "天", "太", "屯\n" } },
          { text = "ue üe" }
        } })),
        [6] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "讠", "𧘇", "⺷\n" } },
          { text = { "⺶", "羊", "玉\n" } },
          { text = "un" }
        } })),
        [7] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "饣", "龵", "𠂇\n" } },
          { text = { "氺", "石", "勺\n" } },
          { text = "u" }
        } })),
        [8] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "彳", "亍", "虫\n" } },
          { text = { "川", "叉", "垂\n" } },
          { text = "i" }
        } })),
        [9] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = "日\n" },
          { text = { "月", "目\n" } },
          { text = "o uo" }
        } })),
        [10] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "丿", "礻", "衤\n" }, align = "justify" },
          { text = { "片", "平", "爿\n" } },
          { text = "ie" }
        } }))
      }
    },
    [3] = row {
      keys = {
        [2] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = "一\n" },
          { text = { "鱼", "凹\n" }, align = "justify" },
          { text = "a" }
        } })),
        [3] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "纟", "厶", "龴\n" } },
          { text = { "罒", "巳", "肃\n" } },
          { text = "(i)ong" }
        } })),
        [4] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "丶", "冫", "氵\n" }, align = "justify" },
          { text = { "⺈", "刂", "刁\n" } },
          { text = "ai" }
        } })),
        [5] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "龶\n" } },
          { text = { "扌", "缶", "非\n" } },
          { text = "en" }
        } })),
        [6] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "艮", "鬼\n" } },
          { text = { "革", "骨", "夬\n" } },
          { text = "eng" }
        } })),
        [7] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "灬", "虍", "\n" } },
          { text = { "黑", "火", "乎\n" } },
          { text = "ang" }
        } })),
        [8] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "钅", "龹", "金\n" } },
          { text = { "臼", "夹", "柬\n" } },
          { text = "an" }
        } })),
        [9] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "匚", "冂", "凵\n" } },
          { text = { "囗", "㠯", "亏\n" }, align = "justify" },
          { text = "uai ing" }
        } })),
        [10] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "丨", "耂", "立\n" }, align = "justify" },
          { text = { "龙", "来", "丽\n" } },
          { text = "i|uang" }
        } }))
      }
    },
    [4] = row {
      keys = {
        [2] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "廴", "辶", "⻊\n" }, align = "justify" },
          { text = { "再", "自", "子\n" } },
          { text = "ou" }
        } })),
        [3] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "乂", "忄", "⺍\n" }, align = "justify" },
          { text = { "⺌", "⺗", "小\n" } },
          { text = "ia ua" }
        } })),
        [4] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "艹", "廾", "寸\n" } },
          { text = { "才", "匆", "册\n" } },
          { text = "ao" }
        } })),
        [5] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "𪛙", "⺮", "豸\n" }, align = "justify" },
          { text = { "主", "舟", "之\n" } },
          { text = "ui ü" }
        } })),
        [6] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "勹", "冖", "宀\n" }, align = "justify" },
          { text = { "丷", "疒\n" } },
          { text = "in" }
        } })),
        [7] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "乀", "⺧", "牜\n" }, align = "justify" },
          { text = { "廿", "鸟", "年\n" } },
          { text = "iao" }
        } })),
        [8] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "朩", "木", "米\n" } },
          { text = { "门", "马", "皿\n" } },
          { text = "ian" }
        } }))  -- 代码行
      }
    }
  }
}))


safe_require("neko.keyboard_switch").apply(keyboard, "KeyboardXiaohe")  -- 切换弹窗排除自身（鹤）
return keyboard -- 输出补丁完成后的布局对象，在NekoCatppuccin.lua的preset_keyboards注册即可切换