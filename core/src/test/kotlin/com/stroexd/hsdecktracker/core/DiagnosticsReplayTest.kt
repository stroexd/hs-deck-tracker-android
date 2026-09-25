package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.tracker.GameEvent
import com.stroexd.hsdecktracker.core.tracker.TrackerController
import com.stroexd.hsdecktracker.core.util.AppJson
import com.stroexd.hsdecktracker.core.vision.CardNameIndex
import com.stroexd.hsdecktracker.core.vision.OcrFrame
import com.stroexd.hsdecktracker.core.vision.VisionGameTracker
import java.io.File
import kotlin.test.Test

/**
 * Entwicklerwerkzeug: spielt eine Diagnose-Sitzung aus der App (`ocr.jsonl`) erneut durch die
 * Erkennung und schreibt die erkannten Ereignisse mit Kartennamen nach `build/replay.txt`.
 *
 * Läuft nur, wenn `HS_DIAG_DIR` (Sitzungsordner) und `HS_CARDS_DIR` (Ordner mit
 * `cards.deDE.json`/`cards.enUS.json` im HearthstoneJSON-Format) gesetzt sind.
 */
class DiagnosticsReplayTest {

    @Test
    fun replayDiagnosticsSession() {
        val sessionDir = System.getenv("HS_DIAG_DIR")?.let(::File) ?: return
        val cardsDir = System.getenv("HS_CARDS_DIR")?.let(::File) ?: return
        val dbs = listOf("deDE", "enUS").mapNotNull { locale ->
            File(cardsDir, "cards.$locale.json").takeIf { it.exists() }?.let { CardDatabase.parse(it.readText(), locale) }
        }
        val db = dbs.first()
        val index = CardNameIndex(dbs.flatMap { d -> d.deckCards.map { it.dbfId to it.name } })
        val frames = File(sessionDir, "ocr.jsonl").readLines()
            .filter { it.isNotBlank() }
            .map { AppJson.decodeFromString(OcrFrame.serializer(), it) }

        var now = 0L
        val tracker = TrackerController(clock = { now })
        val vision = VisionGameTracker(index, contextProvider = { tracker.state.value?.deckCards?.keys.orEmpty() })
        val out = StringBuilder()
        var frameNo = 0
        vision.decisionLog = { out.append("#$frameNo      · $it\n") }
        val t0 = frames.firstOrNull()?.timestamp ?: 0L
        fun names(ids: List<Int>) = ids.mapNotNull { db.byDbfId(it)?.name }.distinct().joinToString("/")

        frames.forEachIndexed { i, frame ->
            now = frame.timestamp
            frameNo = i + 1
            val events = vision.onFrame(frame)
            for (event in events) {
                val text = when (event) {
                    is GameEvent.FriendlyCardSeen -> "EIGENE  ${names(event.dbfIds)}"
                    is GameEvent.FriendlyCardMulliganed -> "ZURÜCK  ${names(event.dbfIds)}"
                    is GameEvent.OpponentCardSeen -> "GEGNER  ${names(event.dbfIds)}"
                    else -> event.toString()
                }
                out.append("#${i + 1} t=${(frame.timestamp - t0) / 1000}s  $text\n")
                tracker.onGameEvent(event, db, recordResults = false)
            }
        }
        val state = tracker.state.value
        if (state != null) {
            out.append("\nKlasse: ${state.playerClass} vs ${state.opponentClass}, Zug ${state.turn}, ging zuerst: ${state.wentFirst}\n")
            out.append("Gegnerkarten: ${state.opponentCards.joinToString { db.byDbfId(it)?.name ?: "$it" }}\n")
            out.append("Extra gezogen: ${state.extraDraws.joinToString { db.byCardId(it)?.name ?: it }}\n")
        }
        File("build/replay.txt").apply { parentFile.mkdirs() }.writeText(out.toString())
        println(out)
    }
}
