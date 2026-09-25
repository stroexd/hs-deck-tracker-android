package com.stroexd.hsdecktracker.core.stats

object DrawOdds {
    fun missProbability(deckSize: Int, copies: Int, draws: Int): Double {
        if (copies <= 0 || draws <= 0) return 1.0
        if (draws > deckSize - copies) return 0.0
        var p = 1.0
        for (i in 0 until draws) {
            p *= (deckSize - copies - i).toDouble() / (deckSize - i)
        }
        return p
    }

    fun atLeastOne(deckSize: Int, copies: Int, draws: Int): Double =
        if (deckSize <= 0) 0.0 else 1.0 - missProbability(deckSize, copies, draws)

    fun nextDraw(deckSize: Int, copies: Int): Double =
        if (deckSize <= 0 || copies <= 0) 0.0 else copies.toDouble() / deckSize

    fun withMulligan(deckSize: Int, copies: Int, handSize: Int, turnDraws: Int): Double {
        if (copies <= 0 || deckSize <= 0) return 0.0
        val missStart = missProbability(deckSize, copies, handSize)
        val missMulligan = missProbability(deckSize - handSize, copies, handSize)
        val missDraws = missProbability(deckSize - handSize, copies, turnDraws)
        return 1.0 - missStart * missMulligan * missDraws
    }
}
