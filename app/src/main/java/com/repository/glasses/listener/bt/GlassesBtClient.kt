package com.repository.glasses.listener.bt

import android.util.Log
import com.repository.glasses.tracing.GT
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "App:BtClient"

/**
 * Glasses-side BT communication.
 *
 * Uses MessageRelay (direct RFCOMM via BtManager) instead of CXR-S.
 * Sends audio/status/etc to companion phone, receives responses/commands.
 */
class GlassesBtClient(private val relay: MessageRelay) {

    private val connectLock = Any()

    /** BT address of the connected companion phone, or null if not connected. */
    val companionAddress: String? get() = relay.companionAddress

    var remoteLog: ((String) -> Unit)? = null

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onResponse(requestId: String, text: String, status: String, tokenCount: Int)
        fun onTtsAudio(requestId: String, audioBase64: String, sentenceIndex: Int, totalSentences: Int, text: String, isFinal: Boolean)
        fun onCommand(type: String, requestId: String, paramsJson: String)
        fun onSettings(settingsJson: String)
        fun onToolStatus(requestId: String, toolName: String, status: String, toolArgsJson: String, toolCallId: String)
        fun onStreamingText(requestId: String, partialText: String, isFinal: Boolean)
        fun onDismissSession()
        fun onRokidCommand(action: String, paramsJson: String)
        fun onPhoneActivate()
        fun onMapArrow(normX: Float, normY: Float, headingDeg: Float) {}
        fun onChatListResponse(chatsJson: String)
        fun onChatHistory(conversationId: String, turnsJson: String)
        fun onTranslationResult(resultJson: String)
        fun onTranslationConfig(configJson: String)
        /** Authoritative desired translation on/off state from phone. Single JSON arg:
         *  {active, from, to, fromNllb, toNllb, fontSize, twoWay}. */
        fun onTranslationState(stateJson: String) {}
        fun onPartialText(text: String)
        fun onUserText(requestId: String, text: String)
        fun onReidResult(trackingId: String, recognized: Boolean, personUid: String, displayName: String, score: Float)
        fun onOrchestratorStatus(connected: Boolean)
        fun onTodoListResponse(json: String)
        fun onAlarmListResponse(json: String) {}
        fun onJobListResponse(json: String) {}
        fun onNotification(notifId: String, sender: String, text: String, chat: String, repliable: Boolean) {}
        fun onNotificationSetText(notifId: String, fullText: String) {}
        fun onNotifReplyResult(notifId: String, ok: Boolean) {}
        fun onNotificationTtsAudio(notifId: String, audioBase64: String, isFinal: Boolean) {}
        fun onReidMerge(sourcePersonId: String, targetPersonId: String, targetDisplayName: String) {}
        fun onReidBestThumb(personUid: String, imageBase64: String) {}
        fun onReidPersonResponse(personUid: String, personJson: String) {}
        fun onTgChatListResponse(json: String) {}
        fun onTgMessagesResponse(chatId: String, json: String) {}
        fun onTgSendResult(json: String) {}
        fun onTgNewMessage(json: String) {}
        fun onTgTopicsResponse(chatId: String, json: String) {}
        fun onSyncMessage(msgType: String, sessionId: String, payload: List<String>) {}
        /** Sideload session control (CH_SIDELOAD). payloadJson is the single JSON arg. */
        fun onSideloadMessage(payloadJson: String) {}
        fun onWeatherUpdate(icon: String, tempC: String, location: String) {}
        fun onTimeSync(epochMillis: Long, tzId: String) {}
        fun onContactsHash(agMac: String, hash: String) {}
        fun onContactsList(agMac: String, hash: String, json: String) {}
    }

    var listener: Listener? = null

    /** Chunk reassembly, bounded by age and by concurrent-stream count. */
    private val chunkAssembler = ChunkAssembler()

    @Volatile
    var isConnected = false
        private set

    /** Diagnostic: init state */
    var initStatus = "not_started"
        private set

    /**
     * Reassemble chunked JSON. Phone sends: [chunk, isFinal] or [prefix, chunk, isFinal].
     * Buffers chunks per channel until isFinal="1", then calls [onComplete] with the full string.
     * [prefixIndex]: if >= 0, reads a prefix field at that arg index before the chunk.
     */
    private fun handleChunkedJson(
        channel: String,
        args: List<String>,
        prefixIndex: Int = -1,
        onComplete: (prefix: String?, json: String) -> Unit
    ) {
        val done = chunkAssembler.accept(channel, args, prefixIndex) ?: return
        onComplete(done.prefix, done.json)
    }

    private fun dispatch(channel: String, args: List<String>) {
        try {
            when (channel) {
                BtProtocol.CH_RESPONSE -> {
                    val requestId = args.getOrElse(0) { "" }
                    val text = args.getOrElse(1) { "" }
                    val status = args.getOrElse(2) { "" }
                    val tokenCount = args.getOrElse(3) { "0" }.toIntOrNull() ?: 0
                    remoteLog?.invoke("Response received: $requestId status=$status tokens=$tokenCount -> ${text.take(80)}...")
                    listener?.onResponse(requestId, text, status, tokenCount)
                }
                BtProtocol.CH_COMMAND, BtProtocol.CH_COMMAND_RESPONSE, BtProtocol.CH_DEVICE_COMMAND -> {
                    val type = args.getOrElse(0) { "" }
                    val requestId = args.getOrElse(1) { "" }
                    val paramsJson = args.getOrElse(2) { "{}" }
                    remoteLog?.invoke("Command received [$channel]: type=$type requestId=$requestId")
                    listener?.onCommand(type, requestId, paramsJson)
                }
                BtProtocol.CH_TTS_AUDIO -> {
                    val requestId = args.getOrElse(0) { "" }
                    val audioBase64 = args.getOrElse(1) { "" }
                    val sentenceIndex = args.getOrElse(2) { "0" }.toIntOrNull() ?: 0
                    val totalSentences = args.getOrElse(3) { "1" }.toIntOrNull() ?: 1
                    val text = args.getOrElse(4) { "" }
                    val isFinal = args.getOrElse(5) { "" } == "1"
                    remoteLog?.invoke("TTS audio received: $requestId sentence=$sentenceIndex/$totalSentences final=$isFinal")
                    listener?.onTtsAudio(requestId, audioBase64, sentenceIndex, totalSentences, text, isFinal)
                }
                BtProtocol.CH_SETTINGS -> {
                    val settingsJson = args.getOrElse(0) { "{}" }
                    remoteLog?.invoke("Settings received: ${settingsJson.take(100)}")
                    listener?.onSettings(settingsJson)
                }
                BtProtocol.CH_TOOL_STATUS -> {
                    val requestId = args.getOrElse(0) { "" }
                    val toolName = args.getOrElse(1) { "" }
                    val status = args.getOrElse(2) { "" }
                    val toolArgsJson = args.getOrElse(3) { "" }
                    val toolCallId = args.getOrElse(4) { "" }
                    remoteLog?.invoke("Tool status: $requestId $toolName -> $status")
                    listener?.onToolStatus(requestId, toolName, status, toolArgsJson, toolCallId)
                }
                BtProtocol.CH_STREAMING_TEXT -> {
                    val requestId = args.getOrElse(0) { "" }
                    val partialText = args.getOrElse(1) { "" }
                    val isFinal = args.getOrElse(2) { "" } == "1"
                    remoteLog?.invoke("Streaming text: $requestId final=$isFinal len=${partialText.length}")
                    listener?.onStreamingText(requestId, partialText, isFinal)
                }
                BtProtocol.CH_DISMISS_SESSION -> {
                    remoteLog?.invoke("Dismiss session received from phone")
                    listener?.onDismissSession()
                }
                BtProtocol.CH_ROKID_COMMAND -> {
                    val action = args.getOrElse(0) { "" }
                    val params = args.getOrElse(1) { "{}" }
                    remoteLog?.invoke("Rokid command received: action=$action")
                    listener?.onRokidCommand(action, params)
                }
                BtProtocol.CH_ACTIVATE -> {
                    remoteLog?.invoke("Phone activate command received")
                    listener?.onPhoneActivate()
                }
                BtProtocol.CH_MAP_ARROW -> {
                    val normX = args.getOrElse(0) { "0" }.toFloatOrNull() ?: 0f
                    val normY = args.getOrElse(1) { "0" }.toFloatOrNull() ?: 0f
                    val headingDeg = args.getOrElse(2) { "0" }.toFloatOrNull() ?: 0f
                    listener?.onMapArrow(normX, normY, headingDeg)
                }
                BtProtocol.CH_HEALTH_PING -> {
                    relay.publish(BtProtocol.CH_HEALTH_PONG, "pong")
                }
                BtProtocol.CH_CHAT_LIST_RESPONSE -> {
                    handleChunkedJson(BtProtocol.CH_CHAT_LIST_RESPONSE, args) { _, json ->
                        remoteLog?.invoke("Chat list response received: ${json.length} chars")
                        listener?.onChatListResponse(json)
                    }
                }
                BtProtocol.CH_CHAT_HISTORY -> {
                    handleChunkedJson(BtProtocol.CH_CHAT_HISTORY, args, prefixIndex = 0) { prefix, turnsJson ->
                        remoteLog?.invoke("Chat history received for $prefix: ${turnsJson.length} chars")
                        listener?.onChatHistory(prefix ?: "", turnsJson)
                    }
                }
                BtProtocol.CH_TRANSLATION_RESULT -> {
                    val resultJson = args.getOrElse(0) { "{}" }
                    remoteLog?.invoke("Translation result received: ${resultJson.take(100)}")
                    listener?.onTranslationResult(resultJson)
                }
                BtProtocol.CH_TRANSLATION_CONFIG -> {
                    val configJson = args.getOrElse(0) { "{}" }
                    remoteLog?.invoke("Translation config received: $configJson")
                    listener?.onTranslationConfig(configJson)
                }
                BtProtocol.CH_TRANSLATION_STATE -> {
                    val stateJson = args.getOrElse(0) { "{}" }
                    remoteLog?.invoke("Translation state received: $stateJson")
                    listener?.onTranslationState(stateJson)
                }
                BtProtocol.CH_GLASSES_PARTIAL_TEXT -> {
                    val text = args.getOrElse(0) { "" }
                    listener?.onPartialText(text)
                }
                BtProtocol.CH_GLASSES_USER_TEXT -> {
                    val requestId = args.getOrElse(0) { "" }
                    val text = args.getOrElse(1) { "" }
                    remoteLog?.invoke("User text received: $requestId -> ${text.take(80)}")
                    listener?.onUserText(requestId, text)
                }
                BtProtocol.CH_REID_RESULT -> {
                    val trackingId = args.getOrElse(0) { "" }
                    val recognizedStr = args.getOrElse(1) { "" }
                    val recognized = recognizedStr == "1" || recognizedStr == "true"
                    val personUid = args.getOrElse(2) { "" }
                    val displayName = args.getOrElse(3) { "" }
                    val score = args.getOrElse(4) { "0" }.toFloatOrNull() ?: 0f
                    remoteLog?.invoke("Reid result: trackingId=$trackingId recognized=$recognized uid=$personUid name=$displayName score=$score")
                    listener?.onReidResult(trackingId, recognized, personUid, displayName, score)
                }
                BtProtocol.CH_REID_MERGE -> {
                    val sourcePersonId = args.getOrElse(0) { "" }
                    val targetPersonId = args.getOrElse(1) { "" }
                    val targetDisplayName = args.getOrElse(2) { "" }
                    remoteLog?.invoke("Reid merge: $sourcePersonId -> $targetPersonId ($targetDisplayName)")
                    listener?.onReidMerge(sourcePersonId, targetPersonId, targetDisplayName)
                }
                BtProtocol.CH_REID_BEST_THUMB -> {
                    val personUid = args.getOrElse(0) { "" }
                    val imageBase64 = args.getOrElse(1) { "" }
                    remoteLog?.invoke("Reid best thumb: uid=$personUid (${imageBase64.length} chars)")
                    listener?.onReidBestThumb(personUid, imageBase64)
                }
                BtProtocol.CH_REID_PERSON_RESP -> {
                    val personUid = args.getOrElse(0) { "" }
                    val personJson = args.getOrElse(1) { "{}" }
                    remoteLog?.invoke("Reid person response: uid=$personUid (${personJson.length} chars)")
                    listener?.onReidPersonResponse(personUid, personJson)
                }
                BtProtocol.CH_TODO_LIST_RESP -> {
                    handleChunkedJson(BtProtocol.CH_TODO_LIST_RESP, args) { _, json ->
                        remoteLog?.invoke("Todo list response received: ${json.length} chars")
                        listener?.onTodoListResponse(json)
                    }
                }
                BtProtocol.CH_ALARM_LIST_RESP -> {
                    handleChunkedJson(BtProtocol.CH_ALARM_LIST_RESP, args) { _, json ->
                        remoteLog?.invoke("Alarm list response received: ${json.length} chars")
                        listener?.onAlarmListResponse(json)
                    }
                }
                BtProtocol.CH_JOB_LIST_RESP -> {
                    handleChunkedJson(BtProtocol.CH_JOB_LIST_RESP, args) { _, json ->
                        remoteLog?.invoke("Job list response received: ${json.length} chars")
                        listener?.onJobListResponse(json)
                    }
                }
                BtProtocol.CH_NOTIFICATION -> {
                    val notifId = args.getOrElse(0) { "" }
                    val sender = args.getOrElse(1) { "" }
                    val text = args.getOrElse(2) { "" }
                    val chat = args.getOrElse(3) { "" }
                    val repliable = args.getOrElse(4) { "0" } == "1"
                    Log.d(TAG, "event=notification id=$notifId sender=$sender len=${text.length} repliable=$repliable")
                    remoteLog?.invoke("Notification: $notifId $sender ${text.take(40)} repliable=$repliable")
                    listener?.onNotification(notifId, sender, text, chat, repliable)
                }
                BtProtocol.CH_NOTIFICATION_SETTEXT -> {
                    val notifId = args.getOrElse(0) { "" }
                    val fullText = args.getOrElse(1) { "" }
                    Log.d(TAG, "event=notification_settext id=$notifId len=${fullText.length}")
                    remoteLog?.invoke("Notification settext: $notifId ${fullText.take(40)}")
                    listener?.onNotificationSetText(notifId, fullText)
                }
                BtProtocol.CH_NOTIF_REPLY_RESULT -> {
                    val notifId = args.getOrElse(0) { "" }
                    val ok = args.getOrElse(1) { "0" } == "1"
                    Log.d(TAG, "event=notif_reply_result id=$notifId ok=$ok")
                    remoteLog?.invoke("Notif reply result: $notifId ok=$ok")
                    listener?.onNotifReplyResult(notifId, ok)
                }
                BtProtocol.CH_NOTIFICATION_TTS -> {
                    val notifId = args.getOrElse(0) { "" }
                    val audioBase64 = args.getOrElse(1) { "" }
                    val isFinal = args.getOrElse(2) { "" } == "1"
                    remoteLog?.invoke("Notification TTS: $notifId (${audioBase64.length} chars, final=$isFinal)")
                    listener?.onNotificationTtsAudio(notifId, audioBase64, isFinal)
                }
                BtProtocol.CH_SYSTEM_STATUS -> {
                    val connected = args.getOrElse(0) { "" } == "1"
                    listener?.onOrchestratorStatus(connected)
                }
                BtProtocol.CH_TG_CHAT_LIST_RESP -> {
                    handleChunkedJson(BtProtocol.CH_TG_CHAT_LIST_RESP, args) { _, json ->
                        remoteLog?.invoke("TG chat list response: ${json.length} chars")
                        listener?.onTgChatListResponse(json)
                    }
                }
                BtProtocol.CH_TG_MESSAGES_RESP -> {
                    handleChunkedJson(BtProtocol.CH_TG_MESSAGES_RESP, args, prefixIndex = 0) { chatId, json ->
                        remoteLog?.invoke("TG messages response: chatId=$chatId ${json.length} chars")
                        listener?.onTgMessagesResponse(chatId ?: "", json)
                    }
                }
                BtProtocol.CH_TG_SEND_RESP -> {
                    val json = args.getOrElse(0) { "{}" }
                    remoteLog?.invoke("TG send result: ${json.take(80)}")
                    listener?.onTgSendResult(json)
                }
                BtProtocol.CH_TG_NEW_MESSAGE -> {
                    handleChunkedJson(BtProtocol.CH_TG_NEW_MESSAGE, args) { _, json ->
                        remoteLog?.invoke("TG new message: ${json.take(80)}")
                        listener?.onTgNewMessage(json)
                    }
                }
                BtProtocol.CH_TG_TOPICS_RESP -> {
                    handleChunkedJson(BtProtocol.CH_TG_TOPICS_RESP, args, prefixIndex = 0) { chatId, json ->
                        remoteLog?.invoke("TG topics response: $chatId ${json.take(80)}")
                        listener?.onTgTopicsResponse(chatId ?: "", json)
                    }
                }
                BtProtocol.CH_SYNC -> {
                    // args[0] = msgType, args[1] = sessionId, args[2..] = payload
                    val msgType = args.getOrElse(0) { "" }
                    val sessionId = args.getOrElse(1) { "0" }
                    val payload = if (args.size > 2) args.subList(2, args.size) else emptyList()
                    remoteLog?.invoke("Sync msg: $msgType session=$sessionId payloadArgs=${payload.size}")
                    listener?.onSyncMessage(msgType, sessionId, payload)
                }
                BtProtocol.CH_SIDELOAD -> {
                    val payloadJson = args.getOrElse(0) { "{}" }
                    remoteLog?.invoke("Sideload msg: ${payloadJson.take(80)}")
                    listener?.onSideloadMessage(payloadJson)
                }
                BtProtocol.CH_TIME_SYNC -> {
                    val epoch = args.getOrElse(0) { "0" }.toLongOrNull() ?: 0L
                    val tz = args.getOrElse(1) { "" }
                    remoteLog?.invoke("TimeSync received: epochMs=$epoch tz='$tz'")
                    listener?.onTimeSync(epoch, tz)
                }
                BtProtocol.CH_WEATHER -> {
                    val icon = args.getOrElse(0) { "" }
                    val temp = args.getOrElse(1) { "0" }
                    val loc = args.getOrElse(2) { "" }
                    remoteLog?.invoke("Weather update: icon=$icon temp=$temp loc=$loc")
                    listener?.onWeatherUpdate(icon, temp, loc)
                }
                BtProtocol.CH_CONTACTS -> {
                    val op = args.getOrElse(0) { "" }
                    when (op) {
                        "HASH" -> {
                            val agMac = args.getOrElse(1) { "" }
                            val hash = args.getOrElse(2) { "" }
                            remoteLog?.invoke("Contacts HASH: agMac=$agMac hash=${hash.take(12)}")
                            listener?.onContactsHash(agMac, hash)
                        }
                        "LIST" -> {
                            // Wire: ["LIST", agMac, hash, jsonChunk, isFinal("0"/"1")]
                            val agMac = args.getOrElse(1) { "" }
                            // The hash sits where a prefix would, so the assembler carries it.
                            val done = chunkAssembler.accept(BtProtocol.CH_CONTACTS, args, prefixIndex = 2)
                            if (done != null) {
                                remoteLog?.invoke("Contacts LIST complete: ${done.json.length} chars")
                                listener?.onContactsList(agMac, done.prefix ?: "", done.json)
                            }
                        }
                        else -> remoteLog?.invoke("Contacts: unknown op '$op'")
                    }
                }
                else -> {
                    if (channel.startsWith("listener_")) {
                        remoteLog?.invoke("Unhandled channel: $channel (${args.size} args)")
                    }
                }
            }
        } catch (e: Exception) {
            remoteLog?.invoke("Failed to dispatch $channel: ${e.message}")
        }
    }

    fun initialize() = GT.section("bt.client.initialize") {
        try {
            relay.remoteLog = remoteLog
            relay.listener = object : MessageRelay.Listener {
                override fun onConnected() {
                    remoteLog?.invoke("BT connected (MessageRelay)")
                    synchronized(connectLock) {
                        isConnected = true
                        listener?.onConnected()
                    }
                }

                override fun onDisconnected() {
                    remoteLog?.invoke("BT disconnected (MessageRelay)")
                    isConnected = false
                    chunkAssembler.clear()
                    listener?.onDisconnected()
                }

                override fun onMessage(channel: String, args: List<String>) {
                    dispatch(channel, args)
                }
            }
            relay.start()
            initStatus = "OK(MessageRelay)"
            remoteLog?.invoke("Initialized: $initStatus")
        } catch (e: Exception) {
            initStatus = "CRASH:${e.message}"
            remoteLog?.invoke("initialize() CRASHED: ${e.message}")
        }
    }

    fun sendStatus(state: String) {
        if (!relay.publish(BtProtocol.CH_STATUS, state)) {
            remoteLog?.invoke("sendStatus($state): relay not connected, dropped (snapshot will reconcile on next connect)")
        }
    }

    /**
     * Authoritative glasses-side state, sent on every (re)connect so phone
     * reconciles even if individual CH_STATUS messages were dropped while the
     * relay was disconnected.
     */
    fun sendStateSnapshot(state: String, requestId: String?) {
        if (!relay.publish(BtProtocol.CH_STATE_SNAPSHOT, state, requestId ?: "")) {
            remoteLog?.invoke("sendStateSnapshot($state, $requestId): relay not connected, dropped")
        }
    }

    fun sendTtsInterrupt(requestId: String) {
        relay.publish(BtProtocol.CH_TTS_INTERRUPT, requestId)
    }

    fun sendCommandResponse(requestId: String, commandType: String, imageBase64: String? = null, text: String? = null) {
        relay.publish(BtProtocol.CH_COMMAND_RESPONSE, requestId, commandType, imageBase64 ?: "", text ?: "")
    }

    fun sendCommandResult(requestId: String, resultJson: String) {
        remoteLog?.invoke("sendCommandResult($requestId): ${resultJson.length} chars")
        if (!relay.publish(BtProtocol.CH_COMMAND, "command_result", requestId, resultJson)) {
            remoteLog?.invoke("sendCommandResult FAILED ($requestId): not connected, payload=${resultJson.length} chars")
        }
    }

    fun sendCommand(command: String, vararg args: String) {
        relay.publish(BtProtocol.CH_COMMAND, command, *args)
    }

    /** Send a 6-byte mouse HID report (base64-encoded) to the phone for BT Classic HID forwarding. */
    fun sendMouseReport(base64Report: String): Boolean {
        return relay.publish(BtProtocol.CH_MOUSE_REPORT, base64Report)
    }

    /** Ask phone to push the full contacts list. Triggered when our cache hash differs. */
    fun requestContactsFull(agMac: String): Boolean {
        return relay.publish(BtProtocol.CH_CONTACTS, "REQUEST_FULL", agMac)
    }

    /**
     * Publish a sync message on the listener_sync channel.
     * Wire format: [msgType, sessionId, payload...]. See SyncChannelHandler.
     */
    fun sendSync(msgType: String, sessionId: String, vararg payload: String): Boolean {
        return relay.publish(BtProtocol.CH_SYNC, msgType, sessionId, *payload)
    }

    /**
     * Publish a sideload-session control message on the listener_sideload channel.
     * Wire format: a single JSON arg, e.g. {"t":"WIFI_READY","details":{...}}.
     * See SideloadChannelHandler.
     */
    fun sendSideload(payloadJson: String): Boolean {
        return relay.publish(BtProtocol.CH_SIDELOAD, payloadJson)
    }

    /**
     * Publish a wake-word event on the listener_wake_event channel.
     * Wire format: [confidence, epochNanos]. Phone-side handler: handleWakeEventFromGlasses.
     */
    fun sendGlassesCommand(type: String, params: JSONObject = JSONObject()) {
        val payload = JSONArray().put(type).put(params)
        relay.publish(BtProtocol.CH_GLASSES_COMMAND, payload.toString())
    }

    fun sendWakeEvent(confidence: Float, epochNanos: Long): Boolean {
        return relay.publish(BtProtocol.CH_WAKE_EVENT, confidence.toString(), epochNanos.toString())
    }

    /** Send large data to phone in 2K chunks: [command, chunk, isFinal("0"/"1")] */
    fun sendChunked(command: String, data: String, onProgress: ((Int) -> Unit)? = null) = GT.section("bt.send_chunked.$command") {
        GT.counter("bt.chunk_bytes", data.length.toLong())
        try {
            val maxChunk = 2_000
            if (data.length <= maxChunk) {
                relay.publish(BtProtocol.CH_COMMAND, command, data, "1")
                onProgress?.invoke(100)
            } else {
                var start = 0
                var count = 0
                val total = data.length
                while (start < total) {
                    val end = minOf(start + maxChunk, total)
                    relay.publish(BtProtocol.CH_COMMAND, command, data.substring(start, end), if (end >= total) "1" else "0")
                    if (end < total) Thread.sleep(20)
                    start = end
                    count++
                    onProgress?.invoke((start * 100) / total)
                }
                remoteLog?.invoke("sendChunked($command): $total chars -> $count chunks")
            }
        } catch (e: Exception) {
            remoteLog?.invoke("sendChunked FAILED ($command): ${e.message}")
        }
    }

    fun sendChatListRequest() {
        relay.publish(BtProtocol.CH_CHAT_LIST_REQUEST, "list")
    }

    fun sendSwitchChat(conversationId: String) {
        relay.publish(BtProtocol.CH_SWITCH_CHAT, conversationId)
    }

    fun sendNewChat() {
        relay.publish(BtProtocol.CH_NEW_CHAT, "new")
    }

    /** Send Base64-encoded PCM audio chunk to phone. Returns 0 on success, -1 on failure. */
    fun sendAudioData(b64Pcm: String): Int {
        return if (relay.publish(BtProtocol.CH_AUDIO_DATA, b64Pcm)) 0 else -1
    }

    /** Send Base64-encoded inward mic (wearer's voice) PCM chunk to phone. */
    fun sendInwardAudioData(b64Pcm: String): Int {
        return if (relay.publish(BtProtocol.CH_AUDIO_DATA_INWARD, b64Pcm)) 0 else -1
    }

    /** Send Base64-encoded, Opus-compressed HFP call downlink (far-party) chunk to phone. */
    fun sendCallAudioData(b64Opus: String): Int {
        return if (relay.publish(BtProtocol.CH_AUDIO_DATA_CALL, b64Opus)) 0 else -1
    }

    /** Relay HFP SCO call-audio state to phone so it can flip its translation sub-source. */
    fun sendCallState(scoActive: Boolean): Boolean {
        return relay.publish(BtProtocol.CH_CALL_STATE, if (scoActive) "1" else "0")
    }

    fun sendTodoListRequest() {
        relay.publish(BtProtocol.CH_TODO_LIST_REQ, "list")
    }

    fun sendTodoToggle(id: String) {
        relay.publish(BtProtocol.CH_TODO_TOGGLE, id)
    }

    fun sendTodoAdd(text: String) {
        relay.publish(BtProtocol.CH_TODO_ADD, text)
    }

    fun sendTodoRemove(id: String) {
        relay.publish(BtProtocol.CH_TODO_REMOVE, id)
    }

    fun sendAlarmListRequest() {
        relay.publish(BtProtocol.CH_ALARM_LIST_REQ, "req")
    }

    fun sendJobListRequest() {
        relay.publish(BtProtocol.CH_JOB_LIST_REQ, "req")
    }

    fun sendNotificationDone(notifId: String) {
        if (relay.publish(BtProtocol.CH_NOTIFICATION_DONE, notifId)) {
            remoteLog?.invoke("Sent CH_NOTIFICATION_DONE: $notifId")
        } else {
            remoteLog?.invoke("Failed to send notification done (not connected)")
        }
    }

    fun sendNotifReplyStart(notifId: String) {
        if (relay.publish(BtProtocol.CH_NOTIF_REPLY_START, notifId)) {
            remoteLog?.invoke("Sent CH_NOTIF_REPLY_START: $notifId")
        } else {
            remoteLog?.invoke("Failed to send notif reply start (not connected)")
        }
    }

    fun sendNotifReplySend(notifId: String, text: String) {
        if (relay.publish(BtProtocol.CH_NOTIF_REPLY_SEND, notifId, text)) {
            remoteLog?.invoke("Sent CH_NOTIF_REPLY_SEND: $notifId -> ${text.take(40)}")
        } else {
            remoteLog?.invoke("Failed to send notif reply send (not connected)")
        }
    }

    fun sendNotifReplyCancel(notifId: String) {
        if (relay.publish(BtProtocol.CH_NOTIF_REPLY_CANCEL, notifId)) {
            remoteLog?.invoke("Sent CH_NOTIF_REPLY_CANCEL: $notifId")
        } else {
            remoteLog?.invoke("Failed to send notif reply cancel (not connected)")
        }
    }

    fun sendAudioDuck(duck: Boolean) {
        if (!relay.publish(BtProtocol.CH_AUDIO_DUCK, if (duck) "1" else "0")) {
            remoteLog?.invoke("Failed to send audio duck (not connected)")
        }
    }

    fun sendWearState(worn: Boolean) {
        if (!relay.publish(BtProtocol.CH_WEAR_STATE, if (worn) "1" else "0")) {
            remoteLog?.invoke("Failed to send wear state (not connected)")
        } else {
            remoteLog?.invoke("Sent CH_WEAR_STATE: worn=$worn")
        }
    }

    fun sendScreenState(on: Boolean) {
        if (!relay.publish(BtProtocol.CH_SCREEN_STATE, if (on) "1" else "0")) {
            remoteLog?.invoke("Failed to send screen state (not connected)")
        } else {
            remoteLog?.invoke("Sent CH_SCREEN_STATE: on=$on")
        }
    }

    fun sendReidFace(trackingId: Int, webpBase64: String): Int {
        return if (relay.publish(BtProtocol.CH_REID_FACE, trackingId.toString(), webpBase64)) 0 else -1
    }

    fun sendReidPersonRequest(personUid: String): Int {
        return if (relay.publish(BtProtocol.CH_REID_PERSON_REQ, personUid)) 0 else -1
    }

    fun sendTgChatListRequest(limit: Int = 20) {
        android.util.Log.d("TG_DEBUG", "BtClient: sendTgChatListRequest limit=$limit")
        relay.publish(BtProtocol.CH_TG_CHAT_LIST_REQ, limit.toString())
    }

    fun sendTgMessagesRequest(chatId: String, limit: Int = 30, topicId: Int = 0, offsetId: Int = 0) {
        relay.publish(BtProtocol.CH_TG_MESSAGES_REQ, chatId, limit.toString(), topicId.toString(), offsetId.toString())
    }

    fun sendTgSendRequest(chatId: String, text: String, topicId: Int = 0) {
        relay.publish(BtProtocol.CH_TG_SEND_REQ, chatId, text, topicId.toString())
    }

    fun sendTgTopicsRequest(chatId: String) {
        relay.publish(BtProtocol.CH_TG_TOPICS_REQ, chatId)
    }

    fun sendTgSubscribe() {
        relay.publish(BtProtocol.CH_TG_SUBSCRIBE, "subscribe")
    }

    fun sendTgUnsubscribe() {
        relay.publish(BtProtocol.CH_TG_UNSUBSCRIBE, "unsubscribe")
    }

    fun sendTgOpenChat(chatId: String, chatTitle: String) {
        relay.publish(BtProtocol.CH_TG_OPEN_CHAT, chatId, chatTitle)
    }

    fun sendTgCloseChat() {
        relay.publish(BtProtocol.CH_TG_CLOSE_CHAT, "close")
    }

    fun sendTgVoiceStart(chatId: String) {
        relay.publish(BtProtocol.CH_TG_VOICE_START, chatId)
    }

    fun sendTgVoiceStop() {
        relay.publish(BtProtocol.CH_TG_VOICE_STOP, "stop")
    }

    fun release() {
        isConnected = false
        chunkAssembler.clear()
        relay.listener = null
        relay.stop()
    }

    /** Alias for release, for lifecycle clarity. */
    fun shutdown() = release()
}
