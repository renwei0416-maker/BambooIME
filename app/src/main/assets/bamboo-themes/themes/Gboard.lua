--[[
Gboard.lua —— Gboard 风格皮肤（基于 NekoCatppuccin 的结构改造）

设计目标：把 Gboard（Material 3 版）的视觉语言搬进灵韵皮肤。
  · 浅灰底板 + 纯白字母键 + 灰一档的功能键，靠色阶分层，不画描边（key_border = 0）
  · 大圆角（10）、无阴影（key_shadow_radius = 0）、行距更疏（vertical_gap = 7）
  · 按键不显示上档角标（配色里把 key_symbol_color / off_key_symbol_color 设成键底色）
  · 蓝色回车 + 白色箭头（lib/key_style_gboard.lua + 配色 enter_text）
  · 空格与字母同色、无标签；候选栏高亮用 Material 的浅蓝底
  · 深色方案 = Gboard 深色（底板 0x1f2023、键 0x33363b、回车 0x8ab4f8）

文件组成：
  Gboard.lua                     ← 本文件（主题入口）
  lib/colors/gboard.lua          ← 浅色 / 深色两套配色
  lib/key_style_gboard.lua       ← 按键样式（由 key_style.lua 派生）
  lib/layouts/gboard_45keys.lua  ← 45 键布局（由 45keys.lua 派生）

其余布局（数字/编辑/计算器/设置/四排/T9/18 键）与 preset_keys 全部复用现有文件，
所以切换主题不会丢键盘、丢按键命令。
]]

-- 字体组合：顺序即回退顺序，第一个生效
local font_combo = {
  "HarmonyOS_Sans_SC_Regular.ttf", -- 主字体：中文 + 拉丁
  "LXGW975YuanSC.ttf",             -- 霞鹜：兜底汉字与 ␣ 等等宽符号
  "OpenMoji.ttf",                  -- emoji
  "CarlsonFont.ttf", "DejaVuSans.ttf",               -- 本主题图标的私有区字形（\u{F83B} 之类）
  "SourceHanSans.otf",             -- 思源：覆盖生僻字
  "PlangothicP1.otf",              -- 遍黑：超大字集
  "PlangothicP2.otf",              -- 遍黑：续
}

-- 配色模块：light / dark 两张表
local C = safe_require("gboard.colors.gboard")

