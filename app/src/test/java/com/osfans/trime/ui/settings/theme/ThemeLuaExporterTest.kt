/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settings.theme

import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.data.theme.model.GeneralStyle
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 验证 ThemeLuaExporter 把 effective theme 序列化成自包含的 `return { ... }` Lua 纯表。
 */
class ThemeLuaExporterTest :
    BehaviorSpec({
        Given("a minimal theme") {
            When("exported to Lua") {
                val lua = ThemeLuaExporter.buildLua(Theme(name = "MyTheme", generalStyle = GeneralStyle()))

                Then("produces a return statement with the name and an empty style table") {
                    lua shouldBe """return { name = "MyTheme", style = {} }"""
                }
            }
        }

        Given("a theme with numeric and list style fields") {
            When("exported to Lua") {
                val style = GeneralStyle(horizontalGap = 4, keyShadowDirection = listOf("down"))
                val lua = ThemeLuaExporter.buildLua(Theme(name = "T", generalStyle = style))

                Then("numbers are written bare and lists become brace tables") {
                    lua shouldBe
                        """return { name = "T", style = { horizontal_gap = 4, key_shadow_direction = { "down" } } }"""
                }
            }
        }

        Given("a theme with color schemes and fallback colors") {
            When("exported to Lua") {
                val theme =
                    Theme(
                        name = "C",
                        generalStyle = GeneralStyle(),
                        colorSchemes =
                            listOf(
                                ColorScheme(id = "default", colors = mapOf("key_back_color" to "0xFF111213")),
                            ),
                        fallbackColors = mapOf("back_color" to "0xFF000000"),
                    )
                val lua = ThemeLuaExporter.buildLua(theme)

                Then("nested tables and strings are written correctly") {
                    lua shouldBe
                        """return { name = "C", style = {}, preset_color_schemes = { { id = "default", colors = { key_back_color = "0xFF111213" } } }, fallback_colors = { back_color = "0xFF000000" } }"""
                }
            }
        }
    })
