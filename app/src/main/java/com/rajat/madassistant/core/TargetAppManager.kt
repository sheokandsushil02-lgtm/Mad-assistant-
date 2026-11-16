package com.rajat.madassistant.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

data class AppInfo(val packageName: String, val label: String)

class TargetAppManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** REMS Business app (the primary target MAD controls). */
    var targetPackage: String
        get() = prefs.getString(KEY_TARGET_PACKAGE, null) ?: DEFAULT_TARGET_PACKAGE
        set(value) {
            prefs.edit().putString(KEY_TARGET_PACKAGE, value).apply()
        }

    /** True when the saved package is the built-in REMS default. */
    val isRemsTarget: Boolean
        get() = targetPackage == DEFAULT_TARGET_PACKAGE

    fun getLaunchableApps(): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = mutableListOf<AppInfo>()
        for (info in pm.queryIntentActivities(intent, 0)) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) continue
            apps.add(AppInfo(pkg, info.loadLabel(pm).toString()))
        }
        return apps.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    fun labelFor(pkg: String): String {
        if (pkg.isEmpty()) return ""
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    companion object {
        private const val PREFS_NAME = "mad_prefs"
        private const val KEY_TARGET_PACKAGE = "target_package"

        /** REMS Business app package, from its manifest. */
        const val DEFAULT_TARGET_PACKAGE = "com.aistudio.remsbusiness.kpxwza"
    }
}
