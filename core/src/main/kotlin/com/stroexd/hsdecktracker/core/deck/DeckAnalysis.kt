package com.stroexd.hsdecktracker.core.deck

import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.CardType
import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.Rarity

/** Eine Deck-Zeile: Karte (falls bekannt) und Anzahl. */
data class DeckEntry(val dbfId: Int, val card: Card?, val count: Int) {
    val cost: Int get() = card?.cost ?: 0
    val name: String get() = card?.name ?: "Unbekannte Karte ($dbfId)"
}

data class DeckSummary(
    /** Anzahl Karten je Manakosten 0..7+ (Index 7 = 7 und mehr). */
    val manaCurve: List<Int>,
    val typeCounts: Map<CardType, Int>,
    val rarityCounts: Map<Rarity, Int>,
    val totalCards: Int,
    val averageCost: Double,
    /** Arkanstaub, um das komplette Deck herzustellen (ohne Sammlung). */
    val fullDustCost: Int,
)

enum class IssueSeverity { ERROR, WARNING }

data class DeckIssue(val severity: IssueSeverity, val message: String)

object DeckAnalysis {

    /** Prince Renathal erlaubt 40 Karten. */
    private const val RENATHAL_CARD_ID = "REV_018"

    fun entries(cards: Map<Int, Int>, db: CardDatabase): List<DeckEntry> = cards
        .filterValues { it > 0 }
        .map { (id, count) -> DeckEntry(id, db.byDbfId(id), count) }
        .sortedWith(compareBy<DeckEntry>({ it.cost }, { it.card?.cardType?.ordinal ?: 99 }, { it.name }))

    fun summary(cards: Map<Int, Int>, db: CardDatabase): DeckSummary {
        val curve = MutableList(8) { 0 }
        val types = mutableMapOf<CardType, Int>()
        val rarities = mutableMapOf<Rarity, Int>()
        var total = 0
        var costSum = 0
        var dust = 0
        for ((id, count) in cards) {
            if (count <= 0) continue
            val card = db.byDbfId(id)
            total += count
            val cost = card?.cost ?: 0
            costSum += cost * count
            curve[cost.coerceIn(0, 7)] += count
            card?.let {
                types.merge(it.cardType, count, Int::plus)
                rarities.merge(it.rarityType, count, Int::plus)
                dust += it.craftCost * count
            }
        }
        return DeckSummary(
            manaCurve = curve,
            typeCounts = types,
            rarityCounts = rarities,
            totalCards = total,
            averageCost = if (total == 0) 0.0 else costSum.toDouble() / total,
            fullDustCost = dust,
        )
    }

    fun expectedSize(cards: Map<Int, Int>, db: CardDatabase): Int =
        if (cards.keys.any { db.byDbfId(it)?.id == RENATHAL_CARD_ID }) 40 else 30

    fun validate(deck: Deck, db: CardDatabase, rules: FormatRules): List<DeckIssue> {
        val issues = mutableListOf<DeckIssue>()
        val expected = expectedSize(deck.cards, db)
        val count = deck.cardCount
        if (count != expected) {
            issues += DeckIssue(
                if (count > expected) IssueSeverity.ERROR else IssueSeverity.WARNING,
                "Das Deck hat $count von $expected Karten.",
            )
        }
        for ((id, copies) in deck.cards) {
            val card = db.byDbfId(id)
            if (card == null) {
                if (!db.isEmpty) issues += DeckIssue(IssueSeverity.WARNING, "Unbekannte Karte (dbfId $id).")
                continue
            }
            if (copies > card.maxCopies) {
                issues += DeckIssue(IssueSeverity.ERROR, "${card.name}: maximal ${card.maxCopies}× erlaubt.")
            }
            if (deck.heroClass.isPlayable && !card.isAllowedIn(deck.heroClass)) {
                issues += DeckIssue(
                    IssueSeverity.WARNING,
                    "${card.name} gehört nicht zur Klasse ${deck.heroClass.displayName} (nur mit Tourist erlaubt).",
                )
            }
            if (!rules.isLegal(card, deck.format)) {
                issues += DeckIssue(
                    IssueSeverity.WARNING,
                    "${card.name} ist in ${deck.format.displayName} nicht erlaubt.",
                )
            }
        }
        return issues
    }

    /** Der Text, den auch der Hearthstone-Client beim Kopieren erzeugt – inkl. Kartenliste. */
    fun exportText(deck: Deck, db: CardDatabase): String = buildString {
        appendLine("### ${deck.name}")
        appendLine("# Klasse: ${deck.heroClass.displayName}")
        appendLine("# Format: ${deck.format.displayName}")
        appendLine("#")
        for (entry in entries(deck.cards, db)) {
            appendLine("# ${entry.count}x (${entry.cost}) ${entry.name}")
        }
        val sideboardOwners = deck.sideboards.groupBy { it.ownerDbfId }
        for ((owner, cards) in sideboardOwners) {
            appendLine("#   ${db.byDbfId(owner)?.name ?: owner}:")
            for (sb in cards) {
                val card = db.byDbfId(sb.dbfId)
                appendLine("#   ${sb.count}x (${card?.cost ?: 0}) ${card?.name ?: sb.dbfId}")
            }
        }
        appendLine("#")
        appendLine(deck.deckCode())
        appendLine("#")
        append("# Um dieses Deck zu verwenden, kopiere es in deine Zwischenablage und erstelle ein neues Deck in Hearthstone.")
    }

    fun isLegalIn(deck: Deck, format: GameFormat, db: CardDatabase, rules: FormatRules): Boolean =
        deck.cards.keys.all { id -> db.byDbfId(id)?.let { rules.isLegal(it, format) } ?: true }
}
