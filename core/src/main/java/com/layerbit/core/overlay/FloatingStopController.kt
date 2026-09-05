package com.layerbit.core.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.layerbit.core.R
import kotlin.math.abs

/**
 * A draggable "Stop" bubble shown over other apps while a broadcast is live (Android's
 * `TYPE_APPLICATION_OVERLAY`, requires the user to have granted "display over other apps"),
 * so the host can end the session without switching back into the app. A tap opens a small
 * confirmation overlay first - dragging the bubble must never be mistaken for a tap, so a
 * broadcast is never killed by an accidental touch.
 */
class FloatingStopController(
    context: Context,
    private val onConfirmStop: () -> Unit
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    // A Service's application context has no Activity theme, so inflating with it directly
    // would use the platform default font instead of the app's own (Space Grotesk).
    private val themedContext = ContextThemeWrapper(appContext, R.style.Theme_Core_Overlay)
    private val inflater = LayoutInflater.from(themedContext)

    private var bubbleView: View? = null
    private var confirmView: View? = null

    fun show() {
        if (bubbleView != null) return
        addBubble()
    }

    fun hide() {
        removeConfirm()
        removeBubble()
    }

    private fun addBubble() {
        val view = inflater.inflate(R.layout.overlay_stop_bubble, null)
        // Default to the top-right corner - the common spot for a utility floating control
        // (screen recorder/mirroring apps typically default here or top-center, keeping clear
        // of the bottom gesture-nav area and most front-camera cutouts). Still fully draggable.
        val density = appContext.resources.displayMetrics.density
        val screenWidth = appContext.resources.displayMetrics.widthPixels
        val bubbleSizePx = (BUBBLE_SIZE_DP * density).toInt()
        val params = overlayLayoutParams(Gravity.TOP or Gravity.START).apply {
            x = screenWidth - bubbleSizePx - INITIAL_MARGIN_PX
            y = INITIAL_MARGIN_PX * 4
        }

        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (!isDragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) v.performClick()
                    true
                }
                else -> false
            }
        }
        view.setOnClickListener { showConfirm() }

        runCatching { windowManager.addView(view, params) }
        bubbleView = view
    }

    private fun showConfirm() {
        if (confirmView != null) return
        val view = inflater.inflate(R.layout.overlay_stop_confirm, null)
        val params = overlayLayoutParams(Gravity.CENTER)

        view.findViewById<View>(R.id.btnConfirmStop).setOnClickListener {
            removeConfirm()
            onConfirmStop()
        }
        view.findViewById<View>(R.id.btnCancelStop).setOnClickListener {
            removeConfirm()
        }

        runCatching { windowManager.addView(view, params) }
        confirmView = view
    }

    private fun removeConfirm() {
        confirmView?.let { view -> runCatching { windowManager.removeView(view) } }
        confirmView = null
    }

    private fun removeBubble() {
        bubbleView?.let { view -> runCatching { windowManager.removeView(view) } }
        bubbleView = null
    }

    private fun overlayLayoutParams(gravity: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }

    companion object {
        private const val DRAG_THRESHOLD_PX = 12
        private const val INITIAL_MARGIN_PX = 24
        private const val BUBBLE_SIZE_DP = 68
    }
}
