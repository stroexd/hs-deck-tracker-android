package com.stroexd.hsdecktracker.ui.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.overlay.BackgroundTracker

/** Background tracking needs the app's accessibility service switched on once. */
@Composable
fun BackgroundSetupDialog(onDismiss: () -> Unit, onUseScreenSharing: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.background_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.background_setup_step))
                FilledTonalButton(onClick = { BackgroundTracker.openAccessibilitySettings(context) }) {
                    Text(stringResource(R.string.open_accessibility))
                }
                Text(
                    stringResource(R.string.background_setup_restricted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { BackgroundTracker.openAppInfo(context) }) { Text(stringResource(R.string.open_app_info)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                onUseScreenSharing()
            }) { Text(stringResource(R.string.use_screen_sharing)) }
        },
    )
}
