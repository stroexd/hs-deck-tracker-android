package com.stroexd.hsdecktracker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import com.stroexd.hsdecktracker.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer nicht gesetzt") }

/** Berechnet einen Wert im Hintergrund neu, sobald sich ein Schlüssel ändert (ohne Flackern). */
@Composable
fun <T> rememberComputed(vararg keys: Any?, initial: T, compute: () -> T): State<T> =
    produceState(initialValue = initial, *keys) {
        value = withContext(Dispatchers.Default) { compute() }
    }

fun Context.copyToClipboard(label: String, text: String, toast: String? = "In die Zwischenablage kopiert") {
    val clipboard = getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    if (toast != null) Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
}

fun Context.readClipboardText(): String? {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(this)?.toString()
}

fun Context.shareText(subject: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, "Teilen"))
}

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

suspend fun Context.readText(uri: Uri): String = withContext(Dispatchers.IO) {
    contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        ?: throw IllegalStateException("Datei konnte nicht geöffnet werden")
}

suspend fun Context.writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
        ?: throw IllegalStateException("Datei konnte nicht geschrieben werden")
}

fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.GERMANY).format(Date(millis))

fun formatDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.GERMANY).format(Date(millis))

fun timeAgo(millis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - millis) / 60_000
    return when {
        minutes < 1 -> "gerade eben"
        minutes < 60 -> "vor $minutes Min."
        minutes < 24 * 60 -> "vor ${minutes / 60} Std."
        minutes < 7 * 24 * 60 -> "vor ${minutes / (24 * 60)} Tagen"
        else -> formatDate(millis)
    }
}

fun formatDuration(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return "–"
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
