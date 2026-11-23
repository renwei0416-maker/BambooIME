-- SPDX-License-Identifier: GPL-3.0-or-later

--[[
LyraIME(灵韵输入法) NekoCatppuccin.lua 【主题总入口脚本】
作用：整个NekoCatppuccin主题的主配置文件，统一装配：全局外观、配色、候选窗口、工具栏、各个键盘布局、液态键盘。
注意点：
1. 本脚本是灵韵Lua专属主题，原版Trime不支持theme{}这套Lua API；
2. safe_require("xxx")：安全加载外部lua模块，找不到文件不会直接崩溃；
3. "ic@xxx"：灵韵内置矢量图标，不需要图片资源；
4. 颜色字段写字符串（如"back_color"）：代表去当前激活配色scheme查表获取真实色值；
5. font_combo 字体数组：优先级从前到后，前面字体缺失自动落到下一个；
6. 文件依赖关系：
    · colors.latte / colors.mocha / colors.frappe / colors.macchiato：四套Catppuccin配色
    · preset_keys.lua：预设按键命令定义
    · layouts/*.lua：各个键盘布局文件
    · liquid_keyboard.lua：液态表情符号键盘
]]

-- 字体优先级组合列表：整套主题全部UI公用的字体回退链
-- 优先顺序：鸿蒙黑体，霞鹜975圆体 → OpenMoji表情字体 → CarlsonFont(灵韵专用图标符号字体) → 思源黑体 → 遍黑体P1(基础汉字) → 遍黑体P2(生僻扩展汉字)
local font_combo = {
  "HarmonyOS_Sans_SC_Regular.ttf", "LXGW975YuanSC.ttf", "OpenMoji.ttf", "CarlsonFont.ttf",
  "SourceHanSans.otf", "PlangothicP1.otf", "PlangothicP2.otf", "DejaVuSans.ttf"
}

-- 构建主题总对象 theme{}，灵韵加载此脚本读取整套主题配置
local CP = safe_require("cjn.palettes")
local INV = safe_require("cjn.invert")  -- 反色工具：生成深色配色  -- 「出彩·渐暖」配色（渐暖 + 26 个字母背景色）

local theme = theme {
  -- ========================================================================
  -- 元数据：主题基础信息
  -- ========================================================================
  name = "出彩·渐暖",        -- 主题对外显示名称
  version = "5.0",           -- 主题版本号
  author = "Carlson24(鹤衔春雪)", -- 作者信息

  -- ========================================================================
  -- 全局样式 (GeneralStyle)
  -- 键盘、候选栏、字号、边距、圆角、阴影、字体的全局公共参数
  -- ========================================================================
  style = style {
    -- ---------- 键盘本体设置 ----------
    keyboard_height = 250,        -- [int] 竖屏模式键盘整体高度，单位px
    keyboard_height_land = 150,   -- [int] 横屏模式键盘整体高度，单位px
    horizontal_gap = 4,           -- [int] 按键水平间距，按键和按键之间横向空隙(px)
    vertical_gap = 6,             -- [int] 按键垂直间距，按键行与行之间纵向空隙(px)
    round_corner = 6,             -- [float] 所有按键统一圆角半径(px)
    key_shadow_radius = 1.1,                      -- [float] 按键阴影半径
    key_shadow_direction = { "right", "down" }, -- [string] 按键阴影位置
    key_border = 0,               -- [int] 按键边框线条宽度(px)

    -- ---------- 竖屏键盘边距（键盘距离屏幕四边留白） ----------
    keyboard_padding = 2,         -- [int] 竖屏：键盘距离屏幕左右两侧留白，曲面屏可以调大减少误触
    keyboard_padding_bottom = 12,  -- [int] 竖屏：键盘距离屏幕底部留白，防止系统全面屏手势误触键盘
    keyboard_padding_top = 3,     -- [int] 竖屏：键盘距离候选栏/屏幕上方留白

    -- ---------- 横屏键盘边距 ----------
    keyboard_padding_land = 40,       -- [int] 横屏：键盘左右两侧留白，避免按键过度拉伸变形
    keyboard_padding_land_bottom = 0, -- [int] 横屏：键盘距离屏幕底部留白

    -- ---------- 候选栏（上栏候选词区域） ----------
    candidate_view_height = 35,         -- [int] 候选栏整体高度(px)
    candidate_padding = 4,              -- [int] 每一个候选项内部左右内边距
    candidate_spacing = 1.0,            -- [float] 候选项之间分隔线宽度
    candidate_text_vertical_bias = 1.0,  -- [float] 候选文字垂直偏移；0=靠上，0.5居中，1靠下
    candidate_border = 0,               -- [int] 候选栏外边框宽度，0关闭边框
    candidate_border_round = 0,         -- [float] 候选栏整体外框圆角
    candidate_corner_radius = 0,        -- [float] 单个候选项圆角半径

    -- ---------- 编码注释（候选栏的编码提示，助记编码） ----------
    comment_height = 0,                 -- [int] 编码提示区域高度；0=完全隐藏编码提示
    comment_vertical_bias = 0.0,        -- [float] overlay模式下注释文字垂直偏移
    comment_position = "OVERLAY",       -- [CommentPosition] 注释显示模式
                                        -- RIGHT：候选词右侧；TOP：候选词上方；OVERLAY：文字叠加在候选词上

    -- ---------- 长按按键弹出气泡（预览弹窗） ----------
    popup_bottom_margin = 68, -- [int] 气泡距离原按键底部的距离，控制气泡向上弹出的位置
    popup_width = 38,         -- [int] 气泡弹窗宽度
    popup_height = 50,        -- [int] 气泡弹窗整体高度
    popup_key_height = 52,    -- [int] 气泡内部子按键容器高度

    -- ---------- 回车键图标/文字模式 ----------
    enter_label_mode = 3, -- [int] 回车键ActionLabel模式
                          -- 0=不使用动态图标；1=仅使用action；2=优先action；3=fallback，没有action时使用default
    enter_labels = {      -- [EnterLabel] 不同场景下回车键显示的内置矢量图标 ic@xxx
      go = "ic@web",          -- 跳转网页场景
      done = "ic@check",      -- 完成操作场景
      next = "ic@keyboard_return", -- 下一项
      pre = "ic@keyboard_return",  -- 上一项
      search = "ic@magnify",       -- 搜索场景
      send = "ic@keyboard_return", -- 发送消息场景
      default = "ic@keyboard_return" -- 默认普通回车图标
    },

    -- ---------- T9九宫模式侧边栏 ----------
    sidebar_round_corner = -1,    -- [float] 侧栏圆角；-1=复用全局 round_corner（原 t9_side_round_corner 是旧键，引擎不认）

    -- ---------- 杂项开关 ----------
    auto_caps = false,                        -- [bool] 是否开启输入句子自动句首大写
    -- background_folder = "backgrounds",     -- [string] 背景图片存放子文件夹，注释状态为默认backgrounds
    reset_ascii_mode_on_focus_change = false, -- [bool] 切换app焦点时是否强制重置为中文；false=记忆中英输入状态

    -- ---------- 全局字体与字号配置，整套主题各个UI模块全部复用font_combo字体链 ----------
    fonts = {
      font_list = font_combo,        -- [必填] 全应用统一字体链（新版 FontManager 只认这一项）
      candidate = font_combo,        -- 候选词字体
      candidate_size = 18,           -- 候选词字号
      comment = font_combo,          -- 编码注释字体
      comment_size = 9,              -- 编码注释字号
      key = font_combo,              -- 按键主文字字体
      key_size = 19,                 -- 按键普通字号
      key_long_size = 16,            -- 按键长文本标签字号
      label = font_combo,            -- 标签/序号字体
      label_size = 22,               -- 标签字号
      latin = font_combo,            -- 西文字体
      symbol = font_combo,           -- 符号、上档小符号字体
      symbol_size = 10,              -- 符号字号
      text = font_combo,             -- 预编辑编码文字字体
      hint = font_combo,             -- 按键底部hint助记提示字体
      hint_size = 10,                -- hint提示字号
      hanb = font_combo,             -- 生僻扩展汉字字体
      popup = font_combo,            -- 长按弹出气泡窗口字体
      popup_size = 20,               -- 气泡窗口字号
      sidebar_size = 19,             -- 侧栏字号（原 t9_side_size 是旧键，引擎一直回退到 key_size=19）
      clipboard = font_combo,        -- 剪贴板弹窗字体
      clipboard_size = 13,           -- 剪贴板条目字号
      clipboard_category = font_combo, -- 剪贴板分类标签字体
      clipboard_category_size = 14    -- 剪贴板分类标签字号
    }
  },

  -- ========================================================================
  -- 配色方案集合 ColorScheme
  -- 加载4套Catppuccin配色：Latte亮色 / Mocha暗色 / Frappe / Macchiato
  -- scheme(对外配色ID, 加载配色lua脚本)
  -- ========================================================================
  preset_color_schemes = {
    -- 浅色：渐暖的全部颜色 + 26 个字母背景色（取自同文「出彩」字母键底图中心色）
    scheme("出彩·渐暖", CP.colors),
    -- 深色 A：逐通道反色（色相翻到对面，例如珊瑚 <-> 深青）
    scheme("出彩·暗A", INV.channel(CP.colors)),
    -- 深色 B：明度翻转（保留色相与饱和度，只是浅色变深色）
    scheme("出彩·暗B", INV.lightness(CP.colors)),
  },

  -- ========================================================================
  -- 预编辑区 Preedit：输入编码的显示区域（Rime编码行）
  -- ========================================================================
  preedit = preedit {
    horizontal_padding = 8, -- [int] 编码文字左右内边距px
    top_end_radius = 5,     -- [float] 预编辑区域上端圆角
    alpha = 0.8,            -- [float] 背景透明度，0完全透明 ~ 1完全不透明
    foreground = {          -- [Foreground] 前景文字样式
      font_size = 16        -- [float] 编码文字字号
    }
  },

  -- ========================================================================
  -- 候选悬浮窗口 Window：弹出式候选悬浮窗（非状态栏候选栏）
  -- ========================================================================
  window = window {
    insets = { vertical = 2, horizontal = 2 }, -- [Padding] 悬浮窗口整体内边距，垂直、水平
    item_padding = { horizontal = 4 },         -- [Padding] 每个候选项水平内边距
    min_width = 0,                             -- [int] 悬浮窗口最小宽度，0不限制
    corner_radius = 5,                         -- [float] 悬浮窗口圆角半径
    border = 1,                                -- [int] 悬浮窗外边框宽度px
    shadow = 10,                               -- [float] 悬浮窗阴影模糊半径
    alpha = 0.85,                              -- [float] 悬浮窗整体透明度 0~1
    foreground = {                              -- [Foreground] 悬浮窗文字样式
      label_font_size = 16,                    -- [float] 候选项序号字号
      text_font_size = 18,                     -- [float] 候选主词字号
      comment_font_size = 12                   -- [float] 候选编码注释字号
    }
  },

  -- ========================================================================
  -- 候选工具栏 CandidatesTool：候选栏侧边工具按钮 + 候选词长按弹出菜单
  -- ========================================================================
  candidates_tool = {
    -- 候选翻页侧边工具按钮
    nav_width = 41,                     -- [int] 候选侧边工具栏宽度px
    button_font = font_combo,            -- [string[]] 工具栏按钮字体，复用字体组合
    background = "back_color",          -- [string] 工具栏背景，查表取配色back_color
    buttons = {
      { action = "Up",               foreground = { style = "ic@arrow-up-bold", font_size = 12 } },    -- 上翻候选
      { action = "Down",             foreground = { style = "ic@arrow-down-bold", font_size = 12 } },  -- 下翻候选
      { action = "BackSpace",        foreground = { style = "ic@backspace-outline", font_size = 12 } },-- 删除
      { action = "CommitScriptText", foreground = { style = "ic@keyboard-return", font_size = 12 } }  -- 上屏编码
    },

    -- 候选词长按弹出通用菜单
    popup_width = 60,                      -- [int] 长按菜单弹窗宽度
    popup_font = font_combo,                -- [string[]] 菜单字体
    popup_text_size = 18,                  -- [int] 菜单文字字号
    popup_text_color = "text_color",        -- [string] 菜单文字颜色，查表配色
    popup_background_color = "back_color",  -- [string] 菜单背景颜色，查表配色
    popup = {
      { action = "CandPin", label = "置顶" },   -- 候选词置顶
      { action = "CandDelete", label = "删除" }   -- 删除该候选词
    },
    -- 【已经置顶的候选词】长按，弹出的特殊菜单（popup_by_type：按词条状态区分菜单）
    popup_by_type = {
      pinned = {
        popup_width = 100,
        popup = {
          { action = "CandPin", label = "取消置顶" },
          { action = "CandUp", label = "前移该词" },
          { action = "CandDown", label = "后移该词" },
          { action = "CandDelete", label = "删除该词" }
        }
      }
    }
  },

  -- ========================================================================
  -- 工具栏 ToolBar：键盘上方悬浮工具条，各种快捷功能按钮
  -- ========================================================================
  tool_bar = toolbar {
    button_font = font_combo,                -- 工具栏全部按钮字体
    back_style = "ic@chevron-triple-left",   -- 工具栏返回图标
    primary_button = { -- 工具栏左侧固定主圆形按钮：打开键盘设置
      action = "KeyboardSettings",
      background = {
        type = "circle",                     -- 按钮背景形状 circle圆形 / rectangle矩形
        corner_radius = 10,                 -- 圆角
        highlight = "hilited_candidate_button_color", -- 按下高亮背景（查表配色）
        normal = "none",                     -- 常态：无背景
        vertical_inset = 4,                  -- 垂直方向内缩距离
        horizontal_inset = 0                -- 水平方向内缩距离
      },
      foreground = {
        font_size = 18,
        padding = 2,
        normal = "text_color",              -- 常态图标颜色，查表配色
        style = "ic@cogs"                   -- 齿轮设置图标
      }
    },
    -- 工具栏右侧一排功能按钮集合
    buttons = {
      {
        action = "HideKeyboard", -- 收起键盘
        foreground = {
          font_size = 18,
          padding = 2,
          normal = "text_color",
          style = "ic@keyboard-close"
        }
      },
      {
        action = "VoiceSwitch", -- 语音输入开关（双状态切换图标 option_styles）
        foreground = {
          font_size = 18,
          padding = 2,
          normal = "text_color",
          option_styles = { "ic@microphone", "ic@stop-circle" }
        }
      },
      {
        action = "BambooSymbol", -- 液态键盘历史记录
        foreground = {
          font_size = 18,
          padding = 2,
          normal = "text_color",
          style = "ic@symbol"
        }
      },
      {
        action = "WindowClipboard", -- 打开剪贴板窗口
        foreground = {
          font_size = 18,
          padding = 2,
          normal = "text_color",
          style = "ic@clipboard-list-outline"
        }
      },
      {
        action = "KeyboardEditor", -- 打开键盘编辑器
        foreground = {
          font_size = 18,
          padding = 2,
          normal = "text_color",
          style = "ic@cursor-move"
        }
      },
      {
        action = "Redo", -- 重做
        foreground = {
          font_size = 18,
          padding = 2,
          normal = "text_color",
          style = "ic@rotate-right"
        }
      },
      {
        action = "Undo", -- 撤销
        foreground = {
          font_size = 18,
          padding = 2,
          normal = "text_color",
          style = "ic@rotate-left"
        }
      },
      {
        action = "FloatingSwitch", -- 悬浮键盘开关，双状态图标
        foreground = {
          font_size = 18,
          padding = 2,
          normal = "text_color",
          option_styles = { "ic@dock-window", "ic@keyboard-outline" }
        }
      },
      {
        action = "OneHandSwitch", -- 单手模式开关，双状态图标
        foreground = {
          font_size = 18,
          padding = 2,
          normal = "text_color",
          option_styles = { "ic@keyboard-variant", "ic@keyboard-outline" }
        }
      }
    }
  },

  -- ========================================================================
  -- 预设按键 PresetKey：加载全局按键命令映射
  -- 里面定义各种动作命令，布局中可以直接引用命令名
  -- ========================================================================
  preset_keys = safe_require("cjn.preset_keys"),

  -- ========================================================================
  -- 预设键盘布局集合 preset_keyboards
  -- key=布局ID，值加载外部布局lua；切换布局时填写这里的ID
  -- ========================================================================
  preset_keyboards = {
    default          = safe_require("cjn.layouts.45keys"),        -- 默认主布局：出彩·渐暖 45 键
    calculator       = safe_require("cjn.layouts.calculator"),        -- 计算器布局
    editor           = safe_require("cjn.layouts.editor"),            -- 编辑方向键布局
    number           = safe_require("cjn.layouts.number"),            -- 数字布局
    settings         = safe_require("cjn.layouts.settings"),          -- 设置菜单布局
    qijian           = safe_require("cjn.layouts.45keys_4pai"),   -- 出彩·渐暖 45 键四排
    wanxiang_pro = safe_require("cjn.layouts.45keys"),   -- 万象拼音PRO 主键盘（普通 45 键，无韵母提示）
    xiaohe = safe_require("cjn.layouts.45keys_flypy"),   -- 小鹤辅助码提示版（用切换键弹窗里的「鹤」进入）
    wanxiang_18key = safe_require("cjn.layouts.37keys_zrm"),    -- 出彩 18 键
    wanxiang_14key = safe_require("cjn.layouts.14keys"),
    wanxiang_t9 = safe_require("cjn.layouts.17keys"),       -- 出彩 T9 九宫
    wanxiang_english = safe_require("cjn.layouts.english") -- 复用45键布局，强制英文模式
  },

  -- ========================================================================
  -- 液态键盘 LiquidKeyboard：表情、符号、音标等弹窗符号面板
  -- ========================================================================
  liquid_keyboard = safe_require("cjn.liquid_keyboard")
}

return theme -- 将完整theme对象导出，灵韵加载此脚本完成主题初始化