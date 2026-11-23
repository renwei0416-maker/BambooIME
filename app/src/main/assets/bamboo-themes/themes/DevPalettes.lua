--[[
DevPalettes.lua    多配色开发主题（LyraIME 灵韵输入法）
==================================================================

一个主题里放 10 套开发者配色，进「配色选择」切换即可：
  Nord / Tokyo Night / Gruvbox / Rosé Pine / Everforest / Dracula
  Catppuccin Mocha / One Dark / Solarized Light / Rosé Pine Dawn

目录（全部自成一套，不引用其它主题的任何文件）：
  DevPalettes.lua                     主题入口（本文件）
  lib\dev\palettes.lua                10 套配色 + 配色工厂 make()
  lib\dev\key_style.lua               按键样式模板（由 lib\key_style_4pai.lua 派生）
  lib\dev\layouts\45keys.lua          三排 45 键布局
  lib\dev\layouts\45keys_4pai.lua     四排 45 键布局
  （共用只读资源：lib\preset_keys.lua 的按键定义、lib\layouts 的数字/编辑/计算器/设置键盘、fonts\）

设计取向：无描边、小圆角、无阴影，角标用柔和的次要色；候选栏压到 32dp 给键盘让高度，
回车与选中候选用强调色，把视觉噪音降到最低，长时间码字不累眼。
]]

-- 字体表：新版灵韵只认 fonts.font_list（全应用只有这一套字体，与其它主题共用 themes\fonts\）
local font_combo = {
  "HarmonyOS_Sans_SC_Regular.ttf",
  "LXGW975YuanSC.ttf",
  "OpenMoji.ttf",
  "CarlsonFont.ttf",
  "SourceHanSans.otf",
  "PlangothicP1.otf",
  "PlangothicP2.otf", "DejaVuSans.ttf",
}

local P = safe_require("dev.palettes")

-- scheme(id, colors) 要单独构造，不能直接写在 theme{} 里
local schemes = {}
for i, item in ipairs(P.list) do
  schemes[i] = scheme(item.id, item.colors)
end

