package info.skyblond.fediverse.toot.trans.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The scan loop's cadence predicate. Unlike the other `requireValid*`
 * helpers it has no component constructor consumer (the loop lives in
 * `Main.kt`), so it is tested directly here.
 * */
class ScanIntervalValidationTest {
    @Test
    fun `a positive finite interval is accepted`() {
        assertEquals(1.minutes, requireValidScanInterval("Config `scanInterval`", 1.minutes))
        // the lower bound is inclusive: any real wait is allowed
        assertEquals(1.milliseconds, requireValidScanInterval("Config `scanInterval`", 1.milliseconds))
    }

    @Test
    fun `zero is rejected because the wait is the point`() {
        val e = assertFailsWith<IllegalArgumentException> {
            requireValidScanInterval("Config `scanInterval`", Duration.ZERO)
        }
        assertTrue(e.message!!.contains("Config `scanInterval`"))
        assertTrue(e.message!!.contains("at least 1ms"))
    }

    @Test
    fun `negative and infinite intervals are rejected`() {
        val negative = assertFailsWith<IllegalArgumentException> {
            requireValidScanInterval("Config `scanInterval`", (-1).seconds)
        }
        assertTrue(negative.message!!.contains("Config `scanInterval`"))
        val infinite = assertFailsWith<IllegalArgumentException> {
            requireValidScanInterval("Config `scanInterval`", Duration.INFINITE)
        }
        assertTrue(infinite.message!!.contains("Config `scanInterval`"))
    }
}
