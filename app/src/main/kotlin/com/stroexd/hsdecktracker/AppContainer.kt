package com.stroexd.hsdecktracker

import android.content.Context
import com.stroexd.hsdecktracker.core.data.CardRepository
import com.stroexd.hsdecktracker.core.data.CollectionRepository
import com.stroexd.hsdecktracker.core.data.DeckRepository
import com.stroexd.hsdecktracker.core.data.HttpClient
import com.stroexd.hsdecktracker.core.data.MatchRepository
import com.stroexd.hsdecktracker.core.data.MetaRepository
import com.stroexd.hsdecktracker.core.data.SettingsRepository
import com.stroexd.hsdecktracker.core.tracker.TrackerController
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
}
