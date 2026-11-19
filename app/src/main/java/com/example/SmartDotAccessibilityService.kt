package com.example

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class SmartDotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("SmartDotAccessibility", "Accessibility Service Successfully Hooked and Enabled")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Mandatory callback for AccessibilityService
    }

    override fun onInterrupt() {
        // Mandatory callback for AccessibilityService
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("SmartDotAccessibility", "Accessibility Service Disconnected")
        if (instance == this) {
            instance = null
        }
    }

    companion object {
        @Volatile
        var instance: SmartDotAccessibilityService? = null
            private set

        fun isEnabled(): Boolean {
            return instance != null
        }
    }
}
