package com.stroexd.hsdecktracker.core.tracker

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.stats.MatchRecord
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.stats.TimelineType
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

    /** Kandidaten für die automatische Deck-Erkennung (Priorität: Reihenfolge der Liste). */
    var deckCandidates: () -> List<Deck> = { emptyList() }

    /** dbfIds, die für Gegnerkarten bevorzugt werden (z. B. Karten aus Meta-Decks). */
    var preferredOpponentIds: () -> Set<Int> = { emptySet() }

    /** Von der Bilderkennung gesehene eigene Karten dieser Partie: Zug → mögliche dbfIds. */
    private val visionSeen = mutableListOf<Pair<Int, List<Int>>>()

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
     * Verarbeitet ein Spielereignis (aus der Bilderkennung oder dem Hearthstone-Log).
     * @return eine abgeschlossene Partie, wenn [recordResults] aktiv ist und das Spiel endete.
     */
    fun onGameEvent(event: GameEvent, db: CardDatabase, recordResults: Boolean = true): MatchRecord? {
        when (event) {
            GameEvent.GameStarted -> {
                val now = clock()
                visionSeen.clear()
                // Ohne fest gewähltes Deck wird das Deck jede Partie neu erkannt.
                val base = selectedDeck?.let { TrackerState.start(it, now) } ?: TrackerState.empty(now)
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
            is GameEvent.FriendlyCardSeen -> onFriendlyCardSeen(event.dbfIds, db)
            is GameEvent.OpponentCardSeen -> {
                if (event.dbfIds.isEmpty()) return null
                val preferred = preferredOpponentIds()
                val id = event.dbfIds.firstOrNull { it in preferred } ?: event.dbfIds.first()
                update { state ->
                    val updated = state.addOpponentCard(id)
                    val cls = db.byDbfId(id)?.hsClass
                    if (state.opponentClass == HsClass.UNKNOWN && cls != null && cls.isPlayable) updated.withOpponentClass(cls) else updated
                }
            }
        }
        return null
    }

    /**
     * Eigene Karte aus der Bilderkennung: dem Deck zuordnen oder – solange das Deck unbekannt ist –
     * das Deck anhand aller bisher gesehenen Karten erkennen (eigene Decks, dann Meta-Decks).
     */
    private fun onFriendlyCardSeen(candidates: List<Int>, db: CardDatabase) {
        val current = _state.value ?: return
        if (candidates.isEmpty()) return
        visionSeen += current.turn to candidates
        val seen = visionSeen.map { it.second }
        val deckKnown = current.deckCards.isNotEmpty()
        // Passt das (evtl. vorher gewählte) Deck offensichtlich nicht, wird neu erkannt.
        val mismatch = deckKnown && seen.size >= 3 &&
            DeckIdentifier.score(Deck(name = "", heroClass = current.playerClass, cards = current.deckCards), seen).matched * 2 < seen.size
        if (!deckKnown || mismatch) {
            val identified = DeckIdentifier.identify(seen, deckCandidates())
            if (identified != null && identified.cards != current.deckCards) {
                if (mismatch) selectedDeck = null
                _state.value = rebuildWithDeck(current, identified, db)
                return
            }
        }
        update { state ->
            val id = candidates.firstOrNull { state.remainingOf(it) > 0 }
            if (id != null) {
                state.draw(id)
            } else {
                val card = db.byDbfId(candidates.first())
                val withExtra = state.addExtraDraw(card?.id ?: candidates.first().toString())
                val cls = card?.hsClass
                if (state.playerClass == HsClass.UNKNOWN && cls != null && cls.isPlayable) withExtra.copy(playerClass = cls) else withExtra
            }
        }
    }

    /** Setzt das erkannte Deck für die laufende Partie und spielt die bisher gesehenen Karten nach. */
    private fun rebuildWithDeck(current: TrackerState, deck: Deck, db: CardDatabase): TrackerState {
        var state = TrackerState.start(deck, current.startedAt).copy(
            autoTracked = current.autoTracked,
            opponentClass = current.opponentClass,
            opponentCards = current.opponentCards,
            wentFirst = current.wentFirst,
            timeline = current.timeline.filter { it.type == TimelineType.OPPONENT_PLAY },
        )
        for ((turn, candidates) in visionSeen) {
            state = state.copy(turn = turn)
            val id = candidates.firstOrNull { state.remainingOf(it) > 0 }
            state = if (id != null) state.draw(id) else state.addExtraDraw(db.byDbfId(candidates.first())?.id ?: candidates.first().toString())
        }
        return state.copy(turn = current.turn)
    }

    /** Deck für die laufende Partie manuell festlegen (bereits gesehene Karten werden übernommen). */
    fun selectDeckForCurrentGame(deck: Deck, db: CardDatabase) {
        selectedDeck = deck
        val current = _state.value
        _state.value = if (current == null) TrackerState.start(deck, clock()) else rebuildWithDeck(current, deck, db)
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
