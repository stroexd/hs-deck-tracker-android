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

enum class ResultFilter { ALL, WINS, LOSSES }

data class MatchQuery(
    val text: String = "",
    val result: ResultFilter = ResultFilter.ALL,
    val opponentClass: HsClass? = null,
    val deckId: String? = null,
    val format: GameFormat? = null,
    val sinceMillis: Long? = null,
)

data class MatchDay(val date: LocalDate, val matches: List<MatchRecord>) {
    val winRate: WinRate get() = StatsCalculator.overall(matches)
}

data class TurnSummary(
    val turn: Int,
    val drawn: List<Int>,
    val returned: List<Int>,
    val extraDrawn: List<String>,
    val opponentPlayed: List<Int>,
)

object MatchHistory {
    /** [labels] adds localized words (class, result names) to the text search. */
    fun filter(
        matches: List<MatchRecord>,
        query: MatchQuery,
        db: CardDatabase? = null,
        labels: (MatchRecord) -> String = { "" },
    ): List<MatchRecord> {
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
                (terms.isEmpty() || searchText(m, db, labels).let { haystack -> terms.all { it in haystack } })
        }.sortedByDescending { it.timestamp }
    }

    private fun searchText(m: MatchRecord, db: CardDatabase?, labels: (MatchRecord) -> String): String = buildString {
        append(normalizeForSearch(m.deckName)).append(' ')
        append(normalizeForSearch(m.opponentArchetype.orEmpty())).append(' ')
        append(normalizeForSearch(labels(m))).append(' ')
        append(normalizeForSearch(m.notes)).append(' ')
        if (db != null) m.opponentCards.forEach { id -> db.byDbfId(id)?.let { append(normalizeForSearch(it.name)).append(' ') } }
    }

    fun groupByDay(matches: List<MatchRecord>, zone: ZoneId = ZoneId.systemDefault()): List<MatchDay> =
        matches.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .map { (date, list) -> MatchDay(date, list.sortedByDescending { it.timestamp }) }
            .sortedByDescending { it.date }

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

/** CSV for spreadsheets and other tools – deliberately language independent. */
object MatchExporter {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun toCsv(matches: List<MatchRecord>, db: CardDatabase, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine("date;result;deck;class;opponent_class;opponent_archetype;format;went_first;turns;duration_s;source;opponent_cards;notes")
        for (m in matches.sortedBy { it.timestamp }) {
            val fields = listOf(
                Instant.ofEpochMilli(m.timestamp).atZone(zone).format(dateFormat),
                m.result.name.lowercase(),
                m.deckName,
                m.playerClass.name.lowercase(),
                m.opponentClass.name.lowercase(),
                m.opponentArchetype.orEmpty(),
                m.format.name.lowercase(),
                m.wentFirst?.toString().orEmpty(),
                m.turns?.toString().orEmpty(),
                m.durationSeconds?.toString().orEmpty(),
                m.source.name.lowercase(),
                m.opponentCards.joinToString(", ") { db.byDbfId(it)?.name ?: it.toString() },
                m.notes,
            )
            appendLine(fields.joinToString(";") { escape(it) })
        }
    }

    private fun escape(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
