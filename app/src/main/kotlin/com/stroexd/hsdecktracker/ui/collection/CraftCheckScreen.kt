@file:OptIn(ExperimentalMaterial3Api::class)

package com.stroexd.hsdecktracker.ui.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.collection.CraftingCalculator
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.deck.DeckTextParser
import com.stroexd.hsdecktracker.core.util.formatNumber
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.components.CollectionCardDialog
import com.stroexd.hsdecktracker.ui.components.CraftSummaryCard
import com.stroexd.hsdecktracker.ui.components.EmptyState
import com.stroexd.hsdecktracker.ui.readClipboardText
import com.stroexd.hsdecktracker.ui.toast
import kotlinx.coroutines.launch

/** „Kann ich das bauen?“ – Deck-Codes einfügen und sofort gegen die Sammlung prüfen. */
@Composable
fun CraftCheckScreen(navController: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val collection by container.collection.collection.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(context.readClipboardText()?.takeIf { DeckTextParser.parseFirst(it) != null }.orEmpty()) }
    var detail by remember { mutableStateOf<Card?>(null) }

    val decks = remember(text, cardState.db) {
        DeckTextParser.parseAll(text).map { Deck.fromDefinition(it.definition, it.name, cardState.db, System.currentTimeMillis()) }
    }
    val analyses = remember(decks, collection, cardState.db, settings.coreSetOwned) {
        decks.map { CraftingCalculator.analyze(it.cards, it.sideboards, collection, cardState.db, settings.collectionOptions) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Deck-Code prüfen") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp),
                        placeholder = { Text("Deck-Code(s) hier einfügen …") },
                    )
                    Row {
                        TextButton(onClick = { context.readClipboardText()?.let { text = it } }) { Text("Aus Zwischenablage") }
                        TextButton(onClick = { text = "" }) { Text("Leeren") }
                    }
                    if (collection.isEmpty) {
                        Text(
                            "Hinweis: Noch keine Sammlung hinterlegt – es wird der volle Staubwert angezeigt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (decks.isEmpty() && text.isNotBlank()) {
                item { EmptyState(Icons.Filled.SearchOff, "Kein gültiger Deck-Code", "Deck-Codes beginnen mit „AAE…“.") }
            }
            items(decks.indices.toList()) { index ->
                val deck = decks[index]
                val analysis = analyses[index]
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(deck.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                ClassBadge(deck.heroClass)
                                Text("${deck.format.displayName} · ${deck.cardCount} Karten", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        FilledTonalButton(onClick = {
                            scope.launch {
                                container.decks.upsert(deck)
                                context.toast("„${deck.name}“ gespeichert")
                            }
                        }) { Text("Speichern") }
                    }
                    CraftSummaryCard(
                        analysis = analysis,
                        collectionEmpty = false,
                        dust = collection.dust,
                        onCardClick = { detail = it },
                    )
                    if (collection.isEmpty) {
                        Text(
                            "Voller Staubwert: ${formatNumber(analysis.dustCost)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
    detail?.let { CollectionCardDialog(card = it, onDismiss = { detail = null }) }
}
