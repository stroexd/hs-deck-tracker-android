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
                    is GameEvent.FriendlyCardSeen -> "OWN       ${names(event.dbfIds)}"
                    is GameEvent.FriendlyCardMulliganed -> "RETURNED  ${names(event.dbfIds)}"
                    is GameEvent.OpponentCardSeen -> "OPPONENT  ${names(event.dbfIds)}"
                    else -> event.toString()
                }
                out.append("#${i + 1} t=${(frame.timestamp - t0) / 1000}s  $text\n")
                if (event !is GameEvent.GameEnded) tracker.onGameEvent(event, db)
            }
        }
        out.append("\nDetected client language: ${vision.gameLocale}")
        val state = tracker.state.value
        if (state != null) {
            out.append("\nClasses: ${state.playerClass} vs ${state.opponentClass}, turn ${state.turn}, went first: ${state.wentFirst}\n")
            out.append("Opponent cards: ${state.opponentCards.joinToString { db.byDbfId(it)?.name ?: "$it" }}\n")
            out.append("Extra draws: ${state.extraDraws.joinToString { db.byCardId(it)?.name ?: it }}\n")
        }
        File("build/replay.txt").apply { parentFile.mkdirs() }.writeText(out.toString())
        println(out)
    }
}
