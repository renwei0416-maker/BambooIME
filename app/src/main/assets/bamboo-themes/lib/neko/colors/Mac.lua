--[[
latte.lua  LyraIME灵韵输入法 Catppuccin‑Latte【浅色主题配色定义脚本】
1、latte表：Catppuccin Latte官方调色板，只存放原始十六进制RGB字符串（没有Alpha透明度，灵韵内部自动补全0xFF完全不透明）
2、scheme对象：灵韵专用SchemeColors类型对象
   ①先把latte全部色名暴露到scheme顶层，供key_style.lua按键样式脚本通过字符串名字查表取色；
   ②再映射Trime/Lyra全部UI组件标准字段：候选栏、按键、气泡、T9、剪贴板等；
3、颜色格式说明：本文件色值格式为 "0xRRGGBB"（字符串），灵韵内部自动转为0xFFRRGGBB；
   若需要半透明，格式写成字符串 "0xAARRGGBB"；"0x00" = 完全透明；
4、文件作用：切换主题时，灵韵加载本文件得到完整scheme，key_style.lua、布局lua只写颜色名字符串，不用写死色值；
5、配套：mocha.lua为Mocha深色版，两套scheme对外暴露完全一样的键名，仅RGB数值不同。
]]

-- Catppuccin Latte 调色板原始色库
local latte = {
  rosewater = "0xdc8a78",  -- 玫瑰水，浅橘粉色
  flamingo  = "0xdd7878",  -- 火烈鸟，暖红色（shift按键常态底色）
  pink      = "0xea76cb",  -- 粉色（退格键常态底色）
  mauve     = "0x8839ef",  -- 锦葵紫，深紫
  red       = "0x2c2c2c",  -- 红色，用于高亮候选文字
  maroon    = "0x2c2c2c",  -- 褐红，注释文字、on状态文字颜色
  peach     = "0xfe640b",  -- 桃色，标点按键按下高亮
  yellow    = "0xdf8e1d",  -- 黄色，shift按键按下高亮
  green     = "0x40a02b",  -- 绿色
  teal      = "0x179299",  -- 水鸭青，全局border_color默认边框色
  sky       = "0x2c2c2c",  -- 天蓝色（数字切换功能键常态底色）
  sapphire  = "0x209fb5",  -- 蓝宝石蓝（斜杠、切换键常态底色）
  blue      = "0x1e66f5",  -- 蓝色，候选序号、symbol按键按下高亮
  lavender  = "0x7287fd",  -- 薰衣草紫（回车键常态底色，气泡选中背景）

  text      = "0xffffff",  -- 主体文字，普通文字、按键文字主色
  subtext1  = "0x5c5f77",  -- 次要文字1
  subtext0  = "0x6c6f85",  -- 次要文字0

  overlay2  = "0x7c7f93",  -- 覆盖层2，回车按键按下高亮
  overlay1  = "0x8c8fa1",  -- 覆盖层1
  overlay0  = "0x9ca0b0",  -- 覆盖层0，T9侧栏分隔线

  surface2  = "0xacb0be", -- 表面层2：按键按下高亮、off‑key背景
  surface1  = "0xa0a0a0", -- 表面层1：base按键边框、按下高亮
  surface0  = "0xccd0da", -- 表面层0
  base      = "0xa4a6a5", --【最浅底色】候选栏背景、base字母按键背景
  mantle    = "0xa4a6a5", --【中间底色】键盘底板背景、mantle字母按键背景
  crust     = "0xdce0e8", --【最深底色】crust字母按键背景

  gaoliang  = "0x218cfa", -- 表面层按下高亮,自定义的常量色值
  gongneng  = "0x2c2c2c", -- 像退格键等功能键的颜色
  enter     = "0x3b74ed", -- enter功能键的颜色
  space     = "0x4a4a4a", -- space功能键的颜色
  shuzi     = "0x2c2c2c", -- 数字排背景色
  yipai     = "0x4a4a4a", -- 一排背景色
  erpai     = "0x4a4a4a", -- 二排背景色
  sanpai    = "0x4a4a4a", -- 三排背景色
  sipai     = "0x4a4a4a", -- 四排背景色
  dipai    = "0x2c2c2c", -- 三排背景色
  biankuang = "0x737574",     -- 边框色
  gnbiankuang  = "0x737574", -- 功能键边框色
 
  none      = "0x00"      -- 完全透明
}

