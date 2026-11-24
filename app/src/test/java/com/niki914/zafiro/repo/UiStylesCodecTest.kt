package com.niki914.zafiro.repo

// 保护：坏的 ui_styles 不能盖过全局风格；合法覆盖要单独生效，不认识的键不能被读丢。

import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.base.UiStylePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiStylesCodecTest {

    @Test
    fun unusableDocumentHasNoOverrides() {
        assertEquals(emptyMap<String, String>(), UiStylesCodec.parse("not json{"))
        assertEquals(emptyMap<String, String>(), UiStylesCodec.parse("{}"))
        assertEquals(emptyMap<String, String>(), UiStylesCodec.parse("""{"overrides":[]}"""))
    }

    @Test
    fun onlyLegalStyleValuesCount() {
        val parsed = UiStylesCodec.parse(
            """{"overrides":{"top_bar_buttons":"md3","top_bar_plate":"nope"}}""",
        )

        assertEquals(ThemeStyle.Material3, UiStylesCodec.overrideOrNull(parsed, UiStylePart.TopBarButtons))
        assertNull(UiStylesCodec.overrideOrNull(parsed, UiStylePart.TopBarPlate))
    }

    @Test
    fun unknownPartStaysInTheMap() {
        val parsed = UiStylesCodec.parse(
            """{"overrides":{"future_part":"liquid","top_bar_buttons":"md3e"}}""",
        )

        assertEquals("liquid", parsed["future_part"])
        assertEquals(
            ThemeStyle.Material3Expressive,
            UiStylesCodec.overrideOrNull(parsed, UiStylePart.TopBarButtons),
        )
    }

    @Test
    fun roundTripEncoding() {
        val original = mapOf(
            "button" to "liquid",
            "switch" to "md3e",
        )
        val encoded = UiStylesCodec.encode(original)
        val parsed = UiStylesCodec.parse(encoded)
        assertEquals(original, parsed)
    }
}
