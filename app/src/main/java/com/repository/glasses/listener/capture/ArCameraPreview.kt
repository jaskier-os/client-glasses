package com.repository.glasses.listener.capture

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView

/**
 * Manages Camera2 preview rendering to a TextureView for AR screen recording.
 * The screen recorder captures whatever is displayed, so showing the camera feed
 * on the TextureView makes the recording contain actual video content.
 */
class ArCameraPreview(private val context: Context) {

    var remoteLog: ((String) -> Unit)? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    @Volatile
    var isRunning = false
        private set

    fun start(textureView: TextureView, onReady: () -> Unit) {
        if (isRunning) {
            remoteLog?.invoke("ArCameraPreview: Already running")
            onReady()
            return
        }

        cameraThread = HandlerThread("ArCameraPreview").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        if (textureView.isAvailable) {
            openCamera(textureView, onReady)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera(textureView, onReady)
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    stop()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    private fun openCamera(textureView: TextureView, onReady: () -> Unit) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCamera(manager)
        if (cameraId == null) {
            remoteLog?.invoke("ArCameraPreview: No camera found")
            onReady()
            return
        }

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    remoteLog?.invoke("ArCameraPreview: Camera opened: $cameraId")
                    cameraDevice = camera
                    startPreview(camera, textureView, onReady)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    remoteLog?.invoke("ArCameraPreview: Camera disconnected")
                    camera.close()
                    cameraDevice = null
                    isRunning = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    remoteLog?.invoke("ArCameraPreview: Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    isRunning = false
                    onReady()
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            remoteLog?.invoke("ArCameraPreview: Camera permission denied: ${e.message}")
            onReady()
        } catch (e: Exception) {
            remoteLog?.invoke("ArCameraPreview: Failed to open camera: ${e.message}")
            onReady()
        }
    }

    private fun startPreview(camera: CameraDevice, textureView: TextureView, onReady: () -> Unit) {
        val surfaceTexture = textureView.surfaceTexture ?: run {
            remoteLog?.invoke("ArCameraPreview: SurfaceTexture not available")
            onReady()
            return
        }

        // Set buffer size to match display
        surfaceTexture.setDefaultBufferSize(textureView.width, textureView.height)
        val surface = Surface(surfaceTexture)

        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        previewBuilder.addTarget(surface)
                        previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                        session.setRepeatingRequest(previewBuilder.build(), null, cameraHandler)
                        isRunning = true
                        remoteLog?.invoke("ArCameraPreview: Camera preview started")
                        onReady()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        remoteLog?.invoke("ArCameraPreview: Capture session config failed")
                        isRunning = false
                        onReady()
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            remoteLog?.invoke("ArCameraPreview: Failed to create preview session: ${e.message}")
            onReady()
        }
    }

    private fun findCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK ||
                facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                return id
            }
        }
        return manager.cameraIdList.firstOrNull()
    }

    fun stop() {
        isRunning = false
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        remoteLog?.invoke("ArCameraPreview: Camera preview stopped")
    }
}
