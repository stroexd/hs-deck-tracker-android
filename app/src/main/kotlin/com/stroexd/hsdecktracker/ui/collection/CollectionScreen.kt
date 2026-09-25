@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.stroexd.hsdecktracker.ui.collection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.stroexd.hsdecktracker.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.collection.CollectionExporter
import com.stroexd.hsdecktracker.core.collection.CollectionImportException
import com.stroexd.hsdecktracker.core.collection.CollectionImportResult
import com.stroexd.hsdecktracker.core.collection.CollectionImporter
import com.stroexd.hsdecktracker.core.collection.CollectionStats
import com.stroexd.hsdecktracker.core.collection.CollectionSummary
import com.stroexd.hsdecktracker.core.collection.CraftingCalculator
import com.stroexd.hsdecktracker.core.collection.SetProgress
import com.stroexd.hsdecktracker.core.meta.CraftRecommendation
import com.stroexd.hsdecktracker.core.meta.MetaCardStats
import com.stroexd.hsdecktracker.core.meta.MetaDeck
import com.stroexd.hsdecktracker.core.util.formatNumber
import com.stroexd.hsdecktracker.core.util.formatPercent
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.message
import com.stroexd.hsdecktracker.ui.Routes
import com.stroexd.hsdecktracker.ui.components.CardTile
import com.stroexd.hsdecktracker.ui.components.ChipRow
import com.stroexd.hsdecktracker.ui.components.CollectionCardDialog
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.components.ConfirmDialog
import com.stroexd.hsdecktracker.ui.components.CraftCostLabel
import com.stroexd.hsdecktracker.ui.components.DustLabel
import com.stroexd.hsdecktracker.ui.components.SectionHeader
import com.stroexd.hsdecktracker.ui.formatDateTime
import com.stroexd.hsdecktracker.ui.navigateTopLevel
import com.stroexd.hsdecktracker.ui.readClipboardText
import com.stroexd.hsdecktracker.ui.readText
import com.stroexd.hsdecktracker.ui.rememberComputed
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.writeText
import kotlinx.coroutines.launch

private enum class SetScope(@StringRes val label: Int) { STANDARD(R.string.standard_sets), ALL(R.string.all_sets) }

