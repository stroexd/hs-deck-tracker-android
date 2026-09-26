package com.stroexd.hsdecktracker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.meta.MetaCardStats
import com.stroexd.hsdecktracker.core.util.formatPercent
import com.stroexd.hsdecktracker.core.collection.OwnedCard
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.labelRes
import kotlinx.coroutines.launch

@Composable
fun CollectionCardDialog(
    card: Card,
    onDismiss: () -> Unit,
    copiesInDeck: Int? = null,
    deckSize: Int = 30,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val collection by container.collection.collection.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val metaState by container.meta.state.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owned = collection.cards[card.dbfId] ?: OwnedCard()
    val metaInfo = remember(metaState, card.dbfId, settings.standardSetOverrides, context) {
        val format = if (settings.formatRules.isLegal(card, GameFormat.STANDARD)) GameFormat.STANDARD else GameFormat.WILD
        val decks = metaState.snapshots[format]?.decks
        if (decks.isNullOrEmpty()) {
            null
        } else {
            val popularity = MetaCardStats.popularity(decks)[card.dbfId]
            val topClass = popularity?.topClass
            if (popularity == null || topClass == null) {
                context.getString(R.string.meta_rarely_played, context.getString(format.labelRes()))
            } else {
                context.getString(
                    R.string.meta_share_of_class,
                    formatPercent(popularity.classShares[topClass], 0),
                    context.getString(topClass.labelRes()),
                    context.getString(format.labelRes()),
                )
            }
        }
    }
    CardDetailDialog(
        card = card,
        locale = cardState.db.locale,
        setName = cardState.db.setName(card.set),
        onDismiss = onDismiss,
        owned = owned.total,
        onOwnedChange = { newTotal ->
            val newNormal = (owned.normal + (newTotal - owned.total)).coerceAtLeast(0)
            scope.launch { container.collection.setNormalCount(card.dbfId, newNormal) }
        },
        copiesInDeck = copiesInDeck,
        deckSize = deckSize,
        metaInfo = metaInfo,
    )
}
