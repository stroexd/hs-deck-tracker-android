package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.stats.MatchSource
import com.stroexd.hsdecktracker.core.stats.TimelineType
import com.stroexd.hsdecktracker.core.tracker.DeckIdentifier
import com.stroexd.hsdecktracker.core.tracker.GameEvent
import com.stroexd.hsdecktracker.core.tracker.TrackerController
import com.stroexd.hsdecktracker.core.util.AppJson
import com.stroexd.hsdecktracker.core.vision.CardNameIndex
import com.stroexd.hsdecktracker.core.vision.OcrFrame
import com.stroexd.hsdecktracker.core.vision.OcrLine
import com.stroexd.hsdecktracker.core.vision.ScreenRegions
import com.stroexd.hsdecktracker.core.vision.UiKeywords
import com.stroexd.hsdecktracker.core.vision.VisionGameTracker
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisionTest {
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
          {"dbfId":202,"id":"NEU_202","name":"Loot Hoarder","cost":2,"type":"MINION","rarity":"COMMON","cardClass":"NEUTRAL","set":"CORE","collectible":true},
          {"dbfId":301,"id":"WAR_301","name":"Broxigar","cost":8,"type":"MINION","rarity":"LEGENDARY","cardClass":"WARRIOR","set":"CORE","collectible":true}
        ]
        """.trimIndent(),
        "enUS",
    )
    private val index = CardNameIndex(db.deckCards.map { it.dbfId to it.name } + listOf(104 to "Meucheln", 102 to "Vorbereitung"))

    private val rogueDeck = Deck(
        id = "rogue",
        name = "Schurke (Shaw)",
        heroClass = HsClass.ROGUE,
        cards = mapOf(101 to 2, 102 to 2, 103 to 1, 104 to 2, 105 to 2),
    )

    private val mageLookalike = rogueDeck.copy(id = "mage", name = "Magier", heroClass = HsClass.MAGE)

    private var time = 0L
    private fun frame(vararg lines: OcrLine): OcrFrame = OcrFrame(timestamp = (time++) * 500, lines = lines.toList())
    private fun frame(lines: List<OcrLine>): OcrFrame = frame(*lines.toTypedArray())
    private fun line(text: String, x: Float, y: Float, h: Float = 0.03f, w: Float = 0.1f) =
        OcrLine(text, x - w / 2, y - h / 2, x + w / 2, y + h / 2)

    private fun versus() = listOf(
        line("Broxigar", 0.315f, 0.675f, w = 0.07f),
        line("DEMON HUNTER", 0.315f, 0.71f, h = 0.02f),
        line("Suspect Maiev", 0.695f, 0.675f),
        line("ROGUE", 0.69f, 0.71f, h = 0.02f, w = 0.04f),
    )
    private fun mulliganHeader() = listOf(line("Starting Hand", 0.495f, 0.125f), line("Confirm", 0.495f, 0.875f, w = 0.05f))
    private fun rowCard(name: String, x: Float, vararg text: String) =
        listOf(line(name, x, 0.545f, h = 0.04f)) + text.mapIndexed { i, t -> line(t, x, 0.635f + i * 0.028f, h = 0.025f) }
    private fun zoomRow(vararg names: String) = names.mapIndexed { i, n -> line(n, 0.28f + i * 0.08f, 0.9f, h = 0.05f, w = 0.06f) }
    private fun endTurn() = line("END TURN", 0.8f, 0.49f, h = 0.02f, w = 0.06f)
    private fun enemyTurn() = line("ENEMY TURN", 0.8f, 0.49f, h = 0.02f, w = 0.06f)
    private fun yourTurn() = line("Your Turn", 0.49f, 0.5f, h = 0.06f, w = 0.14f)
    private fun drawn(name: String) = line(name, 0.755f, 0.635f, h = 0.05f, w = 0.09f)
    private fun opponentEntering(name: String) = line(name, 0.385f, 0.485f, h = 0.04f)
    private fun opponentShown(name: String) = line(name, 0.265f, 0.535f)
    private fun resultText(text: String) = line(text, 0.515f, 0.65f, h = 0.04f, w = 0.07f)

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
    fun contextBoostsRecognitionOfKnownDeckCards() {
        val noisy = "Mathyas Shqx"
        assertNull(index.match(noisy))
        assertEquals(103, index.match(noisy, preferred = setOf(103))?.dbfIds?.first())
        assertEquals(104, index.match("Bakstab", preferred = setOf(104))?.dbfIds?.first())
        assertNull(index.match("xyzqwert", preferred = setOf(105)))
    }

    @Test
    fun truncatedNamesOnlyWhenUnique() {
        val twilight = CardNameIndex(listOf(1 to "Twilight Egg", 2 to "Twilight Mistress", 3 to "Twilight Drake", 4 to "Opu the Unseen"))
        assertEquals(listOf(2), twilight.match("Twilight Mistre")?.dbfIds)
        assertEquals(listOf(4), twilight.match("Opu the Unse")?.dbfIds)
        assertNull(twilight.match("Twilight"))
        assertNull(twilight.match("Twilight M"))
    }

    @Test
    fun uiKeywordsTolerateOcrNoise() {
        assertEquals(false, UiKeywords.turnButton("enemy turn"))
        assertEquals(false, UiKeywords.turnButton("emy turn"))
        assertEquals(false, UiKeywords.turnButton("nemyturn"))
        assertEquals(true, UiKeywords.turnButton("end turn"))
        assertEquals(true, UiKeywords.turnButton("zug beenden"))
        assertNull(UiKeywords.turnButton("turn"))
        assertNull(UiKeywords.turnButton("erazturn"))
        assertTrue(UiKeywords.isYourTurnBanner("our tun"))
        assertEquals(HsClass.DEMONHUNTER, UiKeywords.heroClass("demon hunter"))
        assertEquals(HsClass.HUNTER, UiKeywords.heroClass("hunter"))
        assertEquals(HsClass.ROGUE, UiKeywords.heroClass("schurke"))
        assertEquals(HsClass.DEMONHUNTER, UiKeywords.heroClass("damonenjagerin"))
        assertNull(UiKeywords.heroClass("max"))
        assertEquals(MatchResult.LOSS, UiKeywords.result("defeat"))
        assertEquals(0.5f, ScreenRegions.boardX(0.5f, 16f / 9f))
        assertTrue(ScreenRegions.boardX(0.2f, 16f / 9f) > 0.2f)
        assertEquals(0.2f, ScreenRegions.boardX(0.2f, 0f))
    }

    @Test
    fun fullGameFromVersusScreenToVictory() {
        val tracker = VisionGameTracker(index)
        val events = mutableListOf<GameEvent>()
        fun feed(vararg lines: OcrLine) = tracker.onFrame(frame(*lines)).also { events += it }
        fun feed(lines: List<OcrLine>) = feed(*lines.toTypedArray())

        assertTrue(feed(line("Play", 0.5f, 0.4f), line("Collection", 0.5f, 0.6f)).isEmpty())

        feed(versus() + line("TURN", 0.815f, 0.49f, h = 0.02f, w = 0.03f))
        assertEquals(
            listOf(
                GameEvent.GameStarted,
                GameEvent.ClassDetected(friendly = false, hsClass = HsClass.DEMONHUNTER),
                GameEvent.ClassDetected(friendly = true, hsClass = HsClass.ROGUE),
            ),
            events,
        )
        assertEquals(VisionGameTracker.Phase.MULLIGAN, tracker.phase)

        val row = rowCard("Spymistress", 0.41f, "Deathrattle: Herald", "Eviscerate") +
            rowCard("Backstab", 0.58f, "Deal 2 damage to an", "undamaged minion.") +
            rowCard("Mathias Shaw", 0.75f, "Stealth. Whenever a friendly", "Stealthed minion attacks,")
        repeat(3) { feed(mulliganHeader() + row) }
        val kept = rowCard("Spymistress", 0.41f) + rowCard("Mathias Shaw", 0.75f)
        feed(kept)
        feed(kept + line("Preparation", 0.905f, 0.69f, h = 0.02f, w = 0.05f))
        feed(kept)
        events.clear()
        feed(line("Spym", 0.7f, 0.96f, h = 0.02f, w = 0.02f))
        feed(line("Spym", 0.7f, 0.96f, h = 0.02f, w = 0.02f))
        assertEquals(VisionGameTracker.Phase.PLAYING, tracker.phase)
        assertEquals(
            mapOf(listOf(101) to 1, listOf(9104, 104) to 1, listOf(103) to 1, listOf(102) to 1),
            events.filterIsInstance<GameEvent.FriendlyCardSeen>().groupingBy { it.dbfIds }.eachCount(),
        )
        assertEquals(listOf(GameEvent.FriendlyCardMulliganed(listOf(9104, 104))), events.filterIsInstance<GameEvent.FriendlyCardMulliganed>())

        events.clear()
        feed(yourTurn())
        feed(drawn("Eviscerate"), line("Deal 2 damage.", 0.755f, 0.7f, h = 0.025f))
        feed(drawn("Eviscerate"), endTurn())
        assertEquals(
            listOf(
                GameEvent.TurnOrderDetected(friendlyWentFirst = true),
                GameEvent.TurnChanged(1),
                GameEvent.FriendlyCardSeen(listOf(105)),
            ),
            events,
        )

        events.clear()
        feed(line("Mathias Shaw", 0.69f, 0.485f, h = 0.07f, w = 0.16f), endTurn())
        repeat(2) { feed(*zoomRow("Spymistress", "Preparation", "Mathias Shaw", "Eviscerate").toTypedArray(), endTurn()) }
        assertTrue(events.isEmpty())

        repeat(2) { feed(endTurn()) }

        events.clear()
        feed(enemyTurn())
        feed(enemyTurn())
        feed(opponentEntering("Chaos Strike"), enemyTurn())
        repeat(3) { feed(opponentShown("Chaos Strike"), enemyTurn()) }
        feed(opponentEntering("Chaos Strike"), enemyTurn())
        feed(opponentShown("Chaos Strike"), enemyTurn())
        assertEquals(
            listOf(GameEvent.TurnChanged(2), GameEvent.OpponentCardSeen(listOf(201)), GameEvent.OpponentCardSeen(listOf(201))),
            events,
        )

        events.clear()
        feed(yourTurn())
        val choice = listOf(line("Choose One", 0.495f, 0.125f)) +
            rowCard("Eviscerate", 0.27f) + rowCard("Loot Hoarder", 0.495f) + rowCard("Preparation", 0.725f)
        repeat(2) { feed(choice + endTurn()) }
        feed(drawn("Loot Hoarder"), endTurn())
        assertEquals(
            listOf(GameEvent.TurnChanged(3), GameEvent.FriendlyCardSeen(listOf(202), fromDeck = false)),
            events,
        )

        events.clear()
        repeat(6) { feed(*zoomRow("Spymistress", "Mathias Shaw", "Eviscerate", "Loot Hoarder").toTypedArray(), endTurn()) }
        feed(drawn("Preparation"), endTurn())
        assertEquals(listOf<GameEvent>(GameEvent.FriendlyCardSeen(listOf(102))), events)

        events.clear()
        feed(resultText("Victory!"), endTurn())
        assertTrue(events.isEmpty())
        feed(resultText("Victory!"))
        assertEquals(listOf<GameEvent>(GameEvent.GameEnded(MatchResult.WIN)), events)
        assertEquals(VisionGameTracker.Phase.ENDED, tracker.phase)

        events.clear()
        repeat(8) { feed(endTurn()) }
        assertTrue(events.isEmpty())
        time += 40
        feed(versus())
        assertEquals(GameEvent.GameStarted, events.first())
    }

    @Test
    fun shortFalseGameIsNotRecorded() {
        val tracker = VisionGameTracker(index)
        val events = (1..6).flatMap { tracker.onFrame(frame(enemyTurn())) }.toMutableList()
        assertEquals(listOf<GameEvent>(GameEvent.GameStarted), events.take(1))
        events += tracker.onFrame(frame(resultText("Defeat!")))
        events += tracker.onFrame(frame(resultText("Defeat!")))
        assertTrue(events.none { it is GameEvent.GameEnded })
        assertEquals(VisionGameTracker.Phase.ENDED, tracker.phase)
    }

    @Test
    fun trackerStartedMidGameAndDefeat() {
        val tracker = VisionGameTracker(index)
        val events = mutableListOf<GameEvent>()
        fun feed(vararg lines: OcrLine) = events.addAll(tracker.onFrame(frame(*lines)))
        repeat(6) { feed(*zoomRow("Spymistress", "Preparation", "Backstab").toTypedArray(), enemyTurn()) }
        assertEquals(GameEvent.GameStarted, events.first())
        assertEquals(3, events.count { it is GameEvent.FriendlyCardSeen })
        repeat(3) { feed(endTurn()) }
        repeat(3) { feed(enemyTurn()) }
        feed(resultText("Defeat!"))
        feed(resultText("Defeat!"))
        assertEquals(GameEvent.GameEnded(MatchResult.LOSS), events.last())
    }

    @Test
    fun deckIdentification() {
        val seen = listOf(listOf(101), listOf(102), listOf(9104, 104))
        val otherDeck = Deck(id = "other", name = "Anderes", heroClass = HsClass.ROGUE, cards = mapOf(105 to 2, 202 to 2))
        assertEquals("rogue", DeckIdentifier.identify(seen, listOf(otherDeck, rogueDeck))?.id)
        assertNull(DeckIdentifier.identify(listOf(listOf(101)), listOf(rogueDeck)))
        assertNull(DeckIdentifier.identify(listOf(listOf(101), listOf(201), listOf(202), listOf(202)), listOf(rogueDeck)))
    }

    @Test
    fun controllerUsesClassesMulliganAndRecordsGame() {
        val controller = TrackerController(clock = { 10_000L })
        controller.deckCandidates = { listOf(mageLookalike, rogueDeck) }
        controller.preferredOpponentIds = { emptySet() }
        val events = listOf(
            GameEvent.GameStarted,
            GameEvent.ClassDetected(friendly = false, hsClass = HsClass.DEMONHUNTER),
            GameEvent.ClassDetected(friendly = true, hsClass = HsClass.ROGUE),
            GameEvent.FriendlyCardSeen(listOf(101)),
            GameEvent.FriendlyCardSeen(listOf(9104, 104)),
            GameEvent.FriendlyCardSeen(listOf(103)),
            GameEvent.FriendlyCardMulliganed(listOf(9104, 104)),
            GameEvent.FriendlyCardSeen(listOf(102)),
            GameEvent.TurnOrderDetected(friendlyWentFirst = true),
            GameEvent.TurnChanged(1),
            GameEvent.TurnChanged(2),
            GameEvent.OpponentCardSeen(listOf(102)),
            GameEvent.OpponentCardSeen(listOf(201)),
            GameEvent.OpponentCardSeen(listOf(202)),
            GameEvent.TurnChanged(3),
            GameEvent.FriendlyCardSeen(listOf(202), fromDeck = false),
        )
        events.forEach { controller.onGameEvent(it, db) }
        val state = assertNotNull(controller.state.value)
        assertEquals("rogue", state.deckId)
        assertEquals(HsClass.ROGUE, state.playerClass)
        assertEquals(1, state.remainingOf(101))
        assertEquals(2, state.remainingOf(104))
        assertEquals(0, state.remainingOf(103))
        assertEquals(1, state.remainingOf(102))
        assertEquals(HsClass.DEMONHUNTER, state.opponentClass)
        assertEquals(listOf(201, 202), state.opponentCards)
        assertEquals(listOf("NEU_202"), state.extraDraws)
        assertEquals(true, state.wentFirst)

        val record = assertNotNull(controller.onGameEvent(GameEvent.GameEnded(MatchResult.WIN), db))
        assertEquals(MatchResult.WIN, record.result)
        assertEquals("Schurke (Shaw)", record.deckName)
        assertEquals(HsClass.DEMONHUNTER, record.opponentClass)
        assertEquals(MatchSource.AUTO, record.source)
        assertEquals(4, record.timeline.count { it.type == TimelineType.DRAW })
        assertEquals(1, record.timeline.count { it.type == TimelineType.RETURN })
    }

    @Test
    fun unknownDeckStillTracksSeenCardsAndClass() {
        val controller = TrackerController(clock = { 0L })
        listOf(
            GameEvent.GameStarted,
            GameEvent.FriendlyCardSeen(listOf(101)),
            GameEvent.FriendlyCardSeen(listOf(105)),
            GameEvent.FriendlyCardSeen(listOf(9104, 104)),
            GameEvent.FriendlyCardMulliganed(listOf(9104, 104)),
        ).forEach { controller.onGameEvent(it, db) }
        val state = assertNotNull(controller.state.value)
        assertTrue(state.deckCards.isEmpty())
        assertEquals(listOf("ROG_101", "ROG_105"), state.extraDraws)
        assertEquals(HsClass.ROGUE, state.playerClass)
    }

    @Test
    fun deckOfOtherClassIsDroppedWhenClassDetected() {
        val controller = TrackerController(clock = { 0L })
        controller.selectDeckForCurrentGame(mageLookalike, db)
        controller.onGameEvent(GameEvent.GameStarted, db)
        assertEquals("mage", controller.state.value?.deckId)
        controller.onGameEvent(GameEvent.ClassDetected(friendly = true, hsClass = HsClass.ROGUE), db)
        val state = assertNotNull(controller.state.value)
        assertNull(state.deckId)
        assertEquals(HsClass.ROGUE, state.playerClass)
    }

    @Test
    fun opponentClassFromCardsNeedsTwoMatches() {
        val controller = TrackerController(clock = { 0L })
        controller.onGameEvent(GameEvent.GameStarted, db)
        controller.onGameEvent(GameEvent.OpponentCardSeen(listOf(102)), db)
        assertEquals(HsClass.UNKNOWN, controller.state.value?.opponentClass)
        controller.onGameEvent(GameEvent.OpponentCardSeen(listOf(105)), db)
        assertEquals(HsClass.ROGUE, controller.state.value?.opponentClass)
    }

    @Test
    fun framesSerializeCompactlyForDiagnostics() {
        val frames = listOf(frame(line("Spymistress", 0.3f, 0.45f)))
        val json = AppJson.encodeToString(ListSerializer(OcrFrame.serializer()), frames)
        assertTrue(json.contains("\"s\":\"Spymistress\""))
        assertEquals(frames, AppJson.decodeFromString(ListSerializer(OcrFrame.serializer()), json))
        assertEquals(0f, AppJson.decodeFromString(OcrFrame.serializer(), """{"ts":1,"lines":[]}""").aspect)
        assertTrue(VisionGameTracker.replay(index, frames).isEmpty())
    }
}
