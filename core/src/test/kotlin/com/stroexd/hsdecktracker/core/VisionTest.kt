package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.stats.MatchSource
import com.stroexd.hsdecktracker.core.tracker.DeckIdentifier
import com.stroexd.hsdecktracker.core.tracker.GameEvent
import com.stroexd.hsdecktracker.core.tracker.TrackerController
import com.stroexd.hsdecktracker.core.vision.CardNameIndex
import com.stroexd.hsdecktracker.core.vision.OcrFrame
import com.stroexd.hsdecktracker.core.vision.OcrLine
import com.stroexd.hsdecktracker.core.vision.VisionGameTracker
import com.stroexd.hsdecktracker.core.util.AppJson
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisionTest {

    // Schurken-Karten wie im Screenshot (englischer Client) + ein paar Gegnerkarten
    private val db = CardDatabase.parse(
        """
        [
          {"dbfId":101,"id":"ROG_101","name":"Spymistress","cost":1,"type":"MINION","rarity":"COMMON","cardClass":"ROGUE","set":"CORE","collectible":true},
          {"dbfId":102,"id":"ROG_102","name":"Preparation","cost":0,"type":"SPELL","rarity":"EPIC","cardClass":"ROGUE","set":"CORE","collectible":true},
          {"dbfId":103,"id":"ROG_103","name":"Mathias Shaw","cost":4,"type":"MINION","rarity":"LEGENDARY","cardClass":"ROGUE","set":"TIME_TRAVEL","collectible":true},
          {"dbfId":104,"id":"ROG_104","name":"Backstab","cost":0,"type":"SPELL","rarity":"COMMON","cardClass":"ROGUE","set":"CORE","collectible":true},
          {"dbfId":9104,"id":"LEG_104","name":"Backstab","cost":0,"type":"SPELL","rarity":"FREE","cardClass":"ROGUE","set":"LEGACY","collectible":true},
          {"dbfId":105,"id":"ROG_105","name":"Eviscerate","cost":2,"type":"SPELL","rarity":"COMMON","cardClass":"ROGUE","set":"CORE","collectible":true},
          {"dbfId":201,"id":"DH_201","name":"Chaos Strike","cost":2,"type":"SPELL","rarity":"COMMON","cardClass":"DEMONHUNTER","set":"CORE","collectible":true},
          {"dbfId":202,"id":"NEU_202","name":"Loot Hoarder","cost":2,"type":"MINION","rarity":"COMMON","cardClass":"NEUTRAL","set":"CORE","collectible":true}
        ]
        """.trimIndent(),
        "enUS",
    )
    private val index = CardNameIndex(db.deckCards.map { it.dbfId to it.name } + listOf(104 to "Meucheln"))

    private val rogueDeck = Deck(
        id = "rogue",
        name = "Schurke (Shaw)",
        heroClass = HsClass.ROGUE,
        cards = mapOf(101 to 2, 102 to 2, 103 to 1, 104 to 2, 105 to 2),
    )
    private val otherDeck = Deck(id = "other", name = "Anderes", heroClass = HsClass.ROGUE, cards = mapOf(105 to 2, 202 to 2))

    private var time = 0L
    private fun frame(vararg lines: OcrLine): OcrFrame = OcrFrame(timestamp = (time++) * 500, lines = lines.toList())
    private fun line(text: String, x: Float, y: Float, h: Float = 0.03f) = OcrLine(text, x - 0.05f, y - h / 2, x + 0.05f, y + h / 2)

    private fun hand(vararg names: String) = names.mapIndexed { i, n -> line(n, 0.55f + i * 0.08f, 0.93f, 0.02f) }.toTypedArray()

    @Test
    fun nameIndexHandlesOcrNoise() {
        assertEquals(listOf(103), index.match("Mathias Shaw.")?.dbfIds)
        assertEquals(listOf(103), index.match("4 Mathias Shavv")?.dbfIds)
        assertEquals(listOf(101), index.match("Spymistres")?.dbfIds)
        assertEquals(listOf(9104, 104), index.match("BACKSTAB")?.dbfIds)
        assertEquals(listOf(104), index.match("Meucheln")?.dbfIds)
        assertNull(index.match("Play"))
        assertNull(index.match("Collection"))
        assertEquals(listOf("spymistress", "preparation"), index.findAll("Spymistress Preparation").map { it.key })
    }

    @Test
    fun fullGameFromMulliganToVictory() {
        val tracker = VisionGameTracker(index)
        val events = mutableListOf<GameEvent>()
        fun feed(f: OcrFrame) = tracker.onFrame(f).also { events += it }

        // Hauptmenü: nichts passiert
        assertTrue(feed(frame(line("Play", 0.5f, 0.4f), line("Collection", 0.5f, 0.6f))).isEmpty())

        // Mulligan: drei Karten + „Confirm“
        feed(frame(line("Spymistress", 0.3f, 0.45f), line("Backstab", 0.5f, 0.45f), line("Mathias Shaw", 0.7f, 0.45f), line("Confirm", 0.5f, 0.85f)))
        assertEquals(listOf<GameEvent>(GameEvent.GameStarted), events)
        assertEquals(VisionGameTracker.Phase.MULLIGAN, tracker.phase)
        // Backstab wird getauscht → Preparation
        feed(frame(line("Spymistress", 0.3f, 0.45f), line("Preparation", 0.5f, 0.45f), line("Mathias Shaw", 0.7f, 0.45f), line("Confirm", 0.5f, 0.85f)))

        // Spiel läuft, eigener Zug
        events.clear()
        feed(frame(*hand("Spymistress", "Preparation", "Mathias Shaw"), line("END TURN", 0.8f, 0.49f)))
        assertEquals(
            listOf(
                GameEvent.FriendlyCardSeen(listOf(101)),
                GameEvent.FriendlyCardSeen(listOf(102)),
                GameEvent.FriendlyCardSeen(listOf(103)),
                GameEvent.TurnOrderDetected(friendlyWentFirst = true),
                GameEvent.TurnChanged(1),
            ),
            events,
        )

        // Gegnerischer Zug: Gegner spielt Chaos Strike (links groß eingeblendet), zwei Bilder lang
        events.clear()
        feed(frame(*hand("Spymistress", "Preparation", "Mathias Shaw"), line("ENEMY TURN", 0.8f, 0.49f), line("Chaos Strike", 0.2f, 0.45f, 0.05f)))
        feed(frame(*hand("Spymistress", "Preparation", "Mathias Shaw"), line("ENEMY TURN", 0.8f, 0.49f), line("Chaos Strike", 0.2f, 0.45f, 0.05f)))
        assertEquals(listOf(GameEvent.TurnChanged(2), GameEvent.OpponentCardSeen(listOf(201))), events)

        // Eigener Zug: Backstab gezogen (beide Drucke als Kandidaten)
        events.clear()
        feed(frame(*hand("Spymistress", "Preparation", "Mathias Shaw", "Backstab"), line("END TURN", 0.8f, 0.49f)))
        assertEquals(listOf(GameEvent.TurnChanged(3), GameEvent.FriendlyCardSeen(listOf(9104, 104))), events)

        // Mathias Shaw kurz verdeckt (2 Bilder) → kein neues Exemplar
        events.clear()
        repeat(2) { feed(frame(*hand("Spymistress", "Preparation", "Backstab"), line("END TURN", 0.8f, 0.49f))) }
        feed(frame(*hand("Spymistress", "Preparation", "Mathias Shaw", "Backstab"), line("END TURN", 0.8f, 0.49f)))
        assertTrue(events.isEmpty())

        // Preparation ausgespielt (4 lesbare Bilder ohne) und später zweites Exemplar gezogen
        repeat(4) { feed(frame(*hand("Spymistress", "Mathias Shaw", "Backstab"), line("END TURN", 0.8f, 0.49f))) }
        assertTrue(events.isEmpty())
        feed(frame(*hand("Spymistress", "Mathias Shaw", "Backstab", "Preparation"), line("END TURN", 0.8f, 0.49f)))
        assertEquals(listOf<GameEvent>(GameEvent.FriendlyCardSeen(listOf(102))), events)

        // Sieg (zwei Bilder zur Bestätigung)
        events.clear()
        feed(frame(line("VICTORY", 0.5f, 0.5f, 0.1f), line("END TURN", 0.8f, 0.49f)))
        assertTrue(events.isEmpty())
        feed(frame(line("VICTORY", 0.5f, 0.5f, 0.1f), line("END TURN", 0.8f, 0.49f)))
        assertEquals(listOf<GameEvent>(GameEvent.GameEnded(MatchResult.WIN)), events)
        assertEquals(VisionGameTracker.Phase.ENDED, tracker.phase)

        // Direkt danach noch sichtbarer Zug-Knopf startet keine neue Partie …
        events.clear()
        feed(frame(line("END TURN", 0.8f, 0.49f)))
        assertTrue(events.isEmpty())
        // … aber der nächste Mulligan schon
        feed(frame(line("Eviscerate", 0.3f, 0.45f), line("Backstab", 0.5f, 0.45f), line("The Coin", 0.8f, 0.45f), line("Confirm", 0.5f, 0.85f)))
        assertEquals(listOf<GameEvent>(GameEvent.GameStarted), events)
        events.clear()
        feed(frame(*hand("Eviscerate", "Backstab", "The Coin"), line("ENEMY TURN", 0.8f, 0.49f)))
        assertTrue(GameEvent.TurnOrderDetected(friendlyWentFirst = false) in events)
        assertEquals(1, events.count { it is GameEvent.TurnOrderDetected })
    }

    @Test
    fun trackerStartedMidGameAndDefeat() {
        val tracker = VisionGameTracker(index)
        val events = listOf(
            frame(*hand("Spymistress", "Preparation"), line("ENEMY TURN", 0.8f, 0.49f)),
            frame(line("Defeat", 0.5f, 0.45f, 0.08f)),
            frame(line("Defeat", 0.5f, 0.45f, 0.08f)),
        ).flatMap { tracker.onFrame(it) }
        assertEquals(GameEvent.GameStarted, events.first())
        assertEquals(2, events.count { it is GameEvent.FriendlyCardSeen })
        assertEquals(GameEvent.GameEnded(MatchResult.LOSS), events.last())
    }

    @Test
    fun deckIdentification() {
        val seen = listOf(listOf(101), listOf(102), listOf(9104, 104))
        assertEquals("rogue", DeckIdentifier.identify(seen, listOf(otherDeck, rogueDeck))?.id)
        assertNull(DeckIdentifier.identify(listOf(listOf(101)), listOf(rogueDeck)))
        // Zu viele unpassende Karten → kein Treffer
        assertNull(DeckIdentifier.identify(listOf(listOf(101), listOf(201), listOf(202), listOf(202)), listOf(rogueDeck)))
    }

    @Test
    fun controllerIdentifiesDeckAndRecordsGame() {
        val controller = TrackerController(clock = { 10_000L })
        controller.deckCandidates = { listOf(otherDeck, rogueDeck) }
        controller.preferredOpponentIds = { emptySet() }
        val tracker = VisionGameTracker(index)
        val frames = listOf(
            frame(line("Spymistress", 0.3f, 0.45f), line("Backstab", 0.5f, 0.45f), line("Mathias Shaw", 0.7f, 0.45f), line("Confirm", 0.5f, 0.85f)),
            frame(*hand("Spymistress", "Backstab", "Mathias Shaw"), line("END TURN", 0.8f, 0.49f)),
            frame(*hand("Spymistress", "Backstab", "Mathias Shaw"), line("ENEMY TURN", 0.8f, 0.49f), line("Chaos Strike", 0.2f, 0.45f, 0.05f)),
            frame(line("VICTORY", 0.5f, 0.5f, 0.1f)),
        )
        var record: com.stroexd.hsdecktracker.core.stats.MatchRecord? = null
        var stateBeforeEnd = controller.state.value
        for (f in frames + frame(line("VICTORY", 0.5f, 0.5f, 0.1f))) {
            for (event in tracker.onFrame(f)) {
                if (event is GameEvent.GameEnded) stateBeforeEnd = controller.state.value
                controller.onGameEvent(event, db)?.let { record = it }
            }
        }
        val state = assertNotNull(stateBeforeEnd)
        assertEquals("rogue", state.deckId)
        assertEquals(1, state.remainingOf(101))
        assertEquals(1, state.remainingOf(104))
        assertEquals(0, state.remainingOf(103))
        assertEquals(HsClass.DEMONHUNTER, state.opponentClass)
        assertEquals(listOf(201), state.opponentCards)
        assertEquals(true, state.wentFirst)

        val match = assertNotNull(record)
        assertEquals(MatchResult.WIN, match.result)
        assertEquals("Schurke (Shaw)", match.deckName)
        assertEquals(MatchSource.LOG, match.source)
        assertEquals(3, match.timeline.count { it.type == com.stroexd.hsdecktracker.core.stats.TimelineType.DRAW })
    }

    @Test
    fun unknownDeckStillTracksSeenCardsAndClass() {
        val controller = TrackerController(clock = { 0L })
        val tracker = VisionGameTracker(index)
        listOf(frame(*hand("Spymistress", "Eviscerate"), line("END TURN", 0.8f, 0.49f)))
            .flatMap { tracker.onFrame(it) }
            .forEach { controller.onGameEvent(it, db) }
        val state = assertNotNull(controller.state.value)
        assertTrue(state.deckCards.isEmpty())
        assertEquals(listOf("ROG_101", "ROG_105"), state.extraDraws)
        assertEquals(HsClass.ROGUE, state.playerClass)
    }

    @Test
    fun framesSerializeCompactlyForDiagnostics() {
        val frames = listOf(frame(line("Spymistress", 0.3f, 0.45f)))
        val json = AppJson.encodeToString(ListSerializer(OcrFrame.serializer()), frames)
        assertTrue(json.contains("\"s\":\"Spymistress\""))
        assertEquals(frames, AppJson.decodeFromString(ListSerializer(OcrFrame.serializer()), json))
        // Eine einzelne Karte ohne Mulligan/Zug-Knopf startet keine Partie
        assertTrue(VisionGameTracker.replay(index, frames).isEmpty())
    }
}
