--[[
  对比度守卫（contrast guard）
  --------------------------------------------------------------
  皮肤里最容易出问题的不是配色本身，而是「某处文字/图标色」和
  「它脚下那块背景色」太接近 —— 候选注释、功能键角标、选中候选
  最常翻车。本模块在主题加载后统一体检一遍：只处理**纯色对**
  （图片、半透明背景一律跳过，无法准确判定），不达标就把前景朝
  远离背景明度的方向压暗/提亮，直到刚好达标，尽量少改颜色。

  用法（放在主题入口 `return theme` 之前）：
      local guard = safe_require("colors.contrast_guard")
      for _, s in ipairs(theme.preset_color_schemes or {}) do guard.apply(s) end
]]

local M = {}

-- 要检查的组合：{ 前景键, 背景键, 最低对比度（3.1≈大字/图标，4.6≈正文） }
M.PAIRS = {
  { "candidate_text_color",         "candidate_background",             4.6 },
  { "hilited_candidate_text_color", "hilited_candidate_back_color",     4.6 },
  { "comment_text_color",           "candidate_background",             4.6 },
  { "hilited_comment_text_color",   "hilited_candidate_back_color",     4.6 },
  { "candidate_label_color",        "candidate_background",             3.1 },
  { "hilited_candidate_label_color","hilited_candidate_back_color",     3.1 },
  { "key_text_color",               "key_back_color",                   4.6 },
  { "key_symbol_color",             "key_back_color",                   3.1 },
  { "hilited_key_text_color",       "hilited_key_back_color",           3.1 },
  { "hilited_key_symbol_color",     "hilited_key_back_color",           3.1 },
  { "off_key_text_color",           "off_key_back_color",               4.6 },
  { "off_key_symbol_color",         "off_key_back_color",               3.1 },
  { "hilited_off_key_text_color",   "hilited_off_key_back_color",       3.1 },
  { "on_key_text_color",            "on_key_back_color",                4.6 },
  { "text_color",                   "back_color",                       4.6 },
  { "popup_text_color",             "popup_background_color",           4.6 },
  { "clipboard_text_color",         "clipboard_back_color",             4.6 },
  { "sidebar_text_color",           "sidebar_back_color",               4.6 },
  { "fix_key_text_color",           "off_key_back_color",               4.6 },
  { "fix_key_symbol_color",         "off_key_back_color",               3.1 },
  { "enter_text",                   "enter",                            4.6 },
}

-- 解析 0xAARRGGBB / 0xRRGGBB / #... ；不支持图片与颜色名
local function parse(v)
  if type(v) ~= "string" then return nil end
  local body
  if v:sub(1, 2) == "0x" or v:sub(1, 2) == "0X" then
    body = v:sub(3)
  elseif v:sub(1, 1) == "#" then
    body = v:sub(2)
  else
    return nil
  end
  if not body:match("^%x+$") then return nil end
  local n = #body
  if n <= 2 then return 0, 0, 0, 0 end            -- 与 ColorUtils 一致：极暗/全透明
  local val = tonumber(body, 16)
  if not val then return nil end
  if n <= 5 then
    return math.floor(val / 65536) % 256, math.floor(val / 256) % 256, val % 256, 255
  elseif n == 6 or n == 7 then
    return math.floor(val / 65536) % 256, math.floor(val / 256) % 256, val % 256, 255
  elseif n == 8 then
    return math.floor(val / 65536) % 256, math.floor(val / 256) % 256, val % 256,
      math.floor(val / 16777216) % 256
  end
  return nil
end

local function channel(c)
  c = c / 255
  if c <= 0.03928 then return c / 12.92 end
  return ((c + 0.055) / 1.055) ^ 2.4
end

local function luminance(r, g, b)
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
end

local function contrast(r1, g1, b1, r2, g2, b2)
  local l1, l2 = luminance(r1, g1, b1), luminance(r2, g2, b2)
  if l1 < l2 then l1, l2 = l2, l1 end
  return (l1 + 0.05) / (l2 + 0.05)
end

local function to_hex(r, g, b, a)
  return string.format("0x%02X%02X%02X%02X", a, r, g, b)
end

local function composite(r, g, b, a, br, bg_, bb)
  if a >= 250 then return r, g, b end
  local t = a / 255
  return math.floor(r * t + br * (1 - t) + 0.5),
    math.floor(g * t + bg_ * (1 - t) + 0.5),
    math.floor(b * t + bb * (1 - t) + 0.5)
end

-- 半透明底色"背后是什么"：按主题自身文字明暗推断（浅字→深底，深字→浅底）
local function guess_base(colors)
  local r, g, b = parse(colors.text_color or "")
  if r and luminance(r, g, b) > 0.5 then return 24, 24, 26 end
  return 255, 255, 255
end

-- 把前景朝黑（背景偏亮时）或白（背景偏暗时）线性插值，二分找到刚好达标的点
local function fix(fr, fg, fb, fa, br, bg_, bb, target)
  local bgLum = luminance(br, bg_, bb)
  local goalR, goalG, goalB = 0, 0, 0
  if bgLum < 0.18 then goalR, goalG, goalB = 255, 255, 255 end
  local lo, hi = 0, 1
  local best = { fr, fg, fb }
  for _ = 1, 24 do
    local t = (lo + hi) / 2
    local r = math.floor(fr + (goalR - fr) * t + 0.5)
    local g = math.floor(fg + (goalG - fg) * t + 0.5)
    local b = math.floor(fb + (goalB - fb) * t + 0.5)
    if contrast(r, g, b, br, bg_, bb) >= target then
      hi = t
      best = { r, g, b }
    else
      lo = t
    end
  end
  return best[1], best[2], best[3], fa
end

function M.apply(scheme)
  if type(scheme) ~= "table" then return scheme end
  local colors = scheme.colors or scheme
  local baseR, baseG, baseB = guess_base(colors)
  for _, pair in ipairs(M.PAIRS) do
    local fgKey, bgKey, target = pair[1], pair[2], pair[3]
    local fgv, bgv = colors[fgKey], colors[bgKey]
    if type(fgv) == "string" and type(bgv) == "string" then
      local fr, fg, fb, fa = parse(fgv)
      local br, bg_, bb, ba = parse(bgv)
      -- 前景必须是对比色；背景可以是半透明（按主题明暗推断背衬后合算）
      if fr and br and fa and ba and fa > 0 and ba > 0 then
        local mbr, mbg, mbb = composite(br, bg_, bb, ba, baseR, baseG, baseB)
        local mfr, mfg, mfb = composite(fr, fg, fb, fa, mbr, mbg, mbb)
        if contrast(mfr, mfg, mfb, mbr, mbg, mbb) < target then
          local r, g, b = fix(mfr, mfg, mfb, 255, mbr, mbg, mbb, target)
          colors[fgKey] = to_hex(r, g, b, 255)
        end
      end
    end
  end
  return scheme
end

return M
