package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.TestCards.EPIC_MAGE
import com.stroexd.hsdecktracker.core.TestCards.FIREBALL
import com.stroexd.hsdecktracker.core.TestCards.LEEROY
import com.stroexd.hsdecktracker.core.TestCards.RARE_NEUTRAL
import com.stroexd.hsdecktracker.core.TestCards.REWARD_LEGENDARY
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.collection.CardCollection
import com.stroexd.hsdecktracker.core.collection.OwnedCard
import com.stroexd.hsdecktracker.core.meta.MetaCardStats
import com.stroexd.hsdecktracker.core.meta.MetaDeck
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetaCardStatsTest {
    private val decks = listOf(
        MetaDeck("a", HsClass.MAGE, GameFormat.STANDARD, mapOf(FIREBALL to 2, LEEROY to 1), totalGames = 300),
        MetaDeck("b", HsClass.MAGE, GameFormat.STANDARD, mapOf(FIREBALL to 1, EPIC_MAGE to 2), totalGames = 100),
        MetaDeck("c", HsClass.ROGUE, GameFormat.STANDARD, mapOf(RARE_NEUTRAL to 2, LEEROY to 1), totalGames = 600),
        MetaDeck("d", HsClass.ROGUE, GameFormat.STANDARD, mapOf(REWARD_LEGENDARY to 1, RARE_NEUTRAL to 1), totalGames = 50),
    )

    @Test
    fun popularityIsWeightedByGames() {
        val pop = MetaCardStats.popularity(decks)
        val leeroy = pop.getValue(LEEROY)
        assertTrue(abs(leeroy.overallShare - 900.0 / 1050) < 1e-9)
        assertTrue(abs(leeroy.classShares.getValue(HsClass.MAGE) - 0.75) < 1e-9)
        assertTrue(abs(leeroy.classShares.getValue(HsClass.ROGUE) - 600.0 / 650) < 1e-9)
        assertEquals(HsClass.ROGUE, leeroy.topClass)
        assertTrue(abs(pop.getValue(FIREBALL).avgCopies - 1.75) < 1e-9)
    }

    @Test
    fun recommendationsPreferCardsThatCompleteDecks() {
        val collection = CardCollection(cards = mapOf(FIREBALL to OwnedCard(2), RARE_NEUTRAL to OwnedCard(2)))
        val recs = MetaCardStats.craftRecommendations(decks, collection, TestCards.db)
        assertEquals(listOf(LEEROY, EPIC_MAGE), recs.map { it.card.dbfId })
        assertEquals(2, recs.first().completesDecks)
        assertEquals(1600, recs.first().dustCost)
        assertEquals(800, recs[1].dustCost)
    }
}
