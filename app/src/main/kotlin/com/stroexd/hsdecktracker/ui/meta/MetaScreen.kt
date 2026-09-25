@file:OptIn(ExperimentalMaterial3Api::class)

package com.stroexd.hsdecktracker.ui.meta

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import com.stroexd.hsdecktracker.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.collection.CraftAnalysis
import com.stroexd.hsdecktracker.core.collection.CraftingCalculator
import com.stroexd.hsdecktracker.core.data.MetaSourceType
import com.stroexd.hsdecktracker.core.data.RankRange
import com.stroexd.hsdecktracker.core.data.TimeRange
import com.stroexd.hsdecktracker.core.deck.DeckAnalysis
import com.stroexd.hsdecktracker.core.meta.MetaDeck
import com.stroexd.hsdecktracker.core.util.formatNumber
import com.stroexd.hsdecktracker.core.util.formatPercent
import com.stroexd.hsdecktracker.overlay.rememberTrackingStarter
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.label
import com.stroexd.hsdecktracker.ui.labelRes
import com.stroexd.hsdecktracker.ui.message
import com.stroexd.hsdecktracker.ui.Routes
import com.stroexd.hsdecktracker.ui.components.Banner
import com.stroexd.hsdecktracker.ui.components.ChipRow
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.components.CollectionCardDialog
import com.stroexd.hsdecktracker.ui.components.CraftCostLabel
import com.stroexd.hsdecktracker.ui.components.EmptyState
import com.stroexd.hsdecktracker.ui.components.WinRateText
import com.stroexd.hsdecktracker.ui.copyToClipboard
import com.stroexd.hsdecktracker.ui.decks.deckDetailContent
import com.stroexd.hsdecktracker.ui.decks.rememberDeckDetailData
import com.stroexd.hsdecktracker.ui.rememberComputed
import com.stroexd.hsdecktracker.ui.timeAgo
import com.stroexd.hsdecktracker.ui.theme.uiColor
import com.stroexd.hsdecktracker.ui.toast
import kotlinx.coroutines.launch

private enum class MetaSort(@StringRes val label: Int) { WINRATE(R.string.sort_winrate), POPULARITY(R.string.sort_popularity), DUST(R.string.sort_dust) }

private enum class Buildable(@StringRes val label: Int) { ALL(R.string.all), COMPLETE(R.string.buildable_now), AFFORDABLE(R.string.with_my_dust) }

private data class MetaRow(val deck: MetaDeck, val analysis: CraftAnalysis)

/** Describes what a cached snapshot was loaded for, e.g. "HSReplay · Standard · Bronze – Gold · Current patch". */
@Composable
private fun snapshotLabel(description: String): String {
    val parts = description.split('|')
    return when (parts.firstOrNull()) {
        "hsreplay" -> listOfNotNull(
            "HSReplay",
            GameFormat.entries.firstOrNull { it.name == parts.getOrNull(1) }?.label(),
            RankRange.entries.firstOrNull { it.name == parts.getOrNull(2) }?.let { stringResource(it.labelRes()) },
            TimeRange.entries.firstOrNull { it.name == parts.getOrNull(3) }?.let { stringResource(it.labelRes()) },
        ).joinToString(" · ")
        "url" -> stringResource(R.string.meta_source_own_list_short) + " · " + parts.drop(2).joinToString("|")
        else -> description
    }
}

