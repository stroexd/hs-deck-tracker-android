package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.TestCards.EPIC_MAGE
import com.stroexd.hsdecktracker.core.TestCards.FIREBALL
import com.stroexd.hsdecktracker.core.TestCards.LEEROY
import com.stroexd.hsdecktracker.core.TestCards.RARE_NEUTRAL
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.stats.MatchExporter
import com.stroexd.hsdecktracker.core.stats.MatchHistory
import com.stroexd.hsdecktracker.core.stats.MatchQuery
import com.stroexd.hsdecktracker.core.stats.MatchRecord
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.stats.ResultFilter
import com.stroexd.hsdecktracker.core.stats.TimelineType
import com.stroexd.hsdecktracker.core.tracker.TrackerState
import com.stroexd.hsdecktracker.core.util.AppJson
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchHistoryTest {
    private val db = TestCards.db
    private val deck = Deck(id = "d1", name = "Feuer-Magier", heroClass = HsClass.MAGE, cards = mapOf(FIREBALL to 2, LEEROY to 1))

    @Test
    fun trackerRecordsTimelineIncludingUndoAndRemovals() {
        var state = TrackerState.start(deck, now = 0)
        state = state.draw(FIREBALL).draw(LEEROY).returnToDeck(LEEROY).draw(FIREBALL)
        state = state.nextTurn().draw(LEEROY).undoLastDraw()
        state = state.addOpponentCard(RARE_NEUTRAL).addOpponentCard(EPIC_MAGE).removeOpponentCardAt(0)
        state = state.nextTurn().addExtraDraw("TOKEN_01")
        val record = state.toMatchRecord(MatchResult.WIN, now = 1000)

        assertEquals(
            listOf(
                TimelineType.DRAW to 1, TimelineType.DRAW to 1, TimelineType.RETURN to 1, TimelineType.DRAW to 1,
                TimelineType.OPPONENT_PLAY to 2, TimelineType.EXTRA_DRAW to 3,
            ),
            record.timeline.map { it.type to it.turn },
        )
        assertEquals(listOf(FIREBALL, FIREBALL), MatchHistory.drawnCards(record))
        val turns = MatchHistory.turns(record)
        assertEquals(listOf(1, 2, 3), turns.map { it.turn })
        assertEquals(listOf(FIREBALL, LEEROY, FIREBALL), turns[0].drawn)
        assertEquals(listOf(LEEROY), turns[0].returned)
        assertEquals(listOf(EPIC_MAGE), turns[1].opponentPlayed)
        assertEquals(listOf("TOKEN_01"), turns[2].extraDrawn)
        assertTrue(state.resetForNewGame(5).timeline.isEmpty())
    }

    @Test
    fun oldRecordsWithoutTimelineStillLoad() {
        val json = """{"id":"x","timestamp":5,"result":"WIN","opponentClass":"ROGUE"}"""
        val record = AppJson.decodeFromString(MatchRecord.serializer(), json)
        assertEquals(HsClass.ROGUE, record.opponentClass)
        assertTrue(record.timeline.isEmpty())
        val encoded = AppJson.encodeToString(
            MatchRecord.serializer(),
            TrackerState.start(deck, 0).draw(FIREBALL).toMatchRecord(MatchResult.LOSS, 10),
        )
        assertTrue(encoded.contains("\"timeline\":[{\"t\":1,\"k\":\"DRAW\",\"d\":1}]"))
    }

    private val day = 24L * 60 * 60 * 1000
    private val matches = listOf(
        MatchRecord(id = "1", timestamp = 1 * day + 1000, deckId = "d1", deckName = "Feuer-Magier", playerClass = HsClass.MAGE,
            opponentClass = HsClass.ROGUE, result = MatchResult.WIN, opponentCards = listOf(LEEROY), opponentArchetype = "Tempo Schurke"),
        MatchRecord(id = "2", timestamp = 1 * day + 5000, deckId = "d1", deckName = "Feuer-Magier", playerClass = HsClass.MAGE,
            opponentClass = HsClass.PRIEST, result = MatchResult.LOSS, notes = "knapp; Topdeck"),
        MatchRecord(id = "3", timestamp = 3 * day, deckId = "d2", deckName = "Krieger", playerClass = HsClass.WARRIOR,
            opponentClass = HsClass.ROGUE, result = MatchResult.LOSS, format = GameFormat.WILD),
    )

    @Test
    fun filterAndSearch() {
        assertEquals(listOf("3", "2", "1"), MatchHistory.filter(matches, MatchQuery()).map { it.id })
        assertEquals(listOf("3", "2"), MatchHistory.filter(matches, MatchQuery(result = ResultFilter.LOSSES)).map { it.id })
        assertEquals(listOf("3", "1"), MatchHistory.filter(matches, MatchQuery(opponentClass = HsClass.ROGUE)).map { it.id })
        assertEquals(listOf("2", "1"), MatchHistory.filter(matches, MatchQuery(deckId = "d1")).map { it.id })
        assertEquals(listOf("3"), MatchHistory.filter(matches, MatchQuery(format = GameFormat.WILD)).map { it.id })
        assertEquals(listOf("1"), MatchHistory.filter(matches, MatchQuery(text = "tempo"), db).map { it.id })
        assertEquals(listOf("1"), MatchHistory.filter(matches, MatchQuery(text = "leeroy"), db).map { it.id })
        val germanClass = { m: MatchRecord -> if (m.opponentClass == HsClass.PRIEST) "Priester" else "" }
        assertEquals(listOf("2"), MatchHistory.filter(matches, MatchQuery(text = "topdeck priester"), labels = germanClass).map { it.id })
        assertEquals(listOf("3"), MatchHistory.filter(matches, MatchQuery(sinceMillis = 2 * day)).map { it.id })
    }

    @Test
    fun groupingByDay() {
        val days = MatchHistory.groupByDay(matches, ZoneOffset.UTC)
        assertEquals(listOf(LocalDate.of(1970, 1, 4), LocalDate.of(1970, 1, 2)), days.map { it.date })
        assertEquals(listOf("2", "1"), days[1].matches.map { it.id })
        assertEquals("1–1", days[1].winRate.label)
    }

    @Test
    fun csvExport() {
        val csv = MatchExporter.toCsv(matches, db, ZoneOffset.UTC).lines()
        assertTrue(csv[0].startsWith("date;result;deck"))
        assertEquals("1970-01-02 00:00;win;Feuer-Magier;mage;rogue;Tempo Schurke;standard;;;;manual;Leeroy Jenkins;", csv[1])
        assertTrue(csv[2].endsWith(";\"knapp; Topdeck\""))
    }
}
