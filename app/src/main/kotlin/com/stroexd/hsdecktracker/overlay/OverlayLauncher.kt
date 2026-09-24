package com.stroexd.hsdecktracker.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.stroexd.hsdecktracker.ui.toast

object OverlayLauncher {
    const val HEARTHSTONE_PACKAGE = "com.blizzard.wtcg.hearthstone"

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, OverlayService::class.java))
    }

    /** Startet Hearthstone, falls installiert. */
    fun launchHearthstone(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(HEARTHSTONE_PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

/**
 * Liefert eine Funktion, die das Overlay startet und dabei fehlende Berechtigungen anfragt
 * („Über anderen Apps einblenden“, ab Android 13 Benachrichtigungen).
 */
@Composable
fun rememberOverlayStarter(): () -> Unit {
    val context = LocalContext.current
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        OverlayLauncher.start(context)
    }
    return remember(context, notificationLauncher) {
        {
            when {
                !OverlayLauncher.canDrawOverlays(context) -> {
                    context.toast("Bitte „Über anderen Apps einblenden“ erlauben und das Overlay danach erneut starten.")
                    OverlayLauncher.requestOverlayPermission(context)
                }
                Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> OverlayLauncher.start(context)
            }
        }
    }
}
