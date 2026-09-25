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
        store.update { it.copy(language = "enUS") }
        assertEquals("enUS", JsonFileStore(file, AppSettings.serializer(), AppSettings()).value.language)
        dir.deleteRecursively()
    }

    @Test
    fun unknownFieldsAndEnumsAreTolerated() {
        val dir = Files.createTempDirectory("hs-store").toFile()
        val file = File(dir, "settings.json").apply {
            writeText("""{"language":"frFR","metaRankRange":"DOES_NOT_EXIST","newField":123}""")
        }
        val value = JsonFileStore(file, AppSettings.serializer(), AppSettings()).value
        assertEquals("frFR", value.language)
        assertEquals(AppSettings().metaRankRange, value.metaRankRange)
        dir.deleteRecursively()
    }

    @Test
    fun setNamesAndDefaults() {
        assertEquals("Core", CardSets.displayName("CORE"))
        assertEquals("Some New Set", CardSets.displayName("SOME_NEW_SET"))
        assertTrue(CardSets.isStandardByDefault("SOME_NEW_SET"))
        assertTrue(CardSets.isStandardByDefault("CORE"))
        assertFalse(CardSets.isStandardByDefault("GVG"))
        assertFalse(CardSets.isStandardByDefault("VANILLA"))
        assertFalse(CardSets.isStandardByDefault("HERO_SKINS"))
    }
}
