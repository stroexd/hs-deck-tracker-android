package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.collection.CardCollection
import com.stroexd.hsdecktracker.core.collection.OwnedCard
import com.stroexd.hsdecktracker.core.collection.withScannedCopies
import com.stroexd.hsdecktracker.core.vision.CardNameIndex
import com.stroexd.hsdecktracker.core.vision.CollectionScanner
import com.stroexd.hsdecktracker.core.vision.OcrFrame
import com.stroexd.hsdecktracker.core.vision.OcrLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionScanTest {
    private val db = TestCards.db
    private val index = CardNameIndex(db.deckCards.map { it.dbfId to it.name })

    private fun line(text: String, x: Float, y: Float, w: Float = 0.12f, h: Float = 0.03f) =
        OcrLine(text, x - w / 2, y - h / 2, x + w / 2, y + h / 2)

    private fun frame(vararg lines: OcrLine) = OcrFrame(0, lines.toList())

    private val columns = listOf(0.12f, 0.32f, 0.52f, 0.72f)
    private fun name(text: String, column: Int, row: Int) = line(text, columns[column], if (row == 0) 0.30f else 0.68f)
    private fun badge(text: String, column: Int, row: Int) = line(text, columns[column], if (row == 0) 0.47f else 0.87f, w = 0.04f)

    private val firstPage = frame(
        name("Feuerball", 0, 0), badge("x2", 0, 0),
        name("Arkane Geschosse", 1, 0),
        name("Leeroy Jenkins", 2, 0),
        name("Seltener Diener", 0, 1), badge("x2", 0, 1),
        name("Doppelklasse", 1, 1), badge("×2", 1, 1),
    )

    private fun CollectionScanner.copies(dbfId: Int) = totals().entries.firstOrNull { dbfId in it.key }?.value ?: 0

    @Test
    fun countsAPageOnceTwoReadsAgree() {
        val scanner = CollectionScanner(index)
        scanner.onFrame(firstPage)
        assertEquals(0, scanner.pageCount)
        assertTrue(scanner.onFrame(firstPage))
        assertEquals(1, scanner.pageCount)
        assertEquals(2, scanner.copies(TestCards.FIREBALL))
        assertEquals(1, scanner.copies(TestCards.LEEROY))
        assertEquals(2, scanner.copies(TestCards.RARE_NEUTRAL))
        // The badge below the second row belongs to the card right above it, not to the top one
        assertEquals(1, scanner.copies(TestCards.ARCANE_MISSILES))
        assertEquals(2, scanner.copies(TestCards.DUAL_CLASS))
    }

    @Test
    fun revisitedPagesAreNotCountedTwice() {
        val scanner = CollectionScanner(index)
        val secondPage = frame(name("Epischer Zauber", 0, 0), name("Prinz Renathal", 1, 0), name("Alte Karte", 2, 0))
        listOf(firstPage, firstPage, secondPage, secondPage, firstPage, firstPage).forEach(scanner::onFrame)
        assertEquals(2, scanner.pageCount)
        assertEquals(2, scanner.copies(TestCards.FIREBALL))
        assertEquals(1, scanner.copies(TestCards.OLD_WILD))
    }

    @Test
    fun aPageReadMoreCompletelyMergesIntoTheFirstRead() {
        val scanner = CollectionScanner(index)
        val partial = frame(name("Feuerball", 0, 0), badge("x2", 0, 0), name("Leeroy Jenkins", 2, 0), name("Seltener Diener", 0, 1))
        listOf(partial, partial, firstPage, firstPage).forEach(scanner::onFrame)
        assertEquals(1, scanner.pageCount)
        assertEquals(2, scanner.copies(TestCards.FIREBALL))
        assertEquals(2, scanner.copies(TestCards.RARE_NEUTRAL))
    }

    @Test
    fun normalAndGoldenTilesAddUp() {
        val scanner = CollectionScanner(index)
        val page = frame(name("Feuerball", 0, 0), name("Feuerball", 1, 0), badge("x2", 1, 0))
        repeat(2) { scanner.onFrame(page) }
        assertEquals(3, scanner.copies(TestCards.FIREBALL))
    }

    @Test
    fun ignoresListsAndCardTexts() {
        val scanner = CollectionScanner(index)
        val deckList = listOf("Feuerball", "Leeroy Jenkins", "Alte Karte", "Basiskarte").mapIndexed { i, text ->
            line(text, 0.9f, 0.2f + i * 0.05f)
        }
        val cardText = listOf(
            name("Epischer Zauber", 0, 0),
            line("Ruft einen Diener herbei:", 0.12f, 0.36f),
            line("Prinz Renathal", 0.12f, 0.39f),
            line("Seltener Diener.", 0.32f, 0.30f),
        )
        val page = frame(*(deckList + cardText).toTypedArray())
        repeat(2) { scanner.onFrame(page) }
        assertEquals(setOf(listOf(TestCards.EPIC_MAGE)), scanner.totals().keys)
    }

    @Test
    fun readsCopyBadges() {
        assertEquals(2, CollectionScanner.copiesBadge("x2"))
        assertEquals(3, CollectionScanner.copiesBadge("X 3"))
        assertEquals(2, CollectionScanner.copiesBadge("×2"))
        assertEquals(2, CollectionScanner.copiesBadge("2x"))
        assertNull(CollectionScanner.copiesBadge("x"))
        assertNull(CollectionScanner.copiesBadge("Exodus"))
    }

    private val reprints = CardDatabase.parse(
        """
        [
          {"dbfId":1,"id":"CS2_029","name":"Fireball","cost":4,"type":"SPELL","rarity":"COMMON","cardClass":"MAGE","set":"EXPERT1","collectible":true},
          {"dbfId":200,"id":"CORE_CS2_029","name":"Fireball","cost":4,"type":"SPELL","rarity":"COMMON","cardClass":"MAGE","set":"CORE","collectible":true},
          {"dbfId":5,"id":"RARE_01","name":"Rare Minion","cost":2,"type":"MINION","rarity":"RARE","cardClass":"NEUTRAL","set":"TIME_TRAVEL","collectible":true}
        ]
        """.trimIndent(),
    )
    private val fireball = listOf(1, 200)

    private fun scanned(collection: CardCollection, vararg totals: Pair<List<Int>, Int>) =
        collection.withScannedCopies(totals.toMap(), reprints, FormatRules())

    @Test
    fun reprintsFillTheFreeCorePrintingFirst() {
        assertEquals(mapOf(200 to OwnedCard(normal = 2)), scanned(CardCollection(), fireball to 2).cards)
        assertEquals(
            mapOf(200 to OwnedCard(normal = 2), 1 to OwnedCard(normal = 2)),
            scanned(CardCollection(), fireball to 4).cards,
        )
    }

    @Test
    fun knownCountsStayWhereTheyAre() {
        val known = CardCollection(mapOf(1 to OwnedCard(normal = 1, golden = 1)))
        assertEquals(known.cards, scanned(known, fireball to 2).cards)
        assertEquals(mapOf(1 to OwnedCard(golden = 1)), scanned(known, fireball to 1).cards)
    }

    @Test
    fun keepsGoldenCopiesWhenTheTotalChanges() {
        val known = CardCollection(mapOf(5 to OwnedCard(golden = 1)))
        assertEquals(mapOf(5 to OwnedCard(normal = 1, golden = 1)), scanned(known, listOf(5) to 2).cards)
    }
}
