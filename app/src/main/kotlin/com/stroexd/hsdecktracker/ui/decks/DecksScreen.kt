@file:OptIn(ExperimentalMaterial3Api::class)

package com.stroexd.hsdecktracker.ui.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.collection.CraftingCalculator
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.deck.DeckTextParser
import com.stroexd.hsdecktracker.core.stats.StatsCalculator
import com.stroexd.hsdecktracker.core.stats.WinRate
import com.stroexd.hsdecktracker.overlay.rememberTrackingStarter
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.Routes
import com.stroexd.hsdecktracker.ui.components.Banner
import com.stroexd.hsdecktracker.ui.components.ChipRow
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.components.ClassPickerDialog
import com.stroexd.hsdecktracker.ui.components.CraftCostLabel
import com.stroexd.hsdecktracker.ui.components.EmptyState
import com.stroexd.hsdecktracker.ui.navigateTopLevel
import com.stroexd.hsdecktracker.ui.readClipboardText
import com.stroexd.hsdecktracker.ui.rememberComputed
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.theme.uiColor
import com.stroexd.hsdecktracker.ui.theme.winRateColor
import com.stroexd.hsdecktracker.core.util.formatPercent
import kotlinx.coroutines.launch

private enum class DeckSort(val label: String) { RECENT("Zuletzt"), NAME("Name"), DUST("Staubkosten"), WINRATE("Siegquote") }

private data class DeckRow(val deck: Deck, val analysis: com.stroexd.hsdecktracker.core.collection.CraftAnalysis, val winRate: WinRate)