---@type SchemeColors 灵韵配色对象类型注解
local scheme = {
  -- 明暗模式联动配置：跟随系统深色/浅色自动切换配色
  light_scheme                           = "Mac", -- 当前浅色配色对外显示名称
  dark_scheme                            = "Mac_dark", -- 系统切深色时自动加载Mocha深色配色

  --################################################################################
  --【关键：把Catppuccin全部调色板键复制到scheme顶层】
  -- 供 key_style.lua 脚本使用字符串名字（如"flamingo"）查表获取颜色，缺失则按键颜色错乱！
  --################################################################################
  rosewater                              = latte.rosewater,
  flamingo                               = latte.flamingo,
  pink                                   = latte.pink,
  mauve                                  = latte.mauve,
  red                                    = latte.red,
  maroon                                 = latte.maroon,
  peach                                  = latte.peach,
  yellow                                 = latte.yellow,
  green                                  = latte.green,
  teal                                   = latte.teal,
  sky                                    = latte.sky,
  sapphire                               = latte.sapphire,
  blue                                   = latte.blue,
  lavender                               = latte.lavender,
  text                                   = latte.text,
  subtext1                               = latte.subtext1,
  subtext0                               = latte.subtext0,
  overlay2                               = latte.overlay2,
  overlay1                               = latte.overlay1,
  overlay0                               = latte.overlay0,
  surface2                               = latte.surface2,
  surface1                               = latte.surface1,
  surface0                               = latte.surface0,
  base                                   = latte.base,
  mantle                                 = latte.mantle,
  crust                                  = latte.crust,
  gaoliang                               = latte.gaoliang,
  gongneng                               = latte.gongneng,
  enter                                  = latte.enter,
  space                                  = latte.space,
  shuzi                                  = latte.shuzi,
  yipai                                  = latte.yipai,
  erpai                                  = latte.erpai,
  sanpai                                 = latte.sanpai,
  sipai                                  = latte.sipai,
  dipai                                  = latte.dipai,
  biankuang                              = latte.biankuang,
  gnbiankuang                            = latte.gnbiankuang,
  none                                   = latte.none,

  --################################################################################
  -- 🔹 基础全局UI颜色（候选栏、整体界面）
  --################################################################################
  back_color                             = latte.base,      -- 候选栏整体背景 base浅底色
  text_color                             = latte.text,      -- 普通主文字颜色 text
  border_color                           = latte.teal,      -- 通用边框默认颜色 teal水鸭青

  --################################################################################
  -- 🔹 候选栏区域（上栏候选词）
  --################################################################################
  candidate_text_color                   = latte.text,        -- 普通候选词文字颜色 text
  candidate_background                   = latte.base,        -- 候选栏背景 base
  comment_text_color                     = latte.maroon,      -- 候选词编码/注释文字 maroon褐红
  hilited_candidate_text_color           = latte.red,         --【选中的候选词文字】red红色
  hilited_candidate_back_color           = latte.none,        -- 选中候选词背景：完全透明none
  hilited_candidate_button_color         = latte.none,        -- 候选栏按钮高亮背景：透明none
  hilited_comment_text_color             = latte.red,         -- 选中候选的注释文字 red红色
  hilited_label_color                    = latte.blue,        --【候选序号高亮颜色】blue蓝色
  hilited_text_color                     = latte.maroon,      -- 预编辑高亮文字 maroon褐红
  hilited_back_color                     = latte.base,        -- 选中候选背景兜底 base
  label_color                            = latte.blue,        -- 候选序号普通颜色 blue蓝色
  candidate_border_color                 = latte.sky,        -- 候选栏边框 sky天蓝
  candidate_separator_color              = latte.sky,        -- 候选词之间分隔线 sky天蓝

  --################################################################################
  -- 🔹 预编辑区（输入编码行，Rime上屏前的编码）
  --################################################################################
  text_back_color                        = latte.base,        -- 预编辑编码区域背景 base

  --################################################################################
  -- 🔹【按键系统标准颜色】Trime/Lyra原生按键字段，key_style.lua的ST.off_key读取这里的值
  --################################################################################
  key_back_color                         = latte.base,        -- 默认按键常态背景 base
  key_border_color                       = latte.surface2,    -- 默认按键边框 surface2
  key_text_color                         = latte.text,        -- 默认按键主文字 text
  key_symbol_color                       = latte.text,        -- 默认按键上档符号文字 text

  hilited_key_back_color                 = latte.surface2,    -- 普通按键按下高亮背景 surface2
  hilited_key_text_color                 = latte.text,        -- 按键按下主文字颜色 text
  hilited_key_symbol_color               = latte.text,        -- 按键按下符号颜色 text

  off_key_back_color                     = latte.surface2,    -- off‑key：未激活功能键常态背景 surface2
  off_key_text_color                     = latte.text,        -- off‑key文字颜色 text
  off_key_symbol_color                   = latte.text,        -- off‑key符号颜色 text

  on_key_back_color                      = latte.surface2,    -- on‑key：开关打开状态按键背景 surface2
  on_key_text_color                      = latte.maroon,      -- on‑key开启状态文字 maroon褐红
  on_key_symbol_color                    = latte.maroon,      -- on‑key开启状态符号 maroon褐红

  hilited_off_key_back_color             = latte.base,        -- off‑key按下时背景 base
  hilited_off_key_text_color             = latte.text,        -- off‑key按下文字 text
  hilited_off_key_symbol_color           = latte.text,        -- off‑key按下符号 text

  hilited_on_key_back_color              = latte.base,        -- on‑key开启状态按下背景 base
  hilited_on_key_text_color              = latte.maroon,      -- on‑key开启按下文字 maroon
  hilited_on_key_symbol_color            = latte.maroon,      -- on‑key开启按下符号 maroon

  --################################################################################
  -- 🔹长按弹出气泡（长按按键弹出备选字符弹窗）
  --################################################################################
  popup_back_color                       = latte.base,        -- 气泡面板背景 base
  popup_text_color                       = latte.text,        -- 气泡普通文字 text
  hilited_popup_back_color               = latte.lavender,    -- 气泡内选中项背景 lavender薰衣草紫
  hilited_popup_text_color               = latte.base,        -- 气泡选中项文字 base（浅色底色，形成反白）

  --################################################################################
  -- 🔹键盘底板、液态键盘
  --################################################################################
  keyboard_back_color                    = latte.mantle,      --【整个键盘面板底板背景】mantle中间底色
  keyboard_background                    = latte.mantle,      -- 键盘底板备用字段，同上面mantle
  liquid_keyboard_background             = latte.mantle,      -- 液态表情符号键盘底板背景 mantle
  shadow_color                           = latte.text,        -- 按键阴影颜色 text
  root_background                        = latte.base,        -- 输入法最底层root背景 base

  --################################################################################
  -- 🔹长文本组件（剪贴板大段文本）
  --################################################################################
  long_text_color                        = latte.text,
  long_text_back_color                   = latte.base,

  --################################################################################
  -- 🔹 T9九宫模式侧边栏
  --################################################################################
  t9_side_back_color                     = latte.surface2,    -- T9侧栏背景 surface2
  t9_side_hilited_back_color             = latte.base,        -- T9侧栏选中项背景 base
  t9_side_text_color                     = latte.text,        -- T9侧栏文字 text
  t9_side_border_color                   = latte.surface2,    -- T9侧栏边框 surface2
  t9_side_spacing_color                  = latte.overlay0,    -- T9侧栏分隔线 overlay0

  --################################################################################
  -- 🔹剪贴板窗口
  --################################################################################
  clipboard_category_back_color          = latte.base,        -- 剪贴板分类标题背景 base
  clipboard_category_selected_back_color = latte.surface2,    -- 选中分类背景 surface2
  clipboard_category_selected_text_color = latte.text,        -- 选中分类文字 text
  clipboard_entry_back_color             = latte.base,        -- 剪贴板条目背景 base
  clipboard_checkbox_color               = latte.surface2,    -- 剪贴板复选框颜色 surface2
  hilited_clipboard_entry_back_color     = latte.surface2,    -- 剪贴板条目选中高亮 surface2

  --################################################################################
  -- 🔹【灵韵自定义扩展字段】给key_style.lua中ST.func功能键模板使用
  -- 功能按键文字、符号颜色，不使用系统off_key_text_color，使用这套自定义字段
  --################################################################################
  fix_key_text_color                     = latte.text,
  fix_key_symbol_color                   = latte.text
}

return scheme -- 输出完整配色scheme对象，NekoCatppuccin.lua通过safe_require加载