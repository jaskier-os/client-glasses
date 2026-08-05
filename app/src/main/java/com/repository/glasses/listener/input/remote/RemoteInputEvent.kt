package com.repository.glasses.listener.input.remote

/**
 * The device-agnostic remote input vocabulary.
 *
 * Nothing in this file may reference Android, Bluetooth, keycodes, or `MainActivity.FocusState`.
 * That is what makes a second input device (a direct BLE gadget, another phone, a USB dongle) a
 * matter of writing one new [InputSource] rather than editing the UI.
 *
 * ## Extension axes
 * - **New DEVICE**: implement [InputSource], register it from `ListenerService`. Zero changes here,
 *   zero changes in `RemoteInputRouter`, zero changes in `MainActivity`.
 * - **New ACTION** (a 4th gesture): that is a new UI capability, not a new device. It requires
 *   adding a [RemoteAction] value AND teaching `MainActivity` what it means. That is deliberate and
 *   is expected to go through review -- it is not a failure of the abstraction.
 */
enum class RemoteAction {
    /** A scroll detent, or several coalesced into one. Direction lives in the sign of `delta`. */
    SCROLL_STEP,

    /** Confirm / activate the focused item. */
    SELECT,

    /** Navigate up / dismiss. */
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
    /** Wire protocol version. Always [PROTOCOL_VERSION] for anything the router forwards. */
    val v: Int,
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
) {
    companion object {
        const val PROTOCOL_VERSION = 1
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
)
