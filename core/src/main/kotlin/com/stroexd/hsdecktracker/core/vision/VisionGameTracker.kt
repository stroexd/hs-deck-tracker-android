package com.stroexd.hsdecktracker.core.vision

import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.tracker.GameEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Normalized landscape coordinates measured on a 20:9 phone. x is first converted to that aspect
 * ratio ([boardX]): Hearthstone keeps the board height fixed and centers it horizontally.
 */
object ScreenRegions {
    const val REFERENCE_ASPECT = 2.22f

    fun boardX(x: Float, aspect: Float): Float = if (aspect <= 0f) x else 0.5f + (x - 0.5f) * aspect / REFERENCE_ASPECT

    fun toBoard(line: OcrLine, aspect: Float): OcrLine =
        if (aspect <= 0f) line else line.copy(left = boardX(line.left, aspect), right = boardX(line.right, aspect))

    fun isHand(l: OcrLine): Boolean = l.centerY >= 0.84f

    /** The enlarged hand row shown while the player touches the hand. */
    fun isHandZoomRow(l: OcrLine): Boolean = l.centerY in 0.84f..0.95f && l.centerX in 0.15f..0.85f

    fun isDrawPopup(l: OcrLine): Boolean = l.centerX >= 0.62f && l.centerY in 0.55f..0.75f

    fun isOpponentPopup(l: OcrLine): Boolean = l.centerX in 0.1f..0.48f && l.centerY in 0.15f..0.82f

    fun isMulliganCard(l: OcrLine): Boolean = l.centerX in 0.1f..0.97f && l.centerY in 0.25f..0.84f

    fun isTurnButton(l: OcrLine): Boolean = l.centerX in 0.72f..0.9f && l.centerY in 0.42f..0.58f && l.height <= 0.05f

    fun isBanner(l: OcrLine): Boolean = l.centerX in 0.35f..0.65f && l.centerY in 0.38f..0.62f

    fun isPlayerLabel(l: OcrLine): Boolean = l.centerY >= 0.6f

    fun isHeader(l: OcrLine): Boolean = l.centerY < 0.25f && l.centerX in 0.3f..0.7f

    /** An opponent's card flies in from the center before it settles on the left. */
    fun isOpponentCardEntering(l: OcrLine): Boolean = l.centerX >= 0.33f

    fun isTopBar(l: OcrLine): Boolean = l.centerY < 0.08f

    fun isResult(l: OcrLine): Boolean = l.centerX in 0.3f..0.7f && l.centerY in 0.3f..0.8f && l.height >= 0.03f
}

/**
 * Turns OCR frames of the Hearthstone screen into [GameEvent]s: game start (versus screen or
 * mulligan), classes (name plates, left = opponent), starting hand, drawn cards (enlarged on the
 * right or visible in the hand row), opponent plays (enlarged on the left during their turn),
 * turns (turn button) and the result.
 *
 * Only whole lines that stand alone count as card names, so names inside card texts
 * ("Herald Sinestra") or speech bubbles are ignored.
 */
