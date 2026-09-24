@file:OptIn(ExperimentalLayoutApi::class)

package com.stroexd.hsdecktracker.ui.tracker

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.CardFilter
import com.stroexd.hsdecktracker.core.cards.CardSearch
import com.stroexd.hsdecktracker.core.cards.FormatRules
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.DeckAnalysis
import com.stroexd.hsdecktracker.core.meta.DeckPrediction
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.tracker.TrackerState
import com.stroexd.hsdecktracker.core.util.formatPercent
import com.stroexd.hsdecktracker.ui.components.CardTile
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.theme.uiColor
import com.stroexd.hsdecktracker.ui.theme.winRateColor
import kotlinx.coroutines.delay

/**
 * Tracker-Oberfläche – wird im In-App-Tracker und im Overlay über Hearthstone verwendet.
 *
 * Eigenes Deck: Antippen = gezogen, lange drücken = zurück ins Deck.
 * Gegner: Klasse wählen, gespielte Karten erfassen, Deck-Vorhersage aus den Meta-Decks.
 */
@Composable
fun TrackerPanel(
    state: TrackerState,
    db: CardDatabase,
    predictions: List<DeckPrediction>,
    showOdds: Boolean,
    compact: Boolean,
    onUpdate: ((TrackerState) -> TrackerState) -> Unit,
    onFinish: (MatchResult) -> Unit,
    onNewGame: () -> Unit,
    modifier: Modifier = Modifier,
    onTextInputChange: (Boolean) -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val rowHeight = if (compact) 30.dp else 40.dp
    Column(modifier) {
        // Kopfzeile
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.deckName,
                    style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Im Deck ${state.remainingCount}/${state.initialCount} · Zug ${state.turn}" +
                        when (state.wentFirst) {
                            true -> " · am Zug"
                            false -> " · Münze"
                            null -> ""
                        } + if (state.autoTracked) " · auto" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SmallIconButton(Icons.Filled.Remove, "Zug zurück", compact) { onUpdate { it.previousTurn() } }
            SmallIconButton(Icons.Filled.Add, "Nächster Zug", compact) { onUpdate { it.nextTurn() } }
            SmallIconButton(Icons.AutoMirrored.Filled.Undo, "Rückgängig", compact) { onUpdate { it.undoLastDraw() } }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Deck (${state.remainingCount})", fontSize = if (compact) 12.sp else 14.sp) })
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("Gegner (${state.opponentCards.size})", fontSize = if (compact) 12.sp else 14.sp) },
            )
        }
        Box(Modifier.weight(1f, fill = !compact)) {
            if (tab == 0) {
                DeckTab(state, db, showOdds, rowHeight, onUpdate)
            } else {
                OpponentTab(state, db, predictions, compact, rowHeight, onUpdate, onTextInputChange)
            }
        }
        // Ergebnis
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = { onFinish(MatchResult.WIN) },
                colors = ButtonDefaults.buttonColors(containerColor = HsColors.Win.copy(alpha = 0.85f)),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).height(if (compact) 34.dp else 42.dp),
            ) { Text("Sieg", fontSize = if (compact) 12.sp else 14.sp) }
            Button(
                onClick = { onFinish(MatchResult.LOSS) },
                colors = ButtonDefaults.buttonColors(containerColor = HsColors.Loss.copy(alpha = 0.85f)),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).height(if (compact) 34.dp else 42.dp),
            ) { Text("Niederlage", fontSize = if (compact) 12.sp else 14.sp, maxLines = 1) }
            OutlinedButton(
                onClick = onNewGame,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(if (compact) 34.dp else 42.dp),
            ) { Text("Neu", fontSize = if (compact) 12.sp else 14.sp) }
        }
    }
}

@Composable
private fun SmallIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, compact: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(if (compact) 32.dp else 44.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(if (compact) 18.dp else 24.dp))
    }
}

@Composable
private fun DeckTab(
    state: TrackerState,
    db: CardDatabase,
    showOdds: Boolean,
    rowHeight: androidx.compose.ui.unit.Dp,
    onUpdate: ((TrackerState) -> TrackerState) -> Unit,
) {
    val entries = remember(state.deckCards, db) { DeckAnalysis.entries(state.deckCards, db) }
    if (entries.isEmpty()) {
        Text(
            "Kein Deck gewählt. Starte den Tracker über ein Deck (Decks → Deck → Tracker/Overlay).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
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
                            modifier = Modifier.width(40.dp).padding(start = 4.dp),
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
                    "Zusätzlich gezogen: " + state.extraDraws.mapNotNull { db.byCardId(it)?.name ?: it }.joinToString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(6.dp),
                )
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
    rowHeight: androidx.compose.ui.unit.Dp,
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
                    Text("Klasse des Gegners", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HsClass.playable.forEach { cls ->
                            FilterChip(
                                selected = state.opponentClass == cls,
                                onClick = {
                                    onUpdate { it.withOpponentClass(cls) }
                                    pickClass = false
                                },
                                label = { Text(cls.displayName, fontSize = if (compact) 11.sp else 13.sp) },
                            )
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClassBadge(state.opponentClass)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { pickClass = true }) { Text("ändern") }
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
                        placeholder = { Text("Gespielte Karte suchen …") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    )
                    IconButton(onClick = { closeSearch() }) { Icon(Icons.Filled.Close, contentDescription = "Suche schließen") }
                }
            } else {
                TextButton(onClick = {
                    searching = true
                    onTextInputChange(true)
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Gespielte Karte hinzufügen")
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
                Text("Gespielt (antippen zum Entfernen)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            }
            items(state.opponentCards.size, key = { "played-$it" }) { index ->
                val card = db.byDbfId(state.opponentCards[index])
                CardTile(
                    card = card,
                    name = card?.name ?: "Karte ${state.opponentCards[index]}",
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
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            if (state.opponentCards.isEmpty()) "Häufigstes Deck" else "Vermutetes Deck",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                        if (state.opponentCards.isNotEmpty()) {
                            Text(
                                "${top.matchedCards} von ${top.seenCards} gesehenen Karten passen" +
                                    predictions.drop(1).joinToString("") { " · Alternativ: ${it.deck.displayName}" },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            "Noch zu erwarten (antippen = gespielt):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
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
