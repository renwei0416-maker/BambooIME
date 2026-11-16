--[[
Gboard 风格配色（Material 3 / 现代 Gboard）

设计要点：
  1. 键盘底色是浅灰，字母键纯白，功能键再深一档 —— 靠“色阶”分层，不靠描边。
  2. 上档符号（key_symbol_color / off_key_symbol_color）用柔灰（0x80868b），
     与 Gboard 一样只在角落留小号灰字/灰色图标（键值可用、不抢主字母）。
  3. 回车键用 Google 蓝 + 白色图标，是 Gboard 最显眼的识别点。
  4. 空格键与字母键同色（Gboard 的空格是白的），不是功能键色。
  5. 按下态：字母键变浅灰（gaoliang / hilited_key_back_color），
     功能键再深一档（hilited_off_key_back_color），激活态用蓝色浅底（on_key_*）。

用法：Gboard.lua 里 scheme("default", safe_require("colors.gboard").light)
]]

local gb = {}

-- ==========================================================================
-- 浅色（Gboard 默认）
-- ==========================================================================
gb.light = {
  -- ---------- 基础 / 底板 ----------
  back_color = "0xf2f3f5",              -- 候选栏底色
  text_color = "0x1f1f1f",              -- 全局文字色
  border_color = "0xf2f3f5",            -- 全局边框（等于底色=看不见）
  shadow_color = "0x1a000000",          -- 阴影
  root_background = "0xf2f3f5",         -- 根背景
  keyboard_back_color = "0xf2f3f5",     -- 键盘底色
  keyboard_background = "0xf2f3f5",     -- 键盘背景（可换图片）
  liquid_keyboard_background = "0xf2f3f5",

  -- ---------- 本皮肤的“分层色键”（被 key_style_gboard 引用） ----------
  yipai = "0xffffff",                   -- 字母行 B 底色：纯白
  erpai = "0xffffff",                   -- 字母行 M 底色：纯白
  sanpai = "0xffffff",                  -- 字母行 C 底色：纯白
  sipai = "0xffffff",                   -- 四排布局字母键底色：纯白
  dipai = "0xe8eaed",                   -- 符号/标点/切换键：浅灰
  shuzi = "0xe8eaed",                   -- 数字行：浅灰
  gongneng = "0xe8eaed",                -- Shift / BackSpace：浅灰
  space = "0xffffff",                   -- 空格：与字母同色
  enter = "0x1a73e8",                   -- 回车：Google 蓝
  enter_text = "0xffffff",              -- 回车图标：纯白（Gboard 的蓝色回车）
  gaoliang = "0xdfe1e5",                -- 按下高亮（模板统一引用）
  biankuang = "0xdfe1e5",               -- 字母键描边（等于按下色，视觉上无描边）
  gnbiankuang = "0xd5d8dd",             -- 功能键描边
  none = "0x00",                        -- 透明
  fix_key_text_color = "0x1f1f1f",      -- 功能键文字（key_style 的 fix_key_*）
  fix_key_symbol_color = "0x80868b",    -- 功能键角标/图标（柔灰）

  -- ---------- 普通按键 ----------
  key_back_color = "0xffffff",
  key_text_color = "0x1f1f1f",
  key_symbol_color = "0x80868b",        -- 上档角标/图标（柔灰）
  key_border_color = "0xdfe1e5",
  hilited_key_back_color = "0xdfe1e5",
  hilited_key_text_color = "0x1f1f1f",
  hilited_key_symbol_color = "0x3c4043",

  -- ---------- 功能键（off_key） ----------
  off_key_back_color = "0xe8eaed",
  off_key_text_color = "0x1f1f1f",
  off_key_symbol_color = "0x80868b",
  off_key_border_color = "0xd5d8dd",
  hilited_off_key_back_color = "0xd5d8dd",
  hilited_off_key_text_color = "0x1f1f1f",

  -- ---------- 激活态（on_key：大写锁定、中文模式等） ----------
  on_key_back_color = "0xd3e3fd",
  on_key_text_color = "0x041e49",
  on_key_symbol_color = "0x1a73e8",
  on_key_border_color = "0xbcd4f8",
  hilited_on_key_back_color = "0xbcd4f8",
  hilited_on_key_text_color = "0x041e49",
  hilited_off_key_symbol_color = "0x3c4043",   -- 功能键按下时的角标色
  hilited_on_key_symbol_color = "0x1a73e8",    -- 激活键按下时的角标色

  -- ---------- 候选栏 ----------
  candidate_back_color = "0xf2f3f5",
  candidate_border_color = "0xf2f3f5",
  candidate_text_color = "0x1f1f1f",
  candidate_comment_color = "0x5f6368",
  candidate_label_color = "0x5f6368",
  candidate_separator_color = "0xd9dce1",
  hilited_candidate_back_color = "0xd3e3fd",
  hilited_candidate_text_color = "0x041e49",
  hilited_candidate_comment_color = "0x0b57d0",
  hilited_candidate_label_color = "0x0b57d0",
  hilited_candidate_button_color = "0x1a73e8",  -- 工具栏主按钮高亮
  comment_text_color = "0x5f6368",
  comment_back_color = "0xf2f3f5",

  -- ---------- 按键气泡 / 弹窗 ----------
  popup_back_color = "0xffffff",
  popup_border_color = "0xe0e2e6",
  popup_text_color = "0x1f1f1f",

  -- ---------- 侧栏（T9 / 14 / 18 键） ----------
  sidebar_back_color = "0xffffff",
  sidebar_hilited_back_color = "0xdfe1e5",
  sidebar_text_color = "0x1f1f1f",
  sidebar_hilited_text_color = "0x041e49",
  sidebar_border_color = "0xd9dce1",
  sidebar_spacing_color = "0xf2f3f5",

  -- ---------- 剪贴板 / 液态键盘 ----------
  clipboard_back_color = "0xf2f3f5",
  clipboard_text_color = "0x1f1f1f",
  clipboard_category_back_color = "0xe8eaed",
  clipboard_category_text_color = "0x5f6368",
  long_text_back_color = "0xffffff",

  -- ---------- 调色板（供布局里的 hint 彩色引用：rosewater / overlay2 等） ----------
  rosewater = "0xb3261e", flamingo = "0xc5221f", pink = "0xb3278a", mauve = "0x7c4dff",
  red = "0xd93025", maroon = "0xb3261e", peach = "0xe8710a", yellow = "0xb26a00",
  green = "0x188038", teal = "0x00796b", sky = "0x0288d1", sapphire = "0x1967d2",
  blue = "0x1a73e8", lavender = "0x6b5ce7",
  text = "0x1f1f1f", subtext1 = "0x5f6368", subtext0 = "0x444746",
  overlay2 = "0x5f6368", overlay1 = "0x8f949c", overlay0 = "0xbdc1c7",
  surface2 = "0xd9dce1", surface1 = "0xe7e9ed", surface0 = "0xffffff",
  base = "0xffffff", mantle = "0xffffff", crust = "0xf2f3f5",

  -- 明暗方案互指（部分版本按此自动跟随系统）
  -- 与 Gboard.lua 的 scheme 命名保持一致：default = 深色，Gboard Light = 浅色。
  -- 跟随系统昼夜时：系统浅色 → Gboard Light，系统深色 → default；不跟随时永远是 default（深色）。
  light_scheme = "Gboard Light",
  dark_scheme = "default",
}

