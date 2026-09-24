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
import com.stroexd.hsdecktracker.ui.HsTrackerApp
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.theme.HsTheme

class MainActivity : ComponentActivity() {

    /** Per „Teilen“ empfangener Text (z. B. ein Deck-Code aus dem Browser). */
    private var sharedText by mutableStateOf<String?>(null)
    private var openTracker by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val container = appContainer
        setContent {
            HsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    HsTrackerApp(
                        sharedText = sharedText,
                        onSharedTextHandled = { sharedText = null },
                        openTracker = openTracker,
                        onTrackerOpened = { openTracker = false },
                    )
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
    }

    companion object {
        const val EXTRA_OPEN_TRACKER = "open_tracker"
    }
}
