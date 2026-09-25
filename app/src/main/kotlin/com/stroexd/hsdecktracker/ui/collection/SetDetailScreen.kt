@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.stroexd.hsdecktracker.ui.collection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.stroexd.hsdecktracker.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardSets
import com.stroexd.hsdecktracker.core.cards.Ownership
import com.stroexd.hsdecktracker.core.cards.Rarity
import com.stroexd.hsdecktracker.core.collection.CollectionStats
import com.stroexd.hsdecktracker.core.collection.CraftingCalculator
import com.stroexd.hsdecktracker.core.collection.OwnedCard
import com.stroexd.hsdecktracker.core.util.formatPercent
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.labelRes
import com.stroexd.hsdecktracker.ui.components.CardImage
import com.stroexd.hsdecktracker.ui.components.ChipRow
import com.stroexd.hsdecktracker.ui.components.CollectionCardDialog
import com.stroexd.hsdecktracker.ui.components.ConfirmDialog
import com.stroexd.hsdecktracker.ui.components.DustLabel
import com.stroexd.hsdecktracker.ui.theme.HsColors
import kotlinx.coroutines.launch

@Composable
fun SetDetailScreen(navController: NavHostController, set: String) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val collection by container.collection.collection.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var ownership by rememberSaveable { mutableStateOf(Ownership.ALL) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf<Boolean?>(null) }
    var detail by remember { mutableStateOf<Card?>(null) }

    val cards = remember(cardState.db, set) {
        cardState.db.cardsInSet(set).sortedWith(compareBy<Card>({ it.isNeutral }, { it.hsClass.ordinal }, { it.cost }, { it.name }))
    }
    val progress = remember(cards, collection, settings.coreSetOwned) {
        CollectionStats.progressFor(set, cards, collection, settings.collectionOptions)
    }
    val visible = cards.filter { card ->
        val owned = CraftingCalculator.ownedCopies(card, collection, settings.collectionOptions)
        when (ownership) {
            Ownership.ALL -> true
            Ownership.OWNED -> owned > 0
            Ownership.MISSING -> owned == 0
            Ownership.INCOMPLETE -> owned < card.maxCopies
        }
    }

    fun cycle(card: Card) {
        val current = collection.cards[card.dbfId] ?: OwnedCard()
        val next = if (current.total >= card.maxCopies) 0 else current.total + 1
        val newNormal = (current.normal + (next - current.total)).coerceAtLeast(0)
        scope.launch { container.collection.setNormalCount(card.dbfId, newNormal) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(CardSets.displayName(set)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.own_all_cards)) }, onClick = { menuOpen = false; confirmAll = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remove_all_cards)) }, onClick = { menuOpen = false; confirmAll = false })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    stringResource(R.string.set_progress, progress.uniqueOwned, progress.uniqueTotal, formatPercent(progress.fraction, 0)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (progress.dustToComplete > 0) DustLabel(progress.dustToComplete, prefix = stringResource(R.string.to_complete_prefix))
                Text(
                    stringResource(R.string.set_tap_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ChipRow(
                options = Ownership.entries.toList(),
                isSelected = { it == ownership },
                label = { context.getString(it.labelRes()) },
                onClick = { ownership = it },
                modifier = Modifier.padding(vertical = 4.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 105.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.dbfId }) { card ->
                    val owned = CraftingCalculator.ownedCopies(card, collection, settings.collectionOptions)
                    val editable = card.rarityType != Rarity.FREE && !(settings.coreSetOwned && card.set in CardSets.freeSets)
                    Box(
                        Modifier.combinedClickable(
                            onClick = { if (editable) cycle(card) else detail = card },
                            onLongClick = { detail = card },
                        ),
                    ) {
                        CardImage(
                            card,
                            cardState.db.locale,
                            Modifier.fillMaxWidth().aspectRatio(0.69f).alpha(if (owned == 0) 0.4f else 1f),
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                owned >= card.maxCopies -> HsColors.Win.copy(alpha = 0.85f)
                                owned > 0 -> HsColors.Warning.copy(alpha = 0.85f)
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                        ) {
                            Text(
                                "${minOf(owned, card.maxCopies)}/${card.maxCopies}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    confirmAll?.let { own ->
        ConfirmDialog(
            title = stringResource(if (own) R.string.own_set_title else R.string.clear_set_title),
            message = stringResource(if (own) R.string.own_set_message else R.string.clear_set_message),
            confirmLabel = stringResource(R.string.yes),
            onConfirm = {
                scope.launch {
                    val counts = cards.associate { card ->
                        val current = collection.cards[card.dbfId] ?: OwnedCard()
                        card.dbfId to if (own) (current.normal + card.maxCopies - current.total).coerceAtLeast(current.normal) else 0
                    }
                    container.collection.setNormalCounts(counts)
                }
            },
            onDismiss = { confirmAll = null },
        )
    }
    detail?.let { card -> CollectionCardDialog(card = card, onDismiss = { detail = null }) }
}
