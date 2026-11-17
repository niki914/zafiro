package com.niki914.zafiro.app.ui.home.composer.suggestion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashFuzzyMatcherTest {

    private val sampleSkills = listOf(
        SlashSuggestion(
            id = "phone-use",
            category = SlashCategory.SKILL,
            displayText = "/skill:phone-use",
            replaceToken = "/skill:phone-use ",
            searchKeys = listOf("Phone Use", "phone-use"),
        ),
        SlashSuggestion(
            id = "python",
            category = SlashCategory.SKILL,
            displayText = "/skill:python",
            replaceToken = "/skill:python ",
            searchKeys = listOf("Python Code", "python"),
        ),
        SlashSuggestion(
            id = "browser-use",
            category = SlashCategory.SKILL,
            displayText = "/skill:browser-use",
            replaceToken = "/skill:browser-use ",
            searchKeys = listOf("Browser Use", "browser-use"),
        ),
        SlashSuggestion(
            id = "termux",
            category = SlashCategory.SKILL,
            displayText = "/skill:termux",
            replaceToken = "/skill:termux ",
            searchKeys = listOf("Termux Shell", "termux"),
        ),
    )

    @Test
    fun findSlashTrigger_validAtStart() {
        val trigger = findSlashTrigger("/", 1)
        assertNotNull(trigger)
        assertEquals(0, trigger?.slashIndex)
        assertEquals("", trigger?.query)
        assertEquals(1, trigger?.endIndex)
    }

    @Test
    fun findSlashTrigger_validWithQuery() {
        val trigger = findSlashTrigger("/pu", 3)
        assertNotNull(trigger)
        assertEquals(0, trigger?.slashIndex)
        assertEquals("pu", trigger?.query)
        assertEquals(3, trigger?.endIndex)
    }

    @Test
    fun findSlashTrigger_validAfterWhitespace() {
        val trigger = findSlashTrigger("帮我调用 /p", 7)
        assertNotNull(trigger)
        assertEquals(5, trigger?.slashIndex)
        assertEquals("p", trigger?.query)
        assertEquals(7, trigger?.endIndex)
    }

    @Test
    fun findSlashTrigger_validAfterChineseCharacters() {
        val trigger = findSlashTrigger("帮我用/", 4)
        assertNotNull(trigger)
        assertEquals(3, trigger?.slashIndex)
        assertEquals("", trigger?.query)
        assertEquals(4, trigger?.endIndex)
    }

    @Test
    fun findSlashTrigger_validAfterChinesePunctuation() {
        val trigger = findSlashTrigger("好的，/", 4)
        assertNotNull(trigger)
        assertEquals(3, trigger?.slashIndex)
        assertEquals("", trigger?.query)
        assertEquals(4, trigger?.endIndex)
    }

    @Test
    fun findSlashTrigger_ignoresEnglishCharactersWithoutWhitespace() {
        assertNull(findSlashTrigger("hello/", 6))
    }

    @Test
    fun findSlashTrigger_ignoresMidSentenceCursor() {
        // Cursor in middle of existing skill should not trigger
        assertNull(findSlashTrigger("/skill:phone-use", 4))
        assertNull(findSlashTrigger("帮我用 /p 打开应用", 7))
    }

    @Test
    fun findSlashTrigger_ignoresUrlAndPathWithoutWhitespace() {
        assertNull(findSlashTrigger("https://example.com/foo", 23))
        assertNull(findSlashTrigger("/data/data", 10))
    }

    @Test
    fun findSlashTrigger_ignoresTokenWithWhitespace() {
        assertNull(findSlashTrigger("/p test", 7))
    }

    @Test
    fun replacement_cleansTrailingQueryWithoutDuplicatingLastChar() {
        // Given text is "/ph" and cursor at end (3)
        val text = "/ph"
        val trigger = findSlashTrigger(text, 3)
        assertNotNull(trigger)

        val before = text.substring(0, trigger!!.slashIndex)
        val after = text.substring(trigger.endIndex)
        val replacement = "/skill:phone-use "
        val result = before + replacement + after

        // Must be "/skill:phone-use ", NOT "/skill:phone-use h"
        assertEquals("/skill:phone-use ", result)
    }

    @Test
    fun match_emptyQueryReturnsAll() {
        val result = SlashFuzzyMatcher.match("", sampleSkills)
        assertEquals(sampleSkills.size, result.size)
    }

    @Test
    fun match_prefixMatchesCorrectly() {
        val result = SlashFuzzyMatcher.match("p", sampleSkills)
        val ids = result.map { it.id }
        assertTrue(ids.contains("phone-use"))
        assertTrue(ids.contains("python"))
        assertTrue(!ids.contains("browser-use"))
    }

    @Test
    fun match_wordBoundaryAcronymMatchesPuToPhoneUse() {
        // '/pu' should match 'p'hone-'u'se via acronym/word boundary
        val result = SlashFuzzyMatcher.match("pu", sampleSkills)
        val ids = result.map { it.id }
        assertTrue(ids.contains("phone-use"))
        assertTrue(!ids.contains("python"))
    }

    @Test
    fun match_subsequenceMatchesPo() {
        // '/po' should match 'p'h'o'ne-use and 'p'yth'o'n
        val result = SlashFuzzyMatcher.match("po", sampleSkills)
        val ids = result.map { it.id }
        assertTrue(ids.contains("phone-use"))
        assertTrue(ids.contains("python"))
        assertTrue(!ids.contains("browser-use"))
    }

    @Test
    fun match_categoryPrefixRecognized() {
        val result = SlashFuzzyMatcher.match("sk", sampleSkills)
        assertEquals(sampleSkills.size, result.size)
    }

    @Test
    fun match_categoryColonQueryMatches() {
        val result = SlashFuzzyMatcher.match("skill:pu", sampleSkills)
        val ids = result.map { it.id }
        assertEquals(listOf("phone-use"), ids)
    }

    @Test
    fun match_exactMatchPreservedAsTopCandidate() {
        val result = SlashFuzzyMatcher.match("skill:phone-use", sampleSkills)
        assertEquals("phone-use", result.firstOrNull()?.id)
    }

    @Test
    fun match_exactMatchWithLongerCompletionsRanksExactMatchFirst() {
        val candidatesWithPrefix = listOf(
            SlashSuggestion(
                id = "git",
                category = SlashCategory.SKILL,
                displayText = "/skill:git",
                replaceToken = "/skill:git ",
            ),
            SlashSuggestion(
                id = "github",
                category = SlashCategory.SKILL,
                displayText = "/skill:github",
                replaceToken = "/skill:github ",
            ),
        )
        val result = SlashFuzzyMatcher.match("skill:git", candidatesWithPrefix)
        assertEquals(listOf("git", "github"), result.map { it.id })
    }

    @Test
    fun match_singleLetterDoesNotHijackViaCategoryPrefix() {
        val candidates = listOf(
            SlashSuggestion(
                id = "search-web",
                category = SlashCategory.SKILL,
                displayText = "/skill:search-web",
                replaceToken = "/skill:search-web ",
            ),
            SlashSuggestion(
                id = "python",
                category = SlashCategory.SKILL,
                displayText = "/skill:python",
                replaceToken = "/skill:python ",
            ),
        )
        val result = SlashFuzzyMatcher.match("s", candidates)
        val ids = result.map { it.id }
        assertTrue(ids.contains("search-web"))
        assertTrue(!ids.contains("python"))
    }

    @Test
    fun match_camelCaseWordBoundaryMatches() {
        val candidates = listOf(
            SlashSuggestion(
                id = "phone-helper",
                category = SlashCategory.SKILL,
                displayText = "/skill:phone-helper",
                replaceToken = "/skill:phone-helper ",
                searchKeys = listOf("phoneUseHelper"),
            ),
        )
        val result = SlashFuzzyMatcher.match("pu", candidates)
        assertEquals(listOf("phone-helper"), result.map { it.id })
    }

    @Test
    fun match_noMatchReturnsEmpty() {
        val result = SlashFuzzyMatcher.match("xyz123", sampleSkills)
        assertTrue(result.isEmpty())
    }

    @Test
    fun builtinSlashCommand_findMatchesExactAndIgnoresCaseAndWhitespace() {
        assertEquals(BuiltinSlashCommand.NewSession, BuiltinSlashCommand.find("/new"))
        assertEquals(BuiltinSlashCommand.NewSession, BuiltinSlashCommand.find("  /new  "))
        assertEquals(BuiltinSlashCommand.NewSession, BuiltinSlashCommand.find("/NEW"))
        assertEquals(BuiltinSlashCommand.Compact, BuiltinSlashCommand.find("/compact"))
        assertEquals(BuiltinSlashCommand.Compact, BuiltinSlashCommand.find("  /compact  "))
        assertEquals(BuiltinSlashCommand.Compact, BuiltinSlashCommand.find("/COMPACT"))
        assertNull(BuiltinSlashCommand.find("sentence /new"))
        assertNull(BuiltinSlashCommand.find("/other"))
    }

    @Test
    fun match_builtinCommandsMatchedByPrefixAndKeywords() {
        val candidates = BuiltinSlashCommand.ALL.map { it.toSuggestion() }

        val newResult = SlashFuzzyMatcher.match("n", candidates)
        assertEquals(listOf("new"), newResult.map { it.id })

        val compactResult = SlashFuzzyMatcher.match("c", candidates)
        assertEquals(listOf("compact"), compactResult.map { it.id })

        val chineseNewResult = SlashFuzzyMatcher.match("新", candidates)
        assertEquals(listOf("new"), chineseNewResult.map { it.id })

        val chineseCompactResult = SlashFuzzyMatcher.match("压", candidates)
        assertEquals(listOf("compact"), chineseCompactResult.map { it.id })

        val exactCompact = SlashFuzzyMatcher.match("compact", candidates)
        assertEquals(listOf("compact"), exactCompact.map { it.id })

        val exactNew = SlashFuzzyMatcher.match("new", candidates)
        assertEquals(listOf("new"), exactNew.map { it.id })
    }
}
