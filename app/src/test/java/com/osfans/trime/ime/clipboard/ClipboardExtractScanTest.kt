/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class ClipboardExtractScanTest :
    FunSpec({
        val fallback = ClipboardExtractScan.FALLBACK_RULE_TEXTS

        fun scan(text: String) = ClipboardExtractScan.scan(text, fallback)

        test("所有兜底规则都能编译") {
            ClipboardExtractScan.compile(fallback).size shouldBe fallback.size
        }

        test("省市区的完整地址整段命中，前面的引导语被剥掉") {
            scan("我的地址是浙江省杭州市西湖区文三路100号，麻烦寄过来。") shouldBe
                listOf("浙江省杭州市西湖区文三路100号")
        }

        test("收货地址：这种前缀也剥掉") {
            scan("收货地址：浙江省杭州市余杭区文一西路969号") shouldBe
                listOf("浙江省杭州市余杭区文一西路969号")
        }

        test("被空格打断的地址会拼回去") {
            // R2 只能吃到这里为止，门牌靠 R4 命中再合并
            scan("浙江省杭州市西湖区文三路 100号 501室") shouldBe
                listOf("浙江省杭州市西湖区文三路100号501室")
        }

        test("多个空格打断的地址也拼回去") {
            scan("深圳市南山区科技园南路 8号 3栋2单元501室") shouldBe
                listOf("深圳市南山区科技园南路8号3栋2单元501室")
        }

        test("没有省名的市+区也认") {
            scan("我住在杭州市西湖区文一路100号") shouldBe
                listOf("杭州市西湖区文一路100号")
        }

        test("路街加门牌单独出现也能认，但不吃掉后面的汉字") {
            // 「开会」不该被当成楼名
            scan("我们在建国路88号开会") shouldBe listOf("建国路88号")
        }

        test("小区加门牌能认") {
            scan("阳光花园3栋2单元501室") shouldBe listOf("阳光花园3栋2单元501室")
        }

        test("不带门牌的行政地名不当地址") {
            val hits = scan("我在市政府上班")
            hits.forEach { ClipboardExtractScan.isAddressLike(it) shouldBe false }
        }

        test("结果按原文位置排，不按规则顺序") {
            val text = "取件码 1234，电话 13800138000，详情见 https://example.com/a"
            scan(text) shouldBe listOf("1234", "13800138000", "https://example.com/a")
        }

        test("邮箱不吃后面的汉字") {
            scan("联系 someone@example.com 谢谢") shouldBe listOf("someone@example.com")
        }

        test("链接不吃后面的中文") {
            scan("https://example.com/a?b=1，请查收") shouldBe listOf("https://example.com/a?b=1")
        }

        test("长地址里重叠的短命中被丢掉，只留最长的一段") {
            scan("浙江省杭州市西湖区文三路100号") shouldBe listOf("浙江省杭州市西湖区文三路100号")
        }

        test("地址与非地址相邻时不合并") {
            val hits = scan("文三路100号 13800138000")
            hits shouldContain "文三路100号"
            hits shouldContain "13800138000"
            hits shouldNotContain "文三路100号13800138000"
        }

        test("单号与地址同时存在时各归各的") {
            scan("快递单号 SF1234567890，送到北京市朝阳区建国路88号1001室") shouldBe
                listOf("SF1234567890", "北京市朝阳区建国路88号1001室")
        }

        test("身份证与手机号") {
            scan("身份证 11010519491231002X 电话 13912345678") shouldBe
                listOf("11010519491231002X", "13912345678")
        }

        test("空文本与无命中文本返回空") {
            scan("") shouldBe emptyList()
            scan("今天天气不错") shouldBe emptyList()
            scan("明天上午10点开会") shouldBe emptyList()
        }

        test("坏规则被跳过且不影响其它规则") {
            ClipboardExtractScan.scan("验证码 5678", listOf("(未闭合", "(\\d{4})")) shouldBe listOf("5678")
        }
    })
