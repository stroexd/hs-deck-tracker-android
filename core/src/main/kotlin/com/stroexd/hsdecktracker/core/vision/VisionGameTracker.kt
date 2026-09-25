package com.stroexd.hsdecktracker.core.vision

import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.tracker.GameEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bildschirmbereiche im Querformat (normiert, links oben = 0/0), vermessen an Aufnahmen des
 * Hearthstone-Clients auf einem 20:9-Handy. x-Werte werden vorher mit [boardX] auf dieses
 * Seitenverhältnis umgerechnet: Das Spielfeld hat eine feste Höhe und ist horizontal zentriert.
 */
object ScreenRegions {
    const val REFERENCE_ASPECT = 2.22f

    fun boardX(x: Float, aspect: Float): Float = if (aspect <= 0f) x else 0.5f + (x - 0.5f) * aspect / REFERENCE_ASPECT

    fun toBoard(line: OcrLine, aspect: Float): OcrLine =
        if (aspect <= 0f) line else line.copy(left = boardX(line.left, aspect), right = boardX(line.right, aspect))

    /** Eigene Hand am unteren Rand (Fächer bzw. vergrößerte Handreihe beim Antippen). */
    fun isHand(l: OcrLine): Boolean = l.centerY >= 0.84f

    /** Namen der vergrößerten Handreihe (alle Handkarten nebeneinander lesbar). */
    fun isHandZoomRow(l: OcrLine): Boolean = l.centerY in 0.84f..0.95f && l.centerX in 0.15f..0.85f

    /** Gezogene Karte: wird rechts neben der Mitte vergrößert eingeblendet, bevor sie auf die Hand geht. */
    fun isDrawPopup(l: OcrLine): Boolean = l.centerX >= 0.62f && l.centerY in 0.55f..0.75f

    /** Vergrößerte Karte, die der Gegner gerade spielt (links). */
    fun isOpponentPopup(l: OcrLine): Boolean = l.centerX in 0.1f..0.48f && l.centerY in 0.15f..0.82f

    /** Karten der Starthand (Mulligan), auch beim Austeilen und Austauschen. */
    fun isMulliganCard(l: OcrLine): Boolean = l.centerX in 0.1f..0.97f && l.centerY in 0.25f..0.84f

    /** Zug-Knopf am rechten Rand des Spielfelds. */
    fun isTurnButton(l: OcrLine): Boolean = l.centerX in 0.72f..0.9f && l.centerY in 0.42f..0.58f && l.height <= 0.05f

    /** Großer Hinweis in der Bildschirmmitte („Du bist am Zug“). */
    fun isBanner(l: OcrLine): Boolean = l.centerX in 0.35f..0.65f && l.centerY in 0.38f..0.62f

    /** Namensschild mit Klasse (Versus-Bildschirm, Ecken unten). Links = Gegner. */
    fun isPlayerLabel(l: OcrLine): Boolean = l.centerY >= 0.6f

    /** Überschrift oben in der Mitte (Starthand, Auswahl). */
    fun isHeader(l: OcrLine): Boolean = l.centerY < 0.25f && l.centerX in 0.3f..0.7f

    /** Karte des Gegners auf dem Weg vom Ausspielen zur Anzeige links. */
    fun isOpponentCardEntering(l: OcrLine): Boolean = l.centerX >= 0.33f

    /** Kopfzeile (Name des Gegners, Uhrzeit) – dort stehen keine Karten. */
    fun isTopBar(l: OcrLine): Boolean = l.centerY < 0.08f

    /** Großer Sieg-/Niederlage-Schriftzug. */
    fun isResult(l: OcrLine): Boolean = l.centerX in 0.3f..0.7f && l.centerY in 0.3f..0.8f && l.height >= 0.03f
}

