package com.niki914.zafiro.chat.agentic.buildin.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId

class UtilityToolsTest {
    private val nairobi = ZoneId.of("Africa/Nairobi")

    @Test fun parsesDatesAndDateTimesInTheGivenZone() {
        assertEquals("2026-11-16T00:00+03:00[Africa/Nairobi]", DateMath.parse("2026-11-16", nairobi).toString())
        assertEquals("2026-11-16T09:30+03:00[Africa/Nairobi]", DateMath.parse("2026-11-16T09:30", nairobi).toString())
        assertEquals("2026-11-16T12:00+03:00[Africa/Nairobi]", DateMath.parse("2026-11-16T09:00:00Z", nairobi).toString())
        assertNull(DateMath.parse("tomorrow", nairobi))
    }

    @Test fun nextWeekdayIsStrictlyInTheFuture() {
        val sunday = DateMath.parse("2026-11-15", nairobi)!!
        assertEquals("2026-11-16", DateMath.nextWeekday(sunday, DayOfWeek.MONDAY).toLocalDate().toString())
        assertEquals("2026-11-22", DateMath.nextWeekday(sunday, DayOfWeek.SUNDAY).toLocalDate().toString())
        assertEquals("Sunday", DateMath.describe(sunday)["weekday"]); assertNotNull(DateMath.describe(sunday)["week_of_year"])
    }

    @Test fun cardFitShrinksUntilTheTextFits() {
        assertEquals(60, CardSpec.fitSize(120, 28) { it <= 60 })
        assertEquals(28, CardSpec.fitSize(120, 28) { false })
        assertEquals(300, CardSpec.clampSize(10, 1080)); assertEquals(1080, CardSpec.clampSize(null, 1080))
    }
}
