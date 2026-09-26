package com.stroexd.hsdecktracker.core.cards

import com.stroexd.hsdecktracker.core.util.AppJson
import com.stroexd.hsdecktracker.core.util.normalizeForSearch
import kotlinx.serialization.builtins.ListSerializer

class CardDatabase(
    val allCards: List<Card>,
    val locale: String = "enUS",
    private val setNames: Map<String, String> = emptyMap(),
) {
    private val byDbf: Map<Int, Card> = allCards.associateBy { it.dbfId }
    private val byCardId: Map<String, Card> = allCards.associateBy { it.id }
    private val byName: Map<String, List<Card>> = allCards.groupBy { normalizeForSearch(it.name) }

    val deckCards: List<Card> = allCards
        .filter { it.isDeckCard }
        .sortedWith(compareBy<Card>({ it.cost }, { it.name }))

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

    fun findByName(name: String): Card? {
        val candidates = byName[normalizeForSearch(name)] ?: return null
        return candidates.sortedWith(
            compareByDescending<Card> { it.isDeckCard }
                .thenByDescending { it.set != "CORE" && it.set != "LEGACY" }
                .thenBy { it.dbfId },
        ).firstOrNull()
    }

    fun resolve(identifier: String): Card? {
        val trimmed = identifier.trim().trim('"', '\'')
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.let { return byDbfId(it) }
        return byCardId(trimmed) ?: byCardId(trimmed.uppercase()) ?: findByName(trimmed)
    }

    fun cardsInSet(set: String): List<Card> = deckCards.filter { it.set == set }

    fun setName(set: String): String = setNames[set] ?: CardSets.displayName(set)

    fun hasSetName(set: String): Boolean = set in setNames || CardSets.hasBuiltInName(set)

    fun withSetNames(names: Map<String, String>): CardDatabase = CardDatabase(allCards, locale, names)

    companion object {
        val EMPTY = CardDatabase(emptyList())

        private val serializer = ListSerializer(Card.serializer())

        fun parse(json: String, locale: String = "enUS"): CardDatabase =
            CardDatabase(AppJson.decodeFromString(serializer, json), locale)
    }
}
