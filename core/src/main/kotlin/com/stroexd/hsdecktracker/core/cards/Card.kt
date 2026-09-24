package com.stroexd.hsdecktracker.core.cards

import com.stroexd.hsdecktracker.core.util.normalizeForSearch
import kotlinx.serialization.Serializable

/**
 * Eine Karte im Format von HearthstoneJSON (api.hearthstonejson.com).
 * Unbekannte Felder werden beim Parsen ignoriert.
 */
@Serializable
data class Card(
    val dbfId: Int,
    val id: String,
    val name: String = "",
    val cost: Int = 0,
    val attack: Int? = null,
    val health: Int? = null,
    val durability: Int? = null,
    val armor: Int? = null,
    val type: String = "",
    val rarity: String = "",
    val cardClass: String = "NEUTRAL",
    val classes: List<String> = emptyList(),
    val set: String = "",
    val text: String = "",
    val flavor: String = "",
    val artist: String = "",
    val race: String? = null,
    val races: List<String> = emptyList(),
    val spellSchool: String? = null,
    val mechanics: List<String> = emptyList(),
    val collectible: Boolean = false,
    val howToEarn: String? = null,
    val howToEarnGolden: String? = null,
) {
    val hsClass: HsClass get() = HsClass.fromString(cardClass)
    val rarityType: Rarity get() = Rarity.fromString(rarity)
    val cardType: CardType get() = CardType.fromString(type)

    /** Alle Klassen, in deren Decks die Karte gespielt werden darf (Mehrklassenkarten). */
    val allowedClasses: Set<HsClass>
        get() = if (classes.isNotEmpty()) classes.map { HsClass.fromString(it) }.toSet() else setOf(hsClass)

    val isNeutral: Boolean get() = hsClass == HsClass.NEUTRAL && classes.isEmpty()

    fun isAllowedIn(heroClass: HsClass): Boolean =
        isNeutral || hsClass == heroClass || heroClass in allowedClasses

    val maxCopies: Int get() = rarityType.maxCopies

    /** Karten, die man in ein Deck packen kann (keine Heldenskins, Heldenfähigkeiten, Modus-Karten). */
    val isDeckCard: Boolean
        get() = collectible &&
            cardType in CardType.deckTypes &&
            set !in CardSets.nonConstructedSets &&
            !set.startsWith("BATTLEGROUNDS") &&
            !set.startsWith("LETTUCE")

    /** Herstellbar mit Arkanstaub. Kernset-/Basiskarten und Belohnungskarten sind es nicht. */
    val isCraftable: Boolean
        get() = rarityType in Rarity.collectible && set !in CardSets.freeSets && howToEarn == null

    val craftCost: Int get() = if (isCraftable) rarityType.craftCost else 0

    val tribes: List<String> get() = races.ifEmpty { listOfNotNull(race) }

    /** Kartentext ohne Hearthstone-Markup. */
    val plainText: String get() = cleanCardText(text)

    val plainFlavor: String get() = cleanCardText(flavor)

    /** Normalisierter Suchtext (Name, Text, Stämme, Mechaniken) – einmalig berechnet. */
    val searchText: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildString {
            append(normalizeForSearch(name)).append(' ')
            append(normalizeForSearch(plainText)).append(' ')
            tribes.forEach { append(it.lowercase()).append(' ') }
            mechanics.forEach { append(it.lowercase()).append(' ') }
            spellSchool?.let { append(it.lowercase()) }
        }
    }
}

private val tagRegex = Regex("<[^>]+>")
private val variableRegex = Regex("[$#](\\d+)")
private val refTagRegex = Regex("\\{\\d+}")

fun cleanCardText(raw: String): String = raw
    .replace("[x]", "")
    .replace(tagRegex, "")
    .replace(variableRegex, "$1")
    .replace(refTagRegex, "")
    .replace("\\n", " ")
    .replace('\n', ' ')
    .replace('_', ' ')
    .replace("@", "")
    .replace(Regex("\\s+"), " ")
    .trim()
