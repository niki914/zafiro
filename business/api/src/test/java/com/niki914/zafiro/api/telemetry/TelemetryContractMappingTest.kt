// 保护：字面量写成表达式、藏进注释，或 LIMIT_/CHANNEL_/CONFIG_KEY_ 不在核对名单里时，服务端核对失败但本地编译仍然通过
package com.niki914.zafiro.api.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryContractMappingTest {

    @Test
    fun `source literals match every mapped name and value`() {
        assertEquals(63, SERVER_MAPPING.size)
        assertEquals(63, SERVER_MAPPING.map { it.kotlin }.distinct().size)
        val source = telemetryContractFile().readText()
        val diffs = compareKotlin(source, SERVER_MAPPING)
        assertEquals(diffs.joinToString("\n"), emptyList<String>(), diffs)
    }

    @Test
    fun `comments do not declare a constant`() {
        val source = """
            const val LIMIT_BODY_BYTES = 1
            /*
            const val LIMIT_BODY_BYTES = 2
            const val LIMIT_DAYS_MIN = 9
            */
            // const val LIMIT_DAYS_MAX = 3
            const val LIMIT_DAYS_MAX = 8 // const val LIMIT_DAYS_MAX = 4
        """.trimIndent()
        val parsed = parseKotlin(source)
        assertEquals(1, parsed.consts["LIMIT_BODY_BYTES"])
        assertEquals(8, parsed.consts["LIMIT_DAYS_MAX"])
        assertEquals(emptyList<String>(), parsed.duplicates)
    }

    @Test
    fun `a second declaration of the same name is a mismatch`() {
        val source = """
            const val LIMIT_REV_MIN = 1
            const val LIMIT_REV_MIN = 2
        """.trimIndent()
        val diffs = compareKotlin(source, listOf(MappedConst("LIMIT_REV_MIN", 1)))
        assertTrue(diffs.joinToString("\n"), diffs.any { "duplicate" in it })
    }

    @Test
    fun `a watched name outside the mapping is a mismatch`() {
        val source = """
            const val LIMIT_NOT_IN_MAPPING = 1
            const val HELPER_IGNORED = 2
        """.trimIndent()
        val diffs = compareKotlin(source, emptyList())
        assertEquals(
            listOf("LIMIT_NOT_IN_MAPPING: server=not in mapping android=1"),
            diffs,
        )
    }

    @Test
    fun `visibility type underscores and long suffix still read as literals`() {
        val source = """
            internal const val LIMIT_COUNT_N_MAX: Int = 1_000_000L
            private const val CHANNEL_GITHUB_NAME: String = "github"
            public const val CONFIG_KEY_TELEMETRY_ENABLED_DEFAULT: Boolean = true
        """.trimIndent()
        val parsed = parseKotlin(source)
        assertEquals(1000000, parsed.consts["LIMIT_COUNT_N_MAX"])
        assertEquals("github", parsed.consts["CHANNEL_GITHUB_NAME"])
        assertEquals(true, parsed.consts["CONFIG_KEY_TELEMETRY_ENABLED_DEFAULT"])
    }

    @Test
    fun `an expression is not the same value as its result`() {
        val source = "const val LIMIT_REV_MIN = 1 + 0\n"
        val diffs = compareKotlin(source, listOf(MappedConst("LIMIT_REV_MIN", 1)))
        assertEquals(1, diffs.size)
    }
}
