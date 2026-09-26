package com.stroexd.hsdecktracker.core.collection

import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.CardSets
import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.Rarity
import com.stroexd.hsdecktracker.core.deck.SideboardCard
import kotlinx.serialization.Serializable

@Serializable
data class OwnedCard(
    val normal: Int = 0,
    val golden: Int = 0,
    val diamond: Int = 0,
    val signature: Int = 0,
) {
    val total: Int get() = normal + golden + diamond + signature

    operator fun plus(other: OwnedCard) = OwnedCard(
        normal + other.normal,
        golden + other.golden,
        diamond + other.diamond,
        signature + other.signature,
    )
}

@Serializable
data class CardCollection(
    val cards: Map<Int, OwnedCard> = emptyMap(),
    val dust: Int = 0,
    val updatedAt: Long = 0,
    val source: String? = null,
) {
    val isEmpty: Boolean get() = cards.isEmpty()

    fun owned(dbfId: Int): Int = cards[dbfId]?.total ?: 0

    fun withNormalCount(dbfId: Int, normal: Int): CardCollection {
        val current = cards[dbfId] ?: OwnedCard()
        val updated = current.copy(normal = normal.coerceAtLeast(0))
        val newCards = if (updated.total == 0) cards - dbfId else cards + (dbfId to updated)
        return copy(cards = newCards)
    }

    fun ensureAtLeast(dbfId: Int, required: Int): CardCollection {
        val current = cards[dbfId] ?: OwnedCard()
        if (current.total >= required) return this
        return copy(cards = cards + (dbfId to current.copy(normal = current.normal + required - current.total)))
    }
}

/**
 * Applies copies read from Hearthstone's collection. Reprints share a name, so the copies are spread over
 * the printings: known counts stay, the rest goes to the likeliest printing (free Core, Standard, newest).
 */
fun CardCollection.withScannedCopies(totals: Map<List<Int>, Int>, db: CardDatabase, rules: FormatRules): CardCollection {
    val updated = cards.toMutableMap()
    for ((ids, copies) in totals) {
        val printings = ids.mapNotNull(db::byDbfId).sortedWith(
            compareByDescending<Card> { it.set in CardSets.freeSets }
                .thenByDescending { rules.isLegal(it, GameFormat.STANDARD) }
                .thenByDescending { it.dbfId },
        )
        if (printings.isEmpty() || printings.sumOf { owned(it.dbfId) } == copies) continue
        val assigned = LinkedHashMap<Int, Int>()
        var left = copies
        for (card in printings) {
            val keep = minOf(owned(card.dbfId), left)
            assigned[card.dbfId] = keep
            left -= keep
        }
        for (card in printings) {
            val add = minOf(left, (card.maxCopies - assigned.getValue(card.dbfId)).coerceAtLeast(0))
            assigned[card.dbfId] = assigned.getValue(card.dbfId) + add
            left -= add
        }
        assigned[printings.first().dbfId] = assigned.getValue(printings.first().dbfId) + left
        for ((id, count) in assigned) {
            val current = cards[id] ?: OwnedCard()
            if (current.total == count) continue
            val premium = current.total - current.normal
            val next = if (premium <= count) current.copy(normal = count - premium) else OwnedCard(normal = count)
            if (next.total == 0) updated -= id else updated[id] = next
        }
    }
    return copy(cards = updated)
}

data class CollectionOptions(
    val coreSetOwned: Boolean = true,
)

data class MissingCard(
    val dbfId: Int,
    val card: Card?,
    val missing: Int,
    val craftCostEach: Int,
    val craftable: Boolean,
) {
    val totalCost: Int get() = if (craftable) craftCostEach * missing else 0
}

data class CraftAnalysis(
    val missing: List<MissingCard>,
    val dustCost: Int,
    val uncraftableMissing: Int,
    val ownedCards: Int,
    val totalCards: Int,
) {
    val isComplete: Boolean get() = missing.isEmpty()
    val missingCount: Int get() = missing.sumOf { it.missing }
    val ownedFraction: Double get() = if (totalCards == 0) 1.0 else ownedCards.toDouble() / totalCards

    fun craftableWith(dust: Int): Boolean = uncraftableMissing == 0 && dustCost <= dust

    companion object {
        val EMPTY = CraftAnalysis(emptyList(), 0, 0, 0, 0)
    }
}

