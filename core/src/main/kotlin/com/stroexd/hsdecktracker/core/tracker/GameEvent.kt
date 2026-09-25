package com.stroexd.hsdecktracker.core.tracker

import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.stats.MatchResult

/** What the screen recognition observed. Cards are candidate dbfIds (e.g. Core and Legacy printing). */
sealed interface GameEvent {
    data object GameStarted : GameEvent
    data class ClassDetected(val friendly: Boolean, val hsClass: HsClass) : GameEvent

    /** [fromDeck] is false for cards that surely didn't come from the deck (e.g. picked from a Discover). */
    data class FriendlyCardSeen(val dbfIds: List<Int>, val fromDeck: Boolean = true) : GameEvent
    data class FriendlyCardMulliganed(val dbfIds: List<Int>) : GameEvent
    data class OpponentCardSeen(val dbfIds: List<Int>) : GameEvent

    /** Counts the turns of both players. */
    data class TurnChanged(val turn: Int) : GameEvent
    data class TurnOrderDetected(val friendlyWentFirst: Boolean) : GameEvent
    data class GameEnded(val result: MatchResult) : GameEvent
}
