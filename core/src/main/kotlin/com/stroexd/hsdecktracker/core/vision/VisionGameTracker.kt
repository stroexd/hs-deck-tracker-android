package com.stroexd.hsdecktracker.core.vision

import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.tracker.GameEvent

/** Bildschirmbereiche im Querformat (normiert), abgeleitet vom Layout des Hearthstone-Clients. */
object ScreenRegions {
    /** Eigene Hand unten rechts. */
    fun isHand(l: OcrLine): Boolean = l.centerY >= 0.78f && l.centerX >= 0.30f

    /** Vergrößerte eigene Karte (z. B. beim Ziehen) rechts. */
    fun isOwnPopup(l: OcrLine): Boolean = l.centerX >= 0.58f && l.centerY in 0.12f..0.78f

    /** Vergrößerte Karte, die der Gegner gerade spielt (links). */
    fun isOpponentPopup(l: OcrLine): Boolean = l.centerX <= 0.42f && l.centerY in 0.08f..0.76f

    /** Mitte (Mulligan-Karten). */
    fun isCenter(l: OcrLine): Boolean = l.centerX in 0.12f..0.88f && l.centerY in 0.08f..0.92f

    /** Großer Sieg-/Niederlage-Schriftzug. */
    fun isResult(l: OcrLine): Boolean = l.centerX in 0.2f..0.8f && l.centerY in 0.12f..0.88f && l.height >= 0.035f
}

/**
 * Erkennt den Spielverlauf aus OCR-Ergebnissen des Bildschirms (ohne Zugriff auf Spieldateien):
 *
 * - **Spielstart**: Mulligan-Bildschirm („Bestätigen“ + Karten in der Mitte) oder Zug-Knopf
 * - **Eigene Karten**: Kartennamen in der Hand bzw. vergrößert rechts → gezogen
 * - **Gegnerkarten**: vergrößert links eingeblendete Karte → gespielt
 * - **Züge**: „Zug beenden“ / „Gegnerischer Zug“, **Münze** → mit Münze
 * - **Ergebnis**: „Sieg“ / „Niederlage“
 *
 * Liefert dieselben [GameEvent]s wie der Log-Parser, sodass der Tracker beides gleich verarbeitet.
 */
