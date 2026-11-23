--[[
  键盘切换弹窗的公共列表（输入类键盘 6 个）
  作用：任意一个输入键盘的切换弹窗里都能看到全部 6 个输入键盘，并且不显示自身。
  用法：
    1) 布局里直接引用： popup = KS.popup_for("Keyboard45key")
    2) merge 出来的键盘（小鹤 / 英文）：  KS.apply(kb, "KeyboardXiaohe")
  以后增删键盘只改这里的 order 即可。
]]
local KS = {}

-- 弹窗里的固定顺序
KS.order = {
  "Keyboard45key",     -- 中：普通 45 键主键盘
  "KeyboardXiaohe",    -- 鹤：小鹤辅助码提示版
  "Keyboard45key4pai", -- 大：四排七键
  "Keyboard18key",     -- 18：18 键
  "Keyboard14key",    -- 14：14 键
  "Keyboard9key",      -- T9：九宫
  "KeyboardEnglish",   -- 英：英文
  "Handwriting",       -- 手写：BambooIME 1.1.0 起支持的手写板（显示名由 preset_keys 的 Handwriting 决定）
  "BambooOcr",         -- 拍：BambooIME 1.2.0 起的拍照识字面板（显示名由 preset_keys 的 BambooOcr 决定）
  "AiPanel",           -- AI：BambooIME 2.9.0 起的「AI 模式」面板（显示名由 preset_keys 的 AiPanel 决定）
}

--- 返回"全部键盘减去 self"的列表
function KS.popup_for(self)
  local list = {}
  for i = 1, #KS.order do
    local name = KS.order[i]
    if name ~= self then
      list[#list + 1] = name
    end
  end
  return list
end

--- 修改一个已经 merge 好的键盘对象里的切换键弹窗
function KS.apply(kb, self_name)
  if not kb or not kb.rows then
    return kb
  end
  for i = 1, #kb.rows do
    local r = kb.rows[i]
    if r and r.keys then
      for j = 1, #r.keys do
        local k = r.keys[j]
        if k and k.click == "ModeSwitch" then
          k.popup = KS.popup_for(self_name)
        end
      end
    end
  end
  return kb
end

return KS
