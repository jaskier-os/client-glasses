package com.repository.glasses.listener.input.remote;

import com.repository.glasses.listener.input.remote.IRemoteInputSink;

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
}
