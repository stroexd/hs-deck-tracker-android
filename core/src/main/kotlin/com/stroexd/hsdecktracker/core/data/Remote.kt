package com.stroexd.hsdecktracker.core.data

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.SetNames
import com.stroexd.hsdecktracker.core.meta.DeckListParser
import com.stroexd.hsdecktracker.core.meta.HsReplayParser
import com.stroexd.hsdecktracker.core.meta.MetaSnapshot
import com.stroexd.hsdecktracker.core.util.AppJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int) : IOException("HTTP $code")

sealed interface LoadError {
    /** HSReplay refuses some rank/time filters without premium. */
    data class Forbidden(val code: Int) : LoadError
    data class Http(val code: Int) : LoadError
    data class Network(val detail: String) : LoadError
    data object NoData : LoadError
    data object InvalidUrl : LoadError
    data object NoDecksAtUrl : LoadError

    companion object {
        fun of(e: Exception): LoadError = when {
            e is LoadException -> e.error
            e is HttpException && (e.code == 401 || e.code == 403) -> Forbidden(e.code)
            e is HttpException -> Http(e.code)
            else -> Network(e.message ?: e.javaClass.simpleName)
        }
    }
}

class LoadException(val error: LoadError) : IOException(error.toString())

data class HttpResponse(val code: Int, val body: String)

/** Where a URL leads after redirects, and its ETag or modification date. */
data class HttpHead(val url: String, val tag: String?)

