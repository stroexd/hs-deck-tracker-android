package com.stroexd.hsdecktracker.core.stats

import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import kotlinx.serialization.Serializable
import java.util.UUID

enum class MatchResult(val displayName: String) {
    WIN("Sieg"),
    LOSS("Niederlage"),
    DRAW("Unentschieden"),
}

enum class MatchSource { MANUAL, TRACKER, LOG }

@Serializable
data class MatchRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val deckId: String? = null,
    val deckName: String = "",
    val playerClass: HsClass = HsClass.UNKNOWN,
    val opponentClass: HsClass = HsClass.UNKNOWN,
    val result: MatchResult,
    val format: GameFormat = GameFormat.STANDARD,
    /** true = am Zug, false = mit Münze, null = unbekannt */
    val wentFirst: Boolean? = null,
    val turns: Int? = null,
    val durationSeconds: Int? = null,
    /** dbfIds der vom Gegner gespielten Karten */
    val opponentCards: List<Int> = emptyList(),
    val opponentArchetype: String? = null,
    val source: MatchSource = MatchSource.MANUAL,
    val notes: String = "",
)

data class WinRate(val wins: Int = 0, val losses: Int = 0, val draws: Int = 0) {
    val games: Int get() = wins + losses + draws

    /** Siegquote ohne Unentschieden; null, wenn noch keine Partie gewertet wurde. */
    val rate: Double? get() = if (wins + losses == 0) null else wins.toDouble() / (wins + losses)

    operator fun plus(result: MatchResult): WinRate = when (result) {
        MatchResult.WIN -> copy(wins = wins + 1)
        MatchResult.LOSS -> copy(losses = losses + 1)
        MatchResult.DRAW -> copy(draws = draws + 1)
    }

    val label: String get() = "$wins–$losses" + if (draws > 0) "–$draws" else ""
}

data class DeckWinRate(val deckId: String?, val deckName: String, val playerClass: HsClass, val winRate: WinRate)

data class Streak(val result: MatchResult, val length: Int)

data class StatsFilter(
    val sinceMillis: Long? = null,
    val format: GameFormat? = null,
    val deckId: String? = null,
)

object StatsCalculator {

    fun filter(matches: List<MatchRecord>, filter: StatsFilter): List<MatchRecord> = matches.filter { m ->
        (filter.sinceMillis == null || m.timestamp >= filter.sinceMillis) &&
            (filter.format == null || m.format == filter.format) &&
            (filter.deckId == null || m.deckId == filter.deckId)
    }

    fun overall(matches: List<MatchRecord>): WinRate = matches.fold(WinRate()) { acc, m -> acc + m.result }

    fun byOpponentClass(matches: List<MatchRecord>): Map<HsClass, WinRate> =
        matches.groupBy { it.opponentClass }
            .mapValues { (_, list) -> overall(list) }
            .toList()
            .sortedByDescending { it.second.games }
            .toMap()

    fun byPlayerClass(matches: List<MatchRecord>): Map<HsClass, WinRate> =
        matches.groupBy { it.playerClass }.mapValues { (_, list) -> overall(list) }

    fun byDeck(matches: List<MatchRecord>): List<DeckWinRate> =
        matches.groupBy { it.deckId ?: "name:${it.deckName}" }
            .map { (_, list) ->
                val latest = list.maxBy { it.timestamp }
                DeckWinRate(latest.deckId, latest.deckName.ifBlank { "Ohne Deck" }, latest.playerClass, overall(list))
            }
            .sortedByDescending { it.winRate.games }

    /** Siegquote am Zug (first) und mit Münze (second). */
    fun byTurnOrder(matches: List<MatchRecord>): Pair<WinRate, WinRate> =
        overall(matches.filter { it.wentFirst == true }) to overall(matches.filter { it.wentFirst == false })

    /** Gleitende Siegquote über die letzten [window] Partien, chronologisch. */
    fun rollingWinRate(matches: List<MatchRecord>, window: Int = 10): List<Double> {
        val sorted = matches.filter { it.result != MatchResult.DRAW }.sortedBy { it.timestamp }
        if (sorted.isEmpty()) return emptyList()
        return sorted.indices.map { i ->
            val slice = sorted.subList(maxOf(0, i - window + 1), i + 1)
            slice.count { it.result == MatchResult.WIN }.toDouble() / slice.size
        }
    }

    fun currentStreak(matches: List<MatchRecord>): Streak? {
        val sorted = matches.filter { it.result != MatchResult.DRAW }.sortedByDescending { it.timestamp }
        val first = sorted.firstOrNull() ?: return null
        return Streak(first.result, sorted.takeWhile { it.result == first.result }.size)
    }

    fun averageTurns(matches: List<MatchRecord>): Double? =
        matches.mapNotNull { it.turns }.takeIf { it.isNotEmpty() }?.average()
}
