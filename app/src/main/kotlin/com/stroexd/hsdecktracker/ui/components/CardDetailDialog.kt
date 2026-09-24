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
import androidx.compose.ui.window.Dialog
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardSets
import com.stroexd.hsdecktracker.core.stats.DrawOdds
import com.stroexd.hsdecktracker.core.util.formatNumber
import com.stroexd.hsdecktracker.core.util.formatPercent
import com.stroexd.hsdecktracker.ui.theme.uiColor

/**
 * Detailansicht einer Karte: Bild, Text, Set, Herstellungskosten, eigene Anzahl
 * und – im Deck-Kontext – Ziehwahrscheinlichkeiten.
 */
@Composable
fun CardDetailDialog(
    card: Card,
    locale: String,
    onDismiss: () -> Unit,
    owned: Int? = null,
    onOwnedChange: ((Int) -> Unit)? = null,
    copiesInDeck: Int? = null,
    deckSize: Int = 30,
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
                    Text(card.rarityType.displayName, color = card.rarityType.uiColor, style = MaterialTheme.typography.labelLarge)
                    Text(card.cardType.displayName, style = MaterialTheme.typography.labelLarge)
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
                KeyValue("Set", CardSets.displayName(card.set))
                if (card.tribes.isNotEmpty()) KeyValue("Typ", card.tribes.joinToString { it.lowercase().replaceFirstChar(Char::uppercase) })
                if (card.isCraftable) {
                    KeyValue("Herstellen", "${formatNumber(card.rarityType.craftCost)} Staub (golden ${formatNumber(card.rarityType.goldenCraftCost)})")
                    KeyValue("Entzaubern", "${formatNumber(card.rarityType.disenchantValue)} Staub (golden ${formatNumber(card.rarityType.goldenDisenchantValue)})")
                } else {
                    KeyValue("Herstellen", card.howToEarn ?: if (card.set in CardSets.freeSets) "Kostenlos (Kernset)" else "Nicht herstellbar")
                }
                if (card.artist.isNotBlank()) KeyValue("Künstler", card.artist)

                if (copiesInDeck != null && copiesInDeck > 0) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("Ziehwahrscheinlichkeit (${copiesInDeck}× im Deck)", style = MaterialTheme.typography.titleSmall)
                    KeyValue("Starthand am Zug (mit Mulligan)", formatPercent(DrawOdds.withMulligan(deckSize, copiesInDeck, 3, 0)))
                    KeyValue("Starthand mit Münze (mit Mulligan)", formatPercent(DrawOdds.withMulligan(deckSize, copiesInDeck, 4, 0)))
                    KeyValue("Bis Zug 3 (am Zug)", formatPercent(DrawOdds.withMulligan(deckSize, copiesInDeck, 3, 2)))
                    KeyValue("Bis Zug 5 (am Zug)", formatPercent(DrawOdds.withMulligan(deckSize, copiesInDeck, 3, 4)))
                }

                if (owned != null) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("In deiner Sammlung", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (onOwnedChange != null) {
                            FilledTonalIconButton(onClick = { onOwnedChange((owned - 1).coerceAtLeast(0)) }) {
                                Icon(Icons.Filled.Remove, contentDescription = "Weniger")
                            }
                        }
                        Text(
                            "$owned",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        if (onOwnedChange != null) {
                            FilledTonalIconButton(onClick = { onOwnedChange(owned + 1) }) {
                                Icon(Icons.Filled.Add, contentDescription = "Mehr")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Schließen") }
                }
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}
