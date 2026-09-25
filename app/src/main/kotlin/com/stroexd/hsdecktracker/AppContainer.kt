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
import com.stroexd.hsdecktracker.vision.CapturePacing
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
    /** Zuletzt erkannte Kartennamen – nur bei eingeschalteter Erkennungsanzeige (spart Neuzeichnen). */
    val recognized: List<String> = emptyList(),
    /** Ausgewertete Bildschirmfotos (wird nur gelegentlich aktualisiert). */
    val frames: Int = 0,
    /** Davon mit Texterkennung – der Rest war unverändert und hat das letzte Ergebnis wiederverwendet. */
    val ocrFrames: Int = 0,
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
    private var frameCount = 0
    private var ocrFrameCount = 0

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
        frameCount = 0
        ocrFrameCount = 0
        _recognition.value = RecognitionStatus(active = true)
        // Meta-Decks für Deck-Erkennung und Gegner-Vorhersage bereithalten
        appScope.launch { meta.refresh(GameFormat.STANDARD, settings.value, cards.db) }
    }

    fun onRecognitionStopped() {
        visionTracker = null
        _recognition.update { it.copy(active = false, phase = VisionGameTracker.Phase.IDLE) }
    }

    /**
     * Takt der Bildschirmauswertung: in einer Partie etwa zwei Bilder pro Sekunde, sonst (Menü,
     * nach Spielende) nur alle 1,5 s – der Versus-Bildschirm ist lange genug sichtbar.
     */
    fun capturePacing(): CapturePacing = when (_recognition.value.phase) {
        VisionGameTracker.Phase.PLAYING -> CapturePacing(intervalMillis = 500, maxReuseMillis = 1_500)
        VisionGameTracker.Phase.MULLIGAN -> CapturePacing(intervalMillis = 600, maxReuseMillis = 1_500)
        VisionGameTracker.Phase.IDLE, VisionGameTracker.Phase.ENDED -> CapturePacing(intervalMillis = 1_500, maxReuseMillis = 4_000)
    }

    /**
     * Wertet ein erkanntes Bildschirmfoto aus (Aufruf immer vom selben Hintergrund-Thread).
     * @param notes nimmt für den Diagnose-Modus die Begründungen der Erkennung auf.
     * @param reused Bild war unverändert, das Ergebnis stammt aus der vorigen Texterkennung.
     */
    fun onScreenFrame(frame: OcrFrame, notes: MutableList<String>? = null, reused: Boolean = false): List<GameEvent> {
        val index = currentNameIndex() ?: return emptyList()
        val vision = visionTracker ?: VisionGameTracker(index, contextProvider = ::recognitionContext).also { visionTracker = it }
        vision.decisionLog = notes?.let { list -> { note: String -> list += note } }
        val events = vision.onFrame(frame)
        events.forEach { onGameEvent(it) }
        frameCount++
        if (!reused) ocrFrameCount++
        // Status nur bei Bedarf veröffentlichen – jede Änderung zeichnet das Overlay neu
        val debug = settings.value.showRecognitionDebug
        val current = _recognition.value
        if (current.phase != vision.phase || debug || frameCount % STATUS_EVERY_FRAMES == 0) {
            _recognition.value = current.copy(
                phase = vision.phase,
                recognized = if (debug) vision.lastRecognized.take(8) else emptyList(),
                frames = frameCount,
                ocrFrames = ocrFrameCount,
            )
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
        const val STATUS_EVERY_FRAMES = 20
    }
}
