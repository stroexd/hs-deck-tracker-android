package com.stroexd.hsdecktracker.core.vision

import com.stroexd.hsdecktracker.core.util.normalizeForSearch

sealed interface CollectionEvent {
    /** Copies per card name (all printings) out of opened packs. */
    data class CardsReceived(val copies: Map<List<Int>, Int>) : CollectionEvent

    data class Disenchanted(val dbfIds: List<Int>, val copies: Int) : CollectionEvent

    data class Crafted(val dbfIds: List<Int>, val copies: Int) : CollectionEvent

    /** The "Disenchant extra cards" dialog closed; the screen doesn't tell whether it was confirmed. */
    data object MassDisenchantClosed : CollectionEvent
}

/** Menu texts of all Latin-script clients, taken from Hearthstone's own string tables. */
private object MenuTexts {
    private fun set(vararg texts: String) = texts.mapTo(HashSet()) { normalizeForSearch(it) }

    val packHeader = set(
        "Open Packs", "Packungen öffnen", "Packungen", "Ouvrir des paquets", "Paquets", "Abrir sobres", "Abrir paquetes",
        "Apri buste", "Buste", "Otwórz pakiety", "Pakiety", "Abrir pacotes",
    )
    /** The main menu has an "Open Packs" button too; these buttons tell it apart from the pack screen. */
    val mainMenu = set(
        "My Collection", "Meine Sammlung", "Sammlung", "Ma collection", "Mi colección", "Collezione", "Moja kolekcja",
        "Kolekcja", "Minha coleção", "Modes", "Modi", "Modos", "Modalità", "Inne tryby",
    )
    val done = set("Done", "Fertig", "Terminé", "Listo", "Fine", "Gotowe", "Pronto")
    val massOpened = set(
        "Packs Opened", "Packungen geöffnet", "paquets ouverts", "sobres abiertos", "paquetes abiertos", "Buste aperte",
        "Otwarte pakiety", "Pacotes abertos",
    )
    val summary = set("Summary", "Zusammenfassung", "Résumé", "Resumen", "Sommario", "Podsumowanie", "Resumo")
    val revealAll = set("Reveal All", "Alle enthüllen", "Tout découvrir", "Revelar todo", "Rivela tutto", "Odkryj wszystkie", "Revelar tudo")
    val disenchant = set("Disenchant", "Entzaubern", "Désenchanter", "Desencantar", "Disincanta", "Odczaruj")
    val create = set(
        "Create", "Herstellen", "Créer", "Crear", "Crea", "Stwórz", "Criar",
        "Upgrade", "Aufwerten", "Améliorer", "Mejorar", "Potenzia", "Ulepsz", "Aprimorar",
        "Create Upgrade", "Herstellen Aufwerten", "Créer Améliorer", "Crear Mejorar", "Crea Potenzia", "Stwórz Ulepsz", "Criar Aprimorar",
    )
    val undo = set("Undo", "Rückgängig", "Annuler", "Deshacer", "Annulla", "Cofnij", "Desfazer")
    val massDisenchant = set(
        "Mass Disenchant", "Massenentzauberung", "Massen entzauberung", "Désenchantement de masse", "Desencantar en masa",
        "Desencantamiento en masa", "Disincantamento di massa", "Masowe odczarowanie", "Desencantar em massa",
        "You will destroy", "Ihr zerstört", "Vous allez détruire", "Destruirás", "Distruggerai", "Zniszczysz", "Você destruirá",
    )

    fun isMassOpening(normalized: String): Boolean =
        normalized in summary || normalized in revealAll || massOpened.any { it in normalized }
}

/**
 * Follows Hearthstone's menus outside of games: opened packs, crafting and disenchanting.
 * Screens only count once two frames in a row agree.
 */
class CollectionWatcher(private val index: CardNameIndex) {
    private data class Screen(
        val packHeader: Boolean = false,
        val mainMenu: Boolean = false,
        val done: Boolean = false,
        val massOpening: Boolean = false,
        val disenchant: Boolean = false,
        val create: Boolean = false,
        val undo: Boolean = false,
        val massDisenchant: Boolean = false,
        val cards: List<List<Int>> = emptyList(),
        val focus: List<Int>? = null,
        val focusCopies: Int? = null,
    )

    /** The enlarged card in the crafting view and which buttons it shows. */
    private data class Crafting(val card: List<Int>, val disenchant: Boolean, val create: Boolean, val undo: Boolean, val copies: Int?)

    var decisionLog: ((String) -> Unit)? = null

    private var previous: Screen? = null
    private var packScreenUntil = 0L
    private var pack: List<List<Int>>? = null
    private var addedPack: List<List<Int>>? = null
    private var massOpening: CollectionScanner? = null
    private var massOpeningGone = 0
    private var massOpeningAdded = false
    private var crafting: Crafting? = null
    private var beforeAction: Crafting? = null
    private var pendingCopies = 0
    private var massDisenchantShown = false

    fun onFrame(frame: OcrFrame): List<CollectionEvent> {
        val screen = read(frame.lines)
        val events = mutableListOf<CollectionEvent>()
        followMassOpening(frame, screen, events)
        val stable = screen == previous
        previous = screen
        if (!stable) return events
        if (screen.packHeader && !screen.mainMenu) packScreenUntil = frame.timestamp + PACK_SCREEN_MS
        followPack(frame.timestamp, screen, events)
        followCrafting(screen, events)
        if (screen.massDisenchant) {
            massDisenchantShown = true
        } else if (massDisenchantShown) {
            massDisenchantShown = false
            events += CollectionEvent.MassDisenchantClosed
        }
        return events
    }

