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

    /** Von der Bilderkennung gesehene eigene Karten dieser Partie (für Deck-Erkennung und Nachspielen). */
    private class VisionCard(val turn: Int, val candidates: List<Int>, val returned: Boolean)

    private val visionSeen = mutableListOf<VisionCard>()

    /** Eigene Klasse laut Bilderkennung (Namensschild) – schränkt die Deck-Erkennung ein. */
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
                detectedPlayerClass = null
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
            is GameEvent.FriendlyCardSeen -> onFriendlyCardSeen(event.dbfIds, event.fromDeck, db)
            is GameEvent.FriendlyCardMulliganed -> onFriendlyCardMulliganed(event.dbfIds, db)
            is GameEvent.ClassDetected -> {
                if (!event.hsClass.isPlayable) return null
                if (event.friendly) {
                    detectedPlayerClass = event.hsClass
                    val current = _state.value ?: return null
                    if (current.deckCards.isNotEmpty() && current.playerClass.isPlayable && current.playerClass != event.hsClass) {
                        // Das zuletzt benutzte Deck gehört zu einer anderen Klasse → neu erkennen
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
                } else {
                    update { it.withOpponentClass(event.hsClass) }
                }
            }
            is GameEvent.OpponentCardSeen -> {
                if (event.dbfIds.isEmpty()) return null
                val current = _state.value ?: return null
                // Bekannte Gegnerklasse: nur Karten, die diese Klasse spielen darf (filtert Fehlerkennungen)
                val allowed = if (current.opponentClass.isPlayable) {
                    event.dbfIds.filter { id -> db.byDbfId(id)?.isAllowedIn(current.opponentClass) ?: true }
                } else {
                    event.dbfIds
                }
                if (allowed.isEmpty()) return null
                val preferred = preferredOpponentIds()
                val id = allowed.firstOrNull { it in preferred } ?: allowed.first()
                update { state ->
                    val updated = state.addOpponentCard(id)
                    if (state.opponentClass == HsClass.UNKNOWN) updated.withOpponentClass(inferOpponentClass(updated, db)) else updated
                }
            }
        }
        return null
    }

    /**
     * Gegnerklasse aus gespielten Karten (nur falls das Namensschild nicht gelesen wurde):
     * Die häufigste Klasse, sobald mindestens zwei Karten dazu passen.
     */
    private fun inferOpponentClass(state: TrackerState, db: CardDatabase): HsClass =
        state.opponentCards
            .mapNotNull { id -> db.byDbfId(id)?.hsClass?.takeIf { it.isPlayable } }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= 2 }
            .maxByOrNull { it.value }
            ?.key ?: HsClass.UNKNOWN

    /**
     * Eigene Karte aus der Bilderkennung: dem Deck zuordnen oder – solange das Deck unbekannt ist –
     * das Deck anhand aller bisher gesehenen Karten erkennen (eigene Decks, dann Meta-Decks).
     */
    private fun onFriendlyCardSeen(candidates: List<Int>, fromDeck: Boolean, db: CardDatabase) {
        val current = _state.value ?: return
        if (candidates.isEmpty()) return
        if (!fromDeck) {
            update { it.addExtraDraw(db.byDbfId(candidates.first())?.id ?: candidates.first().toString()) }
            return
        }
        visionSeen += VisionCard(current.turn, candidates, returned = false)
        if (identifyDeck(current, db)) return
        update { state -> drawSeen(state, candidates, db) }
    }

    /** Beim Mulligan ausgetauschte Karte: zurück ins Deck. */
    private fun onFriendlyCardMulliganed(candidates: List<Int>, db: CardDatabase) {
        val current = _state.value ?: return
        if (candidates.isEmpty()) return
        visionSeen += VisionCard(current.turn, candidates, returned = true)
        if (identifyDeck(current, db)) return
        update { state -> returnSeen(state, candidates, db) }
    }

    /** Erkennt das Deck neu, falls noch keins bekannt ist oder das gewählte offensichtlich nicht passt. */
    private fun identifyDeck(current: TrackerState, db: CardDatabase): Boolean {
        val seen = visionSeen.map { it.candidates }
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
        // Deck unbekannt: die zuvor als Zusatzkarte notierte Karte wieder entfernen
        val cardIds = candidates.mapNotNull { db.byDbfId(it)?.id } + candidates.map { it.toString() }
        return state.removeLastExtraDraw(cardIds)
    }

    /** Setzt das erkannte Deck für die laufende Partie und spielt die bisher gesehenen Karten nach. */
    private fun rebuildWithDeck(current: TrackerState, deck: Deck, db: CardDatabase): TrackerState {
        val seenCardIds = visionSeen.flatMap { seen -> seen.candidates.mapNotNull { db.byDbfId(it)?.id } + seen.candidates.map { it.toString() } }.toSet()
        var state = TrackerState.start(deck, current.startedAt).copy(
            autoTracked = current.autoTracked,
            opponentClass = current.opponentClass,
            opponentCards = current.opponentCards,
            wentFirst = current.wentFirst,
            // Zusatzkarten, die nicht aus dem Deck stammen (z. B. entdeckt), bleiben erhalten
            extraDraws = current.extraDraws.filter { it !in seenCardIds },
            timeline = current.timeline.filter { it.type == TimelineType.OPPONENT_PLAY },
        )
        for (seen in visionSeen) {
            state = state.copy(turn = seen.turn)
            state = if (seen.returned) returnSeen(state, seen.candidates, db) else drawSeen(state, seen.candidates, db)
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
