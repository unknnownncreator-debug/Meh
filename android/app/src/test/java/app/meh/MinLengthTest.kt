package app.meh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinLengthTest {
    @Test fun stepsAndLabels() {
        assertEquals(14, MinLength.STEPS.size)
        assertEquals("Any length", MinLength.label(0))
        assertEquals("At least 5 s", MinLength.label(1))
        assertEquals("At least 45 s", MinLength.label(5))
        assertEquals("At least 1 min", MinLength.label(6))
        assertEquals("At least 1½ min", MinLength.label(7))
        assertEquals("At least 20 min", MinLength.label(13))
        assertEquals("out-of-range indexes are clamped", 1200, MinLength.seconds(99))
        assertEquals("shorter than your minimum length (2 min)", MinLength.reason(120))
    }

    @Test fun filter() {
        assertTrue("any length", MinLength.allows(3.0, 0))
        assertTrue("unknown length passes, checked at play time", MinLength.allows(null, 300))
        assertTrue(MinLength.allows(60.0, 60))
        assertFalse(MinLength.allows(59.9, 60))
    }
}
