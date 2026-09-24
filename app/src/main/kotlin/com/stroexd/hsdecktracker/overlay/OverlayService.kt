package com.stroexd.hsdecktracker.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.stroexd.hsdecktracker.MainActivity
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.logreader.HearthstoneLogWatcher
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.theme.HsTheme
import com.stroexd.hsdecktracker.ui.tracker.TrackerPanel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Vordergrund-Dienst, der den Tracker als verschiebbares Fenster über Hearthstone anzeigt
 * und optional die Hearthstone-Logs mitliest.
 */
class OverlayService : LifecycleService(), SavedStateRegistryOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var logJob: Job? = null
    private var logWatcher by mutableStateOf<HearthstoneLogWatcher?>(null)

    override fun onCreate() {
        savedStateController.performRestore(null)
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlayView == null) showOverlay()
        startLogWatcherIfEnabled()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tracker-Overlay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Wird angezeigt, solange das Tracker-Overlay aktiv ist"
                },
            )
        }
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
            .setContentTitle("HS Deck Tracker")
            .setContentText("Overlay aktiv – tippen, um die App zu öffnen")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Beenden", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun startLogWatcherIfEnabled() {
        val container = appContainer
        val settings = container.settings.value
        val uri = settings.logTreeUri
        if (!settings.autoTrackingEnabled || uri == null || logJob?.isActive == true) return
        val watcher = HearthstoneLogWatcher(this, container, Uri.parse(uri))
        logWatcher = watcher
        logJob = lifecycleScope.launch { watcher.run() }
    }

    private fun showOverlay() {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 160
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                HsTheme {
                    OverlayContent(
                        onDrag = { dx, dy -> moveBy(dx, dy) },
                        onClose = { stopSelf() },
                        onTextInput = { focusable -> setFocusable(focusable) },
                        logStatus = logWatcher?.status?.collectAsStateWithLifecycle()?.value,
                    )
                }
            }
        }
        params = layoutParams
        overlayView = view
        windowManager.addView(view, layoutParams)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val view = overlayView ?: return
        val p = params ?: return
        p.x = (p.x + dx.toInt()).coerceAtLeast(0)
        p.y = (p.y + dy.toInt()).coerceAtLeast(0)
        windowManager.updateViewLayout(view, p)
    }

    /** Für Texteingaben (Kartensuche) muss das Fenster kurzzeitig fokussierbar sein. */
    private fun setFocusable(focusable: Boolean) {
        val view = overlayView ?: return
        val p = params ?: return
        p.flags = if (focusable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(view, p)
    }

    override fun onDestroy() {
        logJob?.cancel()
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.stroexd.hsdecktracker.STOP_OVERLAY"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 42
    }
}

@Composable
private fun OverlayContent(
    onDrag: (Float, Float) -> Unit,
    onClose: () -> Unit,
    onTextInput: (Boolean) -> Unit,
    logStatus: String?,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = remember { context.appContainer }
    val state by container.tracker.state.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val metaState by container.meta.state.collectAsStateWithLifecycle()
    var collapsed by remember { mutableStateOf(false) }

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
                    state?.remainingCount?.toString() ?: "HS",
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
                Icon(Icons.Filled.DragIndicator, contentDescription = "Verschieben", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "HS Tracker",
                    style = MaterialTheme.typography.labelMedium,
                    color = HsColors.Gold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { collapsed = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Remove, contentDescription = "Minimieren", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = {
                    val intent = Intent(context, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_TRACKER, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    context.startActivity(intent)
                }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.OpenInFull, contentDescription = "App öffnen", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Schließen", modifier = Modifier.size(18.dp))
                }
            }
            if (logStatus != null && settings.autoTrackingEnabled) {
                Text(
                    logStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            val current = state
            if (current == null) {
                Text(
                    "Kein Deck aktiv. Öffne die App und starte den Tracker über ein Deck." +
                        if (settings.autoTrackingEnabled) " Beim automatischen Tracking wird das Deck beim Spielstart erkannt." else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp),
                )
            } else {
                TrackerPanel(
                    state = current,
                    db = cardState.db,
                    predictions = remember(current.opponentClass, current.opponentCards, metaState) {
                        container.predictOpponent(current, metaState)
                    },
                    showOdds = settings.overlayShowOdds,
                    compact = true,
                    onUpdate = { container.tracker.update(it) },
                    onFinish = { result -> container.finishGame(result) },
                    onNewGame = { container.tracker.newGame() },
                    onTextInputChange = onTextInput,
                )
            }
        }
    }
}
