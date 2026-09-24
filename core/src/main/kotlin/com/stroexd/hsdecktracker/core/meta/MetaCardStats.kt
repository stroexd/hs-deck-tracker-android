package com.stroexd.hsdecktracker.core.meta

import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.collection.CardCollection
import com.stroexd.hsdecktracker.core.collection.CollectionOptions
import com.stroexd.hsdecktracker.core.collection.CraftingCalculator

/** Wie häufig eine Karte in den Meta-Decks gespielt wird (gewichtet nach Anzahl Spielen). */
data class CardPopularity(
    val dbfId: Int,
    /** Anteil aller Meta-Spiele, deren Deck die Karte enthält. */
    val overallShare: Double,
    /** Anteil je Klasse (nur Klassen, die die Karte spielen). */
    val classShares: Map<HsClass, Double>,
    val avgCopies: Double,
) {
    val topClass: HsClass? get() = classShares.maxByOrNull { it.value }?.key
}

data class CraftRecommendation(
    val card: Card,
    val missing: Int,
    val dustCost: Int,
    val popularity: CardPopularity,
    /** Anzahl Meta-Decks, die durch diese Karte (zusammen mit bereits vorhandenen) komplett würden. */
    val completesDecks: Int,
)

object MetaCardStats {

    private fun weight(deck: MetaDeck): Double = (deck.totalGames ?: 1).coerceAtLeast(1).toDouble()

    fun popularity(decks: List<MetaDeck>): Map<Int, CardPopularity> {
        if (decks.isEmpty()) return emptyMap()
        val totalWeight = decks.sumOf { weight(it) }
        val classWeight = decks.groupBy { it.heroClass }.mapValues { (_, list) -> list.sumOf { weight(it) } }
        val inclusion = HashMap<Int, Double>()
        val copies = HashMap<Int, Double>()
        val perClass = HashMap<Int, MutableMap<HsClass, Double>>()
        for (deck in decks) {
            val w = weight(deck)
            for ((id, count) in deck.cards) {
                inclusion.merge(id, w, Double::plus)
                copies.merge(id, w * count, Double::plus)
                perClass.getOrPut(id) { mutableMapOf() }.merge(deck.heroClass, w, Double::plus)
            }
        }
        return inclusion.mapValues { (id, w) ->
            CardPopularity(
                dbfId = id,
                overallShare = w / totalWeight,
                classShares = perClass[id].orEmpty().mapValues { (cls, cw) -> cw / (classWeight[cls] ?: cw) },
                avgCopies = (copies[id] ?: 0.0) / w,
            )
        }
    }

    /**
     * Empfiehlt fehlende, herstellbare Karten: sortiert danach, wie viele Meta-Decks durch sie
     * komplett würden und wie verbreitet sie im Meta sind.
     */
    fun craftRecommendations(
        decks: List<MetaDeck>,
        collection: CardCollection,
        db: CardDatabase,
        options: CollectionOptions = CollectionOptions(),
        limit: Int = 20,
    ): List<CraftRecommendation> {
        if (decks.isEmpty()) return emptyList()
        val popularity = popularity(decks)
        val completes = HashMap<Int, Int>()
        val neededCopies = HashMap<Int, Int>()
        for (deck in decks) {
            val analysis = CraftingCalculator.analyze(deck.cards, deck.sideboards, collection, db, options)
            if (analysis.uncraftableMissing > 0) continue
            analysis.missing.forEach { neededCopies.merge(it.dbfId, it.missing, ::maxOf) }
            if (analysis.missing.size == 1) completes.merge(analysis.missing.first().dbfId, 1, Int::plus)
        }
        return neededCopies.mapNotNull { (id, missing) ->
            val card = db.byDbfId(id) ?: return@mapNotNull null
            if (!card.isCraftable) return@mapNotNull null
            val pop = popularity[id] ?: return@mapNotNull null
            CraftRecommendation(card, missing, card.craftCost * missing, pop, completes[id] ?: 0)
        }.sortedWith(
            compareByDescending<CraftRecommendation> { it.completesDecks }
                .thenByDescending { it.popularity.overallShare / (1 + it.dustCost / 1600.0) },
        ).take(limit)
    }
}
