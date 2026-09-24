package com.stroexd.hsdecktracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.collection.CraftAnalysis
import com.stroexd.hsdecktracker.core.deck.DeckAnalysis
import com.stroexd.hsdecktracker.core.deck.DeckIssue
import com.stroexd.hsdecktracker.core.deck.DeckSummary
import com.stroexd.hsdecktracker.core.deck.IssueSeverity
import com.stroexd.hsdecktracker.core.deck.SideboardCard
import com.stroexd.hsdecktracker.core.util.formatNumber
import com.stroexd.hsdecktracker.ui.theme.HsColors

/** Zusammenfassung „Was fehlt mir für dieses Deck?“ – das Herzstück des Sammlungsabgleichs. */
@Composable
fun CraftSummaryCard(
    analysis: CraftAnalysis,
    collectionEmpty: Boolean,
    dust: Int,
    modifier: Modifier = Modifier,
    onCardClick: (Card) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(true) }
    M3Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (collectionEmpty) {
                Text("Sammlungsabgleich", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Lade im Tab „Sammlung“ deine Karten hoch, um zu sehen, welche Karten dir fehlen und wie viel Arkanstaub das Deck kostet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (analysis.isComplete) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = HsColors.Win)
                    Spacer(Modifier.width(8.dp))
                    Text("Du kannst dieses Deck sofort bauen!", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Es fehlen ${analysis.missingCount} Karten",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Du besitzt ${analysis.ownedCards} von ${analysis.totalCards} Karten",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DustLabel(analysis.dustCost, color = if (analysis.craftableWith(dust)) HsColors.Win else HsColors.Dust)
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { analysis.ownedFraction.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = if (analysis.isComplete) HsColors.Win else MaterialTheme.colorScheme.primary,
            )
            if (!analysis.isComplete) {
                Spacer(Modifier.height(6.dp))
                val statusText = when {
                    analysis.uncraftableMissing > 0 ->
                        "⚠ ${analysis.uncraftableMissing} fehlende Karte(n) können nicht hergestellt werden."
                    analysis.craftableWith(dust) ->
                        "Mit deinem Staub (${formatNumber(dust)}) sofort herstellbar."
                    else -> "Dir fehlen noch ${formatNumber(analysis.dustCost - dust)} Staub (du hast ${formatNumber(dust)})."
                }
                Text(statusText, style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Fehlende Karten", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                }
                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        analysis.missing.forEach { missing ->
                            CardTile(
                                card = missing.card,
                                name = missing.card?.name ?: "Unbekannte Karte (${missing.dbfId})",
                                cost = missing.card?.cost ?: 0,
                                count = missing.missing,
                                missing = missing.missing,
                                onClick = missing.card?.let { card -> { onCardClick(card) } },
                                trailing = {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (missing.craftable) {
                                            DustLabel(missing.totalCost, Modifier.padding(horizontal = 8.dp))
                                        } else {
                                            Text(
                                                "n. herstellbar",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = HsColors.Warning,
                                                modifier = Modifier.padding(horizontal = 8.dp),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeckSummaryCard(summary: DeckSummary, expectedSize: Int, modifier: Modifier = Modifier) {
    M3Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Karten", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${summary.totalCards}/$expectedSize",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (summary.totalCards == expectedSize) MaterialTheme.colorScheme.onSurface else HsColors.Warning,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Ø Mana", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.2f".format(summary.averageCost), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Staubwert", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DustLabel(summary.fullDustCost)
                }
            }
            Spacer(Modifier.height(12.dp))
            ManaCurveChart(summary.manaCurve)
            if (summary.typeCounts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    summary.typeCounts.entries.sortedByDescending { it.value }
                        .joinToString(" · ") { "${it.value} ${it.key.displayName}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun IssuesCard(issues: List<DeckIssue>, modifier: Modifier = Modifier) {
    if (issues.isEmpty()) return
    M3Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = HsColors.Warning.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            issues.take(8).forEach { issue ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (issue.severity == IssueSeverity.ERROR) HsColors.Loss else HsColors.Warning,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(issue.message, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (issues.size > 8) Text("… und ${issues.size - 8} weitere Hinweise", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Fügt die Kartenliste eines Decks (inkl. Sideboards) in eine LazyColumn ein.
 * Fehlende Karten werden markiert.
 */
fun LazyListScope.deckCardItems(
    cards: Map<Int, Int>,
    sideboards: List<SideboardCard>,
    db: CardDatabase,
    analysis: CraftAnalysis?,
    onCardClick: (Card) -> Unit,
) {
    val missingById = analysis?.missing?.associate { it.dbfId to it.missing }.orEmpty()
    val entries = DeckAnalysis.entries(cards, db)
    items(entries.size, key = { "card-${entries[it].dbfId}" }) { index ->
        val entry = entries[index]
        CardTile(
            card = entry.card,
            name = entry.name,
            cost = entry.cost,
            count = entry.count,
            missing = missingById[entry.dbfId] ?: 0,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            onClick = entry.card?.let { card -> { onCardClick(card) } },
        )
    }
    val owners = sideboards.groupBy { it.ownerDbfId }
    owners.forEach { (owner, list) ->
        item(key = "sb-header-$owner") {
            Text(
                "Sideboard: ${db.byDbfId(owner)?.name ?: owner}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        items(list.size, key = { "sb-$owner-${list[it].dbfId}" }) { index ->
            val sb = list[index]
            val card = db.byDbfId(sb.dbfId)
            CardTile(
                card = card,
                name = card?.name ?: "Karte ${sb.dbfId}",
                cost = card?.cost ?: 0,
                count = sb.count,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                onClick = card?.let { c -> { onCardClick(c) } },
            )
        }
    }
}
