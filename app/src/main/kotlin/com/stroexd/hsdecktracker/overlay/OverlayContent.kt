package com.stroexd.hsdecktracker.overlay

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stroexd.hsdecktracker.AppContainer
import com.stroexd.hsdecktracker.CollectionActivity
import com.stroexd.hsdecktracker.MainActivity
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.core.collection.ReceivedStatus
import com.stroexd.hsdecktracker.core.util.formatNumber
import com.stroexd.hsdecktracker.core.vision.VisionGameTracker
import com.stroexd.hsdecktracker.ui.label
import com.stroexd.hsdecktracker.ui.labelRes
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.toast
import com.stroexd.hsdecktracker.ui.tracker.TrackerPanel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun OverlayContent(
    onDrag: (Float, Float) -> Unit,
    onClose: () -> Unit,
    onTextInput: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val container = remember { context.appContainer }
    val state by container.tracker.state.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val metaState by container.meta.state.collectAsStateWithLifecycle()
    val decks by container.decks.decks.collectAsStateWithLifecycle()
    var collapsed by remember { mutableStateOf(false) }

    LaunchedEffect(state?.startedAt) {
        if (state != null) collapsed = false
    }
    val activityId by remember { container.collectionActivity.map { it?.id } }.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(activityId) {
        if (activityId != null) collapsed = false
    }

    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            onDrag(dragAmount.x, dragAmount.y)
        }
    }

    if (collapsed) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.alpha(settings.overlayOpacity).then(dragModifier),
            onClick = { collapsed = false },
        ) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Text(
                    state?.remainingCount?.takeIf { state?.deckCards?.isNotEmpty() == true }?.toString() ?: "HS",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        return
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .width(settings.overlayWidthDp.dp)
            .heightIn(max = 520.dp)
            .alpha(settings.overlayOpacity),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .then(dragModifier)
                    .padding(start = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DragIndicator, contentDescription = stringResource(R.string.move), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                RecognitionDot(container)
                Text(
                    "HS Tracker",
                    style = MaterialTheme.typography.labelMedium,
                    color = HsColors.Gold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { collapsed = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.minimize), modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = {
                    val intent = Intent(context, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_TRACKER, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    context.startActivity(intent)
                }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.OpenInFull, contentDescription = stringResource(R.string.open_app), modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), modifier = Modifier.size(18.dp))
                }
            }
            val scanning by remember { container.recognition.map { it.scan != null }.distinctUntilChanged() }
                .collectAsStateWithLifecycle(initialValue = false)
            if (settings.showRecognitionDebug) RecognitionDebugLine(container)
            if (!scanning) CollectionActivityCard(container)
            val current = state
            if (scanning) {
                CollectionScanPanel(container)
            } else if (current == null) {
                IdleHint(container)
            } else {
                TrackerPanel(
                    state = current,
                    db = cardState.db,
                    predictions = remember(current.opponentClass, current.opponentCards, metaState) {
                        container.predictOpponent(current, metaState)
                    },
                    showOdds = settings.overlayShowOdds,
                    compact = true,
                    showResultButtons = !settings.autoRecordMatches,
                    onUpdate = { container.tracker.update(it) },
                    onFinish = { result -> container.finishGame(result) },
                    onNewGame = { container.tracker.newGame() },
                    onTextInputChange = onTextInput,
                    decks = decks,
                    onSelectDeck = { container.selectDeckForCurrentGame(it) },
                )
            }
        }
    }
}

