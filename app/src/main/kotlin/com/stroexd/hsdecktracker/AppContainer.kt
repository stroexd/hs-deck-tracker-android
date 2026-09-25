package com.stroexd.hsdecktracker

import android.content.Context
import android.content.res.Resources
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.data.CardRepository
import com.stroexd.hsdecktracker.core.data.CollectionRepository
import com.stroexd.hsdecktracker.core.data.DeckRepository
import com.stroexd.hsdecktracker.core.data.GameLocales
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
import com.stroexd.hsdecktracker.core.vision.CollectionScanner
import com.stroexd.hsdecktracker.core.vision.OcrFrame
import com.stroexd.hsdecktracker.core.vision.VisionGameTracker
import com.stroexd.hsdecktracker.vision.CapturePacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale

data class RecognitionStatus(
    val active: Boolean = false,
    val phase: VisionGameTracker.Phase = VisionGameTracker.Phase.IDLE,
    /** Only filled while the recognition debug line is shown (saves overlay redraws). */
    val recognized: List<String> = emptyList(),
    val frames: Int = 0,
    /** Frames that needed text recognition; the others were unchanged and reused the last result. */
    val ocrFrames: Int = 0,
    /** Set while the collection is read from Hearthstone instead of tracking games. */
    val scan: ScanProgress? = null,
)