object CraftingCalculator {
    fun ownedCopies(card: Card, collection: CardCollection, options: CollectionOptions): Int {
        if (card.rarityType == Rarity.FREE) return card.maxCopies
        if (options.coreSetOwned && card.set in CardSets.freeSets) return card.maxCopies
        return collection.owned(card.dbfId)
    }

    fun analyze(
        cards: Map<Int, Int>,
        sideboards: List<SideboardCard>,
        collection: CardCollection,
        db: CardDatabase,
        options: CollectionOptions = CollectionOptions(),
    ): CraftAnalysis {
        val required = cards.filterValues { it > 0 }.toMutableMap()
        for (sb in sideboards) {
            if (db.byDbfId(sb.dbfId)?.isDeckCard == true) required.merge(sb.dbfId, sb.count, Int::plus)
        }
        val missing = mutableListOf<MissingCard>()
        var owned = 0
        var total = 0
        for ((id, need) in required) {
            total += need
            val card = db.byDbfId(id)
            val have = if (card != null) ownedCopies(card, collection, options) else collection.owned(id)
            val covered = minOf(have, need)
            owned += covered
            if (covered < need) {
                missing += MissingCard(
                    dbfId = id,
                    card = card,
                    missing = need - covered,
                    craftCostEach = card?.craftCost ?: 0,
                    craftable = card?.isCraftable ?: false,
                )
            }
        }
        missing.sortWith(compareByDescending<MissingCard> { it.totalCost }.thenBy { it.card?.name ?: "" })
        return CraftAnalysis(
            missing = missing,
            dustCost = missing.sumOf { it.totalCost },
            uncraftableMissing = missing.filterNot { it.craftable }.sumOf { it.missing },
            ownedCards = owned,
            totalCards = total,
        )
    }
}

data class SetProgress(
    val set: String,
    val uniqueOwned: Int,
    val uniqueTotal: Int,
    val copiesOwned: Int,
    val copiesTotal: Int,
    val dustToComplete: Int,
) {
    val fraction: Double get() = if (copiesTotal == 0) 1.0 else copiesOwned.toDouble() / copiesTotal
}

data class CollectionSummary(
    val uniqueOwned: Int,
    val uniqueTotal: Int,
    val copiesOwned: Int,
    val copiesTotal: Int,
    val extraDust: Int,
    val sets: List<SetProgress>,
) {
    val fraction: Double get() = if (copiesTotal == 0) 0.0 else copiesOwned.toDouble() / copiesTotal
}

object CollectionStats {
    fun summarize(
        db: CardDatabase,
        collection: CardCollection,
        options: CollectionOptions = CollectionOptions(),
        setFilter: (String) -> Boolean = { true },
    ): CollectionSummary {
        val setProgress = db.sets.filter(setFilter).map { set -> progressFor(set, db.cardsInSet(set), collection, options) }
        return CollectionSummary(
            uniqueOwned = setProgress.sumOf { it.uniqueOwned },
            uniqueTotal = setProgress.sumOf { it.uniqueTotal },
            copiesOwned = setProgress.sumOf { it.copiesOwned },
            copiesTotal = setProgress.sumOf { it.copiesTotal },
            extraDust = extraDust(db, collection),
            sets = setProgress,
        )
    }

    fun progressFor(set: String, cards: List<Card>, collection: CardCollection, options: CollectionOptions): SetProgress {
        var uniqueOwned = 0
        var copiesOwned = 0
        var copiesTotal = 0
        var dust = 0
        for (card in cards) {
            val owned = minOf(CraftingCalculator.ownedCopies(card, collection, options), card.maxCopies)
            if (owned > 0) uniqueOwned++
            copiesOwned += owned
            copiesTotal += card.maxCopies
            dust += (card.maxCopies - owned) * card.craftCost
        }
        return SetProgress(set, uniqueOwned, cards.size, copiesOwned, copiesTotal, dust)
    }

    fun extraDust(db: CardDatabase, collection: CardCollection): Int {
        var dust = 0
        for ((id, owned) in collection.cards) {
            val card = db.byDbfId(id) ?: continue
            if (!card.isCraftable) continue
            val extra = owned.total - card.maxCopies
            if (extra <= 0) continue
            val extraNormal = minOf(owned.normal, extra)
            val extraGolden = minOf(owned.golden, extra - extraNormal)
            dust += extraNormal * card.rarityType.disenchantValue + extraGolden * card.rarityType.goldenDisenchantValue
        }
        return dust
    }
}
