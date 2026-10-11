package com.jarvis.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * CameraHelper using CameraX
 * Supports silent, high-efficiency photo capture for Gemini Vision
 */
class CameraHelper(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    suspend fun initCamera(lifecycleOwner: LifecycleOwner, useFrontCamera: Boolean = false): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider

                    val cameraSelector = if (useFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(android.view.Surface.ROTATION_0)
                        .build()

                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

                    continuation.resume(true)
                } catch (e: Exception) {
                    continuation.resume(false)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    suspend fun capturePhotoBytes(): ByteArray? {
        val capture = imageCapture ?: return null
        return suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)

                            // Decode bitmap and rotate according to orientation
                            val rotationDegrees = image.imageInfo.rotationDegrees
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            image.close()

                            val rotatedBitmap = if (rotationDegrees != 0 && bitmap != null) {
                                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            } else {
                                bitmap
                            }

                            // Scale down if large to optimize Gemini latency
                            val scaled = if (rotatedBitmap != null && (rotatedBitmap.width > 1280 || rotatedBitmap.height > 1280)) {
                                val scale = 1280f / Math.max(rotatedBitmap.width, rotatedBitmap.height)
                                Bitmap.createScaledBitmap(
                                    rotatedBitmap,
                                    (rotatedBitmap.width * scale).toInt(),
                                    (rotatedBitmap.height * scale).toInt(),
                                    true
                                )
                            } else {
                                rotatedBitmap
                            }

                            val outStream = ByteArrayOutputStream()
                            scaled?.compress(Bitmap.CompressFormat.JPEG, 85, outStream)
                            continuation.resume(outStream.toByteArray())
                        } catch (e: Exception) {
                            continuation.resume(null)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resume(null)
                    }
                }
            )
        }
    }
}