class VisionGameTracker(
    private val index: CardNameIndex,
    private val contextProvider: () -> Set<Int> = { emptySet() },
    private val opponentRepeatMillis: Long = 2_500,
    private val popupRepeatMillis: Long = 4_000,
    private val idleTimeoutMillis: Long = 4 * 60_000,
    private val minGameMillis: Long = 90_000,
) {
    enum class Phase { IDLE, MULLIGAN, PLAYING, ENDED }

    var phase: Phase = Phase.IDLE
        private set

    var lastRecognized: List<String> = emptyList()
        private set

    var decisionLog: ((String) -> Unit)? = null

    private fun log(message: () -> String) {
        decisionLog?.invoke(message())
    }

    private fun describe(card: CardLine): String =
        "${card.name} \"${card.line.text}\" @%.2f/%.2f".format(java.util.Locale.ROOT, card.line.centerX, card.line.centerY)

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
    private var lastActivity = 0L
    private var endedAt = 0L
    private val buttonHistory = ArrayDeque<Boolean>()
    private val localeVotes = ArrayDeque<String>()

    /** Client language read from UI texts (e.g. the turn button), null until it is clear. */
    var gameLocale: String? = null
        private set

    private class Game(val startedAt: Long) {
        var friendlyClass: HsClass? = null
        var opponentClass: HsClass? = null

        val initialHand = HashMap<String, Int>()
        val keptHand = HashMap<String, Int>()
        var mulliganSignalSeen = false
        var mulliganConfirmed = false
        var framesWithoutSignal = 0
        var framesWithoutRow = 0
        var keptFrames = 0
        var lastMulliganSignal = startedAt

        var turnState: Boolean? = null
        var pendingTurn: Boolean? = null
        var ownTurnHoldUntil = 0L
        var turnCounter = 0
        var turnOrderKnown = false
        var coinSeen = false

        /** Copies received per card, and how many of them left the hand. */
        val drawn = HashMap<String, Int>()
        val played = HashMap<String, Int>()
        val handMisses = HashMap<String, Pair<Int, Long>>()
        val popupLastSeen = HashMap<String, Long>()
        val lastChoice = HashMap<String, Long>()

        val opponentLastSeen = HashMap<String, Long>()
        val opponentSettled = HashMap<String, Boolean>()

        var resultStreak = 0
        var lastResult: MatchResult? = null
    }

    private var game = Game(0)

    fun onFrame(frame: OcrFrame): List<GameEvent> {
        val f = classify(frame)
        val now = frame.timestamp
        val events = mutableListOf<GameEvent>()
        buttonHistory.addLast(f.turn != null)
        if (buttonHistory.size > BUTTON_WINDOW) buttonHistory.removeFirst()

        if (phase == Phase.PLAYING || phase == Phase.MULLIGAN) {
            if (f.result != null) {
                game.resultStreak = if (f.result == game.lastResult) game.resultStreak + 1 else 1
                game.lastResult = f.result
                if (game.resultStreak >= 2) {
                    // Very short "games" are misrecognitions and don't belong in the match history
                    if (phase == Phase.PLAYING && (game.turnCounter >= 2 || now - game.startedAt >= minGameMillis)) {
                        events += GameEvent.GameEnded(f.result)
                    }
                    phase = Phase.ENDED
                    endedAt = now
                    return events
                }
            } else {
                game.resultStreak = 0
                game.lastResult = null
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
                        startGame(events, now)
                        phase = Phase.PLAYING
                        game.turnOrderKnown = true
                        onPlayingFrame(f, now, events)
                    }
                }
            }
            Phase.MULLIGAN -> onMulliganFrame(f, now, events)
            Phase.PLAYING -> onPlayingFrame(f, now, events)
        }
        return events
    }

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
            UiKeywords.localeOf(normalized)?.let(::voteLocale)
            if (UiKeywords.matches(normalized, UiKeywords.confirm) || UiKeywords.matches(normalized, UiKeywords.mulligan)) {
                mulliganSignal = true
                continue
            }
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
                // The opponent's coin is shown on the left
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
            // Sentences (card texts, speech bubbles) end with a period, card names never do
            if (ScreenRegions.isTopBar(line) || line.text.trimEnd().endsWith('.')) continue
            nameCandidates += line
        }
        val preferred by lazy(LazyThreadSafetyMode.NONE) { contextProvider() }
        for (line in nameCandidates) {
            if (isTextContinuation(line, lines)) continue
            // Hero name above the class on the versus screen ("Broxigar" is also a card)
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

    /** Card names stand alone on their banner; a line right below other text belongs to a card text. */
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

    /** Several enlarged cards side by side: a choice (Discover) or the mulligan. */
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

    private fun voteLocale(locale: String) {
        localeVotes.addLast(locale)
        if (localeVotes.size > LOCALE_WINDOW) localeVotes.removeFirst()
        val counts = localeVotes.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val best = counts.first()
        val second = counts.getOrNull(1)?.value ?: 0
        if (best.value >= 3 && best.value >= 2 * second) gameLocale = best.key
    }

    private fun mulliganCards(f: FrameInfo): List<CardLine> =
        f.cards.filter { !ScreenRegions.isHand(it.line) && ScreenRegions.isMulliganCard(it.line) }

    private fun startGame(events: MutableList<GameEvent>, now: Long) {
        events += GameEvent.GameStarted
        game = Game(now)
        lastActivity = now
    }

    private fun updateClasses(f: FrameInfo, events: MutableList<GameEvent>) {
        f.opponentClass?.takeIf { it != game.opponentClass }?.let {
            game.opponentClass = it
            events += GameEvent.ClassDetected(friendly = false, hsClass = it)
        }
        f.friendlyClass?.takeIf { it != game.friendlyClass }?.let {
            game.friendlyClass = it
            events += GameEvent.ClassDetected(friendly = true, hsClass = it)
        }
    }

    private fun onMulliganFrame(f: FrameInfo, now: Long, events: MutableList<GameEvent>) {
        updateClasses(f, events)
        if (f.coin) game.coinSeen = true
        f.turn?.let { game.turnState = it }
        val counts = mulliganCards(f).groupingBy { it.key }.eachCount()

        if (f.mulliganSignal) {
            game.mulliganSignalSeen = true
            game.lastMulliganSignal = now
            game.framesWithoutSignal = 0
        } else {
            game.framesWithoutSignal++
        }
        // Once "Confirm" is gone, the row only holds kept and replacement cards
        if (game.mulliganSignalSeen && game.framesWithoutSignal >= 2) game.mulliganConfirmed = true

        if (!game.mulliganConfirmed) {
            counts.forEach { (key, count) -> game.initialHand.merge(key, count, ::maxOf) }
        } else if (counts.isNotEmpty()) {
            counts.forEach { (key, count) -> game.keptHand.merge(key, count, ::maxOf) }
            game.keptFrames++
        }
        game.framesWithoutRow = if (counts.isEmpty()) game.framesWithoutRow + 1 else 0
        if (counts.isNotEmpty() || f.mulliganSignal) lastActivity = now

        val done = f.yourTurnBanner ||
            (game.mulliganConfirmed && game.framesWithoutRow >= 2) ||
            (game.mulliganConfirmed && now - game.lastMulliganSignal > MULLIGAN_TIMEOUT_MILLIS) ||
            (!game.mulliganSignalSeen && now - game.startedAt > MULLIGAN_TIMEOUT_MILLIS)
        if (done) {
            finishMulligan(events)
            phase = Phase.PLAYING
            onPlayingFrame(f, now, events)
        }
    }

    private fun finishMulligan(events: MutableList<GameEvent>) {
        log { "Mulligan: dealt ${game.initialHand}, kept ${game.keptHand} (${game.keptFrames} frames)" }
        // Replaced cards still count for deck recognition, then go back into the deck
        val keptReliable = game.keptFrames >= 2 && game.keptHand.isNotEmpty()
        val final = if (keptReliable) game.keptHand else HashMap(game.initialHand).apply { game.keptHand.forEach { (k, c) -> merge(k, c, ::maxOf) } }
        for ((key, count) in game.initialHand) {
            val candidates = ids[key] ?: continue
            repeat(count) { events += GameEvent.FriendlyCardSeen(candidates) }
            game.drawn[key] = count
        }
        for ((key, count) in game.initialHand) {
            val back = count - (final[key] ?: 0)
            if (back <= 0) continue
            val candidates = ids[key] ?: continue
            repeat(back) { events += GameEvent.FriendlyCardMulliganed(candidates) }
            game.drawn[key] = count - back
        }
        for ((key, count) in final) {
            val extra = count - (game.initialHand[key] ?: 0)
            if (extra <= 0) continue
            val candidates = ids[key] ?: continue
            repeat(extra) { events += GameEvent.FriendlyCardSeen(candidates) }
            game.drawn[key] = count
        }
        if (game.coinSeen && !game.turnOrderKnown) {
            game.turnOrderKnown = true
            events += GameEvent.TurnOrderDetected(friendlyWentFirst = false)
        }
    }

    private fun onPlayingFrame(f: FrameInfo, now: Long, events: MutableList<GameEvent>) {
        if (game.turnCounter <= 2) updateClasses(f, events)
        if (f.coin && !game.turnOrderKnown) {
            game.turnOrderKnown = true
            events += GameEvent.TurnOrderDetected(friendlyWentFirst = false)
        }
        f.turn?.let { handleTurn(it, f.yourTurnBanner, now, events) }
        if (f.choice) f.cards.forEach { if (!ScreenRegions.isHand(it.line)) game.lastChoice[it.key] = now }
        handleOwnCards(f, now, events)
        handleOpponent(f, now, events)
        if (f.turn != null || f.cards.isNotEmpty()) lastActivity = now
        if (now - lastActivity > idleTimeoutMillis) phase = Phase.IDLE
    }

    /**
     * A turn change needs two equal readings. After the "Your Turn" banner the button still shows
     * the opponent's turn for a moment, which is ignored.
     */
    private fun handleTurn(own: Boolean, banner: Boolean, now: Long, events: MutableList<GameEvent>) {
        if (banner) game.ownTurnHoldUntil = now + BANNER_HOLD_MILLIS
        if (!own && now < game.ownTurnHoldUntil && game.turnState == true) return
        if (own == game.turnState && game.turnCounter > 0) {
            game.pendingTurn = null
            return
        }
        if (!banner && game.pendingTurn != own) {
            game.pendingTurn = own
            return
        }
        game.pendingTurn = null
        if (game.turnCounter == 0) {
            game.turnCounter = 1
            if (!game.turnOrderKnown) {
                game.turnOrderKnown = true
                events += GameEvent.TurnOrderDetected(friendlyWentFirst = own)
            }
        } else {
            game.turnCounter++
        }
        game.turnState = own
        events += GameEvent.TurnChanged(game.turnCounter)
    }

    private fun inHand(key: String): Int = (game.drawn[key] ?: 0) - (game.played[key] ?: 0)

    private fun emitDraw(key: String, count: Int, fromDeck: Boolean, events: MutableList<GameEvent>) {
        val candidates = ids[key] ?: return
        repeat(count) { events += GameEvent.FriendlyCardSeen(candidates, fromDeck) }
        game.drawn[key] = (game.drawn[key] ?: 0) + count
    }

    /**
     * Counts copies received per card: more copies visible in the hand than counted means a draw was
     * missed; an enlarged card on the right is a draw unless the card is already in hand (then it's
     * just being inspected).
     */
    private fun handleOwnCards(f: FrameInfo, now: Long, events: MutableList<GameEvent>) {
        val hand = f.cards.filter { ScreenRegions.isHand(it.line) }
        val handCounts = hand.groupingBy { it.key }.eachCount()
        for ((key, count) in handCounts) {
            val known = inHand(key)
            if (count > known) {
                log { "Hand: ×$count (known $known) ${hand.filter { it.key == key }.joinToString { describe(it) }}" }
                emitDraw(key, count - known, fromDeck = true, events)
            }
            game.handMisses.remove(key)
        }
        detectPlayedCards(hand, handCounts, now)
        if (game.turnState == false || f.choice) return
        for (card in f.cards) {
            if (!ScreenRegions.isDrawPopup(card.line)) continue
            val last = game.popupLastSeen.put(card.key, now)
            if (last != null && now - last < popupRepeatMillis) continue
            if (inHand(card.key) > 0) continue
            val chosen = game.lastChoice[card.key]?.let { now - it < CHOICE_MEMORY_MILLIS } == true
            log { "Drawn${if (chosen) " (picked)" else ""}: ${describe(card)}" }
            emitDraw(card.key, 1, fromDeck = !chosen, events)
        }
    }

    /** Conservative on purpose: wrongly "played" cards would be counted twice later. */
    private fun detectPlayedCards(hand: List<CardLine>, handCounts: Map<String, Int>, now: Long) {
        val rowSize = hand.count { ScreenRegions.isHandZoomRow(it.line) }
        val expected = game.drawn.keys.sumOf { inHand(it) }
        if (rowSize < 3 || rowSize < expected - 1) return
        for (key in game.drawn.keys.toList()) {
            val known = inHand(key)
            val count = handCounts[key] ?: 0
            if (known <= count) continue
            val (misses, since) = game.handMisses[key] ?: (0 to now)
            if (misses + 1 >= HAND_MISSES_FOR_PLAYED && now - since >= PLAYED_MIN_MILLIS) {
                log { "Played: $key (missing from hand row)" }
                game.played[key] = (game.played[key] ?: 0) + known - count
                game.handMisses.remove(key)
            } else {
                game.handMisses[key] = misses + 1 to since
            }
        }
    }

    private fun handleOpponent(f: FrameInfo, now: Long, events: MutableList<GameEvent>) {
        if (game.turnState != false || f.choice) return
        // While the player inspects the hand, related cards may pop up on the left: only trust cards flying in
        val inspectingHand = f.cards.count { ScreenRegions.isHandZoomRow(it.line) } >= 2
        for (card in f.cards) {
            if (!ScreenRegions.isOpponentPopup(card.line)) continue
            val entering = ScreenRegions.isOpponentCardEntering(card.line)
            if (inspectingHand && !entering) continue
            val last = game.opponentLastSeen.put(card.key, now)
            val wasSettled = game.opponentSettled.put(card.key, !entering) == true
            // The same card flying in again is a second copy
            if (last == null || now - last > opponentRepeatMillis || (entering && wasSettled)) {
                log { "Opponent: ${describe(card)}" }
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
        private const val LOCALE_WINDOW = 30

        fun replay(index: CardNameIndex, frames: List<OcrFrame>): List<GameEvent> {
            val tracker = VisionGameTracker(index)
            return frames.flatMap { tracker.onFrame(it) }
        }
    }
}
