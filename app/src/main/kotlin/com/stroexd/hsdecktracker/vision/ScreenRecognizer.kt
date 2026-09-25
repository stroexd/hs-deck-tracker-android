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
import android.os.PowerManager
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Takt der Bildschirmauswertung.
 * @param intervalMillis Abstand zwischen zwei Bildschirmfotos.
 * @param maxReuseMillis so lange darf ein unverändertes Bild das letzte Texterkennungs-Ergebnis wiederverwenden.
 */
data class CapturePacing(val intervalMillis: Long, val maxReuseMillis: Long)

/**
 * Nimmt den Bildschirm per MediaProjection auf und erkennt Texte mit ML Kit (on-device) – nur im
 * Querformat (Hearthstone) und bei eingeschaltetem Bildschirm.
 *
 * Sparsam mit dem Akku:
 * - Das virtuelle Display ist zwischen zwei Fotos abgekoppelt (wie ein ausgeschalteter Bildschirm),
 *   statt 60–120 Bilder pro Sekunde zu rendern, von denen nur wenige gebraucht werden.
 * - Der Takt kommt von außen ([pacing]) – außerhalb einer Partie deutlich seltener; im Stromsparmodus
 *   bzw. bei hoher Gerätetemperatur zusätzlich langsamer.
 * - Hat sich das Bild kaum verändert (grober Helligkeitsabdruck), wird die teure Texterkennung
 *   übersprungen und das letzte Ergebnis wiederverwendet.
 * - Eine einzige Bitmap wird wiederverwendet statt pro Bild neu angelegt.
 *
 * @param maskProvider Bereich des eigenen Overlays in Bildschirmkoordinaten, der ignoriert wird.
 * @param onFrame wird immer auf demselben Hintergrund-Thread aufgerufen; die Bitmap ist nur während des
 *   Aufrufs gültig. `reused` = Ergebnis ohne neue Texterkennung (Bild unverändert).
 */
