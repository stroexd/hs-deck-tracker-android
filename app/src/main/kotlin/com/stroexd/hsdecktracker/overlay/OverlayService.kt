package com.stroexd.hsdecktracker.overlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.stroexd.hsdecktracker.MainActivity
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.RecognitionStatus
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.theme.HsTheme
import com.stroexd.hsdecktracker.ui.tracker.TrackerPanel
import com.stroexd.hsdecktracker.vision.DiagnosticsRecorder
import com.stroexd.hsdecktracker.vision.ScreenRecognizer

/**
 * Vordergrund-Dienst: zeigt den Tracker als verschiebbares Fenster über Hearthstone und
 * erkennt – nach Zustimmung zur Bildschirmaufnahme – Partien automatisch per Texterkennung.
 */
class OverlayService : LifecycleService(), SavedStateRegistryOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    @Volatile
    private var overlayBounds: Rect? = null
    private var recognizer: ScreenRecognizer? = null

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
        val resultCode = intent?.getIntExtra(OverlayLauncher.EXTRA_RESULT_CODE, 0) ?: 0
        val captureData = intent?.let { IntentCompat.getParcelableExtra(it, OverlayLauncher.EXTRA_RESULT_DATA, Intent::class.java) }
        val capture = resultCode == Activity.RESULT_OK && captureData != null
        startInForeground(capture || recognizer != null)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlayView == null) showOverlay()
        if (capture && captureData != null) startRecognition(resultCode, captureData)
        return START_NOT_STICKY
    }

    private fun startInForeground(capturing: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tracker-Overlay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Wird angezeigt, solange der Tracker aktiv ist"
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
            .setContentText(if (capturing) "Erkennt Partien automatisch" else "Overlay aktiv")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Beenden", stop)
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (capturing && Build.VERSION.SDK_INT >= 29) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun startRecognition(resultCode: Int, data: Intent) {
        recognizer?.stop()
        val container = appContainer
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull() ?: return
        val diagnostics = if (container.settings.value.recordDiagnostics) {
            DiagnosticsRecorder(DiagnosticsRecorder.root(this))
        } else {
            null
        }
        val screenRecognizer = ScreenRecognizer(
            context = this,
            projection = projection,
            maskProvider = { overlayBounds },
            onFrame = { frame, bitmap ->
                val notes = diagnostics?.let { mutableListOf<String>() }
                val events = container.onScreenFrame(frame, notes)
                diagnostics?.let { runCatching { it.record(frame, events, notes.orEmpty(), bitmap) } }
            },
            onStopped = {
                container.onRecognitionStopped()
                recognizer = null
            },
        )
        recognizer = screenRecognizer
        container.onRecognitionStarted()
        runCatching { screenRecognizer.start() }.onFailure {
            screenRecognizer.stop()
        }
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
            y = 16
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
                    )
                }
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }
        }
        params = layoutParams
        overlayView = view
        windowManager.addView(view, layoutParams)
    }

    private fun updateBounds() {
        val view = overlayView ?: return
        val p = params ?: return
        overlayBounds = Rect(p.x, p.y, p.x + view.width, p.y + view.height)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val view = overlayView ?: return
        val p = params ?: return
        p.x = (p.x + dx.toInt()).coerceAtLeast(0)
        p.y = (p.y + dy.toInt()).coerceAtLeast(0)
        windowManager.updateViewLayout(view, p)
        updateBounds()
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
        recognizer?.stop()
        recognizer = null
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
) {
    val context = LocalContext.current
    val container = remember { context.appContainer }
    val state by container.tracker.state.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val metaState by container.meta.state.collectAsStateWithLifecycle()
    val recognition by container.recognition.collectAsStateWithLifecycle()
    val decks by container.decks.decks.collectAsStateWithLifecycle()
    var collapsed by remember { mutableStateOf(false) }

    // Bei Spielstart automatisch aufklappen
    LaunchedEffect(state?.startedAt) {
        if (state != null) collapsed = false
    }

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
                    state?.remainingCount?.takeIf { state?.deckCards?.isNotEmpty() == true }?.toString() ?: "HS",
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
                RecognitionDot(recognition)
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
            if (settings.showRecognitionDebug && recognition.active) {
                Text(
                    "${recognition.phaseLabel} · Bild ${recognition.frames}: " + recognition.recognized.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            val current = state
            if (current == null) {
                Text(
                    if (recognition.active) {
                        "Automatische Erkennung aktiv – der Tracker startet von selbst, sobald eine Partie beginnt (Mulligan)."
                    } else {
                        "Automatische Erkennung ist aus. Öffne die App und tippe auf „Spielen“, damit Partien automatisch erkannt werden."
                    },
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
                    decks = decks,
                    onSelectDeck = { container.selectDeckForCurrentGame(it) },
                )
            }
        }
    }
}

@Composable
private fun RecognitionDot(recognition: RecognitionStatus) {
    val color = when {
        !recognition.active -> MaterialTheme.colorScheme.outline
        recognition.phase == com.stroexd.hsdecktracker.core.vision.VisionGameTracker.Phase.PLAYING ||
            recognition.phase == com.stroexd.hsdecktracker.core.vision.VisionGameTracker.Phase.MULLIGAN -> HsColors.Win
        else -> HsColors.Warning
    }
    Box(
        Modifier
            .padding(end = 6.dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