class VisionGameTracker(
    private val index: CardNameIndex,
    /** dbfIds, die im Kontext plausibel sind (erkanntes Deck + Meta-Karten). Verbessert die Trefferquote. */
    private val contextProvider: () -> Set<Int> = { emptySet() },
    private val missFrames: Int = 4,
    private val opponentRepeatMillis: Long = 2_500,
    private val idleTimeoutMillis: Long = 4 * 60_000,
    private val restartAfterEndMillis: Long = 45_000,
) {
    enum class Phase { IDLE, MULLIGAN, PLAYING, ENDED }

    var phase: Phase = Phase.IDLE
        private set

    /** Zuletzt erkannte Kartennamen (für Diagnose/Anzeige). */
    var lastRecognized: List<String> = emptyList()
        private set

    private val ids = HashMap<String, List<Int>>()
    private var lastActivity = 0L
    private var endedAt = 0L

    private var mulliganHand: Map<String, Int> = emptyMap()
    private var framesWithoutConfirm = 0
    private var coinSeen = false

    private val handEstimate = LinkedHashMap<String, Int>()
    private val missStreak = HashMap<String, Int>()
    private val lowerStreak = HashMap<String, Int>()
    private val opponentLastSeen = HashMap<String, Long>()

    private var ownTurn: Boolean? = null
    private var turnCounter = 0
    private var turnOrderKnown = false
    private var resultStreak = 0
    private var lastResult: MatchResult? = null

    private data class Classified(
        val confirm: Boolean,
        val ownTurn: Boolean?,
        val result: MatchResult?,
        val coin: Boolean,
        val center: Map<String, Int>,
        val hand: Map<String, Int>,
        val ownPopup: Map<String, Int>,
        val opponent: Set<String>,
    ) {
        val own: Map<String, Int>
            get() = (hand.keys + ownPopup.keys).associateWith { maxOf(hand[it] ?: 0, ownPopup[it] ?: 0) }
    }

    fun onFrame(frame: OcrFrame): List<GameEvent> {
        val c = classify(frame)
        val now = frame.timestamp
        val events = mutableListOf<GameEvent>()

        // Spielende
        if (c.result != null && (phase == Phase.PLAYING || phase == Phase.MULLIGAN)) {
            resultStreak = if (c.result == lastResult) resultStreak + 1 else 1
            lastResult = c.result
            if (resultStreak >= 2) {
                events += GameEvent.GameEnded(c.result)
                phase = Phase.ENDED
                endedAt = now
                return events
            }
        } else if (c.result == null) {
            resultStreak = 0
            lastResult = null
        }

        when (phase) {
            Phase.IDLE, Phase.ENDED -> {
                val mayRestartFromBoard = phase == Phase.IDLE || now - endedAt > restartAfterEndMillis
                when {
                    c.result != null -> Unit
                    c.confirm && c.center.values.sum() >= 2 -> {
                        startGame(events, now)
                        phase = Phase.MULLIGAN
                        mulliganHand = c.center
                        if (c.coin) coinSeen = true
                    }
                    mayRestartFromBoard && c.ownTurn != null && (c.own.isNotEmpty() || phase == Phase.IDLE) -> {
                        // Tracker während einer laufenden Partie gestartet
                        startGame(events, now)
                        phase = Phase.PLAYING
                        handleTurn(c.ownTurn, events, announceOrder = false)
                        handleOwnCards(c, events)
                    }
                }
            }
            Phase.MULLIGAN -> {
                if (c.coin) coinSeen = true
                if (c.confirm) {
                    framesWithoutConfirm = 0
                    if (c.center.values.sum() >= 2) mulliganHand = c.center
                    lastActivity = now
                } else {
                    framesWithoutConfirm++
                    if (framesWithoutConfirm >= 2 || c.ownTurn != null) {
                        finishMulligan(events)
                        phase = Phase.PLAYING
                        c.ownTurn?.let { handleTurn(it, events, announceOrder = true) }
                        handleOwnCards(c, events)
                        lastActivity = now
                    }
                }
            }
            Phase.PLAYING -> {
                if (c.coin && !turnOrderKnown) {
                    turnOrderKnown = true
                    events += GameEvent.TurnOrderDetected(friendlyWentFirst = false)
                }
                c.ownTurn?.let { handleTurn(it, events, announceOrder = true) }
                handleOwnCards(c, events)
                handleOpponent(c, now, events)
                if (c.ownTurn != null || c.own.isNotEmpty() || c.opponent.isNotEmpty()) lastActivity = now
                if (now - lastActivity > idleTimeoutMillis) phase = Phase.IDLE
            }
        }
        return events
    }

    private fun classify(frame: OcrFrame): Classified {
        var confirm = false
        var own: Boolean? = null
        var result: MatchResult? = null
        var coin = false
        val center = LinkedHashMap<String, Int>()
        val hand = LinkedHashMap<String, Int>()
        val ownPopup = LinkedHashMap<String, Int>()
        val opponent = LinkedHashSet<String>()
        val recognized = mutableListOf<String>()
        val preferred = contextProvider()
        for (line in frame.lines) {
            val normalized = CardNameIndex.normalize(line.text)
            if (normalized.isEmpty()) continue
            when {
                UiKeywords.matches(normalized, UiKeywords.confirm) -> { confirm = true; continue }
                UiKeywords.matches(normalized, UiKeywords.enemyTurn) -> { if (own == null) own = false; continue }
                UiKeywords.matches(normalized, UiKeywords.ownTurn) -> { own = true; continue }
                ScreenRegions.isResult(line) && normalized in UiKeywords.victory -> { result = MatchResult.WIN; continue }
                ScreenRegions.isResult(line) && normalized in UiKeywords.defeat -> { result = MatchResult.LOSS; continue }
                ScreenRegions.isResult(line) && normalized in UiKeywords.tie -> { result = MatchResult.DRAW; continue }
                normalized in UiKeywords.coin -> {
                    if (ScreenRegions.isCenter(line) || ScreenRegions.isHand(line)) coin = true
                    continue
                }
            }
            for (match in index.findAll(line.text, preferred)) {
                ids[match.key] = match.dbfIds
                recognized += match.key
                if (ScreenRegions.isCenter(line)) center.merge(match.key, 1, Int::plus)
                if (ScreenRegions.isHand(line)) hand.merge(match.key, 1, Int::plus)
                if (ScreenRegions.isOwnPopup(line)) ownPopup.merge(match.key, 1, Int::plus)
                if (ScreenRegions.isOpponentPopup(line)) opponent += match.key
            }
        }
        lastRecognized = recognized
        return Classified(confirm, own, result, coin, center, hand, ownPopup, opponent)
    }

    private fun startGame(events: MutableList<GameEvent>, now: Long) {
        events += GameEvent.GameStarted
        handEstimate.clear()
        missStreak.clear()
        lowerStreak.clear()
        opponentLastSeen.clear()
        mulliganHand = emptyMap()
        framesWithoutConfirm = 0
        coinSeen = false
        ownTurn = null
        turnCounter = 0
        turnOrderKnown = false
        lastActivity = now
    }

    private fun finishMulligan(events: MutableList<GameEvent>) {
        for ((key, count) in mulliganHand) {
            val candidates = ids[key] ?: continue
            repeat(count) { events += GameEvent.FriendlyCardSeen(candidates) }
            handEstimate[key] = count
        }
        if (coinSeen && !turnOrderKnown) {
            turnOrderKnown = true
            events += GameEvent.TurnOrderDetected(friendlyWentFirst = false)
        }
    }

    private fun handleTurn(own: Boolean, events: MutableList<GameEvent>, announceOrder: Boolean) {
        if (own == ownTurn) return
        if (ownTurn == null) {
            turnCounter = 1
            if (announceOrder && !turnOrderKnown) {
                turnOrderKnown = true
                events += GameEvent.TurnOrderDetected(friendlyWentFirst = own)
            }
        } else {
            turnCounter++
        }
        ownTurn = own
        events += GameEvent.TurnChanged(turnCounter)
    }

    /**
     * Eigene Hand: Neue Exemplare werden als gezogen gemeldet. Verschwindet eine Karte über mehrere
     * gut lesbare Bilder hinweg, gilt sie als ausgespielt – taucht sie danach wieder auf, ist es ein
     * weiteres Exemplar. So führen kurze Verdeckungen nicht zu doppelt gezählten Karten.
     */
    private fun handleOwnCards(c: Classified, events: MutableList<GameEvent>) {
        val visible = c.own
        for ((key, count) in visible) {
            missStreak[key] = 0
            val estimate = handEstimate[key] ?: 0
            when {
                count > estimate -> {
                    val candidates = ids[key] ?: continue
                    repeat(count - estimate) { events += GameEvent.FriendlyCardSeen(candidates) }
                    handEstimate[key] = count
                    lowerStreak[key] = 0
                }
                count < estimate -> {
                    val streak = (lowerStreak[key] ?: 0) + 1
                    if (streak >= missFrames) {
                        handEstimate[key] = count
                        lowerStreak[key] = 0
                    } else {
                        lowerStreak[key] = streak
                    }
                }
                else -> lowerStreak[key] = 0
            }
        }
        if (visible.isEmpty()) return // Hand nicht lesbar – nichts schließen
        for (key in handEstimate.keys.filter { it !in visible }) {
            val streak = (missStreak[key] ?: 0) + 1
            if (streak >= missFrames) {
                handEstimate.remove(key)
                missStreak.remove(key)
            } else {
                missStreak[key] = streak
            }
        }
    }

    private fun handleOpponent(c: Classified, now: Long, events: MutableList<GameEvent>) {
        for (key in c.opponent) {
            if (key in c.hand || key in c.ownPopup) continue
            val last = opponentLastSeen[key]
            if (last == null || now - last > opponentRepeatMillis) {
                ids[key]?.let { events += GameEvent.OpponentCardSeen(it) }
            }
            opponentLastSeen[key] = now
        }
    }

    companion object {
        /** Spielt aufgezeichnete Bilder erneut ab (für Tests und Diagnose). */
        fun replay(index: CardNameIndex, frames: List<OcrFrame>): List<GameEvent> {
            val tracker = VisionGameTracker(index)
            return frames.flatMap { tracker.onFrame(it) }
        }
    }
}
