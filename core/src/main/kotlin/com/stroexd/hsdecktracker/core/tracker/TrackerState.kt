package com.stroexd.hsdecktracker.core.tracker

import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.stats.DrawOdds
import com.stroexd.hsdecktracker.core.stats.MatchRecord
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.stats.MatchSource
import com.stroexd.hsdecktracker.core.stats.TimelineEvent
import com.stroexd.hsdecktracker.core.stats.TimelineType

/**
 * Zustand einer laufenden Partie im Tracker. Unveränderlich – jede Aktion liefert einen neuen Zustand.
 */
data class TrackerState(
    val deckId: String?,
    val deckName: String,
    val playerClass: HsClass,
    val format: GameFormat,
    /** Ursprüngliche Deckliste (dbfId → Anzahl). */
    val deckCards: Map<Int, Int>,
    /** Karten, die noch im Deck sind. */
    val remaining: Map<Int, Int>,
    /** Gezogene Karten in Reihenfolge (für „Rückgängig“). */
    val drawHistory: List<Int> = emptyList(),
    /** Karten-IDs, die gezogen wurden, aber nicht aus der Deckliste stammen (z. B. generierte Karten). */
    val extraDraws: List<String> = emptyList(),
    val opponentClass: HsClass = HsClass.UNKNOWN,
    /** Vom Gegner gespielte Karten (dbfIds) in Reihenfolge. */
    val opponentCards: List<Int> = emptyList(),
    val turn: Int = 1,
    val wentFirst: Boolean? = null,
    val startedAt: Long,
    val autoTracked: Boolean = false,
    /** Verlauf der Partie (Ziehen, Zurückmischen, Gegnerkarten) für die Match-History. */
    val timeline: List<TimelineEvent> = emptyList(),
) {
    val initialCount: Int get() = deckCards.values.sum()
    val remainingCount: Int get() = remaining.values.sum()
    val drawnCount: Int get() = initialCount - remainingCount

    fun remainingOf(dbfId: Int): Int = remaining[dbfId] ?: 0

    fun nextDrawChance(dbfId: Int): Double = DrawOdds.nextDraw(remainingCount, remainingOf(dbfId))

    private fun event(type: TimelineType, dbfId: Int? = null, cardId: String? = null) =
        TimelineEvent(turn, type, dbfId, cardId)

    fun draw(dbfId: Int): TrackerState {
        val left = remainingOf(dbfId)
        if (left <= 0) return this
        return copy(
            remaining = remaining + (dbfId to left - 1),
            drawHistory = drawHistory + dbfId,
            timeline = timeline + event(TimelineType.DRAW, dbfId),
        )
    }

    fun undoLastDraw(): TrackerState {
        val last = drawHistory.lastOrNull() ?: return this
        val index = timeline.indexOfLast { it.type == TimelineType.DRAW && it.dbfId == last }
        return copy(
            remaining = remaining + (last to remainingOf(last) + 1),
            drawHistory = drawHistory.dropLast(1),
            timeline = if (index >= 0) timeline.toMutableList().apply { removeAt(index) } else timeline,
        )
    }

    /** Karte zurück ins Deck (Mulligan, „Mische ins Deck“ …), höchstens bis zur ursprünglichen Anzahl. */
    fun returnToDeck(dbfId: Int): TrackerState {
        val original = deckCards[dbfId] ?: return this
        val left = remainingOf(dbfId)
        if (left >= original) return this
        val index = drawHistory.lastIndexOf(dbfId)
        val history = if (index >= 0) drawHistory.toMutableList().apply { removeAt(index) } else drawHistory
        return copy(
            remaining = remaining + (dbfId to left + 1),
            drawHistory = history,
            timeline = timeline + event(TimelineType.RETURN, dbfId),
        )
    }

    fun addExtraDraw(cardId: String): TrackerState =
        copy(extraDraws = extraDraws + cardId, timeline = timeline + event(TimelineType.EXTRA_DRAW, cardId = cardId))

    /** Entfernt die zuletzt notierte Zusatzkarte mit einer der Karten-IDs (z. B. beim Mulligan zurückgelegt). */
    fun removeLastExtraDraw(cardIds: Collection<String>): TrackerState {
        val index = extraDraws.indexOfLast { it in cardIds }
        if (index < 0) return this
        val timelineIndex = timeline.indexOfLast { it.type == TimelineType.EXTRA_DRAW && it.cardId == extraDraws[index] }
        return copy(
            extraDraws = extraDraws.toMutableList().apply { removeAt(index) },
            timeline = if (timelineIndex >= 0) timeline.toMutableList().apply { removeAt(timelineIndex) } else timeline,
        )
    }

    fun addOpponentCard(dbfId: Int): TrackerState =
        copy(opponentCards = opponentCards + dbfId, timeline = timeline + event(TimelineType.OPPONENT_PLAY, dbfId))

    fun removeOpponentCardAt(index: Int): TrackerState {
        if (index !in opponentCards.indices) return this
        // Das index-te Gegner-Ereignis im Verlauf entfernen
        var seen = -1
        val eventIndex = timeline.indexOfFirst { it.type == TimelineType.OPPONENT_PLAY && ++seen == index }
        return copy(
            opponentCards = opponentCards.toMutableList().apply { removeAt(index) },
            timeline = if (eventIndex >= 0) timeline.toMutableList().apply { removeAt(eventIndex) } else timeline,
        )
    }

    fun withOpponentClass(cls: HsClass): TrackerState = copy(opponentClass = cls)

    fun nextTurn(): TrackerState = copy(turn = turn + 1)

    fun previousTurn(): TrackerState = copy(turn = (turn - 1).coerceAtLeast(1))

    fun withWentFirst(first: Boolean?): TrackerState = copy(wentFirst = first)

    /** Neues Spiel mit demselben Deck. */
    fun resetForNewGame(now: Long): TrackerState = copy(
        remaining = deckCards,
        drawHistory = emptyList(),
        extraDraws = emptyList(),
        opponentClass = HsClass.UNKNOWN,
        opponentCards = emptyList(),
        turn = 1,
        wentFirst = null,
        startedAt = now,
        timeline = emptyList(),
    )

    fun toMatchRecord(
        result: MatchResult,
        now: Long,
        source: MatchSource = if (autoTracked) MatchSource.LOG else MatchSource.TRACKER,
        opponentArchetype: String? = null,
    ): MatchRecord = MatchRecord(
        timestamp = now,
        deckId = deckId,
        deckName = deckName,
        playerClass = playerClass,
        opponentClass = opponentClass,
        result = result,
        format = format,
        wentFirst = wentFirst,
        turns = turn,
        durationSeconds = ((now - startedAt) / 1000).toInt().takeIf { it > 0 },
        opponentCards = opponentCards,
        opponentArchetype = opponentArchetype,
        source = source,
        timeline = timeline,
    )

    companion object {
        fun start(deck: Deck, now: Long): TrackerState = TrackerState(
            deckId = deck.id,
            deckName = deck.name,
            playerClass = deck.heroClass,
            format = deck.format,
            deckCards = deck.cards,
            remaining = deck.cards,
            startedAt = now,
        )

        /** Tracker ohne bekanntes Deck (z. B. automatisch gestartet). */
        fun empty(now: Long): TrackerState = TrackerState(
            deckId = null,
            deckName = "Unbekanntes Deck",
            playerClass = HsClass.UNKNOWN,
            format = GameFormat.STANDARD,
            deckCards = emptyMap(),
            remaining = emptyMap(),
            startedAt = now,
        )
    }
}
