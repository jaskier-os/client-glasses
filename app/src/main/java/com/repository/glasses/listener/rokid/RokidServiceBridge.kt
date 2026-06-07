package com.repository.glasses.listener.rokid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.rokid.os.sprite.assist.client.IAssistClient
import com.rokid.os.sprite.assist.server.IAssistServer
import org.json.JSONObject

class RokidServiceBridge(private val context: Context) {

    companion object {
        private const val TAG = "RokidServiceBridge"
        private const val SERVICE_PACKAGE = "com.rokid.os.sprite.assistserver"
        private const val SERVICE_CLASS = "com.rokid.os.sprite.assist.MasterAssistService"
        private const val SCENE_CMD_ACTION = "com.rokid.os.master.assist.server.cmd"
    }

    private var server: IAssistServer? = null
    private val packageName = context.packageName
    var remoteLog: ((String) -> Unit)? = null
    @Volatile
    var isBound = false
        private set

    private val clientStub = object : IAssistClient.Stub() {
        override fun onRegisterResult(resultJson: String?) {
            remoteLog?.invoke("RokidService: register result: $resultJson")
        }

        override fun onMessageReceive(messageJson: String?): Boolean {
            remoteLog?.invoke("RokidService: message (${messageJson?.length ?: 0} chars): ${messageJson?.take(300)}")
            return false // Do not consume -- let Rokid OS handle default actions (photo/video)
        }

        override fun onDataReceive(key: String?, param: String?, data: ByteArray?) {
            remoteLog?.invoke("RokidService: data: key=$key param=${param?.take(50)}")
        }
    }

    var onBound: (() -> Unit)? = null
    private val pendingCommands = mutableListOf<() -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteLog?.invoke("RokidService: connected to $name")
            server = IAssistServer.Stub.asInterface(service)
            isBound = true
            try {
                server?.registerClient(packageName, clientStub)
                remoteLog?.invoke("RokidService: client registered")
            } catch (e: Exception) {
                remoteLog?.invoke("RokidService: register failed: ${e.message}")
            }
            if (pendingCommands.isNotEmpty()) {
                remoteLog?.invoke("RokidService: executing ${pendingCommands.size} pending commands")
                val cmds = pendingCommands.toList()
                pendingCommands.clear()
                cmds.forEach { it() }
            }
            onBound?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteLog?.invoke("RokidService: DISCONNECTED from $name -- reconnecting in 3s")
            server = null
            isBound = false
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ bind() }, 3000)
        }
    }

    fun bind() {
        try {
            val intent = Intent().apply {
                component = ComponentName(SERVICE_PACKAGE, SERVICE_CLASS)
            }
            val result = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            remoteLog?.invoke("RokidService: bindService result=$result")
            if (!result) {
                remoteLog?.invoke("RokidService: bindService returned false, retrying in 5s")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ bind() }, 5000)
            }
        } catch (e: Exception) {
            remoteLog?.invoke("RokidService: bind exception: ${e.message}")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ bind() }, 5000)
        }
    }

    fun unbind() {
        try {
            if (isBound) {
                server?.unRegisterClient(packageName)
            }
        } catch (e: Exception) {
            remoteLog?.invoke("RokidService: unregister failed: ${e.message}")
        }
        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            remoteLog?.invoke("RokidService: unbind failed: ${e.message}")
        }
        server = null
        isBound = false
        remoteLog?.invoke("RokidService: unbound")
    }

    fun sendControlMsg(json: String) {
        val srv = server
        if (srv != null && isBound) {
            srv.controlMsgJson(packageName, json)
            remoteLog?.invoke("RokidService: control msg sent: ${json.take(100)}")
            return
        }
        remoteLog?.invoke("RokidService: not bound, queuing command (${pendingCommands.size + 1} pending)")
        pendingCommands.add {
            try {
                val s = server
                if (s != null && isBound) {
                    s.controlMsgJson(packageName, json)
                    remoteLog?.invoke("RokidService: deferred control msg sent: ${json.take(100)}")
                } else {
                    remoteLog?.invoke("RokidService: still not bound after rebind, command dropped: ${json.take(60)}")
                }
            } catch (e: Exception) {
                remoteLog?.invoke("RokidService: deferred send failed: ${e.message}")
            }
        }
        if (pendingCommands.size == 1) bind() // only rebind on first queued command
    }

    fun sendGattMessage(cmd: String, key: String, data: String) {
        val json = JSONObject().apply {
            put("type", "cmd_phone_gatt_send_data")
            put("data", JSONObject().apply {
                put("cmd", cmd)
                put("key", key)
                put("data", data)
            })
        }.toString()
        sendControlMsg(json)
    }

    fun sendSceneControl(scene: String, open: Boolean) {
        try {
            val intent = Intent(SCENE_CMD_ACTION).apply {
                putExtra("cmd_type", "control_scene")
                putExtra("scene", scene)
                putExtra("open", if (open) "true" else "false")
            }
            context.sendBroadcast(intent)
            remoteLog?.invoke("RokidService: scene control sent: scene=$scene open=$open")
        } catch (e: Exception) {
            remoteLog?.invoke("RokidService: scene control failed: ${e.message}")
        }
    }

    fun startAudioRecord(audioOpenType: String = "audio_no_ui") {
        val json = JSONObject().apply {
            put("type", "cmd_start_audio_record")
            put("data", JSONObject().apply {
                put("audioOpenType", audioOpenType)
            })
        }.toString()
        sendControlMsg(json)
    }

    fun stopAudioRecord(audioOpenType: String = "audio_no_ui") {
        val json = JSONObject().apply {
            put("type", "cmd_stop_audio_record")
            put("data", JSONObject().apply {
                put("audioOpenType", audioOpenType)
            })
        }.toString()
        sendControlMsg(json)
    }

    fun startCxrAudioStream(type: Int = 3, function: String = "listener") {
        val json = JSONObject().apply {
            put("type", "cmd_start_audio_stream")
            put("data", JSONObject().apply {
                put("type", type)
                put("function", function)
                put("data", "")
            })
        }.toString()
        sendControlMsg(json)
    }

    fun startVideoRecord(cameraOpenType: String) {
        val json = JSONObject().apply {
            put("type", "cmd_start_video_record")
            put("data", JSONObject().apply {
                put("cameraOpenType", cameraOpenType)
            })
        }.toString()
        sendControlMsg(json)
    }

    fun stopVideoRecord(cameraOpenType: String) {
        val json = JSONObject().apply {
            put("type", "cmd_stop_video_record")
            put("data", JSONObject().apply {
                put("cameraOpenType", cameraOpenType)
            })
        }.toString()
        sendControlMsg(json)
    }

    fun stopCxrAudioStream(function: String = "listener") {
        val json = JSONObject().apply {
            put("type", "cmd_stop_audio_stream")
            put("data", JSONObject().apply {
                put("function", function)
            })
        }.toString()
        sendControlMsg(json)
    }
}
