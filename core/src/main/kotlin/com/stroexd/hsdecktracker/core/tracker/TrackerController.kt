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

/** The running game, shared by the in-app tracker and the overlay. */
class TrackerController(private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow<TrackerState?>(null)
    val state: StateFlow<TrackerState?> = _state.asStateFlow()

    private var selectedDeck: Deck? = null

    /** Decks the tracker may recognize, in priority order. */
    var deckCandidates: () -> List<Deck> = { emptyList() }

    var preferredOpponentIds: () -> Set<Int> = { emptySet() }

    private class SeenCard(val turn: Int, val candidates: List<Int>, val returned: Boolean)

    private val seenCards = mutableListOf<SeenCard>()
    private var detectedPlayerClass: HsClass? = null

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

    fun finishGame(result: MatchResult, opponentArchetype: String? = null): MatchRecord? {
        val current = _state.value ?: return null
        val record = current.toMatchRecord(result, clock(), opponentArchetype = opponentArchetype)
        _state.value = current.resetForNewGame(clock())
        return record
    }

    fun onGameEvent(event: GameEvent, db: CardDatabase): MatchRecord? {
        when (event) {
            GameEvent.GameStarted -> {
                seenCards.clear()
                detectedPlayerClass = null
                val now = clock()
                val base = selectedDeck?.let { TrackerState.start(it, now) } ?: TrackerState.empty(now)
                _state.value = base.copy(autoTracked = true)
            }
            is GameEvent.ClassDetected -> onClassDetected(event)
            is GameEvent.FriendlyCardSeen -> onFriendlyCardSeen(event.dbfIds, event.fromDeck, db)
            is GameEvent.FriendlyCardMulliganed -> onFriendlyCardMulliganed(event.dbfIds, db)
            is GameEvent.OpponentCardSeen -> onOpponentCardSeen(event.dbfIds, db)
            is GameEvent.TurnChanged -> update { it.copy(turn = ((event.turn + 1) / 2).coerceAtLeast(1)) }
            is GameEvent.TurnOrderDetected -> update { it.withWentFirst(event.friendlyWentFirst) }
            is GameEvent.GameEnded -> return finishGame(event.result)
        }
        return null
    }

    private fun onClassDetected(event: GameEvent.ClassDetected) {
        if (!event.hsClass.isPlayable) return
        if (!event.friendly) {
            update { it.withOpponentClass(event.hsClass) }
            return
        }
        detectedPlayerClass = event.hsClass
        val current = _state.value ?: return
        if (current.deckCards.isNotEmpty() && current.playerClass.isPlayable && current.playerClass != event.hsClass) {
            // The last used deck belongs to another class: recognize again
            selectedDeck = null
            _state.value = TrackerState.empty(current.startedAt).copy(
                autoTracked = current.autoTracked,
                playerClass = event.hsClass,
                opponentClass = current.opponentClass,
                wentFirst = current.wentFirst,
            )
        } else if (current.deckCards.isEmpty() || current.playerClass == HsClass.UNKNOWN) {
            _state.value = current.copy(playerClass = event.hsClass)
        }
    }

    private fun onOpponentCardSeen(dbfIds: List<Int>, db: CardDatabase) {
        val current = _state.value ?: return
        // Cards the opponent's class can't play are misreadings
        val allowed = if (current.opponentClass.isPlayable) {
            dbfIds.filter { id -> db.byDbfId(id)?.isAllowedIn(current.opponentClass) ?: true }
        } else {
            dbfIds
        }
        if (allowed.isEmpty()) return
        val preferred = preferredOpponentIds()
        val id = allowed.firstOrNull { it in preferred } ?: allowed.first()
        update { state ->
            val updated = state.addOpponentCard(id)
            if (state.opponentClass == HsClass.UNKNOWN) updated.withOpponentClass(inferOpponentClass(updated, db)) else updated
        }
    }

    private fun inferOpponentClass(state: TrackerState, db: CardDatabase): HsClass =
        state.opponentCards
            .mapNotNull { id -> db.byDbfId(id)?.hsClass?.takeIf { it.isPlayable } }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= 2 }
            .maxByOrNull { it.value }
            ?.key ?: HsClass.UNKNOWN

    private fun onFriendlyCardSeen(candidates: List<Int>, fromDeck: Boolean, db: CardDatabase) {
        val current = _state.value ?: return
        if (candidates.isEmpty()) return
        if (!fromDeck) {
            update { it.addExtraDraw(db.byDbfId(candidates.first())?.id ?: candidates.first().toString()) }
            return
        }
        seenCards += SeenCard(current.turn, candidates, returned = false)
        if (identifyDeck(current, db)) return
        update { state -> drawSeen(state, candidates, db) }
    }

    private fun onFriendlyCardMulliganed(candidates: List<Int>, db: CardDatabase) {
        val current = _state.value ?: return
        if (candidates.isEmpty()) return
        seenCards += SeenCard(current.turn, candidates, returned = true)
        if (identifyDeck(current, db)) return
        update { state -> returnSeen(state, candidates, db) }
    }

    /** Recognizes the deck while none is known or the chosen one obviously doesn't match. */
    private fun identifyDeck(current: TrackerState, db: CardDatabase): Boolean {
        val seen = seenCards.map { it.candidates }
        val deckKnown = current.deckCards.isNotEmpty()
        val mismatch = deckKnown && seen.size >= 3 &&
            DeckIdentifier.score(Deck(name = "", heroClass = current.playerClass, cards = current.deckCards), seen).matched * 2 < seen.size
        if (deckKnown && !mismatch) return false
        val cls = detectedPlayerClass
        val candidates = deckCandidates().filter { cls == null || it.heroClass == cls }
        val identified = DeckIdentifier.identify(seen, candidates) ?: return false
        if (identified.cards == current.deckCards) return false
        if (mismatch) selectedDeck = null
        _state.value = rebuildWithDeck(current, identified, db)
        return true
    }

    private fun drawSeen(state: TrackerState, candidates: List<Int>, db: CardDatabase): TrackerState {
        val id = candidates.firstOrNull { state.remainingOf(it) > 0 }
        if (id != null) return state.draw(id)
        val card = db.byDbfId(candidates.first())
        val withExtra = state.addExtraDraw(card?.id ?: candidates.first().toString())
        val cls = card?.hsClass
        return if (state.playerClass == HsClass.UNKNOWN && cls != null && cls.isPlayable) withExtra.copy(playerClass = cls) else withExtra
    }

    private fun returnSeen(state: TrackerState, candidates: List<Int>, db: CardDatabase): TrackerState {
        val id = candidates.firstOrNull { state.remainingOf(it) < (state.deckCards[it] ?: 0) }
        if (id != null) return state.returnToDeck(id)
        return state.removeLastExtraDraw(cardIds(candidates, db))
    }

    private fun cardIds(candidates: List<Int>, db: CardDatabase): Set<String> =
        (candidates.mapNotNull { db.byDbfId(it)?.id } + candidates.map { it.toString() }).toSet()

    /** Applies [deck] to the running game and replays the cards seen so far. */
    private fun rebuildWithDeck(current: TrackerState, deck: Deck, db: CardDatabase): TrackerState {
        val seenIds = seenCards.flatMap { cardIds(it.candidates, db) }.toSet()
        var state = TrackerState.start(deck, current.startedAt).copy(
            autoTracked = current.autoTracked,
            opponentClass = current.opponentClass,
            opponentCards = current.opponentCards,
            wentFirst = current.wentFirst,
            extraDraws = current.extraDraws.filter { it !in seenIds },
            timeline = current.timeline.filter { it.type == TimelineType.OPPONENT_PLAY },
        )
        for (seen in seenCards) {
            state = state.copy(turn = seen.turn)
            state = if (seen.returned) returnSeen(state, seen.candidates, db) else drawSeen(state, seen.candidates, db)
        }
        return state.copy(turn = current.turn)
    }

    fun selectDeckForCurrentGame(deck: Deck, db: CardDatabase) {
        selectedDeck = deck
        val current = _state.value
        _state.value = if (current == null) TrackerState.start(deck, clock()) else rebuildWithDeck(current, deck, db)
    }
}
