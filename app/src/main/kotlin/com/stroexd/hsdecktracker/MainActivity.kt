package com.stroexd.hsdecktracker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stroexd.hsdecktracker.ui.HsTrackerApp
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.ProvideAppLocale
import com.stroexd.hsdecktracker.ui.localized
import com.stroexd.hsdecktracker.ui.theme.HsTheme

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    private var openTracker by mutableStateOf(false)
    private var playRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        publishPlayShortcut()
        val container = appContainer
        setContent {
            val locale by container.appLocale.collectAsStateWithLifecycle()
            ProvideAppLocale(locale) {
                HsTheme {
                    CompositionLocalProvider(LocalAppContainer provides container) {
                        HsTrackerApp(
                            sharedText = sharedText,
                            onSharedTextHandled = { sharedText = null },
                            openTracker = openTracker,
                            onTrackerOpened = { openTracker = false },
                            playRequested = playRequested,
                            onPlayHandled = { playRequested = false },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_TRACKER, false)) {
            openTracker = true
        }
        if (intent.action == ACTION_PLAY) {
            playRequested = true
        }
    }

    private fun publishPlayShortcut() {
        val text = localized()
        val shortcut = ShortcutInfoCompat.Builder(this, "play")
            .setShortLabel(text.getString(R.string.play_and_track_button))
            .setLongLabel(text.getString(R.string.shortcut_play_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_play))
            .setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_PLAY))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(this, shortcut) }
    }

    companion object {
        const val EXTRA_OPEN_TRACKER = "open_tracker"
        const val ACTION_PLAY = "com.stroexd.hsdecktracker.PLAY"
    }
}
