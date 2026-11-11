/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.segments

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PieceTokenizerTest :
    FunSpec({

        // 测试里不碰 android.icu（单元测试没有真机词典），汉字一律退化成一字一块
        val noHanSplit: (String) -> List<String>? = { null }

        fun split(text: String) = PieceTokenizer.split(text, noHanSplit)

        test("pieces join back to the original") {
            val samples =
                listOf(
                    "",
                    "请前往 mt.cn/15VOWoda 还车。",
                    "Hi, OK?",
                    "第一行\n第二行\r\n第三行\r缩进\tTab",
                    "  lead and trail  ",
                    "价格 1,000.5 元，电话 +86 157-3760-5019",
                    "a = b && c == d // e",
                    "((x)) [y] \"z\" 中文标点：、。！",
                )
            samples.forEach { text ->
                split(text).joinToString("") shouldBe text
            }
        }

        test("whitespace runs and newlines are pieces") {
            split("a b") shouldBe listOf("a", " ", "b")
            split("a  b") shouldBe listOf("a", "  ", "b")
            split("a\nb") shouldBe listOf("a", "\n", "b")
            split("a\r\nb") shouldBe listOf("a", "\r\n", "b")
            split("  lead") shouldBe listOf("  ", "lead")
            split("if x {\n    y;\n}") shouldBe
                listOf("if", " ", "x", " ", "{", "\n", "    ", "y", ";", "\n", "}")
        }

        test("links emails and numbers stay whole") {
            split("mt.cn/15VOWoda") shouldBe listOf("mt.cn/15VOWoda")
            split("(see https://qingjian.app/docs)") shouldBe
                listOf("(", "see", " ", "https://qingjian.app/docs", ")")
            split("a@b.com") shouldBe listOf("a@b.com")
            split("157-3760-5019") shouldBe listOf("157-3760-5019")
            split("1,000.5") shouldBe listOf("1,000.5")
            split("+8613800138000") shouldBe listOf("+8613800138000")
            split("2026/11/12") shouldBe listOf("2026/11/12")
        }

        test("repeated operators merge but brackets do not") {
            split("a==b") shouldBe listOf("a", "==", "b")
            split("a&&b") shouldBe listOf("a", "&&", "b")
            split("((") shouldBe listOf("(", "(")
            // 带 / . @ : 的串按链接整块处理（和青简一致），所以 a//b 不再拆
            split("a//b") shouldBe listOf("a//b")
        }

        test("brackets around a link stay separate") {
            split("(mt.cn/x)") shouldBe listOf("(", "mt.cn/x", ")")
        }

        test("a han splitter that covers the run is used as is") {
            PieceTokenizer.split("今天天气不错", { listOf("今天", "天气", "不错") }) shouldBe
                listOf("今天", "天气", "不错")
        }

        test("a han splitter that loses characters falls back to single chars") {
            PieceTokenizer.split("今天天气", { listOf("今天") }) shouldBe
                listOf("今", "天", "天", "气")
        }

        test("words and digits that mix letters are words") {
            split("utf8") shouldBe listOf("utf8")
        }
    })
