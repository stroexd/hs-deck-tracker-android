@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.stroexd.hsdecktracker.ui.stats

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.DeckAnalysis
import com.stroexd.hsdecktracker.core.meta.OpponentPredictor
import com.stroexd.hsdecktracker.core.stats.MatchExporter
import com.stroexd.hsdecktracker.core.stats.MatchHistory
import com.stroexd.hsdecktracker.core.stats.MatchQuery
import com.stroexd.hsdecktracker.core.stats.MatchRecord
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.stats.MatchSource
import com.stroexd.hsdecktracker.core.stats.ResultFilter
import com.stroexd.hsdecktracker.core.stats.StatsCalculator
import com.stroexd.hsdecktracker.core.stats.TurnSummary
import com.stroexd.hsdecktracker.core.util.formatPercent
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.Routes
import com.stroexd.hsdecktracker.ui.components.CardTile
import com.stroexd.hsdecktracker.ui.components.ChipRow
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.components.CollectionCardDialog
import com.stroexd.hsdecktracker.ui.components.ConfirmDialog
import com.stroexd.hsdecktracker.ui.components.EmptyState
import com.stroexd.hsdecktracker.ui.components.KeyValue
import com.stroexd.hsdecktracker.ui.components.SearchField
import com.stroexd.hsdecktracker.ui.components.SectionHeader
import com.stroexd.hsdecktracker.ui.formatDateTime
import com.stroexd.hsdecktracker.ui.formatDuration
import com.stroexd.hsdecktracker.ui.label
import com.stroexd.hsdecktracker.ui.labelRes
import com.stroexd.hsdecktracker.ui.matchSearchLabels
import com.stroexd.hsdecktracker.ui.rememberComputed
import com.stroexd.hsdecktracker.ui.shareText
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.theme.uiColor
import com.stroexd.hsdecktracker.ui.theme.winRateColor
import com.stroexd.hsdecktracker.ui.writeText
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import androidx.compose.material3.Card as M3Card

private enum class HistoryPeriod(@StringRes val label: Int, val days: Int?) {
    ALL(R.string.period_all, null),
    TODAY(R.string.period_today, 1),
    WEEK(R.string.period_week, 7),
    MONTH(R.string.period_month, 30),
}

fun resultColor(result: MatchResult) = when (result) {
    MatchResult.WIN -> HsColors.Win
    MatchResult.LOSS -> HsColors.Loss
    MatchResult.DRAW -> HsColors.Draw
}

@StringRes
private fun resultLetter(result: MatchResult) = when (result) {
    MatchResult.WIN -> R.string.result_letter_win
    MatchResult.LOSS -> R.string.result_letter_loss
    MatchResult.DRAW -> R.string.result_letter_draw
}

@Composable
private fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> stringResource(R.string.period_today)
    today.minusDays(1) -> stringResource(R.string.yesterday)
    else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()))
}

private fun matchSummary(context: Context, m: MatchRecord, db: CardDatabase): String = buildString {
    val you = m.deckName.ifBlank { context.getString(m.playerClass.labelRes()) }
    val opponent = m.opponentArchetype ?: context.getString(m.opponentClass.labelRes())
    appendLine(context.getString(m.result.labelRes()) + ": " + context.getString(R.string.match_vs, you, opponent))
    appendLine(formatDateTime(m.timestamp) + " · " + context.getString(m.format.labelRes()))
    val details = listOfNotNull(
        m.wentFirst?.let { context.getString(if (it) R.string.going_first else R.string.with_coin) },
        m.turns?.let { context.resources.getQuantityString(R.plurals.turns_count, it, it) },
        m.durationSeconds?.let { context.getString(R.string.duration_min, formatDuration(it)) },
    )
    if (details.isNotEmpty()) appendLine(details.joinToString(" · "))
    if (m.opponentCards.isNotEmpty()) {
        appendLine(context.getString(R.string.share_opponent_played, m.opponentCards.joinToString(", ") { db.byDbfId(it)?.name ?: it.toString() }))
    }
    if (m.notes.isNotBlank()) appendLine(context.getString(R.string.share_note, m.notes))
}.trimEnd()