    private fun read(lines: List<OcrLine>): Screen {
        var screen = Screen()
        val menuLines = HashSet<OcrLine>()
        for (line in lines) {
            val text = normalizeForSearch(line.text)
            screen = when {
                text in MenuTexts.packHeader -> screen.copy(packHeader = true)
                text in MenuTexts.mainMenu -> screen.copy(mainMenu = true)
                text in MenuTexts.done -> screen.copy(done = true)
                MenuTexts.isMassOpening(text) -> screen.copy(massOpening = true)
                text in MenuTexts.disenchant -> screen.copy(disenchant = true)
                text in MenuTexts.create -> screen.copy(create = true)
                text in MenuTexts.undo -> screen.copy(undo = true)
                text in MenuTexts.massDisenchant -> screen.copy(massDisenchant = true)
                else -> continue
            }
            menuLines += line
        }
        val tiles = readCardTiles(index, lines) { it in menuLines }
        // The crafting view enlarges one card in front of the darkened collection
        val sorted = tiles.sortedByDescending { it.line.height }
        val focus = sorted.firstOrNull()?.takeIf { sorted.size == 1 || it.line.height >= FOCUS_RATIO * sorted[1].line.height }
        return screen.copy(
            cards = tiles.map { it.dbfIds }.sortedBy { it.first() },
            focus = focus?.dbfIds,
            focusCopies = focus?.copies?.takeIf { focus.copies > 1 },
        )
    }

    /** A single pack: its five cards are revealed once "Done" shows up and added when it is tapped. */
    private fun followPack(now: Long, screen: Screen, events: MutableList<CollectionEvent>) {
        if (screen.mainMenu || screen.cards.size > PACK_SIZE || screen.disenchant || screen.create) {
            packScreenUntil = 0
            pack = null
            return
        }
        if (now <= packScreenUntil && screen.done && !screen.massOpening) {
            if (screen.cards.size >= (pack?.size ?: 1)) pack = screen.cards
            return
        }
        val cards = pack
        pack = null
        // "Done" missed in one read must not add the same pack twice
        if (cards != null && cards != addedPack) {
            addedPack = cards
            decisionLog?.invoke("Pack: $cards")
            events += CollectionEvent.CardsReceived(cards.groupingBy { it }.eachCount())
        }
        if (screen.cards.isEmpty()) addedPack = null
    }

    /** Opening many packs at once ends in a summary that may need scrolling, like a collection page. */
    private fun followMassOpening(frame: OcrFrame, screen: Screen, events: MutableList<CollectionEvent>) {
        if (screen.packHeader && !screen.massOpening) massOpeningAdded = false
        if (screen.massOpening && !massOpeningAdded) {
            val scanner = massOpening ?: CollectionScanner(index, grid = false).also { massOpening = it }
            scanner.onFrame(frame)
            massOpeningGone = 0
            return
        }
        val scanner = massOpening ?: return
        if (++massOpeningGone < 3) return
        massOpening = null
        massOpeningAdded = true
        val copies = scanner.maxima()
        if (copies.isEmpty()) return
        decisionLog?.invoke("Packs: $copies")
        events += CollectionEvent.CardsReceived(copies)
    }

    /**
     * Hearthstone offers "Undo" right after crafting or disenchanting, so an action only counts once the card
     * is closed. Which button was used shows in which one gave way to "Undo", or else in the copy badge.
     */
    private fun followCrafting(screen: Screen, events: MutableList<CollectionEvent>) {
        val focus = screen.focus
        val now = if (focus != null && !screen.massDisenchant && (screen.disenchant || screen.create || screen.undo)) {
            Crafting(focus, screen.disenchant, screen.create, screen.undo, screen.focusCopies)
        } else {
            null
        }
        val before = crafting
        crafting = now
        if (before != null && before.card != now?.card) {
            commitCrafting(before.card, events)
            if (now == null) return
        }
        if (now == null || before == null || now.card != before.card) return
        when {
            !before.undo && now.undo -> {
                beforeAction = before
                pendingCopies += actionCopies(before, now)
            }
            before.undo && now.undo && before.copies != null && now.copies != null -> pendingCopies += now.copies - before.copies
            before.undo && !now.undo && now.copy(undo = false) == beforeAction?.copy(undo = false) -> {
                decisionLog?.invoke("Crafting undone: ${now.card}")
                pendingCopies = 0
                beforeAction = null
            }
        }
    }

    private fun actionCopies(before: Crafting, after: Crafting): Int = when {
        before.disenchant && !after.disenchant && after.create -> -1
        before.create && !after.create && after.disenchant -> 1
        before.copies != null && after.copies != null -> after.copies - before.copies
        else -> 0.also { decisionLog?.invoke("Crafting action unclear: ${after.card}") }
    }

    private fun commitCrafting(card: List<Int>, events: MutableList<CollectionEvent>) {
        val copies = pendingCopies
        pendingCopies = 0
        beforeAction = null
        if (copies == 0) return
        decisionLog?.invoke("Crafting: $card $copies")
        events += if (copies > 0) CollectionEvent.Crafted(card, copies) else CollectionEvent.Disenchanted(card, -copies)
    }

    private companion object {
        const val PACK_SIZE = 5
        const val PACK_SCREEN_MS = 10 * 60_000L
        const val FOCUS_RATIO = 1.4f
    }
}
