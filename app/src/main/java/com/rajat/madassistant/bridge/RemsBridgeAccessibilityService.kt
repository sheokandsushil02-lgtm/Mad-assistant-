package com.rajat.madassistant.bridge

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.rajat.madassistant.core.TargetAppManager

class RemsBridgeAccessibilityService : AccessibilityService() {

    private var targetPackage: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        targetPackage = TargetAppManager(this).targetPackage
        Log.i(TAG, "Bridge connected. Target app: $targetPackage")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (targetPackage.isEmpty()) {
            targetPackage = TargetAppManager(this).targetPackage
        }
        val pkg = event.packageName?.toString() ?: return
        if (targetPackage.isNotEmpty() && pkg != targetPackage) {
            return
        }
    }

    override fun onInterrupt() {
    }

    fun openTargetApp() {
        if (targetPackage.isEmpty()) return
        val launch = packageManager.getLaunchIntentForPackage(targetPackage) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    companion object {
        private const val TAG = "RemsBridge"
    }
}