-- ==========================================================================
-- 深色（Gboard 深色）
-- ==========================================================================
gb.dark = {
  -- ---------- 基础 / 底板 ----------
  back_color = "0x1f2023",
  text_color = "0xe8eaed",
  border_color = "0x1f2023",
  shadow_color = "0x66000000",
  root_background = "0x1f2023",
  keyboard_back_color = "0x1f2023",
  keyboard_background = "0x1f2023",
  liquid_keyboard_background = "0x1f2023",

  -- ---------- 分层色键 ----------
  yipai = "0x33363b",
  erpai = "0x33363b",
  sanpai = "0x33363b",
  sipai = "0x33363b",
  dipai = "0x282a2e",
  shuzi = "0x282a2e",
  gongneng = "0x282a2e",
  space = "0x33363b",
  enter = "0x8ab4f8",
  enter_text = "0x202124",
  gaoliang = "0x4a4e55",
  biankuang = "0x4a4e55",
  gnbiankuang = "0x3a3d43",
  none = "0x00",
  fix_key_text_color = "0xe8eaed",
  fix_key_symbol_color = "0x9aa0a6",

  -- ---------- 普通按键 ----------
  key_back_color = "0x33363b",
  key_text_color = "0xe8eaed",
  key_symbol_color = "0x9aa0a6",
  key_border_color = "0x4a4e55",
  hilited_key_back_color = "0x4a4e55",
  hilited_key_text_color = "0xe8eaed",
  hilited_key_symbol_color = "0xe8eaed",

  -- ---------- 功能键 ----------
  off_key_back_color = "0x282a2e",
  off_key_text_color = "0xe8eaed",
  off_key_symbol_color = "0x9aa0a6",
  off_key_border_color = "0x3a3d43",
  hilited_off_key_back_color = "0x3a3d43",
  hilited_off_key_text_color = "0xe8eaed",

  -- ---------- 激活态 ----------
  on_key_back_color = "0x2c4a77",
  on_key_text_color = "0xd3e3fd",
  on_key_symbol_color = "0xa8c7fa",
  on_key_border_color = "0x3f5c88",
  hilited_on_key_back_color = "0x3f5c88",
  hilited_on_key_text_color = "0xd3e3fd",
  hilited_off_key_symbol_color = "0xe8eaed",
  hilited_on_key_symbol_color = "0xa8c7fa",

  -- ---------- 候选栏 ----------
  candidate_back_color = "0x1f2023",
  candidate_border_color = "0x1f2023",
  candidate_text_color = "0xe8eaed",
  candidate_comment_color = "0x9aa0a6",
  candidate_label_color = "0x9aa0a6",
  candidate_separator_color = "0x3c4046",
  hilited_candidate_back_color = "0x2d4a77",
  hilited_candidate_text_color = "0xd3e3fd",
  hilited_candidate_comment_color = "0xa8c7fa",
  hilited_candidate_label_color = "0xa8c7fa",
  hilited_candidate_button_color = "0x8ab4f8",
  comment_text_color = "0x9aa0a6",
  comment_back_color = "0x1f2023",

  -- ---------- 弹窗 ----------
  popup_back_color = "0x2d3035",
  popup_border_color = "0x3c4046",
  popup_text_color = "0xe8eaed",

  -- ---------- 侧栏 ----------
  sidebar_back_color = "0x33363b",
  sidebar_hilited_back_color = "0x4a4e55",
  sidebar_text_color = "0xe8eaed",
  sidebar_hilited_text_color = "0xd3e3fd",
  sidebar_border_color = "0x3c4046",
  sidebar_spacing_color = "0x1f2023",

  -- ---------- 剪贴板 ----------
  clipboard_back_color = "0x1f2023",
  clipboard_text_color = "0xe8eaed",
  clipboard_category_back_color = "0x282a2e",
  clipboard_category_text_color = "0x9aa0a6",
  long_text_back_color = "0x33363b",

  -- ---------- 调色板 ----------
  rosewater = "0xf28b82", flamingo = "0xf6aea9", pink = "0xfdcfe1", mauve = "0xa8c7fa",
  red = "0xf28b82", maroon = "0xf28b82", peach = "0xf6aea9", yellow = "0xfdd663",
  green = "0x81c995", teal = "0x78d9ec", sky = "0x78d9ec", sapphire = "0x8ab4f8",
  blue = "0x8ab4f8", lavender = "0xa8c7fa",
  text = "0xe8eaed", subtext1 = "0x9aa0a6", subtext0 = "0xc4c7cc",
  overlay2 = "0x9aa0a6", overlay1 = "0x80858c", overlay0 = "0x5f6368",
  surface2 = "0x4a4e55", surface1 = "0x33363b", surface0 = "0x282a2e",
  base = "0x33363b", mantle = "0x1f2023", crust = "0x1b1c1f",

  -- 与 Gboard.lua 的 scheme 命名保持一致：default = 深色，Gboard Light = 浅色。
  -- 跟随系统昼夜时：系统浅色 → Gboard Light，系统深色 → default；不跟随时永远是 default（深色）。
  light_scheme = "Gboard Light",
  dark_scheme = "default",
}

return gb
