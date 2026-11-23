--[[
  配色反色工具：把一套浅色配色整体转成深色（两套算法）
  A 通道反色  INV.channel(colors)   —— 逐通道 R/G/B 取反（0xRRGGBB -> 0x(FF-R)(FF-G)(FF-B)），色相翻到对面
  B 明度翻转  INV.lightness(colors) —— 转 HSL 后只把 L 换成 1-L，色相/饱和度不动
  通用规则：
    · 保留 alpha（0xAARRGGBB 的 AA 位不动）
    · 01/02 位这样的透明值（如 "0x00"）原样保留
    · 非颜色值（图片文件名、配色 id 字符串等）原样保留
]]
local INV = {}

--- 解析 "0xRRGGBB" / "0xAARRGGBB" / "#RRGGBB"；不是颜色就返回 nil
local function parse(v)
  if type(v) ~= "string" then
    return nil
  end
  local hex = v:gsub("^#", ""):gsub("^0[xX]", "")
  local n = #hex
  if not hex:match("^[0-9a-fA-F]+$") then
    return nil
  end
  if n == 6 then
    return 0xFF, tonumber(hex:sub(1, 2), 16), tonumber(hex:sub(3, 4), 16), tonumber(hex:sub(5, 6), 16)
  elseif n == 8 then
    return tonumber(hex:sub(1, 2), 16), tonumber(hex:sub(3, 4), 16), tonumber(hex:sub(5, 6), 16), tonumber(hex:sub(7, 8), 16)
  end
  return nil
end

local function hex2(a, r, g, b)
  return string.format("0x%02X%02X%02X%02X", a, r, g, b)
end

--- RGB -> HSL（h 0~360, s/l 0~1）
local function rgb2hsl(r, g, b)
  r, g, b = r / 255, g / 255, b / 255
  local mx = math.max(r, g, b)
  local mn = math.min(r, g, b)
  local l = (mx + mn) / 2
  if mx == mn then
    return 0, 0, l
  end
  local d = mx - mn
  local s = l > 0.5 and d / (2 - mx - mn) or d / (mx + mn)
  local h
  if mx == r then
    h = (g - b) / d + (g < b and 6 or 0)
  elseif mx == g then
    h = (b - r) / d + 2
  else
    h = (r - g) / d + 4
  end
  return h * 60, s, l
end

--- HSL -> RGB（各 0~255）
local function hsl2rgb(h, s, l)
  local function f(n)
    local k = (n + h / 30) % 12
    local a = s * math.min(l, 1 - l)
    return l - a * math.max(-1, math.min(k - 3, 9 - k, 1))
  end
  return math.floor(f(0) * 255 + 0.5), math.floor(f(8) * 255 + 0.5), math.floor(f(4) * 255 + 0.5)
end

--- A：逐通道反色
function INV.channel(colors)
  local out = {}
  for k, v in pairs(colors or {}) do
    local a, r, g, b = parse(v)
    if a then
      if a == 0 then
        out[k] = v
      else
        out[k] = hex2(a, 255 - r, 255 - g, 255 - b)
      end
    else
      out[k] = v
    end
  end
  return out
end

--- B：明度翻转（保留色相与饱和度）
function INV.lightness(colors)
  local out = {}
  for k, v in pairs(colors or {}) do
    local a, r, g, b = parse(v)
    if a then
      if a == 0 then
        out[k] = v
      else
        local h, s, l = rgb2hsl(r, g, b)
        local nr, ng, nb = hsl2rgb(h, s, 1 - l)
        out[k] = hex2(a, nr, ng, nb)
      end
    else
      out[k] = v
    end
  end
  return out
end

return INV