@Composable
private fun RecognitionDebugLine(container: AppContainer) {
    val recognition by container.recognition.collectAsStateWithLifecycle()
    if (!recognition.active) return
    Text(
        stringResource(
            R.string.recognition_debug,
            stringResource(recognition.phase.labelRes()),
            recognition.frames,
            recognition.recognized.joinToString(", "),
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun CollectionActivityCard(container: AppContainer) {
    val activity by container.collectionActivity.collectAsStateWithLifecycle()
    val current = activity ?: return
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    fun name(id: Int) = cardState.db.byDbfId(id)?.name ?: "#$id"
    val removed = -current.cards.sumOf { it.copies }
    val dust = (if (current.dust > 0) "+" else "") + formatNumber(current.dust)
    val lines: List<String> = when (current.kind) {
        CollectionActivity.Kind.PACK -> {
            val newCards = current.cards.count { it.status == ReceivedStatus.NEW }
            val duplicates = current.cards.count { it.status == ReceivedStatus.DUPLICATE }
            val summary = listOfNotNull(
                stringResource(R.string.activity_new, newCards).takeIf { newCards > 0 },
                pluralStringResource(R.plurals.activity_duplicates, duplicates, duplicates).takeIf { duplicates > 0 },
            ).joinToString(" · ")
            val shown = current.cards.sortedBy { it.status?.let { status -> PACK_ORDER.indexOf(status) } }.take(MAX_ACTIVITY_LINES)
            listOf(pluralStringResource(R.plurals.activity_pack, current.cards.size, current.cards.size), summary) +
                shown.map { changed ->
                    val card = cardState.db.byDbfId(changed.dbfId)
                    when (changed.status) {
                        ReceivedStatus.NEW -> stringResource(R.string.activity_card_new, name(changed.dbfId))
                        ReceivedStatus.DUPLICATE ->
                            stringResource(R.string.activity_card_duplicate, name(changed.dbfId), formatNumber(card?.rarityType?.disenchantValue ?: 0))
                        else -> name(changed.dbfId)
                    }
                } +
                listOfNotNull((current.cards.size - shown.size).takeIf { it > 0 }?.let { stringResource(R.string.more_issues, it) })
        }
        CollectionActivity.Kind.DISENCHANT ->
            listOf(stringResource(R.string.activity_disenchanted, current.cards.joinToString { name(it.dbfId) }), stringResource(R.string.activity_dust, dust))
        CollectionActivity.Kind.CRAFT ->
            listOf(stringResource(R.string.activity_crafted, current.cards.joinToString { name(it.dbfId) }), stringResource(R.string.activity_dust, dust))
        CollectionActivity.Kind.MASS_DISENCHANT -> if (current.undo == null) {
            listOf(stringResource(R.string.activity_mass_question), pluralStringResource(R.plurals.activity_mass_proposal, removed, removed, dust))
        } else {
            listOf(pluralStringResource(R.plurals.activity_mass_done, removed, removed), stringResource(R.string.activity_dust, dust))
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(6.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            lines.filter { it.isNotEmpty() }.forEachIndexed { i, text ->
                Text(
                    text,
                    style = if (i == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                    fontWeight = if (i == 0) FontWeight.Bold else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (current.undo == null) {
                    TextButton(onClick = { container.dismissCollectionActivity() }) { Text(stringResource(R.string.no)) }
                    TextButton(onClick = { container.confirmMassDisenchant() }) { Text(stringResource(R.string.remove)) }
                } else {
                    TextButton(onClick = { container.undoCollectionActivity() }) { Text(stringResource(R.string.undo)) }
                    TextButton(onClick = { container.dismissCollectionActivity() }) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}

private val PACK_ORDER = listOf(ReceivedStatus.NEW, ReceivedStatus.DUPLICATE, ReceivedStatus.COPY)
private const val MAX_ACTIVITY_LINES = 6

@Composable
private fun CollectionScanPanel(container: AppContainer) {
    val context = LocalContext.current
    val recognition by container.recognition.collectAsStateWithLifecycle()
    val scan = recognition.scan ?: return
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            stringResource(if (recognition.active) R.string.scan_instructions else R.string.scan_needs_capture),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.scan_progress, scan.pages, scan.cards, scan.copies), style = MaterialTheme.typography.labelLarge)
        if (scan.lastPage.isNotEmpty()) {
            Text(
                stringResource(R.string.scan_last_page, scan.lastPage.joinToString(", ")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = {
                    container.finishCollectionScan(save = true) { changed ->
                        context.toast(context.resources.getQuantityString(R.plurals.scan_saved, changed, changed))
                    }
                },
                enabled = scan.cards > 0,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = { container.finishCollectionScan(save = false) }) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun IdleHint(container: AppContainer) {
    val recognition by container.recognition.collectAsStateWithLifecycle()
    val matches by container.matches.matches.collectAsStateWithLifecycle()
    val last = matches.maxByOrNull { it.timestamp }?.takeIf { System.currentTimeMillis() - it.timestamp < RECENT_GAME_MS }
    Text(
        when {
            last != null -> stringResource(R.string.last_game_saved, last.result.label(), last.opponentClass.label())
            recognition.active -> stringResource(R.string.phase_idle)
            else -> stringResource(R.string.recognition_status_off)
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(10.dp),
    )
}

private const val RECENT_GAME_MS = 30 * 60_000L

@Composable
private fun RecognitionDot(container: AppContainer) {
    val recognition by container.recognition.collectAsStateWithLifecycle()
    val color = when {
        !recognition.active -> MaterialTheme.colorScheme.outline
        recognition.phase == VisionGameTracker.Phase.PLAYING || recognition.phase == VisionGameTracker.Phase.MULLIGAN -> HsColors.Win
        else -> HsColors.Warning
    }
    Box(
        Modifier
            .padding(end = 6.dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
