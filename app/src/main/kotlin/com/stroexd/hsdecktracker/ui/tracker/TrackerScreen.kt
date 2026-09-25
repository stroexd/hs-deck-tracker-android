@file:OptIn(ExperimentalMaterial3Api::class)

package com.stroexd.hsdecktracker.ui.tracker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.overlay.OverlayLauncher
import com.stroexd.hsdecktracker.overlay.rememberTrackingStarter
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.components.EmptyState
import com.stroexd.hsdecktracker.ui.toast

@Composable
fun TrackerScreen(navController: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val state by container.tracker.state.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val decks by container.decks.decks.collectAsStateWithLifecycle()
    val metaState by container.meta.state.collectAsStateWithLifecycle()
    val recognition by container.recognition.collectAsStateWithLifecycle()
    val startOverlay = rememberTrackingStarter()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tracker") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { startOverlay() }) { Icon(Icons.Filled.Layers, contentDescription = "Als Overlay anzeigen") }
                    IconButton(onClick = {
                        if (!OverlayLauncher.launchHearthstone(context)) context.toast("Hearthstone ist nicht installiert")
                    }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Hearthstone öffnen") }
                    if (state != null) {
                        IconButton(onClick = {
                            container.tracker.stop()
                            OverlayLauncher.stop(context)
                        }) { Icon(Icons.Filled.Stop, contentDescription = "Tracker beenden") }
                    }
                },
            )
        },
    ) { padding ->
        val current = state
        if (current == null) {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Style,
                        title = if (recognition.active) "Warte auf die nächste Partie" else "Automatisch tracken",
                        message = if (recognition.active) {
                            "Die Erkennung läuft. Sobald in Hearthstone der Mulligan erscheint, startet der Tracker und erkennt dein Deck selbst."
                        } else {
                            "Tippe auf „Spielen“: Hearthstone startet, Spielstart, dein Deck und die Karten des Gegners werden automatisch erkannt."
                        },
                        actions = {
                            Button(onClick = { startOverlay() }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text("  Spielen & tracken")
                            }
                        },
                    )
                }
                item {
                    Text(
                        "Oder ein Deck fest vorgeben:",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(decks.sortedByDescending { it.updatedAt }, key = { it.id }) { deck ->
                    ListItem(
                        headlineContent = { Text(deck.name) },
                        supportingContent = { Text("${deck.format.displayName} · ${deck.cardCount} Karten") },
                        leadingContent = { ClassBadge(deck.heroClass) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        tonalElevation = 0.dp,
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        trailingContent = {
                            IconButton(onClick = { container.tracker.start(deck) }) { Icon(Icons.Filled.Style, contentDescription = "Starten") }
                        },
                    )
                }
            }
            return@Scaffold
        }
        val predictions = container.predictOpponent(current, metaState)
        Column(Modifier.fillMaxSize().padding(padding)) {
            TrackerPanel(
                state = current,
                db = cardState.db,
                predictions = predictions,
                showOdds = settings.overlayShowOdds,
                compact = false,
                onUpdate = { container.tracker.update(it) },
                onFinish = { result ->
                    container.finishGame(result)
                    context.toast(if (result == MatchResult.WIN) "Sieg gespeichert" else "Niederlage gespeichert")
                },
                onNewGame = { container.tracker.newGame() },
                modifier = Modifier.fillMaxSize(),
                decks = decks,
                onSelectDeck = { container.selectDeckForCurrentGame(it) },
            )
        }
    }
}
