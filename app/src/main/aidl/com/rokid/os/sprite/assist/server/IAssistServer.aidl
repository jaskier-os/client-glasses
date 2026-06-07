package com.rokid.os.sprite.assist.server;

import com.rokid.os.sprite.assist.client.IAssistClient;

interface IAssistServer {
    void registerClient(String packageName, IAssistClient client);
    void unRegisterClient(String packageName);
    void controlMsgJson(String packageName, String json);
}
