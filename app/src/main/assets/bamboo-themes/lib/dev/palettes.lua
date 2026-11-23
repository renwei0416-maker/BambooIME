--[[
lib\dev\palettes.lua    多配色开发主题（DevPalettes）的配色工厂
------------------------------------------------------------------
一套「开发者配色」真正在变的只有 8 个语义色，其余 90 多个字段都是派生量。
所以这里只写语义色，由 make() 一次性展开成灵韵要求的完整 scheme 表；
加一套新配色 = 在下面 defs 里加一行，不用再抄两百行。

语义色字段：
  base   键盘底色           key    字母键底色           key2  按下/高亮底色
  func   功能键底色         text   主文字色             sub   次要文字、角标、hint
  accent 强调色（回车、选中候选、激活键、侧栏高亮、复选框）
  on     压在 accent 上的文字色
  border 描边/分隔线色（key_border = 0 时可省，默认等于 key2）
  dark   true=深色配色（阴影更浓），默认 true

用法：
  local P = safe_require("dev.palettes")
  for i, s in ipairs(P.list) do schemes[i] = scheme(s.id, s.colors) end
]]

local M = {}

-- ============================================================
-- 配色工厂：语义色 -> 灵韵方案表
-- ============================================================
local function make(p)
  local dark   = p.dark ~= false
  local border = p.border or p.key2 or p.key
  local sub    = p.sub or p.text
  local shadow = p.shadow or (dark and "0x66000000" or "0x33000000")

  local c = {
    -- ===== 版式与容器 =====
    back_color = p.base,
    text_color = p.text,
    border_color = border,
    keyboard_back_color = p.base,
    keyboard_background = p.base,
    root_background = p.base,
    liquid_keyboard_background = p.base,
    shadow_color = shadow,

    -- ===== 候选栏 =====
    candidate_background = p.base,
    candidate_text_color = p.text,
    candidate_border_color = p.base,
    candidate_separator_color = border,
    comment_text_color = sub,
    label_color = sub,
    hilited_candidate_back_color = p.accent,
    hilited_candidate_text_color = p.on,
    hilited_candidate_button_color = p.accent,
    hilited_comment_text_color = sub,
    hilited_label_color = p.on,

    -- ===== 预编辑 =====
    hilited_text_color = p.text,
    hilited_back_color = p.key2,
    text_back_color = p.key2,

    -- ===== 普通键 =====
    key_back_color = p.key,
    key_border_color = border,
    key_text_color = p.text,
    key_symbol_color = sub,
    hilited_key_back_color = p.key2,
    hilited_key_text_color = p.text,
    hilited_key_symbol_color = sub,

    -- ===== 功能键（Shift / 退格 / 符号区） =====
    off_key_back_color = p.func,
    off_key_text_color = p.text,
    off_key_symbol_color = sub,
    hilited_off_key_back_color = p.key2,
    hilited_off_key_text_color = p.text,
    hilited_off_key_symbol_color = sub,

    -- ===== 激活键（Shift 锁定 / 中文模式） =====
    on_key_back_color = p.accent,
    on_key_text_color = p.on,
    on_key_symbol_color = p.on,
    hilited_on_key_back_color = p.accent,
    hilited_on_key_text_color = p.on,
    hilited_on_key_symbol_color = p.on,

    -- ===== 长按弹窗 =====
    popup_back_color = p.func,
    popup_text_color = p.text,
    hilited_popup_back_color = p.accent,
    hilited_popup_text_color = p.on,

    -- ===== 长文本 / 侧栏 / 剪贴板 =====
    long_text_color = sub,
    long_text_back_color = p.func,
    sidebar_back_color = p.func,
    sidebar_hilited_back_color = p.accent,
    sidebar_text_color = p.text,
    sidebar_border_color = border,
    sidebar_spacing_color = p.base,
    clipboard_category_back_color = p.func,
    clipboard_category_selected_back_color = p.accent,
    clipboard_category_selected_text_color = p.on,
    clipboard_entry_back_color = p.key,
    clipboard_checkbox_color = p.accent,
    hilited_clipboard_entry_back_color = p.key2,
    fix_key_text_color = sub,
    fix_key_symbol_color = sub,

    -- ===== 皮肤自身色阶（lib\dev\key_style.lua 按名字查表） =====
    yipai = p.key, erpai = p.key, sanpai = p.key, sipai = p.key,
    dipai = p.func, shuzi = p.func, gongneng = p.func,
    space = p.key, enter = p.accent, enter_text = p.on,
    gaoliang = p.key2, biankuang = border, gnbiankuang = border, none = "0x00",

    -- ===== 调色板别名（对齐 Catppuccin 命名，带色 hint 的布局可直接复用） =====
    rosewater = sub, flamingo = sub, pink = p.accent, mauve = p.accent,
    red = p.accent, maroon = p.accent, peach = p.accent, yellow = p.accent,
    green = p.accent, teal = p.accent, sky = p.accent, sapphire = p.accent,
    blue = p.accent, lavender = p.accent,
    text = p.text, subtext1 = sub, subtext0 = sub,
    overlay2 = sub, overlay1 = border, overlay0 = border,
    surface2 = p.func, surface1 = p.key2, surface0 = p.key2,
    base = p.base, mantle = p.base, crust = p.base,
  }

  if p.extra then
    for k, v in pairs(p.extra) do c[k] = v end
  end
  return c