@Composable
fun CollectionScreen(navController: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val collection by container.collection.collection.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val metaState by container.meta.state.collectAsStateWithLifecycle()
    val db = cardState.db

    var setScope by rememberSaveable { mutableStateOf(SetScope.STANDARD) }
    var showPaste by remember { mutableStateOf(false) }
    var showDust by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<CollectionImportResult?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<com.stroexd.hsdecktracker.core.cards.Card?>(null) }

    fun runImport(text: String) {
        scope.launch {
            try {
                val result = CollectionImporter.import(text, container.cards.db, System.currentTimeMillis())
                container.collection.applyImport(result)
                importResult = result
            } catch (e: CollectionImportException) {
                snackbar.showSnackbar(e.message(context))
            }
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { context.readText(uri) }
                    .onSuccess { runImport(it) }
                    .onFailure { snackbar.showSnackbar(context.getString(R.string.file_read_failed, it.message.orEmpty())) }
            }
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val content = pendingExport
        if (uri != null && content != null) {
            scope.launch {
                runCatching { context.writeText(uri, content) }
                    .onSuccess { snackbar.showSnackbar(context.getString(R.string.collection_exported)) }
                    .onFailure { snackbar.showSnackbar(context.getString(R.string.export_failed, it.message.orEmpty())) }
            }
        }
        pendingExport = null
    }

    val summary by rememberComputed(db, collection, settings, setScope, initial = null as CollectionSummary?) {
        CollectionStats.summarize(db, collection, settings.collectionOptions) { set ->
            setScope == SetScope.ALL || settings.formatRules.isStandardSet(set)
        }
    }
    val buildableMeta by rememberComputed(metaState.snapshots[GameFormat.STANDARD], db, collection, settings.coreSetOwned, initial = emptyList<Pair<MetaDeck, com.stroexd.hsdecktracker.core.collection.CraftAnalysis>>()) {
        metaState.snapshots[GameFormat.STANDARD]?.decks.orEmpty()
            .map { it to CraftingCalculator.analyze(it.cards, it.sideboards, collection, db, settings.collectionOptions) }
            .filter { it.second.uncraftableMissing == 0 }
            .sortedWith(compareBy({ it.second.dustCost }, { -(it.first.winRate ?: 0.0) }))
            .distinctBy { it.first.displayName }
            .take(5)
    }

    val recommendations by rememberComputed(metaState.snapshots[GameFormat.STANDARD], db, collection, settings.coreSetOwned, initial = emptyList<CraftRecommendation>()) {
        MetaCardStats.craftRecommendations(
            metaState.snapshots[GameFormat.STANDARD]?.decks.orEmpty(),
            collection,
            db,
            settings.collectionOptions,
            limit = 10,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_collection)) },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.CRAFT_CHECK) }) {
                        Icon(Icons.Filled.Calculate, contentDescription = stringResource(R.string.check_deck_code))
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (collection.isEmpty) {
                            Text(stringResource(R.string.upload_collection_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.upload_collection_message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val s = summary
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.completion_of, stringResource(setScope.label)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatPercent(s?.fraction), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(stringResource(R.string.arcane_dust), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        DustLabel(collection.dust)
                                        IconButton(onClick = { showDust = true }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_dust)) }
                                    }
                                }
                            }
                            LinearProgressIndicator(progress = { (s?.fraction ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                            if (s != null) {
                                Text(
                                    stringResource(
                                        R.string.collection_counts,
                                        formatNumber(s.uniqueOwned),
                                        formatNumber(s.uniqueTotal),
                                        formatNumber(s.copiesOwned),
                                        formatNumber(s.copiesTotal),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (s.extraDust > 0) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.disenchant_extras), style = MaterialTheme.typography.bodySmall)
                                        DustLabel(s.extraDust, prefix = "+")
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.updated_at, formatDateTime(collection.updatedAt)) +
                                    (collection.source?.let { " · " + stringResource(R.string.source_of, it) } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { openFile.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Filled.FileUpload, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.upload_file))
                            }
                            FilledTonalButton(onClick = { showPaste = true }) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.paste_text))
                            }
                            if (!collection.isEmpty) {
                                androidx.compose.foundation.layout.Box {
                                    OutlinedButton(onClick = { exportMenu = true }) {
                                        Icon(Icons.Filled.FileDownload, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.export))
                                    }
                                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.export_json)) }, onClick = {
                                            exportMenu = false
                                            pendingExport = CollectionExporter.toJson(collection)
                                            saveFile.launch("hs-collection.json")
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.export_csv)) }, onClick = {
                                            exportMenu = false
                                            pendingExport = CollectionExporter.toCsv(collection, db)
                                            saveFile.launch("hs-collection.csv")
                                        })
                                    }
                                }
                                TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.clear)) }
                            }
                        }
                    }
                }
            }
            if (collection.isEmpty) {
                item { ImportHelpCard() }
            }
            if (!collection.isEmpty && buildableMeta.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.cheapest_meta_decks)) {
                        TextButton(onClick = { navController.navigateTopLevel(Routes.META) }) { Text(stringResource(R.string.all)) }
                    }
                }
                items(buildableMeta, key = { "meta-" + it.first.id }) { (deck, analysis) ->
                    Card(
                        onClick = { navController.navigate(Routes.metaDeck(GameFormat.STANDARD, deck.id)) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(deck.displayName, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ClassBadge(deck.heroClass)
                                    deck.winRate?.let { Text(formatPercent(it), style = MaterialTheme.typography.labelMedium) }
                                }
                            }
                            CraftCostLabel(analysis, false, collection.dust)
                        }
                    }
                }
            }
            if (!collection.isEmpty && recommendations.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.worth_crafting)) }
                items(recommendations, key = { "rec-" + it.card.dbfId }) { rec ->
                    CardTile(
                        card = rec.card,
                        name = rec.card.name,
                        cost = rec.card.cost,
                        count = rec.missing,
                        height = 48.dp,
                        subtitle = stringResource(R.string.share_of_meta_games, formatPercent(rec.popularity.overallShare, 0)) +
                            if (rec.completesDecks > 0) {
                                " · " + pluralStringResource(R.plurals.completes_decks, rec.completesDecks, rec.completesDecks)
                            } else {
                                ""
                            },
                        modifier = Modifier.padding(vertical = 2.dp),
                        onClick = { detail = rec.card },
                        trailing = { DustLabel(rec.dustCost, Modifier.padding(horizontal = 8.dp)) },
                    )
                }
            }
            item {
                Column {
                    SectionHeader(stringResource(R.string.sets))
                    ChipRow(
                        options = SetScope.entries.toList(),
                        isSelected = { it == setScope },
                        label = { context.getString(it.label) },
                        onClick = { setScope = it },
                        contentPadding = PaddingValues(0.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            items(summary?.sets.orEmpty(), key = { "set-" + it.set }) { progress ->
                SetProgressRow(progress, showOwnership = !collection.isEmpty) {
                    navController.navigate(Routes.set(progress.set))
                }
            }
        }
    }

    if (showPaste) {
        PasteCollectionDialog(onDismiss = { showPaste = false }, onImport = { text -> showPaste = false; runImport(text) })
    }
    if (showDust) {
        DustDialog(current = collection.dust, onDismiss = { showDust = false }, onSave = { value ->
            showDust = false
            scope.launch { container.collection.setDust(value) }
        })
    }
    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text(stringResource(R.string.collection_imported)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.import_format, result.detectedFormat))
                    Text(stringResource(R.string.import_counts, formatNumber(result.importedCards), formatNumber(result.importedCopies)))
                    result.dust?.let { Text(stringResource(R.string.import_dust, formatNumber(it))) }
                    if (result.unresolved.isNotEmpty()) {
                        Text(
                            stringResource(R.string.import_unresolved, result.unresolved.size, result.unresolved.take(8).joinToString()),
                            color = HsColors.Warning,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { importResult = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
    detail?.let { card -> CollectionCardDialog(card = card, onDismiss = { detail = null }) }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.clear_collection_title),
            message = stringResource(R.string.clear_collection_message),
            confirmLabel = stringResource(R.string.clear),
            onConfirm = { scope.launch { container.collection.clear() } },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun SetProgressRow(progress: SetProgress, showOwnership: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(progress.displayName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (showOwnership) {
                    Text(formatPercent(progress.fraction, 0), style = MaterialTheme.typography.labelLarge)
                } else {
                    Text(pluralStringResource(R.plurals.card_count, progress.uniqueTotal, progress.uniqueTotal), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (showOwnership) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (progress.fraction >= 1.0) HsColors.Win else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.cards_of, progress.uniqueOwned, progress.uniqueTotal),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (progress.dustToComplete > 0) DustLabel(progress.dustToComplete, prefix = stringResource(R.string.remaining_prefix))
                }
            }
        }
    }
}

@Composable
private fun ImportHelpCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.supported_formats), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.supported_formats_list),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.collection_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PasteCollectionDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paste_collection)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                    placeholder = { Text(stringResource(R.string.paste_collection_placeholder)) },
                )
                TextButton(onClick = { context.readClipboardText()?.let { text = it } }) { Text(stringResource(R.string.paste_from_clipboard)) }
            }
        },
        confirmButton = { Button(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.import_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DustDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.arcane_dust)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(7) },
                label = { Text(stringResource(R.string.current_dust)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { Button(onClick = { onSave(text.toIntOrNull() ?: 0) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
