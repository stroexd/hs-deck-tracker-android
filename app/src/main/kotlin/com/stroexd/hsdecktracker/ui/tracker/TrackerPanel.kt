@file:OptIn(ExperimentalLayoutApi::class)

package com.stroexd.hsdecktracker.ui.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.CardFilter
import com.stroexd.hsdecktracker.core.cards.CardSearch
import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.deck.DeckAnalysis
import com.stroexd.hsdecktracker.core.meta.DeckPrediction
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.tracker.TrackerState
import com.stroexd.hsdecktracker.core.util.formatPercent
import com.stroexd.hsdecktracker.ui.components.CardTile
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.label
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.theme.uiColor
import com.stroexd.hsdecktracker.ui.theme.winRateColor
import kotlinx.coroutines.delay

@Composable
fun TrackerPanel(
    state: TrackerState,
    db: CardDatabase,
    predictions: List<DeckPrediction>,
    showOdds: Boolean,
    compact: Boolean,
    showResultButtons: Boolean = true,
    onUpdate: ((TrackerState) -> TrackerState) -> Unit,
    onFinish: (MatchResult) -> Unit,
    onNewGame: () -> Unit,
    modifier: Modifier = Modifier,
    onTextInputChange: (Boolean) -> Unit = {},
    decks: List<Deck> = emptyList(),
    onSelectDeck: ((Deck) -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val rowHeight = if (compact) 30.dp else 40.dp
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.deckName.ifBlank { stringResource(R.string.detecting_deck) },
                    style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        if (compact) {
                            stringResource(R.string.tracker_status_short, state.remainingCount, state.initialCount, state.turn)
                        } else {
                            stringResource(R.string.tracker_status, state.remainingCount, state.initialCount, state.turn)
                        },
                        state.wentFirst?.let { stringResource(if (it) R.string.went_first_short else R.string.coin_short) },
                        if (state.autoTracked) stringResource(R.string.auto_short) else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Next to the board there's no room, and recognition keeps turns and draws anyway
            if (!compact) {
                SmallIconButton(Icons.Filled.Remove, stringResource(R.string.previous_turn)) { onUpdate { it.previousTurn() } }
                SmallIconButton(Icons.Filled.Add, stringResource(R.string.next_turn)) { onUpdate { it.nextTurn() } }
                SmallIconButton(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) { onUpdate { it.undoLastDraw() } }
            }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = {
                    Text(
                        stringResource(if (compact) R.string.tab_deck_short else R.string.deck_with_count, state.remainingCount),
                        fontSize = if (compact) 11.sp else 14.sp,
                        maxLines = 1,
                    )
                },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = {
                    Text(
                        stringResource(if (compact) R.string.tab_opponent_short else R.string.opponent_with_count, state.opponentCards.size),
                        fontSize = if (compact) 11.sp else 14.sp,
                        maxLines = 1,
                    )
                },
            )
        }
        Box(Modifier.weight(1f, fill = !compact)) {
            if (tab == 0) {
                DeckTab(state, db, showOdds, rowHeight, onUpdate, decks, onSelectDeck)
            } else {
                OpponentTab(state, db, predictions, compact, rowHeight, onUpdate, onTextInputChange)
            }
        }
        if (showResultButtons) {
            ResultButtons(compact, onFinish, onNewGame)
        }
    }
}

@Composable
private fun ResultButtons(compact: Boolean, onFinish: (MatchResult) -> Unit, onNewGame: () -> Unit) {
    val height = if (compact) 32.dp else 42.dp
    val fontSize = if (compact) 12.sp else 14.sp
    val win = @Composable { modifier: Modifier ->
        Button(
            onClick = { onFinish(MatchResult.WIN) },
            colors = ButtonDefaults.buttonColors(containerColor = HsColors.Win.copy(alpha = 0.85f)),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = modifier.height(height),
        ) { Text(stringResource(R.string.win), fontSize = fontSize, maxLines = 1) }
    }
    val loss = @Composable { modifier: Modifier ->
        Button(
            onClick = { onFinish(MatchResult.LOSS) },
            colors = ButtonDefaults.buttonColors(containerColor = HsColors.Loss.copy(alpha = 0.85f)),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = modifier.height(height),
        ) { Text(stringResource(R.string.loss), fontSize = fontSize, maxLines = 1) }
    }
    val newGame = @Composable { modifier: Modifier ->
        OutlinedButton(
            onClick = onNewGame,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = modifier.height(height),
        ) { Text(stringResource(R.string.new_game), fontSize = fontSize, maxLines = 1) }
    }
    if (compact) {
        // The overlay column is too narrow for three buttons side by side
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            win(Modifier.fillMaxWidth())
            loss(Modifier.fillMaxWidth())
            newGame(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            win(Modifier.weight(1f))
            loss(Modifier.weight(1f))
            newGame(Modifier)
        }
    }
}

