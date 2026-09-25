package com.stroexd.hsdecktracker.core.data

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.collection.CardCollection
import com.stroexd.hsdecktracker.core.collection.CollectionImportResult
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.deck.DeckTextParser
import com.stroexd.hsdecktracker.core.stats.MatchRecord
import com.stroexd.hsdecktracker.core.util.PrettyJson
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class DeckRepository(dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val store = JsonFileStore(File(dir, "decks.json"), ListSerializer(Deck.serializer()), emptyList())

    val decks: StateFlow<List<Deck>> = store.state

    fun get(id: String): Deck? = store.value.firstOrNull { it.id == id }

    suspend fun upsert(deck: Deck): Deck {
        val now = clock()
        val saved = deck.copy(updatedAt = now, createdAt = if (deck.createdAt == 0L) now else deck.createdAt)
        store.update { list ->
            if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it } else list + saved
        }
        return saved
    }

    suspend fun delete(id: String) {
        store.update { list -> list.filterNot { it.id == id } }
    }

    /** Importiert alle Deck-Codes aus einem Text. Gibt die neu angelegten Decks zurück. */
    suspend fun importFromText(text: String, db: CardDatabase): List<Deck> {
        val now = clock()
        val decks = DeckTextParser.parseAll(text).map { parsed ->
            Deck.fromDefinition(parsed.definition, parsed.name, db, now)
        }
        if (decks.isNotEmpty()) store.update { it + decks }
        return decks
    }

    suspend fun replaceAll(decks: List<Deck>) {
        store.set(decks)
    }
}

class CollectionRepository(dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val store = JsonFileStore(File(dir, "collection.json"), CardCollection.serializer(), CardCollection())

    val collection: StateFlow<CardCollection> = store.state

    suspend fun applyImport(result: CollectionImportResult, keepDustIfMissing: Boolean = true) {
        store.update { current ->
            result.collection.copy(
                dust = if (result.dust == null && keepDustIfMissing) current.dust else result.collection.dust,
                updatedAt = clock(),
            )
        }
    }

    suspend fun setNormalCount(dbfId: Int, count: Int) {
        store.update { it.withNormalCount(dbfId, count).copy(updatedAt = clock()) }
    }

    /** Setzt mehrere normale Anzahlen auf einmal (z. B. „ganzes Set besitzen“). */
    suspend fun setNormalCounts(counts: Map<Int, Int>) {
        store.update { current ->
            counts.entries.fold(current) { acc, (id, count) -> acc.withNormalCount(id, count) }.copy(updatedAt = clock())
        }
    }

    suspend fun setDust(dust: Int) {
        store.update { it.copy(dust = dust.coerceAtLeast(0)) }
    }

    /** Markiert alle Karten eines Decks als besessen. */
    suspend fun addDeck(cards: Map<Int, Int>) {
        store.update { current ->
            cards.entries.fold(current) { acc, (id, count) -> acc.ensureAtLeast(id, count) }.copy(updatedAt = clock())
        }
    }

    suspend fun replace(collection: CardCollection) {
        store.set(collection)
    }

    suspend fun clear() {
        store.update { CardCollection(dust = it.dust) }
    }
}

class MatchRepository(dir: File) {
    private val store = JsonFileStore(File(dir, "matches.json"), ListSerializer(MatchRecord.serializer()), emptyList())

    val matches: StateFlow<List<MatchRecord>> = store.state

    suspend fun add(record: MatchRecord) {
        store.update { it + record }
    }

    suspend fun delete(id: String) {
        store.update { list -> list.filterNot { it.id == id } }
    }

    suspend fun update(record: MatchRecord) {
        store.update { list -> list.map { if (it.id == record.id) record else it } }
    }

    suspend fun replaceAll(matches: List<MatchRecord>) {
        store.set(matches)
    }
}

class SettingsRepository(dir: File) {
    private val store = JsonFileStore(File(dir, "settings.json"), AppSettings.serializer(), AppSettings())

    val settings: StateFlow<AppSettings> = store.state
    val value: AppSettings get() = store.value

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.update(transform)
    }
}

@Serializable
data class BackupData(
    val version: Int = 1,
    val exportedAt: Long = 0,
    val decks: List<Deck> = emptyList(),
    val collection: CardCollection = CardCollection(),
    val matches: List<MatchRecord> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

object Backup {
    fun export(data: BackupData): String = PrettyJson.encodeToString(BackupData.serializer(), data)
    fun import(json: String): BackupData = PrettyJson.decodeFromString(BackupData.serializer(), json)
}
