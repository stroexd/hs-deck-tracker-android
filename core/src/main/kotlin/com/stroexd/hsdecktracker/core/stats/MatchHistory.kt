package com.stroexd.hsdecktracker.core.stats

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.util.normalizeForSearch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ResultFilter(val displayName: String) {
    ALL("Alle"),
    WINS("Siege"),
    LOSSES("Niederlagen"),
}

data class MatchQuery(
    val text: String = "",
    val result: ResultFilter = ResultFilter.ALL,
    val opponentClass: HsClass? = null,
    val deckId: String? = null,
    val format: GameFormat? = null,
    val sinceMillis: Long? = null,
)

/** Partien eines Kalendertags mit Tagesbilanz. */
data class MatchDay(val date: LocalDate, val matches: List<MatchRecord>) {
    val winRate: WinRate get() = StatsCalculator.overall(matches)
}

/** Zusammenfassung eines Zugs aus dem Partieverlauf. */
data class TurnSummary(
    val turn: Int,
    val drawn: List<Int>,
    val returned: List<Int>,
    val extraDrawn: List<String>,
    val opponentPlayed: List<Int>,
)

object MatchHistory {

    /** Filtert und sortiert (neueste zuerst). Die Textsuche berücksichtigt Deck, Archetyp, Klassen, Notizen und Gegnerkarten. */
    fun filter(matches: List<MatchRecord>, query: MatchQuery, db: CardDatabase? = null): List<MatchRecord> {
        val terms = normalizeForSearch(query.text).split(' ').filter { it.isNotBlank() }
        return matches.filter { m ->
            (query.sinceMillis == null || m.timestamp >= query.sinceMillis) &&
                (query.format == null || m.format == query.format) &&
                (query.deckId == null || m.deckId == query.deckId) &&
                (query.opponentClass == null || m.opponentClass == query.opponentClass) &&
                when (query.result) {
                    ResultFilter.ALL -> true
                    ResultFilter.WINS -> m.result == MatchResult.WIN
                    ResultFilter.LOSSES -> m.result == MatchResult.LOSS
                } &&
                (terms.isEmpty() || searchText(m, db).let { haystack -> terms.all { it in haystack } })
        }.sortedByDescending { it.timestamp }
    }

    private fun searchText(m: MatchRecord, db: CardDatabase?): String = buildString {
        append(normalizeForSearch(m.deckName)).append(' ')
        append(normalizeForSearch(m.opponentArchetype.orEmpty())).append(' ')
        append(normalizeForSearch(m.playerClass.displayName)).append(' ')
        append(normalizeForSearch(m.opponentClass.displayName)).append(' ')
        append(normalizeForSearch(m.result.displayName)).append(' ')
        append(normalizeForSearch(m.notes)).append(' ')
        if (db != null) m.opponentCards.forEach { id -> db.byDbfId(id)?.let { append(normalizeForSearch(it.name)).append(' ') } }
    }

    /** Gruppiert nach Kalendertag (neueste Tage zuerst). */
    fun groupByDay(matches: List<MatchRecord>, zone: ZoneId = ZoneId.systemDefault()): List<MatchDay> =
        matches.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .map { (date, list) -> MatchDay(date, list.sortedByDescending { it.timestamp }) }
            .sortedByDescending { it.date }

    fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "Heute"
        today.minusDays(1) -> "Gestern"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMANY))
    }

    /** Verlauf Zug für Zug. */
    fun turns(match: MatchRecord): List<TurnSummary> =
        match.timeline.groupBy { it.turn }.toSortedMap().map { (turn, events) ->
            TurnSummary(
                turn = turn,
                drawn = events.filter { it.type == TimelineType.DRAW }.mapNotNull { it.dbfId },
                returned = events.filter { it.type == TimelineType.RETURN }.mapNotNull { it.dbfId },
                extraDrawn = events.filter { it.type == TimelineType.EXTRA_DRAW }.mapNotNull { it.cardId },
                opponentPlayed = events.filter { it.type == TimelineType.OPPONENT_PLAY }.mapNotNull { it.dbfId },
            )
        }

    /** Eigene Karten, die am Ende gezogen (und nicht zurückgemischt) waren, in Zugreihenfolge. */
    fun drawnCards(match: MatchRecord): List<Int> {
        val result = mutableListOf<Int>()
        for (event in match.timeline) {
            when (event.type) {
                TimelineType.DRAW -> event.dbfId?.let { result += it }
                TimelineType.RETURN -> event.dbfId?.let { id -> result.lastIndexOf(id).takeIf { it >= 0 }?.let { result.removeAt(it) } }
                else -> Unit
            }
        }
        return result
    }
}

object MatchExporter {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.GERMANY)

    fun toCsv(matches: List<MatchRecord>, db: CardDatabase, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine("Datum;Ergebnis;Deck;Klasse;Gegnerklasse;Gegner-Archetyp;Format;Am Zug;Züge;Dauer (s);Quelle;Gegnerkarten;Notizen")
        for (m in matches.sortedBy { it.timestamp }) {
            val fields = listOf(
                Instant.ofEpochMilli(m.timestamp).atZone(zone).format(dateFormat),
                m.result.displayName,
                m.deckName,
                m.playerClass.displayName,
                m.opponentClass.displayName,
                m.opponentArchetype.orEmpty(),
                m.format.displayName,
                when (m.wentFirst) {
                    true -> "ja"
                    false -> "nein (Münze)"
                    null -> ""
                },
                m.turns?.toString().orEmpty(),
                m.durationSeconds?.toString().orEmpty(),
                m.source.displayName,
                m.opponentCards.joinToString(", ") { db.byDbfId(it)?.name ?: it.toString() },
                m.notes,
            )
            appendLine(fields.joinToString(";") { escape(it) })
        }
    }

    /** Lesbare Zusammenfassung zum Teilen. */
    fun summaryText(m: MatchRecord, db: CardDatabase, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine("${m.result.displayName}: ${m.deckName.ifBlank { m.playerClass.displayName }} vs. ${m.opponentArchetype ?: m.opponentClass.displayName}")
        appendLine(Instant.ofEpochMilli(m.timestamp).atZone(zone).format(dateFormat) + " · " + m.format.displayName)
        val details = listOfNotNull(
            m.wentFirst?.let { if (it) "am Zug" else "mit Münze" },
            m.turns?.let { "$it Züge" },
            m.durationSeconds?.let { "%d:%02d min".format(it / 60, it % 60) },
        )
        if (details.isNotEmpty()) appendLine(details.joinToString(" · "))
        if (m.opponentCards.isNotEmpty()) {
            appendLine("Gegner spielte: " + m.opponentCards.joinToString(", ") { db.byDbfId(it)?.name ?: it.toString() })
        }
        if (m.notes.isNotBlank()) appendLine("Notiz: ${m.notes}")
    }.trimEnd()

    private fun escape(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
