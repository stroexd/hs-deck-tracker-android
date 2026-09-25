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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.stroexd.hsdecktracker.AppContainer
import com.stroexd.hsdecktracker.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.util.Date

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

@Composable
fun <T> rememberComputed(vararg keys: Any?, initial: T, compute: () -> T): State<T> =
    produceState(initialValue = initial, *keys) {
        value = withContext(Dispatchers.Default) { compute() }
    }

fun Context.copyToClipboard(label: String, text: String, toast: String? = getString(R.string.copied)) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    if (toast != null) toast(toast)
}

fun Context.readClipboardText(): String? {
    val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(this)?.toString()
}

fun Context.shareText(subject: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, getString(R.string.share)))
}

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

suspend fun Context.readText(uri: Uri): String = withContext(Dispatchers.IO) {
    contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: throw IOException("Can't open $uri")
}

suspend fun Context.writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("Can't write $uri")
}

fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

fun formatDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

@Composable
fun timeAgo(millis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - millis) / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.just_now)
        minutes < 60 -> pluralStringResource(R.plurals.minutes_ago, minutes, minutes)
        minutes < 24 * 60 -> pluralStringResource(R.plurals.hours_ago, minutes / 60, minutes / 60)
        minutes < 7 * 24 * 60 -> pluralStringResource(R.plurals.days_ago, minutes / (24 * 60), minutes / (24 * 60))
        else -> formatDate(millis)
    }
}

fun formatDuration(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return "–"
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
