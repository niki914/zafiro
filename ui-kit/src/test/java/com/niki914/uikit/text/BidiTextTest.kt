// 保护：RTL 文本的方向投票与显示隔离。改投票阈值、技术词规则或控制符插入方式会打破这些用例。
package com.niki914.uikit.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiTextTest {

    private val lrm = "‎"
    private val rlm = "‏"
    private val lri = "⁦"
    private val rli = "⁧"
    private val pdi = "⁩"

    @Test
    fun `hebrew that opens with english words is rtl`() {
        assertEquals(BidiDirection.Rtl, BidiText.directionOf("Claude Code רץ על הטלפון שלי"))
    }

    @Test
    fun `english with a single hebrew word stays ltr`() {
        assertEquals(BidiDirection.Ltr, BidiText.directionOf("Please translate the word שלום into English"))
    }

    @Test
    fun `arabic and persian are rtl`() {
        assertEquals(BidiDirection.Rtl, BidiText.directionOf("Zafiro يعمل على الهاتف"))
        assertEquals(BidiDirection.Rtl, BidiText.directionOf("این برنامه خوب است"))
    }

    @Test
    fun `text without letters has no direction`() {
        assertNull(BidiText.directionOf("10–20 % 3.5 !!!"))
        assertNull(BidiText.directionOf(""))
    }

    @Test
    fun `first rtl word lowers the threshold to a quarter`() {
        assertEquals(BidiDirection.Rtl, BidiText.directionOf("שלום this is test"))
        assertEquals(BidiDirection.Ltr, BidiText.directionOf("שלום this is a test"))
    }

    @Test
    fun `paths flags and urls do not outvote the prose`() {
        val text = "תריץ --dry-run --verbose ./build.sh /tmp/out https://example.com"

        assertEquals(BidiDirection.Rtl, BidiText.directionOf(text))
    }

    @Test
    fun `a slash between hebrew words is prose not a path`() {
        assertEquals(BidiDirection.Rtl, BidiText.directionOf("כן/לא"))
    }

    @Test
    fun `punctuation only tokens do not vote`() {
        assertEquals(BidiDirection.Rtl, BidiText.directionOf("— ... ??? שלום"))
    }

    @Test
    fun `fields with several paragraphs leave direction to the platform`() {
        assertNull(BidiText.fieldDirectionOf("שלום\nhello"))
        assertEquals(BidiDirection.Rtl, BidiText.fieldDirectionOf("Claude רץ על הטלפון"))
    }

    @Test
    fun `hebrew paragraph that opens with english gets a leading rlm`() {
        assertEquals("${rlm}Claude Code רץ על הטלפון", BidiText.forDisplay("Claude Code רץ על הטלפון"))
    }

    @Test
    fun `english paragraph that opens with hebrew gets a leading lrm`() {
        assertEquals("${lrm}שלום means hello in Hebrew", BidiText.forDisplay("שלום means hello in Hebrew"))
    }

    @Test
    fun `paragraphs already matching first strong are left alone`() {
        assertEquals("run src/main 10–20 now", BidiText.forDisplay("run src/main 10–20 now"))
        assertEquals("שלום עולם", BidiText.forDisplay("שלום עולם"))
    }

    @Test
    fun `display text keeps the paragraph count and copies back to the original`() {
        val original = "Claude רץ כאן\nplain English line\n\nשלום 10–20"

        val display = BidiText.forDisplay(original)

        assertEquals(original.split('\n').size, display.split('\n').size)
        assertEquals(original, BidiText.stripInsertedMarks(display))
    }

    @Test
    fun `number ranges in rtl paragraphs are isolated as one run`() {
        assertEquals("הטווח הוא ${lri}10–20${pdi} מעלות", BidiText.forDisplay("הטווח הוא 10–20 מעלות"))
    }

    @Test
    fun `ranges with spaced hyphens decimals and percents are isolated`() {
        assertEquals("${lri}3.5 - 4.5${pdi}", BidiText.isolateLtrIslands("3.5 - 4.5"))
        assertEquals("${lri}10%–20%${pdi}", BidiText.isolateLtrIslands("10%–20%"))
    }

    @Test
    fun `hyphenated names are not ranges`() {
        assertEquals("COVID-19 v2-3", BidiText.isolateLtrIslands("COVID-19 v2-3"))
    }

    @Test
    fun `technical tokens in rtl paragraphs are isolated without their trailing punctuation`() {
        assertEquals("קרא את ${lri}src/main.kt${pdi}.", BidiText.forDisplay("קרא את src/main.kt."))
        assertEquals("הרץ עם ${lri}--dry-run${pdi} קודם", BidiText.forDisplay("הרץ עם --dry-run קודם"))
    }

    @Test
    fun `brackets around a path stay outside the isolate as a pair`() {
        assertEquals("ראה (${lri}../src/app.ts${pdi}) עכשיו", BidiText.forDisplay("ראה (../src/app.ts) עכשיו"))
    }

    @Test
    fun `brackets that belong to a path or url stay inside the isolate`() {
        assertEquals("ראה ${lri}src/(old)${pdi} עכשיו", BidiText.forDisplay("ראה src/(old) עכשיו"))
        assertEquals("(${lri}https://example.com/a(b)${pdi})", BidiText.isolateLtrIslands("(https://example.com/a(b))"))
    }

    @Test(timeout = 1_000)
    fun `a long run of brackets is trimmed in linear time`() {
        assertNull(BidiText.directionOf(")".repeat(50_000)))
        assertEquals(listOf(0..3), BidiText.ltrIslands("src/" + ")".repeat(50_000)))
    }

    @Test
    fun `a flag in brackets does not vote`() {
        assertEquals(BidiDirection.Rtl, BidiText.directionOf("הרץ שוב (--verbose) (--dry-run) ./build.sh"))
    }

    @Test
    fun `extra islands such as inline code merge with overlapping ones`() {
        val text = "קרא את  src/a.kt  עכשיו"

        assertEquals(listOf(6..17), BidiText.ltrIslands(text, extra = listOf(6..17)))
        assertEquals(listOf(0..1, 8..15), BidiText.ltrIslands(text, extra = listOf(0..1, 8..10)))
    }

    @Test
    fun `authored direction controls are respected`() {
        val authored = "${rlm}Claude Code רץ 10–20"

        assertEquals(authored, BidiText.forDisplay(authored))
        assertEquals(authored, BidiText.isolate(authored))
        assertNull(BidiText.directionOf("${lrm}שלום עולם?"))
    }

    @Test
    fun `isolate wraps embedded text by its own direction`() {
        assertEquals("${rli}בדוק את הקובץ${pdi}", BidiText.isolate("בדוק את הקובץ"))
        assertEquals("${lri}check the file${pdi}", BidiText.isolate("check the file"))
    }
}
