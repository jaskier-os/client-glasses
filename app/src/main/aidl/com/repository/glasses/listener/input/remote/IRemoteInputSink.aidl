package com.repository.glasses.listener.input.remote;

/**
 * The UI process's end of the remote-input bridge.
 *
 * `MainActivity` lives in the default process; the Bluetooth frames that feed remote input arrive in
 * `:backend` (see AndroidManifest `android:process=":backend"` on `.service.ListenerService`). The
 * two do NOT share memory, so the in-process `RemoteInputSink` interface cannot be handed across --
 * a direct `router.setSink(activity)` would compile and then silently drop every event.
 *
 * Fields are passed as primitives rather than a Parcelable on purpose: it keeps the parcel tiny
 * (this call sits directly in the scroll path) and avoids a second class that both processes would
 * have to keep in sync with `RemoteInputEvent`.
 *
 * `oneway` so the backend's drain thread never blocks on the UI process. Oneway transactions from a
 * SINGLE calling thread to a SINGLE target binder are delivered in order, which is why the backend
 * must drain from exactly one thread.
 */
oneway interface IRemoteInputSink {
    /**
     * @param action      ordinal of `RemoteAction`.
     * @param delta       signed detent count; 0 for non-scroll actions.
     * @param sourceId    the producing `InputSource`'s id.
     * @param sid         session id as minted by the source.
     * @param seq         monotonic sequence within (sourceId, sid).
     * @param ageMs       in-flight age on the SOURCE's clock.
     * @param sinceLastMs gap to the previous event on the SOURCE's clock, or -1.
     * @param emitNanos   `SystemClock.elapsedRealtimeNanos()` at the moment the backend made this
     *                    call. Used only to measure the added IPC latency; never for logic, because
     *                    it is a glasses-side clock and would reintroduce arrival-time timing.
     */
    void deliver(
        int action,
        int delta,
        String sourceId,
        long sid,
        long seq,
        int ageMs,
        int sinceLastMs,
        long emitNanos);
}
