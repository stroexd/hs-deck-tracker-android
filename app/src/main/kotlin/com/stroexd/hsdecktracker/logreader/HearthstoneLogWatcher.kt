package com.stroexd.hsdecktracker.logreader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.stroexd.hsdecktracker.AppContainer
import com.stroexd.hsdecktracker.core.tracker.DecksLogParser
import com.stroexd.hsdecktracker.core.tracker.PowerLogParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Experimentell: liest `Power.log` und `Decks.log` von Hearthstone über das Storage Access Framework
 * und speist erkannte Ereignisse in den Tracker ein.
 *
 * Voraussetzung: Hearthstone schreibt Logs (per `log.config`) und der Nutzer hat der App Zugriff
 * auf den Hearthstone-Ordner gewährt. Ab Android 11 ist `Android/data` für andere Apps meist
 * gesperrt – dann funktioniert nur das manuelle Tracking.
 */
class HearthstoneLogWatcher(
    private val context: Context,
    private val container: AppContainer,
    private val treeUri: Uri,
) {
    private val _status = MutableStateFlow("Wird gestartet …")
    val status: StateFlow<String> = _status.asStateFlow()

    private class Tail(val name: String) {
        var uri: Uri? = null
        var offset = 0L
        var partial = ""
        var initialized = false
    }

    private val power = Tail("Power.log")
    private val decks = Tail("Decks.log")
    private var powerParser = PowerLogParser()
    private val decksParser = DecksLogParser()

    suspend fun run() = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null || !root.canRead()) {
            _status.value = "Kein Zugriff auf den gewählten Ordner."
            return@withContext
        }
        ensureLogConfig(root)
        var lastScan = 0L
        while (isActive) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastScan > RESCAN_MILLIS || power.uri == null) {
                    lastScan = now
                    locate(root, power)
                    locate(root, decks)
                    _status.value = if (power.uri == null) {
                        "Power.log nicht gefunden – Hearthstone einmal neu starten, damit Logs geschrieben werden."
                    } else {
                        "Lese Hearthstone-Logs …"
                    }
                }
                readNew(decks) { lines, catchingUp ->
                    lines.forEach { line ->
                        decksParser.parseLine(line)?.let { container.onDeckSelectedInGame(it, catchingUp) }
                    }
                }
                readNew(power) { lines, catchingUp ->
                    lines.forEach { line ->
                        powerParser.parseLine(line).forEach { event -> container.onLogEvent(event, catchingUp) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = "Fehler beim Lesen: ${e.message ?: e.javaClass.simpleName}"
            }
            delay(POLL_MILLIS)
        }
    }

    /** Sucht die neueste Logdatei im gewählten Ordner, in `Logs/` oder in Unterordnern davon. */
    private fun locate(root: DocumentFile, tail: Tail) {
        val candidates = mutableListOf<DocumentFile>()
        fun collect(dir: DocumentFile, depth: Int) {
            for (child in dir.listFiles()) {
                if (child.isFile && child.name == tail.name) candidates += child
                else if (child.isDirectory && depth < 2 && (depth == 0 || child.name?.startsWith("Hearthstone") == true || child.name == "Logs")) {
                    collect(child, depth + 1)
                }
            }
        }
        collect(root, 0)
        val newest = candidates.maxByOrNull { it.lastModified() } ?: return
        if (newest.uri != tail.uri) {
            tail.uri = newest.uri
            tail.offset = 0
            tail.partial = ""
            tail.initialized = false
            if (tail === power) powerParser = PowerLogParser()
        }
    }

    private fun readNew(tail: Tail, consume: (List<String>, Boolean) -> Unit) {
        val uri = tail.uri ?: return
        val catchingUp = !tail.initialized
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                val channel = input.channel
                val size = channel.size()
                if (size < tail.offset) {
                    // Datei wurde neu angelegt (Hearthstone-Neustart)
                    tail.offset = 0
                    tail.partial = ""
                    if (tail === power) powerParser = PowerLogParser()
                }
                if (size == tail.offset) {
                    tail.initialized = true
                    return
                }
                channel.position(tail.offset)
                val toRead = (size - tail.offset).coerceAtMost(MAX_CHUNK.toLong()).toInt()
                val buffer = ByteBuffer.allocate(toRead)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) <= 0) break
                }
                tail.offset += buffer.position()
                val text = tail.partial + String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
                val lastBreak = text.lastIndexOf('\n')
                if (lastBreak < 0) {
                    tail.partial = text
                    return
                }
                tail.partial = text.substring(lastBreak + 1)
                consume(text.substring(0, lastBreak).split('\n').map { it.trimEnd('\r') }, catchingUp)
                if (tail.offset >= size) tail.initialized = true
            }
        }
    }

    private fun ensureLogConfig(root: DocumentFile) {
        runCatching {
            val existing = root.findFile("log.config")
            val current = existing?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
            }.orEmpty()
            if (current.contains("[Power]") && current.contains("[Decks]")) return
            val file = existing ?: root.createFile("application/octet-stream", "log.config") ?: return
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
                out.write((current.trimEnd() + "\n" + LOG_CONFIG).trim().toByteArray())
            }
        }
    }

    companion object {
        private const val POLL_MILLIS = 700L
        private const val RESCAN_MILLIS = 15_000L
        private const val MAX_CHUNK = 4 * 1024 * 1024

        /** Aktiviert die für den Tracker nötigen Logs (wie bei HDT/Arcane Tracker). */
        val LOG_CONFIG = """
            [Power]
            LogLevel=1
            FilePrinting=true
            ConsolePrinting=false
            ScreenPrinting=false
            Verbose=true

            [Decks]
            LogLevel=1
            FilePrinting=true
            ConsolePrinting=false
            ScreenPrinting=false
            Verbose=false

            [LoadingScreen]
            LogLevel=1
            FilePrinting=true
            ConsolePrinting=false
            ScreenPrinting=false
            Verbose=false
        """.trimIndent()

        /** Start-Ordner für die Ordnerauswahl: Android/data/com.blizzard.wtcg.hearthstone/files */
        fun initialFolderUri(): Uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Android/data/com.blizzard.wtcg.hearthstone/files",
        )
    }
}