@Composable
fun MetaScreen(navController: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val metaState by container.meta.state.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val collection by container.collection.collection.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var format by rememberSaveable { mutableStateOf(GameFormat.STANDARD) }
    var classFilter by rememberSaveable { mutableStateOf<HsClass?>(null) }
    var sort by rememberSaveable { mutableStateOf(MetaSort.WINRATE) }
    var buildable by rememberSaveable { mutableStateOf(Buildable.ALL) }
    var minGames by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(format, settings.metaSource, settings.metaRankRange, settings.metaTimeRange, settings.metaCustomUrl, cardState.db.isEmpty) {
        if (!cardState.db.isEmpty || settings.metaSource == MetaSourceType.HSREPLAY) {
            container.meta.refresh(format, settings, cardState.db)
        }
    }

    val snapshot = metaState.snapshots[format]
    val rows by rememberComputed(snapshot, cardState.db, collection, settings.coreSetOwned, initial = emptyList<MetaRow>()) {
        snapshot?.decks.orEmpty().map { deck ->
            MetaRow(deck, CraftingCalculator.analyze(deck.cards, deck.sideboards, collection, cardState.db, settings.collectionOptions))
        }
    }
    val maxGames = rows.maxOfOrNull { it.deck.totalGames ?: 0 } ?: 0
    val visible = rows
        .filter { classFilter == null || it.deck.heroClass == classFilter }
        .filter { !minGames || maxGames == 0 || (it.deck.totalGames ?: 0) >= maxGames / 50 }
        .filter {
            when (buildable) {
                Buildable.ALL -> true
                Buildable.COMPLETE -> it.analysis.isComplete
                Buildable.AFFORDABLE -> it.analysis.craftableWith(collection.dust)
            }
        }
        .let { list ->
            when (sort) {
                MetaSort.WINRATE -> list.sortedByDescending { it.deck.winRate ?: 0.0 }
                MetaSort.POPULARITY -> list.sortedByDescending { it.deck.totalGames ?: 0 }
                MetaSort.DUST -> list.sortedWith(compareBy<MetaRow> { it.analysis.uncraftableMissing }.thenBy { it.analysis.dustCost })
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.meta_decks)) },
                actions = {
                    IconButton(onClick = { scope.launch { container.meta.refresh(format, settings, cardState.db, force = true) } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ChipRow(
                    options = listOf(GameFormat.STANDARD, GameFormat.WILD),
                    isSelected = { it == format },
                    label = { context.getString(it.labelRes()) },
                    onClick = { format = it },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                ChipRow(
                    options = listOf<HsClass?>(null) + HsClass.playable,
                    isSelected = { it == classFilter },
                    label = { context.getString(it?.labelRes() ?: R.string.all_classes) },
                    onClick = { classFilter = it },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                ChipRow(
                    options = Buildable.entries.toList(),
                    isSelected = { it == buildable },
                    label = { if (it == Buildable.AFFORDABLE) context.getString(R.string.with_my_dust_amount, formatNumber(collection.dust)) else context.getString(it.label) },
                    onClick = { buildable = it },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                ChipRow(
                    options = MetaSort.entries.toList() + listOf(null),
                    isSelected = { if (it == null) minGames else it == sort },
                    label = { it?.let { s -> "↕ " + context.getString(s.label) } ?: context.getString(R.string.meaningful_only) },
                    onClick = { if (it == null) minGames = !minGames else sort = it },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (metaState.loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            metaState.error?.let { error ->
                item {
                    Banner(
                        error.message(),
                        isError = true,
                        actionLabel = stringResource(R.string.retry),
                        onAction = { scope.launch { container.meta.refresh(format, settings, cardState.db, force = true) } },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (collection.isEmpty) {
                item {
                    Banner(
                        stringResource(R.string.meta_upload_collection_hint),
                        actionLabel = stringResource(R.string.tab_collection),
                        onAction = { navController.navigate(Routes.COLLECTION) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            snapshot?.let { snap ->
                item {
                    Text(
                        snapshotLabel(snap.description) + " · " +
                            stringResource(R.string.decks_of, visible.size, rows.size) + " · " + timeAgo(snap.fetchedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (snapshot == null && !metaState.loading && metaState.error == null) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Leaderboard,
                        title = stringResource(R.string.no_meta_data),
                        message = stringResource(R.string.no_meta_data_hint),
                    )
                }
            }
            items(visible, key = { it.deck.id }) { row ->
                MetaDeckItem(row, collection.isEmpty, collection.dust) {
                    navController.navigate(Routes.metaDeck(format, row.deck.id))
                }
            }
        }
    }
}

@Composable
private fun MetaDeckItem(row: MetaRow, collectionEmpty: Boolean, dust: Int, onClick: () -> Unit) {
    val deck = row.deck
    M3Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(deck.heroClass.uiColor))
            Column(Modifier.weight(1f).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        deck.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (deck.winRate != null) WinRateText(deck.winRate)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClassBadge(deck.heroClass)
                    Text(
                        deck.totalGames?.let { stringResource(R.string.games_count, formatNumber(it)) } ?: deck.format.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    CraftCostLabel(row.analysis, collectionEmpty, dust)
                }
                if (!collectionEmpty && !row.analysis.isComplete) {
                    Text(
                        stringResource(R.string.missing_prefix) + row.analysis.missing.take(4).joinToString { m -> "${m.missing}× ${m.card?.name ?: m.dbfId}" } +
                            if (row.analysis.missing.size > 4) " …" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun MetaDeckDetailScreen(navController: NavHostController, format: GameFormat, deckId: String) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val metaState by container.meta.state.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val collection by container.collection.collection.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val metaDeck = metaState.snapshots[format]?.decks?.firstOrNull { it.id == deckId }
    val startOverlay = rememberTrackingStarter()
    var selectedCard by remember { mutableStateOf<Card?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(metaDeck?.displayName ?: stringResource(R.string.meta_deck), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (metaDeck != null) {
                        IconButton(onClick = { context.copyToClipboard("Deck code", metaDeck.deckCode, context.getString(R.string.deck_code_copied_hint)) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy_deck_code))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (metaDeck == null) {
            EmptyState(
                icon = Icons.Filled.SearchOff,
                title = stringResource(R.string.deck_not_found),
                message = stringResource(R.string.meta_updated_meanwhile),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        val deck = remember(metaDeck) { metaDeck.toDeck(System.currentTimeMillis()) }
        val data = rememberDeckDetailData(deck, cardState.db, collection, settings)
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            deckDetailContent(
                deck = deck,
                db = cardState.db,
                data = data,
                collection = collection,
                matches = emptyList(),
                onCardClick = { selectedCard = it },
                extraInfo = {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        metaDeck.winRate?.let {
                            Column {
                                Text(stringResource(R.string.sort_winrate), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                WinRateText(it)
                            }
                        }
                        metaDeck.totalGames?.let {
                            Column {
                                Text(stringResource(R.string.games), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatNumber(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        metaDeck.avgTurns?.let {
                            Column {
                                Text(stringResource(R.string.avg_turns), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("%.1f".format(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                header = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    container.decks.upsert(deck)
                                    context.toast(context.getString(R.string.added_to_decks, deck.name))
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.SaveAlt, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.save))
                        }
                        OutlinedButton(
                            onClick = {
                                container.tracker.start(deck)
                                navController.navigate(Routes.TRACKER)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.tracker))
                        }
                        OutlinedButton(
                            onClick = {
                                container.tracker.start(deck)
                                startOverlay()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Layers, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.overlay))
                        }
                    }
                },
            )
            item {
                Text(
                    stringResource(if (metaDeck.winRate != null) R.string.meta_source_hsreplay_note else R.string.meta_source_own_list),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    selectedCard?.let { card ->
        CollectionCardDialog(
            card = card,
            onDismiss = { selectedCard = null },
            copiesInDeck = metaDeck?.cards?.get(card.dbfId),
            deckSize = metaDeck?.let { DeckAnalysis.expectedSize(it.cards, cardState.db) } ?: 30,
        )
    }
}
