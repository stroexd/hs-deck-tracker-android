package com.stroexd.hsdecktracker.vision

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.annotation.RequiresApi
import kotlin.math.max
import kotlin.math.min

/** A screenshot; [width] can be smaller than the bitmap when rows are padded. */
class Capture(val bitmap: Bitmap, val width: Int, val height: Int)

/** Where screenshots come from. All calls and callbacks happen on the recognizer's thread. */
interface FrameSource {
    /** [onStopped] is called when the source ends by itself, e.g. screen sharing was stopped. */
    fun start(handler: Handler, onStopped: () -> Unit)

    fun capture(onCapture: (Capture?) -> Unit)

    fun stop()
}

fun realDisplaySize(context: Context): Pair<Int, Int> {
    val windowManager = context.getSystemService(WindowManager::class.java)
    return if (Build.VERSION.SDK_INT >= 30) {
        val bounds = windowManager.maximumWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        metrics.widthPixels to metrics.heightPixels
    }
}

/**
 * Screen sharing. The virtual display is only attached while a frame is taken instead of rendering 60+ frames a
 * second; devices where re-attaching doesn't deliver frames fall back to staying attached.
 */
class MediaProjectionSource(private val context: Context, private val projection: MediaProjection) : FrameSource {
    private lateinit var handler: Handler
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var pending: ((Capture?) -> Unit)? = null
    private var detachBetweenFrames = true
    private var reattachWorks = false
    private var reattached = false
    private var timeouts = 0
    private var bitmap: Bitmap? = null
    private val timeout = Runnable { onTimeout() }

    override fun start(handler: Handler, onStopped: () -> Unit) {
        this.handler = handler
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() = onStopped()
            },
            handler,
        )
        val (realWidth, realHeight) = realDisplaySize(context)
        val landscapeWidth = max(realWidth, realHeight)
        val landscapeHeight = min(realWidth, realHeight)
        val scale = min(1f, MAX_WIDTH.toFloat() / landscapeWidth)
        val width = ((landscapeWidth * scale).toInt() / 2) * 2
        val height = ((landscapeHeight * scale).toInt() / 2) * 2
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader.setOnImageAvailableListener({ onImageAvailable(it) }, handler)
        reader = imageReader
        virtualDisplay = projection.createVirtualDisplay(
            "hs-deck-tracker",
            width,
            height,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler,
        )
    }

    override fun capture(onCapture: (Capture?) -> Unit) {
        val imageReader = reader ?: return onCapture(null)
        pending = onCapture
        if (detachBetweenFrames) {
            runCatching { imageReader.acquireLatestImage()?.close() }
            virtualDisplay?.surface = imageReader.surface
            reattached = true
        }
        handler.postDelayed(timeout, FRAME_TIMEOUT_MS)
    }

    override fun stop() {
        handler.removeCallbacks(timeout)
        pending = null
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        virtualDisplay = null
        reader = null
        bitmap = null
        runCatching { projection.stop() }
    }

    private fun onTimeout() {
        val callback = pending ?: return
        timeouts++
        if (!reattachWorks && timeouts >= MAX_TIMEOUTS) {
            detachBetweenFrames = false
            virtualDisplay?.surface = reader?.surface
            return
        }
        pending = null
        virtualDisplay?.surface = null
        callback(null)
    }

    private fun onImageAvailable(imageReader: ImageReader) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        val callback = pending
        if (callback == null) {
            image.close()
            return
        }
        pending = null
        handler.removeCallbacks(timeout)
        timeouts = 0
        if (reattached) reattachWorks = true
        if (detachBetweenFrames) virtualDisplay?.surface = null
        val capture = try {
            Capture(copyToBitmap(image), image.width, image.height)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
        callback(capture)
    }

    private fun copyToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val bitmapWidth = plane.rowStride / plane.pixelStride
        val current = bitmap?.takeIf { it.width == bitmapWidth && it.height == image.height && !it.isRecycled }
        val target = current ?: Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        plane.buffer.rewind()
        target.copyPixelsFromBuffer(plane.buffer)
        return target
    }

    private companion object {
        const val MAX_WIDTH = 2000
        const val FRAME_TIMEOUT_MS = 700L
        const val MAX_TIMEOUTS = 3
    }
}

/** Screenshots through the app's accessibility service: no screen-sharing prompt per session (Android 11+). */
@RequiresApi(30)
class AccessibilityScreenshotSource(private val service: AccessibilityService) : FrameSource {
    private lateinit var handler: Handler
    private var last: Bitmap? = null

    override fun start(handler: Handler, onStopped: () -> Unit) {
        this.handler = handler
    }

    override fun capture(onCapture: (Capture?) -> Unit) {
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            { command -> handler.post(command) },
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val copy = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hardware ->
                            hardware.copy(Bitmap.Config.ARGB_8888, false).also { hardware.recycle() }
                        }
                    } catch (e: Exception) {
                        null
                    } finally {
                        buffer.close()
                    }
                    // The previous screenshot is done with: captures never overlap
                    last?.recycle()
                    last = copy
                    onCapture(copy?.let { Capture(it, it.width, it.height) })
                }

                override fun onFailure(errorCode: Int) = onCapture(null)
            },
        )
    }

    override fun stop() {
        last = null
    }
}
