package com.stroexd.hsdecktracker.core.collection

import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.CardSets
import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.cards.GameFormat

enum class ReceivedStatus { NEW, COPY, DUPLICATE }

/** [copies] is negative for removed cards; [status] is only set for received cards. */
data class ChangedCard(val dbfId: Int, val copies: Int, val status: ReceivedStatus? = null)

data class CollectionChange(val collection: CardCollection, val cards: List<ChangedCard>, val dust: Int) {
    val isEmpty: Boolean get() = cards.isEmpty() && dust == 0
}

object CollectionChanges {
    /** Packs never contain free Core cards; the newest printing of a name is the likeliest. */
    fun receive(collection: CardCollection, copies: Map<List<Int>, Int>, db: CardDatabase): CollectionChange {
        var result = collection
        val changed = mutableListOf<ChangedCard>()
        for ((ids, count) in copies) {
            val card = ids.mapNotNull(db::byDbfId)
                .sortedWith(compareBy<Card> { it.set in CardSets.freeSets }.thenByDescending { it.dbfId })
                .firstOrNull() ?: continue
            repeat(count) {
                val before = result.owned(card.dbfId)
                val status = when {
                    before == 0 -> ReceivedStatus.NEW
                    before >= card.maxCopies -> ReceivedStatus.DUPLICATE
                    else -> ReceivedStatus.COPY
                }
                result = result.withNormalCount(card.dbfId, (result.cards[card.dbfId]?.normal ?: 0) + 1)
                changed += ChangedCard(card.dbfId, 1, status)
            }
        }
        return CollectionChange(result, changed, 0)
    }

    /** Core and reward cards can't be disenchanted, so the copies come from a craftable printing. */
    fun disenchant(collection: CardCollection, ids: List<Int>, copies: Int, db: CardDatabase): CollectionChange {
        val printings = ids.mapNotNull(db::byDbfId).filter { it.isCraftable }
        val card = printings.maxByOrNull { collection.owned(it.dbfId) } ?: return CollectionChange(collection, emptyList(), 0)
        var owned = collection.cards[card.dbfId] ?: OwnedCard()
        val removed = minOf(copies, owned.total)
        repeat(removed) { owned = owned.withoutOne() }
        // Hearthstone paid out the dust even if the app didn't know about the copies
        val dust = copies * card.rarityType.disenchantValue
        val cards = if (owned.total == 0) collection.cards - card.dbfId else collection.cards + (card.dbfId to owned)
        return CollectionChange(
            collection.copy(cards = cards, dust = collection.dust + dust),
            if (removed > 0) listOf(ChangedCard(card.dbfId, -removed)) else emptyList(),
            dust,
        )
    }

    fun craft(collection: CardCollection, ids: List<Int>, copies: Int, db: CardDatabase, rules: FormatRules): CollectionChange {
        val card = ids.mapNotNull(db::byDbfId)
            .filter { it.isCraftable }
            .sortedWith(compareByDescending<Card> { rules.isLegal(it, GameFormat.STANDARD) }.thenByDescending { it.dbfId })
            .firstOrNull() ?: return CollectionChange(collection, emptyList(), 0)
        val dust = -minOf(collection.dust, copies * card.craftCost)
        val updated = collection.withNormalCount(card.dbfId, (collection.cards[card.dbfId]?.normal ?: 0) + copies)
        return CollectionChange(updated.copy(dust = collection.dust + dust), listOf(ChangedCard(card.dbfId, copies)), dust)
    }

    /** "Disenchant extra cards": each craftable card keeps a playset, normal copies go first. */
    fun withoutExtras(collection: CardCollection, db: CardDatabase): CollectionChange {
        val cards = collection.cards.toMutableMap()
        val changed = mutableListOf<ChangedCard>()
        var dust = 0
        for ((id, owned) in collection.cards) {
            val card = db.byDbfId(id) ?: continue
            val extra = owned.total - card.maxCopies
            if (!card.isCraftable || extra <= 0) continue
            val normal = minOf(owned.normal, extra)
            val golden = minOf(owned.golden, extra - normal)
            dust += normal * card.rarityType.disenchantValue + golden * card.rarityType.goldenDisenchantValue
            var left = owned
            repeat(extra) { left = left.withoutOne() }
            cards[id] = left
            changed += ChangedCard(id, -extra)
        }
        return CollectionChange(collection.copy(cards = cards, dust = collection.dust + dust), changed, dust)
    }

    private fun OwnedCard.withoutOne(): OwnedCard = when {
        normal > 0 -> copy(normal = normal - 1)
        golden > 0 -> copy(golden = golden - 1)
        signature > 0 -> copy(signature = signature - 1)
        diamond > 0 -> copy(diamond = diamond - 1)
        else -> this
    }
}
