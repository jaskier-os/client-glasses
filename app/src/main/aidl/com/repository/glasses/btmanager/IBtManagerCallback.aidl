package com.repository.glasses.btmanager;

interface IBtManagerCallback {
    void onAdapterStateChanged(int state);
    void onBondStateChanged(String address, String name, int prevState, int newState);
    void onAdvertisingStarted(String tag);
    void onAdvertisingFailed(String tag, int errorCode);
    void onAdvertisingStopped(String tag);
    void onRfcommConnected(String socketId, String address, String name);
    void onRfcommDisconnected(String socketId);
    void onRfcommData(String socketId, in byte[] data);
    void onRfcommError(String socketId, String error);
    void onDeviceConnected(String address, String name, int transport);
    void onDeviceDisconnected(String address, int transport);
    void onCallStateChanged(String deviceAddress, int callId, String uuid, int state,
                            String number, String name, boolean multiParty, boolean outgoing);
    void onCallAudioStateChanged(String deviceAddress, int audioState);
    void onHfpConnectionChanged(String deviceAddress, int state);
    void onA2dpConnectionChanged(String deviceAddress, int prevState, int newState);
    void onA2dpAudioStateChanged(String deviceAddress, int state);
}
