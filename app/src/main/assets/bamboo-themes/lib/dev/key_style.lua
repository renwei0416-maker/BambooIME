--[[
lib\dev\key_style.lua    Dev 主题专用按键样式模板
------------------------------------------------------------------
由 lib\key_style_4pai.lua 派生，只改两处：
  1. 字母键字号 25 -> 22
  2. ST.enter 的文字/图标改用配色里的 enter_text 色（回车是强调色时才有对比）
颜色值仍然是「配色 scheme 里的键名」，由各主题的 lib\<ns>\palettes.lua 提供。
]]

--[[
LyraIME(灵韵输入法) key_style.lua
按键样式模板库脚本
注意：
1. 本脚本为灵韵Lua布局专用，原版Trime不支持【颜色名字符串查表】语法
2. 这里所有带双引号的颜色值不是真实色值，是配色scheme中的键名，运行时从latte.lua/mocha.lua读取真实ARGB颜色
3. merge(A,B)：继承模板A的全部属性，B覆盖/新增属性
4. 字段规则：
    key_back_color：按键常态背景
    hilited_key_back_color：按键按下/高亮背景
    key_border_color：按键边框颜色
    key_text_color：按键主文字颜色（字母）
    key_symbol_color：按键右上角上档符号颜色
    key_text_size：主文字字号
    symbol_text_size：上档小符号字号
    key_symbol_offset_x：上档符号横向偏移；正数向右，负数向左
    key_text_offset_y：主文字纵向偏移；正数向下，负数向上
    key_hint_offset_y：底部hint助记提示文字纵向偏移
]]

local ST = {} -- 定义ST表，用来存放全部可复用的按键样式模板

-- 模板：off_key 关闭状态按键基础模板
-- off系列：shift未锁定、普通功能键默认状态的一套颜色，来自配色方案原生字段
ST.off_key = key {
  key_back_color = "shuzi",        -- 常态背景（读取配色中off_key_back_color值）
  key_text_color = "off_key_text_color",        -- 常态文字颜色
  key_symbol_color = "off_key_symbol_color",    -- 常态上档符号颜色
  key_border_color = "gnbiankuang",       -- 边框颜色
  hilited_key_back_color = "gaoliang", -- 按下高亮背景
  hilited_key_text_color = "hilited_off_key_text_color", -- 按下高亮文字
  hilited_key_symbol_color = "hilited_off_key_symbol_color" -- 按下高亮符号
}

-- 模板：hint_offset 文字偏移模板
-- 统一控制按键主文字、底部hint助记提示的上下位置
ST.hint_offset = key {
  key_text_offset_y = -4,  -- 按键主文字纵向偏移，-4：向上偏移4px
  key_hint_offset_y = 1    -- 底部hint助记提示纵向偏移，1：向下偏移1px
}

-- 模板：number 数字行按键模板，继承off_key基础颜色
ST.number = key(merge(ST.off_key, {
  key_text_size = 19,         -- 数字主文字字号19
  symbol_text_size = 8,       -- 右上角上档小符号字号9
  key_symbol_offset_x = 2     -- 上档符号向右偏移2px
}))

-- 模板：settings 设置大图标按键模板
-- 用于菜单页大图标按钮，背景跟随键盘整体底板背景
ST.settings = key {
  key_text_size = 38,                 -- 图标文字大号字号40
  symbol_text_size = 12,              -- 符号字号14
  key_border_color = "keyboard_background", -- 边框颜色：读取键盘底板背景色
  key_back_color = "keyboard_background"     -- 按键背景和键盘底板融为一体
}

-- 模板：func 功能键公共基础模板，继承off_key
-- shift、退格、回车、标点等彩色功能键全部基于此模板扩展
ST.func = key(merge(ST.off_key, {
  key_text_size = 22,                -- 功能键主文字字号23
  symbol_text_size = 8,              -- 功能键右上角上档符号字号8
  key_text_color = "fix_key_text_color",    -- 功能键文字颜色（配色自定义字段）
  key_symbol_color = "fix_key_symbol_color" -- 功能键上档符号颜色
}))

-- 模板：base 字母行B模板（45键第二行 q‑p）
-- Catppuccin主题最浅一层按键底色
ST.base = key {
  key_text_size = 22,                  -- 按键文字字号
  popup_text_size = 20,                -- 弹窗（按键气泡）字号
  key_long_text_size = 10,             -- 长标签按键字号
  symbol_text_size = 8,               -- 符号（上符）字号
  key_back_color = "yipai",             -- 常态背景，读取配色base色
  key_border_color = "biankuang",       -- 边框颜色 
  hilited_key_back_color = "gaoliang"  -- 按下高亮背景 
}