data class ScanProgress(
    val pages: Int = 0,
    val cards: Int = 0,
    val copies: Int = 0,
    val lastPage: List<String> = emptyList(),
)

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

    /** English names are always recognized too: many play with an English client. */
    private val cardsEnglish = CardRepository(cacheDir, http)

    /** Hearthstone locale (e.g. "deDE") used for card data and the app language. */
    val gameLocale: StateFlow<String> = settings.settings
        .map { it.gameLocale(deviceLanguage()) }
        .stateIn(appScope, SharingStarted.Eagerly, settings.value.gameLocale(deviceLanguage()))

    val appLocale: StateFlow<Locale> = gameLocale
        .map(::toLocale)
        .stateIn(appScope, SharingStarted.Eagerly, toLocale(gameLocale.value))

    private val _recognition = MutableStateFlow(RecognitionStatus())
    val recognition: StateFlow<RecognitionStatus> = _recognition.asStateFlow()

    private var nameIndex: CardNameIndex? = null
    private var nameIndexSources: Pair<CardDatabase, CardDatabase>? = null
    private var visionTracker: VisionGameTracker? = null

    @Volatile
    private var collectionScanner: CollectionScanner? = null
    private var frameCount = 0
    private var ocrFrameCount = 0

    init {
        appScope.launch {
            gameLocale.collect { locale ->
                cards.load(locale)
                if (locale != GameLocales.ENGLISH) cardsEnglish.load(GameLocales.ENGLISH)
            }
        }
        appScope.launch { appLocale.collect { Locale.setDefault(it) } }
        tracker.deckCandidates = {
            decks.decks.value.sortedByDescending { it.updatedAt } + metaDecks().map { it.toDeck(0) }
        }
        tracker.preferredOpponentIds = { metaDecks().flatMapTo(HashSet()) { it.cards.keys } }
    }

    fun refreshCards() {
        appScope.launch { cards.load(gameLocale.value, forceRefresh = true) }
    }

    private fun metaDecks(): List<MetaDeck> =
        meta.state.value.snapshots[GameFormat.STANDARD]?.decks.orEmpty() +
            meta.state.value.snapshots[GameFormat.WILD]?.decks.orEmpty()

    fun predictOpponent(state: TrackerState, metaState: MetaState = meta.state.value): List<DeckPrediction> {
        val format = if (state.format == GameFormat.WILD) GameFormat.WILD else GameFormat.STANDARD
        val decks = metaState.snapshots[format]?.decks ?: return emptyList()
        if (!state.opponentClass.isPlayable) return emptyList()
        return OpponentPredictor.predict(state.opponentClass, state.opponentCards, decks)
    }

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

    fun onGameEvent(event: GameEvent) {
        if (event is GameEvent.GameEnded) {
            if (settings.value.autoRecordMatches) finishGame(event.result) else tracker.newGame()
            return
        }
        tracker.onGameEvent(event, cards.db)
    }

    fun selectDeckForCurrentGame(deck: Deck) {
        tracker.selectDeckForCurrentGame(deck, cards.db)
    }

    fun onRecognitionStarted() {
        visionTracker = null
        frameCount = 0
        ocrFrameCount = 0
        _recognition.value = RecognitionStatus(active = true, scan = _recognition.value.scan)
        appScope.launch { meta.refresh(GameFormat.STANDARD, settings.value, cards.db) }
    }

    fun onRecognitionStopped() {
        visionTracker = null
        collectionScanner = null
        _recognition.update { it.copy(active = false, phase = VisionGameTracker.Phase.IDLE, scan = null) }
    }

    fun startCollectionScan() {
        collectionScanner = null
        _recognition.update { it.copy(scan = ScanProgress()) }
    }

    fun finishCollectionScan(save: Boolean, onSaved: (changedCards: Int) -> Unit = {}) {
        val scanner = collectionScanner
        collectionScanner = null
        _recognition.update { it.copy(scan = null) }
        if (!save || scanner == null) return
        val totals = synchronized(scanner) { scanner.totals() }
        appScope.launch {
            val changed = collection.applyScan(totals, cards.db, settings.value.formatRules)
            withContext(Dispatchers.Main) { onSaved(changed) }
        }
    }

    /** Outside of a game the versus screen stays long enough for a slower pace. */
    fun capturePacing(): CapturePacing {
        if (_recognition.value.scan != null) return CapturePacing(intervalMillis = 400, maxReuseMillis = 1_000)
        return when (_recognition.value.phase) {
            VisionGameTracker.Phase.PLAYING -> CapturePacing(intervalMillis = 500, maxReuseMillis = 1_500)
            VisionGameTracker.Phase.MULLIGAN -> CapturePacing(intervalMillis = 600, maxReuseMillis = 1_500)
            VisionGameTracker.Phase.IDLE, VisionGameTracker.Phase.ENDED -> CapturePacing(intervalMillis = 1_500, maxReuseMillis = 4_000)
        }
    }

    /** Always called from the same background thread. */
    fun onScreenFrame(frame: OcrFrame, notes: MutableList<String>? = null, reused: Boolean = false): List<GameEvent> {
        val index = currentNameIndex() ?: return emptyList()
        if (_recognition.value.scan != null) {
            scanFrame(index, frame, notes)
            return emptyList()
        }
        val vision = visionTracker ?: VisionGameTracker(index, contextProvider = ::recognitionContext).also { visionTracker = it }
        vision.decisionLog = notes?.let { list -> { note: String -> list += note } }
        val events = vision.onFrame(frame)
        events.forEach { onGameEvent(it) }
        followClientLanguage(vision.gameLocale)
        frameCount++
        if (!reused) ocrFrameCount++
        // Every status change redraws the overlay, so publish only what is visible
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

    private fun scanFrame(index: CardNameIndex, frame: OcrFrame, notes: MutableList<String>?) {
        val scanner = collectionScanner ?: CollectionScanner(index).also { collectionScanner = it }
        val progress = synchronized(scanner) {
            scanner.decisionLog = notes?.let { list -> { note: String -> list += note } }
            if (!scanner.onFrame(frame)) return
            val totals = scanner.totals()
            ScanProgress(
                pages = scanner.pageCount,
                cards = totals.size,
                copies = totals.values.sum(),
                lastPage = scanner.lastPage.map { tile ->
                    val name = cards.db.byDbfId(tile.dbfIds.first())?.name ?: tile.name
                    if (tile.copies > 1) "$name ×${tile.copies}" else name
                },
            )
        }
        _recognition.update { status -> if (status.scan != null) status.copy(scan = progress) else status }
    }

    private fun followClientLanguage(detected: String?) {
        if (detected == null || detected == settings.value.detectedGameLocale) return
        appScope.launch { settings.update { it.copy(detectedGameLocale = detected) } }
    }

    /** Cards that are plausible right now; they are matched with a slightly lower threshold. */
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
        // Never swap the index mid-game, the game state would be lost
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
        const val STATUS_EVERY_FRAMES = 20

        fun deviceLanguage(): String = Resources.getSystem().configuration.locales[0].language

        fun toLocale(gameLocale: String): Locale =
            Locale.forLanguageTag(gameLocale.take(2) + "-" + gameLocale.drop(2))
    }
}
