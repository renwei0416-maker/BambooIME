/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

/**
 * 「提取内容」的扫描内核：纯函数、不碰 Android，方便单测。
 *
 * 与 [com.osfans.trime.util.extractAll] 的区别：
 *  - 结果按**在原文里的出现位置**排序，不按规则顺序；
 *  - 互相包含的命中只留最长的一段；
 *  - 被空格 / 逗号打断的地址会拼回一整段；
 *  - 地址前面粘着的「我的地址是」「我住在」这类引导语会被剥掉。
 */
internal object ClipboardExtractScan {
    /** 判断一段命中是不是地址用的字表。 */
    val ADDRESS_HINTS =
        listOf(
            "省", "市", "区", "县", "旗", "镇", "乡", "村",
            "路", "街", "道", "巷", "弄", "胡同",
            "号", "栋", "幢", "座", "单元", "室", "楼", "层",
            "小区", "花园", "公寓", "大厦", "广场", "苑", "府",
        )

    /** 两段地址之间最多允许几个间隔字符（空白或分隔符），超过就不合并。 */
    const val MERGE_GAP = 2

    /** 太长的文本不做扫描，防止正则在超长内容上跑太久。 */
    const val MAX_SCAN_LENGTH = 20_000

    private const val HAN = "\\u4e00-\\u9fa5"
    private const val HAN_ALNUM = "\\u4e00-\\u9fa5A-Za-z0-9"

    /** 省级行政区白名单。用它锚定长地址的起点，否则「我的地址是浙江…」会把前面的话一起吃进来。 */
    private const val PROVINCES =
        "北京|天津|上海|重庆|河北|山西|辽宁|吉林|黑龙江|江苏|浙江|安徽|福建|江西|山东|河南|" +
            "湖北|湖南|广东|海南|四川|贵州|云南|陕西|甘肃|青海|台湾|内蒙古|广西|西藏|宁夏|新疆|港澳"

    /**
     * 门牌后面还能跟什么：`100号501室` 这种单元号，或者 `88号SOHO` 这种英文楼名。
     * 故意不接受纯汉字尾巴，否则「建国路88号开会」会把「开会」带进来。
     */
    private const val TAIL = "(?:\\d{1,5}(?:室|楼|层|单元|栋|幢|座)|[A-Za-z][A-Za-z0-9 \\-]{1,14})?"

    /**
     * 「剪贴板提取规则」这个设置项默认是空的。空着的时候用这一组兜底，
     * 否则菜单点下去永远是「未找到匹配内容」。用户在设置里填了就用他自己的。
     */
    val FALLBACK_RULE_TEXTS = listOf(
        // 验证码 / 取件码（优先取捕获组里的数字）
        "(?i)(?:验证码|校验码|动态码|确认码|激活码|安全码|取件码|提货码|verification code|code)" +
            "[^0-9]{0,16}(\\d{4,8})",
        // 单号 / 订单号 / 快递单号 / 运单号：字母数字横杠，遇到空格或汉字自然中断
        "(?<!\\w)(?:快递单号|运单号|订单号|单号)[\\s:：=]*([A-Za-z0-9][A-Za-z0-9-]{5,})",
        // 链接：显式排除汉字与全角标点，避免把紧跟的中文吞进来
        "https?://[^\\s\"'<>，。；、（）【】「」\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]+",
        // 邮箱：只认 ASCII，且顶级域必须是 2 位以上字母（结尾的 . 或 - 不会再被吃进去）
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
        // 中国大陆手机号
        "(?<!\\d)1[3-9]\\d{9}(?!\\d)",
        // 金额
        "[¥￥$]\\s?\\d+(?:\\.\\d{1,2})?",
        // 日期
        "\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?",
        // 身份证号
        "(?<!\\d)\\d{17}[\\dXx](?!\\d)",
        // ── 地址 ──
        // R1 路街 + 门牌：必须有数字门牌才算，所以「我在市政府上班」不会命中
        "[$HAN_ALNUM]{2,8}(?:路|街|道|巷|弄|胡同|大道|大街)" +
            "[$HAN_ALNUM]{0,6}\\d{1,5}(?:号|弄)$TAIL",
        // R2a 省 + 市 + 区/县 + 详细地址（省名白名单锚定，不会吃掉前面的话）
        "(?:$PROVINCES)(?:省|市|自治区|特别行政区)?[$HAN]{2,6}(?:市|自治州|地区)" +
            "[$HAN]{2,10}(?:区|县|旗)[^\\s，。；！？、\\n]{2,30}",
        // R2b 市 + 区/县 + 详细地址（没有省名时的常见写法）
        "[$HAN]{2,4}(?:市|自治州|地区)[$HAN]{2,10}(?:区|县|旗)[^\\s，。；！？、\\n]{2,30}",
        // R3 小区 / 大厦 / 广场 + 门牌
        "[$HAN_ALNUM]{2,8}(?:小区|花园|公寓|大厦|广场|中心|苑|府)" +
            "[$HAN_ALNUM]{0,6}\\d{1,5}(?:号|栋|幢|座|单元|室)",
        // R4 裸门牌：单独出现时也收（「100号」「501室」）；
        // 和 R2 隔着空格时靠下面的相邻合并拼回完整地址
        "(?<![\\dA-Za-z])\\d{1,5}(?:号|栋|幢|座|单元|室|楼|层)(?:[^\\s，。；！？、\\n]{0,12})?",
    )

