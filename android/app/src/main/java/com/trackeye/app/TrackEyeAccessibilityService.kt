package com.trackeye.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * System-wide control: performs real click / scroll / back at the current
 * gaze position. The user must enable TrackEye under
 * Settings > Accessibility before any system action is possible.
 */
class TrackEyeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun clickAt(xPx: Float, yPx: Float) {
        val path = Path().apply { moveTo(xPx, yPx) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun scrollAt(xPx: Float, yPx: Float, downward: Boolean) {
        val dy = if (downward) -420f else 420f
        val path = Path().apply {
            moveTo(xPx, yPx)
            lineTo(xPx, yPx + dy)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    companion object {
        @Volatile
        var instance: TrackEyeAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null

        /** Latest gaze position in display pixels, updated by MainActivity. */
        @Volatile
        var gazeXPx: Float = 0f

        @Volatile
        var gazeYPx: Float = 0f
    }
}
