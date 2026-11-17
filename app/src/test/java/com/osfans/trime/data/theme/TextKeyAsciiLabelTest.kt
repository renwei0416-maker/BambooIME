/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.TextKeySerializer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 验证 TextKeySerializer 的 ascii_label 序列化往返不丢失字段。
 *
 * ThemeDraft.patchedKeyboard 依赖「序列化 → 补丁 → 反序列化」往返，
 * 修复前 serializeKey 未写出 ascii_label、deserializeKey 未读入 ascii_label，
 * 导致单键编辑应用后按键的 ASCII 标签（英文键盘小写字母等）被静默丢弃。
 */
class TextKeyAsciiLabelTest :
    BehaviorSpec({
        Given("a TextKey with an ascii_label") {
            When("serialized and deserialized through TextKeySerializer") {
                val key =
                    TextKeyboard.TextKey(
                        label = listOf(TextKeyboard.LabelSegment(text = "Q")),
                        asciiLabel = listOf(TextKeyboard.LabelSegment(text = "q")),
                    )
                val roundTripped = TextKeySerializer.deserializeKey(TextKeySerializer.serializeKey(key))

                Then("the ascii_label survives the round-trip") {
                    roundTripped.asciiLabel shouldBe listOf(TextKeyboard.LabelSegment(text = "q"))
                }

                Then("the label is preserved too") {
                    roundTripped.label shouldBe listOf(TextKeyboard.LabelSegment(text = "Q"))
                }
            }
        }

        Given("a TextKey without an ascii_label") {
            When("serialized and deserialized") {
                val key =
                    TextKeyboard.TextKey(
                        label = listOf(TextKeyboard.LabelSegment(text = "Q")),
                    )
                val roundTripped = TextKeySerializer.deserializeKey(TextKeySerializer.serializeKey(key))

                Then("the ascii_label stays empty") {
                    roundTripped.asciiLabel shouldBe emptyList()
                }
            }
        }

        Given("a styled ascii_label") {
            When("serialized and deserialized") {
                val key =
                    TextKeyboard.TextKey(
                        label = listOf(TextKeyboard.LabelSegment(text = "Q")),
                        asciiLabel =
                            listOf(
                                TextKeyboard.LabelSegment(text = "q", bold = true, color = "red"),
                                TextKeyboard.LabelSegment(text = "1", align = TextKeyboard.Align.RIGHT),
                            ),
                    )
                val roundTripped = TextKeySerializer.deserializeKey(TextKeySerializer.serializeKey(key))

                Then("per-segment styles survive") {
                    roundTripped.asciiLabel shouldBe
                        listOf(
                            TextKeyboard.LabelSegment(text = "q", bold = true, color = "red"),
                            TextKeyboard.LabelSegment(text = "1", align = TextKeyboard.Align.RIGHT),
                        )
                }
            }
        }
    })
