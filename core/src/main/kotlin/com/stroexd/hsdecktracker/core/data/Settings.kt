package com.stroexd.hsdecktracker.core.data

import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.collection.CollectionOptions
import kotlinx.serialization.Serializable

enum class MetaSourceType { HSREPLAY, CUSTOM_URL }

/** Names match HSReplay's API values. */
enum class RankRange { BRONZE_THROUGH_GOLD, DIAMOND_THROUGH_LEGEND, LEGEND_ONLY, ALL }

enum class TimeRange { CURRENT_PATCH, LAST_3_DAYS, LAST_7_DAYS, LAST_14_DAYS }

/** Hearthstone client languages with their own names. */
object GameLocales {
    const val AUTO = "auto"
    const val ENGLISH = "enUS"

    val all: List<Pair<String, String>> = listOf(
        "enUS" to "English",
        "deDE" to "Deutsch",
        "frFR" to "Français",
        "esES" to "Español (EU)",
        "esMX" to "Español (AL)",
        "itIT" to "Italiano",
        "plPL" to "Polski",
        "ptBR" to "Português (BR)",
        "ruRU" to "Русский",
        "koKR" to "한국어",
        "jaJP" to "日本語",
        "zhCN" to "简体中文",
        "zhTW" to "繁體中文",
        "thTH" to "ไทย",
    )

    fun fromLanguage(language: String): String = when (language.lowercase()) {
        "pt" -> "ptBR"
        "zh" -> "zhCN"
        else -> all.firstOrNull { it.first.startsWith(language.lowercase()) }?.first ?: ENGLISH
    }
}

@Serializable
data class AppSettings(
    /** [GameLocales.AUTO] follows the language of the Hearthstone client. */
    val language: String = GameLocales.AUTO,
    val detectedGameLocale: String? = null,
    val coreSetOwned: Boolean = true,
    val standardSetOverrides: Map<String, Boolean> = emptyMap(),
    val metaSource: MetaSourceType = MetaSourceType.HSREPLAY,
    val metaRankRange: RankRange = RankRange.BRONZE_THROUGH_GOLD,
    val metaTimeRange: TimeRange = TimeRange.CURRENT_PATCH,
    val metaCustomUrl: String = "",
    val overlayOpacity: Float = 0.92f,
    val overlayShowOdds: Boolean = true,
    val overlayWidthDp: Int = 230,
    val autoRecordMatches: Boolean = true,
    val showRecognitionDebug: Boolean = false,
    val recordDiagnostics: Boolean = false,
) {
    val formatRules: FormatRules get() = FormatRules(standardSetOverrides)
    val collectionOptions: CollectionOptions get() = CollectionOptions(coreSetOwned = coreSetOwned)

    /** Card data and app language: the chosen one, otherwise Hearthstone's, otherwise the device's. */
    fun gameLocale(deviceLanguage: String): String = when {
        language != GameLocales.AUTO -> language
        detectedGameLocale != null -> detectedGameLocale
        else -> GameLocales.fromLanguage(deviceLanguage)
    }
}
