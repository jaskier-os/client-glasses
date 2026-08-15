package com.repository.glasses.listener.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryAlerterTest {

    private fun alerter() = LowBatteryAlerter()

    @Test
    fun `no alert while above the highest threshold`() {
        val a = alerter()
        listOf(100, 60, 25, 21).forEach { pct ->
            val d = a.evaluate(pct, eligible = true)
            assertNull("pct=$pct must not alert", d.alertAt)
            assertFalse("pct=$pct must not pin", d.pinScreen)
        }
    }

    @Test
    fun `alerts once at 20 then once at 15`() {
        val a = alerter()
        assertEquals(20, a.evaluate(20, true).alertAt)
        // Still in the 20 band -- already shown, must stay quiet.
        assertNull(a.evaluate(19, true).alertAt)
        assertNull(a.evaluate(16, true).alertAt)
        assertEquals(15, a.evaluate(15, true).alertAt)
        assertNull(a.evaluate(14, true).alertAt)
        assertNull(a.evaluate(11, true).alertAt)
    }

    @Test
    fun `pins at 10 and below and stops carding`() {
        val a = alerter()
        a.evaluate(20, true); a.evaluate(15, true)
        listOf(10, 7, 1, 0).forEach { pct ->
            val d = a.evaluate(pct, eligible = true)
            assertTrue("pct=$pct must pin", d.pinScreen)
            assertNull("pct=$pct must not also card", d.alertAt)
        }
    }

    @Test
    fun `a single large drop shows the most urgent crossed threshold only`() {
        val a = alerter()
        val d = a.evaluate(14, true)
        assertEquals(15, d.alertAt)
        // 20 was passed through and is now stale -- climbing back to 17 must not fire it.
        assertNull(a.evaluate(17, true).alertAt)
    }

    @Test
    fun `dropping straight into the pin band consumes the transient thresholds`() {
        val a = alerter()
        assertTrue(a.evaluate(8, true).pinScreen)
        // Recovering to 12 releases the pin but must not now fire 20 and 15,
        // which were never "unseen" -- they were passed through.
        val d = a.evaluate(12, true)
        assertFalse(d.pinScreen)
        assertNull(d.alertAt)
    }

    @Test
    fun `re-arms only after climbing clear of the threshold`() {
        val a = alerter()
        assertEquals(20, a.evaluate(20, true).alertAt)
        // Inside the hysteresis band: not re-armed.
        assertNull(a.evaluate(21, true).alertAt)
        assertNull(a.evaluate(22, true).alertAt)
        assertNull(a.evaluate(20, true).alertAt)
        // Clear of it: re-armed.
        assertNull(a.evaluate(23, true).alertAt)
        assertEquals(20, a.evaluate(20, true).alertAt)
    }

    @Test
    fun `flapping on the boundary cannot spam`() {
        val a = alerter()
        assertEquals(20, a.evaluate(20, true).alertAt)
        repeat(20) {
            assertNull(a.evaluate(21, true).alertAt)
            assertNull(a.evaluate(20, true).alertAt)
        }
    }

    @Test
    fun `ineligible suppresses both the card and the pin`() {
        val a = alerter()
        val d = a.evaluate(8, eligible = false)
        assertNull(d.alertAt)
        assertFalse(d.pinScreen)
    }

    @Test
    fun `a threshold crossed while ineligible fires when eligibility returns`() {
        val a = alerter()
        assertNull(a.evaluate(18, eligible = false).alertAt)
        // Put the glasses back on at the same level -- the 20% card is still owed.
        assertEquals(20, a.evaluate(18, eligible = true).alertAt)
    }

    @Test
    fun `charging past a threshold re-arms it even though nothing was shown`() {
        val a = alerter()
        assertEquals(20, a.evaluate(20, true).alertAt)
        // On the cable: ineligible, but the level itself climbs clear.
        a.evaluate(30, eligible = false)
        // Unplugged and draining again -- the card is owed once more.
        assertEquals(20, a.evaluate(20, eligible = true).alertAt)
    }

    @Test
    fun `pin releases as soon as the level recovers above the pin threshold`() {
        val a = alerter()
        assertTrue(a.evaluate(9, true).pinScreen)
        assertFalse(a.evaluate(11, true).pinScreen)
    }
}
