package com.stroexd.hsdecktracker.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import com.stroexd.hsdecktracker.core.vision.OverlayLayout
import com.stroexd.hsdecktracker.core.vision.OverlaySide
import com.stroexd.hsdecktracker.core.vision.ScreenBox
import com.stroexd.hsdecktracker.ui.ProvideAppLocale
import com.stroexd.hsdecktracker.ui.theme.HsTheme
import com.stroexd.hsdecktracker.vision.realDisplaySize
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The tracker next to Hearthstone's board: a narrow column in the empty curtain left or right of it, clear of the
 * camera and the rounded corners (see [OverlayLayout]). It brings its own lifecycle so both the screen-sharing
 * service and the accessibility service can host it; the accessibility one needs no "display over other apps"
 * permission.
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
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var view: ComposeView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        // Absolute screen coordinates, including the area around the camera
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private var side = context.appContainer.settings.value.overlaySide
    private var panel = ScreenBox(0, 0, 0, 0)
    private var collapsed = false
    private var panelSize by mutableStateOf(DpSize.Zero)

    private val rotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) = place()
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
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
            place()
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
                            panelSize = panelSize,
                            onDrag = { dx, dy -> moveBy(dx, dy) },
                            onDragEnd = { snapToSide() },
                            onCollapsedChange = { value ->
                                collapsed = value
                                place()
                            },
                            onClose = onClose,
                            onTextInput = { focusable -> setFocusable(focusable) },
                        )
                    }
                }
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }
        }
        view = composeView
        place()
        windowManager.addView(composeView, params)
        displayManager.registerDisplayListener(rotationListener, Handler(Looper.getMainLooper()))
        lifecycleScope.launch {
            context.appContainer.settings.settings.map { it.overlaySide }.distinctUntilChanged().collect {
                side = it
                place()
            }
        }
    }

    fun hide() {
        view?.visibility = View.GONE
        bounds = null
    }

    fun destroy() {
        displayManager.unregisterDisplayListener(rotationListener)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        bounds = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun place() {
        val (width, height) = realDisplaySize(context)
        val (cornerRadius, cutouts) = screenEdges()
        panel = OverlayLayout.sidePanel(
            width,
            height,
            side,
            minWidth = px(MIN_WIDTH_DP),
            maxWidth = px(MAX_WIDTH_DP),
            gap = px(GAP_DP),
            cornerRadius = cornerRadius,
            cutouts = cutouts,
        )
        panelSize = DpSize((panel.width / density).dp, (panel.height / density).dp)
        val x = if (collapsed && side == OverlaySide.RIGHT) panel.right - px(BUBBLE_SIZE.value) else panel.left
        // Display changes also fire for refresh rate switches, so only move when something changed
        if (x != params.x || panel.top != params.y) {
            params.x = x
            params.y = panel.top
            view?.takeIf { it.isAttachedToWindow }?.let { windowManager.updateViewLayout(it, params) }
        }
        updateBounds()
    }

    private fun screenEdges(): Pair<Int, List<ScreenBox>> {
        if (Build.VERSION.SDK_INT < 30) return 0 to emptyList()
        val insets = windowManager.maximumWindowMetrics.windowInsets
        val cutouts = insets.displayCutout?.boundingRects.orEmpty().map { ScreenBox(it.left, it.top, it.right, it.bottom) }
        val cornerRadius = if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                RoundedCorner.POSITION_TOP_LEFT,
                RoundedCorner.POSITION_TOP_RIGHT,
                RoundedCorner.POSITION_BOTTOM_LEFT,
                RoundedCorner.POSITION_BOTTOM_RIGHT,
            ).maxOf { insets.getRoundedCorner(it)?.radius ?: 0 }
        } else {
            0
        }
        return cornerRadius to cutouts
    }

    private fun snapToSide() {
        val current = view ?: return
        val nearest = OverlayLayout.nearestSide(params.x + current.width / 2, realDisplaySize(context).first)
        side = nearest
        place()
        val container = context.appContainer
        container.appScope.launch { container.settings.update { it.copy(overlaySide = nearest) } }
    }

    private fun updateBounds() {
        val current = view?.takeIf { it.visibility == View.VISIBLE } ?: return
        bounds = Rect(params.x, params.y, params.x + current.width, params.y + current.height)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val current = view ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
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

    private fun px(dp: Float): Int = (dp * density).toInt()

    private companion object {
        const val MIN_WIDTH_DP = 104f
        const val MAX_WIDTH_DP = 200f
        const val GAP_DP = 4f
    }
}
