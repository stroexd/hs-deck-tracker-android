package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.cards.CardSets
import com.stroexd.hsdecktracker.core.data.AppSettings
import com.stroexd.hsdecktracker.core.data.JsonFileStore
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreTest {

    @Test
    fun corruptFileFallsBackToDefaultAndIsKept() = runTest {
        val dir = Files.createTempDirectory("hs-store").toFile()
        val file = File(dir, "settings.json").apply { writeText("{ kaputt") }
        val store = JsonFileStore(file, AppSettings.serializer(), AppSettings())
        assertEquals(AppSettings(), store.value)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("settings.json.corrupt") })
        store.update { it.copy(cardLocale = "enUS") }
        assertEquals("enUS", JsonFileStore(file, AppSettings.serializer(), AppSettings()).value.cardLocale)
        dir.deleteRecursively()
    }

    @Test
    fun unknownFieldsAndEnumsAreTolerated() {
        val dir = Files.createTempDirectory("hs-store").toFile()
        val file = File(dir, "settings.json").apply {
            writeText("""{"cardLocale":"frFR","metaRankRange":"GIBT_ES_NICHT","neuesFeld":123}""")
        }
        val value = JsonFileStore(file, AppSettings.serializer(), AppSettings()).value
        assertEquals("frFR", value.cardLocale)
        assertEquals(AppSettings().metaRankRange, value.metaRankRange)
        dir.deleteRecursively()
    }

    @Test
    fun setNamesAndDefaults() {
        assertEquals("Kernset", CardSets.displayName("CORE"))
        assertEquals("Some New Set", CardSets.displayName("SOME_NEW_SET"))
        assertTrue(CardSets.isStandardByDefault("SOME_NEW_SET"))
        assertTrue(CardSets.isStandardByDefault("CORE"))
        assertFalse(CardSets.isStandardByDefault("GVG"))
        assertFalse(CardSets.isStandardByDefault("VANILLA"))
        assertFalse(CardSets.isStandardByDefault("HERO_SKINS"))
    }
}
