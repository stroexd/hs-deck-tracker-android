package com.stroexd.hsdecktracker.core.tracker

import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.deck.DeckCode
import com.stroexd.hsdecktracker.core.stats.MatchResult

/** Ereignisse einer Partie – aus dem Hearthstone-`Power.log` oder aus der Bilderkennung. */
sealed interface GameEvent {
    data object GameStarted : GameEvent
    data class FormatDetected(val format: GameFormat) : GameEvent
    data class FriendlyPlayerDetected(val playerId: Int) : GameEvent
    data class HeroRevealed(val friendly: Boolean, val cardId: String) : GameEvent
    data class FriendlyCardDrawn(val cardId: String) : GameEvent
    data class FriendlyCardReturned(val cardId: String) : GameEvent
    data class OpponentCardPlayed(val cardId: String) : GameEvent
    /** Globaler Zugzähler des Spiels (zählt Züge beider Spieler). */
    data class TurnChanged(val turn: Int) : GameEvent
    data class TurnOrderDetected(val friendlyWentFirst: Boolean) : GameEvent
    data class GameEnded(val result: MatchResult?) : GameEvent

    /** Bilderkennung: eigene Karte gezogen (mögliche dbfIds, z. B. Kernset- und Legacy-Druck). */
    data class FriendlyCardSeen(val dbfIds: List<Int>) : GameEvent

    /** Bilderkennung: Karte vom Gegner gespielt (mögliche dbfIds). */
    data class OpponentCardSeen(val dbfIds: List<Int>) : GameEvent
}

/**
 * Zustandsbehafteter Parser für die `GameState.DebugPrint*`-Zeilen aus `Power.log`
 * (gleiches Format wie auf dem PC, das auch HDT/HSReplay auswerten).
 *
 * Erkannt werden: Spielstart/-ende inkl. Ergebnis, eigene gezogene/zurückgemischte Karten
 * (nur Karten aus dem ursprünglichen Deck), vom Gegner gespielte Karten, Helden, Zugreihenfolge.
 */
class PowerLogParser {

    private class Entity(val id: Int) {
        var cardId: String = ""
        var controller: Int = 0
        var zone: String = ""
        var cardType: String = ""
        var originalDeck = false
        var leftDeckPending = false
        var playedPending = false
    }

    private val entities = HashMap<Int, Entity>()
    private val playerNames = HashMap<String, Int>()
    private val heroes = HashMap<Int, String>()
    private val announcedHeroes = HashSet<Int>()
    private var friendlyPlayerId: Int? = null
    private var firstPlayerId: Int? = null
    private var turnOrderAnnounced = false
    private var setupPhase = false
    private var gameEnded = true
    private var pendingResult: MatchResult? = null
    private var choicePlayerName: String? = null

    private var blockEntity: Entity? = null
    private var blockPlayerId: Int? = null
    private var blockIsCreate = false
    private var blockIsShow = false

    val isInGame: Boolean get() = !gameEnded

    fun parseLine(line: String): List<GameEvent> {
        val match = prefixRegex.find(line) ?: return emptyList()
        val content = match.groupValues[2].trim()
        val events = mutableListOf<GameEvent>()
        when (match.groupValues[1]) {
            "DebugPrintPower" -> handlePower(content, events)
            "DebugPrintGame" -> handleGame(content, events)
            "DebugPrintEntityChoices" -> handleChoices(content, events)
        }
        return events
    }

    // ------------------------------------------------------------------ Power

