package com.stroexd.hsdecktracker.core.tracker

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.stats.MatchRecord
import com.stroexd.hsdecktracker.core.stats.MatchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hält die laufende Tracker-Partie – geteilt zwischen In-App-Tracker und Overlay.
 * Abgeschlossene Partien werden zurückgegeben, damit der Aufrufer sie speichert.
 */
class TrackerController(private val clock: () -> Long = System::currentTimeMillis) {

    private val _state = MutableStateFlow<TrackerState?>(null)
    val state: StateFlow<TrackerState?> = _state.asStateFlow()

    private var selectedDeck: Deck? = null

    fun start(deck: Deck) {
        selectedDeck = deck
        _state.value = TrackerState.start(deck, clock())
    }

    fun stop() {
        selectedDeck = null
        _state.value = null
    }

    fun update(transform: (TrackerState) -> TrackerState) {
        _state.update { current -> current?.let(transform) }
    }

    fun newGame() = update { it.resetForNewGame(clock()) }

    /** Beendet die aktuelle Partie mit einem Ergebnis und startet eine neue mit demselben Deck. */
    fun finishGame(result: MatchResult, opponentArchetype: String? = null): MatchRecord? {
        val current = _state.value ?: return null
        val record = current.toMatchRecord(result, clock(), opponentArchetype = opponentArchetype)
        _state.value = current.resetForNewGame(clock())
        return record
    }

    /**
     * Verarbeitet ein Ereignis aus dem Hearthstone-Log.
     * @return eine abgeschlossene Partie, wenn [recordResults] aktiv ist und das Spiel endete.
     */
    fun onLogEvent(event: GameEvent, db: CardDatabase, recordResults: Boolean = true): MatchRecord? {
        when (event) {
            GameEvent.GameStarted -> {
                val now = clock()
                val base = _state.value?.resetForNewGame(now)
                    ?: selectedDeck?.let { TrackerState.start(it, now) }
                    ?: TrackerState.empty(now)
                _state.value = base.copy(autoTracked = true)
            }
            is GameEvent.FormatDetected -> update { if (it.deckId == null) it.copy(format = event.format) else it }
            is GameEvent.FriendlyPlayerDetected -> Unit
            is GameEvent.HeroRevealed -> {
                val cls = heroClass(event.cardId, db)
                update { state ->
                    when {
                        !event.friendly -> state.withOpponentClass(cls)
                        state.playerClass == HsClass.UNKNOWN -> state.copy(playerClass = cls)
                        else -> state
                    }
                }
            }
            is GameEvent.FriendlyCardDrawn -> update { state ->
                val dbfId = matchDeckCard(event.cardId, state, db) { state.remainingOf(it) > 0 }
                if (dbfId != null) state.draw(dbfId) else state.addExtraDraw(event.cardId)
            }
            is GameEvent.FriendlyCardReturned -> update { state ->
                val dbfId = matchDeckCard(event.cardId, state, db) { state.remainingOf(it) < (state.deckCards[it] ?: 0) }
                if (dbfId != null) state.returnToDeck(dbfId) else state
            }
            is GameEvent.OpponentCardPlayed -> {
                val card = db.byCardId(event.cardId) ?: return null
                update { it.addOpponentCard(card.dbfId) }
            }
            is GameEvent.TurnChanged -> update { it.copy(turn = ((event.turn + 1) / 2).coerceAtLeast(1)) }
            is GameEvent.TurnOrderDetected -> update { it.withWentFirst(event.friendlyWentFirst) }
            is GameEvent.GameEnded -> {
                val result = event.result ?: return null
                if (!recordResults) return null
                return finishGame(result)
            }
        }
        return null
    }

    /** Übernimmt das in Hearthstone gewählte Deck (aus `Decks.log`). */
    fun onDeckSelected(deck: Deck) {
        selectedDeck = deck
        val current = _state.value
        if (current == null || current.deckId != deck.id) {
            _state.value = TrackerState.start(deck, clock()).copy(autoTracked = current?.autoTracked ?: false)
        }
    }

    private fun heroClass(cardId: String, db: CardDatabase): HsClass {
        val fromPrefix = HsClass.fromHeroCardId(cardId)
        if (fromPrefix.isPlayable) return fromPrefix
        return db.byCardId(cardId)?.hsClass?.takeIf { it.isPlayable } ?: HsClass.UNKNOWN
    }

    /**
     * Ordnet eine Karten-ID aus dem Log einer dbfId der Deckliste zu. Neben der exakten ID wird
     * über den Namen verglichen (z. B. Kernset- vs. Legacy-Druck derselben Karte).
     */
    private fun matchDeckCard(cardId: String, state: TrackerState, db: CardDatabase, accept: (Int) -> Boolean): Int? {
        val card = db.byCardId(cardId)
        if (card != null && card.dbfId in state.deckCards && accept(card.dbfId)) return card.dbfId
        val name = card?.name ?: return null
        return state.deckCards.keys.firstOrNull { id -> db.byDbfId(id)?.name == name && accept(id) }
    }
}