class HttpClient(private val client: OkHttpClient = defaultClient()) {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpException(response.code)
            HttpResponse(response.code, body)
        }
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String = get(url, headers).body

    suspend fun head(url: String): HttpHead = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).head().header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code)
            HttpHead(response.request.url.toString(), response.header("ETag") ?: response.header("Last-Modified"))
        }
    }

    companion object {
        const val USER_AGENT = "HSDeckTracker-Android/1.0 (+https://github.com/stroexd/hs-deck-tracker-android)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

data class CardDataState(
    val db: CardDatabase = CardDatabase.EMPTY,
    val loading: Boolean = false,
    val error: LoadError? = null,
    val lastUpdated: Long? = null,
    val locale: String = "",
)

/**
 * Card data from HearthstoneJSON plus set names from Hearthstone's own strings. A cheap check for a new game
 * build (the "latest" URL redirects to the build) brings new sets in as soon as they are out.
 */
class CardRepository(
    private val dir: File,
    private val http: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val withSetNames: Boolean = true,
    private val cardsUrl: String = CARDS_URL,
    private val stringsUrl: String = STRINGS_URL,
) {
    private val _state = MutableStateFlow(CardDataState())
    val state: StateFlow<CardDataState> = _state.asStateFlow()
    val db: CardDatabase get() = _state.value.db
    private val mutex = Mutex()
    private var version: String? = null
    private var lastCheck = 0L

    private fun cacheFile(locale: String) = File(dir, "cards_$locale.json")
    private fun versionFile(locale: String) = File(dir, "cards_$locale.version")
    private fun setNamesFile(locale: String) = File(dir, "sets_$locale.json")

    suspend fun load(locale: String, forceRefresh: Boolean = false) = mutex.withLock {
        if (_state.value.locale != locale || _state.value.db.isEmpty) loadCached(locale)
        val current = _state.value
        if (forceRefresh || current.locale != locale || current.db.isEmpty) {
            lastCheck = clock()
            download(locale, latestVersion(locale))
        } else {
            refreshIfOutdated(locale)
        }
    }

    /** At most every few hours; cheap unless there is a new build. */
    suspend fun checkForUpdate() = mutex.withLock {
        val locale = _state.value.locale
        if (locale.isNotEmpty()) refreshIfOutdated(locale)
    }

    private suspend fun refreshIfOutdated(locale: String) {
        if (clock() - lastCheck < CHECK_INTERVAL_MILLIS) return
        lastCheck = clock()
        val latest = latestVersion(locale)
        val outdated = if (latest != null) latest != version else clock() - (_state.value.lastUpdated ?: 0L) > MAX_AGE_MILLIS
        val db = _state.value.db
        when {
            outdated -> download(locale, latest)
            // The strings sometimes name a new set a little later than the cards arrive
            withSetNames && db.sets.any { !db.hasSetName(it) } -> fetchSetNames(locale, db)?.let { names ->
                saveSetNames(locale, names)
                _state.update { it.copy(db = db.withSetNames(names)) }
            }
        }
    }

    private suspend fun latestVersion(locale: String): String? = runCatching {
        val head = http.head(cardsUrl.format(locale))
        buildNumber.find(head.url)?.groupValues?.get(1) ?: head.tag
    }.getOrNull()

    private suspend fun loadCached(locale: String) = withContext(Dispatchers.IO) {
        val file = cacheFile(locale)
        val cached = if (file.exists()) runCatching { CardDatabase.parse(file.readText(), locale) }.getOrNull() else null
        if (cached == null || cached.isEmpty) return@withContext
        version = versionFile(locale).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
        _state.value = CardDataState(db = cached.withSetNames(cachedSetNames(locale)), lastUpdated = file.lastModified(), locale = locale)
    }

    private suspend fun download(locale: String, latest: String?) {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val json = http.getText(cardsUrl.format(locale))
            val parsed = withContext(Dispatchers.Default) { CardDatabase.parse(json, locale) }
            if (parsed.isEmpty) throw LoadException(LoadError.NoData)
            val names = (if (withSetNames) fetchSetNames(locale, parsed) else null) ?: cachedSetNames(locale)
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                writeAtomically(cacheFile(locale), json)
                saveSetNames(locale, names)
                versionFile(locale).writeText(latest.orEmpty())
            }
            version = latest
            _state.value = CardDataState(db = parsed.withSetNames(names), loading = false, lastUpdated = clock(), locale = locale)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = LoadError.of(e)) }
        }
    }

    /** Without them sets keep their built-in names, so failures only cost the translation. */
    private suspend fun fetchSetNames(locale: String, db: CardDatabase): Map<String, String>? = runCatching {
        val strings = SetNames.parseStrings(http.getText(stringsUrl.format(locale)))
        withContext(Dispatchers.Default) { SetNames.resolve(strings, db.deckCards) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun cachedSetNames(locale: String): Map<String, String> = runCatching {
        AppJson.decodeFromString(setNamesSerializer, setNamesFile(locale).readText())
    }.getOrDefault(emptyMap())

    private suspend fun saveSetNames(locale: String, names: Map<String, String>) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        writeAtomically(setNamesFile(locale), AppJson.encodeToString(setNamesSerializer, names))
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val CARDS_URL = "https://api.hearthstonejson.com/v1/latest/%s/cards.collectible.json"
        const val STRINGS_URL = "https://raw.githubusercontent.com/HearthSim/hsdata/master/Strings/%s/GLOBAL.txt"
        private const val CHECK_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
        private const val MAX_AGE_MILLIS = 3L * 24 * 60 * 60 * 1000
        private val buildNumber = Regex("/v1/(\\d+)/")
        private val setNamesSerializer = MapSerializer(String.serializer(), String.serializer())

        fun renderUrl(cardId: String, locale: String, size: Int = 256) =
            "https://art.hearthstonejson.com/v1/render/latest/$locale/${size}x/$cardId.png"

        fun tileUrl(cardId: String) = "https://art.hearthstonejson.com/v1/tiles/$cardId.png"

        fun artUrl(cardId: String) = "https://art.hearthstonejson.com/v1/256x/$cardId.jpg"
    }
}

data class MetaState(
    val snapshots: Map<GameFormat, MetaSnapshot> = emptyMap(),
    val loading: Boolean = false,
    val error: LoadError? = null,
)