@Composable
private fun SmallIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun DeckTab(
    state: TrackerState,
    db: CardDatabase,
    showOdds: Boolean,
    rowHeight: Dp,
    onUpdate: ((TrackerState) -> TrackerState) -> Unit,
    decks: List<Deck>,
    onSelectDeck: ((Deck) -> Unit)?,
) {
    val entries = remember(state.deckCards, db) { DeckAnalysis.entries(state.deckCards, db) }
    if (entries.isEmpty()) {
        UnknownDeck(state, db, rowHeight, decks, onSelectDeck)
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(entries.size, key = { entries[it].dbfId }) { index ->
            val entry = entries[index]
            val remaining = state.remainingOf(entry.dbfId)
            CardTile(
                card = entry.card,
                name = entry.name,
                cost = entry.cost,
                count = remaining,
                height = rowHeight,
                dimmed = remaining == 0,
                onClick = { onUpdate { it.draw(entry.dbfId) } },
                onLongClick = { onUpdate { it.returnToDeck(entry.dbfId) } },
                trailing = if (showOdds && remaining > 0) {
                    {
                        Text(
                            formatPercent(state.nextDrawChance(entry.dbfId), 0),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            modifier = Modifier.width(30.dp).padding(start = 2.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
        if (state.extraDraws.isNotEmpty()) {
            item(key = "extra") {
                Text(
                    stringResource(R.string.extra_drawn, state.extraDraws.joinToString { db.byCardId(it)?.name ?: it }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun UnknownDeck(
    state: TrackerState,
    db: CardDatabase,
    rowHeight: Dp,
    decks: List<Deck>,
    onSelectDeck: ((Deck) -> Unit)?,
) {
    var choosing by remember { mutableStateOf(false) }
    val candidates = remember(decks, state.playerClass) {
        decks.sortedWith(compareByDescending<Deck> { it.heroClass == state.playerClass }.thenByDescending { it.updatedAt })
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "info") {
            Text(
                stringResource(if (state.autoTracked) R.string.detecting_deck else R.string.no_deck_selected),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(6.dp),
            )
        }
        if (state.extraDraws.isNotEmpty()) {
            item(key = "seen-header") {
                Text(stringResource(R.string.seen_so_far), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp))
            }
            items(state.extraDraws.size, key = { "seen-$it" }) { index ->
                val card = db.byCardId(state.extraDraws[index])
                CardTile(card = card, name = card?.name ?: state.extraDraws[index], cost = card?.cost ?: 0, count = 1, height = rowHeight)
            }
        }
        if (onSelectDeck != null && decks.isNotEmpty()) {
            item(key = "choose") {
                TextButton(onClick = { choosing = !choosing }) {
                    Text(stringResource(if (choosing) R.string.close_selection else R.string.choose_deck_manually))
                }
            }
            if (choosing) {
                items(candidates.size, key = { "deck-${candidates[it].id}" }) { index ->
                    val deck = candidates[index]
                    Surface(
                        onClick = {
                            onSelectDeck(deck)
                            choosing = false
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(deck.heroClass.uiColor))
                            Spacer(Modifier.width(6.dp))
                            Text(deck.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpponentTab(
    state: TrackerState,
    db: CardDatabase,
    predictions: List<DeckPrediction>,
    compact: Boolean,
    rowHeight: Dp,
    onUpdate: ((TrackerState) -> TrackerState) -> Unit,
    onTextInputChange: (Boolean) -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var pickClass by remember { mutableStateOf(false) }
    val results = remember(query, state.opponentClass, db) {
        if (query.length < 2) {
            emptyList()
        } else {
            val classes = if (state.opponentClass.isPlayable) setOf(state.opponentClass, HsClass.NEUTRAL) else emptySet()
            CardSearch.filter(db.deckCards, CardFilter(query = query, classes = classes), FormatRules()).take(8)
        }
    }

    fun closeSearch() {
        searching = false
        query = ""
        onTextInputChange(false)
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "class") {
            if (!state.opponentClass.isPlayable || pickClass) {
                Column {
                    Text(stringResource(R.string.opponent_class_title), style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HsClass.playable.forEach { cls ->
                            FilterChip(
                                selected = state.opponentClass == cls,
                                onClick = {
                                    onUpdate { it.withOpponentClass(cls) }
                                    pickClass = false
                                },
                                label = { Text(cls.label(), fontSize = if (compact) 11.sp else 13.sp) },
                            )
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClassBadge(state.opponentClass, Modifier.clickable { pickClass = true })
                    if (!compact) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { pickClass = true }) { Text(stringResource(R.string.change)) }
                    }
                }
            }
        }
        item(key = "search") {
            if (searching) {
                val focusRequester = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) {
                    delay(150)
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.search_played_card)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    )
                    IconButton(onClick = { closeSearch() }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_search)) }
                }
            } else {
                TextButton(onClick = {
                    searching = true
                    onTextInputChange(true)
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(if (compact) R.string.add_card_short else R.string.add_played_card))
                }
            }
        }
        items(results.size, key = { "result-${results[it].dbfId}" }) { index ->
            val card = results[index]
            CardTile(
                card = card,
                name = card.name,
                cost = card.cost,
                count = 1,
                height = rowHeight,
                onClick = {
                    onUpdate { it.addOpponentCard(card.dbfId) }
                    closeSearch()
                },
            )
        }
        if (state.opponentCards.isNotEmpty()) {
            item(key = "played-header") {
                Text(stringResource(if (compact) R.string.played_short else R.string.played_tap_to_remove), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            }
            items(state.opponentCards.size, key = { "played-$it" }) { index ->
                val card = db.byDbfId(state.opponentCards[index])
                CardTile(
                    card = card,
                    name = card?.name ?: stringResource(R.string.card_number, state.opponentCards[index]),
                    cost = card?.cost ?: 0,
                    count = 1,
                    height = rowHeight,
                    onClick = { onUpdate { it.removeOpponentCardAt(index) } },
                )
            }
        }
        val top = predictions.firstOrNull()
        if (top != null) {
            item(key = "prediction") {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Column(Modifier.padding(if (compact) 6.dp else 8.dp)) {
                        if (!compact) {
                            Text(
                                stringResource(if (state.opponentCards.isEmpty()) R.string.most_common_deck else R.string.likely_deck),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                top.deck.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = top.deck.heroClass.uiColor,
                                modifier = Modifier.weight(1f),
                            )
                            top.deck.winRate?.let { Text(formatPercent(it, 0), color = winRateColor(it), style = MaterialTheme.typography.labelMedium) }
                        }
                        if (!compact && state.opponentCards.isNotEmpty()) {
                            Text(
                                (
                                    listOf(stringResource(R.string.seen_cards_match, top.matchedCards, top.seenCards)) +
                                        predictions.drop(1).map { stringResource(R.string.alternative, it.deck.displayName) }
                                    ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (!compact) {
                            Text(
                                stringResource(R.string.still_expected),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            val remaining = DeckAnalysis.entries(top.remainingCards, db)
            items(remaining.size, key = { "pred-${remaining[it].dbfId}" }) { index ->
                val entry = remaining[index]
                CardTile(
                    card = entry.card,
                    name = entry.name,
                    cost = entry.cost,
                    count = entry.count,
                    height = rowHeight,
                    onClick = { onUpdate { it.addOpponentCard(entry.dbfId) } },
                )
            }
        }
    }
}
