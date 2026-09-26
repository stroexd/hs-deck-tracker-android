package com.stroexd.hsdecktracker.overlay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.ui.toast

object OverlayLauncher {
    const val HEARTHSTONE_PACKAGE = "com.blizzard.wtcg.hearthstone"
    const val EXTRA_RESULT_CODE = "capture_result_code"
    const val EXTRA_RESULT_DATA = "capture_result_data"

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
    }

    fun startWithCapture(context: Context, resultCode: Int, data: Intent) {
        val intent = Intent(context, OverlayService::class.java)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, OverlayService::class.java))
    }

    fun launchHearthstone(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(HEARTHSTONE_PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

/** Whether the tracker's accessibility service is on; checked again whenever the screen returns from Android's settings. */
@Composable
fun rememberBackgroundTrackerEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(BackgroundTracker.isSupported && BackgroundTracker.isEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = BackgroundTracker.isSupported && BackgroundTracker.isEnabled(context)
    }
    return enabled
}

@Composable
fun rememberBackgroundTracking(): Boolean {
    val settings by LocalContext.current.appContainer.settings.settings.collectAsStateWithLifecycle()
    return rememberBackgroundTrackerEnabled() && settings.backgroundTracking
}

@Composable
fun rememberTrackingStarter(launchGame: Boolean = true): () -> Unit {
    val context = LocalContext.current
    val currentLaunchGame by rememberUpdatedState(launchGame)
    val background by rememberUpdatedState(rememberBackgroundTracking())
    var waitingForOverlayPermission by remember { mutableStateOf(false) }

    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            OverlayLauncher.startWithCapture(context, result.resultCode, data)
        } else {
            context.toast(context.getString(R.string.capture_denied))
            OverlayLauncher.start(context)
        }
        if (currentLaunchGame && !OverlayLauncher.launchHearthstone(context)) {
            context.toast(context.getString(R.string.hearthstone_not_installed))
        }
    }
    val requestCapture: () -> Unit = remember(context, captureLauncher) {
        {
            if (context.appContainer.recognition.value.active) {
                OverlayLauncher.start(context)
                if (currentLaunchGame) OverlayLauncher.launchHearthstone(context)
            } else {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                captureLauncher.launch(manager.createScreenCaptureIntent())
            }
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestCapture()
    }
    val proceed: () -> Unit = remember(context, notificationLauncher, requestCapture) {
        {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestCapture()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, proceed) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && waitingForOverlayPermission && OverlayLauncher.canDrawOverlays(context)) {
                waitingForOverlayPermission = false
                proceed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(context, proceed) {
        {
            if (background) {
                if (!OverlayLauncher.launchHearthstone(context)) context.toast(context.getString(R.string.hearthstone_not_installed))
            } else if (!OverlayLauncher.canDrawOverlays(context)) {
                waitingForOverlayPermission = true
                context.toast(context.getString(R.string.overlay_permission_hint))
                OverlayLauncher.requestOverlayPermission(context)
            } else {
                proceed()
            }
        }
    }
}