@Composable
fun MatchRow(match: MatchRecord, onClick: () -> Unit, onLongClick: () -> Unit) {
    val color = resultColor(match.result)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(resultLetter(match.result)), color = color, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.match_vs,
                    match.deckName.ifBlank { match.playerClass.label() },
                    match.opponentArchetype ?: match.opponentClass.label(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(match.timestamp)),
                    match.format.label(),
                    match.wentFirst?.let { stringResource(if (it) R.string.went_first_short else R.string.coin_short) },
                    match.turns?.let { pluralStringResource(R.plurals.turns_count, it, it) },
                    match.durationSeconds?.let { formatDuration(it) },
                    match.opponentCards.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.opponent_cards_count, it, it) },
                    if (match.source == MatchSource.AUTO) stringResource(R.string.auto_short) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (match.timeline.isNotEmpty()) {
            Icon(
                Icons.Filled.Timeline,
                contentDescription = stringResource(R.string.has_timeline),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).padding(end = 2.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(12.dp).clip(CircleShape).background(match.opponentClass.uiColor))
    }
}

@Composable
fun MatchHistoryContent(
    navController: NavHostController,
    initialDeckId: String?,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val matches by container.matches.matches.collectAsStateWithLifecycle()
    val decks by container.decks.decks.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()

    var text by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf(ResultFilter.ALL) }
    var opponent by rememberSaveable { mutableStateOf<HsClass?>(null) }
    var format by rememberSaveable { mutableStateOf<GameFormat?>(null) }
    var deckId by rememberSaveable { mutableStateOf(initialDeckId) }
    var period by rememberSaveable { mutableStateOf(HistoryPeriod.ALL) }
    var deckMenu by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<MatchRecord?>(null) }
    var pendingCsv by remember { mutableStateOf<String?>(null) }

    val unknownDeck = stringResource(R.string.unknown_deck)
    val searchLabels = remember(context) { matchSearchLabels(context) }
    val deckNames = remember(matches, decks, unknownDeck) {
        (decks.map { it.id to it.name } + matches.mapNotNull { m -> m.deckId?.let { it to m.deckName.ifBlank { unknownDeck } } })
            .distinctBy { it.first }
    }
    val filtered by rememberComputed(matches, text, result, opponent, format, deckId, period, cardState.db, searchLabels, initial = emptyList<MatchRecord>()) {
        val since = period.days?.let { days ->
            if (days == 1) {
                LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else {
                System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
            }
        }
        MatchHistory.filter(
            matches,
            MatchQuery(text = text, result = result, opponentClass = opponent, deckId = deckId, format = format, sinceMillis = since),
            cardState.db,
            searchLabels,
        )
    }
    val days = remember(filtered) { MatchHistory.groupByDay(filtered) }
    val total = StatsCalculator.overall(filtered)

    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val content = pendingCsv
        if (uri != null && content != null) {
            scope.launch {
                runCatching { context.writeText(uri, content) }
                    .onSuccess { onMessage(context.resources.getQuantityString(R.plurals.matches_exported, filtered.size, filtered.size)) }
                    .onFailure { onMessage(context.getString(R.string.export_failed, it.message.orEmpty())) }
            }
        }
        pendingCsv = null
    }

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            SearchField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = stringResource(R.string.history_search_placeholder),
            )
        }
        item {
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ResultFilter.entries.forEach { r ->
                    FilterChip(selected = result == r, onClick = { result = r }, label = { Text(stringResource(r.labelRes())) })
                }
                listOf(GameFormat.STANDARD, GameFormat.WILD).forEach { f ->
                    FilterChip(
                        selected = format == f,
                        onClick = { format = if (format == f) null else f },
                        label = { Text(f.label()) },
                    )
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
        item {
            ChipRow(
                options = HistoryPeriod.entries.toList(),
                isSelected = { it == period },
                label = { context.getString(it.label) },
                onClick = { period = it },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            ChipRow(
                options = listOf<HsClass?>(null) + HsClass.playable,
                isSelected = { it == opponent },
                label = { cls ->
                    if (cls == null) context.getString(R.string.all_opponents) else context.getString(R.string.vs_class, context.getString(cls.labelRes()))
                },
                onClick = { opponent = it },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.history_summary, filtered.size, filtered.size, total.label),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(formatPercent(total.rate), style = MaterialTheme.typography.labelLarge, color = winRateColor(total.rate))
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        pendingCsv = MatchExporter.toCsv(filtered, cardState.db)
                        exportCsv.launch(context.getString(R.string.csv_file_name))
                    },
                    enabled = filtered.isNotEmpty(),
                ) { Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.export_csv_action)) }
            }
        }
        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    icon = if (matches.isEmpty()) Icons.Filled.History else Icons.Filled.SearchOff,
                    title = stringResource(if (matches.isEmpty()) R.string.no_games_title else R.string.no_results),
                    message = stringResource(if (matches.isEmpty()) R.string.no_games_message else R.string.no_results_hint),
                )
            }
        }
        days.forEach { day ->
            item(key = "day-${day.date}") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        dayLabel(day.date),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    val rate = day.winRate
                    Text("${rate.label} · ${formatPercent(rate.rate, 0)}", style = MaterialTheme.typography.labelMedium, color = winRateColor(rate.rate))
                }
            }
            items(day.matches, key = { it.id }) { match ->
                MatchRow(
                    match = match,
                    onClick = { navController.navigate(Routes.match(match.id)) },
                    onLongClick = { toDelete = match },
                )
            }
        }
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
fun MatchHistoryScreen(navController: NavHostController, deckId: String?) {
    val container = LocalAppContainer.current
    val decks by container.decks.decks.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deckName = deckId?.let { id -> decks.firstOrNull { it.id == id }?.name }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        deckName?.let { stringResource(R.string.matches_of_deck, it) } ?: stringResource(R.string.matches),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        MatchHistoryContent(
            navController = navController,
            initialDeckId = deckId,
            modifier = Modifier.fillMaxSize().padding(padding),
            onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
        )
    }
}

