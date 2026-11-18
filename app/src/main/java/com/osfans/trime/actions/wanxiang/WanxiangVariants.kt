/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.wanxiang

/**
 * 万象拼音可下载的方案包清单。
 *
 * 名字与体积全部取自官方 release（v18.1.13）：
 *  * Base —— 全拼 + 任意双拼 + 官方九键，**不含**辅助码词库（这就是内置的那一份）；
 *  * 9 个 `*-fuzhu` —— 完整配置包，词库里带辅助码列，装哪个就等于选哪种辅助码。
 *
 * 换变体是「整包覆盖」：官方各变体文件清单一致，只有词库那 4 个文件不同。
 */
object WanxiangVariants {

    const val TAG = "v18.1.13"

    const val GRAM_FILE = "wanxiang-lts-zh-hans.gram"
    const val GRAM_BYTES = 408_552_492L

    /** 国内镜像根地址，供「自定义下载地址」示例使用。 */
    const val MIRROR_HINT = "https://cnb.cool/amzxyz/rime-wanxiang"

    data class Variant(
        val id: String,
        val title: String,
        val file: String,
        val bytes: Long,
        val note: String,
        val hasAux: Boolean,
    )

    val list: List<Variant> =
        listOf(
            Variant("base", "Base（全拼/双拼 + 九键）", "rime-wanxiang-base.zip", 26_197_573L, "内置，不含辅助码", false),
            Variant("flypy", "小鹤辅助", "rime-wanxiang-flypy-fuzhu.zip", 30_429_402L, "小鹤双拼 + 小鹤辅助码", true),
            Variant("zrm", "自然码辅助", "rime-wanxiang-zrm-fuzhu.zip", 31_573_956L, "自然码 + 自然码辅助码", true),
            Variant("wx", "万象辅助", "rime-wanxiang-wx-fuzhu.zip", 30_443_762L, "万象自带辅助码", true),
            Variant("shouyou", "首右辅助", "rime-wanxiang-shouyou-fuzhu.zip", 29_795_873L, "首右辅助码", true),
            Variant("shyplus", "首右+辅助", "rime-wanxiang-shyplus-fuzhu.zip", 30_019_570L, "首右增强辅助码", true),
            Variant("tiger", "虎码首末辅助", "rime-wanxiang-tiger-fuzhu.zip", 30_497_560L, "虎码首末辅助码", true),
            Variant("moqi", "墨奇辅助", "rime-wanxiang-moqi-fuzhu.zip", 30_542_102L, "墨奇辅助码", true),
            Variant("wubi", "五笔前2辅助", "rime-wanxiang-wubi-fuzhu.zip", 30_293_508L, "五笔前两码辅助", true),
            Variant("hanxin", "汉心辅助", "rime-wanxiang-hanxin-fuzhu.zip", 30_069_765L, "汉心辅助码", true),
        )

    fun byId(id: String): Variant? = list.find { it.id == id }

    fun urlOf(v: Variant, baseUrl: String = WanxiangPrefs.downloadBase): String {
        val base = baseUrl.ifBlank { "https://github.com/amzxyz/rime-wanxiang/releases/download/$TAG/" }
        return if (base.endsWith("/")) base + v.file else "$base/${v.file}"
    }

    fun sizeText(bytes: Long): String = String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)

    fun installedNote(id: String): String = byId(id)?.title ?: id
}
