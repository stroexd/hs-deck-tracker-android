package com.stroexd.hsdecktracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardSets
import com.stroexd.hsdecktracker.core.stats.DrawOdds
import com.stroexd.hsdecktracker.core.util.formatNumber
import com.stroexd.hsdecktracker.core.util.formatPercent
import com.stroexd.hsdecktracker.ui.label
import com.stroexd.hsdecktracker.ui.theme.uiColor

@Composable
fun CardDetailDialog(
    card: Card,
    locale: String,
    onDismiss: () -> Unit,
    owned: Int? = null,
    onOwnedChange: ((Int) -> Unit)? = null,
    copiesInDeck: Int? = null,
    deckSize: Int = 30,
    metaInfo: String? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        M3Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CardImage(card, locale, Modifier.fillMaxWidth(0.72f).heightIn(min = 200.dp))
                Spacer(Modifier.height(8.dp))
                Text(card.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ClassBadge(card.hsClass)
                    Text(card.rarityType.label(), color = card.rarityType.uiColor, style = MaterialTheme.typography.labelLarge)
                    Text(card.cardType.label(), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(8.dp))
                if (card.plainText.isNotBlank()) {
                    Text(card.plainText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                }
                if (card.plainFlavor.isNotBlank()) {
                    Text(
                        card.plainFlavor,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                KeyValue(stringResource(R.string.card_set), CardSets.displayName(card.set))
                if (card.tribes.isNotEmpty()) KeyValue(stringResource(R.string.card_tribe), card.tribes.joinToString { it.lowercase().replaceFirstChar(Char::uppercase) })
                if (card.isCraftable) {
                    KeyValue(
                        stringResource(R.string.card_craft),
                        stringResource(R.string.card_dust_golden, formatNumber(card.rarityType.craftCost), formatNumber(card.rarityType.goldenCraftCost)),
                    )
                    KeyValue(
                        stringResource(R.string.card_disenchant),
                        stringResource(R.string.card_dust_golden, formatNumber(card.rarityType.disenchantValue), formatNumber(card.rarityType.goldenDisenchantValue)),
                    )
                } else {
                    val howToGet = card.howToEarn
                        ?: stringResource(if (card.set in CardSets.freeSets) R.string.card_free_core else R.string.card_not_craftable)
                    KeyValue(stringResource(R.string.card_craft), howToGet)
                }
                if (card.artist.isNotBlank()) KeyValue(stringResource(R.string.card_artist), card.artist)
                if (metaInfo != null) KeyValue("Meta", metaInfo)

                if (copiesInDeck != null && copiesInDeck > 0) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text(stringResource(R.string.odds_title, copiesInDeck), style = MaterialTheme.typography.titleSmall)
                    KeyValue(stringResource(R.string.odds_opening_first), formatPercent(DrawOdds.withMulligan(deckSize, copiesInDeck, 3, 0)))
                    KeyValue(stringResource(R.string.odds_opening_coin), formatPercent(DrawOdds.withMulligan(deckSize, copiesInDeck, 4, 0)))
                    KeyValue(stringResource(R.string.odds_turn_3), formatPercent(DrawOdds.withMulligan(deckSize, copiesInDeck, 3, 2)))
                    KeyValue(stringResource(R.string.odds_turn_5), formatPercent(DrawOdds.withMulligan(deckSize, copiesInDeck, 3, 4)))
                }

                if (owned != null) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.in_your_collection), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (onOwnedChange != null) {
                            FilledTonalIconButton(onClick = { onOwnedChange((owned - 1).coerceAtLeast(0)) }) {
                                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.less))
                            }
                        }
                        Text(
                            "$owned",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        if (onOwnedChange != null) {
                            FilledTonalIconButton(onClick = { onOwnedChange(owned + 1) }) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.more))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}