@Composable
fun MatchDetailScreen(navController: NavHostController, matchId: String) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val matches by container.matches.matches.collectAsStateWithLifecycle()
    val decks by container.decks.decks.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val metaState by container.meta.state.collectAsStateWithLifecycle()
    val match = matches.firstOrNull { it.id == matchId }
    val db = cardState.db
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selectedCard by remember { mutableStateOf<Card?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.match)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (match != null) {
                        IconButton(onClick = { context.shareText(context.getString(R.string.hearthstone_game), matchSummary(context, match, db)) }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                        }
                        IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit)) }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete)) }
                    }
                },
            )
        },
    ) { padding ->
        if (match == null) {
            EmptyState(
                icon = Icons.Filled.SearchOff,
                title = stringResource(R.string.match_not_found),
                message = stringResource(R.string.match_deleted),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        val deck = match.deckId?.let { id -> decks.firstOrNull { it.id == id } }
        val prediction = remember(match, metaState) {
            val format = if (match.format == GameFormat.WILD) GameFormat.WILD else GameFormat.STANDARD
            val metaDecks = metaState.snapshots[format]?.decks.orEmpty()
            if (match.opponentCards.isEmpty() || !match.opponentClass.isPlayable) {
                null
            } else {
                OpponentPredictor.predict(match.opponentClass, match.opponentCards, metaDecks, limit = 1).firstOrNull()
            }
        }
        val turns = remember(match) { MatchHistory.turns(match) }
        val drawn = remember(match) { MatchHistory.drawnCards(match).groupingBy { it }.eachCount() }

        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                MatchHeaderCard(match, onOpenDeck = deck?.let { d -> { navController.navigate(Routes.deck(d.id)) } })
            }
            if (match.notes.isNotBlank()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        SectionHeader(stringResource(R.string.note))
                        Text(match.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SectionHeader(stringResource(R.string.opponent))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClassBadge(match.opponentClass)
                        match.opponentArchetype?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                    }
                    if (prediction != null && prediction.matchedCards > 0) {
                        Text(
                            stringResource(
                                R.string.predicted_meta_deck,
                                prediction.deck.displayName,
                                prediction.matchedCards,
                                prediction.seenCards,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (match.opponentCards.isEmpty()) {
                        Text(
                            stringResource(R.string.no_opponent_cards),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            val opponentEntries = DeckAnalysis.entries(match.opponentCards.groupingBy { it }.eachCount(), db)
            items(opponentEntries, key = { "opp-${it.dbfId}" }) { entry ->
                CardTile(
                    card = entry.card,
                    name = entry.name,
                    cost = entry.cost,
                    count = entry.count,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    onClick = entry.card?.let { card -> { selectedCard = card } },
                )
            }
            if (drawn.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.your_drawn_cards, drawn.values.sum()), Modifier.padding(horizontal = 16.dp)) }
                val drawnEntries = DeckAnalysis.entries(drawn, db)
                items(drawnEntries, key = { "drawn-${it.dbfId}" }) { entry ->
                    CardTile(
                        card = entry.card,
                        name = entry.name,
                        cost = entry.cost,
                        count = entry.count,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        onClick = entry.card?.let { card -> { selectedCard = card } },
                    )
                }
            }
            if (turns.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.timeline), Modifier.padding(horizontal = 16.dp)) }
                items(turns, key = { "turn-${it.turn}" }) { turn ->
                    TurnRow(turn, db)
                }
            } else if (match.source == MatchSource.MANUAL) {
                item {
                    Text(
                        stringResource(R.string.manual_no_timeline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (editing && match != null) {
        EditMatchDialog(
            match = match,
            onDismiss = { editing = false },
            onSave = { updated ->
                editing = false
                scope.launch { container.matches.update(updated) }
            },
        )
    }
    if (confirmDelete && match != null) {
        ConfirmDialog(
            title = stringResource(R.string.delete_match_title),
            message = stringResource(R.string.delete_match_detail),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                scope.launch {
                    container.matches.delete(match.id)
                    navController.popBackStack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
    selectedCard?.let { card -> CollectionCardDialog(card = card, onDismiss = { selectedCard = null }) }
}

@Composable
private fun MatchHeaderCard(match: MatchRecord, onOpenDeck: (() -> Unit)?) {
    val color = resultColor(match.result)
    M3Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(match.result.label(), style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClassBadge(match.playerClass)
                Text(stringResource(R.string.vs), style = MaterialTheme.typography.labelLarge)
                ClassBadge(match.opponentClass)
            }
            Text(
                match.deckName.ifBlank { stringResource(R.string.no_deck) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            KeyValue(stringResource(R.string.date), formatDateTime(match.timestamp))
            KeyValue(stringResource(R.string.format), match.format.label())
            KeyValue(
                stringResource(R.string.turn_order),
                when (match.wentFirst) {
                    true -> stringResource(R.string.going_first)
                    false -> stringResource(R.string.with_coin)
                    null -> "–"
                },
            )
            match.turns?.let { KeyValue(stringResource(R.string.turns), it.toString()) }
            match.durationSeconds?.let { KeyValue(stringResource(R.string.duration), stringResource(R.string.duration_min, formatDuration(it))) }
            KeyValue(stringResource(R.string.recorded_by), stringResource(match.source.labelRes()))
            if (onOpenDeck != null) {
                FilledTonalButton(onClick = onOpenDeck, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.open_deck)) }
            }
        }
    }
}

@Composable
private fun TurnRow(turn: TurnSummary, db: CardDatabase) {
    fun names(ids: List<Int>) = ids.joinToString(", ") { db.byDbfId(it)?.name ?: it.toString() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            if (turn.turn <= 1) stringResource(R.string.turn_start) else stringResource(R.string.turn_n, turn.turn),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(64.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (turn.drawn.isNotEmpty()) TimelineLine(R.string.timeline_drawn, names(turn.drawn), HsColors.Mana)
            if (turn.returned.isNotEmpty()) TimelineLine(R.string.timeline_returned, names(turn.returned), HsColors.Warning)
            if (turn.extraDrawn.isNotEmpty()) {
                TimelineLine(R.string.timeline_extra, turn.extraDrawn.joinToString(", ") { db.byCardId(it)?.name ?: it }, HsColors.Dust)
            }
            if (turn.opponentPlayed.isNotEmpty()) TimelineLine(R.string.timeline_opponent, names(turn.opponentPlayed), HsColors.Loss)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun TimelineLine(@StringRes label: Int, text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.timeline_line, stringResource(label), text), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EditMatchDialog(match: MatchRecord, onDismiss: () -> Unit, onSave: (MatchRecord) -> Unit) {
    var result by remember { mutableStateOf(match.result) }
    var opponent by remember { mutableStateOf(match.opponentClass) }
    var archetype by remember { mutableStateOf(match.opponentArchetype.orEmpty()) }
    var wentFirst by remember { mutableStateOf(match.wentFirst) }
    var notes by remember { mutableStateOf(match.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_match)) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.result), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MatchResult.entries.forEach { r ->
                        FilterChip(selected = result == r, onClick = { result = r }, label = { Text(r.label()) })
                    }
                }
                Text(stringResource(R.string.opponent_class), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (HsClass.playable + HsClass.UNKNOWN).forEach { cls ->
                        FilterChip(selected = opponent == cls, onClick = { opponent = cls }, label = { Text(cls.label()) })
                    }
                }
                OutlinedTextField(
                    value = archetype,
                    onValueChange = { archetype = it },
                    label = { Text(stringResource(R.string.opponent_archetype_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = wentFirst == true, onClick = { wentFirst = if (wentFirst == true) null else true }, label = { Text(stringResource(R.string.going_first)) })
                    FilterChip(selected = wentFirst == false, onClick = { wentFirst = if (wentFirst == false) null else false }, label = { Text(stringResource(R.string.coin)) })
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.note)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    match.copy(
                        result = result,
                        opponentClass = opponent,
                        opponentArchetype = archetype.trim().ifBlank { null },
                        wentFirst = wentFirst,
                        notes = notes.trim(),
                    ),
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
