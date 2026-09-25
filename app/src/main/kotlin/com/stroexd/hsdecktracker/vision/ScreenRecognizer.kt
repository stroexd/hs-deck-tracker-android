package com.stroexd.hsdecktracker.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.stroexd.hsdecktracker.core.vision.OcrFrame
import com.stroexd.hsdecktracker.core.vision.OcrLine
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Nimmt den Bildschirm per MediaProjection auf und erkennt Texte mit ML Kit (on-device).
 * Etwa zwei Bilder pro Sekunde werden ausgewertet – nur im Querformat (Hearthstone).
 *
 * @param maskProvider Bereich des eigenen Overlays in Bildschirmkoordinaten, der ignoriert wird.
 * @param onFrame wird auf einem eigenen Thread aufgerufen; die Bitmap ist nur während des Aufrufs gültig.
 */
class ScreenRecognizer(
    private val context: Context,
    private val projection: MediaProjection,
    private val maskProvider: () -> Rect?,
    private val onFrame: (OcrFrame, Bitmap) -> Unit,
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("hs-screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val worker = Executors.newSingleThreadExecutor()

    // Nach stop() eintreffende Ergebnisse werden verworfen statt abgelehnt.
    private val listenerExecutor = Executor { command -> runCatching { worker.execute(command) } }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var lastFrameAt = 0L

    fun start() {
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stop()
                }
            },
            handler,
        )
        val (realWidth, realHeight) = realDisplaySize()
        // Hearthstone läuft im Querformat → virtuelles Display im Querformat anlegen.
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

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        handler.post {
            runCatching { virtualDisplay?.release() }
            runCatching { reader?.close() }
            virtualDisplay = null
            reader = null
            runCatching { projection.stop() }
            worker.execute {
                runCatching { recognizer.close() }
            }
            worker.shutdown()
            thread.quitSafely()
            onStopped()
        }
    }

    private fun realDisplaySize(): Pair<Int, Int> {
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

    private fun onImageAvailable(imageReader: ImageReader) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        val now = SystemClock.elapsedRealtime()
        val (realWidth, realHeight) = realDisplaySize()
        val portrait = realWidth < realHeight
        if (stopped.get() || portrait || now - lastFrameAt < INTERVAL_MS || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameAt = now
        val bitmap = try {
            image.toBitmap()
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
        if (bitmap == null) {
            busy.set(false)
            return
        }
        val mask = maskProvider()?.let { scaleMask(it, bitmap.width, bitmap.height, realWidth, realHeight) }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener(listenerExecutor) { text ->
                try {
                    onFrame(toFrame(text, bitmap.width, bitmap.height, mask), bitmap)
                } catch (e: Exception) {
                    // Fehler in der Auswertung dürfen die Aufnahme nicht beenden.
                } finally {
                    bitmap.recycle()
                    busy.set(false)
                }
            }
            .addOnFailureListener(listenerExecutor) {
                bitmap.recycle()
                busy.set(false)
            }
    }

    private fun scaleMask(mask: Rect, frameWidth: Int, frameHeight: Int, realWidth: Int, realHeight: Int): Rect {
        val sx = frameWidth.toFloat() / realWidth
        val sy = frameHeight.toFloat() / realHeight
        return Rect(
            (mask.left * sx).toInt() - MASK_MARGIN,
            (mask.top * sy).toInt() - MASK_MARGIN,
            (mask.right * sx).toInt() + MASK_MARGIN,
            (mask.bottom * sy).toInt() + MASK_MARGIN,
        )
    }

    private fun toFrame(text: Text, width: Int, height: Int, mask: Rect?): OcrFrame {
        val w = width.toFloat()
        val h = height.toFloat()
        val lines = ArrayList<OcrLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (mask != null && Rect.intersects(mask, box)) continue
                lines += OcrLine(line.text, box.left / w, box.top / h, box.right / w, box.bottom / h)
            }
        }
        return OcrFrame(System.currentTimeMillis(), lines)
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private companion object {
        const val MAX_WIDTH = 2000
        const val INTERVAL_MS = 450L
        const val MASK_MARGIN = 8
    }
}
