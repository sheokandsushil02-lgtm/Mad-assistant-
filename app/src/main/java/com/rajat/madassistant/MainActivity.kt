package com.rajat.madassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rajat.madassistant.brain.AiBrainClient
import com.rajat.madassistant.core.TargetAppManager
import com.rajat.madassistant.service.AssistantForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnSelectTarget: Button
    private lateinit var btnStart: Button
    private lateinit var btnSettings: Button
    private lateinit var targetAppManager: TargetAppManager

    /** Abhi kaunsa AI company + model select hai (dialog ke andar badalta hai). */
    private var selectedProvider: String = AiBrainClient.defaultProvider()
    private var selectedModel: String = AiBrainClient.defaultModel(AiBrainClient.defaultProvider())

    /** Fetch Available Models se aayi list (key-supported models). */
    private var fetchedModels: List<String> = emptyList()

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
        btnStart.setOnClickListener { toggleAssistant() }
        updateTargetLabel()
        refreshRunState()
    }

    override fun onResume() {
        super.onResume()
        refreshRunState()
    }

    /** Start/Stop toggle: chal raha hai to band karo, band hai to chalu karo. */
    private fun toggleAssistant() {
        if (AssistantForegroundService.isRunning) {
            stopAssistant()
        } else {
            startAssistant()
        }
    }

    private fun stopAssistant() {
        stopService(Intent(this, AssistantForegroundService::class.java))
        statusText.setText(R.string.status_idle)
        refreshRunState()
    }

    private fun startAssistant() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
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
        refreshRunState()
    }

    /** Button ko assistant ke hisaab se update karo. */
    private fun refreshRunState() {
        if (AssistantForegroundService.isRunning) {
            btnStart.setText(R.string.btn_stop)
            btnStart.setBackgroundColor(Color.parseColor("#B3261E"))
        } else {
            btnStart.setText(R.string.btn_start)
            btnStart.setBackgroundColor(Color.parseColor("#1D6F42"))
        }
    }

    /** Settings: AI company + API key + model + Fetch + Test — sab ek jagah. */
    private fun showSettingsDialog() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // Saved values load karo
        selectedProvider = prefs.getString(KEY_PROVIDER, AiBrainClient.defaultProvider())!!
            .ifBlank { AiBrainClient.defaultProvider() }
        selectedModel = prefs.getString(KEY_MODEL, "").orEmpty()
            .ifBlank { AiBrainClient.defaultModel(selectedProvider) }
        fetchedModels = emptyList()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        // --- Test result line (sabse pehle declare — baaki isko update karte hain) ---
        val testStatus = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#B58900"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        }

        // --- Model button ---
        val modelButton = Button(this).apply {
            text = "🤖  $selectedModel"
        }
        modelButton.setOnClickListener {
            val models = if (fetchedModels.isNotEmpty()) {
                fetchedModels
            } else {
                AiBrainClient.modelsFor(selectedProvider)
            }
            val display = models.toMutableList()
            display.add("✏️  Custom model (type karo)")
            showPicker("Model chuno ($selectedProvider)", display.toTypedArray(), selectedModel) { picked ->
                if (picked.startsWith("✏️")) {
                    askCustomModel { custom ->
                        if (custom.isNotBlank()) {
                            selectedModel = custom
                            modelButton.text = "🤖  $selectedModel"
                        }
                    }
                } else {
                    selectedModel = picked
                    modelButton.text = "🤖  $selectedModel"
                }
            }
        }

        // --- AI Company picker ---
        val providerLabel = TextView(this).apply {
            text = "AI Company (tap to change)"
            textSize = 12f
        }
        val providerButton = Button(this).apply {
            text = "🏢  $selectedProvider"
        }
        providerButton.setOnClickListener {
            showPicker("AI Company chuno", AiBrainClient.PROVIDERS.toTypedArray(), selectedProvider) { picked ->
                selectedProvider = picked
                selectedModel = AiBrainClient.defaultModel(picked)
                fetchedModels = emptyList()
                providerButton.text = "🏢  $selectedProvider"
                modelButton.text = "🤖  $selectedModel"
            }
        }

        val input = EditText(this).apply {
            hint = "API key (spaces hat jaayengi khud)"
            setText(prefs.getString(KEY_API, ""))
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }

        val modelLabel = TextView(this).apply {
            text = "AI Model (tap to change)"
            textSize = 12f
            setPadding(0, pad, 0, 0)
        }

        // --- Fetch Available Models button ---
        val btnFetch = Button(this).apply {
            text = "🔄 Fetch Available Models"
        }
        btnFetch.setOnClickListener {
            val key = input.text.toString().trim()
            if (key.isBlank()) {
                testStatus.text = "🔴 API key khaali hai"
                testStatus.setTextColor(Color.RED)
                return@setOnClickListener
            }
            testStatus.text = "⏳ Models aa rahi hain…"
            testStatus.setTextColor(Color.parseColor("#B58900"))
            btnFetch.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                val result = AiBrainClient.fetchGeminiModels(key)
                btnFetch.isEnabled = true
                if (result.ok) {
                    fetchedModels = result.models
                    testStatus.text = "🟢 ${result.message} — ab Model button dabao, asli list khulegi"
                    testStatus.setTextColor(Color.parseColor("#0A8F2E"))
                } else {
                    testStatus.text = "🔴 ${result.message}"
                    testStatus.setTextColor(Color.RED)
                }
            }
        }

        // --- Test Connection button ---
        val btnTest = Button(this).apply {
            text = "Test Connection"
        }
        btnTest.setOnClickListener {
            val key = input.text.toString().trim()
            if (key.isBlank()) {
                testStatus.text = "🔴 API key khaali hai"
                testStatus.setTextColor(Color.RED)
                return@setOnClickListener
            }
            testStatus.text = "⏳ Testing… (thinking models ko 20-25 sec lag sakte hain)"
            testStatus.setTextColor(Color.parseColor("#B58900"))
            btnTest.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                val result = AiBrainClient.testConnection(selectedProvider, key, selectedModel)
                if (result.ok) {
                    testStatus.text = "🟢 ${result.message}"
                    testStatus.setTextColor(Color.parseColor("#0A8F2E"))
                } else {
                    testStatus.text = "🔴 ${result.message}"
                    testStatus.setTextColor(Color.RED)
                }
                btnTest.isEnabled = true
            }
        }

        // --- UI order ---
        container.addView(providerLabel)
        container.addView(providerButton)
        container.addView(input)
        container.addView(modelLabel)
        container.addView(modelButton)
        container.addView(btnFetch)
        container.addView(btnTest)
        container.addView(testStatus)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(container)
            .setPositiveButton(R.string.btn_save_key) { _, _ ->
                prefs.edit()
                    .putString(KEY_API, input.text.toString().trim())
                    .putString(KEY_PROVIDER, selectedProvider)
                    .putString(KEY_MODEL, AiBrainClient.normalizeModel(selectedModel))
                    .apply()
                statusText.setText(R.string.status_key_saved)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Custom model naam poochho (future models ke liye). */
    private fun askCustomModel(onDone: (String) -> Unit) {
        val box = EditText(this).apply {
            hint = "Model ka exact naam type karo"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Custom Model")
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onDone(box.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Common list dialog — tap par tick ke saath select. */
    private fun showPicker(
        title: String,
        options: Array<String>,
        current: String,
        onPicked: (String) -> Unit
    ) {
        val currentIdx = options.indexOf(current).let { if (it >= 0) it else 0 }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                onPicked(options[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
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
        private const val KEY_PROVIDER = "ai_provider"
        private const val KEY_MODEL = "ai_model"
    }
}
