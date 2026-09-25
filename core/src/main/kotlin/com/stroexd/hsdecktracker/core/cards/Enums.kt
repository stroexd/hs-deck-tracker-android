package com.stroexd.hsdecktracker.core.cards

enum class HsClass(
    val defaultHeroDbfId: Int?,
    val heroCardIdPrefix: String?,
    val color: Long,
) {
    DEATHKNIGHT(78065, "HERO_11", 0xFFC41E3A),
    DEMONHUNTER(56550, "HERO_10", 0xFFA330C9),
    DRUID(274, "HERO_06", 0xFFFF7C0A),
    HUNTER(31, "HERO_05", 0xFFAAD372),
    MAGE(637, "HERO_08", 0xFF3FC7EB),
    PALADIN(671, "HERO_04", 0xFFF48CBA),
    PRIEST(813, "HERO_09", 0xFFE8E8E8),
    ROGUE(930, "HERO_03", 0xFFFFF468),
    SHAMAN(1066, "HERO_02", 0xFF0070DD),
    WARLOCK(893, "HERO_07", 0xFF8788EE),
    WARRIOR(7, "HERO_01", 0xFFC69B6D),
    NEUTRAL(null, null, 0xFF9E9E9E),
    UNKNOWN(null, null, 0xFF616161);

    val isPlayable: Boolean get() = defaultHeroDbfId != null

    val englishName: String
        get() = when (this) {
            DEATHKNIGHT -> "Death Knight"
            DEMONHUNTER -> "Demon Hunter"
            else -> name.lowercase().replaceFirstChar { it.uppercase() }
        }

    companion object {
        val playable: List<HsClass> = entries.filter { it.isPlayable }

        fun fromString(value: String?): HsClass {
            if (value.isNullOrBlank()) return UNKNOWN
            val normalized = value.trim().uppercase().replace("_", "").replace(" ", "")
            return entries.firstOrNull { it.name == normalized } ?: UNKNOWN
        }

        fun fromHeroCardId(cardId: String?): HsClass {
            if (cardId.isNullOrBlank()) return UNKNOWN
            return playable.firstOrNull { cardId.startsWith(it.heroCardIdPrefix!!) } ?: UNKNOWN
        }

        fun fromDefaultHeroDbfId(dbfId: Int): HsClass =
            playable.firstOrNull { it.defaultHeroDbfId == dbfId } ?: UNKNOWN
    }
}

enum class Rarity(
    val craftCost: Int,
    val disenchantValue: Int,
    val goldenCraftCost: Int,
    val goldenDisenchantValue: Int,
    val color: Long,
) {
    FREE(0, 0, 0, 0, 0xFF9E9E9E),
    COMMON(40, 5, 400, 50, 0xFFDADADA),
    RARE(100, 20, 800, 100, 0xFF2F80ED),
    EPIC(400, 100, 1600, 400, 0xFFA335EE),
    LEGENDARY(1600, 400, 3200, 1600, 0xFFFF8000),
    UNKNOWN(0, 0, 0, 0, 0xFF9E9E9E);

    val maxCopies: Int get() = if (this == LEGENDARY) 1 else 2

    companion object {
        val collectible: List<Rarity> = listOf(COMMON, RARE, EPIC, LEGENDARY)
        fun fromString(value: String?): Rarity =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: UNKNOWN
    }
}

enum class CardType {
    MINION, SPELL, WEAPON, HERO, LOCATION, HERO_POWER, ENCHANTMENT, UNKNOWN;

    companion object {
        val deckTypes: List<CardType> = listOf(MINION, SPELL, WEAPON, HERO, LOCATION)
        fun fromString(value: String?): CardType =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: UNKNOWN
    }
}

enum class GameFormat(val id: Int) {
    WILD(1),
    STANDARD(2),
    CLASSIC(3),
    TWIST(4);

    val englishName: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        fun fromId(id: Int): GameFormat = entries.firstOrNull { it.id == id } ?: WILD

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
