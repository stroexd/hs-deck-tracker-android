@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.stroexd.hsdecktracker.ui.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.BuildConfig
import com.stroexd.hsdecktracker.core.cards.CardSets
import com.stroexd.hsdecktracker.core.data.AppSettings
import com.stroexd.hsdecktracker.core.data.Backup
import com.stroexd.hsdecktracker.core.data.BackupData
import com.stroexd.hsdecktracker.core.data.MetaSourceType
import com.stroexd.hsdecktracker.core.data.RankRange
import com.stroexd.hsdecktracker.core.data.TimeRange
import com.stroexd.hsdecktracker.core.data.cardLocales
import com.stroexd.hsdecktracker.core.util.formatNumber
import com.stroexd.hsdecktracker.logreader.HearthstoneLogWatcher
import com.stroexd.hsdecktracker.overlay.OverlayLauncher
import com.stroexd.hsdecktracker.overlay.rememberOverlayStarter
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.components.SectionHeader
import com.stroexd.hsdecktracker.ui.formatDateTime
import com.stroexd.hsdecktracker.ui.readText
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.writeText
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(navController: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val startOverlay = rememberOverlayStarter()
    var showSets by rememberSaveable { mutableStateOf(false) }
    var localeMenu by remember { mutableStateOf(false) }
    var customUrl by rememberSaveable(settings.metaCustomUrl) { mutableStateOf(settings.metaCustomUrl) }
    var pendingBackup by remember { mutableStateOf<String?>(null) }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { container.settings.update(transform) }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                .recoverCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            update { it.copy(logTreeUri = uri.toString()) }
        }
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = pendingBackup
        if (uri != null && content != null) {
            scope.launch {
                runCatching { context.writeText(uri, content) }
                    .onSuccess { snackbar.showSnackbar("Backup gespeichert") }
                    .onFailure { snackbar.showSnackbar("Backup fehlgeschlagen: ${it.message}") }
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
                    container.settings.update { current -> data.settings.copy(logTreeUri = current.logTreeUri) }
                    data
                }.onSuccess {
                    snackbar.showSnackbar("Backup geladen: ${it.decks.size} Decks, ${it.matches.size} Partien")
                }.onFailure {
                    snackbar.showSnackbar("Backup konnte nicht gelesen werden: ${it.message}")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            // ------------------------------------------------------------ Kartendaten
            item {
                SettingsCard("Kartendaten") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sprache der Karten", modifier = Modifier.weight(1f))
                        Box {
                            OutlinedButton(onClick = { localeMenu = true }) {
                                Text(cardLocales.firstOrNull { it.first == settings.cardLocale }?.second ?: settings.cardLocale)
                            }
                            DropdownMenu(expanded = localeMenu, onDismissRequest = { localeMenu = false }) {
                                cardLocales.forEach { (code, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                        localeMenu = false
                                        update { it.copy(cardLocale = code) }
                                    })
                                }
                            }
                        }
                    }
                    Text(
                        buildString {
                            append("${formatNumber(cardState.db.deckCards.size)} Karten geladen")
                            cardState.lastUpdated?.let { append(" · Stand ${formatDateTime(it)}") }
                            if (cardState.loading) append(" · lädt …")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    cardState.error?.let { Text(it, color = HsColors.Loss, style = MaterialTheme.typography.bodySmall) }
                    FilledTonalButton(onClick = { container.refreshCards() }, enabled = !cardState.loading) { Text("Jetzt aktualisieren") }
                    Text(
                        "Quelle: HearthstoneJSON (hearthstonejson.com). Kartenbilder: art.hearthstonejson.com.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // ------------------------------------------------------------ Sammlung
            item {
                SettingsCard("Sammlung & Staubkosten") {
                    SwitchRow(
                        title = "Kernset als besessen werten",
                        subtitle = "Kernset-Karten sind für alle Spieler kostenlos (über Klassenstufen freigeschaltet).",
                        checked = settings.coreSetOwned,
                        onChange = { v -> update { it.copy(coreSetOwned = v) } },
                    )
                }
            }
            // ------------------------------------------------------------ Meta
            item {
                SettingsCard("Meta-Decks") {
                    Text("Quelle", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MetaSourceType.entries.forEach { source ->
                            FilterChip(
                                selected = settings.metaSource == source,
                                onClick = { update { it.copy(metaSource = source) } },
                                label = { Text(source.displayName) },
                            )
                        }
                    }
                    if (settings.metaSource == MetaSourceType.HSREPLAY) {
                        Text("Rangbereich", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RankRange.entries.forEach { range ->
                                FilterChip(
                                    selected = settings.metaRankRange == range,
                                    onClick = { update { it.copy(metaRankRange = range) } },
                                    label = { Text(range.displayName) },
                                )
                            }
                        }
                        Text("Zeitraum", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TimeRange.entries.forEach { range ->
                                FilterChip(
                                    selected = settings.metaTimeRange == range,
                                    onClick = { update { it.copy(metaTimeRange = range) } },
                                    label = { Text(range.displayName) },
                                )
                            }
                        }
                        Text(
                            "Die Daten stammen von HSReplay.net (öffentliche Website-Schnittstelle, inoffiziell). Einige Filter sind dort nur mit Premium verfügbar – im Zweifel „Bronze – Gold“ und „Aktueller Patch“ verwenden.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text("URL einer Textdatei mit Deck-Codes") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { update { it.copy(metaCustomUrl = customUrl.trim()) } }) { Text("Übernehmen") }
                        Text(
                            "Z. B. ein GitHub-Gist (Raw-Link) mit Deck-Codes. Zeilen „### Name“ vor einem Code werden als Deckname verwendet.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // ------------------------------------------------------------ Standard-Sets
            item {
                SettingsCard("Standard-Format") {
                    Row(
                        Modifier.fillMaxWidth().clickable { showSets = !showSets },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Welche Sets sind Standard?")
                            Text(
                                "Nach einer Rotation hier anpassen. Standard: " +
                                    cardState.db.sets.filter { settings.formatRules.isStandardSet(it) }.joinToString { CardSets.displayName(it) },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(if (showSets) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                    }
                    if (settings.standardSetOverrides.isNotEmpty()) {
                        TextButton(onClick = { update { it.copy(standardSetOverrides = emptyMap()) } }) { Text("Auf Voreinstellung zurücksetzen") }
                    }
                }
            }
            if (showSets) {
                items(cardState.db.sets, key = { "set-$it" }) { set ->
                    SwitchRow(
                        title = CardSets.displayName(set),
                        subtitle = if (set in settings.standardSetOverrides) "manuell festgelegt" else null,
                        checked = settings.formatRules.isStandardSet(set),
                        onChange = { v -> update { it.copy(standardSetOverrides = it.standardSetOverrides + (set to v)) } },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            // ------------------------------------------------------------ Overlay
            item {
                SettingsCard("Overlay") {
                    var opacity by remember(settings.overlayOpacity) { mutableFloatStateOf(settings.overlayOpacity) }
                    var width by remember(settings.overlayWidthDp) { mutableFloatStateOf(settings.overlayWidthDp.toFloat()) }
                    Text("Transparenz: ${(opacity * 100).toInt()} %")
                    Slider(
                        value = opacity,
                        onValueChange = { opacity = it },
                        onValueChangeFinished = { update { it.copy(overlayOpacity = opacity) } },
                        valueRange = 0.4f..1f,
                    )
                    Text("Breite: ${width.toInt()} dp")
                    Slider(
                        value = width,
                        onValueChange = { width = it },
                        onValueChangeFinished = { update { it.copy(overlayWidthDp = width.toInt()) } },
                        valueRange = 180f..360f,
                    )
                    SwitchRow(
                        title = "Ziehwahrscheinlichkeit anzeigen",
                        checked = settings.overlayShowOdds,
                        onChange = { v -> update { it.copy(overlayShowOdds = v) } },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { startOverlay() }) { Text("Overlay starten") }
                        OutlinedButton(onClick = { OverlayLauncher.stop(context) }) { Text("Beenden") }
                    }
                    if (!OverlayLauncher.canDrawOverlays(context)) {
                        TextButton(onClick = { OverlayLauncher.requestOverlayPermission(context) }) {
                            Text("Berechtigung „Über anderen Apps einblenden“ erteilen")
                        }
                    }
                }
            }
            // ------------------------------------------------------------ Auto-Tracking
            item {
                SettingsCard("Automatisches Tracking (experimentell)") {
                    Text(
                        "Wie HDT/HSReplay: Die App liest das Power.log von Hearthstone und erkennt gezogene Karten, Karten des Gegners, " +
                            "Gegnerklasse und Ergebnis automatisch. Dafür braucht die App Zugriff auf den Ordner " +
                            "„Android/data/com.blizzard.wtcg.hearthstone/files“. Android 11+ erlaubt diesen Zugriff auf vielen Geräten " +
                            "nicht mehr – dann bleibt das manuelle Tracking (Karten antippen).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Ordner: " + (settings.logTreeUri?.let { Uri.decode(it).substringAfterLast(':') } ?: "nicht gewählt"),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { pickFolder.launch(HearthstoneLogWatcher.initialFolderUri()) }) {
                            Text("Hearthstone-Ordner wählen")
                        }
                        if (settings.logTreeUri != null) {
                            TextButton(onClick = { update { it.copy(logTreeUri = null, autoTrackingEnabled = false) } }) { Text("Entfernen") }
                        }
                    }
                    SwitchRow(
                        title = "Automatisches Tracking aktivieren",
                        subtitle = "Läuft, solange das Overlay aktiv ist. Beim ersten Mal wird eine log.config angelegt – danach Hearthstone neu starten.",
                        checked = settings.autoTrackingEnabled,
                        enabled = settings.logTreeUri != null,
                        onChange = { v -> update { it.copy(autoTrackingEnabled = v) } },
                    )
                    SwitchRow(
                        title = "Partien automatisch speichern",
                        checked = settings.autoRecordMatches,
                        onChange = { v -> update { it.copy(autoRecordMatches = v) } },
                    )
                }
            }
            // ------------------------------------------------------------ Backup
            item {
                SettingsCard("Datensicherung") {
                    Text(
                        "Decks, Sammlung, Statistik und Einstellungen als JSON-Datei sichern oder wiederherstellen.",
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
                        }) { Text("Backup erstellen") }
                        OutlinedButton(onClick = { importBackup.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                            Text("Wiederherstellen")
                        }
                    }
                }
            }
            // ------------------------------------------------------------ Über
            item {
                SettingsCard("Über") {
                    Text("HS Deck Tracker ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
                    Text(
                        "Inoffizielles Fan-Projekt. Hearthstone ist eine Marke von Blizzard Entertainment. " +
                            "Nicht verbunden mit Blizzard, HSReplay.net, HearthSim oder HearthPwn. " +
                            "Kartendaten: HearthstoneJSON (HearthSim).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
