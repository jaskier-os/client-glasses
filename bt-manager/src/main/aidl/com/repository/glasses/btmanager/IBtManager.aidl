package com.repository.glasses.btmanager;

import com.repository.glasses.btmanager.IBtManagerCallback;

interface IBtManager {
    void registerCallback(IBtManagerCallback callback);
    void unregisterCallback(IBtManagerCallback callback);

    boolean isBluetoothEnabled();
    String getAdapterAddress();
    String getAdapterName();
    String getBondedDevicesJson();

    void setDiscoverable(int durationSeconds);
    boolean isDiscoverable();

    void startAdvertising(String tag, String serviceUuid, boolean includeDeviceName);
    void stopAdvertising(String tag);
    boolean isAdvertising(String tag);

    String connectRfcommOutbound(String deviceAddress, String uuid);
    String listenRfcommInbound(String serviceName, String uuid);
    void closeRfcommSocket(String socketId);
    void writeRfcommSocket(String socketId, in byte[] data);
    boolean isRfcommConnected(String socketId);
    String getActiveConnectionsJson();

    String getCallSnapshotJson();
    boolean acceptCall(String deviceAddress, int flag);
    boolean rejectCall(String deviceAddress);
    boolean terminateCall(String deviceAddress, int callId);
    String getPrimaryHfpDeviceAddress();
    boolean setHfMicMute(String deviceAddress, boolean muted);

    /**
     * Hold the A2DP Sink link to deviceAddress DOWN. Enforced continuously --
     * remote/auto reconnects are re-dropped for as long as a lease is held.
     *
     * This is a LEASE, not a latch, because no client-side cleanup can survive the
     * client process being killed. The hold expires by itself unless renewed, so it
     * is structurally impossible for A2DP to stay suppressed once the holder stops
     * running. Three independent releases exist: explicit release, lease expiry,
     * and death of [owner] (linkToDeath, which is the instant path).
     *
     * @param owner a binder the caller keeps alive for the lifetime of the hold.
     * @return an opaque token, or null if the request was rejected.
     */
    String acquireA2dpSuppression(String deviceAddress, long leaseMs, IBinder owner);
    /** Extend the lease. Oneway: renewal is a heartbeat, the caller must never block on it. */
    oneway void renewA2dpSuppression(String token);
    /** Drop the hold and actively restore the link. Oneway: teardown must never block. */
    oneway void releaseA2dpSuppression(String token);
    boolean isA2dpSuppressed(String deviceAddress);

    void setActiveSession(String label);
    void clearActiveSession(String label);
    boolean isAnySessionActive();

    boolean notifyPhone(byte eventCode, long epochNanos);
    boolean notifyPhoneWithData(byte eventCode, byte data, long epochNanos);

    void sendBleHello();
}