    private fun handlePower(content: String, events: MutableList<GameEvent>) {
        if (content.startsWith("tag=")) {
            val tag = blockTagRegex.find(content) ?: return
            applyBlockTag(tag.groupValues[1], tag.groupValues[2], events)
            return
        }
        finishBlock(events)
        when {
            content == "CREATE_GAME" -> startGame(events)
            content.startsWith("GameEntity") -> Unit
            content.startsWith("Player ") -> {
                val m = playerRegex.find(content) ?: return
                blockPlayerId = m.groupValues[2].toInt()
            }
            content.startsWith("FULL_ENTITY") -> {
                val creating = fullCreatingRegex.find(content)
                val entity: Entity
                if (creating != null) {
                    val id = creating.groupValues[1].toInt()
                    entity = Entity(id).also { entities[id] = it }
                    entity.cardId = creating.groupValues[2]
                } else {
                    val updating = entityWithCardIdRegex.find(content) ?: return
                    entity = entityFromRef(updating.groupValues[1]) ?: return
                    if (updating.groupValues[2].isNotEmpty()) entity.cardId = updating.groupValues[2]
                }
                blockEntity = entity
                blockIsCreate = true
            }
            content.startsWith("SHOW_ENTITY") || content.startsWith("CHANGE_ENTITY") -> {
                val m = entityWithCardIdRegex.find(content) ?: return
                val entity = entityFromRef(m.groupValues[1]) ?: return
                if (m.groupValues[2].isNotEmpty()) entity.cardId = m.groupValues[2]
                blockEntity = entity
                blockIsShow = true
            }
            content.startsWith("HIDE_ENTITY") || content.startsWith("TAG_CHANGE") -> {
                val m = tagChangeRegex.find(content) ?: return
                handleTagChange(m.groupValues[1].trim(), m.groupValues[2], m.groupValues[3], events)
            }
        }
    }

    private fun applyBlockTag(tag: String, value: String, events: MutableList<GameEvent>) {
        blockPlayerId?.let { playerId ->
            if (tag == "FIRST_PLAYER" && value == "1") {
                firstPlayerId = playerId
                announceTurnOrder(events)
            }
            return
        }
        val entity = blockEntity ?: return
        when (tag) {
            "ZONE" -> if (blockIsCreate) {
                entity.zone = value
                if (setupPhase && value == "DECK") entity.originalDeck = true
            } else {
                changeZone(entity, value, events)
            }
            "CONTROLLER" -> value.toIntOrNull()?.let { entity.controller = it }
            "CARDTYPE" -> entity.cardType = value
        }
    }

    private fun finishBlock(events: MutableList<GameEvent>) {
        val entity = blockEntity
        if (entity != null) {
            if (blockIsCreate && setupPhase && entity.cardType == "HERO" && entity.zone == "PLAY" && entity.cardId.isNotEmpty()) {
                heroes[entity.controller] = entity.cardId
                announceHeroes(events)
            }
            if (blockIsShow) {
                maybeDetectFriendly(entity, events)
                flush(entity, events)
            }
        }
        blockEntity = null
        blockPlayerId = null
        blockIsCreate = false
        blockIsShow = false
    }

    private fun handleTagChange(ref: String, tag: String, value: String, events: MutableList<GameEvent>) {
        if (ref == "GameEntity" || ref == "1" && tag in gameTags) {
            when {
                tag == "STEP" && value == "BEGIN_MULLIGAN" -> setupPhase = false
                tag == "TURN" -> value.toIntOrNull()?.let { events += GameEvent.TurnChanged(it) }
                tag == "STATE" && value == "COMPLETE" -> endGame(events)
            }
            return
        }
        val entity = entityFromRef(ref)
        if (entity != null) {
            when (tag) {
                "ZONE" -> changeZone(entity, value, events)
                "CONTROLLER" -> value.toIntOrNull()?.let { entity.controller = it }
                "CARDTYPE" -> entity.cardType = value
            }
            return
        }
        // Spielername
        when (tag) {
            "PLAYSTATE" -> {
                val result = when (value) {
                    "WON" -> MatchResult.WIN
                    "LOST" -> MatchResult.LOSS
                    "TIED" -> MatchResult.DRAW
                    else -> return
                }
                val playerId = playerIdForName(ref) ?: return
                val friendly = friendlyPlayerId ?: return
                pendingResult = if (playerId == friendly) result else result.inverted()
            }
            "FIRST_PLAYER" -> if (value == "1") {
                playerIdForName(ref)?.let { firstPlayerId = it; announceTurnOrder(events) }
            }
        }
    }

