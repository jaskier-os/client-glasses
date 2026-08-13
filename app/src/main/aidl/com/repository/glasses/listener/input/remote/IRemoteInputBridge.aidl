package com.repository.glasses.listener.input.remote;

import com.repository.glasses.listener.input.remote.IRemoteInputSink;
import com.repository.glasses.listener.arstream.IHudSurfaceSink;

/**
 * The `:backend` end of the remote-input bridge, handed to the UI process from
 * `ListenerService.onBind` when the bind Intent carries {@link #ACTION}.
 *
 * Registration is a two-step handshake rather than a push from the backend because only the UI
 * process knows when it actually has a live, resumed `MainActivity` to act on events.
 */
interface IRemoteInputBridge {
    /**
     * Attach `sink` as THE remote-input sink, replacing any previous one.
     *
     * Registration is keyed on `sink.asBinder()`, not on the interface object: AIDL unmarshals a
     * fresh `Stub.Proxy` wrapper on every transaction, so two calls carrying the same remote object
     * produce two non-equal interface instances but the same canonical `IBinder`.
     */
    void registerSink(IRemoteInputSink sink);

    /**
     * Detach `sink`, but only if it is still the current one. Passing the instance (rather than
     * offering a bare `clear()`) is what stops a departing screen's teardown, which can run AFTER an
     * arriving screen has already registered, from clearing the newcomer's sink.
     */
    void unregisterSink(IRemoteInputSink sink);

    /**
     * Report that the UI declined an action it received, so the source can explain the
     * silence to the user.
     *
     * The refusal decision is made in the UI process (only it knows the focus state) but
     * the status backchannel lives in `:backend`. Without this call every refusal
     * terminated in a log line on the glasses' internal flash while the watch went on
     * saying "Connected" -- the single biggest UX failure of this feature.
     *
     * `oneway` on purpose: a refusal is already the unhappy path and the UI thread must
     * not block on the backend to report one.
     *
     * @param reasonOrdinal ordinal of RemoteRefusalReason.
     */
    oneway void reportRefusal(int reasonOrdinal);

    /**
     * Attach `sink` as the live AR stream's HUD surface sink, replacing any previous one.
     *
     * Registered through this bridge because it is the binder the UI process already holds; a
     * Surface must travel over binder rather than a broadcast to stay usable in the receiver.
     */
    void registerHudSurfaceSink(IHudSurfaceSink sink);

    /** Detach `sink`, but only if it is still the current one (same rationale as unregisterSink). */
    void unregisterHudSurfaceSink(IHudSurfaceSink sink);
}
