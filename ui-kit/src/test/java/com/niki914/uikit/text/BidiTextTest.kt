// 保护：RTL 文本的方向投票与显示隔离。改投票阈值、技术词规则或控制符插入方式会打破这些用例。
package com.niki914.uikit.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiTextTest {

    private val lrm = "\u200E"
    private val rlm = "\u200F"
    private val lri = "\u2066"
    private val rli = "\u2067"
    private val pdi = "\u2069"

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
    fun `backtick code does not vote`() {
        assertEquals(BidiDirection.Rtl, BidiText.directionOf("הרץ `adb shell pm list packages` עכשיו"))
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
    fun `arithmetic between numbers is isolated as one run`() {
        assertEquals("${lri}6*7${pdi}", BidiText.isolateLtrIslands("6*7"))
        assertEquals("חשב ${lri}10 + 20 = 30${pdi} עכשיו", BidiText.forDisplay("חשב 10 + 20 = 30 עכשיו"))
    }

    @Test
    fun `backtick code in plain text is isolated as one run`() {
        assertEquals(
            "הרץ ${lrm}`su -c 'id -u'`${lrm} עכשיו",
            BidiText.forDisplay("הרץ `su -c 'id -u'` עכשיו"),
        )
    }

    @Test
    fun `dates and versions are isolated whole or not at all`() {
        assertEquals("נפגש ב${lri}12/11/2026${pdi} בשעה 10:30", BidiText.isolateLtrIslands("נפגש ב12/11/2026 בשעה 10:30"))
        assertEquals("גרסה v1.2.3+456 עכשיו", BidiText.isolateLtrIslands("גרסה v1.2.3+456 עכשיו"))
    }

    @Test
    fun `islands get strong marks so a latin phrase around them stays one run`() {
        assertEquals("הרץ npm run build ${lrm}--prod${lrm} עכשיו", BidiText.forDisplay("הרץ npm run build --prod עכשיו"))
        assertEquals(
            "צריך Python (${lrm}3.10–3.12${lrm}) בשביל זה",
            BidiText.forDisplay("צריך Python (3.10–3.12) בשביל זה"),
        )
        assertEquals(
            "תריץ ${lrm}--dry-run${lrm} ${lrm}./build.sh${lrm} ${lrm}/tmp/out${lrm}",
            BidiText.forDisplay("תריץ --dry-run ./build.sh /tmp/out"),
        )
    }

    @Test
    fun `code with rtl letters is isolated instead of marked`() {
        assertEquals("הרץ ${lri}`echo \"שלום\"`${pdi} עכשיו", BidiText.forDisplay("הרץ `echo \"שלום\"` עכשיו"))
        // ADLAM CAPITAL LETTER ALIF (U+1E900) is outside the BMP: two UTF-16 chars, one RTL letter
        val adlam = "\uD83A\uDD00"
        assertEquals("קרא ${lri}`echo $adlam`${pdi} עכשיו", BidiText.forDisplay("קרא `echo $adlam` עכשיו"))
    }

    @Test
    fun `numbers without a latin neighbour are isolated so later numbers keep their place`() {
        assertEquals("הערכים הם 1, ${lri}10–20${pdi}, 2, 3 עכשיו", BidiText.forDisplay("הערכים הם 1, 10–20, 2, 3 עכשיו"))
        assertEquals("בין ${lrm}10–20${lrm} seconds", BidiText.forDisplay("בין 10–20 seconds"))
        assertEquals(
            "הערכים הם 1, ${lri}10–20${pdi}, 2, 3 (API) עכשיו",
            BidiText.forDisplay("הערכים הם 1, 10–20, 2, 3 (API) עכשיו"),
        )
    }

    @Test
    fun `letters inside an isolated island are not context for a number island`() {
        assertEquals(
            "הערכים הם 1, ${lri}10–20${pdi}, 2, 3 ${lri}`echo שלום`${pdi} עכשיו",
            BidiText.forDisplay("הערכים הם 1, 10–20, 2, 3 `echo שלום` עכשיו"),
        )
        assertEquals(
            "${lri}`שלום echo`${pdi} ${lri}10–20${pdi}, 2, 3 עכשיו",
            BidiText.forDisplay("`שלום echo` 10–20, 2, 3 עכשיו"),
        )
    }

    @Test
    fun `a multiline isolated island keeps its line break as a context boundary`() {
        val text = "API  echo\nשלום  10–20, 2, 3 עכשיו"
        assertEquals(
            listOf(lri.single() to pdi.single(), lri.single() to pdi.single()),
            BidiText.islandMarks(text, listOf(4..14, 16..20)),
        )
    }

    @Test
    fun `arabic indic digits keep their own rtl behaviour`() {
        assertEquals("من ١٠–٢٠ ثانية", BidiText.isolateLtrIslands("من ١٠–٢٠ ثانية"))
        assertEquals("הרץ ${lri}`echo ٣`${pdi} עכשיו", BidiText.forDisplay("הרץ `echo ٣` עכשיו"))
    }

    @Test
    fun `an rtl paragraph that opens with an island still gets an rtl base`() {
        assertEquals("${rlm}${lrm}--verbose${lrm} עוזר לראות הכול", BidiText.forDisplay("--verbose עוזר לראות הכול"))
        assertEquals("${lri}10–20${pdi} שניות בערך", BidiText.forDisplay("10–20 שניות בערך"))
    }

    @Test
    fun `first strong detection skips the inside of an isolate`() {
        assertEquals("${rlm}${lri}`שלום`${pdi} API מחזיר תשובה", BidiText.forDisplay("`שלום` API מחזיר תשובה"))
    }

    @Test
    fun `literal backticks in parsed markdown are plain characters`() {
        val text = "Hello ` one two three four five six ` שלום"
        assertEquals(BidiDirection.Ltr, BidiText.directionOf(text, backtickCode = false))

        val prose = "הסימן ` אומר start והסימן ` אומר end"
        val ticks = listOf(prose.indexOf('`'), prose.lastIndexOf('`')).map { it..it }
        assertEquals(ticks, BidiText.ltrIslands(prose, extra = ticks))
    }

    @Test
    fun `hyphenated names are not ranges`() {
        assertEquals("COVID-19 v2-3", BidiText.isolateLtrIslands("COVID-19 v2-3"))
    }

    @Test
    fun `technical tokens in rtl paragraphs are isolated without their trailing punctuation`() {
        assertEquals("קרא את ${lrm}src/main.kt${lrm}.", BidiText.forDisplay("קרא את src/main.kt."))
        assertEquals("הרץ עם ${lrm}--dry-run${lrm} קודם", BidiText.forDisplay("הרץ עם --dry-run קודם"))
    }

    @Test
    fun `brackets around a path stay outside the isolate as a pair`() {
        assertEquals("ראה (${lrm}../src/app.ts${lrm}) עכשיו", BidiText.forDisplay("ראה (../src/app.ts) עכשיו"))
    }

    @Test
    fun `brackets that belong to a path or url stay inside the isolate`() {
        assertEquals("ראה ${lrm}src/(old)${lrm} עכשיו", BidiText.forDisplay("ראה src/(old) עכשיו"))
        assertEquals("(${lrm}https://example.com/a(b)${lrm})", BidiText.isolateLtrIslands("(https://example.com/a(b))"))
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
