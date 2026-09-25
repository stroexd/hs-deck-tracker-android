package com.stroexd.hsdecktracker.core.vision

import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.util.normalizeForSearch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

/** Card names stand alone on their banner; a line right below other text belongs to a card text. */
internal fun OcrLine.continuesText(lines: List<OcrLine>): Boolean {
    val h = max(height, 0.01f)
    return lines.any { other ->
        other !== this &&
            other.bottom <= top + 0.5f * h &&
            other.bottom >= top - 1.2f * h &&
            min(other.right, right) > max(other.left, left) &&
            other.text.count { it.isLetter() } >= 3
    }
}

@Serializable
data class OcrFrame(
    @SerialName("ts") val timestamp: Long,
    @SerialName("lines") val lines: List<OcrLine>,
    @SerialName("a") val aspect: Float = 0f,
)

data class NameMatch(val key: String, val dbfIds: List<Int>, val score: Double)

class CardNameIndex(names: List<Pair<Int, String>>) {
    private val byKey: Map<String, List<Int>> = names
        .mapNotNull { (id, name) -> normalize(name).takeIf { it.length >= MIN_LENGTH }?.let { it to id } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, ids) -> ids.distinct().sortedDescending() }

    private val byFirstChar: Map<Char, List<String>> = byKey.keys.groupBy { it.first() }

    val size: Int get() = byKey.size

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
        val preferredThreshold = if (key.length >= 8) 0.75 else 0.8
        if (bestPreferred != null && bestPreferredScore >= preferredThreshold) {
            return NameMatch(bestPreferred, idsFor(bestPreferred, preferred), bestPreferredScore)
        }
        val threshold = if (key.length >= 10) 0.8 else 0.85
        return if (best != null && bestScore >= threshold) NameMatch(best, byKey.getValue(best), bestScore) else null
    }

    private fun prefixMatch(key: String, candidates: List<String>, preferred: Set<Int>): NameMatch? {
        if (key.length < PREFIX_MIN_LENGTH) return null
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

/** Hearthstone UI texts per client language (normalized: lowercase, no accents or punctuation). */
private class UiLanguage(
    val locale: String,
    val confirm: List<String>,
    val mulligan: List<String>,
    val choice: List<String>,
    val ownTurn: List<String>,
    val enemyTurn: List<String>,
    val yourTurn: List<String>,
    val victory: List<String>,
    val defeat: List<String>,
    val tie: List<String>,
    val coin: List<String>,
    val extraCard: List<String>,
    val classes: Map<HsClass, String>,
) {
    val all: List<String>
        get() = confirm + mulligan + choice + ownTurn + enemyTurn + yourTurn + victory + defeat + tie + coin + extraCard + classes.values
}

object UiKeywords {
    private val languages = listOf(
        UiLanguage(
            locale = "enUS",
            confirm = listOf("confirm"),
            mulligan = listOf("starting hand", "keep or replace cards"),
            choice = listOf("choose one", "discover"),
            ownTurn = listOf("end turn"),
            enemyTurn = listOf("enemy turn", "opponent s turn"),
            yourTurn = listOf("your turn"),
            victory = listOf("victory"),
            defeat = listOf("defeat"),
            tie = listOf("tie"),
            coin = listOf("the coin"),
            extraCard = listOf("you get an extra card"),
            classes = mapOf(
                HsClass.DEATHKNIGHT to "death knight", HsClass.DEMONHUNTER to "demon hunter", HsClass.DRUID to "druid",
                HsClass.HUNTER to "hunter", HsClass.MAGE to "mage", HsClass.PALADIN to "paladin", HsClass.PRIEST to "priest",
                HsClass.ROGUE to "rogue", HsClass.SHAMAN to "shaman", HsClass.WARLOCK to "warlock", HsClass.WARRIOR to "warrior",
            ),
        ),
        UiLanguage(
            locale = "deDE",
            confirm = listOf("bestatigen"),
            mulligan = listOf("starthand", "karten behalten oder ersetzen"),
            choice = listOf("wahlt eine", "wahlt eine karte"),
            ownTurn = listOf("zug beenden"),
            enemyTurn = listOf("gegnerischer zug", "gegnerzug", "zug des gegners"),
            yourTurn = listOf("euer zug", "ihr seid am zug", "dein zug", "du bist am zug"),
            victory = listOf("sieg"),
            defeat = listOf("niederlage"),
            tie = listOf("unentschieden"),
            coin = listOf("die munze"),
            extraCard = listOf("ihr erhaltet eine zusatzliche karte", "du erhaltst eine zusatzliche karte"),
            classes = mapOf(
                HsClass.DEATHKNIGHT to "todesritter", HsClass.DEMONHUNTER to "damonenjager", HsClass.DRUID to "druide",
                HsClass.HUNTER to "jager", HsClass.MAGE to "magier", HsClass.PALADIN to "paladin", HsClass.PRIEST to "priester",
                HsClass.ROGUE to "schurke", HsClass.SHAMAN to "schamane", HsClass.WARLOCK to "hexenmeister", HsClass.WARRIOR to "krieger",
            ),
        ),
        UiLanguage(
            locale = "frFR",
            confirm = listOf("confirmer"),
            mulligan = listOf("main de depart"),
            choice = listOf("choisissez"),
            ownTurn = listOf("fin du tour"),
            enemyTurn = listOf("tour adverse", "tour de l adversaire"),
            yourTurn = listOf("a vous de jouer", "votre tour"),
            victory = listOf("victoire"),
            defeat = listOf("defaite"),
            tie = listOf("egalite"),
            coin = listOf("la piece"),
            extraCard = emptyList(),
            classes = mapOf(
                HsClass.DEATHKNIGHT to "chevalier de la mort", HsClass.DEMONHUNTER to "chasseur de demons", HsClass.DRUID to "druide",
                HsClass.HUNTER to "chasseur", HsClass.MAGE to "mage", HsClass.PALADIN to "paladin", HsClass.PRIEST to "pretre",
                HsClass.ROGUE to "voleur", HsClass.SHAMAN to "chaman", HsClass.WARLOCK to "demoniste", HsClass.WARRIOR to "guerrier",
            ),
        ),
        UiLanguage(
            locale = "esES",
            confirm = listOf("confirmar"),
            mulligan = listOf("mano inicial"),
            choice = listOf("elige una"),
            ownTurn = listOf("fin de turno", "terminar turno"),
            enemyTurn = listOf("turno enemigo", "turno del rival"),
            yourTurn = listOf("tu turno"),
            victory = listOf("victoria"),
            defeat = listOf("derrota"),
            tie = listOf("empate"),
            coin = listOf("la moneda"),
            extraCard = emptyList(),
            classes = mapOf(
                HsClass.DEATHKNIGHT to "caballero de la muerte", HsClass.DEMONHUNTER to "cazador de demonios", HsClass.DRUID to "druida",
                HsClass.HUNTER to "cazador", HsClass.MAGE to "mago", HsClass.PALADIN to "paladin", HsClass.PRIEST to "sacerdote",
                HsClass.ROGUE to "picaro", HsClass.SHAMAN to "chaman", HsClass.WARLOCK to "brujo", HsClass.WARRIOR to "guerrero",
            ),
        ),
        UiLanguage(
            locale = "itIT",
            confirm = listOf("conferma"),
            mulligan = listOf("mano iniziale"),
            choice = listOf("scegli"),
            ownTurn = listOf("fine turno"),
            enemyTurn = listOf("turno avversario"),
            yourTurn = listOf("tocca a te", "il tuo turno"),
            victory = listOf("vittoria"),
            defeat = listOf("sconfitta"),
            tie = listOf("pareggio"),
            coin = listOf("la moneta"),
            extraCard = emptyList(),
            classes = mapOf(
                HsClass.DEATHKNIGHT to "cavaliere della morte", HsClass.DEMONHUNTER to "cacciatore di demoni", HsClass.DRUID to "druido",
                HsClass.HUNTER to "cacciatore", HsClass.MAGE to "mago", HsClass.PALADIN to "paladino", HsClass.PRIEST to "sacerdote",
                HsClass.ROGUE to "ladro", HsClass.SHAMAN to "sciamano", HsClass.WARLOCK to "stregone", HsClass.WARRIOR to "guerriero",
            ),
        ),
        UiLanguage(
            locale = "plPL",
            confirm = listOf("potwierdz"),
            mulligan = listOf("reka startowa"),
            choice = listOf("wybierz"),
            ownTurn = listOf("koniec tury"),
            enemyTurn = listOf("tura przeciwnika"),
            yourTurn = listOf("twoja tura"),
            victory = listOf("zwyciestwo"),
            defeat = listOf("porazka"),
            tie = listOf("remis"),
            coin = listOf("moneta"),
            extraCard = emptyList(),
            classes = mapOf(
                HsClass.DEATHKNIGHT to "rycerz smierci", HsClass.DEMONHUNTER to "łowca demonow", HsClass.DRUID to "druid",
                HsClass.HUNTER to "łowca", HsClass.MAGE to "mag", HsClass.PALADIN to "paladyn", HsClass.PRIEST to "kapłan",
                HsClass.ROGUE to "łotr", HsClass.SHAMAN to "szaman", HsClass.WARLOCK to "czarnoksieznik", HsClass.WARRIOR to "wojownik",
            ),
        ),
        UiLanguage(
            locale = "ptBR",
            confirm = listOf("confirmar"),
            mulligan = listOf("mao inicial"),
            choice = listOf("escolha uma"),
            ownTurn = listOf("encerrar turno", "fim do turno"),
            enemyTurn = listOf("turno do oponente", "turno inimigo"),
            yourTurn = listOf("sua vez", "seu turno"),
            victory = listOf("vitoria"),
            defeat = listOf("derrota"),
            tie = listOf("empate"),
            coin = listOf("a moeda"),
            extraCard = emptyList(),
            classes = mapOf(
                HsClass.DEATHKNIGHT to "cavaleiro da morte", HsClass.DEMONHUNTER to "cacador de demonios", HsClass.DRUID to "druida",
                HsClass.HUNTER to "cacador", HsClass.MAGE to "mago", HsClass.PALADIN to "paladino", HsClass.PRIEST to "sacerdote",
                HsClass.ROGUE to "ladino", HsClass.SHAMAN to "xama", HsClass.WARLOCK to "bruxo", HsClass.WARRIOR to "guerreiro",
            ),
        ),
    ).map { it.normalized() }

    private fun UiLanguage.normalized() = UiLanguage(
        locale, confirm, mulligan, choice, ownTurn, enemyTurn, yourTurn, victory, defeat, tie, coin, extraCard,
        classes.mapValues { normalizeForSearch(it.value) },
    )

    private fun set(pick: (UiLanguage) -> List<String>): Set<String> = languages.flatMapTo(HashSet(), pick)

    val confirm = set { it.confirm }
    val mulligan = set { it.mulligan }
    val choice = set { it.choice }
    val coin = set { it.coin }
    val extraCard = set { it.extraCard }
    private val victory = set { it.victory }
    private val defeat = set { it.defeat }
    private val tie = set { it.tie }
    private val ownTurn = set { it.ownTurn }.map { it.replace(" ", "") }
    private val enemyTurn = set { it.enemyTurn }.map { it.replace(" ", "") }
    private val yourTurn = set { it.yourTurn }.map { it.replace(" ", "") }

    /** Fragments that survive typical misreadings of the turn button (e.g. "EMY TURN"). */
    private val enemyFragments = listOf("enemy", "nemyt", "emyturn", "gegner", "advers", "rival", "avversar", "enemig", "przeciwn", "oponent")
    private val ownFragments = listOf("endturn", "zugbeend", "findutour", "findeturno", "fineturno", "koniectury", "encerrarturno")

    private val classNames: Map<String, HsClass> =
        languages.flatMap { language -> language.classes.map { (cls, name) -> name to cls } }.toMap()

    /** Texts that only exist in one client language. */
    private val localeOfText: Map<String, String> = languages
        .flatMap { language -> language.all.map { normalizeForSearch(it) to language.locale } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.distinct().size == 1 }
        .mapValues { it.value.first() }

    fun localeOf(normalized: String): String? = localeOfText[normalized]

    fun matches(normalized: String, keywords: Set<String>): Boolean =
        normalized in keywords || keywords.any { it.length >= 7 && normalized.contains(it) }

    /** Turn button: true = own turn, false = opponent's turn, null = unreadable. */
    fun turnButton(normalized: String): Boolean? {
        val compact = normalized.replace(" ", "")
        if (compact.length < 6 || compact.length > 20) return null
        if (enemyFragments.any { it in compact }) return false
        if (ownFragments.any { it in compact }) return true
        val own = ownTurn.maxOf { CardNameIndex.similarity(compact, it) }
        val enemy = enemyTurn.maxOf { CardNameIndex.similarity(compact, it) }
        return when {
            enemy >= 0.7 && enemy > own -> false
            own >= 0.7 && own > enemy -> true
            else -> null
        }
    }

    fun isYourTurnBanner(normalized: String): Boolean {
        val compact = normalized.replace(" ", "")
        if (compact.length < 5 || compact.length > 16) return false
        return yourTurn.any { CardNameIndex.similarity(compact, it) >= 0.7 || (it.length >= 7 && it.endsWith(compact) && compact.length >= 6) }
    }

    fun result(normalized: String): MatchResult? = when (normalized) {
        in victory -> MatchResult.WIN
        in defeat -> MatchResult.LOSS
        in tie -> MatchResult.DRAW
        else -> null
    }

    /** Class on a name plate, e.g. "DEMON HUNTER"; tolerates female forms and small misreadings. */
    fun heroClass(normalized: String): HsClass? {
        classNames[normalized]?.let { return it }
        if (normalized.length < 5) return null
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
