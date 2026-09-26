package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.cards.SetNames
import com.stroexd.hsdecktracker.core.data.CardRepository
import com.stroexd.hsdecktracker.core.data.HttpClient
import com.stroexd.hsdecktracker.core.meta.FormatDetection
import com.stroexd.hsdecktracker.core.meta.MetaDeck
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewSetsTest {
    private fun card(dbfId: Int, id: String, set: String) =
        """{"dbfId":$dbfId,"id":"$id","name":"Card $dbfId","cost":1,"type":"MINION","rarity":"COMMON","cardClass":"NEUTRAL","set":"$set","collectible":true}"""

    private val db = CardDatabase.parse(
        "[" + listOf(
            card(1, "EX1_001", "EXPERT1"),
            card(10, "RLK_100", "RETURN_OF_THE_LICH_KING"),
            card(11, "RLK_101", "RETURN_OF_THE_LICH_KING"),
            card(12, "RLK_900", "PATH_OF_ARTHAS"),
            card(20, "WW_001", "WILD_WEST"),
            card(30, "TIME_001", "TIME_TRAVEL"),
            card(31, "TIME_002", "TIME_TRAVEL"),
            card(40, "CATA_001", "CATACLYSM"),
            card(41, "CATA_002", "CATACLYSM"),
            card(42, "CATA_900", "EVENT"),
            card(50, "JAIL_001", "ESCAPEFROM_VIOLET_HOLD"),
            card(60, "VAN_001", "VANILLA"),
        ).joinToString(",") + "]",
    )

    private val strings = """
        GLOBAL_CARD_SET_EXPERT1	Classic
        GLOBAL_CARD_SET_RLK	March of the Lich King
        GLOBAL_CARD_SET_RLK_SHORT	Lich King
        GLOBAL_CARD_SET_PA	Path of Arthas
        GLOBAL_CARD_SET_WST	Showdown in the Badlands
        GLOBAL_CARD_SET_TIME	Across the Timeways
        GLOBAL_CARD_SET_TIME_SEARCHABLE_SHORTHAND_NAMES	tt	comment
        GLOBAL_CARD_SET_CATA	CATACLYSM
        GLOBAL_CARD_SET_EVE	Event
        GLOBAL_CARD_SET_JAIL	Escape from Violet Hold
        GLOBAL_CARD_SET_LETL		Lettuce
        GLOBAL_OTHER	Something
    """.trimIndent()

    @Test
    fun namesNewSetsFromTheGameStrings() {
        val names = SetNames.resolve(SetNames.parseStrings(strings), db.deckCards)
        assertEquals("March of the Lich King", names["RETURN_OF_THE_LICH_KING"])
        assertEquals("Path of Arthas", names["PATH_OF_ARTHAS"])
        assertEquals("Showdown in the Badlands", names["WILD_WEST"])
        assertEquals("Across the Timeways", names["TIME_TRAVEL"])
        assertEquals("CATACLYSM", names["CATACLYSM"])
        assertEquals("Event", names["EVENT"])
        assertEquals("Escape from Violet Hold", names["ESCAPEFROM_VIOLET_HOLD"])
        assertNull(names["EXPERT1"])
        assertEquals("Legacy (Classic)", db.withSetNames(names).setName("EXPERT1"))
    }

    private fun deck(vararg ids: Int) = MetaDeck("d", HsClass.MAGE, GameFormat.STANDARD, ids.associateWith { 2 })

    @Test
    fun standardFollowsWhatCurrentDecksPlay() {
        val decks = List(10) { deck(30, 41) }
        val detected = FormatDetection.fromStandardDecks(decks, db)!!
        // Sets newer than anything played yet are new releases and count as Standard too
        assertEquals(setOf("TIME_TRAVEL", "CATACLYSM", "EVENT", "ESCAPEFROM_VIOLET_HOLD"), detected.standard)
        assertTrue("RETURN_OF_THE_LICH_KING" in detected.wild)
        assertFalse("VANILLA" in detected.standard || "VANILLA" in detected.wild)
        assertNull(FormatDetection.fromStandardDecks(decks.take(3), db))
    }

    @Test
    fun manualChoicesBeatDetectionWhichBeatsTheBuiltInList() {
        val rules = FormatRules(mapOf("GVG" to true), detectedStandard = setOf("TIME_TRAVEL"), detectedWild = setOf("SPACE", "GVG"))
        assertTrue(rules.isStandardSet("GVG"))
        assertFalse(rules.isStandardSet("SPACE"))
        assertTrue(rules.isStandardSet("TIME_TRAVEL"))
        assertTrue(rules.isStandardSet("A_SET_FROM_NEXT_YEAR"))
    }

    private val server = MockWebServer()

    @AfterTest
    fun stopServer() = server.shutdown()

    @Test
    fun downloadsAgainOnlyWhenTheGameBuildChanges() = runTest {
        var build = 100
        var downloads = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/v1/latest/") ->
                        MockResponse().setResponseCode(302).setHeader("Location", path.replace("latest", "$build"))
                    request.method == "HEAD" -> MockResponse()
                    path.startsWith("/v1/") -> {
                        downloads++
                        MockResponse().setBody("[" + card(30, "TIME_001", "TIME_TRAVEL") + "]")
                    }
                    path.startsWith("/strings/") -> MockResponse().setBody(strings)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        var now = 1_000_000L
        val repository = CardRepository(
            Files.createTempDirectory("cards").toFile(),
            HttpClient(),
            clock = { now },
            cardsUrl = server.url("/v1/latest/%s/cards.collectible.json").toString(),
            stringsUrl = server.url("/strings/%s/GLOBAL.txt").toString(),
        )
        repository.load("enUS")
        assertEquals(1, downloads)
        assertEquals("Across the Timeways", repository.db.setName("TIME_TRAVEL"))

        now += 7 * 60 * 60 * 1000L
        repository.checkForUpdate()
        assertEquals(1, downloads)

        build = 101
        repository.checkForUpdate()
        assertEquals(1, downloads, "checks are throttled")
        now += 7 * 60 * 60 * 1000L
        repository.checkForUpdate()
        assertEquals(2, downloads)
    }
}
