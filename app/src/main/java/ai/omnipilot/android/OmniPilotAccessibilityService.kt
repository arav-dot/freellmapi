package ai.omnipilot.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class OmniPilotAccessibilityService : AccessibilityService() {
    companion object { var instance: OmniPilotAccessibilityService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun tap(x: Float, y: Float): Boolean {
        val metrics = resources.displayMetrics
        if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f ||
            x >= metrics.widthPixels || y >= metrics.heightPixels
        ) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 350): Boolean {
        val metrics = resources.displayMetrics
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite() ||
            x1 < 0f || y1 < 0f || x2 < 0f || y2 < 0f ||
            x1 >= metrics.widthPixels || x2 >= metrics.widthPixels ||
            y1 >= metrics.heightPixels || y2 >= metrics.heightPixels || duration !in 1..2_000
        ) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun typeText(text: String): Boolean {
        if (text.length > 500) return false
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
    }
}
