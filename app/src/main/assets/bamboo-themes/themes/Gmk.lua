--[[
Gmk.lua    GMK 键帽风主题（LyraIME 灵韵输入法）
==================================================================

三套 GMK 经典套色，在「配色选择」里切换：
  GMK Olivia（奶油白 + 玫瑰粉回车）
  GMK 8008（深灰蓝 + 青色回车）
  GMK Bento（米白 + 橙红回车）

目录（自成一套，不引用其它主题文件）：
  Gmk.lua                              主题入口（本文件）
  lib\gmk\palettes.lua                 3 套配色 + 图片路径拼接
  lib\gmk\key_style.lua                按键样式模板（由 lib\key_style_4pai.lua 派生）
  lib\gmk\layouts\45keys.lua           三排 45 键布局
  lib\gmk\layouts\45keys_4pai.lua      四排 45 键布局
  backgrounds\gmk\<套色>\*.png         键帽图（letter/func/space/enter/pressed 各一张）

键帽图说明：普通 PNG（不是 .9.png），宽高比按真实按键做（200×100、空格 600×100），
靠"贴图直接拉伸"也能保持圆角形状不乱；键帽的立体感来自图片本身的顶部高光与底部内阴影。
背景图不吃 key_border 和 round_corner，所以这两项在这里没有视觉效果，保持 0/10 只是为了
候选栏、弹窗、侧栏等纯色部件统一。
]]

-- 字体表：新版灵韵只认 fonts.font_list（与其它主题共用 themes\fonts\）
local font_combo = {
  "HarmonyOS_Sans_SC_Regular.ttf",
  "LXGW975YuanSC.ttf",
  "OpenMoji.ttf",
  "CarlsonFont.ttf",
  "SourceHanSans.otf",
  "PlangothicP1.otf",
  "PlangothicP2.otf", "DejaVuSans.ttf",
}

local P = safe_require("gmk.palettes")

local schemes = {}
for i, item in ipairs(P.list) do
  schemes[i] = scheme(item.id, item.colors)
end

local theme_tbl = theme {
  name = "Gmk",
  version = "1.0",
  author = "LyraIME-SKIN",

  style = style {
    -- 背景图目录：图片路径 "olivia/letter.png" 解析成 backgrounds\gmk\olivia\letter.png
    background_folder = "gmk",

    keyboard_height = 268,
    keyboard_height_land = 156,
    horizontal_gap = 5,                 -- 键帽之间留出缝，才看得出是一颗颗键帽
    vertical_gap = 7,
    round_corner = 10,                  -- 只影响纯色部件（候选栏/弹窗/侧栏）
    key_border = 0,                     -- 键帽的边由图片自带
    key_shadow_radius = 0.6,            -- 轻投影，键帽浮起来一点
    key_shadow_direction = { "down" },
    keyboard_padding = 4,
    keyboard_padding_top = 5,
    keyboard_padding_bottom = 10,
    keyboard_padding_land = 40,
    keyboard_padding_land_bottom = 0,
    content_padding = 0,

    candidate_view_height = 34,
    candidate_padding = 4,
    candidate_spacing = 1.0,
    candidate_text_vertical_bias = 1.0,
    candidate_border = 0,
    candidate_border_round = 0,
    candidate_corner_radius = 0,
    candidate_label = false,
    comment_height = 0,
    comment_vertical_bias = 0.0,
    comment_position = "OVERLAY",

    popup_bottom_margin = 68,
    popup_width = 38,
    popup_height = 50,
    popup_key_height = 52,

    sidebar_round_corner = -1,

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

    fonts = {
      font_list = font_combo,
      candidate_size = 18,
      candidate_label_size = 14,
      comment_size = 9,
      key_size = 23,
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

  preedit = preedit {
    horizontal_padding = 8,
    top_end_radius = 5,
    alpha = 0.85,
    foreground = { font_size = 16 },
  },

  window = window {
    insets = { vertical = 2, horizontal = 2 },
    item_padding = { horizontal = 4 },
    min_width = 0,
    corner_radius = 8,
    border = 0,
    shadow = 10,
    alpha = 0.95,
    foreground = {
      label_font_size = 16,
      text_font_size = 18,
      comment_font_size = 12,
    },
  },

  candidates_tool = {
    nav_width = 41,
    background = "back_color",
    buttons = {
      { action = "Up",               foreground = { style = "ic@arrow-up-bold",     font_size = 12 } },
      { action = "Down",             foreground = { style = "ic@arrow-down-bold",   font_size = 12 } },
      { action = "BackSpace",        foreground = { style = "ic@backspace-outline", font_size = 12 } },
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
      { action = "HideKeyboard",    foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@keyboard-close" } },
      { action = "VoiceSwitch",     foreground = { font_size = 18, padding = 2, normal = "text_color", option_styles = { "ic@microphone", "ic@stop-circle" } } },
      { action = "BambooSymbol",   foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@symbol" } },
      { action = "WindowClipboard", foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@clipboard-list-outline" } },
      { action = "KeyboardEditor",  foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@cursor-move" } },
      { action = "Redo",            foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@rotate-right" } },
      { action = "Undo",            foreground = { font_size = 18, padding = 2, normal = "text_color", style = "ic@rotate-left" } },
      { action = "FloatingSwitch",  foreground = { font_size = 18, padding = 2, normal = "text_color", option_styles = { "ic@dock-window", "ic@keyboard-outline" } } },
      { action = "OneHandSwitch",   foreground = { font_size = 18, padding = 2, normal = "text_color", option_styles = { "ic@keyboard-variant", "ic@keyboard-outline" } } },
    },
  },

  preset_keys = safe_require("gmk.preset_keys"),

  preset_keyboards = {
    default          = safe_require("gmk.layouts.45keys"),
    qijian           = safe_require("gmk.layouts.45keys_4pai"),
    wanxiang_t9 = safe_require("gmk.layouts.17keys"),       -- Gmk T9 九宫
    wanxiang_18key = safe_require("gmk.layouts.37keys_zrm"),    -- Gmk 18 键
    wanxiang_14key = safe_require("gmk.layouts.14keys"),
    wanxiang_pro = safe_require("gmk.layouts.45keys"),   -- 万象拼音PRO 主键盘（普通 45 键，无韵母提示）
    xiaohe = safe_require("gmk.layouts.45keys_flypy"),   -- 小鹤辅助码提示版（用切换键弹窗里的「鹤」进入）
    wanxiang_english = safe_require("gmk.layouts.english"),
    number           = safe_require("gmk.layouts.number"),
    editor           = safe_require("gmk.layouts.editor"),
    calculator       = safe_require("gmk.layouts.calculator"),
    settings         = safe_require("gmk.layouts.settings"),
  },

  preset_color_schemes = schemes,

  liquid_keyboard = safe_require("gmk.liquid_keyboard"),
}

-- 对比度守卫：把「文字/图标 vs 背景」太接近的组合自动拉开（图片与半透明背景跳过）
local guard = safe_require("gmk.colors.contrast_guard")
for _, s in ipairs(theme_tbl.preset_color_schemes or {}) do guard.apply(s) end

return theme_tbl
