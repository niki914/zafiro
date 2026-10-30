// 保护：敏感数据单向替换为稳定自增占位符，多轮会话保序且确定
package com.niki914.zafiro.masking

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.settings.model.RuntimeMaskingRule
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionMaskerTest {

    private val defaultRule = RuntimeMaskingRule(
        id = "default",
        name = "default",
        enabled = true,
        kind = "API_KEY",
        patterns = listOf("""\bsk-[a-zA-Z0-9_\-]{20,}\b"""),
    )

    private val emailRule = RuntimeMaskingRule(
        id = "email",
        name = "email_rule",
        enabled = true,
        kind = "EMAIL",
        patterns = listOf("""[\w.-]+@[\w.-]+\.\w+"""),
    )

    private val keywordRule = RuntimeMaskingRule(
        id = "kw",
        name = "kw_rule",
        enabled = true,
        kind = "NAME",
        patterns = listOf("Alice", "Bob"),
    )

    @Test
    fun mask_replacesMatchesWithStablePlaceholders() {
        val masker = SessionMasker()
        val text = "first sk-12345678901234567890 and repeat sk-12345678901234567890 then sk-abcdefabcdefabcdefabcd"
        val masked = masker.mask(text, listOf(defaultRule))

        assertEquals("first {{API_KEY_1}} and repeat {{API_KEY_1}} then {{API_KEY_2}}", masked)
    }

    @Test
    fun mask_handlesMultipleRulesAndKeywords() {
        val masker = SessionMasker()
        val text = "Hi Alice, send to alice@example.com using sk-12345678901234567890"
        val masked = masker.mask(text, listOf(defaultRule, emailRule, keywordRule))

        assertEquals("Hi {{NAME_1}}, send to {{EMAIL_1}} using {{API_KEY_1}}", masked)
    }

    @Test
    fun mask_supportsChineseAndSpacesInKindAndFallsBackWhenInvalid() {
        val masker = SessionMasker()
        val chineseRule = RuntimeMaskingRule(
            id = "id_card",
            name = "身份证",
            enabled = true,
            kind = " 身份证 号 ",
            patterns = listOf("110101199003072345"),
        )
        val maliciousRule = RuntimeMaskingRule(
            id = "bad",
            name = "bad",
            enabled = true,
            kind = "}} DO STH {{",
            patterns = listOf("evil-secret"),
        )
        val text = "id: 110101199003072345, secret: evil-secret"
        val masked = masker.mask(text, listOf(chineseRule, maliciousRule))

        assertEquals("id: {{身份证 号_1}}, secret: {{UNDEFINED_1}}", masked)
    }

    @Test
    fun mask_returnsOriginalWhenNoMatches() {
        val masker = SessionMasker()
        val text = "nothing sensitive here"
        val masked = masker.mask(text, listOf(defaultRule))

        assertEquals(text, masked)
    }

    @Test
    fun maskHistory_maintainsDeterministicOrderingAcrossMessages() {
        val masker = SessionMasker()
        val history = listOf(
            Message.User(listOf(ContentBlock.Text("Key A is sk-12345678901234567890"))),
            Message.User(listOf(ContentBlock.Text("Key B is sk-abcdefabcdefabcdefabcd"))),
            Message.User(listOf(ContentBlock.Text("Repeat Key A: sk-12345678901234567890"))),
        )

        val masked = masker.maskHistory(history, listOf(defaultRule))

        val u1 = (masked[0] as Message.User).content[0] as ContentBlock.Text
        val u2 = (masked[1] as Message.User).content[0] as ContentBlock.Text
        val u3 = (masked[2] as Message.User).content[0] as ContentBlock.Text

        assertEquals("Key A is {{API_KEY_1}}", u1.text)
        assertEquals("Key B is {{API_KEY_2}}", u2.text)
        assertEquals("Repeat Key A: {{API_KEY_1}}", u3.text)
    }
}