    private fun changeZone(entity: Entity, newZone: String, events: MutableList<GameEvent>) {
        val old = entity.zone
        entity.zone = newZone
        if (old == newZone) return
        if (old == "DECK" && entity.originalDeck) {
            entity.leftDeckPending = true
        }
        if (newZone == "DECK" && old == "HAND" && entity.originalDeck) {
            if (entity.leftDeckPending) {
                entity.leftDeckPending = false
            } else if (entity.cardId.isNotEmpty() && isFriendly(entity)) {
                events += GameEvent.FriendlyCardReturned(entity.cardId)
            }
        }
        if (old == "HAND" && newZone in playedZones) {
            entity.playedPending = true
        }
        flush(entity, events)
    }

    /** Meldet ausstehende Ereignisse, sobald Karten-ID und eigener Spieler bekannt sind. */
    private fun flush(entity: Entity, events: MutableList<GameEvent>) {
        val friendly = friendlyPlayerId ?: return
        if (entity.cardId.isEmpty()) return
        if (entity.leftDeckPending) {
            if (entity.controller == friendly) events += GameEvent.FriendlyCardDrawn(entity.cardId)
            entity.leftDeckPending = false
        }
        if (entity.playedPending) {
            if (entity.controller != friendly && entity.controller != 0) events += GameEvent.OpponentCardPlayed(entity.cardId)
            entity.playedPending = false
        }
    }

    private fun maybeDetectFriendly(entity: Entity, events: MutableList<GameEvent>) {
        if (friendlyPlayerId != null || entity.cardId.isEmpty() || entity.controller == 0) return
        if (entity.originalDeck) setFriendly(entity.controller, events)
    }

    private fun setFriendly(playerId: Int, events: MutableList<GameEvent>) {
        if (friendlyPlayerId != null) return
        friendlyPlayerId = playerId
        events += GameEvent.FriendlyPlayerDetected(playerId)
        announceHeroes(events)
        announceTurnOrder(events)
        entities.values.sortedBy { it.id }.forEach { flush(it, events) }
    }

    private fun isFriendly(entity: Entity): Boolean = friendlyPlayerId != null && entity.controller == friendlyPlayerId

    private fun announceHeroes(events: MutableList<GameEvent>) {
        val friendly = friendlyPlayerId ?: return
        for ((playerId, cardId) in heroes.toSortedMap()) {
            if (announcedHeroes.add(playerId)) events += GameEvent.HeroRevealed(playerId == friendly, cardId)
        }
    }

    private fun announceTurnOrder(events: MutableList<GameEvent>) {
        if (turnOrderAnnounced) return
        val friendly = friendlyPlayerId ?: return
        val first = firstPlayerId ?: return
        turnOrderAnnounced = true
        events += GameEvent.TurnOrderDetected(first == friendly)
    }

    private fun startGame(events: MutableList<GameEvent>) {
        entities.clear()
        playerNames.clear()
        heroes.clear()
        announcedHeroes.clear()
        friendlyPlayerId = null
        firstPlayerId = null
        turnOrderAnnounced = false
        pendingResult = null
        choicePlayerName = null
        setupPhase = true
        gameEnded = false
        events += GameEvent.GameStarted
    }

    private fun endGame(events: MutableList<GameEvent>) {
        if (gameEnded) return
        gameEnded = true
        events += GameEvent.GameEnded(pendingResult)
    }

    private fun entityFromRef(ref: String): Entity? {
        ref.toIntOrNull()?.let { id -> return entities.getOrPut(id) { Entity(id) } }
        if (!ref.startsWith("[")) return null
        val id = bracketIdRegex.find(ref)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val entity = entities.getOrPut(id) { Entity(id) }
        bracketCardIdRegex.find(ref)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.let {
            if (entity.cardId.isEmpty()) entity.cardId = it
        }
        bracketPlayerRegex.find(ref)?.groupValues?.get(1)?.toIntOrNull()?.let {
            if (entity.controller == 0) entity.controller = it
        }
        bracketZoneRegex.find(ref)?.groupValues?.get(1)?.let {
            if (entity.zone.isEmpty()) entity.zone = it
        }
        return entity
    }

    private fun playerIdForName(name: String): Int? {
        playerNames[name]?.let { return it }
        if (playerNames.size == 1) {
            val (knownName, knownId) = playerNames.entries.first()
            if (knownName != name) return if (knownId == 1) 2 else 1
        }
        return null
    }

    // ------------------------------------------------------------------ Game / Choices