local theme_tbl = theme {
  name = "Dev Palettes",
  version = "1.0",
  author = "LyraIME-SKIN",

  -- ============================================================
  -- 全局版式
  -- ============================================================
  style = style {
    keyboard_height = 262,              -- 竖屏键盘高度（px）
    keyboard_height_land = 152,         -- 横屏键盘高度
    horizontal_gap = 4,                 -- 键与键的横向间隙
    vertical_gap = 6,                   -- 行间距
    round_corner = 8,                   -- 按键圆角
    key_border = 0,                     -- 按键描边宽度：0 = 无描边
    key_shadow_radius = 0.0,            -- 按键阴影：0 = 关闭
    keyboard_padding = 3,               -- 键盘左右内边距
    keyboard_padding_top = 4,           -- 键盘顶部内边距
    keyboard_padding_bottom = 10,       -- 键盘底部内边距（给手势条留位置）
    keyboard_padding_land = 40,         -- 横屏左右内边距
    keyboard_padding_land_bottom = 0,
    content_padding = 0,

    -- 候选栏
    candidate_view_height = 32,
    candidate_padding = 4,
    candidate_spacing = 1.0,
    candidate_text_vertical_bias = 1.0,
    candidate_border = 0,
    candidate_border_round = 0,
    candidate_corner_radius = 0,
    candidate_label = false,            -- 不显示候选序号，更像开发工具
    comment_height = 0,
    comment_vertical_bias = 0.0,
    comment_position = "OVERLAY",       -- 注释叠在候选右侧

    -- 长按弹窗
    popup_bottom_margin = 68,
    popup_width = 38,
    popup_height = 50,
    popup_key_height = 52,

    sidebar_round_corner = -1,          -- -1 = 跟随 round_corner

    -- 回车图标：按输入场景自动切换
    enter_label_mode = 3,
    enter_labels = {
      go      = "ic@web",
      done    = "ic@check",
      next    = "ic@keyboard-return",
      pre     = "ic@keyboard-return",
      search  = "ic@magnify",
      send    = "ic@keyboard-return",
      default = "ic@keyboard-return",
    },

    auto_caps = false,
    reset_ascii_mode_on_focus_change = false,

    -- 字体：font_list 是唯一有效字段（逐项字体数组新版已废弃）
    fonts = {
      font_list = font_combo,
      candidate_size = 18,
      candidate_label_size = 14,
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

  -- ============================================================
  -- 编码区 / 悬浮窗
  -- ============================================================
  preedit = preedit {
    horizontal_padding = 8,
    top_end_radius = 5,
    alpha = 0.8,
    foreground = { font_size = 16 },
  },

  window = window {
    insets = { vertical = 2, horizontal = 2 },
    item_padding = { horizontal = 4 },
    min_width = 0,
    corner_radius = 6,
    border = 0,
    shadow = 8,
    alpha = 0.95,
    foreground = {
      label_font_size = 16,
      text_font_size = 18,
      comment_font_size = 12,
    },
  },

  -- ============================================================
  -- 候选工具栏（翻页 / 删除 / 上屏）
  -- ============================================================
  candidates_tool = {
    nav_width = 41,
    background = "back_color",
    buttons = {
      { action = "Up",              foreground = { style = "ic@arrow-up-bold",      font_size = 12 } },
      { action = "Down",            foreground = { style = "ic@arrow-down-bold",    font_size = 12 } },
      { action = "BackSpace",       foreground = { style = "ic@backspace-outline",  font_size = 12 } },
      { action = "CommitScriptText", foreground = { style = "ic@keyboard-return",   font_size = 12 } },
    },
    popup_width = 60,
    popup_text_size = 18,
    popup_text_color = "text_color",
    popup_background_color = "back_color",
    popup = {
      { action = "CandPin",    label = "置顶" },
      { action = "CandDelete", label = "删除" },
    },
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

  -- ============================================================
  -- 顶部工具栏
  -- ============================================================
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
      { action = "BambooSymbol",  foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@symbol" } },
      { action = "WindowClipboard", foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@clipboard-list-outline" } },
      { action = "KeyboardEditor", foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@cursor-move" } },
      { action = "Redo",           foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@rotate-right" } },
      { action = "Undo",           foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@rotate-left" } },
      { action = "FloatingSwitch", foreground = { font_size = 18, padding = 2, normal = "text_color", option_styles = { "ic@dock-window", "ic@keyboard-outline" } } },
      { action = "OneHandSwitch",  foreground = { font_size = 18, padding = 2, normal = "text_color", option_styles = { "ic@keyboard-variant", "ic@keyboard-outline" } } },
    },
  },

  -- ============================================================
  -- 按键定义：直接复用皮肤仓库里的 preset_keys
  -- ============================================================
  preset_keys = safe_require("dev.preset_keys"),

  -- ============================================================
  -- 键盘布局：自己的两份在前，其余键盘与其它主题共用只读文件
  -- ============================================================
  preset_keyboards = {
    default          = safe_require("dev.layouts.45keys"),          -- 三排 45 键
    qijian           = safe_require("dev.layouts.45keys_4pai"),     -- 四排 45 键
    wanxiang_t9 = safe_require("dev.layouts.17keys"),       -- Dev T9 九宫
    wanxiang_18key = safe_require("dev.layouts.37keys_zrm"),    -- Dev 18 键
    wanxiang_14key = safe_require("dev.layouts.14keys"),
    wanxiang_pro = safe_require("dev.layouts.45keys"),   -- 万象拼音PRO 主键盘（普通 45 键，无韵母提示）
    xiaohe = safe_require("dev.layouts.45keys_flypy"),   -- 小鹤辅助码提示版（用切换键弹窗里的「鹤」进入）
    wanxiang_english = safe_require("dev.layouts.english"),
    number           = safe_require("dev.layouts.number"),
    editor           = safe_require("dev.layouts.editor"),
    calculator       = safe_require("dev.layouts.calculator"),
    settings         = safe_require("dev.layouts.settings"),
  },

  -- ============================================================
  -- 配色：由 lib\dev\palettes.lua 展开的 10 套
  -- ============================================================
  preset_color_schemes = schemes,

  liquid_keyboard = safe_require("dev.liquid_keyboard"),
}

-- 对比度守卫：把「文字/图标 vs 背景」太接近的组合自动拉开（图片与半透明背景跳过）
local guard = safe_require("dev.colors.contrast_guard")
for _, s in ipairs(theme_tbl.preset_color_schemes or {}) do guard.apply(s) end

return theme_tbl
