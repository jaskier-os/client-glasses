package com.repository.glasses.listener.battery

/**
 * Decides WHEN the low-battery state should light the waveguide. Pure state
 * machine -- no Android types, no side effects -- so the threshold/hysteresis
 * behaviour is unit-testable without a device.
 *
 * Two distinct behaviours, mirroring the notification "show only this" model:
 *
 *  - ALERT: crossing down through each of [alertThresholds] (20%, 15%) shows a
 *    single card and lights the panel for a bounded window. Fires once per
 *    crossing; re-arms only after the level climbs back above the threshold by
 *    [rearmHysteresis], so a level flapping on the boundary cannot spam.
 *  - PIN: at or below [pinThreshold] (10%) the panel stays lit continuously,
 *    because at that point the wearer needs to see the state at a glance rather
 *    than be told once.
 *
 * Both are suppressed while [eligible] is false (charging, or not on the head):
 * there is nothing to warn about on a charger, and there is no eye in front of
 * the waveguide when the glasses are folded or off. A suppressed alert is NOT
 * consumed -- it stays pending and fires on the next evaluation once the wearer
 * puts the glasses back on, so a threshold crossed off-head is not lost.
 */
class LowBatteryAlerter(
    private val alertThresholds: List<Int> = listOf(20, 15),
    private val pinThreshold: Int = 10,
    private val rearmHysteresis: Int = 2,
) {
    /**
     * @param alertAt the threshold whose card should be shown now, or null.
     * @param pinScreen whether the panel should currently be held on.
     */
    data class Decision(val alertAt: Int?, val pinScreen: Boolean)

    /** Thresholds whose card has already been shown and not yet re-armed. */
    private val consumed = HashSet<Int>()

    /**
     * Whether the LEVEL itself is low, independent of whether an alert is due.
     *
     * A CONDITION, not an event. [Decision.alertAt] is one-shot -- it goes null the
     * instant its card is consumed -- so anything that must stay true for the whole time
     * the battery is low (the indicator surviving a blackout, for one) has to ask this
     * instead. Deriving that from the decision made the state flicker on for a single
     * evaluation and back off two seconds later.
     */
    fun isLow(pct: Int): Boolean =
        pct <= (alertThresholds.maxOrNull() ?: pinThreshold) || pct <= pinThreshold

    fun evaluate(pct: Int, eligible: Boolean): Decision {
        // Re-arm independently of eligibility: this tracks the battery itself,
        // not whether we were allowed to draw. Charging past 20% must re-arm the
        // 20% card even though no card was shown while charging.
        alertThresholds.forEach { t ->
            if (pct > t + rearmHysteresis) consumed.remove(t)
        }

        if (!eligible) return Decision(alertAt = null, pinScreen = false)

        // Below the pin threshold the panel is already lit permanently; adding a
        // transient card on top would just re-blink a screen that never goes off.
        if (pct <= pinThreshold) {
            // Passing straight through 20/15 into pinned territory (a fast drain,
            // or a first reading taken below them) still counts as having shown
            // them -- otherwise they would all fire the moment the pin releases.
            alertThresholds.forEach { if (pct <= it) consumed.add(it) }
            return Decision(alertAt = null, pinScreen = true)
        }

        // Lowest crossed-but-unshown threshold wins, so a drop from 22% to 14%
        // in one step shows 15% (the more urgent state) rather than 20%.
        val due = alertThresholds.filter { pct <= it && it !in consumed }.minOrNull()
        if (due != null) {
            // Everything above the one we are about to show is now stale.
            alertThresholds.forEach { if (pct <= it) consumed.add(it) }
        }
        return Decision(alertAt = due, pinScreen = false)
    }
}
