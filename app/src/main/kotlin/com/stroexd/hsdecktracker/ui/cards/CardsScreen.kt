@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.stroexd.hsdecktracker.ui.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.stroexd.hsdecktracker.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardFilter
import com.stroexd.hsdecktracker.core.cards.CardSearch
import com.stroexd.hsdecktracker.core.cards.CardType
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.cards.Ownership
import com.stroexd.hsdecktracker.core.cards.Rarity
import com.stroexd.hsdecktracker.core.collection.CraftingCalculator
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.label
import com.stroexd.hsdecktracker.ui.labelRes
import com.stroexd.hsdecktracker.ui.components.CardImage
import com.stroexd.hsdecktracker.ui.components.ChipRow
import com.stroexd.hsdecktracker.ui.components.CollectionCardDialog
import com.stroexd.hsdecktracker.ui.components.EmptyState
import com.stroexd.hsdecktracker.ui.components.SearchField
import com.stroexd.hsdecktracker.ui.rememberComputed
import com.stroexd.hsdecktracker.ui.theme.uiColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CardsViewModel : ViewModel() {
    private val _filter = MutableStateFlow(CardFilter(format = GameFormat.STANDARD))
    val filter: StateFlow<CardFilter> = _filter.asStateFlow()
    fun update(transform: (CardFilter) -> CardFilter) = _filter.update(transform)
}

@Composable
fun CardsScreen(navController: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val vm: CardsViewModel = viewModel()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val collection by container.collection.collection.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Card?>(null) }

    val results by rememberComputed(cardState.db, filter, collection, settings, initial = emptyList<Card>()) {
        CardSearch.filter(cardState.db.deckCards, filter, settings.formatRules, collection, settings.collectionOptions)
    }
    val advancedCount = listOf(filter.rarities.isNotEmpty(), filter.types.isNotEmpty(), filter.set != null, filter.ownership != Ownership.ALL)
        .count { it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_cards)) },
                actions = {
                    IconButton(onClick = { showFilters = true }) {
                        BadgedBox(badge = { if (advancedCount > 0) Badge { Text("$advancedCount") } }) {
                            Icon(Icons.Filled.FilterList, contentDescription = stringResource(R.string.filter))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                value = filter.query,
                onValueChange = { q -> vm.update { it.copy(query = q) } },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = stringResource(R.string.card_search_full_placeholder),
            )
            ChipRow(
                options = listOf(GameFormat.STANDARD, GameFormat.WILD, GameFormat.CLASSIC),
                isSelected = { it == filter.format },
                label = { context.getString(it.labelRes()) },
                onClick = { f -> vm.update { it.copy(format = if (it.format == f) null else f) } },
                modifier = Modifier.padding(top = 4.dp),
            )
            ChipRow(
                options = HsClass.playable + HsClass.NEUTRAL,
                isSelected = { it in filter.classes },
                label = { context.getString(it.labelRes()) },
                onClick = { cls -> vm.update { it.copy(classes = if (cls in it.classes) it.classes - cls else it.classes + cls) } },
                modifier = Modifier.padding(top = 4.dp),
            )
            ChipRow(
                options = (0..7).toList(),
                isSelected = { it in filter.costs },
                label = { if (it == 7) "7+" else "$it" },
                onClick = { c -> vm.update { it.copy(costs = if (c in it.costs) it.costs - c else it.costs + c) } },
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                pluralStringResource(R.plurals.card_count, results.size, results.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (results.isEmpty() && !cardState.db.isEmpty) {
                EmptyState(Icons.Filled.SearchOff, stringResource(R.string.no_results), stringResource(R.string.no_results_hint))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results, key = { it.dbfId }) { card ->
                    val owned = if (collection.isEmpty) null else CraftingCalculator.ownedCopies(card, collection, settings.collectionOptions)
                    CardGridItem(card, cardState.db.locale, owned) { selected = card }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            CardFilterSheet(
                filter = filter,
                sets = cardState.db.sets,
                setName = cardState.db::setName,
                collectionAvailable = !collection.isEmpty,
                onChange = { transform -> vm.update(transform) },
            )
        }
    }
    selected?.let { card -> CollectionCardDialog(card = card, onDismiss = { selected = null }) }
}

@Composable
fun CardGridItem(card: Card, locale: String, owned: Int?, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick)) {
        CardImage(
            card,
            locale,
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.69f)
                .alpha(if (owned == 0) 0.45f else 1f),
        )
        if (owned != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (owned >= card.maxCopies) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
            ) {
                Text(
                    "${minOf(owned, card.maxCopies)}/${card.maxCopies}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun CardFilterSheet(
    filter: CardFilter,
    sets: List<String>,
    setName: (String) -> String,
    collectionAvailable: Boolean,
    onChange: ((CardFilter) -> CardFilter) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.filter), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onChange { CardFilter(query = it.query, format = it.format) } }) { Text(stringResource(R.string.reset)) }
        }
        Text(stringResource(R.string.rarity), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf(Rarity.FREE) + Rarity.collectible).forEach { rarity ->
                FilterChip(
                    selected = rarity in filter.rarities,
                    onClick = { onChange { it.copy(rarities = if (rarity in it.rarities) it.rarities - rarity else it.rarities + rarity) } },
                    label = { Text(rarity.label(), color = if (rarity in filter.rarities) MaterialTheme.colorScheme.onSurface else rarity.uiColor) },
                )
            }
        }
        Text(stringResource(R.string.card_type), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CardType.deckTypes.forEach { type ->
                FilterChip(
                    selected = type in filter.types,
                    onClick = { onChange { it.copy(types = if (type in it.types) it.types - type else it.types + type) } },
                    label = { Text(type.label()) },
                )
            }
        }
        if (collectionAvailable) {
            Text(stringResource(R.string.tab_collection), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Ownership.entries.forEach { ownership ->
                    FilterChip(
                        selected = filter.ownership == ownership,
                        onClick = { onChange { it.copy(ownership = ownership) } },
                        label = { Text(stringResource(ownership.labelRes())) },
                    )
                }
            }
        }
        Text(stringResource(R.string.card_set), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter.set == null, onClick = { onChange { it.copy(set = null) } }, label = { Text(stringResource(R.string.all_sets)) })
            sets.forEach { set ->
                FilterChip(
                    selected = filter.set == set,
                    onClick = { onChange { it.copy(set = if (it.set == set) null else set) } },
                    label = { Text(setName(set)) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
