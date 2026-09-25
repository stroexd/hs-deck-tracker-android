package com.stroexd.hsdecktracker.core.util

import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

/** Gemeinsame JSON-Konfiguration: tolerant beim Lesen, vollständig beim Schreiben. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

val PrettyJson: Json = Json(AppJson) { prettyPrint = true }

private val diacritics = Regex("\\p{InCombiningDiacriticalMarks}+")
private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")

/** Kleinbuchstaben, ohne Akzente/Satzzeichen – für tolerante Suche und Namensvergleich. */
fun normalizeForSearch(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT).replace("ß", "ss"), Normalizer.Form.NFD)
    return decomposed.replace(diacritics, "").replace(nonAlnum, " ").trim()
}

/** Formatiert Zahlen mit deutschem Tausendertrennzeichen, z. B. `12.400`. */
fun formatNumber(value: Int): String = String.format(Locale.GERMANY, "%,d", value)

fun formatPercent(value: Double?, decimals: Int = 1): String =
    if (value == null || value.isNaN()) "–" else String.format(Locale.GERMANY, "%.${decimals}f %%", value * 100)
