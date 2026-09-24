package com.stroexd.hsdecktracker

import android.content.Context
import com.stroexd.hsdecktracker.core.data.CardRepository
import com.stroexd.hsdecktracker.core.data.CollectionRepository
import com.stroexd.hsdecktracker.core.data.DeckRepository
import com.stroexd.hsdecktracker.core.data.HttpClient
import com.stroexd.hsdecktracker.core.data.MatchRepository
import com.stroexd.hsdecktracker.core.data.MetaRepository
import com.stroexd.hsdecktracker.core.data.SettingsRepository
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.data.MetaState
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.deck.DeckCode
import com.stroexd.hsdecktracker.core.meta.DeckPrediction
import com.stroexd.hsdecktracker.core.meta.OpponentPredictor
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.tracker.DecksLogParser
import com.stroexd.hsdecktracker.core.tracker.GameEvent
import com.stroexd.hsdecktracker.core.tracker.TrackerController
import com.stroexd.hsdecktracker.core.tracker.TrackerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/** Einfache manuelle Dependency Injection – eine Instanz pro App-Prozess. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dataDir = File(context.filesDir, "data")
    private val cacheDir = File(context.filesDir, "cache")

    val okHttpClient: OkHttpClient = HttpClient.defaultClient()
    private val http = HttpClient(okHttpClient)

    val settings = SettingsRepository(dataDir)
    val decks = DeckRepository(dataDir)
    val collection = CollectionRepository(dataDir)
    val matches = MatchRepository(dataDir)
    val cards = CardRepository(cacheDir, http)
    val meta = MetaRepository(cacheDir, http)
    val tracker = TrackerController()

    init {
        // Kartendatenbank laden und bei Sprachwechsel neu laden.
        appScope.launch {
            settings.settings.map { it.cardLocale }.distinctUntilChanged().collect { locale ->
                cards.load(locale)
            }
        }
    }

    fun refreshCards() {
        appScope.launch { cards.load(settings.value.cardLocale, forceRefresh = true) }
    }

    /** Wahrscheinlichste Gegner-Decks anhand der Meta-Daten des passenden Formats. */
    fun predictOpponent(state: TrackerState, metaState: MetaState = meta.state.value): List<DeckPrediction> {
        val format = if (state.format == GameFormat.WILD) GameFormat.WILD else GameFormat.STANDARD
        val decks = metaState.snapshots[format]?.decks ?: return emptyList()
        if (!state.opponentClass.isPlayable) return emptyList()
        return OpponentPredictor.predict(state.opponentClass, state.opponentCards, decks)
    }

    /** Beendet die laufende Tracker-Partie und speichert sie in der Statistik. */
    fun finishGame(result: MatchResult) {
        val state = tracker.state.value ?: return
        val archetype = predictOpponent(state)
            .firstOrNull()
            ?.takeIf { it.matchedCards >= 2 }
            ?.deck
            ?.archetypeName
        val record = tracker.finishGame(result, archetype) ?: return
        appScope.launch { matches.add(record) }
    }

    /** Ereignis aus dem Hearthstone-Log (automatisches Tracking). */
    fun onLogEvent(event: GameEvent, catchingUp: Boolean) {
        if (event is GameEvent.GameEnded) {
            val result = event.result ?: return
            when {
                !catchingUp && settings.value.autoRecordMatches -> finishGame(result)
                else -> tracker.newGame()
            }
            return
        }
        tracker.onLogEvent(event, cards.db, recordResults = false)
    }

    /** In Hearthstone gewähltes Deck (aus Decks.log) übernehmen – neue Decks werden gespeichert. */
    fun onDeckSelectedInGame(selection: DecksLogParser.DeckSelection, catchingUp: Boolean) {
        val definition = DeckCode.decodeOrNull(selection.deckCode) ?: return
        val existing = decks.decks.value.firstOrNull { it.cards == definition.cards }
        val deck = existing ?: Deck.fromDefinition(definition, selection.name, cards.db, System.currentTimeMillis())
            .also { if (!catchingUp) appScope.launch { decks.upsert(it) } }
        tracker.onDeckSelected(deck)
    }
}
