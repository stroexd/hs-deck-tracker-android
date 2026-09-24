@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.stroexd.hsdecktracker.ui.decks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.collection.CardCollection
import com.stroexd.hsdecktracker.core.collection.CraftAnalysis
import com.stroexd.hsdecktracker.core.collection.CraftingCalculator
import com.stroexd.hsdecktracker.core.data.AppSettings
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.deck.DeckAnalysis
import com.stroexd.hsdecktracker.core.deck.DeckIssue
import com.stroexd.hsdecktracker.core.deck.DeckSummary
import com.stroexd.hsdecktracker.core.stats.MatchRecord
import com.stroexd.hsdecktracker.core.stats.StatsCalculator
import com.stroexd.hsdecktracker.core.util.formatPercent
import com.stroexd.hsdecktracker.overlay.rememberOverlayStarter
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.Routes
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.components.CollectionCardDialog
import com.stroexd.hsdecktracker.ui.components.ConfirmDialog
import com.stroexd.hsdecktracker.ui.components.CraftSummaryCard
import com.stroexd.hsdecktracker.ui.components.DeckSummaryCard
import com.stroexd.hsdecktracker.ui.components.EmptyState
import com.stroexd.hsdecktracker.ui.components.IssuesCard
import com.stroexd.hsdecktracker.ui.components.SectionHeader
import com.stroexd.hsdecktracker.ui.components.deckCardItems
import com.stroexd.hsdecktracker.ui.copyToClipboard
import com.stroexd.hsdecktracker.ui.shareText
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.theme.winRateColor
import com.stroexd.hsdecktracker.ui.toast
import kotlinx.coroutines.launch

@Composable
fun DeckDetailScreen(navController: NavHostController, deckId: String) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val decks by container.decks.decks.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val collection by container.collection.collection.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val matches by container.matches.matches.collectAsStateWithLifecycle()
    val deck = decks.firstOrNull { it.id == deckId }
    val startOverlay = rememberOverlayStarter()

    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selectedCard by remember { mutableStateOf<Card?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deck?.name ?: "Deck", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (deck != null) {
                        IconButton(onClick = { scope.launch { container.decks.upsert(deck.copy(favorite = !deck.favorite)) } }) {
                            Icon(
                                if (deck.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "Favorit",
                                tint = if (deck.favorite) HsColors.Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { context.copyToClipboard("Deck-Code", deck.deckCode(), "Deck-Code kopiert – in Hearthstone „Neues Deck“ wählen") }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Deck-Code kopieren")
                        }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Mehr") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Bearbeiten") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { menuOpen = false; navController.navigate(Routes.builder(id = deck.id)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Teilen") },
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                onClick = { menuOpen = false; context.shareText(deck.name, DeckAnalysis.exportText(deck, cardState.db)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Karten zur Sammlung hinzufügen") },
                                leadingIcon = { Icon(Icons.Filled.Inventory2, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        val all = deck.cards.toMutableMap()
                                        deck.sideboards.forEach { all.merge(it.dbfId, it.count, Int::plus) }
                                        container.collection.addDeck(all)
                                        context.toast("Alle Karten als besessen markiert")
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Löschen") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (deck == null) {
            EmptyState(
                icon = Icons.Filled.SearchOff,
                title = "Deck nicht gefunden",
                message = "Das Deck wurde gelöscht oder existiert nicht mehr.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        val data = rememberDeckDetailData(deck, cardState.db, collection, settings)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            deckDetailContent(
                deck = deck,
                db = cardState.db,
                data = data,
                collection = collection,
                matches = matches.filter { it.deckId == deck.id },
                onCardClick = { selectedCard = it },
                header = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(
                            onClick = {
                                container.tracker.start(deck)
                                navController.navigate(Routes.TRACKER)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Tracker")
                        }
                        FilledTonalButton(
                            onClick = {
                                container.tracker.start(deck)
                                startOverlay()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Layers, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Overlay")
                        }
                    }
                },
            )
        }
    }

    selectedCard?.let { card ->
        CollectionCardDialog(
            card = card,
            onDismiss = { selectedCard = null },
            copiesInDeck = deck?.cards?.get(card.dbfId),
            deckSize = deck?.let { DeckAnalysis.expectedSize(it.cards, cardState.db) } ?: 30,
        )
    }

    if (confirmDelete && deck != null) {
        ConfirmDialog(
            title = "Deck löschen?",
            message = "„${deck.name}“ wird entfernt. Gespielte Partien bleiben in der Statistik erhalten.",
            confirmLabel = "Löschen",
            onConfirm = {
                scope.launch {
                    container.decks.delete(deck.id)
                    navController.popBackStack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Vorberechnete Daten für die Deck-Detailansicht. */
data class DeckDetailData(
    val analysis: CraftAnalysis,
    val summary: DeckSummary,
    val issues: List<DeckIssue>,
    val expectedSize: Int,
)

@Composable
fun rememberDeckDetailData(deck: Deck, db: CardDatabase, collection: CardCollection, settings: AppSettings): DeckDetailData =
    remember(deck, db, collection, settings.coreSetOwned, settings.standardSetOverrides) {
        DeckDetailData(
            analysis = CraftingCalculator.analyze(deck.cards, deck.sideboards, collection, db, settings.collectionOptions),
            summary = DeckAnalysis.summary(deck.cards, db),
            issues = DeckAnalysis.validate(deck, db, settings.formatRules),
            expectedSize = DeckAnalysis.expectedSize(deck.cards, db),
        )
    }

/**
 * Gemeinsamer Inhalt der Deck-Detailansicht (eigene Decks und Meta-Decks).
 */
fun LazyListScope.deckDetailContent(
    deck: Deck,
    db: CardDatabase,
    data: DeckDetailData,
    collection: CardCollection,
    matches: List<MatchRecord>,
    onCardClick: (Card) -> Unit,
    header: @Composable () -> Unit,
    extraInfo: (@Composable () -> Unit)? = null,
) {
    item(key = "header") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ClassBadge(deck.heroClass)
                Text(deck.format.displayName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically))
                deck.archetype?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
            extraInfo?.invoke()
            header()
        }
    }
    item(key = "details") {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IssuesCard(data.issues)
            CraftSummaryCard(
                analysis = data.analysis,
                collectionEmpty = collection.isEmpty,
                dust = collection.dust,
                onCardClick = onCardClick,
            )
            DeckSummaryCard(data.summary, data.expectedSize)
            if (matches.isNotEmpty()) DeckStatsCard(matches)
        }
    }
    item(key = "cards-header") {
        SectionHeader("Karten", Modifier.padding(horizontal = 16.dp))
    }
    deckCardItems(deck.cards, deck.sideboards, db, data.analysis, onCardClick)
    if (deck.notes.isNotBlank()) {
        item(key = "notes") {
            Column(Modifier.padding(16.dp)) {
                SectionHeader("Notizen")
                Text(deck.notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DeckStatsCard(matches: List<MatchRecord>) {
    val overall = StatsCalculator.overall(matches)
    val byClass = StatsCalculator.byOpponentClass(matches)
    M3Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Deine Bilanz", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "${overall.label} · ${formatPercent(overall.rate)}",
                    color = winRateColor(overall.rate),
                    fontWeight = FontWeight.Bold,
                )
            }
            byClass.entries.take(6).forEach { (cls, rate) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClassBadge(cls)
                    Spacer(Modifier.weight(1f))
                    Text("${rate.label} · ${formatPercent(rate.rate, 0)}", color = winRateColor(rate.rate), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
