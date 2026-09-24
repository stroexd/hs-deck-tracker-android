package com.stroexd.hsdecktracker.core.cards

/**
 * Hearthstone-Klassen inkl. Standard-Helden (für Deck-Codes) und Klassenfarbe (ARGB).
 */
enum class HsClass(
    val displayName: String,
    val defaultHeroDbfId: Int?,
    val heroCardIdPrefix: String?,
    val color: Long,
) {
    DEATHKNIGHT("Todesritter", 78065, "HERO_11", 0xFFC41E3A),
    DEMONHUNTER("Dämonenjäger", 56550, "HERO_10", 0xFFA330C9),
    DRUID("Druide", 274, "HERO_06", 0xFFFF7C0A),
    HUNTER("Jäger", 31, "HERO_05", 0xFFAAD372),
    MAGE("Magier", 637, "HERO_08", 0xFF3FC7EB),
    PALADIN("Paladin", 671, "HERO_04", 0xFFF48CBA),
    PRIEST("Priester", 813, "HERO_09", 0xFFE8E8E8),
    ROGUE("Schurke", 930, "HERO_03", 0xFFFFF468),
    SHAMAN("Schamane", 1066, "HERO_02", 0xFF0070DD),
    WARLOCK("Hexenmeister", 893, "HERO_07", 0xFF8788EE),
    WARRIOR("Krieger", 7, "HERO_01", 0xFFC69B6D),
    NEUTRAL("Neutral", null, null, 0xFF9E9E9E),
    UNKNOWN("Unbekannt", null, null, 0xFF616161);

    val isPlayable: Boolean get() = defaultHeroDbfId != null

    companion object {
        val playable: List<HsClass> = entries.filter { it.isPlayable }

        fun fromString(value: String?): HsClass {
            if (value.isNullOrBlank()) return UNKNOWN
            val normalized = value.trim().uppercase().replace("_", "").replace(" ", "")
            return entries.firstOrNull { it.name == normalized } ?: UNKNOWN
        }

        /** Leitet die Klasse aus einer Helden-Karten-ID wie `HERO_08b` ab. */
        fun fromHeroCardId(cardId: String?): HsClass {
            if (cardId.isNullOrBlank()) return UNKNOWN
            return playable.firstOrNull { cardId.startsWith(it.heroCardIdPrefix!!) } ?: UNKNOWN
        }

        fun fromDefaultHeroDbfId(dbfId: Int): HsClass =
            playable.firstOrNull { it.defaultHeroDbfId == dbfId } ?: UNKNOWN
    }
}

enum class Rarity(
    val displayName: String,
    val craftCost: Int,
    val disenchantValue: Int,
    val goldenCraftCost: Int,
    val goldenDisenchantValue: Int,
    val color: Long,
) {
    FREE("Basis", 0, 0, 0, 0, 0xFF9E9E9E),
    COMMON("Gewöhnlich", 40, 5, 400, 50, 0xFFDADADA),
    RARE("Selten", 100, 20, 800, 100, 0xFF2F80ED),
    EPIC("Episch", 400, 100, 1600, 400, 0xFFA335EE),
    LEGENDARY("Legendär", 1600, 400, 3200, 1600, 0xFFFF8000),
    UNKNOWN("Unbekannt", 0, 0, 0, 0, 0xFF9E9E9E);

    val maxCopies: Int get() = if (this == LEGENDARY) 1 else 2

    companion object {
        val collectible: List<Rarity> = listOf(COMMON, RARE, EPIC, LEGENDARY)
        fun fromString(value: String?): Rarity =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: UNKNOWN
    }
}

enum class CardType(val displayName: String) {
    MINION("Diener"),
    SPELL("Zauber"),
    WEAPON("Waffe"),
    HERO("Held"),
    LOCATION("Ort"),
    HERO_POWER("Heldenfähigkeit"),
    ENCHANTMENT("Verzauberung"),
    UNKNOWN("Unbekannt");

    companion object {
        val deckTypes: List<CardType> = listOf(MINION, SPELL, WEAPON, HERO, LOCATION)
        fun fromString(value: String?): CardType =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: UNKNOWN
    }
}

/** Spielformat, IDs entsprechen dem Format-Feld im Hearthstone-Deck-Code. */
enum class GameFormat(val id: Int, val displayName: String) {
    WILD(1, "Wild"),
    STANDARD(2, "Standard"),
    CLASSIC(3, "Klassisch"),
    TWIST(4, "Twist");

    companion object {
        fun fromId(id: Int): GameFormat = entries.firstOrNull { it.id == id } ?: WILD

        /** Akzeptiert `FT_STANDARD`, `STANDARD`, `RANKED_STANDARD` usw. */
        fun fromString(value: String?): GameFormat? {
            val v = value?.uppercase() ?: return null
            return when {
                "STANDARD" in v -> STANDARD
                "WILD" in v -> WILD
                "CLASSIC" in v -> CLASSIC
                "TWIST" in v -> TWIST
                else -> null
            }
        }
    }
}
