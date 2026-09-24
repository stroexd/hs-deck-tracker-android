package com.stroexd.hsdecktracker.core.cards

import com.stroexd.hsdecktracker.core.collection.CardCollection
import com.stroexd.hsdecktracker.core.collection.CollectionOptions
import com.stroexd.hsdecktracker.core.collection.CraftingCalculator
import com.stroexd.hsdecktracker.core.util.normalizeForSearch

enum class Ownership(val displayName: String) {
    ALL("Alle"),
    OWNED("Besessen"),
    MISSING("Fehlend"),
    INCOMPLETE("Unvollständig"),
}

data class CardFilter(
    val query: String = "",
    val classes: Set<HsClass> = emptySet(),
    /** Manakosten; 7 steht für „7+“. */
    val costs: Set<Int> = emptySet(),
    val rarities: Set<Rarity> = emptySet(),
    val types: Set<CardType> = emptySet(),
    val set: String? = null,
    val format: GameFormat? = null,
    val ownership: Ownership = Ownership.ALL,
) {
    val isDefault: Boolean get() = this == CardFilter()
}

object CardSearch {

    fun filter(
        cards: List<Card>,
        filter: CardFilter,
        rules: FormatRules,
        collection: CardCollection? = null,
        options: CollectionOptions = CollectionOptions(),
    ): List<Card> {
        val terms = normalizeForSearch(filter.query).split(' ').filter { it.isNotBlank() }
        return cards.filter { card ->
            (filter.classes.isEmpty() || filter.classes.any { cls ->
                if (cls == HsClass.NEUTRAL) card.isNeutral else card.hsClass == cls || cls in card.allowedClasses
            }) &&
                (filter.costs.isEmpty() || (if (card.cost >= 7) 7 else card.cost) in filter.costs) &&
                (filter.rarities.isEmpty() || card.rarityType in filter.rarities) &&
                (filter.types.isEmpty() || card.cardType in filter.types) &&
                (filter.set == null || card.set == filter.set) &&
                (filter.format == null || rules.isLegal(card, filter.format)) &&
                matchesOwnership(card, filter.ownership, collection, options) &&
                (terms.isEmpty() || matchesTerms(card, terms))
        }
    }

    private fun matchesOwnership(
        card: Card,
        ownership: Ownership,
        collection: CardCollection?,
        options: CollectionOptions,
    ): Boolean {
        if (ownership == Ownership.ALL || collection == null) return true
        val owned = CraftingCalculator.ownedCopies(card, collection, options)
        return when (ownership) {
            Ownership.ALL -> true
            Ownership.OWNED -> owned > 0
            Ownership.MISSING -> owned == 0
            Ownership.INCOMPLETE -> owned < card.maxCopies
        }
    }

    private fun matchesTerms(card: Card, terms: List<String>): Boolean {
        val haystack = buildString {
            append(normalizeForSearch(card.name)).append(' ')
            append(normalizeForSearch(card.plainText)).append(' ')
            card.tribes.forEach { append(it.lowercase()).append(' ') }
            card.mechanics.forEach { append(it.lowercase()).append(' ') }
            card.spellSchool?.let { append(it.lowercase()) }
        }
        return terms.all { it in haystack }
    }
}