class MetaRepository(
    private val dir: File,
    private val http: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(MetaState(snapshots = loadCached()))
    val state: StateFlow<MetaState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var archetypes: Map<Int, String> = emptyMap()

    private fun cacheFile(format: GameFormat) = File(dir, "meta_${format.name.lowercase()}.json")

    private fun loadCached(): Map<GameFormat, MetaSnapshot> = supportedFormats.mapNotNull { format ->
        val file = cacheFile(format)
        if (!file.exists()) return@mapNotNull null
        runCatching { format to AppJson.decodeFromString(MetaSnapshot.serializer(), file.readText()) }.getOrNull()
    }.toMap()

    fun snapshot(format: GameFormat): MetaSnapshot? = _state.value.snapshots[format]

    suspend fun refresh(format: GameFormat, settings: AppSettings, db: CardDatabase, force: Boolean = false) = mutex.withLock {
        val description = describe(format, settings)
        val existing = _state.value.snapshots[format]
        if (!force && existing != null && existing.description == description &&
            clock() - existing.fetchedAt < MAX_AGE_MILLIS
        ) {
            return@withLock
        }
        _state.update { it.copy(loading = true, error = null) }
        try {
            val snapshot = when (settings.metaSource) {
                MetaSourceType.HSREPLAY -> fetchHsReplay(format, settings, description)
                MetaSourceType.CUSTOM_URL -> fetchCustom(format, settings, db, description)
            }
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                cacheFile(format).writeText(AppJson.encodeToString(MetaSnapshot.serializer(), snapshot))
            }
            _state.update { it.copy(snapshots = it.snapshots + (format to snapshot), loading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = LoadError.of(e)) }
        }
    }

    private suspend fun fetchHsReplay(format: GameFormat, settings: AppSettings, description: String): MetaSnapshot {
        if (archetypes.isEmpty()) {
            archetypes = runCatching {
                HsReplayParser.parseArchetypes(http.getText(ARCHETYPES_URL, jsonHeaders))
            }.getOrDefault(emptyMap())
        }
        val gameType = if (format == GameFormat.WILD) "RANKED_WILD" else "RANKED_STANDARD"
        val url = "$DECKS_URL?GameType=$gameType&LeagueRankRange=${settings.metaRankRange.name}" +
            "&Region=ALL&TimeRange=${settings.metaTimeRange.name}"
        // HSReplay answers 202 while the query is still being computed.
        var body = ""
        for (attempt in 0 until 6) {
            val response = http.get(url, jsonHeaders)
            if (response.code == 200 && response.body.isNotBlank()) {
                body = response.body
                break
            }
            delay(2500L * (attempt + 1))
        }
        if (body.isBlank()) throw LoadException(LoadError.NoData)
        val decks = HsReplayParser.parseDecks(body, archetypes, format)
        return MetaSnapshot(decks = decks, fetchedAt = clock(), source = "HSReplay.net", description = description)
    }

    private suspend fun fetchCustom(
        format: GameFormat,
        settings: AppSettings,
        db: CardDatabase,
        description: String,
    ): MetaSnapshot {
        val url = settings.metaCustomUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw LoadException(LoadError.InvalidUrl)
        }
        val decks = DeckListParser.parse(http.getText(url), db)
            .filter { format == GameFormat.WILD || it.format == format }
        if (decks.isEmpty()) throw LoadException(LoadError.NoDecksAtUrl)
        return MetaSnapshot(decks = decks, fetchedAt = clock(), source = url, description = description)
    }

    /** Identifies the query a cached snapshot was made for. */
    private fun describe(format: GameFormat, settings: AppSettings): String = when (settings.metaSource) {
        MetaSourceType.HSREPLAY -> "hsreplay|$format|${settings.metaRankRange}|${settings.metaTimeRange}"
        MetaSourceType.CUSTOM_URL -> "url|$format|${settings.metaCustomUrl.trim()}"
    }

    companion object {
        val supportedFormats = listOf(GameFormat.STANDARD, GameFormat.WILD)
        private const val MAX_AGE_MILLIS = 6L * 60 * 60 * 1000
        const val ARCHETYPES_URL = "https://hsreplay.net/api/v1/archetypes/"
        const val DECKS_URL = "https://hsreplay.net/analytics/query/list_decks_by_win_rate_v2/"
        private val jsonHeaders = mapOf("Accept" to "application/json")
    }
}