/**
 * Erkennt den Spielverlauf aus OCR-Ergebnissen des Bildschirms (ohne Zugriff auf Spieldateien):
 *
 * - **Spielstart**: Versus-Bildschirm (Klassen beider Spieler) oder Mulligan („Bestätigen“ + Karten)
 * - **Klassen**: Namensschilder links (Gegner) und rechts (eigene Klasse)
 * - **Starthand**: alle Karten des Mulligans, ausgetauschte Karten gehen zurück ins Deck
 * - **Eigene Karten**: vergrößert eingeblendete gezogene Karte bzw. die angetippte Handreihe
 * - **Gegnerkarten**: im gegnerischen Zug links vergrößert eingeblendete Karte
 * - **Züge**: Beschriftung des Zug-Knopfs, **Münze** → mit Münze
 * - **Ergebnis**: „Sieg“ / „Niederlage“ – nur für echte Partien (nicht für kurze Fehlerkennungen)
 *
 * Als Kartenname zählt nur eine ganze Textzeile, die nicht Teil eines Kartentexts ist – Namen, die im
 * Effekttext anderer Karten vorkommen („Herold: Sinestra“), werden so nicht als Karte gewertet.
 */
class VisionGameTracker(
    private val index: CardNameIndex,
    /** dbfIds, die im Kontext plausibel sind (erkanntes Deck + Meta-Karten). Verbessert die Trefferquote. */
    private val contextProvider: () -> Set<Int> = { emptySet() },
    private val opponentRepeatMillis: Long = 2_500,
    private val popupRepeatMillis: Long = 4_000,
    private val idleTimeoutMillis: Long = 4 * 60_000,
    private val minGameMillis: Long = 90_000,
) {
    enum class Phase { IDLE, MULLIGAN, PLAYING, ENDED }

    var phase: Phase = Phase.IDLE
        private set

    /** Zuletzt erkannte Kartennamen (für Diagnose/Anzeige). */
    var lastRecognized: List<String> = emptyList()
        private set

    /** Begründungen der Entscheidungen (für den Diagnose-Modus), z. B. „gezogen: … (Hand)“. */
    var decisionLog: ((String) -> Unit)? = null

    private fun log(message: () -> String) {
        decisionLog?.invoke(message())
    }

    private fun describe(card: CardLine): String =
        "${card.name} „${card.line.text}“ @%.2f/%.2f".format(java.util.Locale.ROOT, card.line.centerX, card.line.centerY)

    /** Erkannter Kartenname; [key] ist für alle Drucke und Sprachen einer Karte gleich. */
    private class CardLine(val key: String, val name: String, val line: OcrLine)

    private class FrameInfo(
        val mulliganSignal: Boolean,
        val turn: Boolean?,
        val yourTurnBanner: Boolean,
        val result: MatchResult?,
        val coin: Boolean,
        val choice: Boolean,
        val friendlyClass: HsClass?,
        val opponentClass: HsClass?,
        val cards: List<CardLine>,
    ) {
        val isGameIntro: Boolean
            get() = (friendlyClass != null && opponentClass != null) ||
                (mulliganSignal && (friendlyClass != null || opponentClass != null))
    }

    private val ids = HashMap<String, List<Int>>()
    private var gameStartedAt = 0L
    private var lastActivity = 0L
    private var endedAt = 0L
    private val buttonHistory = ArrayDeque<Boolean>()

    private var friendlyClass: HsClass? = null
    private var opponentClass: HsClass? = null

    // Mulligan
    private val initialHand = HashMap<String, Int>()
    private val keptHand = HashMap<String, Int>()
    private var mulliganSignalSeen = false
    private var mulliganConfirmed = false
    private var framesWithoutSignal = 0
    private var framesWithoutRow = 0
    private var keptFrames = 0
    private var lastMulliganSignal = 0L

    // Züge
    private var turnState: Boolean? = null
    private var pendingTurn: Boolean? = null
    private var ownTurnHoldUntil = 0L
    private var turnCounter = 0
    private var turnOrderKnown = false
    private var coinSeen = false

    // Eigene Karten: gezählte Exemplare, davon ausgespielt
    private val drawn = HashMap<String, Int>()
    private val played = HashMap<String, Int>()
    private val handMisses = HashMap<String, Pair<Int, Long>>()
    private val popupLastSeen = HashMap<String, Long>()
    private val lastChoice = HashMap<String, Long>()

    private val opponentLastSeen = HashMap<String, Long>()
    private val opponentSettled = HashMap<String, Boolean>()

    private var resultStreak = 0
    private var lastResult: MatchResult? = null

    fun onFrame(frame: OcrFrame): List<GameEvent> {
        val f = classify(frame)
        val now = frame.timestamp
        val events = mutableListOf<GameEvent>()
        buttonHistory.addLast(f.turn != null)
        if (buttonHistory.size > BUTTON_WINDOW) buttonHistory.removeFirst()

        // Spielende
        if (phase == Phase.PLAYING || phase == Phase.MULLIGAN) {
            if (f.result != null) {
                resultStreak = if (f.result == lastResult) resultStreak + 1 else 1
                lastResult = f.result
                if (resultStreak >= 2) {
                    // Kurze „Partien“ sind Fehlerkennungen – nicht in die Match-History schreiben
                    if (phase == Phase.PLAYING && (turnCounter >= 2 || now - gameStartedAt >= minGameMillis)) {
                        events += GameEvent.GameEnded(f.result)
                    }
                    phase = Phase.ENDED
                    endedAt = now
                    return events
                }
            } else {
                resultStreak = 0
                lastResult = null
            }
        }

        when (phase) {
            Phase.IDLE, Phase.ENDED -> {
                val sinceEnd = now - endedAt
                when {
                    f.result != null || (phase == Phase.ENDED && sinceEnd < RESTART_COOLDOWN_MILLIS) -> Unit
                    f.isGameIntro || (f.mulliganSignal && mulliganCards(f).size >= 2) -> {
                        startGame(events, now)
                        phase = Phase.MULLIGAN
                        onMulliganFrame(f, now, events)
                    }
                    (phase == Phase.IDLE || sinceEnd > MID_GAME_AFTER_END_MILLIS) &&
                        buttonHistory.count { it } >= MID_GAME_FRAMES -> {
                        // Erkennung während einer laufenden Partie gestartet
                        startGame(events, now)
                        phase = Phase.PLAYING
                        turnOrderKnown = true
                        onPlayingFrame(f, now, events)
                    }
                }
            }
            Phase.MULLIGAN -> onMulliganFrame(f, now, events)
            Phase.PLAYING -> onPlayingFrame(f, now, events)
        }
        return events
    }

    // ------------------------------------------------------------------ Auswertung eines Bildes

    private fun classify(frame: OcrFrame): FrameInfo {
        val lines = frame.lines.map { ScreenRegions.toBoard(it, frame.aspect) }
        var mulliganSignal = false
        var turn: Boolean? = null
        var banner = false
        var result: MatchResult? = null
        var coin = false
        var choiceHeader = false
        var friendly: HsClass? = null
        var opponent: HsClass? = null
        val cards = mutableListOf<CardLine>()
        val labels = mutableListOf<OcrLine>()
        val nameCandidates = mutableListOf<OcrLine>()
        for (line in lines) {
            val normalized = CardNameIndex.normalize(line.text)
            if (normalized.none { it.isLetter() }) continue
            if (UiKeywords.matches(normalized, UiKeywords.confirm) || UiKeywords.matches(normalized, UiKeywords.mulligan)) {
                mulliganSignal = true
                continue
            }
            // Überschrift einer Auswahl („Choose One“) – nicht der Kartentext „Entdeckt …“
            if (ScreenRegions.isHeader(line) && normalized in UiKeywords.choice) {
                choiceHeader = true
                continue
            }
            if (ScreenRegions.isTurnButton(line)) {
                val state = UiKeywords.turnButton(normalized)
                if (state != null) {
                    turn = state
                    continue
                }
            }
            if (ScreenRegions.isBanner(line) && UiKeywords.isYourTurnBanner(normalized)) {
                banner = true
                continue
            }
            if (ScreenRegions.isResult(line)) {
                val r = UiKeywords.result(normalized)
                if (r != null) {
                    result = r
                    continue
                }
            }
            if (normalized in UiKeywords.coin || UiKeywords.matches(normalized, UiKeywords.extraCard)) {
                // Die Münze des Gegners erscheint links – nur die eigene zählt
                if (line.centerX >= 0.5f || ScreenRegions.isHand(line)) coin = true
                continue
            }
            if (ScreenRegions.isPlayerLabel(line)) {
                val cls = UiKeywords.heroClass(normalized)
                if (cls != null) {
                    if (line.centerX < 0.5f) opponent = cls else friendly = cls
                    labels += line
                    continue
                }
            }
            // Sätze (Kartentext, Sprechblasen) enden mit Punkt – Kartennamen nie
            if (ScreenRegions.isTopBar(line) || line.text.trimEnd().endsWith('.')) continue
            nameCandidates += line
        }
        val preferred by lazy(LazyThreadSafetyMode.NONE) { contextProvider() }
        for (line in nameCandidates) {
            if (isTextContinuation(line, lines)) continue
            // Heldenname über der Klasse auf dem Versus-Bildschirm („Broxigar“ ist auch eine Karte)
            if (labels.any { isDirectlyAbove(line, it) }) continue
            val match = index.match(line.text, preferred) ?: continue
            val key = match.dbfIds.minOrNull()?.toString() ?: continue
            ids[key] = match.dbfIds
            cards += CardLine(key, match.key, line)
        }
        lastRecognized = cards.map { it.name }
        val outsideHand = cards.filter { !ScreenRegions.isHand(it.line) }
        return FrameInfo(
            mulliganSignal = mulliganSignal,
            turn = if (banner) true else turn,
            yourTurnBanner = banner,
            result = result,
            coin = coin,
            choice = choiceHeader || isCardRow(outsideHand),
            friendlyClass = friendly,
            opponentClass = opponent,
            cards = cards,
        )
    }

    private fun isDirectlyAbove(line: OcrLine, below: OcrLine): Boolean =
        below.top - line.bottom in -0.01f..(1.5f * max(line.height, below.height)) &&
            min(line.right, below.right) > max(line.left, below.left)

    /**
     * Zeile gehört zu einem Kartentext: Direkt darüber steht (in derselben Spalte) weiterer Text.
     * Kartennamen stehen dagegen frei auf dem Namensbanner.
     */
    private fun isTextContinuation(line: OcrLine, lines: List<OcrLine>): Boolean {
        val h = max(line.height, 0.01f)
        return lines.any { other ->
            other !== line &&
                other.bottom <= line.top + 0.5f * h &&
                other.bottom >= line.top - 1.2f * h &&
                min(other.right, line.right) > max(other.left, line.left) &&
                other.text.count { it.isLetter() } >= 3
        }
    }

    /** Mehrere vergrößerte Karten nebeneinander: Auswahl („Entdecken“, „Wählt aus“) oder Mulligan. */
    private fun isCardRow(cards: List<CardLine>): Boolean {
        for (i in cards.indices) {
            for (j in i + 1 until cards.size) {
                val a = cards[i].line
                val b = cards[j].line
                if (abs(a.centerY - b.centerY) <= 0.04f && abs(a.centerX - b.centerX) >= 0.12f) return true
            }
        }
        return false
    }

    private fun mulliganCards(f: FrameInfo): List<CardLine> =
        f.cards.filter { !ScreenRegions.isHand(it.line) && ScreenRegions.isMulliganCard(it.line) }

    // ------------------------------------------------------------------ Spielstart & Mulligan

    private fun startGame(events: MutableList<GameEvent>, now: Long) {
        events += GameEvent.GameStarted
        gameStartedAt = now
        lastActivity = now
        friendlyClass = null
        opponentClass = null
        initialHand.clear()
        keptHand.clear()
        mulliganSignalSeen = false
        mulliganConfirmed = false
        framesWithoutSignal = 0
        framesWithoutRow = 0
        keptFrames = 0
        lastMulliganSignal = now
        turnState = null
        pendingTurn = null
        turnCounter = 0
        turnOrderKnown = false
        coinSeen = false
        drawn.clear()
        played.clear()
        handMisses.clear()
        popupLastSeen.clear()
        lastChoice.clear()
        opponentLastSeen.clear()
        opponentSettled.clear()
        resultStreak = 0
        lastResult = null
    }

    private fun updateClasses(f: FrameInfo, events: MutableList<GameEvent>) {
        f.opponentClass?.takeIf { it != opponentClass }?.let {
            opponentClass = it
            events += GameEvent.ClassDetected(friendly = false, hsClass = it)
        }
        f.friendlyClass?.takeIf { it != friendlyClass }?.let {
            friendlyClass = it
            events += GameEvent.ClassDetected(friendly = true, hsClass = it)
        }
    }

    private fun onMulliganFrame(f: FrameInfo, now: Long, events: MutableList<GameEvent>) {
        updateClasses(f, events)
        if (f.coin) coinSeen = true
        f.turn?.let { turnState = it }
        val counts = mulliganCards(f).groupingBy { it.key }.eachCount()

        if (f.mulliganSignal) {
            mulliganSignalSeen = true
            lastMulliganSignal = now
            framesWithoutSignal = 0
        } else {
            framesWithoutSignal++
        }
        // „Bestätigen“ verschwunden → ab jetzt liegen nur noch behaltene und neue Karten in der Reihe
        if (mulliganSignalSeen && framesWithoutSignal >= 2) mulliganConfirmed = true

        if (!mulliganConfirmed) {
            counts.forEach { (key, count) -> initialHand.merge(key, count, ::maxOf) }
        } else if (counts.isNotEmpty()) {
            counts.forEach { (key, count) -> keptHand.merge(key, count, ::maxOf) }
            keptFrames++
        }
        framesWithoutRow = if (counts.isEmpty()) framesWithoutRow + 1 else 0
        if (counts.isNotEmpty() || f.mulliganSignal) lastActivity = now

        val done = f.yourTurnBanner ||
            (mulliganConfirmed && framesWithoutRow >= 2) ||
            (mulliganConfirmed && now - lastMulliganSignal > MULLIGAN_TIMEOUT_MILLIS) ||
            (!mulliganSignalSeen && now - gameStartedAt > MULLIGAN_TIMEOUT_MILLIS)
        if (done) {
            finishMulligan(events)
            phase = Phase.PLAYING
            onPlayingFrame(f, now, events)
        }
    }

    private fun finishMulligan(events: MutableList<GameEvent>) {
        log { "Mulligan: Starthand $initialHand, behalten $keptHand ($keptFrames Bilder)" }
        // Behaltene Karten nur verwerten, wenn die Reihe nach dem Bestätigen gut lesbar war
        val keptReliable = keptFrames >= 2 && keptHand.isNotEmpty()
        val final = if (keptReliable) keptHand else HashMap(initialHand).apply { keptHand.forEach { (k, c) -> merge(k, c, ::maxOf) } }
        // Alle Karten der Starthand melden – auch ausgetauschte helfen bei der Deck-Erkennung
        for ((key, count) in initialHand) {
            val candidates = ids[key] ?: continue
            repeat(count) { events += GameEvent.FriendlyCardSeen(candidates) }
            drawn[key] = count
        }
        for ((key, count) in initialHand) {
            val back = count - (final[key] ?: 0)
            if (back <= 0) continue
            val candidates = ids[key] ?: continue
            repeat(back) { events += GameEvent.FriendlyCardMulliganed(candidates) }
            drawn[key] = count - back
        }
        for ((key, count) in final) {
            val extra = count - (initialHand[key] ?: 0)
            if (extra <= 0) continue
            val candidates = ids[key] ?: continue
            repeat(extra) { events += GameEvent.FriendlyCardSeen(candidates) }
            drawn[key] = count
        }
        if (coinSeen && !turnOrderKnown) {
            turnOrderKnown = true
            events += GameEvent.TurnOrderDetected(friendlyWentFirst = false)
        }
    }

    // ------------------------------------------------------------------ Laufende Partie

    private fun onPlayingFrame(f: FrameInfo, now: Long, events: MutableList<GameEvent>) {
        if (turnCounter <= 2) updateClasses(f, events)
        if (f.coin && !turnOrderKnown) {
            turnOrderKnown = true
            events += GameEvent.TurnOrderDetected(friendlyWentFirst = false)
        }
        f.turn?.let { handleTurn(it, f.yourTurnBanner, now, events) }
        if (f.choice) f.cards.forEach { if (!ScreenRegions.isHand(it.line)) lastChoice[it.key] = now }
        handleOwnCards(f, now, events)
        handleOpponent(f, now, events)
        if (f.turn != null || f.cards.isNotEmpty()) lastActivity = now
        if (now - lastActivity > idleTimeoutMillis) phase = Phase.IDLE
    }

    /**
     * Zugwechsel erst nach zwei gleichen Ablesungen (einzelne Lesefehler am Knopf ignorieren).
     * Nach dem Hinweis „Du bist am Zug“ zeigt der Knopf noch kurz den gegnerischen Zug – das wird ignoriert.
     */
    private fun handleTurn(own: Boolean, banner: Boolean, now: Long, events: MutableList<GameEvent>) {
        if (banner) ownTurnHoldUntil = now + BANNER_HOLD_MILLIS
        if (!own && now < ownTurnHoldUntil && turnState == true) return
        if (own == turnState && turnCounter > 0) {
            pendingTurn = null
            return
        }
        if (!banner && pendingTurn != own) {
            pendingTurn = own
            return
        }
        pendingTurn = null
        if (turnCounter == 0) {
            turnCounter = 1
            if (!turnOrderKnown) {
                turnOrderKnown = true
                events += GameEvent.TurnOrderDetected(friendlyWentFirst = own)
            }
        } else {
            turnCounter++
        }
        turnState = own
        events += GameEvent.TurnChanged(turnCounter)
    }

    private fun inHand(key: String): Int = (drawn[key] ?: 0) - (played[key] ?: 0)

    private fun emitDraw(key: String, count: Int, fromDeck: Boolean, events: MutableList<GameEvent>) {
        val candidates = ids[key] ?: return
        repeat(count) { events += GameEvent.FriendlyCardSeen(candidates, fromDeck) }
        drawn[key] = (drawn[key] ?: 0) + count
    }

    /**
     * Eigene Karten. Gezählt wird, wie viele Exemplare einer Karte man bekommen hat:
     * - Handreihe: Sind mehr Exemplare gleichzeitig zu sehen als bisher gezählt, wird nachgezählt.
     * - Fehlt eine Karte über mehrere Sekunden in der vollständig lesbaren Handreihe, wurde sie ausgespielt.
     * - Gezogene Karte (rechts vergrößert) zählt, wenn die Karte gerade nicht auf der Hand ist.
     */
    private fun handleOwnCards(f: FrameInfo, now: Long, events: MutableList<GameEvent>) {
        val hand = f.cards.filter { ScreenRegions.isHand(it.line) }
        val handCounts = hand.groupingBy { it.key }.eachCount()
        for ((key, count) in handCounts) {
            val known = inHand(key)
            if (count > known) {
                log { "Hand: ×$count (bisher $known) ${hand.filter { it.key == key }.joinToString { describe(it) }}" }
                emitDraw(key, count - known, fromDeck = true, events)
            }
            handMisses.remove(key)
        }
        detectPlayedCards(hand, handCounts, now)
        if (turnState == false || f.choice) return
        for (card in f.cards) {
            if (!ScreenRegions.isDrawPopup(card.line)) continue
            val last = popupLastSeen.put(card.key, now)
            if (last != null && now - last < popupRepeatMillis) continue
            if (inHand(card.key) > 0) continue
            val chosen = lastChoice[card.key]?.let { now - it < CHOICE_MEMORY_MILLIS } == true
            log { "Gezogen${if (chosen) " (gewählt)" else ""}: ${describe(card)}" }
            emitDraw(card.key, 1, fromDeck = !chosen, events)
        }
    }

    /**
     * Ausgespielt: Karte fehlt in der Handreihe, obwohl die Reihe (fast) vollständig lesbar ist –
     * wiederholt und über mindestens [PLAYED_MIN_MILLIS]. Vorsichtig, damit Lesefehler nicht dazu
     * führen, dass eine Karte später doppelt gezählt wird.
     */
    private fun detectPlayedCards(hand: List<CardLine>, handCounts: Map<String, Int>, now: Long) {
        val rowSize = hand.count { ScreenRegions.isHandZoomRow(it.line) }
        val expected = drawn.keys.sumOf { inHand(it) }
        if (rowSize < 3 || rowSize < expected - 1) return
        for (key in drawn.keys.toList()) {
            val known = inHand(key)
            val count = handCounts[key] ?: 0
            if (known <= count) continue
            val (misses, since) = handMisses[key] ?: (0 to now)
            if (misses + 1 >= HAND_MISSES_FOR_PLAYED && now - since >= PLAYED_MIN_MILLIS) {
                log { "Ausgespielt: ${key} (fehlt in der Handreihe)" }
                played[key] = (played[key] ?: 0) + known - count
                handMisses.remove(key)
            } else {
                handMisses[key] = misses + 1 to since
            }
        }
    }

    private fun handleOpponent(f: FrameInfo, now: Long, events: MutableList<GameEvent>) {
        if (turnState != false || f.choice) return
        // Sieht sich der Spieler gerade die eigene Hand an, können links auch verwandte Karten
        // (Tooltips) erscheinen – dann zählt nur eine gerade hereinfliegende Karte.
        val inspectingHand = f.cards.count { ScreenRegions.isHandZoomRow(it.line) } >= 2
        for (card in f.cards) {
            if (!ScreenRegions.isOpponentPopup(card.line)) continue
            val entering = ScreenRegions.isOpponentCardEntering(card.line)
            if (inspectingHand && !entering) continue
            val last = opponentLastSeen.put(card.key, now)
            val wasSettled = opponentSettled.put(card.key, !entering) == true
            // Neu: länger nicht gesehen – oder dieselbe Karte fliegt erneut herein (zweites Exemplar)
            if (last == null || now - last > opponentRepeatMillis || (entering && wasSettled)) {
                log { "Gegner: ${describe(card)}" }
                ids[card.key]?.let { events += GameEvent.OpponentCardSeen(it) }
            }
        }
    }

    companion object {
        private const val BUTTON_WINDOW = 8
        private const val MID_GAME_FRAMES = 6
        private const val HAND_MISSES_FOR_PLAYED = 4
        private const val PLAYED_MIN_MILLIS = 2_000L
        private const val RESTART_COOLDOWN_MILLIS = 15_000L
        private const val MID_GAME_AFTER_END_MILLIS = 120_000L
        private const val MULLIGAN_TIMEOUT_MILLIS = 60_000L
        private const val CHOICE_MEMORY_MILLIS = 6_000L
        private const val BANNER_HOLD_MILLIS = 3_000L

        /** Spielt aufgezeichnete Bilder erneut ab (für Tests und Diagnose). */
        fun replay(index: CardNameIndex, frames: List<OcrFrame>): List<GameEvent> {
            val tracker = VisionGameTracker(index)
            return frames.flatMap { tracker.onFrame(it) }
        }
    }
}