local theme = theme {
  name = "Gboard",
  version = "1.0",
  author = "Gboard Style",
  -- 说明：Theme 只认 name / version / author / style / preedit / window / toolbar /
  -- candidates_tool / preset_keys / preset_keyboards / preset_color_schemes / liquid_keyboard，
  -- 没有 description 字段，写了会被静默丢弃，所以主题简介放在文件头部注释里。

  -- ======================================================================
  -- 全局样式
  -- ======================================================================
  style = style {
    keyboard_height = 265,                  -- 竖屏键盘高度（比 Catppuccin 的 255 略高，配 7 的行距）
    keyboard_height_land = 158,             -- 横屏键盘高度
    horizontal_gap = 4,                     -- 键间横向间距（Gboard 比默认更疏）
    vertical_gap = 6,                       -- 行间纵向间距
    round_corner = 10,                      -- 按键圆角：Gboard 的大圆角
    key_border = 0,                         -- 不画按键描边，靠底色区分
    key_shadow_radius = 0.0,                -- 无阴影：Material 3 是纯平面
    key_shadow_direction = { "down" },      -- 半径 0 时无效果，保留以便你要阴影时改半径
    keyboard_padding = 3,                   -- 键盘左右内边距
    keyboard_padding_top = 4,               -- 顶部内边距（键盘与候选栏之间）
    keyboard_padding_bottom = 10,           -- 底部内边距（留给手势条）
    keyboard_padding_land = 40,             -- 横屏左右内边距
    keyboard_padding_land_bottom = 0,
    content_padding = 0,                    -- 面板内容上下内缩（用图片背景时才需要）

    -- 候选栏：Gboard 的候选是一行文字，选中项是浅蓝色药丸
    candidate_view_height = 34,
    candidate_padding = 4,
    candidate_spacing = 1.0,
    candidate_text_vertical_bias = 1.0,
    candidate_border = 0,
    candidate_border_round = 0,
    candidate_corner_radius = 0,
    comment_height = 0,                     -- 不占位显示注释
    comment_vertical_bias = 0.0,
    comment_position = "OVERLAY",           -- 注释叠在候选上（占 0 高度）
    candidate_label = false,                -- 候选不显示序号（Gboard 没有 12345）

    -- 按键气泡 / 长按弹窗
    popup_bottom_margin = 68,
    popup_width = 48,
    popup_height = 50,
    popup_key_height = 52,

    -- 侧栏（T9 / 14 键 / 18 键）：跟随按键圆角
    sidebar_round_corner = -1,

    -- 回车标签：全部走图标（配合 enter_label_mode = 3）
    enter_label_mode = 3,
    enter_labels = {
      go = "ic@web",
      done = "ic@keyboard-return",
      next = "ic@keyboard-return",
      pre = "ic@keyboard-return",
      search = "ic@magnify",
      send = "ic@keyboard-return",
      default = "ic@keyboard-return",
    },

    auto_caps = false,
    reset_ascii_mode_on_focus_change = false,

    -- 字体：当前版本只认 font_list + 各项字号（逐项字体数组已废弃）
    fonts = {
      font_list = font_combo,
      candidate_size = 18,
      comment_size = 9,
      key_size = 22,
      key_long_size = 15,
      label_size = 22,
      symbol_size = 8,
      hint_size = 10,
      popup_size = 18,
      sidebar_size = 19,            -- 侧栏字号（与其它主题统一）
      clipboard_size = 13,
      clipboard_category_size = 14,
      liquid_tabs_size = 14,
    },
  },

  -- ======================================================================
  -- 配色（每个 scheme 一张独立的表，不能复用同一张表两次）
  -- ======================================================================
  -- default = 深色：内置主题时用户开箱即见深色，想换亮色选「Gboard Light」。
  -- 之所以改主题而不是改全局偏好（normalModeColor），是为了只影响 Gboard 一个主题。
  preset_color_schemes = {
    scheme("default", C.dark),
    scheme("Gboard Light", C.light),
  },

  -- ======================================================================
  -- 预编辑（拼音输入中那行字母）
  -- ======================================================================
  preedit = preedit {
    horizontal_padding = 8,
    top_end_radius = 5,
    alpha = 0.8,
    foreground = {
      font_size = 16,
    },
  },

  -- ======================================================================
  -- 悬浮窗（剪贴板、编辑器等弹出面板）
  -- ======================================================================
  window = window {
    insets = { vertical = 4, horizontal = 6 },
    item_padding = { horizontal = 6 },
    min_width = 0,
    corner_radius = 12,
    border = 0,
    shadow = 8,
    alpha = 1.0,
    foreground = {
      label_font_size = 16,
      text_font_size = 18,
      comment_font_size = 12,
    },
  },

  -- ======================================================================
  -- 候选栏两侧的导航按钮
  -- ======================================================================
  candidates_tool = {
    nav_width = 41,
    background = "back_color",
    buttons = {
      { action = "Up",              foreground = { style = "ic@arrow-up-bold",   font_size = 12, padding = 2, normal = "text_color" } },
      { action = "Down",            foreground = { style = "ic@arrow-down-bold", font_size = 12, padding = 2, normal = "text_color" } },
      { action = "BackSpace",       foreground = { style = "ic@backspace-outline", font_size = 12, padding = 2, normal = "text_color" } },
      { action = "CommitScriptText", foreground = { style = "ic@keyboard-return", font_size = 12, padding = 2, normal = "text_color" } },
    },
    -- 候选长按弹窗
    popup_width = 60,
    popup_text_size = 18,
    popup_text_color = "text_color",
    popup_background_color = "back_color",
    popup = {
      { action = "CandPin",    label = "置顶" },
      { action = "CandDelete", label = "删除" },
    },
    -- 已置顶词条换一套弹窗
    popup_by_type = {
      pinned = {
        popup_width = 100,
        popup = {
          { action = "CandPin",    label = "取消置顶" },
          { action = "CandUp",     label = "前移该词" },
          { action = "CandDown",   label = "后移该词" },
          { action = "CandDelete", label = "删除该词" },
        },
      },
    },
  },

  -- ======================================================================
  -- 顶部工具栏
  -- ======================================================================
  tool_bar = toolbar {
    back_style = "ic@chevron-triple-left",
    primary_button = {
      action = "KeyboardSettings",
      background = {
        type = "circle",
        corner_radius = 10,
        highlight = "hilited_candidate_button_color",
        normal = "none",
        vertical_inset = 4,
        horizontal_inset = 0,
      },
      foreground = {
        font_size = 18,
        padding = 2,
        normal = "text_color",
        style = "ic@cogs",
      },
    },
    buttons = {
      { action = "HideKeyboard",   foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@keyboard-close" } },
      { action = "VoiceSwitch",    foreground = { font_size = 18, padding = 2, normal = "text_color", option_styles = { "ic@microphone", "ic@stop-circle" } } },
      { action = "LiquidHistory",  foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@sticker-emoji" } },
      { action = "WindowClipboard", foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@clipboard-list-outline" } },
      { action = "KeyboardEditor", foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@cursor-move" } },
      { action = "Redo",           foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@rotate-right" } },
      { action = "Undo",           foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@rotate-left" } },
      { action = "FloatingSwitch", foreground = { font_size = 18, padding = 2, normal = "text_color", option_styles = { "ic@dock-window", "ic@keyboard-outline" } } },
      { action = "OneHandSwitch",  foreground = { font_size = 18, padding = 2, normal = "text_color", option_styles = { "ic@keyboard-variant", "ic@keyboard-outline" } } },
    },
  },

  -- ======================================================================
  -- 预设按键：与 Catppuccin 主题共用同一份命令表
  -- ======================================================================
  preset_keys = safe_require("gboard.preset_keys"),

  -- ======================================================================
  -- 键盘列表：default 是 Gboard 版 45 键，其余沿用现有布局
  -- ======================================================================
  preset_keyboards = {
    default = safe_require("gboard.layouts.gboard_45keys"),                     -- Gboard 45 键
    number = safe_require("gboard.layouts.number"),                             -- 数字键盘
    editor = safe_require("gboard.layouts.editor"),                             -- 编辑键盘（方向键等）
    calculator = safe_require("gboard.layouts.calculator"),                     -- 计算器
    settings = safe_require("gboard.layouts.settings"),                         -- 设置与导航
    qijian = safe_require("gboard.layouts.gboard_45keys_4pai"),                  -- Gboard 45 键四排
    wanxiang_pro = safe_require("gboard.layouts.gboard_45keys"),   -- 万象拼音PRO 主键盘（普通 45 键，无韵母提示）
    xiaohe = safe_require("gboard.layouts.gboard_45keys_flypy"),   -- 小鹤辅助码提示版（用切换键弹窗里的「鹤」进入）
    wanxiang_18key = safe_require("gboard.layouts.gboard_37keys_zrm"),    -- Gboard 18 键
    wanxiang_14key = safe_require("gboard.layouts.14keys"),
    wanxiang_t9 = safe_require("gboard.layouts.gboard_17keys"),       -- Gboard T9 九宫
    wanxiang_english = safe_require("gboard.layouts.gboard_english"),
  },

  -- ======================================================================
  -- 液态键盘（符号 / emoji / 历史 / 剪贴板等面板）
  -- ======================================================================
  liquid_keyboard = safe_require("gboard.liquid_keyboard"),
}

-- 对比度守卫：把「文字/图标 vs 背景」太接近的组合自动拉开（图片与半透明背景跳过）
local guard = safe_require("gboard.colors.contrast_guard")
for _, s in ipairs(theme.preset_color_schemes or {}) do guard.apply(s) end

return theme