@Composable
fun DecksScreen(
    navController: NavHostController,
    sharedText: String?,
    onSharedTextHandled: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val decks by container.decks.decks.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val collection by container.collection.collection.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val matches by container.matches.matches.collectAsStateWithLifecycle()
    val recognition by container.recognition.collectAsStateWithLifecycle()
    val startTracking = rememberTrackingStarter()

    var formatFilter by rememberSaveable { mutableStateOf<GameFormat?>(null) }
    var sort by rememberSaveable { mutableStateOf(DeckSort.RECENT) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var importInitialText by remember { mutableStateOf("") }
    var showClassPicker by rememberSaveable { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            importInitialText = sharedText
            showImport = true
            onSharedTextHandled()
        }
    }

    val rows by rememberComputed(decks, cardState.db, collection, settings.coreSetOwned, matches, initial = emptyList<DeckRow>()) {
        val byDeck = matches.groupBy { it.deckId }
        decks.map { deck ->
            DeckRow(
                deck = deck,
                analysis = CraftingCalculator.analyze(deck.cards, deck.sideboards, collection, cardState.db, settings.collectionOptions),
                winRate = StatsCalculator.overall(byDeck[deck.id].orEmpty()),
            )
        }
    }
    val visible = rows
        .filter { formatFilter == null || it.deck.format == formatFilter }
        .let { list ->
            when (sort) {
                DeckSort.RECENT -> list.sortedWith(compareByDescending<DeckRow> { it.deck.favorite }.thenByDescending { it.deck.updatedAt })
                DeckSort.NAME -> list.sortedBy { it.deck.name.lowercase() }
                DeckSort.DUST -> list.sortedBy { it.analysis.dustCost + it.analysis.uncraftableMissing * 100_000 }
                DeckSort.WINRATE -> list.sortedByDescending { it.winRate.rate ?: -1.0 }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meine Decks") },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.TRACKER) }) {
                        Icon(Icons.Filled.Layers, contentDescription = "Tracker")
                    }
                    IconButton(onClick = { navController.navigate(Routes.CRAFT_CHECK) }) {
                        Icon(Icons.Filled.Calculate, contentDescription = "Deck-Code prüfen")
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { showAddMenu = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Deck hinzufügen") },
                )
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Deck-Code importieren") },
                        leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            importInitialText = context.readClipboardText()?.takeIf { DeckTextParser.parseFirst(it) != null }.orEmpty()
                            showImport = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Neues Deck bauen") },
                        leadingIcon = { Icon(Icons.Filled.Build, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            showClassPicker = true
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                PlayCard(
                    active = recognition.active,
                    status = recognition.phaseLabel,
                    onPlay = startTracking,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
            if (cardState.loading && cardState.db.isEmpty) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text("Kartendatenbank wird geladen …", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            cardState.error?.let { error ->
                item {
                    Banner(
                        error,
                        isError = true,
                        actionLabel = "Erneut",
                        onAction = { container.refreshCards() },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (decks.isNotEmpty()) {
                item {
                    ChipRow(
                        options = listOf<GameFormat?>(null, GameFormat.STANDARD, GameFormat.WILD, GameFormat.TWIST),
                        isSelected = { it == formatFilter },
                        label = { it?.displayName ?: "Alle" },
                        onClick = { formatFilter = it },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item {
                    ChipRow(
                        options = DeckSort.entries.toList(),
                        isSelected = { it == sort },
                        label = { "↕ ${it.label}" },
                        onClick = { sort = it },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            if (decks.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Style,
                        title = "Noch keine Decks",
                        message = "Importiere einen Deck-Code (z. B. von HSReplay, HearthPwn oder aus Hearthstone kopiert), " +
                            "baue ein eigenes Deck oder übernimm ein Meta-Deck.",
                        actions = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    importInitialText = context.readClipboardText()?.takeIf { DeckTextParser.parseFirst(it) != null }.orEmpty()
                                    showImport = true
                                }) { Text("Deck-Code importieren") }
                                OutlinedButton(onClick = { navController.navigateTopLevel(Routes.META) }) { Text("Meta-Decks ansehen") }
                            }
                        },
                    )
                }
            }
            items(visible, key = { it.deck.id }) { row ->
                DeckListItem(
                    row = row,
                    collectionEmpty = collection.isEmpty,
                    dust = collection.dust,
                    onClick = { navController.navigate(Routes.deck(row.deck.id)) },
                )
            }
        }
    }

    if (showImport) {
        ImportDeckDialog(
            initialText = importInitialText,
            onDismiss = { showImport = false },
            onImport = { text ->
                scope.launch {
                    val imported = container.decks.importFromText(text, container.cards.db)
                    showImport = false
                    snackbar.showSnackbar(
                        when (imported.size) {
                            0 -> "Kein gültiger Deck-Code gefunden."
                            1 -> "„${imported.first().name}“ importiert."
                            else -> "${imported.size} Decks importiert."
                        },
                    )
                }
            },
        )
    }
    if (showClassPicker) {
        ClassPickerDialog(
            title = "Klasse wählen",
            onDismiss = { showClassPicker = false },
            onPick = { cls ->
                showClassPicker = false
                navController.navigate(Routes.builder(cls = cls, format = GameFormat.STANDARD))
            },
        )
    }
}

/** Einstieg „Spielen & tracken“: Hearthstone starten, alles Weitere erkennt die App selbst. */
@Composable
private fun PlayCard(active: Boolean, status: String, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onPlay,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = HsColors.Gold,
                modifier = Modifier.width(40.dp).height(40.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (active) "Tracker aktiv" else "Spielen & automatisch tracken",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    if (active) "$status – tippen, um Hearthstone zu öffnen"
                    else "Startet Hearthstone. Spielstart, dein Deck und die Karten des Gegners werden erkannt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun DeckListItem(row: DeckRow, collectionEmpty: Boolean, dust: Int, onClick: () -> Unit) {
    val deck = row.deck
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(deck.heroClass.uiColor))
            Column(Modifier.weight(1f).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (deck.favorite) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = HsColors.Gold, modifier = Modifier.padding(end = 4.dp))
                    }
                    Text(
                        deck.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CraftCostLabel(row.analysis, collectionEmpty, dust)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClassBadge(deck.heroClass)
                    Text(
                        "${deck.format.displayName} · ${deck.cardCount} Karten",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.winRate.games > 0) {
                        Text(
                            "${row.winRate.label} · ${formatPercent(row.winRate.rate, 0)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = winRateColor(row.winRate.rate),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImportDeckDialog(initialText: String, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(initialText) }
    val found = remember(text) { DeckTextParser.parseAll(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deck-Code importieren") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Füge einen oder mehrere Deck-Codes ein – z. B. aus Hearthstone („Deck kopieren“), HSReplay oder HearthPwn.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                    placeholder = { Text("AAECA…") },
                )
                TextButton(onClick = { context.readClipboardText()?.let { text = it } }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Aus Zwischenablage einfügen")
                }
                Text(
                    when (found.size) {
                        0 -> if (text.isBlank()) "" else "Kein gültiger Deck-Code erkannt."
                        1 -> "1 Deck erkannt" + (found.first().name?.let { ": $it" } ?: "")
                        else -> "${found.size} Decks erkannt"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (found.isEmpty()) HsColors.Warning else HsColors.Win,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onImport(text) }, enabled = found.isNotEmpty()) { Text("Importieren") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
