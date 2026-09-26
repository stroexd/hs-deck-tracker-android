package com.stroexd.hsdecktracker.core.meta

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.deck.DeckCode
import com.stroexd.hsdecktracker.core.deck.DeckDefinition
import com.stroexd.hsdecktracker.core.deck.DeckSource
import com.stroexd.hsdecktracker.core.deck.DeckTextParser
import com.stroexd.hsdecktracker.core.deck.SideboardCard
import com.stroexd.hsdecktracker.core.util.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class MetaDeck(
    val id: String,
    val heroClass: HsClass,
    val format: GameFormat,
    val cards: Map<Int, Int>,
    val sideboards: List<SideboardCard> = emptyList(),
    val archetypeId: Int? = null,
    val archetypeName: String? = null,
    val winRate: Double? = null,
    val totalGames: Int? = null,
    val avgTurns: Double? = null,
    val avgDurationSeconds: Double? = null,
    val name: String? = null,
) {
    val displayName: String
        get() = archetypeName ?: name ?: "${heroClass.englishName} Deck"

    val deckCode: String
        get() = DeckCode.encode(
            DeckDefinition(listOf(heroClass.defaultHeroDbfId ?: 7), format, cards, sideboards),
        )

    fun toDeck(now: Long): Deck = Deck(
        name = displayName,
        heroClass = heroClass,
        format = format,
        cards = cards,
        sideboards = sideboards,
        source = DeckSource.META,
        archetype = archetypeName,
        createdAt = now,
        updatedAt = now,
    )
}

@Serializable
data class MetaSnapshot(
    val decks: List<MetaDeck> = emptyList(),
    val fetchedAt: Long = 0,
    val source: String = "",
    val description: String = "",
) {
    val fromHsReplay: Boolean get() = description.startsWith("hsreplay|")
}

class MetaParseException(message: String) : IllegalArgumentException(message)

object HsReplayParser {
    fun parseArchetypes(json: String): Map<Int, String> {
        val root = AppJson.parseToJsonElement(json)
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["results"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return list.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.int("id") ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            id to name
        }.toMap()
    }

    fun parseDecks(json: String, archetypes: Map<Int, String>, format: GameFormat): List<MetaDeck> {
        val root = AppJson.parseToJsonElement(json) as? JsonObject
            ?: throw MetaParseException("Unexpected HSReplay response")
        val series = root["series"] as? JsonObject ?: throw MetaParseException("No deck data in response")
        val data = series["data"] as? JsonObject ?: throw MetaParseException("No deck data in response")
        val decks = mutableListOf<MetaDeck>()
        for ((classKey, value) in data) {
            val heroClass = HsClass.fromString(classKey)
            val entries = value as? JsonArray ?: continue
            for (entry in entries) {
                val obj = entry as? JsonObject ?: continue
                val cards = parseCardList(obj["deck_list"]) ?: continue
                if (cards.isEmpty()) continue
                val archetypeId = obj.int("archetype_id")
                val winRate = obj.double("win_rate")?.let { if (it > 1.0) it / 100.0 else it }
                decks += MetaDeck(
                    id = obj.string("deck_id") ?: cards.hashCode().toString(),
                    heroClass = heroClass,
                    format = format,
                    cards = cards,
                    sideboards = parseSideboards(obj["deck_sideboard"]),
                    archetypeId = archetypeId,
                    archetypeName = archetypeId?.let { archetypes[it] },
                    winRate = winRate,
                    totalGames = obj.int("total_games"),
                    avgTurns = obj.double("avg_num_player_turns"),
                    avgDurationSeconds = obj.double("avg_game_length_seconds"),
                )
            }
        }
        return decks
    }

    private fun parseCardList(element: JsonElement?): Map<Int, Int>? {
        val array = asArray(element) ?: return null
        val cards = linkedMapOf<Int, Int>()
        for (pair in array) {
            val p = pair as? JsonArray ?: continue
            val id = (p.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: continue
            val count = (p.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: 1
            cards.merge(id, count, Int::plus)
        }
        return cards
    }

    private fun parseSideboards(element: JsonElement?): List<SideboardCard> {
        val array = asArray(element) ?: return emptyList()
        return array.mapNotNull { item ->
            val p = item as? JsonArray ?: return@mapNotNull null
            val values = p.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            when (values.size) {
                3 -> SideboardCard(values[0], values[1], values[2])
                else -> null
            }
        }
    }

    private fun asArray(element: JsonElement?): JsonArray? = when (element) {
        is JsonArray -> element
        is JsonPrimitive -> element.contentOrNull?.let {
            runCatching { AppJson.parseToJsonElement(it) as? JsonArray }.getOrNull()
        }
        else -> null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}

object DeckListParser {
    fun parse(text: String, db: CardDatabase): List<MetaDeck> =
        DeckTextParser.parseAll(text).mapIndexed { index, parsed ->
            val heroClass = Deck.detectClass(parsed.definition, db)
            MetaDeck(
                id = "list-$index-${parsed.code.hashCode()}",
                heroClass = heroClass,
                format = parsed.definition.format,
                cards = parsed.definition.cards,
                sideboards = parsed.definition.sideboards,
                name = parsed.name,
            )
        }
}

data class DeckPrediction(
    val deck: MetaDeck,
    val matchedCards: Int,
    val seenCards: Int,
    val remainingCards: Map<Int, Int>,
) {
    val matchFraction: Double get() = if (seenCards == 0) 0.0 else matchedCards.toDouble() / seenCards
}

object OpponentPredictor {
    fun predict(
        opponentClass: HsClass,
        seenCards: List<Int>,
        decks: List<MetaDeck>,
        limit: Int = 3,
    ): List<DeckPrediction> {
        val candidates = decks.filter { opponentClass == HsClass.UNKNOWN || it.heroClass == opponentClass }
        if (candidates.isEmpty()) return emptyList()
        val seen = seenCards.groupingBy { it }.eachCount()
        val predictions = candidates.map { deck ->
            var matched = 0
            val remaining = deck.cards.toMutableMap()
            for ((id, count) in seen) {
                val inDeck = deck.cards[id] ?: 0
                val m = minOf(inDeck, count)
                matched += m
                if (inDeck > 0) {
                    val left = inDeck - m
                    if (left > 0) remaining[id] = left else remaining.remove(id)
                }
            }
            DeckPrediction(deck, matched, seenCards.size, remaining)
        }
        return predictions
            .filter { seenCards.isEmpty() || it.matchedCards > 0 }
            .sortedWith(
                compareByDescending<DeckPrediction> { it.matchedCards }
                    .thenByDescending { it.deck.totalGames ?: 0 },
            )
            .distinctBy { it.deck.archetypeName ?: it.deck.id }
            .take(limit)
    }
}