    /** 地址前面常见的引导语，命中后要剥掉。 */
    private val LEAD =
        Regex(
            "^(?:我的|收货|收件|寄件|发货|公司|家庭)?(?:地址|住址)[是为：:\\s]*" +
                "|^(?:我|我们)?(?:住在|位于|前往|去往|寄到|送到|在|去)",
        )

    /** 一处命中：文字 + 在原文里的位置。 */
    data class Hit(
        val text: String,
        val start: Int,
        val end: Int,
    )

    /**
     * 把规则文本编译成 [Regex]，坏规则直接跳过（用户在设置里手填，可能写错）。
     */
    fun compile(ruleTexts: Collection<String>): List<Regex> = ruleTexts.mapNotNull {
        try {
            Regex(it)
        } catch (e: Exception) {
            null
        }
    }

    /** 扫描入口：规则文本 → 结果列表。 */
    fun scan(
        source: String,
        ruleTexts: Collection<String>,
    ): List<String> = scanHits(source, compile(ruleTexts)).map { it.text }

    /** 扫描入口：已编译的规则 → 命中列表（已排序、去重叠、地址已合并、引导语已剥）。 */
    fun scanHits(
        text: String,
        rules: Collection<Regex>,
    ): List<Hit> {
        val source = if (text.length > MAX_SCAN_LENGTH) text.substring(0, MAX_SCAN_LENGTH) else text
        val hits = ArrayList<Hit>()
        rules.forEach { regex ->
            regex.findAll(source).forEach { match ->
                // 有捕获组就取组（验证码、单号那种），否则取整段
                val groups = match.groups.drop(1).filterNotNull().filter { it.value.isNotEmpty() }
                if (groups.isEmpty()) {
                    if (match.value.isNotEmpty()) {
                        hits.add(Hit(match.value, match.range.first, match.range.last + 1))
                    }
                } else {
                    groups.forEach { g -> hits.add(Hit(g.value, g.range.first, g.range.last + 1)) }
                }
            }
        }
        if (hits.isEmpty()) return emptyList()
        // 同一位置开始、更长的优先；被前面已收下的范围包住的丢掉
        hits.sortWith(compareBy({ it.start }, { -it.end }))
        val kept = ArrayList<Hit>(hits.size)
        var coveredUntil = -1
        hits.forEach { hit ->
            if (hit.start >= coveredUntil) {
                kept.add(hit)
                coveredUntil = hit.end
            }
        }
        return mergeAdjacentAddresses(source, kept).map { trimLead(it) }
    }

    /**
     * 把被空格 / 逗号打断的地址接回去：`浙江省杭州市西湖区文三路` + `100号` → 一段。
     *
     * 只对两边都带地址特征、且间隔不超过 [MERGE_GAP] 个字符的相邻命中生效，
     * 所以不会把电话号码、验证码粘到地址上。
     */
    fun mergeAdjacentAddresses(
        source: String,
        hits: List<Hit>,
    ): List<Hit> {
        if (hits.size < 2) return hits
        val merged = ArrayList<Hit>(hits.size)
        hits.forEach { hit ->
            val last = merged.lastOrNull()
            if (last == null || hit.start < last.end) {
                merged.add(hit)
                return@forEach
            }
            val gap = source.substring(last.end, hit.start)
            val canMerge =
                isAddressLike(last.text) &&
                    isAddressLike(hit.text) &&
                    gap.length <= MERGE_GAP &&
                    gap.none { it == '\n' } &&
                    gap.all { it.isWhitespace() || it in "，,、·" }
            if (canMerge) {
                // 纯空白直接去掉，别在地址中间留一个空格
                val joiner = if (gap.isBlank()) "" else gap
                merged[merged.size - 1] = Hit(last.text + joiner + hit.text, last.start, hit.end)
            } else {
                merged.add(hit)
            }
        }
        return merged
    }

    /** 剥掉地址前面粘着的「我的地址是」「我住在」这类引导语（只对地址生效）。 */
    fun trimLead(hit: Hit): Hit {
        if (!isAddressLike(hit.text)) return hit
        var text = hit.text
        var start = hit.start
        repeat(3) {
            val m = LEAD.find(text) ?: return@repeat
            if (m.range.first != 0 || m.value.length >= text.length) return@repeat
            text = text.substring(m.value.length)
            start += m.value.length
        }
        return Hit(text, start, hit.end)
    }

    fun isAddressLike(text: String): Boolean = ADDRESS_HINTS.any { text.contains(it) }
}
