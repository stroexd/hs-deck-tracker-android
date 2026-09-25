package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.collection.CardCollection
import com.stroexd.hsdecktracker.core.collection.ChangedCard
import com.stroexd.hsdecktracker.core.collection.CollectionChanges
import com.stroexd.hsdecktracker.core.collection.OwnedCard
import com.stroexd.hsdecktracker.core.collection.ReceivedStatus
import com.stroexd.hsdecktracker.core.vision.CardNameIndex
import com.stroexd.hsdecktracker.core.vision.CollectionEvent
import com.stroexd.hsdecktracker.core.vision.CollectionWatcher
import com.stroexd.hsdecktracker.core.vision.OcrFrame
import com.stroexd.hsdecktracker.core.vision.OcrLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionWatcherTest {
    private val db = TestCards.db
    private val index = CardNameIndex(db.deckCards.map { it.dbfId to it.name })
    private var time = 0L

    private fun line(text: String, x: Float, y: Float, h: Float = 0.03f, w: Float = 0.14f) =
        OcrLine(text, x - w / 2, y - h / 2, x + w / 2, y + h / 2)

    /** Every screen is shown for two frames, like a real capture of a still screen. */
    private fun CollectionWatcher.show(vararg lines: OcrLine): List<CollectionEvent> =
        (1..2).flatMap { onFrame(OcrFrame(time++, lines.toList())) }

    private val packHeader = line("Packungen öffnen", 0.15f, 0.05f)
    private val done = line("Fertig", 0.5f, 0.92f, w = 0.08f)

    private val revealedPack = arrayOf(
        line("Feuerball", 0.3f, 0.3f), line("Seltener Diener", 0.7f, 0.3f),
        line("Feuerball", 0.2f, 0.65f), line("Leeroy Jenkins", 0.5f, 0.65f), line("Epischer Zauber", 0.8f, 0.65f),
        done,
    )

    @Test
    fun addsAPackWhenDoneIsTapped() {
        val watcher = CollectionWatcher(index)
        watcher.show(packHeader)
        assertTrue(watcher.show(*revealedPack).isEmpty())
        val events = watcher.show(packHeader)
        val expected = mapOf(
            listOf(TestCards.FIREBALL) to 2,
            listOf(TestCards.RARE_NEUTRAL) to 1,
            listOf(TestCards.LEEROY) to 1,
            listOf(TestCards.EPIC_MAGE) to 1,
        )
        assertEquals(listOf<CollectionEvent>(CollectionEvent.CardsReceived(expected)), events)
    }

    @Test
    fun aMissedDoneDoesNotAddThePackTwice() {
        val watcher = CollectionWatcher(index)
        watcher.show(packHeader)
        watcher.show(*revealedPack)
        val first = watcher.show(*revealedPack.dropLast(1).toTypedArray())
        watcher.show(*revealedPack)
        val second = watcher.show(packHeader)
        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
    }

    @Test
    fun noPacksOutsideThePackScreen() {
        val watcher = CollectionWatcher(index)
        watcher.show(*revealedPack)
        assertTrue(watcher.show(packHeader).isEmpty())
    }

    @Test
    fun theOpenPacksButtonInTheMainMenuIsNotThePackScreen() {
        val watcher = CollectionWatcher(index)
        watcher.show(line("Packungen öffnen", 0.83f, 0.64f), line("Meine Sammlung", 0.8f, 0.86f), line("Modi", 0.5f, 0.76f))
        watcher.show(*revealedPack)
        assertTrue(watcher.show(line("Modi", 0.5f, 0.76f)).isEmpty())
    }

    private val background = arrayOf(line("Alte Karte", 0.12f, 0.3f), line("Basiskarte", 0.85f, 0.3f))
    private fun craftingView(vararg buttons: String) = arrayOf(
        *background,
        line("Prinz Renathal", 0.5f, 0.45f, h = 0.06f, w = 0.25f),
        *buttons.mapIndexed { i, text -> line(text, 0.3f + i * 0.4f, 0.85f, w = 0.12f) }.toTypedArray(),
    )

    @Test
    fun disenchantCountsWhenTheCardIsClosed() {
        val watcher = CollectionWatcher(index)
        watcher.show(*craftingView("Entzaubern", "Herstellen"))
        assertTrue(watcher.show(*craftingView("Rückgängig", "Herstellen")).isEmpty())
        val events = watcher.show(*background)
        assertEquals(listOf<CollectionEvent>(CollectionEvent.Disenchanted(listOf(TestCards.RENATHAL), 1)), events)
    }

    @Test
    fun craftingIsRecognizedByTheButtonThatTurnedIntoUndo() {
        val watcher = CollectionWatcher(index)
        watcher.show(*craftingView("Entzaubern", "Herstellen"))
        watcher.show(*craftingView("Entzaubern", "Rückgängig"))
        assertEquals(listOf<CollectionEvent>(CollectionEvent.Crafted(listOf(TestCards.RENATHAL), 1)), watcher.show(*background))
    }

    @Test
    fun undoInHearthstoneCancelsTheAction() {
        val watcher = CollectionWatcher(index)
        watcher.show(*craftingView("Entzaubern", "Herstellen"))
        watcher.show(*craftingView("Rückgängig", "Herstellen"))
        watcher.show(*craftingView("Entzaubern", "Herstellen"))
        assertTrue(watcher.show(*background).isEmpty())
    }

    @Test
    fun reportsWhenTheMassDisenchantDialogCloses() {
        val watcher = CollectionWatcher(index)
        assertTrue(watcher.show(line("Massenentzauberung", 0.5f, 0.2f), line("Ihr zerstört:", 0.5f, 0.3f)).isEmpty())
        assertEquals(listOf<CollectionEvent>(CollectionEvent.MassDisenchantClosed), watcher.show(*background))
    }

    @Test
    fun readsTheSummaryAfterOpeningManyPacks() {
        val watcher = CollectionWatcher(index)
        val summary = arrayOf(
            line("12 Packungen geöffnet", 0.5f, 0.05f, w = 0.3f),
            line("Feuerball", 0.3f, 0.3f), line("x3", 0.3f, 0.47f, w = 0.04f),
            line("Seltener Diener", 0.6f, 0.3f),
        )
        watcher.show(*summary)
        watcher.show(*summary)
        val events = (0..3).flatMap { watcher.onFrame(OcrFrame(time++, listOf(packHeader))) }
        assertEquals(
            listOf<CollectionEvent>(CollectionEvent.CardsReceived(mapOf(listOf(TestCards.FIREBALL) to 3, listOf(TestCards.RARE_NEUTRAL) to 1))),
            events,
        )
    }

    @Test
    fun marksNewCopiesAndDuplicates() {
        val collection = CardCollection(mapOf(TestCards.RARE_NEUTRAL to OwnedCard(normal = 2), TestCards.EPIC_MAGE to OwnedCard(normal = 1)))
        val change = CollectionChanges.receive(
            collection,
            mapOf(listOf(TestCards.FIREBALL) to 1, listOf(TestCards.EPIC_MAGE) to 1, listOf(TestCards.RARE_NEUTRAL) to 1),
            db,
        )
        assertEquals(
            listOf(
                ChangedCard(TestCards.FIREBALL, 1, ReceivedStatus.NEW),
                ChangedCard(TestCards.EPIC_MAGE, 1, ReceivedStatus.COPY),
                ChangedCard(TestCards.RARE_NEUTRAL, 1, ReceivedStatus.DUPLICATE),
            ),
            change.cards,
        )
        assertEquals(3, change.collection.owned(TestCards.RARE_NEUTRAL))
    }

    @Test
    fun disenchantRemovesNormalCopiesFirstAndAddsDust() {
        val collection = CardCollection(mapOf(TestCards.RARE_NEUTRAL to OwnedCard(normal = 1, golden = 1)), dust = 100)
        val change = CollectionChanges.disenchant(collection, listOf(TestCards.RARE_NEUTRAL), 1, db)
        assertEquals(OwnedCard(golden = 1), change.collection.cards[TestCards.RARE_NEUTRAL])
        assertEquals(120, change.collection.dust)
    }

    @Test
    fun freeCardsCannotBeDisenchanted() {
        val collection = CardCollection(mapOf(TestCards.ARCANE_MISSILES to OwnedCard(normal = 2)))
        assertTrue(CollectionChanges.disenchant(collection, listOf(TestCards.ARCANE_MISSILES), 1, db).isEmpty)
    }

    @Test
    fun craftingSpendsDust() {
        val change = CollectionChanges.craft(CardCollection(dust = 2000), listOf(TestCards.RENATHAL), 1, db, FormatRules())
        assertEquals(1, change.collection.owned(TestCards.RENATHAL))
        assertEquals(400, change.collection.dust)
    }

    @Test
    fun massDisenchantKeepsAPlayset() {
        val collection = CardCollection(
            mapOf(TestCards.FIREBALL to OwnedCard(normal = 3, golden = 1), TestCards.LEEROY to OwnedCard(normal = 1)),
        )
        val change = CollectionChanges.withoutExtras(collection, db)
        assertEquals(OwnedCard(normal = 1, golden = 1), change.collection.cards[TestCards.FIREBALL])
        assertEquals(OwnedCard(normal = 1), change.collection.cards[TestCards.LEEROY])
        assertEquals(10, change.dust)
    }
}