class ScreenRecognizer(
    private val context: Context,
    private val projection: MediaProjection,
    private val maskProvider: () -> Rect?,
    private val pacing: () -> CapturePacing,
    private val onFrame: (frame: OcrFrame, bitmap: Bitmap, reused: Boolean) -> Unit,
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("hs-screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val worker = Executors.newSingleThreadExecutor()

    // Nach stop() eintreffende Ergebnisse werden verworfen statt abgelehnt.
    private val listenerExecutor = Executor { command -> runCatching { worker.execute(command) } }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val stopped = AtomicBoolean(false)
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // Nur auf dem Aufnahme-Thread verwendet
    private var awaitingFrame = false
    private var captureStartedAt = 0L
    private var detachBetweenFrames = true
    private var reattachWorks = false
    private var reattached = false
    private var timeouts = 0
    private var bitmap: Bitmap? = null
    private var lastPrint: IntArray? = null
    private var lastLines: List<OcrLine>? = null
    private var lastOcrAt = 0L

    private val captureTask = Runnable { capture() }
    private val frameTimeout = Runnable { onFrameTimeout() }

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
        // Das erste Bild wird sofort ausgewertet, danach bestimmt der Takt die Aufnahmen.
        awaitingFrame = true
        captureStartedAt = SystemClock.elapsedRealtime()
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
        handler.postDelayed(frameTimeout, FRAME_TIMEOUT_MS)
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        handler.post {
            handler.removeCallbacks(captureTask)
            handler.removeCallbacks(frameTimeout)
            runCatching { virtualDisplay?.release() }
            runCatching { reader?.close() }
            virtualDisplay = null
            reader = null
            runCatching { projection.stop() }
            val lastBitmap = bitmap
            bitmap = null
            worker.execute {
                runCatching { recognizer.close() }
                lastBitmap?.recycle()
            }
            worker.shutdown()
            thread.quitSafely()
            onStopped()
        }
    }

    // ------------------------------------------------------------------ Takt

    private fun scheduleNext() {
        if (stopped.get()) return
        val interval = effectiveInterval(pacing().intervalMillis)
        val wait = (captureStartedAt + interval - SystemClock.elapsedRealtime()).coerceAtLeast(MIN_GAP_MS)
        handler.removeCallbacks(captureTask)
        handler.postDelayed(captureTask, wait)
    }

    /** Stromsparmodus bzw. hohe Temperatur → seltener auswerten. */
    private fun effectiveInterval(base: Long): Long {
        var factor = 1.0
        if (powerManager?.isPowerSaveMode == true) factor *= 1.5
        if (Build.VERSION.SDK_INT >= 29 && (powerManager?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE) factor *= 2
        return (base * factor).toLong()
    }

    private fun capture() {
        if (stopped.get()) return
        captureStartedAt = SystemClock.elapsedRealtime()
        val (realWidth, realHeight) = realDisplaySize()
        // Bildschirm aus oder Hochformat (nicht Hearthstone): nichts aufnehmen, später erneut prüfen
        if (powerManager?.isInteractive == false || realWidth < realHeight) {
            handler.postDelayed(captureTask, INACTIVE_CHECK_MS)
            return
        }
        awaitingFrame = true
        if (detachBetweenFrames) {
            val imageReader = reader ?: return
            // Veraltetes Bild verwerfen, dann Display wieder ankoppeln → liefert ein frisches Bild
            runCatching { imageReader.acquireLatestImage()?.close() }
            virtualDisplay?.surface = imageReader.surface
            reattached = true
            handler.postDelayed(frameTimeout, FRAME_TIMEOUT_MS)
        }
        // Ohne Abkoppeln kommt das nächste Bild ohnehin von selbst.
    }

    /**
     * Kein Bild nach dem Ankoppeln. Bei unverändertem Bildschirm kann das vorkommen – dann gibt es auch
     * nichts Neues zu erkennen. Kam nach dem Ankoppeln aber noch nie ein Bild, unterstützt das Gerät das
     * Abkoppeln nicht: Dann bleibt das Display dauerhaft angekoppelt (sicherer Rückfall).
     */
    private fun onFrameTimeout() {
        if (!awaitingFrame || stopped.get()) return
        timeouts++
        if (!reattachWorks && timeouts >= MAX_TIMEOUTS) {
            detachBetweenFrames = false
            virtualDisplay?.surface = reader?.surface
            return // bleibt angekoppelt und wartet auf das nächste Bild
        }
        awaitingFrame = false
        virtualDisplay?.surface = null
        scheduleNext()
    }

    // ------------------------------------------------------------------ Auswertung

    private fun onImageAvailable(imageReader: ImageReader) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        if (stopped.get() || !awaitingFrame) {
            image.close()
            return
        }
        awaitingFrame = false
        handler.removeCallbacks(frameTimeout)
        timeouts = 0
        if (reattached) reattachWorks = true
        if (detachBetweenFrames) virtualDisplay?.surface = null
        process(image)
    }

    private fun process(image: Image) {
        val (realWidth, realHeight) = realDisplaySize()
        val width = image.width
        val height = image.height
        val mask = maskProvider()?.let { scaleMask(it, width, height, realWidth, realHeight) }
        val print = try {
            fingerprint(image, mask)
        } catch (e: Exception) {
            image.close()
            scheduleNext()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val previousLines = lastLines
        val previousPrint = lastPrint
        val reusable = previousLines != null && previousPrint != null &&
            now - lastOcrAt < pacing().maxReuseMillis && isSimilar(print, previousPrint)
        val target = try {
            if (reusable) bitmap else copyToBitmap(image)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
        if (target == null) {
            scheduleNext()
            return
        }
        val aspect = width.toFloat() / height
        if (reusable && previousLines != null) {
            // Bild unverändert → letztes Ergebnis mit neuem Zeitstempel, ohne Texterkennung
            val frame = OcrFrame(System.currentTimeMillis(), previousLines, aspect)
            listenerExecutor.execute {
                runCatching { onFrame(frame, target, true) }
                handler.post { scheduleNext() }
            }
            return
        }
        recognizer.process(InputImage.fromBitmap(target, 0))
            .addOnSuccessListener(listenerExecutor) { text ->
                val frame = toFrame(text, width, height, aspect, mask)
                // Auswertungsfehler dürfen die Aufnahme nicht beenden.
                runCatching { onFrame(frame, target, false) }
                handler.post {
                    lastLines = frame.lines
                    lastPrint = print
                    lastOcrAt = now
                    scheduleNext()
                }
            }
            .addOnFailureListener(listenerExecutor) {
                handler.post { scheduleNext() }
            }
    }

    /**
     * Grober Helligkeitsabdruck (Raster aus Zellen, je 4 Stichproben). Zellen im Overlay-Bereich
     * werden ausgelassen, damit sich ändernde Overlay-Inhalte keine Texterkennung auslösen.
     */
    private fun fingerprint(image: Image, mask: Rect?): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val cellWidth = image.width / GRID_X
        val cellHeight = image.height / GRID_Y
        val result = IntArray(GRID_X * GRID_Y)
        for (gy in 0 until GRID_Y) {
            for (gx in 0 until GRID_X) {
                val index = gy * GRID_X + gx
                val left = gx * cellWidth
                val top = gy * cellHeight
                if (mask != null && mask.intersects(left, top, left + cellWidth, top + cellHeight)) {
                    result[index] = -1
                    continue
                }
                var sum = 0
                for (sy in 1..2) {
                    for (sx in 1..2) {
                        val offset = (top + cellHeight * sy / 3) * rowStride + (left + cellWidth * sx / 3) * pixelStride
                        val r = buffer.get(offset).toInt() and 0xFF
                        val g = buffer.get(offset + 1).toInt() and 0xFF
                        val b = buffer.get(offset + 2).toInt() and 0xFF
                        sum += (r * 54 + g * 183 + b * 19) shr 8
                    }
                }
                result[index] = sum / 4
            }
        }
        return result
    }

    /** Nur wenige Zellen verändert (z. B. flackerndes Feuer am Spielfeldrand) → gleiches Bild. */
    private fun isSimilar(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        var changed = 0
        for (i in a.indices) {
            if (a[i] < 0 || b[i] < 0) continue
            if (abs(a[i] - b[i]) > CELL_THRESHOLD && ++changed > MAX_CHANGED_CELLS) return false
        }
        return true
    }

    /** Kopiert das Bild in die wiederverwendete Bitmap (inkl. Zeilenpolster rechts). */
    private fun copyToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val bitmapWidth = plane.rowStride / plane.pixelStride
        val current = bitmap?.takeIf { it.width == bitmapWidth && it.height == image.height && !it.isRecycled }
        val target = current ?: Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888).also {
            bitmap?.recycle()
            bitmap = it
        }
        plane.buffer.rewind()
        target.copyPixelsFromBuffer(plane.buffer)
        return target
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

    private fun toFrame(text: Text, width: Int, height: Int, aspect: Float, mask: Rect?): OcrFrame {
        val w = width.toFloat()
        val h = height.toFloat()
        val lines = ArrayList<OcrLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                // Zeilenpolster rechts neben dem eigentlichen Bild enthält keine Inhalte
                if (box.left >= width) continue
                if (mask != null && Rect.intersects(mask, box)) continue
                lines += OcrLine(line.text, box.left / w, box.top / h, min(box.right.toFloat(), w) / w, box.bottom / h)
            }
        }
        return OcrFrame(System.currentTimeMillis(), lines, aspect = aspect)
    }

    private companion object {
        const val MAX_WIDTH = 2000
        const val MASK_MARGIN = 8
        const val MIN_GAP_MS = 50L
        const val INACTIVE_CHECK_MS = 3_000L
        const val FRAME_TIMEOUT_MS = 700L
        const val MAX_TIMEOUTS = 3
        const val GRID_X = 48
        const val GRID_Y = 27
        const val CELL_THRESHOLD = 20
        const val MAX_CHANGED_CELLS = 6
    }
}
