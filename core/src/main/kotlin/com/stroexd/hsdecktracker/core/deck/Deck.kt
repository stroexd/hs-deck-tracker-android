package com.stroexd.hsdecktracker.core.deck

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import kotlinx.serialization.Serializable
import java.util.UUID

enum class DeckSource { USER, IMPORT, META }

@Serializable
data class Deck(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val heroClass: HsClass,
    val format: GameFormat = GameFormat.STANDARD,
    /** dbfId → Anzahl */
    val cards: Map<Int, Int> = emptyMap(),
    val sideboards: List<SideboardCard> = emptyList(),
    val heroDbfId: Int? = null,
    val source: DeckSource = DeckSource.USER,
    val archetype: String? = null,
    val notes: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val favorite: Boolean = false,
) {
    val cardCount: Int get() = cards.values.sum()

    fun toDefinition(): DeckDefinition = DeckDefinition(
        heroes = listOf(heroDbfId ?: heroClass.defaultHeroDbfId ?: HsClass.WARRIOR.defaultHeroDbfId!!),
        format = format,
        cards = cards,
        sideboards = sideboards,
    )

    fun deckCode(): String = DeckCode.encode(toDefinition())

    fun withCard(dbfId: Int, delta: Int): Deck {
        val newCount = ((cards[dbfId] ?: 0) + delta).coerceAtLeast(0)
        val newCards = if (newCount == 0) cards - dbfId else cards + (dbfId to newCount)
        return copy(cards = newCards)
    }

    companion object {
        /** Erzeugt ein Deck aus einem dekodierten Deck-Code. */
        fun fromDefinition(
            definition: DeckDefinition,
            name: String?,
            db: CardDatabase,
            now: Long,
            source: DeckSource = DeckSource.IMPORT,
        ): Deck {
            val heroClass = detectClass(definition, db)
            return Deck(
                name = name?.takeIf { it.isNotBlank() } ?: "${heroClass.displayName}-Deck",
                heroClass = heroClass,
                format = definition.format,
                cards = definition.cards,
                sideboards = definition.sideboards,
                heroDbfId = definition.heroes.firstOrNull(),
                source = source,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun detectClass(definition: DeckDefinition, db: CardDatabase): HsClass {
            val heroDbfId = definition.heroes.firstOrNull()
            if (heroDbfId != null) {
                HsClass.fromDefaultHeroDbfId(heroDbfId).takeIf { it.isPlayable }?.let { return it }
                db.byDbfId(heroDbfId)?.let { hero ->
                    hero.hsClass.takeIf { it.isPlayable }?.let { return it }
                    HsClass.fromHeroCardId(hero.id).takeIf { it.isPlayable }?.let { return it }
                }
            }
            return inferClassFromCards(definition.cards, db)
        }

        fun inferClassFromCards(cards: Map<Int, Int>, db: CardDatabase): HsClass =
            cards.entries
                .mapNotNull { (id, count) -> db.byDbfId(id)?.hsClass?.takeIf { it.isPlayable }?.let { it to count } }
                .groupBy({ it.first }, { it.second })
                .maxByOrNull { (_, counts) -> counts.sum() }
                ?.key ?: HsClass.UNKNOWN
    }
}

/** Ein in Text gefundener Deck-Code, optional mit Namen aus der `### Name`-Zeile. */
data class ParsedDeckText(val code: String, val name: String?, val definition: DeckDefinition)

object DeckTextParser {
    private val codeRegex = Regex("AAE[A-Za-z0-9+/]{6,}={0,2}")
    private val nameRegex = Regex("^\\s*###\\s*(.+?)\\s*$")

    /** Findet alle gültigen Deck-Codes in einem Text (z. B. aus der Zwischenablage). */
    fun parseAll(text: String): List<ParsedDeckText> {
        val result = mutableListOf<ParsedDeckText>()
        var pendingName: String? = null
        for (line in text.lines()) {
            val nameMatch = nameRegex.find(line)
            if (nameMatch != null) {
                pendingName = nameMatch.groupValues[1]
                continue
            }
            if (line.trimStart().startsWith("#")) continue
            for (match in codeRegex.findAll(line)) {
                val definition = DeckCode.decodeOrNull(match.value) ?: continue
                result += ParsedDeckText(match.value, pendingName, definition)
                pendingName = null
            }
        }
        return result
    }

    fun parseFirst(text: String): ParsedDeckText? = parseAll(text).firstOrNull()
}
