package info.skyblond.fediverse.toot.trans.masto.statuses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class CooldownTest {
    private val cooldown = Cooldown(3.minutes)

    @Test
    fun `the cooldown boundary is inclusive and measured against the fetch time`() {
        val boundary = original().copy(createdAt = now.minusSeconds(180))
        assertTrue(cooldown.checkCooldown(fetched(boundary)))
        val young = original().copy(createdAt = now.minusSeconds(179))
        assertFalse(cooldown.checkCooldown(fetched(young)))
    }

    @Test
    fun `an edit resets the cooldown`() {
        val justEdited = original().copy(editedAt = now.minusSeconds(30))
        assertFalse(cooldown.checkCooldown(fetched(justEdited)))
        val settledEdit = original().copy(editedAt = now.minusSeconds(180))
        assertTrue(cooldown.checkCooldown(fetched(settledEdit)))
    }

    @Test
    fun `a version fetched during the cooldown is not final even when checked later`() {
        val young = original().copy(createdAt = now.minusSeconds(60))
        val earlyFetch = fetched(young, at = young.createdAt.plusSeconds(30))
        assertFalse(cooldown.checkCooldown(earlyFetch))
    }

    @Test
    fun `future timestamps keep cooling until time catches up`() {
        val future = original().copy(createdAt = now.plusSeconds(60))
        assertFalse(cooldown.checkCooldown(fetched(future)))
        // fetched a full cooldown after the future creation time: settled
        val settledAt = now.plusSeconds(60).plusSeconds(180)
        assertTrue(cooldown.checkCooldown(fetched(future, at = settledAt)))
    }

    @Test
    fun `zero disables the wait for everything except future timestamps`() {
        val instant = Cooldown(Duration.ZERO)
        assertTrue(instant.checkCooldown(fetched(original())))
        assertTrue(instant.checkCooldown(fetched(original().copy(createdAt = now.minusSeconds(600)))))
        assertFalse(instant.checkCooldown(fetched(original().copy(createdAt = now.plusSeconds(1)))))
    }

    @Test
    fun `negative and infinite durations fail fast while zero and the default are accepted`() {
        for (bad in listOf((-1).minutes, Duration.INFINITE)) {
            val error = assertFailsWith<IllegalArgumentException> { Cooldown(bad) }
            assertTrue(error.message!!.contains("duration"))
        }
        Cooldown(Duration.ZERO)
        Cooldown()
        assertEquals(3.minutes, Cooldown.DEFAULT_DURATION)
    }
}
