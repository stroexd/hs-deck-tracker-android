package com.stroexd.hsdecktracker.overlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.stroexd.hsdecktracker.MainActivity
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.ui.localized
import com.stroexd.hsdecktracker.vision.MediaProjectionSource
import com.stroexd.hsdecktracker.vision.ScreenRecognizer
import com.stroexd.hsdecktracker.vision.startScreenRecognition

/** Tracking through screen sharing, for devices or people without the background tracking service. */
class OverlayService : Service() {
    private var overlay: OverlayWindow? = null
    private var recognizer: ScreenRecognizer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(OverlayLauncher.EXTRA_RESULT_CODE, 0) ?: 0
        val captureData = intent?.let { IntentCompat.getParcelableExtra(it, OverlayLauncher.EXTRA_RESULT_DATA, Intent::class.java) }
        val capture = resultCode == Activity.RESULT_OK && captureData != null
        startInForeground(capture || recognizer != null)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val window = overlay ?: OverlayWindow(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, onClose = { stopSelf() })
            .also { overlay = it }
        window.show()
        if (capture && captureData != null) startRecognition(resultCode, captureData, window)
        return START_NOT_STICKY
    }

    private fun startInForeground(capturing: Boolean) {
        val text = localized()
        // Re-creating an existing channel only updates its name, so it follows language changes
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, text.getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TRACKER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracker)
            .setContentTitle(text.getString(R.string.app_name))
            .setContentText(text.getString(if (capturing) R.string.notification_recognizing else R.string.notification_overlay_active))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, text.getString(R.string.stop), stop)
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (capturing && Build.VERSION.SDK_INT >= 29) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun startRecognition(resultCode: Int, data: Intent, window: OverlayWindow) {
        recognizer?.stop()
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull() ?: return
        recognizer = startScreenRecognition(
            MediaProjectionSource(this, projection),
            maskProvider = { window.bounds },
            onStopped = { recognizer = null },
        )
    }

    override fun onDestroy() {
        recognizer?.stop()
        recognizer = null
        overlay?.destroy()
        overlay = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.stroexd.hsdecktracker.STOP_OVERLAY"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 42
    }
}