-- 模板：mantle 字母行M模板（45键第三行 a‑l）
-- Catppuccin中间层级底色，比base深一点
ST.mantle = key {
  key_text_size = 22,                  -- 按键文字字号
  popup_text_size = 20,                -- 弹窗（按键气泡）字号
  key_long_text_size = 10,             -- 长标签按键字号
  symbol_text_size = 8,               -- 符号（上符）字号
  key_back_color = "erpai",             -- 常态背景，读取配色base色
  key_border_color = "biankuang",       -- 边框颜色 
  hilited_key_back_color = "gaoliang"  -- 按下高亮背景 
}

-- 模板：crust 字母行C模板（45键第四行 z‑m）
-- Catppuccin最深一层普通字母按键底色
ST.crust = key {
  key_text_size = 22,                  -- 按键文字字号
  popup_text_size = 20,                -- 弹窗（按键气泡）字号
  key_long_text_size = 10,             -- 长标签按键字号
  symbol_text_size = 8,               -- 符号（上符）字号
  key_back_color = "sanpai",             -- 常态背景，读取配色base色
  key_border_color = "biankuang",       -- 边框颜色 
  hilited_key_back_color = "gaoliang"  -- 按下高亮背景 
}

-- 模板：crust 字母行C模板（45键第四行 z‑m）
-- Catppuccin最深一层普通字母按键底色
ST.qijiansipai = key {
  key_text_size = 22,                  -- 按键文字字号
  popup_text_size = 20,                -- 弹窗（按键气泡）字号
  key_long_text_size = 10,             -- 长标签按键字号
  symbol_text_size = 8,               -- 符号（上符）字号
  key_back_color = "sipai",             -- 常态背景，读取配色base色
  key_border_color = "biankuang",       -- 边框颜色 
  hilited_key_back_color = "gaoliang"  -- 按下高亮背景 
}

-- 模板：shift 大小写切换按键模板，继承func功能键模板
ST.shift = key(merge(ST.func, {
  key_back_color = "gongneng",     -- Shift常态背景色 flamingo
  key_border_color = "gnbiankuang",  -- Shift边框颜色 flamingo
  hilited_key_back_color = "gaoliang"-- Shift按下高亮背景 yellow
}))

-- 模板：backspace 退格删除键模板，继承func
ST.backspace = key(merge(ST.func, {
  key_back_color = "gongneng",        -- 退格常态背景 pink
  key_border_color = "gnbiankuang",      -- 退格边框 pink
  hilited_key_back_color = "gaoliang" -- 按下高亮 flamingo
}))

-- 模板：symbol 布局切换/数字切换功能键，继承func
ST.symbol = key(merge(ST.func, {
  key_back_color = "dipai",
  key_border_color = "gnbiankuang",
  hilited_key_back_color = "gaoliang"
}))

-- 模板：punct 标点键模板（逗号、句号），继承func
ST.punct = key(merge(ST.func, {
  key_back_color = "dipai",
  key_border_color = "gnbiankuang",
  hilited_key_back_color = "gaoliang"
}))

-- 模板：slash 斜杠按键模板，继承func
ST.slash = key(merge(ST.func, {
  key_back_color = "dipai",
  key_border_color = "gnbiankuang",
  hilited_key_back_color = "gaoliang"
}))

-- 模板：switch 输入法/布局切换按键模板，继承func
ST.switch = key(merge(ST.func, {
  key_back_color = "dipai",
  key_border_color = "gnbiankuang",
  hilited_key_back_color = "gaoliang"
}))

-- 模板：space 空格按键模板，继承func
ST.space = key {
  key_text_size = 20,                  -- 按键文字字号
  popup_text_size = 20,                -- 弹窗（按键气泡）字号
  key_long_text_size = 10,             -- 长标签按键字号
  symbol_text_size = 11,               -- 符号（上符）字号
  key_back_color = "space",             -- 常态背景，读取配色base色
  key_border_color = "gnbiankuang",       -- 边框颜色 
  hilited_key_back_color = "gaoliang"  -- 按下高亮背景 
}

-- 模板：enter 回车键模板，继承func
ST.enter = key(merge(ST.func, {
  key_back_color = "enter",
  key_border_color = "gnbiankuang",
  key_text_color = "enter_text",        -- 回车上的文字/图标用对比色（配色里的 enter_text）
  key_symbol_color = "enter_text",
  hilited_key_back_color = "gaoliang",
  hilited_key_text_color = "enter_text",
  hilited_key_symbol_color = "enter_text"
}))

-- 模板：clean 备用清除按键模板，继承func
-- 布局中没有默认使用，预留给自定义布局调用
ST.clean = key(merge(ST.func, {
  key_back_color = "dipai",
  key_border_color = "gnbiankuang",
  hilited_key_back_color = "gaoliang"
}))

return ST -- 将全部ST模板导出，供布局文件使用 safe_require("key_style") 加载