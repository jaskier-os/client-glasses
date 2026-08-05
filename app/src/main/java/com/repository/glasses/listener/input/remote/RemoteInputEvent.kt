package com.repository.glasses.listener.input.remote

/**
 * The device-agnostic remote input vocabulary.
 *
 * Nothing in this file may reference Android, Bluetooth, keycodes, or `MainActivity.FocusState`.
 * That is what makes a second input device (a direct BLE gadget, another phone, a USB dongle) a
 * matter of writing one new [InputSource] rather than editing the UI.
 *
 * ## Actions, not gestures
 *
 * Every value below is what the user MEANT, never what their finger did. Gesture recognition is the
 * SOURCE's job and happens on the source, where the recognition delay is free; interpreting the
 * resulting action against the current UI is this side's job. Nothing here or downstream may try to
 * reconstruct a gesture from a sequence of actions.
 *
 * ## Extension axes
 * - **New DEVICE**: implement [InputSource], register it from `ListenerService`. Zero changes here,
 *   zero changes in `RemoteInputRouter`, zero changes in `MainActivity`.
 * - **New GESTURE on an existing device**: entirely the source's business, as long as it resolves to
 *   one of the actions below. Zero changes anywhere in this repo.
 * - **New ACTION**: that is a new UI capability, not a new device. It requires adding a
 *   [RemoteAction] value AND teaching `MainActivity` what it means. That is deliberate and is
 *   expected to go through review -- it is not a failure of the abstraction.
 */
enum class RemoteAction {
    /** A scroll detent, or several coalesced into one. Direction lives in the sign of `delta`. */
    SCROLL_STEP,

    /**
     * Select / enter. A SEMANTIC action: the user asked to activate what is focused.
     *
     * The source decided this. On the watch it is what one tap resolves to, after the watch waited
     * out its own double-tap window locally. The glasses do NOT re-derive gestures from it: there is
     * no arrival-time arithmetic, no deferral, and no path from two of these to a [BACK].
     *
     * That split is what makes the abstraction real. A source with a bezel, a source with a real
     * back button and a source with a voice trigger all emit the same three actions, and the UI
     * needs no knowledge of any of their gesture vocabularies.
     */
    SELECT,

    /**
     * Back / exit. A SEMANTIC action: the user asked to leave where they are.
     *
     * Produced by whatever affordance the source has for it -- a dedicated button, or, on the watch,
     * a locally recognised double tap. What produced it is the source's business.
     */
    BACK,
}

/**
 * One actionable remote input, after the router has authenticated, de-duplicated and age-checked it.
 * This is the ONLY type that crosses the router -> sink boundary.
 *
 * Immutable: the main-thread queue merges bursts by replacing entries, never by mutating one the
 * drain loop may already hold.
 */
data class RemoteInputEvent(
    val action: RemoteAction,
    /**
     * Signed detent count for [RemoteAction.SCROLL_STEP]; `+` = forward/down, `-` = back/up.
     * Always `0` for SELECT and BACK.
     */
    val delta: Int,
    /** Which [InputSource] produced this. */
    val sourceId: String,
    /** Session id, as minted by the source. */
    val sid: Long,
    /** Monotonic sequence within `(sourceId, sid)`. */
    val seq: Long,
    /**
     * How long this event spent in flight, in milliseconds, measured entirely on the SOURCE's clock
     * (its own `wms` minus the session's OPEN baseline, compared against arrival). Never a
     * cross-device clock comparison, and never a value the transport could freeze while queueing.
     */
    val ageMs: Int,
    /**
     * Milliseconds between this event and the previous one from the same session, measured on the
     * SOURCE's clock. `-1` when there is no predecessor (first event of a session).
     *
     * This is what makes tap disambiguation immune to transport jitter. Two taps a user made 350 ms
     * apart can easily ARRIVE 420 ms apart -- coalescing, a queue stall, and a BLE connection
     * interval all add delay, and none of it is uniform -- so timing them by arrival would silently
     * turn a deliberate double tap into two singles. The source stamped both at the moment the
     * finger landed, so the interval between those stamps is the user's real intent.
     */
    val sinceLastMs: Int,
) {
    companion object {
        /** [sinceLastMs] when this is the first event of a session. */
        const val NO_PREDECESSOR = -1
    }
}

/**
 * Session lifecycle signals. These never reach the sink -- the router consumes them to manage
 * per-source session state. Keeping them on the source -> router edge (rather than inventing extra
 * [RemoteAction] values) is what lets the router own `lastSeq` exclusively, with no second owner.
 */
enum class RemoteLifecycle {
    /** Start of a session. Resets the sequence baseline and establishes the age baseline. */
    OPEN,

    /** Orderly end of a session. */
    CLOSE,

    /** Liveness. Keeps a session from expiring; carries no input. */
    PING,
}

/** What an [InputSource] hands to the router: either an action or a lifecycle signal. */
sealed interface RemoteInputFrame {
    val v: Int
    val sourceId: String
    val sid: Long
    val seq: Long

    /** Source-clock timestamp, low 32 bits of the source's monotonic clock. */
    val wms: Long

    data class Action(
        override val v: Int,
        override val sourceId: String,
        override val sid: Long,
        override val seq: Long,
        override val wms: Long,
        val action: RemoteAction,
        val delta: Int,
    ) : RemoteInputFrame

    data class Lifecycle(
        override val v: Int,
        override val sourceId: String,
        override val sid: Long,
        override val seq: Long,
        override val wms: Long,
        val kind: RemoteLifecycle,
    ) : RemoteInputFrame
}

/**
 * Status pushed back to a source so it can tell the user why nothing is happening.
 * Device-agnostic on purpose: no keycodes, no `FocusState`, no transport detail.
 */
data class RemoteInputStatus(
    /** A session for this source is currently open on the glasses. */
    val sessionOpen: Boolean,
    /** A UI sink is attached, i.e. events will actually be acted on rather than dropped. */
    val sinkAttached: Boolean,
    /** Cumulative events dropped for this source since the router was created. */
    val droppedTotal: Long,
    /**
     * The UI refused an action recently, and why.
     *
     * Distinct from [droppedTotal], which counts events the TRANSPORT discarded (no
     * session, rate limit, TTL). This counts events that arrived intact and were then
     * declined by the UI. The two have opposite remedies -- one is a link problem, the
     * other is "you are folded" or "go back first" -- and collapsing them is what let
     * the watch say "Connected" while every event was being refused.
     *
     * Null when nothing has been refused recently.
     */
    val refusal: RemoteRefusal? = null,
)

/**
 * Why the glasses UI is declining input, in terms a remote device can render.
 *
 * Deliberately coarse: the watch needs to distinguish "wake your glasses" from "you
 * cannot do that here", because those are the two cases with different user actions.
 * Finer reasons would be noise on a 1-inch screen.
 */
enum class RemoteRefusalReason {
    /** The action is not permitted in the current UI state. Go back, or do it here. */
    NOT_ALLOWED,

    /** The glasses are folded. Unfold them. */
    FOLDED,

    /** A call, recording or reply owns the UI. Wait, or finish it on the glasses. */
    LOCKED,
}

/**
 * A refusal, with the count so a source can tell a fresh refusal from a stale one
 * without needing a synchronized clock.
 */
data class RemoteRefusal(
    val reason: RemoteRefusalReason,
    /** Cumulative refusals for this source since the router was created. */
    val total: Long,
    /** Elapsed-realtime ms on the GLASSES when the most recent refusal happened. */
    val atWms: Long,
)
