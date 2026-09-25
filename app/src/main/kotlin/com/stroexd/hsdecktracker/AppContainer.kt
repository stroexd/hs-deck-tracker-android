package com.stroexd.hsdecktracker

import android.content.Context
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.data.CardRepository
import com.stroexd.hsdecktracker.core.data.CollectionRepository
import com.stroexd.hsdecktracker.core.data.DeckRepository
import com.stroexd.hsdecktracker.core.data.HttpClient
import com.stroexd.hsdecktracker.core.data.MatchRepository
import com.stroexd.hsdecktracker.core.data.MetaRepository
import com.stroexd.hsdecktracker.core.data.MetaState
import com.stroexd.hsdecktracker.core.data.SettingsRepository
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.meta.DeckPrediction
import com.stroexd.hsdecktracker.core.meta.MetaDeck
import com.stroexd.hsdecktracker.core.meta.OpponentPredictor
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.tracker.GameEvent
import com.stroexd.hsdecktracker.core.tracker.TrackerController
import com.stroexd.hsdecktracker.core.tracker.TrackerState
import com.stroexd.hsdecktracker.core.vision.CardNameIndex
import com.stroexd.hsdecktracker.core.vision.OcrFrame
import com.stroexd.hsdecktracker.core.vision.VisionGameTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/** Zustand der automatischen Bildschirmerkennung (für Overlay und Einstellungen). */
data class RecognitionStatus(
    val active: Boolean = false,
    val phase: VisionGameTracker.Phase = VisionGameTracker.Phase.IDLE,
    val recognized: List<String> = emptyList(),
    val frames: Int = 0,
) {
    val phaseLabel: String
        get() = when (phase) {
            VisionGameTracker.Phase.IDLE -> "Warte auf Spielstart"
            VisionGameTracker.Phase.MULLIGAN -> "Mulligan erkannt"
            VisionGameTracker.Phase.PLAYING -> "Partie läuft"
            VisionGameTracker.Phase.ENDED -> "Partie beendet"
        }
}

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

    /** Englische Kartennamen – viele spielen mit englischem Client, die Erkennung kennt beide Sprachen. */
    private val cardsEnglish = CardRepository(cacheDir, http)
    val meta = MetaRepository(cacheDir, http)
    val tracker = TrackerController()

    private val _recognition = MutableStateFlow(RecognitionStatus())
    val recognition: StateFlow<RecognitionStatus> = _recognition.asStateFlow()

    private var nameIndex: CardNameIndex? = null
    private var nameIndexSources: Pair<CardDatabase, CardDatabase>? = null
    private var visionTracker: VisionGameTracker? = null

    init {
        // Kartendatenbank laden und bei Sprachwechsel neu laden.
        appScope.launch {
            settings.settings.map { it.cardLocale }.distinctUntilChanged().collect { locale ->
                cards.load(locale)
                if (locale != ENGLISH) cardsEnglish.load(ENGLISH)
            }
        }
        tracker.deckCandidates = {
            decks.decks.value.sortedByDescending { it.updatedAt } + metaDecks().map { it.toDeck(0) }
        }
        tracker.preferredOpponentIds = { metaDecks().flatMapTo(HashSet()) { it.cards.keys } }
    }

    fun refreshCards() {
        appScope.launch { cards.load(settings.value.cardLocale, forceRefresh = true) }
    }

    private fun metaDecks(): List<MetaDeck> =
        meta.state.value.snapshots[GameFormat.STANDARD]?.decks.orEmpty() +
            meta.state.value.snapshots[GameFormat.WILD]?.decks.orEmpty()

    /** Wahrscheinlichste Gegner-Decks anhand der Meta-Daten des passenden Formats. */
    fun predictOpponent(state: TrackerState, metaState: MetaState = meta.state.value): List<DeckPrediction> {
        val format = if (state.format == GameFormat.WILD) GameFormat.WILD else GameFormat.STANDARD
        val decks = metaState.snapshots[format]?.decks ?: return emptyList()
        if (!state.opponentClass.isPlayable) return emptyList()
        return OpponentPredictor.predict(state.opponentClass, state.opponentCards, decks)
    }

    /** Beendet die laufende Tracker-Partie und speichert sie in der Match-History. */
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

    /** Ereignis aus der automatischen Erkennung. */
    fun onGameEvent(event: GameEvent) {
        if (event is GameEvent.GameEnded) {
            val result = event.result ?: return
            if (settings.value.autoRecordMatches) finishGame(result) else tracker.newGame()
            return
        }
        tracker.onGameEvent(event, cards.db, recordResults = false)
    }

    /** Manuelle Deck-Wahl während einer automatisch erkannten Partie. */
    fun selectDeckForCurrentGame(deck: Deck) {
        tracker.selectDeckForCurrentGame(deck, cards.db)
    }

    // ------------------------------------------------------------------ Bildschirmerkennung

    fun onRecognitionStarted() {
        visionTracker = null
        _recognition.value = RecognitionStatus(active = true)
        // Meta-Decks für Deck-Erkennung und Gegner-Vorhersage bereithalten
        appScope.launch { meta.refresh(GameFormat.STANDARD, settings.value, cards.db) }
    }

    fun onRecognitionStopped() {
        visionTracker = null
        _recognition.update { it.copy(active = false, phase = VisionGameTracker.Phase.IDLE) }
    }

    /** Wertet ein erkanntes Bildschirmfoto aus (Aufruf immer vom selben Hintergrund-Thread). */
    fun onScreenFrame(frame: OcrFrame): List<GameEvent> {
        val index = currentNameIndex() ?: return emptyList()
        val vision = visionTracker ?: VisionGameTracker(index, contextProvider = ::recognitionContext).also { visionTracker = it }
        val events = vision.onFrame(frame)
        events.forEach { onGameEvent(it) }
        _recognition.update {
            it.copy(phase = vision.phase, recognized = vision.lastRecognized.take(8), frames = it.frames + 1)
        }
        return events
    }

    /**
     * Karten, die im aktuellen Kontext plausibel sind: das laufende Deck, alle eigenen Decks
     * und die Meta-Decks. Danach wird die Erkennung bevorzugt abgeglichen (weniger Fehltreffer).
     */
    private fun recognitionContext(): Set<Int> {
        val result = HashSet<Int>()
        tracker.state.value?.deckCards?.keys?.let { result += it }
        decks.decks.value.forEach { result += it.cards.keys }
        metaDecks().forEach { result += it.cards.keys }
        return result
    }

    private fun currentNameIndex(): CardNameIndex? {
        val primary = cards.db
        val english = cardsEnglish.db
        if (primary.isEmpty && english.isEmpty) return null
        val sources = primary to english
        val gameRunning = visionTracker?.phase.let { it == VisionGameTracker.Phase.MULLIGAN || it == VisionGameTracker.Phase.PLAYING }
        // Während einer Partie nicht wechseln – sonst ginge der Spielzustand verloren.
        if (sources != nameIndexSources && (nameIndex == null || !gameRunning)) {
            nameIndex = CardNameIndex(
                primary.deckCards.map { it.dbfId to it.name } + english.deckCards.map { it.dbfId to it.name },
            )
            nameIndexSources = sources
            visionTracker = null
        }
        return nameIndex
    }

    private companion object {
        const val ENGLISH = "enUS"
    }
}
