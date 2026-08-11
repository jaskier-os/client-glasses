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

    void setActiveSession(String label);
    void clearActiveSession(String label);
    boolean isAnySessionActive();

    boolean notifyPhone(byte eventCode, long epochNanos);
    boolean notifyPhoneWithData(byte eventCode, byte data, long epochNanos);

    void sendBleHello();
}
