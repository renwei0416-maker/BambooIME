--[[
lib\cjn\palettes.lua    「出彩·渐暖」主题的配色
==================================================================
配色主体直接复用 lib\colors\jiannuan.lua（也就是「渐暖」配色），
只额外补 26 个字母背景色 zmu_a ~ zmu_z：
取值方式 = 打开同文「出彩」配色对应的 26 张字母键底图（g_a.png ~ g_z.png），
取图片正中间一小块区域的平均色。这批底图本身是纯色块，所以取值就是它的本色。
布局里逐个字母键用 key_back_color = "zmu_q" 之类的方式取用。
]]

local M = {}

local base = safe_require("cjn.colors.jiannuan")

-- 26 个字母键的常态背景色（a~z，与键位上的字母一一对应）
local letter_colors = {
  zmu_a = "0xFFFFBDB3",
  zmu_b = "0xFFDFEFE5",
  zmu_c = "0xFFFEEEDF",
  zmu_d = "0xFFFFDCD7",
  zmu_e = "0xFFF9CD9C",
  zmu_f = "0xFFFFE9E6",
  zmu_g = "0xFFE7F1D8",
  zmu_h = "0xFFC9E3D6",
  zmu_i = "0xFF99D1D2",
  zmu_j = "0xFFC9E5E8",
  zmu_k = "0xFFBFCAE6",
  zmu_l = "0xFFC9B6D4",
  zmu_m = "0xFFE8D3E2",
  zmu_n = "0xFFD9DEF1",
  zmu_o = "0xFF93AAD6",
  zmu_p = "0xFFD2A6C7",
  zmu_q = "0xFFF07E6C",
  zmu_r = "0xFFFDE0A6",
  zmu_s = "0xFFFFD0C9",
  zmu_t = "0xFFFDF2B4",
  zmu_u = "0xFF99D0B9",
  zmu_v = "0xFFF2F6E8",
  zmu_w = "0xFFF39889",
  zmu_x = "0xFFFCE9DB",
  zmu_y = "0xFFC8E2B2",
  zmu_z = "0xFFFDEAE6",
}

-- merge 返回新表，不会改动 jiannuan 原表
M.colors = merge(base, letter_colors)
M.letters = letter_colors

return M
