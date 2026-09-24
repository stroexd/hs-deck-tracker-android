@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.stroexd.hsdecktracker.ui.decks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.AppContainer
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardFilter
import com.stroexd.hsdecktracker.core.cards.CardSearch
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.collection.CraftingCalculator
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.deck.DeckAnalysis
import com.stroexd.hsdecktracker.core.deck.DeckSource
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.Routes
import com.stroexd.hsdecktracker.ui.components.CardTile
import com.stroexd.hsdecktracker.ui.components.ChipRow
import com.stroexd.hsdecktracker.ui.components.CollectionCardDialog
import com.stroexd.hsdecktracker.ui.components.ConfirmDialog
import com.stroexd.hsdecktracker.ui.components.CraftSummaryCard
import com.stroexd.hsdecktracker.ui.components.DeckSummaryCard
import com.stroexd.hsdecktracker.ui.components.DustLabel
import com.stroexd.hsdecktracker.ui.components.IssuesCard
import com.stroexd.hsdecktracker.ui.components.ManaGem
import com.stroexd.hsdecktracker.ui.components.SearchField
import com.stroexd.hsdecktracker.ui.rememberComputed
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.theme.uiColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeckBuilderViewModel(
    private val container: AppContainer,
    deckId: String?,
    initialClass: HsClass?,
    initialFormat: GameFormat?,
) : ViewModel() {
    private val existing: Deck? = deckId?.let { container.decks.get(it) }
    private var original: Deck = existing ?: Deck(
        name = "${(initialClass ?: HsClass.MAGE).displayName}-Deck",
        heroClass = initialClass ?: HsClass.MAGE,
        format = initialFormat ?: GameFormat.STANDARD,
        source = DeckSource.USER,
    )
    private val _draft = MutableStateFlow(original)
    val draft: StateFlow<Deck> = _draft.asStateFlow()

    val isNew: Boolean get() = existing == null
    val isDirty: Boolean get() = _draft.value != original

    fun add(card: Card) {
        _draft.update { deck ->
            val current = deck.cards[card.dbfId] ?: 0
            val db = container.cards.db
            val limit = DeckAnalysis.expectedSize(deck.cards + (card.dbfId to 1), db)
            if (current >= card.maxCopies || deck.cardCount >= limit) deck else deck.withCard(card.dbfId, 1)
        }
    }

    fun remove(dbfId: Int) = _draft.update { it.withCard(dbfId, -1) }

    fun rename(name: String) = _draft.update { it.copy(name = name) }

    fun setFormat(format: GameFormat) = _draft.update { it.copy(format = format) }

    suspend fun save(): Deck {
        val saved = container.decks.upsert(_draft.value.copy(name = _draft.value.name.ifBlank { "Neues Deck" }))
        original = saved
        _draft.value = saved
        return saved
    }
}

