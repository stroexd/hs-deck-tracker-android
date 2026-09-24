package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.TestCards.DUAL_CLASS
import com.stroexd.hsdecktracker.core.TestCards.EPIC_MAGE
import com.stroexd.hsdecktracker.core.TestCards.FIREBALL
import com.stroexd.hsdecktracker.core.TestCards.LEEROY
import com.stroexd.hsdecktracker.core.TestCards.OLD_WILD
import com.stroexd.hsdecktracker.core.TestCards.RARE_NEUTRAL
import com.stroexd.hsdecktracker.core.TestCards.RENATHAL
import com.stroexd.hsdecktracker.core.cards.CardFilter
import com.stroexd.hsdecktracker.core.cards.CardSearch
import com.stroexd.hsdecktracker.core.cards.CardType
import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.cards.Ownership
import com.stroexd.hsdecktracker.core.collection.CardCollection
import com.stroexd.hsdecktracker.core.collection.OwnedCard
import com.stroexd.hsdecktracker.core.data.AppSettings
import com.stroexd.hsdecktracker.core.data.Backup
import com.stroexd.hsdecktracker.core.data.BackupData
import com.stroexd.hsdecktracker.core.data.DeckRepository
import com.stroexd.hsdecktracker.core.data.MatchRepository
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.deck.DeckAnalysis
import com.stroexd.hsdecktracker.core.deck.IssueSeverity
import com.stroexd.hsdecktracker.core.meta.HsReplayParser
import com.stroexd.hsdecktracker.core.meta.OpponentPredictor
import com.stroexd.hsdecktracker.core.stats.DrawOdds
import com.stroexd.hsdecktracker.core.stats.MatchRecord
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.stats.StatsCalculator
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiscTest {
    private val db = TestCards.db

    @Test
    fun cardDatabaseLookupsAndText() {
        assertEquals("Feuerball", db.byCardId("CS2_029")?.name)
        assertEquals(FIREBALL, db.findByName("feuerball")?.dbfId)
        assertEquals(LEEROY, db.resolve("LEG_001")?.dbfId)
        assertEquals("Verursacht 6 Schaden.", db.byDbfId(FIREBALL)?.plainText)
        assertEquals("Test Text", db.byDbfId(2)?.plainText)
        assertTrue(db.deckCards.none { it.cardType == CardType.HERO && it.set == "HERO_SKINS" })
        assertTrue(db.deckCards.none { it.id == "GAME_005" })
    }

    @Test
    fun formatRulesAndSearch() {
        val rules = FormatRules()
        assertTrue(rules.isLegal(db.byDbfId(LEEROY)!!, GameFormat.STANDARD))
        assertTrue(!rules.isLegal(db.byDbfId(OLD_WILD)!!, GameFormat.STANDARD))
        assertTrue(rules.isLegal(db.byDbfId(OLD_WILD)!!, GameFormat.WILD))
        val overridden = FormatRules(mapOf("NAXX" to true, "TIME_TRAVEL" to false))
        assertTrue(overridden.isLegal(db.byDbfId(OLD_WILD)!!, GameFormat.STANDARD))
        assertTrue(!overridden.isLegal(db.byDbfId(LEEROY)!!, GameFormat.STANDARD))

        val rogue = CardSearch.filter(db.deckCards, CardFilter(classes = setOf(HsClass.ROGUE)), rules)
        assertEquals(listOf(DUAL_CLASS), rogue.map { it.dbfId })
        val cheapNeutral = CardSearch.filter(db.deckCards, CardFilter(classes = setOf(HsClass.NEUTRAL), costs = setOf(2)), rules)
        assertEquals(listOf(RARE_NEUTRAL), cheapNeutral.map { it.dbfId })
        val murloc = CardSearch.filter(db.deckCards, CardFilter(query = "murloc"), rules)
        assertEquals(listOf(RARE_NEUTRAL), murloc.map { it.dbfId })
        val sevenPlus = CardSearch.filter(db.deckCards, CardFilter(costs = setOf(7)), rules)
        assertEquals(listOf(OLD_WILD), sevenPlus.map { it.dbfId })
        val collection = CardCollection(cards = mapOf(FIREBALL to OwnedCard(1)))
        val incomplete = CardSearch.filter(db.deckCards, CardFilter(ownership = Ownership.INCOMPLETE, classes = setOf(HsClass.MAGE)), rules, collection)
        assertTrue(FIREBALL in incomplete.map { it.dbfId })
        val owned = CardSearch.filter(db.deckCards, CardFilter(ownership = Ownership.OWNED), rules, collection)
        assertTrue(FIREBALL in owned.map { it.dbfId } && LEEROY !in owned.map { it.dbfId })
    }

    @Test
    fun deckValidationAndSummary() {
        val deck = Deck(
            name = "Test",
            heroClass = HsClass.WARRIOR,
            format = GameFormat.STANDARD,
            cards = mapOf(FIREBALL to 2, LEEROY to 2, OLD_WILD to 1, RENATHAL to 1),
        )
        val issues = DeckAnalysis.validate(deck, db, FormatRules())
        assertTrue(issues.any { it.message.contains("6 von 40") })
        assertTrue(issues.any { it.severity == IssueSeverity.ERROR && it.message.contains("Leeroy") })
        assertTrue(issues.any { it.message.contains("Feuerball gehört nicht") })
        assertTrue(issues.any { it.message.contains("Alte Karte ist in Standard nicht erlaubt") })

        val summary = DeckAnalysis.summary(deck.cards, db)
        assertEquals(6, summary.totalCards)
        assertEquals(listOf(0, 0, 0, 1, 2, 2, 0, 1), summary.manaCurve)
        assertEquals(2 * 40 + 2 * 1600 + 40 + 1600, summary.fullDustCost)
        val export = DeckAnalysis.exportText(deck, db)
        assertTrue(export.startsWith("### Test"))
        assertTrue(export.contains("# 2x (4) Feuerball"))
    }

    @Test
    fun drawOdds() {
        assertEquals(2.0 / 30, DrawOdds.nextDraw(30, 2))
        // Mindestens eine von 2 Karten in 3 Zügen aus 30: 1 - (28·27·26)/(30·29·28)
        val expected = 1 - (28.0 * 27 * 26) / (30.0 * 29 * 28)
        assertTrue(abs(DrawOdds.atLeastOne(30, 2, 3) - expected) < 1e-9)
        assertEquals(1.0, DrawOdds.atLeastOne(5, 1, 5))
        assertEquals(0.0, DrawOdds.atLeastOne(30, 0, 5))
        val mulligan = DrawOdds.withMulligan(30, 2, handSize = 3, turnDraws = 1)
        assertTrue(mulligan > DrawOdds.atLeastOne(30, 2, 4))
        assertTrue(mulligan < 1.0)
    }

    @Test
    fun statsCalculations() {
        fun m(t: Long, r: MatchResult, opp: HsClass, first: Boolean?) =
            MatchRecord(timestamp = t, deckId = "d", deckName = "D", playerClass = HsClass.MAGE, opponentClass = opp, result = r, wentFirst = first)
        val matches = listOf(
            m(1, MatchResult.WIN, HsClass.ROGUE, true),
            m(2, MatchResult.LOSS, HsClass.ROGUE, false),
            m(3, MatchResult.WIN, HsClass.PRIEST, true),
            m(4, MatchResult.WIN, HsClass.ROGUE, false),
            m(5, MatchResult.DRAW, HsClass.PRIEST, null),
        )
        val overall = StatsCalculator.overall(matches)
        assertEquals(3, overall.wins)
        assertEquals(0.75, overall.rate)
        assertEquals("3–1–1", overall.label)
        val byClass = StatsCalculator.byOpponentClass(matches)
        assertEquals(HsClass.ROGUE, byClass.keys.first())
        assertEquals(2, byClass[HsClass.ROGUE]?.wins)
        val (first, coin) = StatsCalculator.byTurnOrder(matches)
        assertEquals(1.0, first.rate)
        assertEquals(0.5, coin.rate)
        val streak = StatsCalculator.currentStreak(matches)
        assertEquals(MatchResult.WIN, streak?.result)
        assertEquals(2, streak?.length)
        assertEquals(listOf(1.0, 0.5, 2.0 / 3, 0.75), StatsCalculator.rollingWinRate(matches))
    }

    @Test
    fun hsReplayParsingAndPrediction() {
        val archetypes = HsReplayParser.parseArchetypes(
            """[{"id": 7, "name": "Aggro Magier", "player_class_name": "MAGE"}, {"id": 8, "name": "Kontroll-Magier"}]""",
        )
        assertEquals("Aggro Magier", archetypes[7])
        val response = """
            {"render_as": "table", "series": {"metadata": {}, "data": {
              "MAGE": [
                {"deck_id": "abc", "archetype_id": 7, "deck_list": "[[1,2],[5,2]]", "win_rate": 55.5, "total_games": 1200, "avg_num_player_turns": 7.5},
                {"deck_id": "def", "archetype_id": 8, "deck_list": [[4,2],[3,1]], "deck_sideboard": "[]", "win_rate": 48.0, "total_games": 800}
              ],
              "ROGUE": [{"deck_id": "ghi", "archetype_id": 99, "deck_list": "[[8,2]]", "win_rate": 51.0, "total_games": 50}]
            }}, "as_of": "2026-09-20T12:00:00Z"}
        """.trimIndent()
        val decks = HsReplayParser.parseDecks(response, archetypes, GameFormat.STANDARD)
        assertEquals(3, decks.size)
        val aggro = decks.first { it.id == "abc" }
        assertEquals("Aggro Magier", aggro.displayName)
        assertEquals(HsClass.MAGE, aggro.heroClass)
        assertTrue(abs(aggro.winRate!! - 0.555) < 1e-9)
        assertEquals(mapOf(FIREBALL to 2, RARE_NEUTRAL to 2), aggro.cards)
        assertEquals("Schurke-Deck", decks.first { it.id == "ghi" }.displayName)
        assertTrue(aggro.deckCode.startsWith("AAE"))

        val prediction = OpponentPredictor.predict(HsClass.MAGE, listOf(EPIC_MAGE), decks)
        assertEquals("def", prediction.first().deck.id)
        assertEquals(mapOf(EPIC_MAGE to 1, LEEROY to 1), prediction.first().remainingCards)
        assertEquals(2, OpponentPredictor.predict(HsClass.MAGE, emptyList(), decks).size)
    }

    @Test
    fun repositoriesPersistAndBackupRoundTrip() = runTest {
        val dir = Files.createTempDirectory("hs-test").toFile()
        val repo = DeckRepository(dir, clock = { 42L })
        val saved = repo.upsert(Deck(name = "Persistiert", heroClass = HsClass.MAGE, cards = mapOf(FIREBALL to 2)))
        assertEquals(42L, saved.createdAt)
        val imported = repo.importFromText("### Import\n${saved.deckCode()}", db)
        assertEquals("Import", imported.single().name)
        // Neue Instanz liest die Datei wieder ein
        val reloaded = DeckRepository(dir)
        assertEquals(listOf("Persistiert", "Import"), reloaded.decks.value.map { it.name })

        val matches = MatchRepository(dir)
        matches.add(MatchRecord(timestamp = 1, result = MatchResult.WIN))
        assertEquals(1, MatchRepository(dir).matches.value.size)

        val backup = BackupData(exportedAt = 5, decks = reloaded.decks.value, matches = matches.matches.value, settings = AppSettings(cardLocale = "enUS"))
        val restored = Backup.import(Backup.export(backup))
        assertEquals(backup, restored)
        dir.deleteRecursively()
    }
}
