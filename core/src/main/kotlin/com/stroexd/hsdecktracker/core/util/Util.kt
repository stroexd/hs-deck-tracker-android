package com.stroexd.hsdecktracker.core.util

import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

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

fun normalizeForSearch(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT).replace("ß", "ss"), Normalizer.Form.NFD)
    return decomposed.replace(diacritics, "").replace(nonAlnum, " ").trim()
}

fun formatNumber(value: Int): String = String.format(Locale.getDefault(), "%,d", value)

fun formatPercent(value: Double?, decimals: Int = 1): String =
    if (value == null || value.isNaN()) "–" else String.format(Locale.getDefault(), "%.${decimals}f%%", value * 100)
