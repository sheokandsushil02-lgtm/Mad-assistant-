package com.rajat.madassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rajat.madassistant.core.TargetAppManager
import com.rajat.madassistant.service.AssistantForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnSelectTarget: Button
    private lateinit var btnStart: Button
    private lateinit var btnSettings: Button
    private lateinit var targetAppManager: TargetAppManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        btnSelectTarget = findViewById(R.id.btnSelectTarget)
        btnStart = findViewById(R.id.btnStart)
        btnSettings = findViewById(R.id.btnSettings)
        targetAppManager = TargetAppManager(this)

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnSelectTarget.setOnClickListener { showAppPicker() }
        btnStart.setOnClickListener { startAssistant() }
        updateTargetLabel()
    }

    /** API key / connection settings live here, off the main screen. */
    private fun showSettingsDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.api_key_hint)
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API, ""))
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(container)
            .setPositiveButton(R.string.btn_save_key) { _, _ ->
                val key = input.text.toString().trim()
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_API, key)
                    .apply()
                statusText.setText(R.string.status_key_saved)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAppPicker() {
        val apps = targetAppManager.getLaunchableApps()
        if (apps.isEmpty()) {
            statusText.setText(R.string.status_no_apps)
            return
        }
        val labels = apps.map { "${it.label}  (${it.packageName})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        AlertDialog.Builder(this)
            .setTitle(R.string.select_target_title)
            .setAdapter(adapter) { _, which ->
                targetAppManager.targetPackage = apps[which].packageName
                updateTargetLabel()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateTargetLabel() {
        val pkg = targetAppManager.targetPackage
        if (pkg.isEmpty()) {
            btnSelectTarget.setText(R.string.btn_select_target)
            btnStart.isEnabled = false
        } else {
            btnSelectTarget.text = getString(R.string.btn_target_selected, targetAppManager.labelFor(pkg))
            btnStart.isEnabled = true
        }
    }

    private fun startAssistant() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (needed.isNotEmpty()) {
            statusText.setText(R.string.status_permission_needed)
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
            return
        }

        ContextCompat.startForegroundService(this, Intent(this, AssistantForegroundService::class.java))
        statusText.setText(R.string.status_listening)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) startAssistant()
    }

    companion object {
        private const val REQ_PERMISSIONS = 1001
        private const val PREFS = "mad_prefs"
        private const val KEY_API = "gemini_api_key"
    }
}
