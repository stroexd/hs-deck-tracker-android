package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.TestCards.FIREBALL
import com.stroexd.hsdecktracker.core.TestCards.LEEROY
import com.stroexd.hsdecktracker.core.TestCards.RARE_NEUTRAL
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.stats.MatchSource
import com.stroexd.hsdecktracker.core.tracker.DecksLogParser
import com.stroexd.hsdecktracker.core.tracker.GameEvent
import com.stroexd.hsdecktracker.core.tracker.PowerLogParser
import com.stroexd.hsdecktracker.core.tracker.TrackerController
import com.stroexd.hsdecktracker.core.tracker.TrackerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrackerTest {

    private val deck = Deck(
        id = "deck-1",
        name = "Testmagier",
        heroClass = HsClass.MAGE,
        format = GameFormat.STANDARD,
        cards = mapOf(FIREBALL to 2, LEEROY to 1, RARE_NEUTRAL to 2),
    )

    /** Synthetisches Power.log im Format des Hearthstone-Clients. */
    private val powerLog = """
        D 10:00:00.0000001 GameState.DebugPrintPower() - CREATE_GAME
        D 10:00:00.0000002 GameState.DebugPrintPower() -     GameEntity EntityID=1
        D 10:00:00.0000003 GameState.DebugPrintPower() -         tag=TURN value=0
        D 10:00:00.0000004 GameState.DebugPrintPower() -     Player EntityID=2 PlayerID=1 GameAccountId=[hi=1 lo=2]
        D 10:00:00.0000005 GameState.DebugPrintPower() -         tag=FIRST_PLAYER value=1
        D 10:00:00.0000006 GameState.DebugPrintPower() -     Player EntityID=3 PlayerID=2 GameAccountId=[hi=1 lo=3]
        D 10:00:00.0000007 GameState.DebugPrintPower() -         tag=PLAYSTATE value=PLAYING
        D 10:00:00.0000008 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=4 CardID=
        D 10:00:00.0000009 GameState.DebugPrintPower() -     tag=ZONE value=DECK
        D 10:00:00.0000010 GameState.DebugPrintPower() -     tag=CONTROLLER value=1
        D 10:00:00.0000011 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=5 CardID=
        D 10:00:00.0000012 GameState.DebugPrintPower() -     tag=ZONE value=DECK
        D 10:00:00.0000013 GameState.DebugPrintPower() -     tag=CONTROLLER value=1
        D 10:00:00.0000014 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=6 CardID=
        D 10:00:00.0000015 GameState.DebugPrintPower() -     tag=ZONE value=DECK
        D 10:00:00.0000016 GameState.DebugPrintPower() -     tag=CONTROLLER value=1
        D 10:00:00.0000017 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=10 CardID=
        D 10:00:00.0000018 GameState.DebugPrintPower() -     tag=ZONE value=DECK
        D 10:00:00.0000019 GameState.DebugPrintPower() -     tag=CONTROLLER value=2
        D 10:00:00.0000020 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=64 CardID=HERO_08
        D 10:00:00.0000021 GameState.DebugPrintPower() -     tag=CONTROLLER value=1
        D 10:00:00.0000022 GameState.DebugPrintPower() -     tag=CARDTYPE value=HERO
        D 10:00:00.0000023 GameState.DebugPrintPower() -     tag=ZONE value=PLAY
        D 10:00:00.0000024 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=66 CardID=HERO_01c
        D 10:00:00.0000025 GameState.DebugPrintPower() -     tag=CONTROLLER value=2
        D 10:00:00.0000026 GameState.DebugPrintPower() -     tag=CARDTYPE value=HERO
        D 10:00:00.0000027 GameState.DebugPrintPower() -     tag=ZONE value=PLAY
        D 10:00:00.0000028 GameState.DebugPrintGame() - GameType=GT_RANKED
        D 10:00:00.0000029 GameState.DebugPrintGame() - FormatType=FT_STANDARD
        D 10:00:00.0000030 GameState.DebugPrintGame() - PlayerID=1, PlayerName=Ich#1234
        D 10:00:00.0000031 GameState.DebugPrintGame() - PlayerID=2, PlayerName=UNKNOWN HUMAN PLAYER
        D 10:00:00.0000032 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STEP value=BEGIN_DRAW
        D 10:00:00.0000033 GameState.DebugPrintPower() - SHOW_ENTITY - Updating Entity=[entityName=UNKNOWN ENTITY [cardType=INVALID] id=4 zone=DECK zonePos=0 cardId= player=1] CardID=CS2_029
        D 10:00:00.0000034 GameState.DebugPrintPower() -     tag=CARDTYPE value=SPELL
        D 10:00:00.0000035 GameState.DebugPrintPower() - TAG_CHANGE Entity=[entityName=Feuerball id=4 zone=DECK zonePos=0 cardId=CS2_029 player=1] tag=ZONE value=HAND
        D 10:00:00.0000036 GameState.DebugPrintPower() - TAG_CHANGE Entity=[entityName=UNKNOWN ENTITY [cardType=INVALID] id=5 zone=DECK zonePos=0 cardId= player=1] tag=ZONE value=HAND
        D 10:00:00.0000037 GameState.DebugPrintPower() - SHOW_ENTITY - Updating Entity=[entityName=UNKNOWN ENTITY [cardType=INVALID] id=5 zone=HAND zonePos=2 cardId= player=1] CardID=LEG_001
        D 10:00:00.0000038 GameState.DebugPrintPower() -     tag=ZONE value=HAND
        D 10:00:00.0000039 GameState.DebugPrintPower() - TAG_CHANGE Entity=[entityName=UNKNOWN ENTITY [cardType=INVALID] id=10 zone=DECK zonePos=0 cardId= player=2] tag=ZONE value=HAND
        D 10:00:00.0000040 GameState.DebugPrintPower() - FULL_ENTITY - Creating ID=70 CardID=GAME_005
        D 10:00:00.0000041 GameState.DebugPrintPower() -     tag=ZONE value=HAND
        D 10:00:00.0000042 GameState.DebugPrintPower() -     tag=CONTROLLER value=2
        D 10:00:00.0000043 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STEP value=BEGIN_MULLIGAN
        D 10:00:00.0000044 GameState.DebugPrintEntityChoices() - id=1 Player=Ich#1234 TaskList=4 ChoiceType=MULLIGAN CountMin=0 CountMax=3
        D 10:00:00.0000045 GameState.DebugPrintEntityChoices() -   Entities[0]=[entityName=Feuerball id=4 zone=HAND zonePos=1 cardId=CS2_029 player=1]
        D 10:00:00.0000046 GameState.DebugPrintPower() - TAG_CHANGE Entity=[entityName=Leeroy Jenkins id=5 zone=HAND zonePos=2 cardId=LEG_001 player=1] tag=ZONE value=DECK
        D 10:00:00.0000047 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=TURN value=1
        D 10:00:00.0000048 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=TURN value=2
        D 10:00:00.0000049 GameState.DebugPrintPower() - SHOW_ENTITY - Updating Entity=[entityName=UNKNOWN ENTITY [cardType=INVALID] id=10 zone=HAND zonePos=1 cardId= player=2] CardID=RARE_01
        D 10:00:00.0000050 GameState.DebugPrintPower() -     tag=ZONE value=PLAY
        D 10:00:00.0000051 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=TURN value=3
        D 10:00:00.0000052 GameState.DebugPrintPower() - TAG_CHANGE Entity=Ich#1234 tag=PLAYSTATE value=WON
        D 10:00:00.0000053 GameState.DebugPrintPower() - TAG_CHANGE Entity=UNKNOWN HUMAN PLAYER tag=PLAYSTATE value=LOST
        D 10:00:00.0000054 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE
        D 10:00:00.0000055 PowerTaskList.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE
    """.trimIndent()

    private fun parse(log: String): List<GameEvent> {
        val parser = PowerLogParser()
        return log.lines().flatMap { parser.parseLine(it) }
    }

    @Test
    fun parserEmitsExpectedEvents() {
        val events = parse(powerLog)
        val expected = listOf(
            GameEvent.GameStarted,
            GameEvent.FormatDetected(GameFormat.STANDARD),
            GameEvent.FriendlyPlayerDetected(1),
            GameEvent.HeroRevealed(friendly = true, cardId = "HERO_08"),
            GameEvent.HeroRevealed(friendly = false, cardId = "HERO_01c"),
            GameEvent.TurnOrderDetected(friendlyWentFirst = true),
            GameEvent.FriendlyCardDrawn("CS2_029"),
            GameEvent.FriendlyCardDrawn("LEG_001"),
            GameEvent.FriendlyCardReturned("LEG_001"),
            GameEvent.TurnChanged(1),
            GameEvent.TurnChanged(2),
            GameEvent.OpponentCardPlayed("RARE_01"),
            GameEvent.TurnChanged(3),
            GameEvent.GameEnded(MatchResult.WIN),
        )
        assertEquals(expected, events)
    }

    @Test
    fun parserDetectsLossAndSecondPlayer() {
        val log = powerLog
            .replace("tag=FIRST_PLAYER value=1\n", "tag=PLAYSTATE value=PLAYING\n")
            .replace("PlayerID=2 GameAccountId=[hi=1 lo=3]", "PlayerID=2 GameAccountId=[hi=1 lo=3]\nD 10:00:00.0000006 GameState.DebugPrintPower() -         tag=FIRST_PLAYER value=1")
            .replace("Ich#1234 tag=PLAYSTATE value=WON", "Ich#1234 tag=PLAYSTATE value=LOST")
            .replace("UNKNOWN HUMAN PLAYER tag=PLAYSTATE value=LOST", "UNKNOWN HUMAN PLAYER tag=PLAYSTATE value=WON")
        val events = parse(log)
        assertEquals(GameEvent.TurnOrderDetected(false), events.filterIsInstance<GameEvent.TurnOrderDetected>().single())
        assertEquals(GameEvent.GameEnded(MatchResult.LOSS), events.last())
    }

    @Test
    fun controllerAppliesLogEventsToDeck() {
        val controller = TrackerController(clock = { 1_000L })
        controller.start(deck)
        val parser = PowerLogParser()
        var finished: com.stroexd.hsdecktracker.core.stats.MatchRecord? = null
        var stateBeforeEnd: TrackerState? = null
        for (line in powerLog.lines()) {
            for (event in parser.parseLine(line)) {
                if (event is GameEvent.GameEnded) stateBeforeEnd = controller.state.value
                controller.onGameEvent(event, TestCards.db)?.let { finished = it }
            }
        }
        val before = assertNotNull(stateBeforeEnd)
        assertEquals(1, before.remainingOf(FIREBALL))
        assertEquals(1, before.remainingOf(LEEROY))
        assertEquals(listOf(RARE_NEUTRAL), before.opponentCards)
        assertEquals(HsClass.WARRIOR, before.opponentClass)
        assertEquals(true, before.wentFirst)
        assertEquals(2, before.turn)

        val record = assertNotNull(finished)
        assertEquals(MatchResult.WIN, record.result)
        assertEquals(HsClass.WARRIOR, record.opponentClass)
        assertEquals("deck-1", record.deckId)
        assertEquals(MatchSource.LOG, record.source)
        // Nach Spielende ist der Tracker für die nächste Partie zurückgesetzt.
        assertEquals(5, controller.state.value?.remainingCount)
    }

    @Test
    fun manualTrackingDrawUndoAndReturn() {
        var state = TrackerState.start(deck, now = 0)
        assertEquals(5, state.remainingCount)
        assertEquals(0.4, state.nextDrawChance(FIREBALL))
        state = state.draw(FIREBALL).draw(FIREBALL).draw(FIREBALL)
        assertEquals(0, state.remainingOf(FIREBALL))
        assertEquals(2, state.drawHistory.size)
        state = state.undoLastDraw()
        assertEquals(1, state.remainingOf(FIREBALL))
        state = state.returnToDeck(FIREBALL).returnToDeck(FIREBALL)
        assertEquals(2, state.remainingOf(FIREBALL))
        state = state.addOpponentCard(LEEROY).addOpponentCard(FIREBALL).removeOpponentCardAt(0)
        assertEquals(listOf(FIREBALL), state.opponentCards)
        val record = state.withOpponentClass(HsClass.ROGUE).nextTurn().toMatchRecord(MatchResult.LOSS, now = 60_000)
        assertEquals(60, record.durationSeconds)
        assertEquals(2, record.turns)
        assertEquals(MatchSource.TRACKER, record.source)
    }

    @Test
    fun endWithoutKnownResultDoesNotRecord() {
        val controller = TrackerController(clock = { 0L })
        controller.start(deck)
        assertNull(controller.onGameEvent(GameEvent.GameEnded(null), TestCards.db))
    }

    @Test
    fun decksLogSelection() {
        val code = deck.deckCode()
        val parser = DecksLogParser()
        val lines = listOf(
            "I 18:00:00.1234567 Finding Game With Deck:",
            "I 18:00:00.1234567 ### Mein Deck",
            "I 18:00:00.1234567 # Deck ID: 123",
            "I 18:00:00.1234567 $code",
        )
        val selections = lines.mapNotNull { parser.parseLine(it) }
        assertEquals(listOf(DecksLogParser.DeckSelection("Mein Deck", code)), selections)
    }
}