@Composable
fun DeckBuilderScreen(
    navController: NavHostController,
    deckId: String?,
    initialClass: HsClass?,
    initialFormat: GameFormat?,
) {
    val container = LocalAppContainer.current
    val vm: DeckBuilderViewModel = viewModel { DeckBuilderViewModel(container, deckId, initialClass, initialFormat) }
    val scope = rememberCoroutineScope()
    val deck by vm.draft.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val collection by container.collection.collection.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val db = cardState.db

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var costs by remember { mutableStateOf(emptySet<Int>()) }
    var showAllClasses by rememberSaveable { mutableStateOf(false) }
    var onlyOwned by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var detailCard by remember { mutableStateOf<Card?>(null) }

    val candidates by rememberComputed(db, query, costs, showAllClasses, deck.heroClass, deck.format, onlyOwned, collection, settings, initial = emptyList<Card>()) {
        val filter = CardFilter(
            query = query,
            classes = if (showAllClasses) emptySet() else setOf(deck.heroClass, HsClass.NEUTRAL),
            costs = costs,
            format = deck.format,
            ownership = if (onlyOwned) com.stroexd.hsdecktracker.core.cards.Ownership.OWNED else com.stroexd.hsdecktracker.core.cards.Ownership.ALL,
        )
        CardSearch.filter(db.deckCards, filter, settings.formatRules, collection, settings.collectionOptions)
            .sortedWith(compareBy<Card>({ it.isNeutral }, { it.cost }, { it.name }))
    }
    val analysis = remember(deck, db, collection, settings.coreSetOwned) {
        CraftingCalculator.analyze(deck.cards, deck.sideboards, collection, db, settings.collectionOptions)
    }
    val expected = remember(deck.cards, db) { DeckAnalysis.expectedSize(deck.cards, db) }

    fun leave() {
        if (vm.isDirty) confirmLeave = true else navController.popBackStack()
    }
    BackHandler(enabled = vm.isDirty) { confirmLeave = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "Neues Deck" else "Deck bearbeiten") },
                navigationIcon = {
                    IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val saved = vm.save()
                            navController.popBackStack()
                            if (deckId == null) navController.navigate(Routes.deck(saved.id))
                        }
                    }) { Icon(Icons.Filled.Check, contentDescription = "Speichern") }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(deck.heroClass.uiColor))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${deck.cardCount}/$expected Karten",
                        fontWeight = FontWeight.Bold,
                        color = if (deck.cardCount == expected) HsColors.Win else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!collection.isEmpty && !analysis.isComplete) {
                        Text("Fehlt: ", style = MaterialTheme.typography.labelMedium)
                        DustLabel(analysis.dustCost)
                        Spacer(Modifier.width(12.dp))
                    }
                    Button(onClick = {
                        scope.launch {
                            val saved = vm.save()
                            navController.popBackStack()
                            if (deckId == null) navController.navigate(Routes.deck(saved.id))
                        }
                    }) { Text("Speichern") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Karten") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Deck (${deck.cardCount})") })
            }
            if (tab == 0) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SearchField(query, { query = it }, placeholder = "Karte, Text, Stamm …")
                }
                ChipRow(
                    options = (0..7).toList(),
                    isSelected = { it in costs },
                    label = { if (it == 7) "7+" else it.toString() },
                    onClick = { c -> costs = if (c in costs) costs - c else costs + c },
                )
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = showAllClasses, onClick = { showAllClasses = !showAllClasses }, label = { Text("Alle Klassen") })
                    FilterChip(selected = onlyOwned, onClick = { onlyOwned = !onlyOwned }, label = { Text("Nur besessene") })
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(candidates, key = { it.dbfId }) { card ->
                        BuilderCardRow(
                            card = card,
                            inDeck = deck.cards[card.dbfId] ?: 0,
                            owned = if (collection.isEmpty) null else CraftingCalculator.ownedCopies(card, collection, settings.collectionOptions),
                            onAdd = { vm.add(card) },
                            onInfo = { detailCard = card },
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = deck.name,
                                onValueChange = vm::rename,
                                label = { Text("Deckname") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ChipRow(
                                options = listOf(GameFormat.STANDARD, GameFormat.WILD, GameFormat.TWIST),
                                isSelected = { it == deck.format },
                                label = { it.displayName },
                                onClick = vm::setFormat,
                                contentPadding = PaddingValues(0.dp),
                            )
                            IssuesCard(remember(deck, db, settings.standardSetOverrides) { DeckAnalysis.validate(deck, db, settings.formatRules) })
                            DeckSummaryCard(remember(deck.cards, db) { DeckAnalysis.summary(deck.cards, db) }, expected)
                            CraftSummaryCard(analysis, collection.isEmpty, collection.dust, onCardClick = { detailCard = it })
                        }
                    }
                    val entries = DeckAnalysis.entries(deck.cards, db)
                    items(entries, key = { it.dbfId }) { entry ->
                        CardTile(
                            card = entry.card,
                            name = entry.name,
                            cost = entry.cost,
                            count = entry.count,
                            missing = analysis.missing.firstOrNull { it.dbfId == entry.dbfId }?.missing ?: 0,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            onClick = entry.card?.let { card -> { detailCard = card } },
                            trailing = {
                                Row {
                                    IconButton(onClick = { vm.remove(entry.dbfId) }) { Icon(Icons.Filled.Remove, contentDescription = "Entfernen") }
                                    entry.card?.let { card ->
                                        IconButton(onClick = { vm.add(card) }) { Icon(Icons.Filled.Add, contentDescription = "Hinzufügen") }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    detailCard?.let { card ->
        CollectionCardDialog(card = card, onDismiss = { detailCard = null }, copiesInDeck = deck.cards[card.dbfId], deckSize = expected)
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = "Änderungen verwerfen?",
            message = "Das Deck wurde noch nicht gespeichert.",
            confirmLabel = "Verwerfen",
            onConfirm = { navController.popBackStack() },
            onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
private fun BuilderCardRow(card: Card, inDeck: Int, owned: Int?, onAdd: () -> Unit, onInfo: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onAdd, onLongClick = onInfo)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ManaGem(card.cost, size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(card.rarityType.uiColor))
                Spacer(Modifier.width(4.dp))
                Text(
                    listOfNotNull(card.cardType.displayName, card.hsClass.takeIf { !card.isNeutral }?.displayName).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (owned != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Besitz ${minOf(owned, card.maxCopies)}/${card.maxCopies}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (owned >= card.maxCopies) HsColors.Win else if (owned > 0) HsColors.Warning else HsColors.Loss,
                    )
                }
            }
        }
        if (inDeck > 0) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    "${inDeck}×",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        FilledTonalIconButton(onClick = onAdd, enabled = inDeck < card.maxCopies) {
            Icon(Icons.Filled.Add, contentDescription = "Hinzufügen")
        }
    }
    Spacer(Modifier.height(1.dp))
}
