package com.stroexd.hsdecktracker.core.tracker

import com.stroexd.hsdecktracker.core.deck.Deck

/**
 * Erkennt, welches Deck gespielt wird, anhand der bisher gesehenen eigenen Karten.
 * Jede gesehene Karte ist eine Liste möglicher dbfIds (verschiedene Drucke desselben Namens).
 */
object DeckIdentifier {

    data class Score(val deck: Deck, val matched: Int, val unmatched: Int)

    fun score(deck: Deck, seen: List<List<Int>>): Score {
        val remaining = deck.cards.toMutableMap()
        var matched = 0
        var unmatched = 0
        for (candidates in seen) {
            val id = candidates.firstOrNull { (remaining[it] ?: 0) > 0 }
            if (id != null) {
                remaining[id] = remaining.getValue(id) - 1
                matched++
            } else {
                unmatched++
            }
        }
        return Score(deck, matched, unmatched)
    }

    /**
     * Bestes Deck unter [candidates] (Reihenfolge = Priorität bei Gleichstand, z. B. eigene Decks vor Meta-Decks),
     * wenn mindestens [minMatches] Karten und mindestens 60 % der gesehenen Karten passen.
     */
    fun identify(seen: List<List<Int>>, candidates: List<Deck>, minMatches: Int = 2): Deck? {
        if (seen.size < minMatches) return null
        val best = candidates
            .map { score(it, seen) }
            .filter { it.matched >= minMatches && it.matched * 10 >= seen.size * 6 }
            .sortedWith(compareByDescending<Score> { it.matched }.thenBy { it.unmatched })
            .firstOrNull()
        return best?.deck
    }
}
