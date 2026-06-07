package com.repository.glasses.listener

import org.json.JSONArray
import org.json.JSONObject

/**
 * Test/diagnostic singleton mirroring the navigation step state that the
 * glasses display. Both processes (UI + :backend) write to it so the BT
 * `nav_goal_status` diag command can return what's actually being shown.
 *
 * Updates from MainActivity (UI process) are recorded with the timestamp the
 * navStepIndexReceiver fired -- that's when the user sees the change.
 * ListenerService (:backend) records the steps payload it forwarded, so a
 * snapshot matches what the glasses think the journey contains.
 */
object NavStepState {

    data class StepChange(
        val index: Int,
        val timestampMs: Long,
        val source: String
    )

    @Volatile var currentIndex: Int = -1
        private set
    @Volatile var stepsJson: String? = null
        private set
    @Volatile var minimapVisible: Boolean = false
        private set
    @Volatile var lastStepReceivedAtMs: Long = 0L
        private set
    @Volatile var lastStepsReceivedAtMs: Long = 0L
        private set

    private val history = ArrayDeque<StepChange>()
    private const val HISTORY_CAP = 50

    @Synchronized
    fun recordStepIndex(index: Int, source: String) {
        val now = System.currentTimeMillis()
        // Only push to history when the index actually changes -- repeated
        // identical pushes (the phone resends current index after reroutes)
        // would otherwise drown the timeline.
        if (index != currentIndex) {
            history.addLast(StepChange(index, now, source))
            while (history.size > HISTORY_CAP) history.removeFirst()
        }
        currentIndex = index
        lastStepReceivedAtMs = now
    }

    @Synchronized
    fun recordSteps(json: String?) {
        stepsJson = json
        lastStepsReceivedAtMs = System.currentTimeMillis()
    }

    @Synchronized
    fun setMinimapVisible(visible: Boolean) {
        minimapVisible = visible
    }

    @Synchronized
    fun reset() {
        currentIndex = -1
        stepsJson = null
        minimapVisible = false
        lastStepReceivedAtMs = 0L
        lastStepsReceivedAtMs = 0L
        history.clear()
    }

    @Synchronized
    fun snapshotJson(): JSONObject {
        val now = System.currentTimeMillis()
        val parsedSteps: JSONArray? = stepsJson?.let { runCatching { JSONArray(it) }.getOrNull() }
        val currentStep: JSONObject? = parsedSteps?.let { arr ->
            if (currentIndex in 0 until arr.length()) arr.optJSONObject(currentIndex) else null
        }
        val historyArr = JSONArray()
        for (h in history) {
            historyArr.put(JSONObject().apply {
                put("index", h.index)
                put("ts_ms", h.timestampMs)
                put("ago_ms", now - h.timestampMs)
                put("source", h.source)
            })
        }
        return JSONObject().apply {
            // The phone's glasses-command wrapper checks result.success before
            // marking the ADB result successful; without this the snapshot
            // would always come back as "Glasses command failed" even though
            // the BT round-trip worked.
            put("success", true)
            put("current_index", currentIndex)
            put("minimap_visible", minimapVisible)
            put("steps_count", parsedSteps?.length() ?: 0)
            put("last_step_received_ms_ago",
                if (lastStepReceivedAtMs == 0L) -1L else now - lastStepReceivedAtMs)
            put("last_steps_received_ms_ago",
                if (lastStepsReceivedAtMs == 0L) -1L else now - lastStepsReceivedAtMs)
            put("now_ms", now)
            currentStep?.let { put("current_step", it) }
            parsedSteps?.let { put("steps", it) }
            put("history", historyArr)
        }
    }
}