end

-- ============================================================
-- 8 套配色：base / key / func / key2 / text / sub / accent / on
-- ============================================================
local defs = {
  { id = "Nord",            base = "0x2E3440", key = "0x3B4252", func = "0x434C5E", key2 = "0x4C566A", text = "0xECEFF4", sub = "0xD8DEE9", accent = "0x88C0D0", on = "0x2E3440", border = "0x434C5E" },
  { id = "Tokyo Night",     base = "0x1A1B26", key = "0x24283B", func = "0x292E42", key2 = "0x3B4261", text = "0xC0CAF5", sub = "0x9AA5CE", accent = "0x7AA2F7", on = "0x16161E", border = "0x292E42" },
  { id = "Gruvbox",         base = "0x282828", key = "0x3C3836", func = "0x504945", key2 = "0x665C54", text = "0xEBDBB2", sub = "0xA89984", accent = "0xFABD2F", on = "0x282828", border = "0x504945" },
  { id = "Rosé Pine",       base = "0x232136", key = "0x2A273F", func = "0x393552", key2 = "0x44415A", text = "0xE0DEF4", sub = "0x908CAA", accent = "0xEB6F92", on = "0x191724", border = "0x393552" },
  { id = "Everforest",      base = "0x2D353B", key = "0x343F44", func = "0x3D484D", key2 = "0x475258", text = "0xD3C6AA", sub = "0x9DA9A0", accent = "0xA7C080", on = "0x2D353B", border = "0x3D484D" },
  { id = "Dracula",         base = "0x282A36", key = "0x343746", func = "0x44475A", key2 = "0x4E5266", text = "0xF8F8F2", sub = "0xA6ACCD", accent = "0xBD93F9", on = "0x282A36", border = "0x44475A" },
  { id = "Catppuccin Mocha", base = "0x1E1E2E", key = "0x313244", func = "0x45475A", key2 = "0x585B70", text = "0xCDD6F4", sub = "0xA6ADC8", accent = "0xCBA6F7", on = "0x11111B", border = "0x45475A" },
  { id = "One Dark",        base = "0x282C34", key = "0x3A4048", func = "0x444B56", key2 = "0x4F5762", text = "0xABB2BF", sub = "0x7F848E", accent = "0x61AFEF", on = "0x282C34", border = "0x444B56" },

  -- 浅色配色（暗色模式下不自动切换，需要时把 id 填进下面 lightIds）
  { id = "Solarized Light", base = "0xFDF6E3", key = "0xEEE8D5", func = "0xE4DDC8", key2 = "0xD7D0BB", text = "0x586E75", sub = "0x93A1A1", accent = "0x268BD2", on = "0xFDF6E3", border = "0xE4DDC8", dark = false },
  { id = "Rosé Pine Dawn",  base = "0xFAF4ED", key = "0xFFFAF3", func = "0xF2E9E1", key2 = "0xDFDAD9", text = "0x575279", sub = "0x9893A5", accent = "0xD7827E", on = "0xFFFAF3", border = "0xF2E9E1", dark = false },
}

-- ============================================================
-- 展开
-- ============================================================
M.defs = defs
M.list = {}
M.byId = {}
for _, d in ipairs(defs) do
  local colors = make(d)
  M.list[#M.list + 1] = { id = d.id, colors = colors }
  M.byId[d.id] = colors
end

return M
