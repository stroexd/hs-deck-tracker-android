package com.stroexd.hsdecktracker.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.core.vision.OcrFrame
import com.stroexd.hsdecktracker.core.vision.OcrLine
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

data class CapturePacing(val intervalMillis: Long, val maxReuseMillis: Long)

/** Connects a frame source to the app: recognition, diagnostics and the recognition status. */
fun Context.startScreenRecognition(
    source: FrameSource,
    maskProvider: () -> Rect?,
    isActive: () -> Boolean = { true },
    onStopped: () -> Unit = {},
): ScreenRecognizer {
    val container = appContainer
    val diagnostics = if (container.settings.value.recordDiagnostics) DiagnosticsRecorder(DiagnosticsRecorder.root(this)) else null
    val recognizer = ScreenRecognizer(
        context = this,
        source = source,
        isActive = isActive,
        maskProvider = maskProvider,
        pacing = { container.capturePacing() },
        onFrame = { frame, bitmap, reused ->
            val notes = diagnostics?.let { mutableListOf<String>() }
            val events = container.onScreenFrame(frame, notes, reused)
            diagnostics?.let { runCatching { it.record(frame, events, notes.orEmpty(), bitmap) } }
        },
        onStopped = {
            container.onRecognitionStopped()
            onStopped()
        },
    )
    container.onRecognitionStarted()
    recognizer.start()
    return recognizer
}

/**
 * Takes screenshots at a pace that depends on the game phase and reads them with on-device text recognition.
 * Unchanged screens reuse the last result, and nothing is captured while [isActive] is false, the screen is off
 * or the device is upright (Hearthstone only runs in landscape).
 */
class ScreenRecognizer(
    private val context: Context,
    private val source: FrameSource,
    private val isActive: () -> Boolean,
    private val maskProvider: () -> Rect?,
    private val pacing: () -> CapturePacing,
    private val onFrame: (frame: OcrFrame, bitmap: Bitmap, reused: Boolean) -> Unit,
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("hs-screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val worker = Executors.newSingleThreadExecutor()

    private val listenerExecutor = Executor { command -> runCatching { worker.execute(command) } }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val stopped = AtomicBoolean(false)

    private var captureStartedAt = 0L
    private var lastPrint: IntArray? = null
    private var lastLines: List<OcrLine>? = null
    private var lastOcrAt = 0L

    private val captureTask = Runnable { capture() }

    fun start() {
        handler.post {
            val started = runCatching { source.start(handler) { stop() } }.isSuccess
            if (started) capture() else stop()
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        handler.post {
            handler.removeCallbacks(captureTask)
            runCatching { source.stop() }
            worker.execute { runCatching { recognizer.close() } }
            worker.shutdown()
            thread.quitSafely()
            onStopped()
        }
    }

    private fun scheduleNext() {
        if (stopped.get()) return
        val interval = effectiveInterval(pacing().intervalMillis)
        val wait = (captureStartedAt + interval - SystemClock.elapsedRealtime()).coerceAtLeast(MIN_GAP_MS)
        handler.removeCallbacks(captureTask)
        handler.postDelayed(captureTask, wait)
    }

    private fun effectiveInterval(base: Long): Long {
        var factor = 1.0
        if (powerManager?.isPowerSaveMode == true) factor *= 1.5
        if (Build.VERSION.SDK_INT >= 29 && (powerManager?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE) factor *= 2
        return (base * factor).toLong()
    }

    private fun capture() {
        if (stopped.get()) return
        captureStartedAt = SystemClock.elapsedRealtime()
        val (realWidth, realHeight) = realDisplaySize(context)
        if (powerManager?.isInteractive == false || realWidth < realHeight || !isActive()) {
            handler.postDelayed(captureTask, INACTIVE_CHECK_MS)
            return
        }
        source.capture { capture ->
            if (capture == null || stopped.get()) scheduleNext() else process(capture)
        }
    }

    private fun process(capture: Capture) {
        val (realWidth, realHeight) = realDisplaySize(context)
        val bitmap = capture.bitmap
        val width = capture.width
        val height = capture.height
        val mask = maskProvider()?.let { scaleMask(it, width, height, realWidth, realHeight) }
        val print = try {
            fingerprint(capture, mask)
        } catch (e: Exception) {
            scheduleNext()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val previousLines = lastLines
        val previousPrint = lastPrint
        val aspect = width.toFloat() / height
        if (previousLines != null && previousPrint != null && now - lastOcrAt < pacing().maxReuseMillis && isSimilar(print, previousPrint)) {
            val frame = OcrFrame(System.currentTimeMillis(), previousLines, aspect)
            listenerExecutor.execute {
                runCatching { onFrame(frame, bitmap, true) }
                handler.post { scheduleNext() }
            }
            return
        }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener(listenerExecutor) { text ->
                val frame = toFrame(text, width, height, aspect, mask)
                runCatching { onFrame(frame, bitmap, false) }
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

    /** Brightness of a coarse grid, the overlay left out: enough to notice whether anything changed. */
    private fun fingerprint(capture: Capture, mask: Rect?): IntArray {
        val cellWidth = capture.width / GRID_X
        val cellHeight = capture.height / GRID_Y
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
                        val pixel = capture.bitmap.getPixel(left + cellWidth * sx / 3, top + cellHeight * sy / 3)
                        sum += (((pixel shr 16) and 0xFF) * 54 + ((pixel shr 8) and 0xFF) * 183 + (pixel and 0xFF) * 19) shr 8
                    }
                }
                result[index] = sum / 4
            }
        }
        return result
    }

    private fun isSimilar(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        var changed = 0
        for (i in a.indices) {
            if (a[i] < 0 || b[i] < 0) continue
            if (abs(a[i] - b[i]) > CELL_THRESHOLD && ++changed > MAX_CHANGED_CELLS) return false
        }
        return true
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
                if (box.left >= width) continue
                if (mask != null && Rect.intersects(mask, box)) continue
                lines += OcrLine(line.text, box.left / w, box.top / h, min(box.right.toFloat(), w) / w, box.bottom / h)
            }
        }
        return OcrFrame(System.currentTimeMillis(), lines, aspect = aspect)
    }

    private companion object {
        const val MASK_MARGIN = 8
        const val MIN_GAP_MS = 50L
        const val INACTIVE_CHECK_MS = 3_000L
        const val GRID_X = 48
        const val GRID_Y = 27
        const val CELL_THRESHOLD = 20
        const val MAX_CHANGED_CELLS = 6
    }
}
