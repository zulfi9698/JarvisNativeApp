package com.zulfi.jarvis

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        var instance: JarvisAccessibilityService? = null
        var autoClickWhatsAppSend: Boolean = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow ?: return

        // If WhatsApp is opened by Jarvis to send a message, automatically click the Send button
        if (autoClickWhatsAppSend && event?.packageName?.toString()?.contains("whatsapp") == true) {
            val sendNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
            if (!sendNodes.isNullOrEmpty()) {
                sendNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                autoClickWhatsAppSend = false
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
