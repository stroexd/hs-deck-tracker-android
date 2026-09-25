package com.stroexd.hsdecktracker.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.stroexd.hsdecktracker.core.tracker.GameEvent
import com.stroexd.hsdecktracker.core.util.AppJson
import com.stroexd.hsdecktracker.core.vision.OcrFrame
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Speichert erkannte Texte (und gelegentlich ein verkleinertes Bildschirmfoto) lokal,
 * damit sich die Erkennung für das eigene Gerät nachvollziehen und verbessern lässt.
 * Die Daten verlassen das Gerät nur, wenn der Nutzer sie selbst teilt.
 */
class DiagnosticsRecorder(root: File) {
    private val sessionDir = File(root, "sitzung-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())).apply { mkdirs() }
    private val ocrFile = File(sessionDir, "ocr.jsonl")
    private val eventsFile = File(sessionDir, "events.txt")
    private var frames = 0
    private var images = 0

    private var lastImageFrame = -IMAGE_EVERY

    fun record(frame: OcrFrame, events: List<GameEvent>, notes: List<String>, bitmap: Bitmap) {
        if (frames >= MAX_FRAMES) return
        frames++
        ocrFile.appendText(AppJson.encodeToString(OcrFrame.serializer(), frame) + "\n")
        if (events.isNotEmpty() || notes.isNotEmpty()) {
            eventsFile.appendText(
                buildString {
                    notes.forEach { append("${frame.timestamp}: · ").append(it).append('\n') }
                    if (events.isNotEmpty()) append("${frame.timestamp}: ${events.joinToString()}\n")
                },
            )
        }
        // Regelmäßig ein Bild, zusätzlich bei Ereignissen (aber nicht bei jedem Bild einer Serie)
        val periodic = frames - lastImageFrame >= IMAGE_EVERY
        val eventImage = events.isNotEmpty() && frames - lastImageFrame >= 3
        if (images < MAX_IMAGES && (periodic || eventImage)) {
            images++
            lastImageFrame = frames
            val scale = IMAGE_WIDTH.toFloat() / bitmap.width
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, IMAGE_WIDTH, (bitmap.height * scale).toInt(), true) else bitmap
            FileOutputStream(File(sessionDir, "bild-%04d.jpg".format(frames))).use { scaled.compress(Bitmap.CompressFormat.JPEG, 60, it) }
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    companion object {
        private const val MAX_FRAMES = 6000
        private const val MAX_IMAGES = 200
        private const val IMAGE_EVERY = 12
        private const val IMAGE_WIDTH = 1024

        fun root(context: Context) = File(context.filesDir, "diagnose")

        fun hasData(context: Context): Boolean = root(context).walkTopDown().any { it.isFile }

        fun clear(context: Context) {
            root(context).deleteRecursively()
        }

        /** Packt alle Aufzeichnungen in eine ZIP-Datei und öffnet den Teilen-Dialog. */
        fun share(context: Context): Boolean {
            val root = root(context)
            val files = root.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) return false
            val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
            val zip = File(sharedDir, "hs-tracker-diagnose.zip")
            ZipOutputStream(FileOutputStream(zip)).use { out ->
                for (file in files) {
                    out.putNextEntry(ZipEntry(file.relativeTo(root).path))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "HS Deck Tracker – Diagnose")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Diagnose teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
    }
}
