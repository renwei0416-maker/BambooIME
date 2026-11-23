-- 选项面板配置
---@type { [string]: PresetKey }
local pk = {
  -- 功能键
  Escape             = { label = "ic@keyboard-esc", send = "Escape" },
  -- ── 编辑区快捷操作（退格键手势 / 字母 D 长按） ─────────────
  -- 清空编辑区：交给苍竹的 BambooClear（走 InputConnection 一次性删除光标前后全部内容，
  -- 比连发「全选 + 退格」稳，不会因为全选异步还没生效就被退格抢先）。
  ClearAll   = { label = "清空", popup_label = "清空", text = "BambooClear" }, -- 苍竹：光标前后全删
  -- 删到行首 / 删一个词：同样交给苍竹（BambooDeleteLine / BambooDeleteWord），
  -- 由宿主读编辑器文本后精确删除；宿主不支持时内部自动退回按键序列。
  DeleteLine = { text = "BambooDeleteLine" },                                                -- 苍竹：删到行首
  WordDelete = { text = "BambooDeleteWord" },                                                 -- 苍竹：删除光标前的一个词

  Home               = { label = "ic@arrow-collapse-left", send = "Home" },
  End                = { label = "ic@arrow-collapse-right", send = "End" },
  Page_Up            = { label = "ic@page-first", send = "Page_Up" },
  Page_Down          = { label = "ic@page-last", send = "Page_Down" },
  Delete             = { label = "ic@delete-forever", send = "Delete" },
  BackSpace          = { label = "ic@backspace-outline", repeatable = true, send = "BackSpace" },
  Tab                = { label = "ic@keyboard-tab", repeatable = true, send = "Tab" },
  Enter              = { label = "ic@keyboard-return", send = "Return" },
  -- 苍竹 AI：回车键上滑/右滑。这里用「动作名」（text 通道）直连宿主，不再依赖组合键；
  -- 组合键（Ctrl+; / Ctrl+Alt+;）仍然保留在设置里，是给外接键盘和自定义 send: 绑定用的。
  AI                 = { label = "AI", popup_label = "AI", text = "BambooAiSend" },
  ClipboardAI        = { label = "剪贴板", popup_label = "剪贴板AI", text = "BambooAiClip" },
  Shift              = { label = "ic@apple-keyboard-shift", functional = true, send = "Shift_L", shift_lock = "long" },
  CapsLock           = { label = "ic@apple-keyboard-caps", functional = true, send = "Shift_L", shift_lock = "click" },
  Ctrl               = { label = "ic@apple-keyboard-command", send = "Control_L" },
  Alt                = { label = "ic@apple-keyboard-option", send = "Alt_L" },
  Space              = { label = "ic@keyboard-space", slide_cursor = true, send = "space" },
  Down               = { label = "ic@arrow-down-bold", repeatable = true, send = "Down" },
  Left               = { label = "ic@arrow-left-bold", repeatable = true, send = "Left" },
  Right              = { label = "ic@arrow-right-bold", repeatable = true, send = "Right" },
  Up                 = { label = "ic@arrow-up-bold", repeatable = true, send = "Up" },

  -- App 功能
  VoiceAssist        = { label = "ic@microphone", send = "VOICE_ASSIST" },                                 -- 语音识别
  -- 按住说话（苍竹 token）：行为与上面 VoiceAssist 完全一致（波纹/提示/松手结束都同一份代码），
  -- 只是识别出文字后，若当前在某个 AI 模式里就顺带把这段文字交给该模式处理；未进模式则只上屏。
  -- 必须写 text 而不是 send：send 会被当键码解析，认不出就整条吞掉（表现为长按没反应）。
  VoiceAi            = { label = "ic@microphone", text = "BambooVoiceAi" },                              -- 按住说话（语音 + AI）
  HideKeyboard       = { label = "ic@keyboard-close", send = "BACK" },                                     -- 收起键盘
  ThemeReload        = { label = "ic@cookie-refresh-outline", command = "set_theme", option = "$reload" }, -- 刷新主题
  ZiTools            = { label = "ic@magnify", command = "run", option = "https://zi.tools/zi/%1$s" },     -- 字统网查字
BaiduSearch        = { label = "百", popup_label = "百", command = "run", option = "https://www.baidu.com/s?wd=%1$s" }, -- 百度查上屏的字
  Deploy             = { label = "ic@archive-refresh-outline", command = "apply", option = "DEPLOY" },     -- 重新部署方案
  Sync               = { label = "ic@sync", command = "apply", option = "SYNC_USER_DATA" },                -- 同步用户数据
  Restart            = { label = "ic@restart", command = "apply", option = "RESTART_RIME" },               -- 重启 Rime
  Settings           = { label = "ic@cog-outline", send = "SETTINGS" },                                    -- 设置
  Info               = { label = "ic@information-outline", text = "BambooAbout" },                                -- 关于
  SchemeList         = { label = "ic@list-box-outline", send = "MENU" },                                   -- 方案列表
  ThemeList          = { label = "ic@palette-swatch-outline", send = "SETTINGS", option = "theme" },       -- 主题列表
  ColorList          = { label = "ic@palette-outline", send = "PROG_RED" },                                -- 配色列表
  IMESwitch          = { label = "ic@web", send = "LANGUAGE_SWITCH" },                                     -- 输入法切换

  -- 编辑键
  SelectAll          = { label = "ic@select-all", send = "Control+a" },    -- 全选
  Cut                = { label = "ic@content-cut", send = "Control+x" },   -- 剪切
  Copy               = { label = "ic@content-copy", send = "Control+c" },  -- 复制
  Paste              = { label = "ic@content-paste", send = "Control+v" }, -- 粘贴
  Undo               = { label = "ic@rotate-left", send = "Control+z" },   -- 撤销
  Redo               = { label = "ic@rotate-right", send = "Control+y" },  -- 重做

  -- 状态切换
  FloatingSwitch     = { toggle = "_floating_keyboard", send = "SWITCH_CHARSET", states = { "停靠模式", "悬浮模式" } }, -- 悬浮键盘切换
  ModeSwitch         = { toggle = "ascii_mode", send = "SWITCH_CHARSET", states = { "\u{F830}", "\u{F831}" } }, -- ASCII 模式切换
  OneHandSwitch      = { toggle = "_one_hand_mode", send = "SWITCH_CHARSET", states = { "标准键盘", "单手键盘" } }, -- 单手键盘切换
  VoiceSwitch        = { toggle = "_voice_assist", send = "SWITCH_CHARSET", states = { "开始识别", "停止识别" } }, -- 工具栏语音识别
  HintSwitch         = { toggle = "_hide_key_hint", send = "SWITCH_CHARSET", states = { "显示助记", "隐藏助记" } }, -- j键盘助记
  HideSymbols        = { toggle = "_hide_key_symbol", send = "SWITCH_CHARSET", states = { "显示符号", "隐藏符号" } }, -- 关掉按键右上角符号/顶部小图标（/ 键下滑）

  -- 键盘切换
  KeyboardDefault    = { label = "ic@keyboard", send = "Eisu_toggle", select = ".default" },             -- 默认键盘
  KeyboardPrior      = { label = "ic@page-previous", send = "Eisu_toggle", select = ".prior" },          -- 上一个键盘
  KeyboardNext       = { label = "ic@page-next", send = "Eisu_toggle", select = ".next" },               -- 下一个键盘
  KeyboardCalculator = { label = "ic@calculator-variant", send = "Eisu_toggle", select = "calculator" }, -- 计算器键盘
  KeyboardEditor     = { label = "ic@square-edit-outline", send = "Eisu_toggle", select = "editor" },    -- 编辑键盘
  KeyboardNumber     = { label = "ic@numeric", send = "Eisu_toggle", select = "number" },                -- 数字键盘
  KeyboardSettings   = { label = "ic@cogs", send = "Eisu_toggle", select = "settings" },                 -- 设置键盘
  Keyboard45key4pai  = { label = "七", popup_label = "七", send = "Eisu_toggle", select = "qijian" },            -- 万象四排 45 键
  Keyboard45key      = { label = "中", popup_label = "", send = "Eisu_toggle", select = "default" },                         -- 普通 45 键主键盘（弹窗显示「中」）
KeyboardXiaohe     = { label = "鹤", popup_label = "'ic@bird'", send = "Eisu_toggle", select = "xiaohe" },                   -- 小鹤辅助码提示版
  Keyboard18key      = { label = "18", popup_label = "18", send = "Eisu_toggle", select = "wanxiang_18key" },          -- 万象 18 键
  Keyboard14key      = { label = "14", popup_label = "14", send = "Eisu_toggle", select = "wanxiang_14key" },          -- 万象 14 键
  Keyboard9key       = { label = "T9", popup_label = "T9", send = "Eisu_toggle", select = "wanxiang_t9" },             -- 万象 9 键
  KeyboardEnglish    = { label = "英", popup_label = "", send = "Eisu_toggle", select = "wanxiang_english" },        -- 万象英文
  KeyboardMain       = { label = "45", popup_label = "45", send = "Eisu_toggle", select = "default" },                       -- 回到主键盘（preset_keyboards.default，Gboard 风格 45 键）
  -- 手写板（BambooIME 1.1.0 起）：label 是弹窗里显示的名字；text 必须留成 "Handwriting"，
  -- App 侧就是靠这个字符串认出"要打开手写板"，而不是当成文字上屏。
  Handwriting        = { label = "手写", popup_label = "'ic@draw'", text = "Handwriting" },

-- 拍照识字（BambooIME 1.2.0 起）：和手写一样只改显示名，text 必须是 "BambooOcr"
BambooOcr          = { label = "拍", popup_label = "'ic@camera'", text = "BambooOcr" },

-- AI 模式面板（BambooIME 2.9.0 起）：点一下打开面板，里面是所有 AI 模式 + 文曲星。
-- 和上面两个一样：**必须写 text 不能写 send**，否则 token 会被 KeyAction 吞掉、点下去没反应。
-- 注意：新版 App 才有这个 token；旧版 App 装新主题会把这串字当普通文字上屏。
AiPanel            = { label = "AI", popup_label = "'ic@robot'", text = "BambooAiPanel" },

  -- 数字和符号面板（BambooIME 2.0.0 起）：从键盘直接唤起，进去停在「符号」页。
  -- label 用 ic@ 图标（不带引号 = 整串当图标），text 必须是 "BambooSymbol"
  BambooSymbol       = { label = "ic@symbol", popup_label = "ic@symbol", text = "BambooSymbol" },

  -- 方案切换（需配合方案使用）

  -- 窗口切换
  WindowMenu         = { label = "ic@tune-variant", command = "menu_keyboard" },              -- 方案开关页面
  WindowClipboard    = { label = "ic@clipboard-list-outline", command = "clipboard_window" }, -- 剪贴板

  -- 流体键盘
  LiquidExit         = { label = "ic@keyboard", command = "liquid_keyboard", option = "-1" },           -- 退出流体键盘
  LiquidTabs         = { label = "ic@format-list-text", command = "liquid_keyboard", option = "tabs" }, -- 流体键盘导航页面
  LiquidHistory      = { label = "ic@history", command = "liquid_keyboard", option = "history" },       -- 符号历史记录
  LiquidAscii        = { label = "ic@symbol", command = "liquid_keyboard", option = "ascii" },          -- ascii 符号
  LiquidEmoji        = { label = "ic@cat", command = "liquid_keyboard", option = "emoji" },             -- emoji 符号

  -- Rime 方案相关
  CandDelete         = { label = "ic@delete-forever", send = "Control+Delete" },                     -- 删除选中候选
  CandLeft           = { label = "ic@pan-left", send = "Control+j" },                                -- 提前选中候选位置
  CandRight          = { label = "ic@pan-right", send = "Control+k" },                               -- 推后选中候选位置
  CandReset          = { label = "ic@lock-reset", send = "Control+l" },                              -- 重置选中候选位置
  CandTop            = { label = "ic@pin", send = "Control+p" },                                     -- 置顶选中候选
  CandPin            = { label = "ic@pin", send = "Control+t" },                                     -- 置顶/取消置顶选中候选
  CandUp             = { label = "ic@pan-up", send = "Control+equal" },                              -- 上移选中的 pin 候选
  CandDown           = { label = "ic@pan-down", send = "Control+plus" },                             -- 下移选中的 pin 候选
  ReverseLookup      = { label = "ic@magnify", text = "`" },                                         -- 反查
  AddDict            = { label = "ic@pen-plus", text = "``" },                                       -- 造词
  Calculator         = { label = "ic@calculator-variant", actions = { "KeyboardCalculator", "V" } }, -- 计算器
  T9Clean            = { label = "ic@close-octagon-outline", command = "sidebar_clear" },                 -- T9 布局清空

  -- 计算器函数
  -- ==== ASCII 处理 ====
  Slash              = { label = "/", commit = "/" }, -- 除号
  Period             = { label = ".", commit = "." }, -- 句号
  ParenCommit        = { commit = "()" }, -- 括号
  Paren              = { label = "()", actions = { "ParenCommit", "Left" } }, -- 括号
  BracketCommit      = { commit = "[]" }, -- 中括号
  Bracket            = { label = "[]", actions = { "BracketCommit", "Left" } }, -- 中括号
  Formula            = { label = "算式", actions = { "End", "space" } }, -- 输出算式

  -- ==== 基础数学 ====
  Sin                = { popup_label = "sin  ", text = "sin(){Left}" },   -- 正弦
  Cos                = { popup_label = "cos  ", text = "cos(){Left}" },   -- 余弦
  Tan                = { popup_label = "tan  ", text = "tan(){Left}" },   -- 正切
  Asin               = { popup_label = "asin ", text = "asin(){Left}" },  -- 反正弦
  Acos               = { popup_label = "acos ", text = "acos(){Left}" },  -- 反余弦
  Atan               = { popup_label = "atan ", text = "atan(){Left}" },  -- 反正切
  Atan2              = { popup_label = "atan2", text = "atan2(){Left}" }, -- 点(x,y)角度
  Sinh               = { popup_label = "sinh ", text = "sinh(){Left}" },  -- 双曲正弦
  Cosh               = { popup_label = "cosh ", text = "cosh(){Left}" },  -- 双曲余弦
  Tanh               = { popup_label = "tanh ", text = "tanh(){Left}" },  -- 双曲正切
  Deg                = { popup_label = "deg  ", text = "deg(){Left}" },   -- 弧度→角度
  Rad                = { popup_label = "rad  ", text = "rad(){Left}" },   -- 角度→弧度
  Exp                = { popup_label = "exp  ", text = "exp(){Left}" },   -- e^x
  Ldexp              = { popup_label = "ldexp", text = "ldexp(){Left}" }, -- x·2^y
  Log                = { popup_label = "log  ", text = "log(){Left}" },   -- 对数(底x)
  Loge               = { popup_label = "loge ", text = "loge(){Left}" },  -- 自然对数
  Logt               = { popup_label = "logt ", text = "logt(){Left}" },  -- 常用对数
  Sqrt               = { popup_label = "sqrt ", text = "sqrt(){Left}" },  -- 平方根
  Csqrt              = { popup_label = "csqrt", text = "csqrt(){Left}" }, -- 复数平方根
  Nroot              = { popup_label = "nroot", text = "nroot(){Left}" }, -- 开n次方
  Ceil               = { popup_label = "ceil ", text = "ceil(){Left}" },  -- 向上取整
  Floor              = { popup_label = "floor", text = "floor(){Left}" }, -- 向下取整

  -- ==== 幂方求和 ====
  Sqsum              = { popup_label = "sqs  ", text = "sqsum(){Left}" },  -- 自然数平方和
  Cbsum              = { popup_label = "cbs  ", text = "cbsum(){Left}" },  -- 自然数立方和
  Qpsum              = { popup_label = "qps  ", text = "qpsum(){Left}" },  -- 自然数四次方和
  Osqsum             = { popup_label = "osqs ", text = "osqsum(){Left}" }, -- 奇数平方和
  Esqsum             = { popup_label = "esqs ", text = "esqsum(){Left}" }, -- 偶数平方和
  Ocbsum             = { popup_label = "ocbs ", text = "ocbsum(){Left}" }, -- 奇数立方和
  Ecbsum             = { popup_label = "ecbs ", text = "ecbsum(){Left}" }, -- 偶数立方和
  Oqpsum             = { popup_label = "oqps ", text = "oqpsum(){Left}" }, -- 奇数四次方和
  Eqpsum             = { popup_label = "eqps ", text = "eqpsum(){Left}" }, -- 偶数四次方和

  -- ==== 组合与数论 ====
  Fact               = { popup_label = "fact ", text = "fact(){Left}" }, -- 阶乘
  Perm               = { popup_label = "perm ", text = "perm(){Left}" }, -- 排列数
  Comb               = { popup_label = "comb ", text = "comb(){Left}" }, -- 组合数
  Gcd                = { popup_label = "gcd  ", text = "gcd(){Left}" },  -- 最大公因数
  Lcm                = { popup_label = "lcm  ", text = "lcm(){Left}" },  -- 最小公倍数
  Mod                = { popup_label = "mod  ", text = "mod(){Left}" },  -- 取余

  -- ==== 数列 ====
  Arithsum           = { popup_label = "aris ", text = "arith_sum(){Left}" }, -- 等差数列前n项和
  Geosum             = { popup_label = "geos ", text = "geo_sum(){Left}" },   -- 等比数列前n项和
  Seq                = { popup_label = "seq  ", text = "seq(){Left}" },       -- 数列通项公式

  -- ==== 统计 ====
  Avg                = { popup_label = "avg  ", text = "avg(){Left}" }, -- 平均数
  Var                = { popup_label = "var  ", text = "var(){Left}" }, -- 方差

  -- ==== 方程求解 ====
  Eq1                = { popup_label = "eq1  ", text = "eq1(){Left}" },  -- 一元一次方程
  Eq2                = { popup_label = "eq2  ", text = "eq2(){Left}" },  -- 二元一次方程组
  Eq2D               = { popup_label = "eq2d ", text = "eq2d(){Left}" }, -- 一元二次方程
  Eq3                = { popup_label = "eq3  ", text = "eq3(){Left}" },  -- 一元三次方程
  Eq4                = { popup_label = "eq4  ", text = "eq4(){Left}" },  -- 一元四次方程

  -- ==== 几何 — 直线 ====
  LinePS             = { popup_label = "lnps ", text = "line_ps(){Left}" }, -- 点斜式求直线
  Line2P             = { popup_label = "ln2p ", text = "line_2p(){Left}" }, -- 两点式求直线
  Dist               = { popup_label = "dist ", text = "dist(){Left}" },    -- 两点间距离
  Pbisec             = { popup_label = "pbis ", text = "pbisec(){Left}" },  -- 垂直平分线
  Rotate             = { popup_label = "rotat", text = "rotate(){Left}" },  -- 点绕点旋转
  Lines              = { popup_label = "lines", text = "lines(){Left}" },   -- 两直线位置关系
  Lsym               = { popup_label = "lsym ", text = "lsym(){Left}" },    -- 直线对称

  -- ==== 几何 — 点与直线 ====
  Pld                = { popup_label = "pld  ", text = "pld(){Left}" },  -- 点到直线距离/对称
  Plsl               = { popup_label = "plsl ", text = "plsl(){Left}" }, -- 线关于点对称

  -- ==== 几何 — 二次函数 ====
  QuadV              = { popup_label = "qdv  ", text = "quad_v(){Left}" },  -- 顶点式求二次函数
  Quad3P             = { popup_label = "qd3p ", text = "quad_3p(){Left}" }, -- 三点求二次函数

  -- ==== 几何 — 圆 ====
  CircleR            = { popup_label = "ccr  ", text = "circle_r(){Left}" },   -- 圆心半径求圆
  CircleC2P          = { popup_label = "cc2p ", text = "circle_c2p(){Left}" }, -- 圆心两点求圆
  Circle3P           = { popup_label = "c3p  ", text = "circle_3p(){Left}" },  -- 三点求圆
  Circles            = { popup_label = "circ  ", text = "circles(){Left}" },   -- 两圆关系(标准)
  CirclesG           = { popup_label = "cclg ", text = "circles_g(){Left}" },  -- 两圆关系(一般)

  -- ==== 几何 — 三角形 ====
  TriSSS             = { popup_label = "tsss ", text = "tri_sss(){Left}" },    -- 三边求面积
  TriArea            = { popup_label = "trar ", text = "tri_area(){Left}" },   -- 顶点求面积
  TriCenter          = { popup_label = "tcen ", text = "tri_center(){Left}" }, -- 四心坐标
  TriCR              = { popup_label = "tcr  ", text = "tri_cr(){Left}" },     -- 内外半径(边长)
  TriCRP             = { popup_label = "tcrp ", text = "tri_crp(){Left}" },    -- 内外半径(顶点)

  -- ==== 几何 — 正多边形 ====
  Poly               = { popup_label = "poly ", text = "poly(){Left}" }, -- 正n边形面积

  -- ==== 行列式 ====
  Det                = { popup_label = "det  ", text = "det(){Left}" }, -- 行列式

  -- ==== 随机数 ====
  Rand               = { popup_label = "rand ", text = "rand(){Left}" },  -- 随机数
  RandN              = { popup_label = "randn", text = "randn(){Left}" }, -- 批量随机数

  -- ==== 数论 ====
  Pytha              = { popup_label = "pytha", text = "pytha(){Left}" },  -- 勾股数
  Pfact              = { popup_label = "pfact", text = "pfact(){Left}" },  -- 质因数分解
  Primes             = { popup_label = "prims", text = "primes(){Left}" }, -- 找质数

  -- ==== 工具 ====
  Base               = { popup_label = "base ", text = "base(){Left}" }, -- 进制转换
  Unit               = { popup_label = "unit ", text = "unit(){Left}" }  -- 单位换算
}

return pk
