package com.stroexd.hsdecktracker.core.vision

import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.stats.MatchResult
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
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Ergebnis der Texterkennung für ein Bildschirmfoto. */
@Serializable
data class OcrFrame(
    @SerialName("ts") val timestamp: Long,
    @SerialName("lines") val lines: List<OcrLine>,
    /** Seitenverhältnis (Breite/Höhe) des Bildschirmfotos, 0 = unbekannt. */
    @SerialName("a") val aspect: Float = 0f,
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
     * Reihenfolge: exakter Name, eindeutig abgeschnittener Name (z. B. von überlappenden Handkarten
     * verdeckt), unscharfer Abgleich.
     *
     * @param preferred dbfIds, die im aktuellen Kontext plausibel sind (erkanntes Deck, Meta-Karten).
     *   Karten aus dieser Menge werden bevorzugt und mit etwas lockererer Schwelle akzeptiert.
     */
    fun match(raw: String, preferred: Set<Int> = emptySet()): NameMatch? {
        val key = normalize(raw)
        if (key.length < MIN_LENGTH) return null
        byKey[key]?.let { return NameMatch(key, it, 1.0) }
        if (key.length < FUZZY_MIN_LENGTH) return null
        val candidates = byFirstChar[key.first()] ?: return null
        prefixMatch(key, candidates, preferred)?.let { return it }
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
            if (preferred.isNotEmpty() && score > bestPreferredScore && isPreferred(candidate, preferred)) {
                bestPreferredScore = score
                bestPreferred = candidate
            }
        }
        // Bevorzugte Karte (aus dem Deck) mit etwas lockererer Schwelle
        val preferredThreshold = if (key.length >= 8) 0.75 else 0.8
        if (bestPreferred != null && bestPreferredScore >= preferredThreshold) {
            return NameMatch(bestPreferred, idsFor(bestPreferred, preferred), bestPreferredScore)
        }
        val threshold = if (key.length >= 10) 0.8 else 0.85
        return if (best != null && bestScore >= threshold) NameMatch(best, byKey.getValue(best), bestScore) else null
    }

    /** Abgeschnittener Name: eindeutiger Namensanfang mit mindestens 60 % der Länge. */
    private fun prefixMatch(key: String, candidates: List<String>, preferred: Set<Int>): NameMatch? {
        if (key.length < PREFIX_MIN_LENGTH) return null
        // Eindeutig unter allen Namen mit diesem Anfang („Twilight“ passt auf viele Karten → keine)
        val prefixed = candidates.filter { it.length > key.length && it.startsWith(key) }
        if (prefixed.isEmpty()) return null
        val pick = prefixed.singleOrNull() ?: prefixed.filter { isPreferred(it, preferred) }.singleOrNull() ?: return null
        if (key.length * 10 < pick.length * 6) return null
        return NameMatch(pick, idsFor(pick, preferred), key.length.toDouble() / pick.length)
    }

    private fun isPreferred(key: String, preferred: Set<Int>): Boolean =
        preferred.isNotEmpty() && byKey.getValue(key).any { it in preferred }

    private fun idsFor(key: String, preferred: Set<Int>): List<Int> =
        byKey.getValue(key).sortedByDescending { it in preferred }

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
        const val PREFIX_MIN_LENGTH = 8
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
    val mulligan = setOf(
        "starting hand", "keep or replace cards", "starthand", "main de depart", "mano inicial", "mano iniziale",
    )
    val choice = setOf("choose one", "discover", "wahlt eine", "wahlt eine karte", "entdeckt", "choisissez", "elige una", "scegli")
    val ownTurn = setOf(
        "end turn", "your turn", "zug beenden", "zug ende", "du bist am zug", "dein zug", "euer zug", "ihr seid am zug",
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

    /** Hinweis beim Mulligan für den zweiten Spieler. */
    val extraCard = setOf("you get an extra card", "ihr erhaltet eine zusatzliche karte", "du erhaltst eine zusatzliche karte")

    private val ownTurnCompact = ownTurn.map { it.replace(" ", "") }
    private val enemyTurnCompact = enemyTurn.map { it.replace(" ", "") }
    private val enemyFragments = listOf("enemy", "nemyt", "emyturn", "gegner", "advers", "rival", "avversar", "enemig")
    private val ownFragments = listOf("endturn", "zugbeend", "findutour", "findeturno", "fineturno")

    /** Klassennamen, wie sie auf dem Versus-Bildschirm und den Namensschildern stehen. */
    private val classNames: Map<String, HsClass> = buildMap {
        put("demon hunter", HsClass.DEMONHUNTER)
        put("death knight", HsClass.DEATHKNIGHT)
        put("druid", HsClass.DRUID)
        put("hunter", HsClass.HUNTER)
        put("mage", HsClass.MAGE)
        put("paladin", HsClass.PALADIN)
        put("priest", HsClass.PRIEST)
        put("rogue", HsClass.ROGUE)
        put("shaman", HsClass.SHAMAN)
        put("warlock", HsClass.WARLOCK)
        put("warrior", HsClass.WARRIOR)
        HsClass.playable.forEach { put(normalizeForSearch(it.displayName), it) }
    }

    fun matches(normalized: String, keywords: Set<String>): Boolean =
        normalized in keywords || keywords.any { it.length >= 7 && normalized.contains(it) }

    /**
     * Beschriftung des Zug-Knopfs (nur für Text an dessen Position aufrufen): true = eigener Zug
     * („Zug beenden“), false = Gegner am Zug, null = nicht lesbar. Tolerant gegenüber Lesefehlern
     * wie „EMY TURN“ oder „NEMYTURN“.
     */
    fun turnButton(normalized: String): Boolean? {
        val compact = normalized.replace(" ", "")
        if (compact.length < 6 || compact.length > 20) return null
        if (enemyFragments.any { it in compact }) return false
        if (ownFragments.any { it in compact }) return true
        val own = ownTurnCompact.maxOf { CardNameIndex.similarity(compact, it) }
        val enemy = enemyTurnCompact.maxOf { CardNameIndex.similarity(compact, it) }
        return when {
            enemy >= 0.7 && enemy > own -> false
            own >= 0.7 && own > enemy -> true
            else -> null
        }
    }

    /** Großer Hinweis „Du bist am Zug“ in der Bildschirmmitte. */
    fun isYourTurnBanner(normalized: String): Boolean {
        val compact = normalized.replace(" ", "")
        if (compact.length < 5 || compact.length > 16) return false
        return listOf("yourturn", "deinzug", "euerzug", "ihrseidamzug", "duistamzug", "avousdejouer", "tuturno", "toccaate")
            .any { CardNameIndex.similarity(compact, it) >= 0.7 || (it.length >= 7 && it.endsWith(compact) && compact.length >= 6) }
    }

    fun result(normalized: String): MatchResult? = when (normalized) {
        in victory -> MatchResult.WIN
        in defeat -> MatchResult.LOSS
        in tie -> MatchResult.DRAW
        else -> null
    }

    /** Klassenname auf einem Namensschild, z. B. „DEMON HUNTER“ oder „SCHURKE“. */
    fun heroClass(normalized: String): HsClass? {
        classNames[normalized]?.let { return it }
        if (normalized.length < 5) return null
        // weibliche Formen („Jägerin“) und kleine Lesefehler
        classNames.entries.firstOrNull { (name, _) ->
            name.length >= 5 && normalized.startsWith(name) && normalized.length <= name.length + 2
        }?.let { return it.value }
        return classNames.entries
            .filter { it.key.length >= 6 }
            .maxByOrNull { CardNameIndex.similarity(normalized, it.key) }
            ?.takeIf { CardNameIndex.similarity(normalized, it.key) >= 0.85 }
            ?.value
    }
}