    private fun handleGame(content: String, events: MutableList<GameEvent>) {
        playerNameRegex.find(content)?.let { m ->
            playerNames[m.groupValues[2].trim()] = m.groupValues[1].toInt()
            return
        }
        formatRegex.find(content)?.let { m ->
            GameFormat.fromString(m.groupValues[1])?.let { events += GameEvent.FormatDetected(it) }
        }
    }

    private fun handleChoices(content: String, events: MutableList<GameEvent>) {
        choiceHeaderRegex.find(content)?.let { m ->
            choicePlayerName = m.groupValues[1].trim()
            return
        }
        val entityMatch = choiceEntityRegex.find(content) ?: return
        val ref = entityMatch.groupValues[1]
        val playerId = bracketPlayerRegex.find(ref)?.groupValues?.get(1)?.toIntOrNull() ?: return
        choicePlayerName?.let { playerNames.putIfAbsent(it, playerId) }
        val cardId = bracketCardIdRegex.find(ref)?.groupValues?.get(1).orEmpty()
        if (cardId.isNotEmpty() && cardId != COIN_CARD_ID) setFriendly(playerId, events)
    }

    private fun MatchResult.inverted(): MatchResult = when (this) {
        MatchResult.WIN -> MatchResult.LOSS
        MatchResult.LOSS -> MatchResult.WIN
        MatchResult.DRAW -> MatchResult.DRAW
    }

    private companion object {
        const val COIN_CARD_ID = "GAME_005"
        val playedZones = setOf("PLAY", "SECRET", "GRAVEYARD")
        val gameTags = setOf("STEP", "TURN", "STATE")

        val prefixRegex = Regex("GameState\\.(DebugPrintPower|DebugPrintGame|DebugPrintEntityChoices)\\(\\)\\s*-\\s?(.*)$")
        val blockTagRegex = Regex("^tag=(\\S+) value=(\\S*)")
        val playerRegex = Regex("Player EntityID=(\\d+) PlayerID=(\\d+)")
        val fullCreatingRegex = Regex("FULL_ENTITY - Creating ID=(\\d+) CardID=(\\S*)")
        val entityWithCardIdRegex = Regex("(?:Updating|Entity=)\\s*(?:Entity=)?(\\[.*]|\\d+) CardID=(\\S*)\\s*$")
        val tagChangeRegex = Regex("Entity=(.+) tag=(\\S+) value=(\\S*)")
        val bracketIdRegex = Regex("\\bid=(\\d+)")
        val bracketCardIdRegex = Regex("cardId=([^\\s\\]]*)")
        val bracketPlayerRegex = Regex("player=(\\d+)")
        val bracketZoneRegex = Regex("\\bzone=(\\w+)")
        val playerNameRegex = Regex("PlayerID=(\\d+), PlayerName=(.+)$")
        val formatRegex = Regex("FormatType=(\\w+)")
        val choiceHeaderRegex = Regex("id=\\d+ Player=(.+?) TaskList=")
        val choiceEntityRegex = Regex("Entities\\[\\d+]=(\\[.*])")
    }
}

/**
 * Liest `Decks.log`: Beim Suchen einer Partie schreibt Hearthstone Name und Code des gewählten Decks.
 */
class DecksLogParser {
    data class DeckSelection(val name: String?, val deckCode: String)

    private var capturing = false
    private var name: String? = null
    private var linesSinceStart = 0

    fun parseLine(line: String): DeckSelection? {
        val content = timestampRegex.replace(line, "").trim()
        if (content.contains("With Deck:")) {
            capturing = true
            name = null
            linesSinceStart = 0
            return null
        }
        if (!capturing) return null
        linesSinceStart++
        if (linesSinceStart > 8) {
            capturing = false
            return null
        }
        when {
            content.startsWith("###") -> name = content.removePrefix("###").trim()
            content.startsWith("#") -> Unit
            codeRegex.matches(content) && DeckCode.decodeOrNull(content) != null -> {
                capturing = false
                return DeckSelection(name, content)
            }
        }
        return null
    }

    private companion object {
        val timestampRegex = Regex("^[A-Z] \\d{1,2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\s+")
        val codeRegex = Regex("AAE[A-Za-z0-9+/]+={0,2}")
    }
}
