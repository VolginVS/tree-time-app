package com.treetime.app.focusblock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.treetime.app.MainActivity

class FocusGuardAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!FocusBlockState.isActive()) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // If user is in system UI (e.g. permission dialogs), we still redirect.
        bringAppToFront()
    }

    override fun onInterrupt() = Unit

    private fun bringAppToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }
}

