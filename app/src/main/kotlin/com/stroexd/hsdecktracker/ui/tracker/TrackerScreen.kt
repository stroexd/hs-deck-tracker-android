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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.overlay.OverlayLauncher
import com.stroexd.hsdecktracker.overlay.rememberTrackingStarter
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.components.EmptyState
import com.stroexd.hsdecktracker.ui.label
import com.stroexd.hsdecktracker.ui.labelRes
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
                title = { Text(stringResource(R.string.tracker)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { startOverlay() }) { Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.show_as_overlay)) }
                    IconButton(onClick = {
                        if (!OverlayLauncher.launchHearthstone(context)) context.toast(context.getString(R.string.hearthstone_not_installed))
                    }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.open_hearthstone)) }
                    if (state != null) {
                        IconButton(onClick = {
                            container.tracker.stop()
                            OverlayLauncher.stop(context)
                        }) { Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop_tracker)) }
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
                        title = stringResource(if (recognition.active) R.string.waiting_for_game else R.string.track_automatically),
                        message = stringResource(
                            if (recognition.active) R.string.waiting_for_game_message else R.string.track_automatically_message,
                        ),
                        actions = {
                            Button(onClick = { startOverlay() }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text(stringResource(R.string.play_and_track_button), Modifier.padding(start = 8.dp))
                            }
                        },
                    )
                }
                item {
                    Text(
                        stringResource(R.string.or_pick_deck),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(decks.sortedByDescending { it.updatedAt }, key = { it.id }) { deck ->
                    ListItem(
                        headlineContent = { Text(deck.name) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.deck_format_cards,
                                    deck.format.label(),
                                    pluralStringResource(R.plurals.card_count, deck.cardCount, deck.cardCount),
                                ),
                            )
                        },
                        leadingContent = { ClassBadge(deck.heroClass) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        tonalElevation = 0.dp,
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        trailingContent = {
                            IconButton(onClick = { container.tracker.start(deck) }) { Icon(Icons.Filled.Style, contentDescription = stringResource(R.string.start)) }
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
                    context.toast(context.getString(R.string.result_saved, context.getString(result.labelRes())))
                },
                onNewGame = { container.tracker.newGame() },
                modifier = Modifier.fillMaxSize(),
                decks = decks,
                onSelectDeck = { container.selectDeckForCurrentGame(it) },
            )
        }
    }
}
