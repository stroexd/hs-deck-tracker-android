package com.stroexd.hsdecktracker.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.ui.ProvideAppLocale
import com.stroexd.hsdecktracker.ui.theme.HsTheme

/**
 * The floating tracker on top of Hearthstone. It brings its own lifecycle so both the screen-sharing service and
 * the accessibility service can host it; the accessibility one needs no "display over other apps" permission.
 */
class OverlayWindow(
    private val context: Context,
    windowType: Int,
    private val onClose: () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 16
        y = 16
    }

    /** Where the overlay covers the screen, so recognition can ignore it. */
    @Volatile
    var bounds: Rect? = null
        private set

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun show() {
        view?.let {
            it.visibility = View.VISIBLE
            updateBounds()
            return
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayWindow)
            setViewTreeSavedStateRegistryOwner(this@OverlayWindow)
            setContent {
                val locale by context.appContainer.appLocale.collectAsStateWithLifecycle()
                ProvideAppLocale(locale) {
                    HsTheme {
                        OverlayContent(
                            onDrag = { dx, dy -> moveBy(dx, dy) },
                            onClose = onClose,
                            onTextInput = { focusable -> setFocusable(focusable) },
                        )
                    }
                }
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }
        }
        view = composeView
        windowManager.addView(composeView, params)
    }

    fun hide() {
        view?.visibility = View.GONE
        bounds = null
    }

    fun destroy() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        bounds = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun updateBounds() {
        val current = view?.takeIf { it.visibility == View.VISIBLE } ?: return
        bounds = Rect(params.x, params.y, params.x + current.width, params.y + current.height)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val current = view ?: return
        params.x = (params.x + dx.toInt()).coerceAtLeast(0)
        params.y = (params.y + dy.toInt()).coerceAtLeast(0)
        windowManager.updateViewLayout(current, params)
        updateBounds()
    }

    private fun setFocusable(focusable: Boolean) {
        val current = view ?: return
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(current, params)
    }
}
