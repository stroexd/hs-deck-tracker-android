package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.TestCards.ARCANE_MISSILES
import com.stroexd.hsdecktracker.core.TestCards.BASIC
import com.stroexd.hsdecktracker.core.TestCards.EPIC_MAGE
import com.stroexd.hsdecktracker.core.TestCards.FIREBALL
import com.stroexd.hsdecktracker.core.TestCards.LEEROY
import com.stroexd.hsdecktracker.core.TestCards.RARE_NEUTRAL
import com.stroexd.hsdecktracker.core.TestCards.REWARD_LEGENDARY
import com.stroexd.hsdecktracker.core.collection.CardCollection
import com.stroexd.hsdecktracker.core.collection.CollectionExporter
import com.stroexd.hsdecktracker.core.collection.CollectionImportException
import com.stroexd.hsdecktracker.core.collection.CollectionImporter
import com.stroexd.hsdecktracker.core.collection.CollectionOptions
import com.stroexd.hsdecktracker.core.collection.CollectionStats
import com.stroexd.hsdecktracker.core.collection.CraftingCalculator
import com.stroexd.hsdecktracker.core.collection.OwnedCard
import com.stroexd.hsdecktracker.core.deck.SideboardCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionTest {
    private val db = TestCards.db

    @Test
    fun importsHsReplayJson() {
        val json = """{"collection": {"1": [2, 0, 0, 0], "3": [0, 1], "5": [1, 1, 0, 1], "99999": [1]}, "dust": 1500, "favorite_heroes": {}}"""
        val result = CollectionImporter.import(json, db, now = 1)
        assertEquals("HSReplay JSON", result.detectedFormat)
        assertEquals(1500, result.dust)
        assertEquals(2, result.collection.owned(FIREBALL))
        assertEquals(1, result.collection.owned(LEEROY))
        assertEquals(3, result.collection.owned(RARE_NEUTRAL))
        assertEquals(listOf("99999"), result.unresolved)
    }

    @Test
    fun importsJsonListsAndMaps() {
        val list = """[{"cardId": "CS2_029", "count": 2, "golden": 1}, {"name": "Leeroy Jenkins"}, [5, 2]]"""
        val r1 = CollectionImporter.import(list, db, now = 1)
        assertEquals(3, r1.collection.owned(FIREBALL))
        assertEquals(1, r1.collection.owned(LEEROY))
        assertEquals(2, r1.collection.owned(RARE_NEUTRAL))

        val map = """{"CS2_029": 1, "Seltener Diener": 2, "staub": 50}"""
        val r2 = CollectionImporter.import(map, db, now = 1)
        assertEquals(1, r2.collection.owned(FIREBALL))
        assertEquals(2, r2.collection.owned(RARE_NEUTRAL))
        assertEquals(50, r2.dust)
    }

    @Test
    fun importsCsvWithHeaderAndQuotes() {
        val csv = """
            Name;Anzahl;Golden
            "Feuerball";2;0
            Leeroy Jenkins;0;1
            Unbekannt;1;0
            Dust;3200
        """.trimIndent()
        val result = CollectionImporter.import(csv, db, now = 1)
        assertEquals("CSV", result.detectedFormat)
        assertEquals(2, result.collection.owned(FIREBALL))
        assertEquals(OwnedCard(normal = 0, golden = 1), result.collection.cards[LEEROY])
        assertEquals(3200, result.dust)
        assertEquals(listOf("Unbekannt"), result.unresolved)
    }

    @Test
    fun importsFreeTextLines() {
        val text = """
            2x Feuerball
            1 Leeroy Jenkins
            RARE_01 2
            Epischer Zauber
            5 1
        """.trimIndent()
        val result = CollectionImporter.import(text, db, now = 1)
        assertEquals(2, result.collection.owned(FIREBALL))
        assertEquals(1, result.collection.owned(LEEROY))
        assertEquals(3, result.collection.owned(RARE_NEUTRAL))
        assertEquals(1, result.collection.owned(EPIC_MAGE))
    }

    @Test
    fun exportRoundTrip() {
        val collection = CardCollection(
            cards = mapOf(FIREBALL to OwnedCard(2), LEEROY to OwnedCard(0, 1, 0, 1)),
            dust = 777,
        )
        val json = CollectionImporter.import(CollectionExporter.toJson(collection), db, now = 1)
        assertEquals(collection.cards, json.collection.cards)
        assertEquals(777, json.dust)
        val csv = CollectionImporter.import(CollectionExporter.toCsv(collection, db), db, now = 1)
        assertEquals(collection.cards, csv.collection.cards)
        assertEquals(777, csv.dust)
    }

    @Test
    fun emptyOrUselessInputFails() {
        assertFailsWith<CollectionImportException> { CollectionImporter.import("   ", db, now = 1) }
        assertFailsWith<CollectionImportException> { CollectionImporter.import("{\"collection\": {}}", db, now = 1) }
    }

    @Test
    fun craftAnalysisCountsMissingDust() {
        val deck = mapOf(FIREBALL to 2, ARCANE_MISSILES to 2, LEEROY to 1, REWARD_LEGENDARY to 1, BASIC to 2, EPIC_MAGE to 2)
        val collection = CardCollection(cards = mapOf(FIREBALL to OwnedCard(1), EPIC_MAGE to OwnedCard(0, 2)))
        val analysis = CraftingCalculator.analyze(deck, emptyList(), collection, db)
        assertEquals(1640, analysis.dustCost)
        assertEquals(1, analysis.uncraftableMissing)
        assertEquals(3, analysis.missingCount)
        assertEquals(10, analysis.totalCards)
        assertEquals(7, analysis.ownedCards)
        assertFalse(analysis.craftableWith(100_000))
        assertEquals(LEEROY, analysis.missing.first().dbfId)
    }

    @Test
    fun coreSetOptionAndSideboards() {
        val deck = mapOf(ARCANE_MISSILES to 2)
        val sideboard = listOf(SideboardCard(RARE_NEUTRAL, 1, 999), SideboardCard(424242, 1, 999))
        val empty = CardCollection()
        val withCore = CraftingCalculator.analyze(deck, sideboard, empty, db)
        assertEquals(100, withCore.dustCost)
        assertTrue(withCore.craftableWith(100))
        val withoutCore = CraftingCalculator.analyze(deck, sideboard, empty, db, CollectionOptions(coreSetOwned = false))
        assertEquals(100, withoutCore.dustCost)
        assertEquals(2, withoutCore.uncraftableMissing)
        assertFalse(withoutCore.craftableWith(10_000))
    }

    @Test
    fun completeDeckIsBuildable() {
        val deck = mapOf(FIREBALL to 2, LEEROY to 1)
        val collection = CardCollection(cards = mapOf(FIREBALL to OwnedCard(3), LEEROY to OwnedCard(1)))
        val analysis = CraftingCalculator.analyze(deck, emptyList(), collection, db)
        assertTrue(analysis.isComplete)
        assertTrue(analysis.craftableWith(0))
        assertEquals(1.0, analysis.ownedFraction)
    }

    @Test
    fun setProgressAndExtraDust() {
        val collection = CardCollection(
            cards = mapOf(LEEROY to OwnedCard(normal = 2, golden = 1), RARE_NEUTRAL to OwnedCard(1), FIREBALL to OwnedCard(4)),
        )
        val summary = CollectionStats.summarize(db, collection)
        val timeTravel = summary.sets.first { it.set == "TIME_TRAVEL" }
        assertEquals(3, timeTravel.uniqueTotal)
        assertEquals(2, timeTravel.uniqueOwned)
        assertEquals(2, timeTravel.copiesOwned)
        assertEquals(4, timeTravel.copiesTotal)
        assertEquals(100, timeTravel.dustToComplete)
        assertEquals(810, summary.extraDust)
    }
}
