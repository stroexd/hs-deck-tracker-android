package com.stroexd.hsdecktracker.core.cards

import com.stroexd.hsdecktracker.core.util.AppJson
import com.stroexd.hsdecktracker.core.util.normalizeForSearch
import kotlinx.serialization.builtins.ListSerializer

/** In-Memory-Kartendatenbank mit schnellen Lookups. */
class CardDatabase(val allCards: List<Card>, val locale: String = "deDE") {

    private val byDbf: Map<Int, Card> = allCards.associateBy { it.dbfId }
    private val byCardId: Map<String, Card> = allCards.associateBy { it.id }
    private val byName: Map<String, List<Card>> = allCards.groupBy { normalizeForSearch(it.name) }

    /** Alle Karten, die in Decks gespielt werden können, sortiert nach Kosten und Name. */
    val deckCards: List<Card> = allCards
        .filter { it.isDeckCard }
        .sortedWith(compareBy<Card>({ it.cost }, { it.name }))

    /** Sets der Deck-Karten, neueste zuerst (höchste dbfId als Näherung für das Erscheinungsdatum). */
    val sets: List<String> = deckCards
        .groupBy { it.set }
        .mapValues { (_, cards) -> cards.maxOf { it.dbfId } }
        .entries
        .sortedByDescending { it.value }
        .map { it.key }

    val isEmpty: Boolean get() = allCards.isEmpty()
    val size: Int get() = allCards.size

    fun byDbfId(dbfId: Int): Card? = byDbf[dbfId]

    fun byCardId(cardId: String): Card? = byCardId[cardId]

    /** Sucht eine Karte über ihren (lokalisierten) Namen, bevorzugt Deck-Karten und neuere Drucke. */
    fun findByName(name: String): Card? {
        val candidates = byName[normalizeForSearch(name)] ?: return null
        return candidates.sortedWith(
            compareByDescending<Card> { it.isDeckCard }
                .thenByDescending { it.set != "CORE" && it.set != "LEGACY" }
                .thenBy { it.dbfId },
        ).firstOrNull()
    }

    /** Löst eine Kennung auf: dbfId (Zahl), Karten-ID (z. B. `CS2_029`) oder Kartenname. */
    fun resolve(identifier: String): Card? {
        val trimmed = identifier.trim().trim('"', '\'')
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.let { return byDbfId(it) }
        return byCardId(trimmed) ?: byCardId(trimmed.uppercase()) ?: findByName(trimmed)
    }

    fun cardsInSet(set: String): List<Card> = deckCards.filter { it.set == set }

    companion object {
        val EMPTY = CardDatabase(emptyList())

        private val serializer = ListSerializer(Card.serializer())

        /** Parst die JSON-Antwort von `cards.collectible.json` bzw. `cards.json`. */
        fun parse(json: String, locale: String = "deDE"): CardDatabase =
            CardDatabase(AppJson.decodeFromString(serializer, json), locale)
    }
}
