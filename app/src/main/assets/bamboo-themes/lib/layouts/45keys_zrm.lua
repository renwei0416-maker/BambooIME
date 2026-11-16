local S = safe_require("key_style")

local keyboard = keyboard(merge(safe_require("layouts.45keys"), keyboard {
  name = "自然万象",
  label_transform = "UPPERCASE",
  rows = {
    [2] = row {
      keys = {
        [1] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "䒑", "犭\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "丌", "欠", "且\n" }, color = "overlay2", align = "justify" },
          { text = { "犬", "iu" }, color = { "overlay2", "text" }, align = "justify" }
        } })),
        [2] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "亠", "夂\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "夊", "攵", "王\n" }, color = { "rosewater", "rosewater", "overlay2" }, align = "justify" },
          { text = { "ia", "ua" }, align = "justify" }
        } })),
        [3] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "阝", "卩\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "二", "儿", "耳\n" }, color = "overlay2", align = "justify" },
          { text = { "而", "尔", "e" }, color = { "overlay2", "overlay2", "text" }, align = "justify" }
        } })),
        [4] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "亻", "人\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "入", "肉", "刃\n" }, color = "overlay2", align = "justify" },
          { text = { "壬", "uan" }, color = { "overlay2", "text" }, align = "justify" }
        } })),
        [5] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "土", "田\n" }, color = "overlay2", align = { "center", "right" } },
          { text = { "它", "太", "台\n" }, color = "overlay2", align = "justify" },
          { text = { "ue", "üe" }, align = "justify" }
        } })),
        [6] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "讠", "衤\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "业", "乁", "㇈\n" }, color = "rosewater", align = "justify" },
          { text = { "ing", "uai" }, align = "justify" }
        } })),
        [7] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "礻", "饣\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "石", "十", "士\n" }, color = { "rosewater", "overlay2", "overlay2" }, align = "justify" },
          { text = { "殳", "矢", "u" }, color = { "overlay2", "overlay2", "text" }, align = "justify" }
        } })),
        [8] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "㐅", "厂\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "虫", "川", "巛\n" }, color = { "rosewater", "overlay2", "overlay2" }, align = "justify" },
          { text = { "亍", "豖", "i" }, color = { "overlay2", "overlay2", "text" }, align = "justify" }
        } })),
        [9] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "日", "月\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "目", "曰\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "o", " uo" }, align = "justify" }
        } })),
        [10] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "丿", "彡\n" }, color = { "lavender", "rosewater" }, align = { "center", "right" } },
          { text = { "𤴔", "疋", "攴\n" }, color = { "rosewater", "overlay2", "overlay2" }, align = "justify" },
          { text = { "片", "un" }, color = { "overlay2", "text" }, align = "justify" }
        } }))
      }
    },
    [3] = row {
      keys = {
        [2] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "丨", "一\n" }, color = "lavender", align = { "center", "right" } },
          { text = { "乛", "亅", "𠃋\n" }, color = { "lavender", "rosewater", "rosewater" }, align = "justify" },
          { text = { "𡿨", "〇", "a" }, color = { "overlay2", "overlay2", "text" }, align = "justify" }
        } })),
        [3] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "纟", "罒\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "巳", "厶", "龴\n" }, color = "overlay2", align = "justify" },
          { text = "(i)ong", align = "center" }
        } })),
        [4] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "丶", "乀\n" }, color = "lavender", align = { "center", "right" } },
          { text = { "冫", "氵", "刂\n" }, color = "rosewater", align = "justify" },
          { text = "i|uang", align = "center" }
        } })),
        [5] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "扌", "丰\n" }, color = { "rosewater", "overlay2" }, align = { "center", "right" } },
          { text = { "反", "方", "父\n" }, color = "overlay2", align = "justify" },
          { text = { "缶", "en" }, color = { "overlay2", "text" }, align = "justify" }
        } })),
        [6] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "乚", "勹\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "广", "㠯", "弓\n" }, color = { "rosewater", "rosewater", "overlay2" }, align = "justify" },
          { text = { "工", "eng" }, color = { "overlay2", "text" }, align = "justify" }
        } })),
        [7] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "灬", "火\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "户", "虍", "厷\n" }, color = { "rosewater", "rosewater", "overlay2" }, align = "justify" },
          { text = { "黑", "ang" }, color = { "overlay2", "text" }, align = "justify" }
        } })),
        [8] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "钅", "金\n" }, color = { "rosewater", "overlay2" }, align = { "center", "right" } },
          { text = { "几", "九", "己\n" }, color = "overlay2", align = "justify" },
          { text = { "臼", "an" }, color = { "overlay2", "text" }, align = "justify" }
        } })),
        [9] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "匚", "冂\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "凵", "囗", "口\n" }, color = "rosewater", align = "justify" },
          { text = { "丂", "ao" }, color = { "overlay2", "text" }, align = "justify" }
        } })),
        [10] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "力", "立\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "六", "耒", "龙\n" }, color = "overlay2", align = "justify" },
          { text = { "卤", "ai" }, color = { "overlay2", "text" }, align = "justify" }
        } }))
      }
    },
    [4] = row {
      keys = {
        [2] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "廴", "辶\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "⻊", "走", "子\n" }, color = { "rosewater", "overlay2", "overlay2" }, align = "justify" },
          { text = { "卆", "ei" }, color = { "overlay2", "text" }, align = "justify" }
        } })),
        [3] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "心", "忄\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "彳", "⺌", "⺗\n" }, color = { "rosewater", "overlay2", "overlay2" }, align = "justify" },
          { text = { "小", "ie" }, color = { "overlay2", "text" }, align = "justify" }
        } })),
        [4] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "艹", "卄\n" }, color = { "rosewater", "overlay2" }, align = { "center", "right" } },
          { text = { "廾", "屮", "卝\n" }, color = "overlay2", align = "justify" },
          { text = { "寸", "iao" }, color = { "overlay2", "text" }, align = "justify" }
        } })),
        [5] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "⺮", "𠂤\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "爫", "隹", "豸\n" }, color = "overlay2", align = "justify" },
          { text = { "ü", "ui" }, align = "justify" }
        } })),
        [6] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "冖", "宀\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "丷", "疒", "八\n" }, color = { "rosewater", "rosewater", "overlay2" }, align = "justify" },
          { text = { "卜", "ou" }, color = { "overlay2", "text" }, align = "justify" }
        } })),
        [7] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "女", "⺧\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "牜", "牛", "鸟\n" }, color = { "rosewater", "overlay2", "overlay2" }, align = "justify" },
          { text = { "年", "in" }, color = { "overlay2", "text" }, align = "justify" }
        } })),
        [8] = key(merge(S.hint_offset, key { label = { { align = "left", valign = "top" } }, hint = {
          { text = { "木", "门\n" }, color = "rosewater", align = { "center", "right" } },
          { text = { "皿", "毛", "丏\n" }, color = { "rosewater", "overlay2", "overlay2" }, align = "justify" },
          { text = { "尨", "ian" }, color = { "overlay2", "text" }, align = "justify" }
        } }))
      }
    }
  }
}))

return keyboard
