@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.stroexd.hsdecktracker.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.BuildConfig
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.core.cards.CardSets
import com.stroexd.hsdecktracker.core.data.AppSettings
import com.stroexd.hsdecktracker.core.data.Backup
import com.stroexd.hsdecktracker.core.data.BackupData
import com.stroexd.hsdecktracker.core.data.GameLocales
import com.stroexd.hsdecktracker.core.data.MetaSourceType
import com.stroexd.hsdecktracker.core.data.RankRange
import com.stroexd.hsdecktracker.core.data.TimeRange
import com.stroexd.hsdecktracker.core.util.formatNumber
import com.stroexd.hsdecktracker.overlay.OverlayLauncher
import com.stroexd.hsdecktracker.overlay.rememberTrackingStarter
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.components.SectionHeader
import com.stroexd.hsdecktracker.ui.formatDateTime
import com.stroexd.hsdecktracker.ui.labelRes
import com.stroexd.hsdecktracker.ui.message
import com.stroexd.hsdecktracker.ui.readText
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.writeText
import com.stroexd.hsdecktracker.vision.DiagnosticsRecorder
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(navController: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val startOverlay = rememberTrackingStarter()
    val recognition by container.recognition.collectAsStateWithLifecycle()
    var showSets by rememberSaveable { mutableStateOf(false) }
    val gameLocale by container.gameLocale.collectAsStateWithLifecycle()
    var customUrl by rememberSaveable(settings.metaCustomUrl) { mutableStateOf(settings.metaCustomUrl) }
    var pendingBackup by remember { mutableStateOf<String?>(null) }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { container.settings.update(transform) }
    }

    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = pendingBackup
        if (uri != null && content != null) {
            scope.launch {
                runCatching { context.writeText(uri, content) }
                    .onSuccess { snackbar.showSnackbar(context.getString(R.string.backup_saved)) }
                    .onFailure { snackbar.showSnackbar(context.getString(R.string.backup_failed, it.message.orEmpty())) }
            }
        }
        pendingBackup = null
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val data = Backup.import(context.readText(uri))
                    container.decks.replaceAll(data.decks)
                    container.collection.replace(data.collection)
                    container.matches.replaceAll(data.matches)
                    container.settings.update { data.settings }
                    data
                }.onSuccess {
                    snackbar.showSnackbar(context.getString(R.string.backup_restored, it.decks.size, it.matches.size))
                }.onFailure {
                    snackbar.showSnackbar(context.getString(R.string.backup_read_failed, it.message.orEmpty()))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                SettingsCard(stringResource(R.string.language_and_cards)) {
                    LanguagePicker(
                        selected = settings.language,
                        onSelect = { code -> update { it.copy(language = code) } },
                    )
                    val detected = settings.detectedGameLocale
                    Text(
                        when {
                            settings.language != GameLocales.AUTO -> stringResource(R.string.language_ui_note)
                            detected != null -> stringResource(R.string.language_auto_detected, localeName(detected))
                            else -> stringResource(R.string.language_auto_pending, localeName(gameLocale))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.cards_loaded, formatNumber(cardState.db.deckCards.size)) +
                            cardState.lastUpdated?.let { stringResource(R.string.cards_updated_at, formatDateTime(it)) }.orEmpty() +
                            if (cardState.loading) stringResource(R.string.cards_loading_suffix) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    cardState.error?.let { Text(it.message(), color = HsColors.Loss, style = MaterialTheme.typography.bodySmall) }
                    FilledTonalButton(onClick = { container.refreshCards() }, enabled = !cardState.loading) {
                        Text(stringResource(R.string.update_now))
                    }
                    Text(
                        stringResource(R.string.card_data_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SettingsCard(stringResource(R.string.collection_and_dust)) {
                    SwitchRow(
                        title = stringResource(R.string.core_set_owned),
                        subtitle = stringResource(R.string.core_set_owned_hint),
                        checked = settings.coreSetOwned,
                        onChange = { v -> update { it.copy(coreSetOwned = v) } },
                    )
                }
            }
            item {
                SettingsCard(stringResource(R.string.meta_decks)) {
                    Text(stringResource(R.string.source), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MetaSourceType.entries.forEach { source ->
                            FilterChip(
                                selected = settings.metaSource == source,
                                onClick = { update { it.copy(metaSource = source) } },
                                label = { Text(stringResource(source.labelRes())) },
                            )
                        }
                    }
                    if (settings.metaSource == MetaSourceType.HSREPLAY) {
                        Text(stringResource(R.string.rank_range), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RankRange.entries.forEach { range ->
                                FilterChip(
                                    selected = settings.metaRankRange == range,
                                    onClick = { update { it.copy(metaRankRange = range) } },
                                    label = { Text(stringResource(range.labelRes())) },
                                )
                            }
                        }
                        Text(stringResource(R.string.time_range), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TimeRange.entries.forEach { range ->
                                FilterChip(
                                    selected = settings.metaTimeRange == range,
                                    onClick = { update { it.copy(metaTimeRange = range) } },
                                    label = { Text(stringResource(range.labelRes())) },
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.hsreplay_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text(stringResource(R.string.custom_url_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { update { it.copy(metaCustomUrl = customUrl.trim()) } }) { Text(stringResource(R.string.apply)) }
                        Text(
                            stringResource(R.string.custom_url_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SettingsCard(stringResource(R.string.standard_format)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { showSets = !showSets },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.which_sets_standard))
                            Text(
                                stringResource(
                                    R.string.standard_sets_hint,
                                    cardState.db.sets.filter { settings.formatRules.isStandardSet(it) }.joinToString { CardSets.displayName(it) },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(if (showSets) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                    }
                    if (settings.standardSetOverrides.isNotEmpty()) {
                        TextButton(onClick = { update { it.copy(standardSetOverrides = emptyMap()) } }) { Text(stringResource(R.string.reset_to_default)) }
                    }
                }
            }
            if (showSets) {
                items(cardState.db.sets, key = { "set-$it" }) { set ->
                    SwitchRow(
                        title = CardSets.displayName(set),
                        subtitle = if (set in settings.standardSetOverrides) stringResource(R.string.set_manually) else null,
                        checked = settings.formatRules.isStandardSet(set),
                        onChange = { v -> update { it.copy(standardSetOverrides = it.standardSetOverrides + (set to v)) } },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            item {
                SettingsCard(stringResource(R.string.overlay)) {
                    var opacity by remember(settings.overlayOpacity) { mutableFloatStateOf(settings.overlayOpacity) }
                    var width by remember(settings.overlayWidthDp) { mutableFloatStateOf(settings.overlayWidthDp.toFloat()) }
                    Text(stringResource(R.string.opacity, (opacity * 100).toInt()))
                    Slider(
                        value = opacity,
                        onValueChange = { opacity = it },
                        onValueChangeFinished = { update { it.copy(overlayOpacity = opacity) } },
                        valueRange = 0.4f..1f,
                    )
                    Text(stringResource(R.string.overlay_width, width.toInt()))
                    Slider(
                        value = width,
                        onValueChange = { width = it },
                        onValueChangeFinished = { update { it.copy(overlayWidthDp = width.toInt()) } },
                        valueRange = 180f..360f,
                    )
                    SwitchRow(
                        title = stringResource(R.string.show_draw_odds),
                        checked = settings.overlayShowOdds,
                        onChange = { v -> update { it.copy(overlayShowOdds = v) } },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { startOverlay() }) { Text(stringResource(R.string.start_overlay)) }
                        OutlinedButton(onClick = { OverlayLauncher.stop(context) }) { Text(stringResource(R.string.stop)) }
                    }
                    if (!OverlayLauncher.canDrawOverlays(context)) {
                        TextButton(onClick = { OverlayLauncher.requestOverlayPermission(context) }) {
                            Text(stringResource(R.string.grant_overlay_permission))
                        }
                    }
                }
            }
            item {
                SettingsCard(stringResource(R.string.automatic_recognition)) {
                    Text(
                        stringResource(R.string.recognition_explained),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (recognition.active) {
                            stringResource(R.string.recognition_status_active, stringResource(recognition.phase.labelRes()), recognition.frames) +
                                if (recognition.frames > 0) {
                                    stringResource(R.string.recognition_status_saved, recognition.frames - recognition.ocrFrames)
                                } else {
                                    ""
                                }
                        } else {
                            stringResource(R.string.recognition_status_off)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (recognition.active) HsColors.Win else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = { startOverlay() }) {
                        Text(stringResource(if (recognition.active) R.string.open_hearthstone else R.string.play_and_track_button))
                    }
                    SwitchRow(
                        title = stringResource(R.string.auto_record),
                        subtitle = stringResource(R.string.auto_record_hint),
                        checked = settings.autoRecordMatches,
                        onChange = { v -> update { it.copy(autoRecordMatches = v) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.track_collection),
                        subtitle = stringResource(R.string.track_collection_hint),
                        checked = settings.trackCollectionChanges,
                        onChange = { v -> update { it.copy(trackCollectionChanges = v) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.show_recognized_texts),
                        subtitle = stringResource(R.string.show_recognized_texts_hint),
                        checked = settings.showRecognitionDebug,
                        onChange = { v -> update { it.copy(showRecognitionDebug = v) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.record_diagnostics),
                        subtitle = stringResource(R.string.record_diagnostics_hint),
                        checked = settings.recordDiagnostics,
                        onChange = { v -> update { it.copy(recordDiagnostics = v) } },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            if (!DiagnosticsRecorder.share(context)) {
                                scope.launch { snackbar.showSnackbar(context.getString(R.string.no_diagnostics)) }
                            }
                        }) { Text(stringResource(R.string.share_diagnostics)) }
                        TextButton(onClick = {
                            DiagnosticsRecorder.clear(context)
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.diagnostics_deleted)) }
                        }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
            item {
                SettingsCard(stringResource(R.string.backup)) {
                    Text(
                        stringResource(R.string.backup_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            pendingBackup = Backup.export(
                                BackupData(
                                    exportedAt = System.currentTimeMillis(),
                                    decks = container.decks.decks.value,
                                    collection = container.collection.collection.value,
                                    matches = container.matches.matches.value,
                                    settings = container.settings.value,
                                ),
                            )
                            exportBackup.launch("hs-deck-tracker-backup.json")
                        }) { Text(stringResource(R.string.create_backup)) }
                        OutlinedButton(onClick = { importBackup.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                            Text(stringResource(R.string.restore))
                        }
                    }
                }
            }
            item {
                SettingsCard(stringResource(R.string.about)) {
                    Text("HS Deck Tracker ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.about_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun localeName(code: String): String = GameLocales.all.firstOrNull { it.first == code }?.second ?: code

@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.language_label), modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(if (selected == GameLocales.AUTO) stringResource(R.string.language_auto) else localeName(selected))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                val options = listOf(GameLocales.AUTO to stringResource(R.string.language_auto)) + GameLocales.all
                options.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        expanded = false
                        onSelect(code)
                    })
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column {
        SectionHeader(title)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
