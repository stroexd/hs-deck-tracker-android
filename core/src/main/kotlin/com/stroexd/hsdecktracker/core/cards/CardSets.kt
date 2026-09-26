package com.stroexd.hsdecktracker.core.cards

object CardSets {
    private val names: Map<String, String> = mapOf(
        "CORE" to "Core",
        "LEGACY" to "Legacy",
        "EXPERT1" to "Legacy (Classic)",
        "BASIC" to "Basic",
        "VANILLA" to "Classic",
        "HOF" to "Hall of Fame",
        "NAXX" to "Naxxramas",
        "GVG" to "Goblins vs Gnomes",
        "BRM" to "Blackrock Mountain",
        "TGT" to "The Grand Tournament",
        "LOE" to "League of Explorers",
        "OG" to "Whispers of the Old Gods",
        "KARA" to "One Night in Karazhan",
        "GANGS" to "Mean Streets of Gadgetzan",
        "UNGORO" to "Journey to Un'Goro",
        "ICECROWN" to "Knights of the Frozen Throne",
        "LOOTAPALOOZA" to "Kobolds & Catacombs",
        "GILNEAS" to "The Witchwood",
        "BOOMSDAY" to "The Boomsday Project",
        "TROLL" to "Rastakhan's Rumble",
        "DALARAN" to "Rise of Shadows",
        "ULDUM" to "Saviors of Uldum",
        "DRAGONS" to "Descent of Dragons",
        "YEAR_OF_THE_DRAGON" to "Galakrond's Awakening",
        "DEMON_HUNTER_INITIATE" to "Demon Hunter Initiate",
        "BLACK_TEMPLE" to "Ashes of Outland",
        "SCHOLOMANCE" to "Scholomance Academy",
        "DARKMOON_FAIRE" to "Madness at the Darkmoon Faire",
        "THE_BARRENS" to "Forged in the Barrens",
        "STORMWIND" to "United in Stormwind",
        "ALTERAC_VALLEY" to "Fractured in Alterac Valley",
        "THE_SUNKEN_CITY" to "Voyage to the Sunken City",
        "REVENDRETH" to "Murder at Castle Nathria",
        "RETURN_OF_THE_LICH_KING" to "March of the Lich King",
        "PATH_OF_ARTHAS" to "Path of Arthas",
        "BATTLE_OF_THE_BANDS" to "Festival of Legends",
        "TITANS" to "TITANS",
        "WILD_WEST" to "Showdown in the Badlands",
        "WONDERS" to "Caverns of Time",
        "WHIZBANGS_WORKSHOP" to "Whizbang's Workshop",
        "ISLAND_VACATION" to "Perils in Paradise",
        "SPACE" to "The Great Dark Beyond",
        "EMERALD_DREAM" to "Into the Emerald Dream",
        "THE_LOST_CITY" to "The Lost City of Un'Goro",
        "TIME_TRAVEL" to "Across the Timeways",
        "EVENT" to "Event",
        "TAVERNS_OF_TIME" to "Taverns of Time",
    )

    val freeSets: Set<String> = setOf("CORE", "BASIC")

    val nonConstructedSets: Set<String> = setOf(
        "HERO_SKINS", "TB", "MISSIONS", "CREDITS", "CHEAT", "DEMO", "NONE", "INVALID",
        "SLUSH", "TUTORIAL", "MERCENARIES", "LETTUCE", "BATTLEGROUNDS", "PLACEHOLDER_202204",
    )

    val wildSets: Set<String> = setOf(
        "LEGACY", "EXPERT1", "BASIC", "HOF", "NAXX", "GVG", "BRM", "TGT", "LOE", "OG", "KARA",
        "GANGS", "UNGORO", "ICECROWN", "LOOTAPALOOZA", "GILNEAS", "BOOMSDAY", "TROLL", "DALARAN",
        "ULDUM", "DRAGONS", "YEAR_OF_THE_DRAGON", "DEMON_HUNTER_INITIATE", "BLACK_TEMPLE",
        "SCHOLOMANCE", "DARKMOON_FAIRE", "THE_BARRENS", "STORMWIND", "ALTERAC_VALLEY",
        "THE_SUNKEN_CITY", "REVENDRETH", "RETURN_OF_THE_LICH_KING", "PATH_OF_ARTHAS",
        "BATTLE_OF_THE_BANDS", "TITANS", "WILD_WEST", "WONDERS", "WHIZBANGS_WORKSHOP",
        "ISLAND_VACATION", "SPACE", "EVENT", "TAVERNS_OF_TIME", "WILD_EVENT",
    )

    const val CLASSIC_SET = "VANILLA"

    fun displayName(set: String): String = names[set] ?: prettify(set)

    fun hasBuiltInName(set: String): Boolean = set in names

    fun isStandardByDefault(set: String): Boolean =
        set !in wildSets && set != CLASSIC_SET && set !in nonConstructedSets

    private fun prettify(set: String): String = set.split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }
        .ifBlank { set }
}

/** Manual overrides first, then what the current meta shows, then the built-in list (new sets count as Standard). */
class FormatRules(
    private val standardOverrides: Map<String, Boolean> = emptyMap(),
    private val detectedStandard: Set<String> = emptySet(),
    private val detectedWild: Set<String> = emptySet(),
) {
    fun isStandardSet(set: String): Boolean = standardOverrides[set] ?: when (set) {
        in detectedStandard -> true
        in detectedWild -> false
        else -> CardSets.isStandardByDefault(set)
    }

    fun isLegal(card: Card, format: GameFormat): Boolean = when (format) {
        GameFormat.STANDARD -> isStandardSet(card.set)
        GameFormat.CLASSIC -> card.set == CardSets.CLASSIC_SET
        GameFormat.WILD, GameFormat.TWIST -> card.set != CardSets.CLASSIC_SET
    }
}
