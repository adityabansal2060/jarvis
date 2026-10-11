package com.jarvis.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * ScreenCaptureHelper using MediaProjection
 * Captures the current phone screen cleanly into a JPEG byte array for Gemini Vision
 */
class ScreenCaptureHelper(private val context: Context) {

    suspend fun captureScreenBytes(mediaProjection: MediaProjection): ByteArray? = withContext(Dispatchers.Default) {
        var virtualDisplay: VirtualDisplay? = null
        var imageReader: ImageReader? = null
        var image: Image? = null

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            // Create ImageReader
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "JarvisScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            )

            // Allow display 150ms to render frame
            delay(150)

            image = imageReader.acquireLatestImage()
            if (image == null) {
                delay(100)
                image = imageReader.acquireLatestImage()
            }

            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop out stride padding
                val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)

                // Scale down slightly if QHD/4K to optimize Gemini latency
                val scaled = if (width > 1080) {
                    val scale = 1080f / width
                    Bitmap.createScaledBitmap(cleanBitmap, 1080, (height * scale).toInt(), true)
                } else {
                    cleanBitmap
                }

                val outStream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, outStream)
                return@withContext outStream.toByteArray()
            }

            return@withContext null
        } catch (e: Exception) {
            return@withContext null
        } finally {
            try {
                image?.close()
                virtualDisplay?.release()
                imageReader?.close()
            } catch (_: Exception) {}
        }
    }
}
