package com.rokid.os.sprite.assist.client;

interface IAssistClient {
    void onRegisterResult(String resultJson);
    boolean onMessageReceive(String messageJson);
    void onDataReceive(String key, String param, in byte[] data);
}
