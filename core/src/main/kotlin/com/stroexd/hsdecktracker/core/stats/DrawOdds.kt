package com.stroexd.hsdecktracker.core.stats

/** Hypergeometrische Wahrscheinlichkeiten für das Ziehen von Karten. */
object DrawOdds {

    /** Wahrscheinlichkeit, dass keine der [copies] Karten unter [draws] gezogenen aus [deckSize] ist. */
    fun missProbability(deckSize: Int, copies: Int, draws: Int): Double {
        if (copies <= 0 || draws <= 0) return 1.0
        if (draws > deckSize - copies) return 0.0
        var p = 1.0
        for (i in 0 until draws) {
            p *= (deckSize - copies - i).toDouble() / (deckSize - i)
        }
        return p
    }

    /** Wahrscheinlichkeit, mindestens eine der [copies] Karten in [draws] Zügen zu ziehen. */
    fun atLeastOne(deckSize: Int, copies: Int, draws: Int): Double =
        if (deckSize <= 0) 0.0 else 1.0 - missProbability(deckSize, copies, draws)

    /** Chance, dass die nächste gezogene Karte eine der [copies] ist. */
    fun nextDraw(deckSize: Int, copies: Int): Double =
        if (deckSize <= 0 || copies <= 0) 0.0 else copies.toDouble() / deckSize

    /**
     * Chance, eine Karte bis zu einem Zug auf der Hand zu haben, wenn man im Mulligan
     * alle Karten austauscht, die nicht die gesuchte sind.
     *
     * @param handSize 3 am Zug, 4 mit Münze
     * @param turnDraws Anzahl Karten, die nach dem Mulligan noch gezogen werden (Zug 1 = 1)
     */
    fun withMulligan(deckSize: Int, copies: Int, handSize: Int, turnDraws: Int): Double {
        if (copies <= 0 || deckSize <= 0) return 0.0
        val missStart = missProbability(deckSize, copies, handSize)
        // Ersatzkarten werden gezogen, bevor die abgelegten zurück ins Deck gemischt werden.
        val missMulligan = missProbability(deckSize - handSize, copies, handSize)
        val missDraws = missProbability(deckSize - handSize, copies, turnDraws)
        return 1.0 - missStart * missMulligan * missDraws
    }
}
