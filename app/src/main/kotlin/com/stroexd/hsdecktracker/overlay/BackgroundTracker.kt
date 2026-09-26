package com.stroexd.hsdecktracker.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.vision.AccessibilityScreenshotSource
import com.stroexd.hsdecktracker.vision.ScreenRecognizer
import com.stroexd.hsdecktracker.vision.startScreenRecognition

/**
 * Tracks in the background: as soon as Hearthstone is in front, the overlay appears and the screen is read through
 * accessibility screenshots, with no screen-sharing prompt. It only looks at which app is in front and never reads
 * the content of other apps.
 */
class BackgroundTracker : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: OverlayWindow? = null
    private var recognizer: ScreenRecognizer? = null
    private var passingPackages: Set<String> = emptySet()

    @Volatile
    private var hearthstoneInFront = false

    private val endSession = Runnable { stopSession() }

    override fun onServiceConnected() {
        // System UI, keyboards and permission dialogs appear over Hearthstone without leaving it
        passingPackages = setOf(packageName, "android", "com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller") +
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val app = event.packageName?.toString() ?: return
        when (app) {
            OverlayLauncher.HEARTHSTONE_PACKAGE -> setHearthstoneInFront(true)
            in passingPackages -> Unit
            else -> setHearthstoneInFront(false)
        }
    }

    private fun setHearthstoneInFront(inFront: Boolean) {
        if (inFront == hearthstoneInFront) return
        hearthstoneInFront = inFront
        if (inFront) {
            handler.removeCallbacks(endSession)
            startSession()
        } else {
            overlay?.hide()
            // A game survives a quick look at another app
            handler.postDelayed(endSession, SESSION_TIMEOUT_MS)
        }
    }

    private fun startSession() {
        val container = appContainer
        if (!isSupported || !container.settings.value.backgroundTracking) return
        // Screen sharing started by hand keeps the job
        if (recognizer == null && container.recognition.value.active) return
        val window = overlay ?: OverlayWindow(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, onClose = { overlay?.hide() })
            .also { overlay = it }
        if (container.settings.value.showOverlay) window.show()
        if (recognizer == null && Build.VERSION.SDK_INT >= 30) {
            recognizer = startScreenRecognition(
                AccessibilityScreenshotSource(this),
                maskProvider = { window.bounds },
                isActive = { hearthstoneInFront },
                onStopped = { handler.post { recognizer = null } },
            )
        }
    }

    private fun stopSession() {
        recognizer?.stop()
        recognizer = null
        overlay?.destroy()
        overlay = null
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(endSession)
        stopSession()
        super.onDestroy()
    }

    companion object {
        private const val SESSION_TIMEOUT_MS = 10 * 60_000L

        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 30

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val component = ComponentName(context, BackgroundTracker::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
        }

        fun openAccessibilitySettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Sideloaded apps need "Allow restricted settings" here before Android lets them use accessibility. */
        fun openAppInfo(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
