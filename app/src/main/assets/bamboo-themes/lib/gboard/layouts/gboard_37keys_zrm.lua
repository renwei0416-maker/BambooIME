--[[
layouts.45keys_zrm.lua 万象自然码18键布局补丁扩展脚本
功能说明：
1、merge(safe_require("layouts.gboard_37keys"), ...)：继承上面那份【37keys_zrm（18键基础布局）】全部内容，只做局部覆盖修改，不重写整个键盘。
2、只修改第2、3、4行按键的 hint（按键底部助记提示文本），也就是自然码韵母提示；
3、不改动：单击click、上滑swipe_up、长按popup、按键样式S.base/S.mantle、按键宽度高度，完全沿用原37keys布局；
4、数组索引 [2] [3] [4]：rows数组下标，从1开始计数：
    rows[1] = 数字行；
    rows[2] = 第二行q‑p；
    rows[3] = 第三行a‑l；
    rows[4] = 第四行z‑m；
5、keys[1] [2]：行内按键数组下标，该行第N个按键；
6、hint：按键底部小字提示；align="justify" = 两端对齐，多个韵母符号左右分散排布；
7、本文件只追加韵母提示，键盘所有点击、手势逻辑全部来自 layouts.37keys。
]]

-- merge(基础布局对象, 要覆盖修改的配置)
-- safe_require("layouts.gboard_37keys") 读取18键基础布局，在此基础上打补丁，只修改hint助记提示
local keyboard = keyboard(merge(safe_require("gboard.layouts.gboard_37keys"), keyboard {
  name = "Gboard 18 键·自然码", -- 布局对外显示名称（替换原来基础布局的名字）
  reset_ascii_mode = true,            -- 每次进入这个键盘都强制回中文（不回退到上一次的英文状态）

  rows = {
    -- 修改键盘【第2行】(q‑p那一行，rows[2])，只修改hint助记提示，按键其他属性全部继承原布局
    [2] = row {
      keys = {
        [1] = key { hint = { { text = "iu" } } },        -- Q
        [2] = key { hint = { { text = "ei e" } } },      -- WE
        [3] = key { hint = { { text = "uan ue üe" } } }, -- RT
        [4] = key { hint = { { text = "un" } } },        -- Y
        [5] = key { hint = { { text = "u" } } },         -- U
        [6] = key { hint = { { text = "i o uo" } } },    -- IO
        [7] = key { hint = { { text = "ie" } } }         -- P
      }
    },

    -- 修改键盘【第3行】(a‑l那一行，rows[3])
    [3] = row {
      keys = {
        [2] = key { hint = { { text = "a" } } },          -- A
        [3] = key { hint = { { text = "(i)ong ai" } } },  -- SD
        [4] = key { hint = { { text = "en eng" } } },     -- FG
        [5] = key { hint = { { text = "ang" } } },        -- H
        [6] = key { hint = { { text = "an uai ing" } } }, -- JK
        [7] = key { hint = { { text = "iang uang" } } }   -- L
      }
    },

    -- 修改键盘【第4行】(z‑m那一行，rows[4])
    [4] = row {
      keys = {
        [2] = key { hint = { { text = "ou" } } },       -- Z
        [3] = key { hint = { { text = "ia ua ao" } } }, -- XC
        [4] = key { hint = { { text = "ui ü" } } },     -- V
        [5] = key { hint = { { text = "in iao" } } },   -- BN
        [6] = key { hint = { { text = "ian" } } }       -- M
      }
    }
  }
}))

return keyboard -- 输出打补丁完成后的完整布局对象，供 NekoCatppuccin.lua 的 preset_keyboards 加载使用