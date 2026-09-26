package com.stroexd.hsdecktracker.core.cards

/**
 * Set names from Hearthstone's own strings ("GLOBAL_CARD_SET_*"), so new sets get their real, translated name
 * without an app update. The string keys are short codes: the set code itself, its initials, or the prefix of
 * the set's card IDs (TIME_001 → TIME).
 */
object SetNames {
    private const val KEY_PREFIX = "GLOBAL_CARD_SET_"
    private val variants = listOf("_SHORT", "_SEARCHABLE_SHORTHAND_NAMES", "_RESERVE")
    private val aliases = mapOf("WILD_WEST" to "WST")

    /** The game calls this one "Classic" just like the Classic format's set. */
    private val builtIn = setOf("EXPERT1")
    private val fillerWords = setOf("OF", "THE")

    fun parseStrings(text: String): Map<String, String> = text.lineSequence()
        .filter { it.startsWith(KEY_PREFIX) }
        .mapNotNull { line ->
            val parts = line.split('\t')
            val key = parts[0].removePrefix(KEY_PREFIX)
            val name = parts.getOrNull(1)?.trim().orEmpty()
            if (name.isEmpty() || variants.any { key.endsWith(it) }) null else key to name
        }
        .toMap()

    fun resolve(strings: Map<String, String>, cards: List<Card>): Map<String, String> {
        val bySet = cards.groupBy { it.set } - builtIn
        val result = HashMap<String, String>()
        val claimed = HashSet<String>()
        for ((set, _) in bySet) {
            val key = namedKeys(set).firstOrNull { it in strings } ?: continue
            result[set] = strings.getValue(key)
            claimed += key
        }
        // Card ID prefixes are shared by small companion sets, so the larger set gets the name
        bySet.filterKeys { it !in result }
            .mapNotNull { (set, setCards) -> dominantPrefix(setCards)?.let { Triple(set, it.first, it.second) } }
            .filter { (_, prefix, _) -> prefix in strings && prefix !in claimed }
            .groupBy { it.second }
            .forEach { (prefix, candidates) -> result[candidates.maxBy { it.third }.first] = strings.getValue(prefix) }
        return result
    }

    private fun namedKeys(set: String): List<String> {
        val words = set.split('_').filter { it.isNotEmpty() }
        val initials = words.filter { it !in fillerWords }.joinToString("") { it.take(1) }
        return listOfNotNull(
            aliases[set],
            set,
            initials.takeIf { it.length >= 2 && words.size >= 2 },
            words.singleOrNull()?.take(3)?.takeIf { it.length == 3 },
        )
    }

    private fun dominantPrefix(cards: List<Card>): Pair<String, Int>? = cards
        .groupingBy { it.id.substringBefore('_').uppercase() }
        .eachCount()
        .maxByOrNull { it.value }
        ?.toPair()
}
