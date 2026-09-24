package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.TestCards.ARCANE_MISSILES
import com.stroexd.hsdecktracker.core.TestCards.FIREBALL
import com.stroexd.hsdecktracker.core.TestCards.LEEROY
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.deck.DeckCode
import com.stroexd.hsdecktracker.core.deck.DeckDefinition
import com.stroexd.hsdecktracker.core.deck.DeckTextParser
import com.stroexd.hsdecktracker.core.deck.InvalidDeckCodeException
import com.stroexd.hsdecktracker.core.deck.SideboardCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckCodeTest {

    /** Beispiel-Deckstring aus der HearthSim-Dokumentation (Jäger, Standard, 30 Karten). */
    private val knownCode = "AAECAR8GxwPJBLsFmQfZB/gIDI0B2AGoArUDhwSSBe0G6wfbCe0JgQr+DAA="

    @Test
    fun decodesKnownDeckstring() {
        val deck = DeckCode.decode(knownCode)
        assertEquals(listOf(31), deck.heroes)
        assertEquals(GameFormat.STANDARD, deck.format)
        assertEquals(30, deck.cardCount)
        assertEquals(18, deck.cards.size)
        assertEquals(6, deck.cards.values.count { it == 1 })
        assertEquals(12, deck.cards.values.count { it == 2 })
        assertEquals(HsClass.HUNTER, HsClass.fromDefaultHeroDbfId(deck.heroes.first()))
    }

    @Test
    fun roundTripKeepsContent() {
        val original = DeckCode.decode(knownCode)
        val reencoded = DeckCode.decode(DeckCode.encode(original))
        assertEquals(original.heroes, reencoded.heroes)
        assertEquals(original.format, reencoded.format)
        assertEquals(original.cards, reencoded.cards)
    }

    @Test
    fun roundTripWithLargeIdsCountsAndSideboards() {
        val definition = DeckDefinition(
            heroes = listOf(78065),
            format = GameFormat.WILD,
            cards = mapOf(1 to 1, 300 to 2, 70000 to 3, 123456 to 1),
            sideboards = listOf(SideboardCard(90001, 1, 123456), SideboardCard(90002, 2, 123456), SideboardCard(90003, 3, 123456)),
        )
        val decoded = DeckCode.decode(DeckCode.encode(definition))
        assertEquals(definition.heroes, decoded.heroes)
        assertEquals(definition.format, decoded.format)
        assertEquals(definition.cards, decoded.cards)
        assertEquals(definition.sideboards.toSet(), decoded.sideboards.toSet())
    }

    @Test
    fun acceptsMissingPaddingAndWhitespace() {
        val noPadding = knownCode.trimEnd('=')
        assertEquals(30, DeckCode.decode(" $noPadding \n").cardCount)
    }

    @Test
    fun rejectsInvalidCodes() {
        assertFailsWith<InvalidDeckCodeException> { DeckCode.decode("das ist kein code") }
        assertFailsWith<InvalidDeckCodeException> { DeckCode.decode("AAECAR8G") }
        assertFailsWith<InvalidDeckCodeException> { DeckCode.decode("") }
        assertNull(DeckCode.decodeOrNull("QUJD"))
    }

    @Test
    fun parsesClientExportText() {
        val deck = Deck(name = "Testdeck", heroClass = HsClass.MAGE, cards = mapOf(FIREBALL to 2, ARCANE_MISSILES to 2, LEEROY to 1))
        val text = """
            ### Mein Magier
            # Klasse: Magier
            # Format: Standard
            #
            # 2x (1) Arkane Geschosse
            #
            ${deck.deckCode()}
            #
            # Um dieses Deck zu verwenden ...
        """.trimIndent()
        val parsed = DeckTextParser.parseAll(text)
        assertEquals(1, parsed.size)
        assertEquals("Mein Magier", parsed.first().name)
        assertEquals(deck.cards, parsed.first().definition.cards)
    }

    @Test
    fun findsMultipleCodesInText() {
        val a = Deck(name = "A", heroClass = HsClass.MAGE, cards = mapOf(FIREBALL to 2)).deckCode()
        val b = Deck(name = "B", heroClass = HsClass.WARRIOR, cards = mapOf(LEEROY to 1)).deckCode()
        val parsed = DeckTextParser.parseAll("Top-Decks:\n### Erstes\n$a\nNoch eins: $b und Text")
        assertEquals(2, parsed.size)
        assertEquals("Erstes", parsed[0].name)
        assertNull(parsed[1].name)
    }

    @Test
    fun detectsClassFromHeroOrCards() {
        val db = TestCards.db
        val mage = DeckDefinition(listOf(637), GameFormat.STANDARD, mapOf(FIREBALL to 2))
        assertEquals(HsClass.MAGE, Deck.detectClass(mage, db))
        val unknownHero = DeckDefinition(listOf(999999), GameFormat.STANDARD, mapOf(FIREBALL to 2, LEEROY to 1))
        assertEquals(HsClass.MAGE, Deck.detectClass(unknownHero, db))
        val imported = Deck.fromDefinition(mage, null, db, now = 5)
        assertEquals("Magier-Deck", imported.name)
        assertTrue(imported.deckCode().startsWith("AAE"))
    }
}
