--[[
lib\gmk\palettes.lua    GMK 键帽风主题（Gmk）的配色工厂
------------------------------------------------------------------
和 DevPalettes 的差别：按键背景不再是纯色，而是指向 backgrounds\gmk\<套色>\ 里的键帽图。
所以这里比纯色配色多一个 img 字段，负责拼出图片路径：

  letter.png   字母/数字键        func.png     Shift、退格、符号等功能键
  space.png    空格（宽版）       enter.png    回车
  pressed.png  所有键的按下态

Gmk.lua 里写了 background_folder = "gmk"，所以 "olivia/letter.png" 会解析成
  <主题目录>\backgrounds\gmk\olivia\letter.png
与 NekoCatppuccin 的 backgrounds\keys\ios\ 完全隔离。

其它字段含义同 lib\dev\palettes.lua：base 键盘底色、text 主文字色、sub 次要文字/角标、
fixText 功能键上的文字（深浅可能和字母键相反）、accent 强调色、on 压在 accent 上的文字色。
]]

local M = {}

local function make(p)
  local img = p.img
  local function pic(name) return img .. "/" .. name .. ".png" end

  local c = {
    -- ===== 版式与容器 =====
    back_color = p.base,
    text_color = p.text,
    border_color = p.border,
    keyboard_back_color = p.base,
    keyboard_background = p.base,
    root_background = p.base,
    liquid_keyboard_background = p.base,
    shadow_color = p.shadow or "0x66000000",

    -- ===== 候选栏 =====
    candidate_background = p.base,
    candidate_text_color = p.text,
    candidate_border_color = p.base,
    candidate_separator_color = p.border,
    comment_text_color = p.sub,
    label_color = p.sub,
    hilited_candidate_back_color = p.accent,
    hilited_candidate_text_color = p.on,
    hilited_candidate_button_color = p.accent,
    hilited_comment_text_color = p.sub,
    hilited_label_color = p.on,

    -- ===== 预编辑 =====
    hilited_text_color = p.text,
    hilited_back_color = p.func,
    text_back_color = p.func,

    -- ===== 普通键（图片作背景）=====
    key_back_color = pic("letter"),
    key_border_color = "0x00",
    -- 键帽上的字和角标单独可配：键帽图是浅色而候选区是深色时，两者必须分开取色
    key_text_color = p.keyText or p.text,
    key_symbol_color = p.keySub or p.sub,
    hilited_key_back_color = pic("pressed"),
    hilited_key_text_color = p.keyText or p.text,
    hilited_key_symbol_color = p.keySub or p.sub,

    -- ===== 功能键 =====
    off_key_back_color = pic("func"),
    off_key_text_color = p.fixText,
    off_key_symbol_color = p.fixSub,
    hilited_off_key_back_color = pic("pressed"),
    -- 按下态统一用 pressed.png：它是浅色键帽时必须用深字，所以这里跟键帽文字走，而不是功能键文字
    hilited_off_key_text_color = p.pressText or p.keyText or p.text,
    hilited_off_key_symbol_color = p.pressSub or p.keySub or p.sub,

    -- ===== 激活键（Shift 锁定 / 中文模式）=====
    on_key_back_color = p.accent,
    on_key_text_color = p.on,
    on_key_symbol_color = p.on,
    hilited_on_key_back_color = p.accent,
    hilited_on_key_text_color = p.on,
    hilited_on_key_symbol_color = p.on,

    -- ===== 长按弹窗（保持纯色，图片在弹窗里太花）=====
    popup_back_color = p.func,
    popup_text_color = p.text,
    hilited_popup_back_color = p.accent,
    hilited_popup_text_color = p.on,

    -- ===== 长文本 / 侧栏 / 剪贴板 =====
    long_text_color = p.sub,
    long_text_back_color = p.func,
    sidebar_back_color = p.func,
    sidebar_hilited_back_color = p.accent,
    sidebar_text_color = p.text,
    sidebar_border_color = p.border,
    sidebar_spacing_color = p.base,
    clipboard_category_back_color = p.func,
    clipboard_category_selected_back_color = p.accent,
    clipboard_category_selected_text_color = p.on,
    clipboard_entry_back_color = p.base,
    clipboard_checkbox_color = p.accent,
    hilited_clipboard_entry_back_color = p.func,
    fix_key_text_color = p.fixText,
    fix_key_symbol_color = p.fixSub,

    -- ===== 皮肤自身色阶（lib\gmk\key_style.lua 按名字查表）=====
    yipai = pic("letter"), erpai = pic("letter"), sanpai = pic("letter"), sipai = pic("letter"),
    dipai = pic("func"), shuzi = pic("func"), gongneng = pic("func"),
    space = pic("space"), enter = pic("enter"), enter_text = p.enterText or p.on,
    gaoliang = pic("pressed"), biankuang = "0x00", gnbiankuang = "0x00", none = "0x00",

    -- ===== 调色板别名 =====
    rosewater = p.sub, flamingo = p.sub, pink = p.accent, mauve = p.accent,
    red = p.accent, maroon = p.accent, peach = p.accent, yellow = p.accent,
    green = p.accent, teal = p.accent, sky = p.accent, sapphire = p.accent,
    blue = p.accent, lavender = p.accent,
    text = p.text, subtext1 = p.sub, subtext0 = p.sub,
    overlay2 = p.sub, overlay1 = p.border, overlay0 = p.border,
    surface2 = p.func, surface1 = p.func, surface0 = p.func,
    base = p.base, mantle = p.base, crust = p.base,
  }

  if p.extra then
    for k, v in pairs(p.extra) do c[k] = v end
  end
  return c
end

-- ============================================================
-- 三套键帽配色（图片由 backgrounds\gmk\ 里的 PNG 提供）
-- ============================================================
local defs = {
  {
    id = "GMK Olivia", img = "olivia",
    base = "0x2A2728", text = "0xE8E0D4", sub = "0xC9BCA8",
    keyText = "0x2A2728", keySub = "0x6B5F52",
    fixText = "0xF2EDE4", fixSub = "0xB5ABA1",
    func = "0x3E3A3B", border = "0x3E3A3B",
    accent = "0xE8A0A8", on = "0x2A2728",
    shadow = "0x66000000",
  },
  {
    id = "GMK 8008", img = "8008",
    base = "0x1B1D22", text = "0xE8EFF5", sub = "0x9FB3C8",
    fixText = "0xE8EFF5", fixSub = "0x9FB3C8",
    func = "0x2A3038", border = "0x15181C",
    accent = "0x4FC3E8", on = "0x0E1216",
    shadow = "0x70000000",
  },
  {
    id = "GMK Bento", img = "bento",
    base = "0xF3EFE7", text = "0x3A3A3A", sub = "0x8A8378",
    fixText = "0x3A3A3A", fixSub = "0x8A8378",
    func = "0xE5D9C6", border = "0xD8C9B2",
    accent = "0xE8894A", on = "0xFFFAF3", enterText = "0x33241A",
    shadow = "0x33000000",
  },
}

M.defs = defs
M.list = {}
M.byId = {}
for _, d in ipairs(defs) do
  local colors = make(d)
  M.list[#M.list + 1] = { id = d.id, colors = colors }
  M.byId[d.id] = colors
end

return M
