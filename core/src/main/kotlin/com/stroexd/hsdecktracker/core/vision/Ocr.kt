package com.stroexd.hsdecktracker.core.vision

import com.stroexd.hsdecktracker.core.util.normalizeForSearch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Eine per Texterkennung gefundene Zeile. Koordinaten sind auf 0..1 normiert
 * (bezogen auf den Bildschirm im Querformat, links oben = 0/0).
 */
@Serializable
data class OcrLine(
    @SerialName("s") val text: String,
    @SerialName("l") val left: Float,
    @SerialName("t") val top: Float,
    @SerialName("r") val right: Float,
    @SerialName("b") val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    val height: Float get() = bottom - top
}

/** Ergebnis der Texterkennung für ein Bildschirmfoto. */
@Serializable
data class OcrFrame(
    @SerialName("ts") val timestamp: Long,
    @SerialName("lines") val lines: List<OcrLine>,
)

data class NameMatch(val key: String, val dbfIds: List<Int>, val score: Double)

/**
 * Findet Kartennamen in (fehlerbehafteten) OCR-Texten – exakt oder unscharf (Levenshtein).
 * Namen mehrerer Sprachen können gemischt werden (z. B. deutsche App, englischer Client).
 */
class CardNameIndex(names: List<Pair<Int, String>>) {

    private val byKey: Map<String, List<Int>> = names
        .mapNotNull { (id, name) -> normalize(name).takeIf { it.length >= MIN_LENGTH }?.let { it to id } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, ids) -> ids.distinct().sortedDescending() }

    private val byFirstChar: Map<Char, List<String>> = byKey.keys.groupBy { it.first() }

    val size: Int get() = byKey.size

    /**
     * Beste Übereinstimmung für eine ganze Zeile, oder null.
     *
     * @param preferred dbfIds, die im aktuellen Kontext plausibel sind (erkanntes Deck, Meta-Karten).
     *   Karten aus dieser Menge werden bevorzugt und mit lockererer Schwelle akzeptiert – so werden
     *   bekannte Deckkarten auch bei unsauberer Erkennung zuverlässiger getroffen, ohne dass der
     *   globale Abgleich mehr Fehltreffer produziert.
     */
    fun match(raw: String, preferred: Set<Int> = emptySet()): NameMatch? {
        val key = normalize(raw)
        if (key.length < MIN_LENGTH) return null
        byKey[key]?.let { return NameMatch(key, it, 1.0) }
        if (key.length < FUZZY_MIN_LENGTH) return null
        val candidates = byFirstChar[key.first()] ?: return null
        val maxDistance = max(2, key.length / 5)
        var best: String? = null
        var bestScore = 0.0
        var bestPreferred: String? = null
        var bestPreferredScore = 0.0
        for (candidate in candidates) {
            if (abs(candidate.length - key.length) > maxDistance) continue
            val score = similarity(key, candidate)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            if (preferred.isNotEmpty() && score > bestPreferredScore && byKey.getValue(candidate).any { it in preferred }) {
                bestPreferredScore = score
                bestPreferred = candidate
            }
        }
        // Bevorzugte Karte (aus dem Deck) mit lockererer Schwelle
        val preferredThreshold = if (key.length >= 8) 0.68 else 0.78
        if (bestPreferred != null && bestPreferredScore >= preferredThreshold) {
            return NameMatch(bestPreferred, byKey.getValue(bestPreferred).sortedByDescending { it in preferred }, bestPreferredScore)
        }
        val threshold = if (key.length >= 10) 0.8 else 0.85
        return if (best != null && bestScore >= threshold) NameMatch(best, byKey.getValue(best), bestScore) else null
    }

    /**
     * Sucht alle Kartennamen in einer Zeile. Versucht zuerst die ganze Zeile, danach
     * zusammenhängende Wortgruppen (falls die Erkennung zwei Namen zu einer Zeile verbindet).
     */
    fun findAll(raw: String, preferred: Set<Int> = emptySet()): List<NameMatch> {
        match(raw, preferred)?.let { return listOf(it) }
        val words = normalize(raw).split(' ').filter { it.isNotBlank() }
        if (words.size < 2 || words.size > 10) return emptyList()
        val result = mutableListOf<NameMatch>()
        var start = 0
        while (start < words.size) {
            var found: Pair<Int, NameMatch>? = null
            for (end in min(words.size, start + 5) downTo start + 1) {
                val candidate = words.subList(start, end).joinToString(" ")
                val m = byKey[candidate]?.let { NameMatch(candidate, it, 1.0) }
                    ?: if (candidate.length >= 8) match(candidate, preferred)?.takeIf { it.score >= 0.9 } else null
                if (m != null) {
                    found = end to m
                    break
                }
            }
            if (found != null) {
                result += found.second
                start = found.first
            } else {
                start++
            }
        }
        return result
    }

    companion object {
        const val MIN_LENGTH = 4
        const val FUZZY_MIN_LENGTH = 6
        private val edgeDigits = Regex("^\\d+\\s+|\\s+\\d+$")

        fun normalize(text: String): String = normalizeForSearch(text).replace(edgeDigits, "").trim()

        fun similarity(a: String, b: String): Double {
            val longest = max(a.length, b.length)
            if (longest == 0) return 1.0
            return 1.0 - levenshtein(a, b).toDouble() / longest
        }

        private fun levenshtein(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
                }
                val tmp = previous
                previous = current
                current = tmp
            }
            return previous[b.length]
        }
    }
}

/** Texte der Spieloberfläche in mehreren Sprachen (normalisiert: klein, ohne Akzente/Satzzeichen). */
object UiKeywords {
    val confirm = setOf("confirm", "bestatigen", "confirmer", "confirmar", "conferma", "potwierdz", "confirma")
    val ownTurn = setOf(
        "end turn", "your turn", "zug beenden", "zug ende", "du bist am zug", "dein zug",
        "fin du tour", "a vous de jouer", "fin de turno", "tu turno", "fine turno", "tocca a te",
    )
    val enemyTurn = setOf(
        "enemy turn", "opponent s turn", "opponents turn", "gegnerischer zug", "gegnerzug", "zug des gegners",
        "tour adverse", "tour de l adversaire", "turno enemigo", "turno del rival", "turno avversario",
    )
    val victory = setOf("victory", "sieg", "victoire", "victoria", "vittoria", "zwyciestwo")
    val defeat = setOf("defeat", "niederlage", "defaite", "derrota", "sconfitta", "porazka")
    val tie = setOf("tie", "unentschieden", "egalite", "empate", "pareggio")
    val coin = setOf("the coin", "die munze", "la piece", "la moneda", "la moneta", "moneta")

    fun matches(normalized: String, keywords: Set<String>): Boolean =
        normalized in keywords || keywords.any { it.length >= 7 && normalized.contains(it) }
}
