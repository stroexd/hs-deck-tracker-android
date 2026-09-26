@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.stroexd.hsdecktracker.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.cards.Rarity
import com.stroexd.hsdecktracker.core.collection.CraftAnalysis
import com.stroexd.hsdecktracker.core.data.CardRepository
import com.stroexd.hsdecktracker.core.util.formatNumber
import com.stroexd.hsdecktracker.core.util.formatPercent
import com.stroexd.hsdecktracker.ui.label
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.theme.uiColor
import com.stroexd.hsdecktracker.ui.theme.winRateColor

@Composable
fun ClassBadge(cls: HsClass, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = cls.uiColor.copy(alpha = 0.18f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(cls.uiColor))
            Spacer(Modifier.width(6.dp))
            Text(cls.label(), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun ManaGem(cost: Int, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size * 0.74f)
                .rotate(45f)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.linearGradient(listOf(HsColors.Mana, HsColors.ManaDark)))
                .border(1.dp, Color(0xFFBFE3FF), RoundedCornerShape(3.dp)),
        )
        Text(
            text = cost.toString(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.5f).sp,
        )
    }
}

@Composable
fun CardTile(
    card: Card?,
    name: String,
    cost: Int,
    count: Int,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    missing: Int = 0,
    dimmed: Boolean = false,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    // Overlay rows beside the board: less padding, names may use two small lines
    val dense = height < 34.dp
    val rarityColor = card?.rarityType?.takeIf { it != Rarity.FREE && it != Rarity.UNKNOWN }?.uiColor
    val clickModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(clickModifier)
            .alpha(if (dimmed) 0.4f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(rarityColor ?: Color.Transparent),
        )
        ManaGem(cost, Modifier.padding(horizontal = if (dense) 2.dp else 4.dp), size = height * 0.7f)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (card != null) {
                AsyncImage(
                    model = CardRepository.tileUrl(card.id),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(120.dp),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0.0f to MaterialTheme.colorScheme.surfaceContainerHigh,
                            0.45f to MaterialTheme.colorScheme.surfaceContainerHigh,
                            1.0f to MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.15f),
                        ),
                    ),
            )
            Column(Modifier.align(Alignment.CenterStart).padding(start = if (dense) 2.dp else 4.dp, end = if (dense) 2.dp else 8.dp)) {
                Text(
                    text = name,
                    style = when {
                        dense -> MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp)
                        height < 36.dp -> MaterialTheme.typography.bodySmall
                        else -> MaterialTheme.typography.bodyMedium
                    },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (dense) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (missing > 0) HsColors.Loss else MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxHeight()
                .widthIn(min = if (dense) 18.dp else 30.dp)
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = if (dense) 3.dp else 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            val label = when {
                card?.rarityType == Rarity.LEGENDARY && count == 1 -> "★"
                else -> count.toString()
            }
            Text(
                label,
                color = if (card?.rarityType == Rarity.LEGENDARY) HsColors.Gold else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun CardImage(card: Card, locale: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = CardRepository.renderUrl(card.id, locale),
        contentDescription = card.name,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

@Composable
fun ManaCurveChart(curve: List<Int>, modifier: Modifier = Modifier, barHeight: Dp = 64.dp) {
    val max = (curve.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        curve.forEachIndexed { index, count ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (count > 0) count.toString() else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(barHeight * (count.toFloat() / max))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(Brush.verticalGradient(listOf(HsColors.Mana, HsColors.ManaDark))),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (index == 7) "7+" else index.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun DustLabel(amount: Int, modifier: Modifier = Modifier, color: Color = HsColors.Dust, prefix: String = "") {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Diamond, contentDescription = stringResource(R.string.arcane_dust), tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text("$prefix${formatNumber(amount)}", color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun CraftCostLabel(analysis: CraftAnalysis?, collectionEmpty: Boolean, dust: Int, modifier: Modifier = Modifier) {
    when {
        analysis == null -> Unit
        collectionEmpty -> Text(
            stringResource(R.string.collection_missing),
            modifier = modifier,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        analysis.isComplete -> Surface(
            modifier = modifier,
            shape = RoundedCornerShape(50),
            color = HsColors.Win.copy(alpha = 0.18f),
        ) {
            Text(
                stringResource(R.string.buildable_badge),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                color = HsColors.Win,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        else -> {
            val affordable = analysis.craftableWith(dust)
            val color = if (affordable) HsColors.Win else HsColors.Dust
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                DustLabel(analysis.dustCost, color = color)
                if (analysis.uncraftableMissing > 0) {
                    Text(" ⚠", color = HsColors.Warning, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun WinRateText(rate: Double?, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium) {
    Text(formatPercent(rate), modifier = modifier, color = winRateColor(rate), style = style, fontWeight = FontWeight.Bold)
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actions != null) {
            Spacer(Modifier.height(8.dp))
            actions()
        }
    }
}

@Composable
fun Banner(
    message: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val color = if (isError) HsColors.Loss else HsColors.Mana
    M3Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isError) Icons.Filled.ErrorOutline else Icons.Filled.Info,
                contentDescription = null,
                tint = color,
            )
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    M3Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = valueColor)
        }
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.search_placeholder),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear)) }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
fun <T> ChipRow(
    options: List<T>,
    isSelected: (T) -> Boolean,
    label: (T) -> String,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(options.size) { index ->
            val option = options[index]
            FilterChip(selected = isSelected(option), onClick = { onClick(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
fun ClassPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onPick: (HsClass) -> Unit,
    includeUnknown: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val classes = if (includeUnknown) HsClass.playable + HsClass.UNKNOWN else HsClass.playable
                classes.forEach { cls ->
                    Surface(
                        onClick = { onPick(cls) },
                        shape = RoundedCornerShape(10.dp),
                        color = cls.uiColor.copy(alpha = 0.2f),
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(cls.uiColor))
                            Spacer(Modifier.width(8.dp))
                            Text(cls.label())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
