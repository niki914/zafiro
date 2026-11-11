package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.impl.WaitBuiltin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaitBuiltinTest {
    @Test
    fun acceptsSecondsInsideTheRange() {
        assertEquals(2.0, WaitBuiltin.parseSeconds("""{"seconds":2}""")!!, 0.0)
        assertEquals(0.5, WaitBuiltin.parseSeconds("""{"seconds":0.5}""")!!, 0.0)
        assertEquals(60.0, WaitBuiltin.parseSeconds("""{"seconds":60}""")!!, 0.0)
    }

    @Test
    fun rejectsOutOfRangeMissingAndGarbage() {
        assertNull(WaitBuiltin.parseSeconds("""{"seconds":61}"""))
        assertNull(WaitBuiltin.parseSeconds("""{"seconds":0}"""))
        assertNull(WaitBuiltin.parseSeconds("""{"seconds":-3}"""))
        assertNull(WaitBuiltin.parseSeconds("""{}"""))
        assertNull(WaitBuiltin.parseSeconds("""{"seconds":"soon"}"""))
        assertNull(WaitBuiltin.parseSeconds("not json"))
    }
}
