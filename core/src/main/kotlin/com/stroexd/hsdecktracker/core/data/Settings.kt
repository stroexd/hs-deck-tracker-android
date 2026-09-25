package com.stroexd.hsdecktracker.core.data

import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.collection.CollectionOptions
import kotlinx.serialization.Serializable

enum class MetaSourceType(val displayName: String) {
    HSREPLAY("HSReplay.net (inoffiziell)"),
    CUSTOM_URL("Eigene Deck-Code-Liste (URL)"),
}

enum class RankRange(val apiValue: String, val displayName: String) {
    BRONZE_THROUGH_GOLD("BRONZE_THROUGH_GOLD", "Bronze – Gold"),
    DIAMOND_THROUGH_LEGEND("DIAMOND_THROUGH_LEGEND", "Diamant – Legende"),
    LEGEND_ONLY("LEGEND_ONLY", "Nur Legende"),
    ALL("ALL", "Alle Ränge"),
}

enum class TimeRange(val apiValue: String, val displayName: String) {
    CURRENT_PATCH("CURRENT_PATCH", "Aktueller Patch"),
    LAST_3_DAYS("LAST_3_DAYS", "Letzte 3 Tage"),
    LAST_7_DAYS("LAST_7_DAYS", "Letzte 7 Tage"),
    LAST_14_DAYS("LAST_14_DAYS", "Letzte 14 Tage"),
}

val cardLocales: List<Pair<String, String>> = listOf(
    "deDE" to "Deutsch",
    "enUS" to "English",
    "frFR" to "Français",
    "esES" to "Español",
    "itIT" to "Italiano",
    "plPL" to "Polski",
    "ptBR" to "Português (BR)",
    "ruRU" to "Русский",
    "koKR" to "한국어",
    "zhCN" to "简体中文",
    "jaJP" to "日本語",
)

@Serializable
data class AppSettings(
    val cardLocale: String = "deDE",
    val coreSetOwned: Boolean = true,
    /** Set → ist Standard (überschreibt die eingebaute Einschätzung). */
    val standardSetOverrides: Map<String, Boolean> = emptyMap(),
    val metaSource: MetaSourceType = MetaSourceType.HSREPLAY,
    val metaRankRange: RankRange = RankRange.BRONZE_THROUGH_GOLD,
    val metaTimeRange: TimeRange = TimeRange.CURRENT_PATCH,
    val metaCustomUrl: String = "",
    val overlayOpacity: Float = 0.92f,
    val overlayShowOdds: Boolean = true,
    val overlayWidthDp: Int = 230,
    /** Beendete Partien automatisch in der Match-History speichern. */
    val autoRecordMatches: Boolean = true,
    /** Zuletzt erkannte Texte im Overlay anzeigen (zur Kontrolle der Bilderkennung). */
    val showRecognitionDebug: Boolean = false,
    /** Erkannte Texte und einige Bildschirmfotos lokal speichern, um die Erkennung zu verbessern. */
    val recordDiagnostics: Boolean = false,
) {
    val formatRules: FormatRules get() = FormatRules(standardSetOverrides)
    val collectionOptions: CollectionOptions get() = CollectionOptions(coreSetOwned = coreSetOwned)
}
