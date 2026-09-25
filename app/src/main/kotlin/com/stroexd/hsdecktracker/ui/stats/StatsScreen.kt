@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.stroexd.hsdecktracker.ui.stats

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.stroexd.hsdecktracker.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.stats.MatchRecord
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.stats.MatchSource
import com.stroexd.hsdecktracker.core.stats.StatsCalculator
import com.stroexd.hsdecktracker.core.stats.StatsFilter
import com.stroexd.hsdecktracker.core.stats.WinRate
import com.stroexd.hsdecktracker.core.util.formatPercent
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.label
import com.stroexd.hsdecktracker.ui.Routes
import com.stroexd.hsdecktracker.ui.components.ChipRow
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.components.ConfirmDialog
import com.stroexd.hsdecktracker.ui.components.EmptyState
import com.stroexd.hsdecktracker.ui.components.SectionHeader
import com.stroexd.hsdecktracker.ui.components.StatTile
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.theme.uiColor
import com.stroexd.hsdecktracker.ui.theme.winRateColor
import kotlinx.coroutines.launch

private enum class Period(@StringRes val label: Int, val days: Int?) {
    WEEK(R.string.period_week, 7),
    MONTH(R.string.period_month, 30),
    ALL(R.string.period_all, null),
}

@Composable
fun StatsScreen(navController: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val matches by container.matches.matches.collectAsStateWithLifecycle()
    val decks by container.decks.decks.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(Period.ALL) }
    var format by rememberSaveable { mutableStateOf<GameFormat?>(null) }
    var deckId by rememberSaveable { mutableStateOf<String?>(null) }
    var deckMenu by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<MatchRecord?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    val filtered = remember(matches, period, format, deckId) {
        val since = period.days?.let { System.currentTimeMillis() - it * 24L * 60 * 60 * 1000 }
        StatsCalculator.filter(matches, StatsFilter(sinceMillis = since, format = format, deckId = deckId))
            .sortedByDescending { it.timestamp }
    }
    val overall = StatsCalculator.overall(filtered)
    val (first, coin) = StatsCalculator.byTurnOrder(filtered)
    val streak = StatsCalculator.currentStreak(filtered)
    val byClass = StatsCalculator.byOpponentClass(filtered)
    val byDeck = StatsCalculator.byDeck(filtered)
    val trend = remember(filtered) { StatsCalculator.rollingWinRate(filtered).takeLast(100) }
    val deckNames = remember(matches, decks) {
        (decks.map { it.id to it.name } + matches.mapNotNull { m -> m.deckId?.let { it to m.deckName } }).distinctBy { it.first }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_stats)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_match)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.overview)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.games_with_count, matches.size)) })
            }
            if (tab == 1) {
                MatchHistoryContent(
                    navController = navController,
                    initialDeckId = null,
                    modifier = Modifier.fillMaxSize(),
                    onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                )
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    ChipRow(Period.entries.toList(), { it == period }, { context.getString(it.label) }, { period = it }, Modifier.padding(top = 8.dp))
                }
                item {
                    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf<GameFormat?>(null, GameFormat.STANDARD, GameFormat.WILD).forEach { f ->
                            FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f?.label() ?: stringResource(R.string.all_formats)) })
                        }
                        Box {
                            FilterChip(
                                selected = deckId != null,
                                onClick = { deckMenu = true },
                                label = {
                                    Text(
                                        deckNames.firstOrNull { it.first == deckId }?.second ?: stringResource(R.string.all_decks),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                            DropdownMenu(expanded = deckMenu, onDismissRequest = { deckMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.all_decks)) }, onClick = { deckId = null; deckMenu = false })
                                deckNames.forEach { (id, name) ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = { deckId = id; deckMenu = false })
                                }
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.QueryStats,
                            title = stringResource(R.string.no_games_title),
                            message = stringResource(R.string.no_games_message),
                        )
                    }
                    return@LazyColumn
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(stringResource(R.string.sort_winrate), formatPercent(overall.rate), Modifier.weight(1f), winRateColor(overall.rate))
                            StatTile(stringResource(R.string.games), "${overall.games}", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(stringResource(R.string.record), overall.label, Modifier.weight(1f))
                            StatTile(
                                stringResource(R.string.streak),
                                streak?.let {
                                    stringResource(if (it.result == MatchResult.WIN) R.string.streak_wins else R.string.streak_losses, it.length)
                                } ?: "–",
                                Modifier.weight(1f),
                                if (streak?.result == MatchResult.WIN) HsColors.Win else if (streak != null) HsColors.Loss else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(stringResource(R.string.going_first), "${formatPercent(first.rate, 0)} (${first.games})", Modifier.weight(1f), winRateColor(first.rate))
                            StatTile(stringResource(R.string.with_coin), "${formatPercent(coin.rate, 0)} (${coin.games})", Modifier.weight(1f), winRateColor(coin.rate))
                        }
                    }
                }
                if (trend.size >= 3) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            SectionHeader(stringResource(R.string.trend_rolling))
                            TrendChart(trend, Modifier.fillMaxWidth().height(140.dp))
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.matchups), Modifier.padding(horizontal = 16.dp)) }
                items(byClass.entries.toList(), key = { "mu-" + it.key.name }) { (cls, rate) ->
                    MatchupRow(cls, rate)
                }
                if (deckId == null && byDeck.size > 1) {
                    item { SectionHeader(stringResource(R.string.tab_decks), Modifier.padding(horizontal = 16.dp)) }
                    items(byDeck, key = { "deck-" + (it.deckId ?: it.deckName) }) { deckRate ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(deckRate.playerClass.uiColor))
                            Spacer(Modifier.width(8.dp))
                            Text(deckRate.deckName.ifBlank { stringResource(R.string.no_deck) }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${deckRate.winRate.label} · ${formatPercent(deckRate.winRate.rate, 0)}",
                                color = winRateColor(deckRate.winRate.rate),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                item {
                    SectionHeader(stringResource(R.string.recent_games), Modifier.padding(horizontal = 16.dp)) {
                        TextButton(onClick = { tab = 1 }) { Text(stringResource(R.string.show_all_count, matches.size)) }
                    }
                }
                items(filtered.take(5), key = { it.id }) { match ->
                    MatchRow(
                        match = match,
                        onClick = { navController.navigate(Routes.match(match.id)) },
                        onLongClick = { toDelete = match },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddMatchDialog(
            decks = decks,
            onDismiss = { showAdd = false },
            onSave = { record ->
                showAdd = false
                scope.launch { container.matches.add(record) }
            },
        )
    }
    toDelete?.let { match ->
        ConfirmDialog(
            title = stringResource(R.string.delete_match_title),
            message = stringResource(
                R.string.delete_match_message,
                match.deckName.ifBlank { stringResource(R.string.no_deck) },
                match.opponentClass.label(),
                match.result.label(),
            ),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { scope.launch { container.matches.delete(match.id) } },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun MatchupRow(cls: HsClass, rate: WinRate) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(130.dp)) { ClassBadge(cls) }
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(HsColors.Loss.copy(alpha = 0.35f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((rate.rate ?: 0.0).toFloat())
                    .height(10.dp)
                    .background(HsColors.Win),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${formatPercent(rate.rate, 0)} (${rate.games})",
            style = MaterialTheme.typography.labelMedium,
            color = winRateColor(rate.rate),
            modifier = Modifier.width(78.dp),
        )
    }
}

@Composable
private fun TrendChart(values: List<Double>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val w = size.width
            val h = size.height
            drawLine(
                gridColor,
                Offset(0f, h / 2),
                Offset(w, h / 2),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
            )
            if (values.size < 2) return@Canvas
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = w * i / (values.size - 1)
                val y = h * (1f - v.toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun AddMatchDialog(decks: List<Deck>, onDismiss: () -> Unit, onSave: (MatchRecord) -> Unit) {
    var deck by remember { mutableStateOf(decks.maxByOrNull { it.updatedAt }) }
    var opponent by remember { mutableStateOf(HsClass.MAGE) }
    var result by remember { mutableStateOf(MatchResult.WIN) }
    var wentFirst by remember { mutableStateOf<Boolean?>(null) }
    var deckMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_match)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.your_deck), style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { deckMenu = true }) { Text(deck?.name ?: stringResource(R.string.no_deck), maxLines = 1) }
                    DropdownMenu(expanded = deckMenu, onDismissRequest = { deckMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.no_deck)) }, onClick = { deck = null; deckMenu = false })
                        decks.forEach { d -> DropdownMenuItem(text = { Text(d.name) }, onClick = { deck = d; deckMenu = false }) }
                    }
                }
                Text(stringResource(R.string.opponent), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HsClass.playable.forEach { cls ->
                        FilterChip(selected = opponent == cls, onClick = { opponent = cls }, label = { Text(cls.label()) })
                    }
                }
                Text(stringResource(R.string.result), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MatchResult.entries.forEach { r ->
                        FilterChip(selected = result == r, onClick = { result = r }, label = { Text(r.label()) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = wentFirst == true, onClick = { wentFirst = if (wentFirst == true) null else true }, label = { Text(stringResource(R.string.going_first)) })
                    FilterChip(selected = wentFirst == false, onClick = { wentFirst = if (wentFirst == false) null else false }, label = { Text(stringResource(R.string.coin)) })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    MatchRecord(
                        timestamp = System.currentTimeMillis(),
                        deckId = deck?.id,
                        deckName = deck?.name.orEmpty(),
                        playerClass = deck?.heroClass ?: HsClass.UNKNOWN,
                        opponentClass = opponent,
                        result = result,
                        format = deck?.format ?: GameFormat.STANDARD,
                        wentFirst = wentFirst,
                        source = MatchSource.MANUAL,
                    ),
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
