package com.stroexd.hsdecktracker.core.meta

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.CardSets

data class DetectedFormat(val standard: Set<String>, val wild: Set<String>)

object FormatDetection {
    private const val MIN_DECKS = 10

    /**
     * A set is Standard when current Standard decks play its cards, or when it is newer than all of them
     * (released before the statistics caught up). That follows every rotation without an app update.
     */
    fun fromStandardDecks(decks: List<MetaDeck>, db: CardDatabase): DetectedFormat? {
        if (decks.size < MIN_DECKS || db.isEmpty) return null
        val used = decks.asSequence()
            .flatMap { deck -> deck.cards.keys + deck.sideboards.map { it.dbfId } }
            .mapNotNullTo(HashSet()) { db.byDbfId(it)?.set }
        val sets = db.sets.filter { it != CardSets.CLASSIC_SET && it !in CardSets.nonConstructedSets }
        val newestUsed = sets.indexOfFirst { it in used }
        if (newestUsed < 0) return null
        val standard = sets.filterIndexedTo(HashSet()) { i, set -> set in used || i < newestUsed }
        return DetectedFormat(standard, sets.toSet() - standard)
    }
}
