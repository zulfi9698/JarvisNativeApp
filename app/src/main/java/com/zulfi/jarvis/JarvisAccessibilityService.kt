package com.zulfi.jarvis

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen node inspection & automatic button clicks will hook here
    }

    override fun onInterrupt() {}
}
