package com.repository.glasses.listener.input.remote

/**
 * A device that can drive the glasses UI remotely.
 *
 * This is THE extension point. Adding a new input device -- a direct BLE gadget, a second phone, a
 * USB dongle -- means writing one class that implements this interface and registering it from
 * `ListenerService`. It requires no change to `RemoteInputRouter` and, critically, **no change to
 * `MainActivity`**.
 *
 * An implementation owns exactly one thing: turning whatever its transport delivers into
 * [RemoteInputFrame]s. It owns no session state, no sequence numbers, no rate limiting and no
 * staleness policy -- those all live in the router, so every source gets them identically and for
 * free.
 */
interface InputSource {
    /**
     * Stable identifier for this device, e.g. `"watch"`.
     *
     * Must match `[a-z0-9_]{1,16}` and must be unique among registered sources. The router uses it
     * to namespace sessions, sequence numbers and rate limits, and it is the source allowlist:
     * a frame naming an unregistered source is rejected. An implementation must reject any inbound
     * frame whose `src` field is not this value before emitting it.
     */
    val sourceId: String

    /**
     * Begin delivering frames to [sink]. Called by the router on registration.
     *
     * The sink may be invoked from any thread; the router is responsible for its own
     * synchronization. Implementations should NOT do expensive work (crypto, IO) on a transport
     * callback thread that also serves other features -- hand off first.
     */
    fun attach(sink: (RemoteInputFrame) -> Unit)

    /** Stop delivering frames and release transport resources. Must be idempotent. */
    fun detach()

    /**
     * Router -> source status push, so the device can tell its user why input is being ignored
     * (no session, or no UI attached because the glasses screen is not active).
     *
     * Default no-op: a source that has no back channel simply ignores it.
     */
    fun onStatus(status: RemoteInputStatus) {}
}

/**
 * The UI's side of remote input.
 *
 * **This interface has exactly one method, permanently.** Every decision about what a remote event
 * MEANS -- which keycode it becomes, whether the current focus state permits it, how tab navigation
 * is driven -- belongs in the implementation, not in this interface and not in the router. A second
 * method here would mean the router had started making UI decisions again, which is the design
 * regression this shape exists to prevent. If you think you need one, raise it for review.
 *
 * Called on the main thread of the implementing process.
 */
interface RemoteInputSink {
    fun onRemoteInput(e: RemoteInputEvent)
}
