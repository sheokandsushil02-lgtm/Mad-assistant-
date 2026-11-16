package com.rajat.madassistant

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rajat.madassistant.brain.AiBrainClient
import com.rajat.madassistant.bridge.RemsBridgeAccessibilityService
import com.rajat.madassistant.core.TargetAppManager
import com.rajat.madassistant.service.AssistantForegroundService
import com.rajat.madassistant.speech.MadEvents
import com.rajat.madassistant.speech.MadSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var micStatus: TextView
    private lateinit var aiStatus: TextView
    private lateinit var ttsStatus: TextView
    private lateinit var btnSelectTarget: Button
    private lateinit var btnStart: Button
    private lateinit var btnMic: Button
    private lateinit var btnSettings: Button
    private lateinit var targetAppManager: TargetAppManager

    private var selectedProvider: String = AiBrainClient.defaultProvider()
    private var selectedModel: String = AiBrainClient.defaultModel(AiBrainClient.defaultProvider())
    private var fetchedModels: List<String> = emptyList()

    /** Live status receiver — engine yahi se live Mic/AI/TTS + "Heard:..." bhejta hai. */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val text = i?.getStringExtra(MadEvents.EXTRA_TEXT) ?: return
            runOnUiThread { updateStatus(text) }
        }
    }

    /** "Mic: ...", "AI: ...", "TTS: ..." lines ko apne-apne debug rows me daalta hai. */
    private fun updateStatus(text: String) {
        when {
            text.startsWith("Mic:") -> micStatus.text = text
            text.startsWith("AI:") -> aiStatus.text = text
            text.startsWith("TTS:") -> ttsStatus.text = text
            text.startsWith("Heard:") -> {
                aiStatus.text = "AI: ${text.removePrefix("Heard:").trim()}"
                micStatus.text = "Mic: idle (paused)"
            }
            else -> statusText.text = text
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        micStatus = findViewById(R.id.micStatus)
        aiStatus = findViewById(R.id.aiStatus)
        ttsStatus = findViewById(R.id.ttsStatus)
        btnSelectTarget = findViewById(R.id.btnSelectTarget)
        btnStart = findViewById(R.id.btnStart)
        btnMic = findViewById(R.id.btnMic)
        btnSettings = findViewById(R.id.btnSettings)
        targetAppManager = TargetAppManager(this)

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnSelectTarget.setOnClickListener { showAppPicker() }
        btnStart.setOnClickListener { toggleAssistant() }
        btnMic.setOnClickListener {
            if (AssistantForegroundService.isRunning) {
                micStatus.text = "Mic: launching command session..."
                startService(
                    Intent(this, AssistantForegroundService::class.java)
                        .setAction(AssistantForegroundService.ACTION_LISTEN_NOW)
                )
            } else {
                Toast.makeText(this, "Pehle Start Assistant dabao", Toast.LENGTH_SHORT).show()
            }
        }
        updateTargetLabel()
        refreshRunState()
    }

    override fun onResume() {
        super.onResume()
        refreshRunState()
        val filter = IntentFilter(MadEvents.STATUS)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            else registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        runCatching { unregisterReceiver(statusReceiver) }
        super.onPause()
    }

    /** Start/Stop toggle: chal raha hai to band karo, band hai to chalu karo. */
    private fun toggleAssistant() {
        if (AssistantForegroundService.isRunning) stopAssistant() else startAssistant()
    }

    private fun stopAssistant() {
        stopService(Intent(this, AssistantForegroundService::class.java))
        statusText.setText(R.string.status_idle)
        micStatus.text = "Mic: idle (stopped)"
        aiStatus.text = "AI: stopped"
        ttsStatus.text = "TTS: stopped"
        refreshRunState()
    }

    private fun startAssistant() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.RECORD_AUDIO)

        if (needed.isNotEmpty()) {
            statusText.setText(R.string.status_permission_needed)
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, AssistantForegroundService::class.java))
        statusText.setText(R.string.status_listening)
        micStatus.text = "Mic: starting..."
        aiStatus.text = "AI: starting..."
        ttsStatus.text = "TTS: starting..."
        refreshRunState()
    }

    private fun refreshRunState() {
        if (AssistantForegroundService.isRunning) {
            btnStart.setText(R.string.btn_stop)
            btnStart.setBackgroundColor(Color.parseColor("#B3261E"))
        } else {
            btnStart.setText(R.string.btn_start)
            btnStart.setBackgroundColor(Color.parseColor("#1D6F42"))
        }
    }

    // ================= SETTINGS DASHBOARD =================

    private fun showSettingsDialog() {
        val pad = dp(12)
        val prefs = getSharedPreferences(MadSettings.PREFS, MODE_PRIVATE)

        selectedProvider = prefs.getString(MadSettings.KEY_PROVIDER, AiBrainClient.defaultProvider())!!
            .ifBlank { AiBrainClient.defaultProvider() }
        selectedModel = prefs.getString(MadSettings.KEY_MODEL, "").orEmpty()
            .ifBlank { AiBrainClient.defaultModel(selectedProvider) }
        fetchedModels = emptyList()

        val scroll = ScrollViewWrap(this)
        val root = scroll.content

        // ===== SECTION 1: AI Provider & Model =====
        root.addView(sectionTitle("🧠 AI Provider & Model"))
        val providerButton = Button(this).apply { text = "🏢  $selectedProvider" }
        val modelButton = Button(this).apply { text = "🤖  $selectedModel" }
        val keyInput = EditText(this).apply {
            hint = "API key"
            setText(prefs.getString(MadSettings.KEY_API, ""))
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val fetchBtn = Button(this).apply { text = "🔄 Fetch Available Models" }
        val testBtn = Button(this).apply { text = "Test Connection" }
        val testStatus = TextView(this).apply {
            textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B58900"))
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val providerLabel = TextView(this).apply { text = "AI Company"; textSize = 12f }
        val modelLabel = TextView(this).apply { text = "AI Model"; textSize = 12f }

        providerButton.setOnClickListener {
            showPicker("AI Company chuno", AiBrainClient.PROVIDERS.toTypedArray(), selectedProvider) { p ->
                selectedProvider = p
                selectedModel = AiBrainClient.defaultModel(p)
                fetchedModels = emptyList()
                providerButton.text = "🏢  $selectedProvider"
                modelButton.text = "🤖  $selectedModel"
            }
        }
        modelButton.setOnClickListener {
            val models = (if (fetchedModels.isNotEmpty()) fetchedModels else AiBrainClient.modelsFor(selectedProvider)).toMutableList()
            models.add("✏️  Custom model (type karo)")
            showPicker("Model chuno ($selectedProvider)", models.toTypedArray(), selectedModel) { m ->
                if (m.startsWith("✏️")) {
                    askCustomModel { custom ->
                        if (custom.isNotBlank()) { selectedModel = custom; modelButton.text = "🤖  $selectedModel" }
                    }
                } else { selectedModel = m; modelButton.text = "🤖  $selectedModel" }
            }
        }
        fetchBtn.setOnClickListener {
            val key = keyInput.text.toString().trim()
            if (key.isBlank()) { testStatus.text = "🔴 API key khaali hai"; testStatus.setTextColor(Color.RED); return@setOnClickListener }
            testStatus.text = "⏳ Models aa rahi hain…"
            fetchBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                val r = AiBrainClient.fetchGeminiModels(key)
                fetchBtn.isEnabled = true
                if (r.ok) { fetchedModels = r.models; testStatus.text = "🟢 ${r.message} — Model button dabao"; testStatus.setTextColor(Color.parseColor("#0A8F2E")) }
                else { testStatus.text = "🔴 ${r.message}"; testStatus.setTextColor(Color.RED) }
            }
        }
        testBtn.setOnClickListener {
            val key = keyInput.text.toString().trim()
            if (key.isBlank()) { testStatus.text = "🔴 API key khaali hai"; testStatus.setTextColor(Color.RED); return@setOnClickListener }
            testStatus.text = "⏳ Testing… (thinking models 20-25 sec)"
            testBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                val r = AiBrainClient.testConnection(selectedProvider, key, selectedModel)
                if (r.ok) { testStatus.text = "🟢 ${r.message}"; testStatus.setTextColor(Color.parseColor("#0A8F2E")) }
                else { testStatus.text = "🔴 ${r.message}"; testStatus.setTextColor(Color.RED) }
                testBtn.isEnabled = true
            }
        }

        root.addView(providerLabel); root.addView(providerButton)
        root.addView(keyInput)
        root.addView(modelLabel); root.addView(modelButton)
        root.addView(fetchBtn); root.addView(testBtn); root.addView(testStatus)

        // ===== SECTION 2: Permissions Dashboard =====
        root.addView(sectionTitle("✅ Permissions & System"))
        val micRow = permRow("🎤 Microphone") { hasMic() }
        val accRow = permRow("♿ Accessibility") { hasAccessibility() }
        val overRow = permRow("🖥 Overlay") { Settings.canDrawOverlays(this) }
        val batRow = permRow("🔋 Battery") { hasBatteryExemption() }
        val notifRow = permRow("🔔 Notifications") { hasNotifications() }
        micRow.button.setOnClickListener { askMic() }
        accRow.button.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        overRow.button.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        batRow.button.setOnClickListener {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        notifRow.button.setOnClickListener { askNotifications() }
        root.addView(micRow.row); root.addView(accRow.row); root.addView(overRow.row)
        root.addView(batRow.row); root.addView(notifRow.row)

        // ===== SECTION 3: Voice & Personality =====
        root.addView(sectionTitle("🎙 Voice & Personality"))
        val genderButton = Button(this).apply {
            text = "Voice: " + prettyGender(prefs.getString(MadSettings.KEY_VOICE_GENDER, "system") ?: "system")
        }
        genderButton.setOnClickListener {
            showPicker("Voice chuno", arrayOf("System Default", "Male (Jarvis)", "Female (Maya)"), prettyGender(prefs.getString(MadSettings.KEY_VOICE_GENDER, "system") ?: "system")) { g ->
                val v = when (g) {
                    "Male (Jarvis)" -> "male"
                    "Female (Maya)" -> "female"
                    else -> "system"
                }
                prefs.edit().putString(MadSettings.KEY_VOICE_GENDER, v).apply()
                genderButton.text = "Voice: " + prettyGender(v)
            }
        }
        root.addView(genderButton)

        // Engine voice dropdown — Google TTS ke available hi-IN/en-IN voices
        val voiceButton = Button(this).apply {
            text = "Engine Voice: " + ((prefs.getString(MadSettings.KEY_VOICE_NAME, "") ?: "").ifBlank { "Auto (best)" })
        }
        voiceButton.setOnClickListener { showVoicePicker(prefs, voiceButton) }
        root.addView(voiceButton)

        root.addView(hint("Pitch (0.7 - 1.4, default 1.0)"))
        val pitchBar = SeekBar(this)
        pitchBar.max = 70
        pitchBar.progress = (((prefs.getString(MadSettings.KEY_PITCH, "1.0") ?: "1.0").toFloatOrNull() ?: 1f) * 100 - 70).toInt()
        pitchBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putString(MadSettings.KEY_PITCH, "%.2f".format((p + 70) / 100f)).apply()
            }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {}
        })
        root.addView(pitchBar)

        root.addView(hint("Speed (0.7 - 1.5, default 0.95)"))
        val rateBar = SeekBar(this)
        rateBar.max = 80
        rateBar.progress = (((prefs.getString(MadSettings.KEY_RATE, "0.95") ?: "0.95").toFloatOrNull() ?: 0.95f) * 100 - 70).toInt()
        rateBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putString(MadSettings.KEY_RATE, "%.2f".format((p + 70) / 100f)).apply()
            }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {}
        })
        root.addView(rateBar)

        val langButton = Button(this).apply {
            text = "Language: " + prettyLang(prefs.getString(MadSettings.KEY_LANG, "hinglish") ?: "hinglish")
        }
        langButton.setOnClickListener {
            showPicker("Language chuno", arrayOf("Hinglish", "Hindi", "English"), prettyLang(prefs.getString(MadSettings.KEY_LANG, "hinglish") ?: "hinglish")) { l ->
                val v = when (l) {
                    "Hindi" -> "hindi"
                    "English" -> "english"
                    else -> "hinglish"
                }
                prefs.edit().putString(MadSettings.KEY_LANG, v).apply()
                langButton.text = "Language: " + prettyLang(v)
            }
        }
        root.addView(langButton)

        val testVoiceBtn = Button(this).apply { text = "🔊 Test Voice" }
        testVoiceBtn.setOnClickListener {
            prefs.edit()
                .putString(MadSettings.KEY_API, keyInput.text.toString().trim())
                .putString(MadSettings.KEY_PROVIDER, selectedProvider)
                .putString(MadSettings.KEY_MODEL, AiBrainClient.normalizeModel(selectedModel))
                .apply()
            val i = Intent(this, AssistantForegroundService::class.java).setAction(AssistantForegroundService.ACTION_TEST_VOICE)
            if (AssistantForegroundService.isRunning) startService(i) else Toast.makeText(this, "Pehle assistant Start karo", Toast.LENGTH_SHORT).show()
        }
        root.addView(testVoiceBtn)

        // ===== SECTION 4: Wake Word & Triggers =====
        root.addView(sectionTitle("⚡ Wake Word & Triggers"))
        val wakeInput = EditText(this).apply {
            hint = "Wake word (default: hey mad)"
            setText(prefs.getString(MadSettings.KEY_WAKE, "hey mad") ?: "hey mad")
            setSingleLine()
        }
        root.addView(wakeInput)
        root.addView(toggle("Continuous Wake-Word Listening", MadSettings.KEY_WAKE_ENABLED, true))
        root.addView(toggle("Voice Reply (TTS)", MadSettings.KEY_TTS_ENABLED, true))
        root.addView(toggle("Beep on Wake", MadSettings.KEY_BEEP_ENABLED, true))

        // ===== SECTION 5: Persona =====
        root.addView(sectionTitle("🤵 Assistant Persona"))
        val personaInput = EditText(this).apply {
            hint = "Persona / custom instructions"
            setText(prefs.getString(MadSettings.KEY_PERSONA, MadSettings.personaDefault()) ?: MadSettings.personaDefault())
            minLines = 3
            gravity = Gravity.TOP
        }
        root.addView(personaInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(scroll.view)
            .setPositiveButton(R.string.btn_save_key) { _, _ ->
                prefs.edit()
                    .putString(MadSettings.KEY_API, keyInput.text.toString().trim())
                    .putString(MadSettings.KEY_PROVIDER, selectedProvider)
                    .putString(MadSettings.KEY_MODEL, AiBrainClient.normalizeModel(selectedModel))
                    .putString(MadSettings.KEY_WAKE, wakeInput.text.toString().trim().ifBlank { "hey mad" })
                    .putString(MadSettings.KEY_PERSONA, personaInput.text.toString().trim().ifBlank { MadSettings.personaDefault() })
                    .apply()
                // Running service ko naye settings turant reload karo (restart nahi chahiye)
                if (AssistantForegroundService.isRunning) {
                    startService(Intent(this, AssistantForegroundService::class.java)
                        .setAction(AssistantForegroundService.ACTION_RELOAD))
                }
                statusText.setText(R.string.status_key_saved)
                refreshRunState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Google TTS engine ki hi-IN/en-IN voices — dropdown + network/HD badges ke saath. */
    private fun showVoicePicker(prefs: android.content.SharedPreferences, voiceButton: Button) {
        val list = ttsList
        if (list == null) {
            Toast.makeText(this, "TTS abhi load ho raha hai — thoda der baad try karo", Toast.LENGTH_SHORT).show()
            return
        }
        if (list.isEmpty()) {
            Toast.makeText(this, "Google TTS engine ki voices nahi mili — service start hone do", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { prettyVoice(it) }
        val options = listOf("Auto (best natural voice)") + labels
        val current = prefs.getString(MadSettings.KEY_VOICE_NAME, "") ?: ""
        val idx = labels.indexOf(current).let { if (it >= 0) it + 1 else 0 }
        showPicker("Engine Voice chuno (hi-IN / en-IN)", options.toTypedArray(), options[idx.coerceAtMost(options.size - 1)]) { pick ->
            if (pick.startsWith("Auto")) {
                prefs.edit().putString(MadSettings.KEY_VOICE_NAME, "").apply()
                voiceButton.text = "Engine Voice: Auto (best)"
            } else {
                val chosen = list[options.indexOf(pick) - 1]
                prefs.edit().putString(MadSettings.KEY_VOICE_NAME, chosen.name).apply()
                voiceButton.text = "Engine Voice: " + chosen.name
            }
        }
    }

    private fun prettyVoice(v: android.speech.tts.Voice): String {
        val tags = mutableListOf<String>()
        if (v.name.endsWith("-network")) tags.add("HD")
        if (v.quality == android.speech.tts.Voice.QUALITY_VERY_HIGH || v.quality == android.speech.tts.Voice.QUALITY_HIGH) tags.add("HQ")
        val tag = if (tags.isEmpty()) "" else " [" + tags.joinToString(" ") + "]"
        return "${v.name}$tag (${v.locale})"
    }

    // ---------- helpers ----------

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun hasAccessibility(): Boolean {
        val expected = ComponentName(this, RemsBridgeAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val splitter = TextUtilsSimple.split(enabled, ':')
        return splitter.any { it.equals(expected.flattenToString(), ignoreCase = true) }
    }

    private fun hasBatteryExemption(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun askMic() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    private class PermRow(val row: LinearLayout, val button: Button)

    private fun permRow(label: String, check: () -> Boolean): PermRow {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tv = TextView(this).apply { text = label; textSize = 15f; setTextColor(Color.WHITE) }
        val dot = View(this)
        dot.background = null
        val state = TextView(this).apply {
            text = if (check()) "🟢 Enabled" else "🔴 Disabled"
            textSize = 13f
            setPadding(dp(8), 0, dp(8), 0)
        }
        val btn = Button(this).apply { text = if (check()) "OK" else "Enable" }
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(state)
        row.addView(btn)
        return PermRow(row, btn)
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.parseColor("#4FC3F7"))
        setPadding(0, dp(20), 0, dp(6))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun hint(t: String): TextView = TextView(this).apply {
        text = t; textSize = 12f; setPadding(0, dp(10), 0, dp(4))
    }

    private fun toggle(label: String, key: String, def: Boolean): CheckBox = CheckBox(this).apply {
        text = label
        isChecked = getSharedPreferences(MadSettings.PREFS, MODE_PRIVATE).getBoolean(key, def)
        setOnCheckedChangeListener { _, c ->
            getSharedPreferences(MadSettings.PREFS, MODE_PRIVATE).edit().putBoolean(key, c).apply()
        }
    }

    private fun prettyGender(v: String): String = when (v) {
        "male" -> "Male (Jarvis)"
        "female" -> "Female (Maya)"
        else -> "System Default"
    }

    private fun prettyLang(v: String): String = when (v) {
        "hindi" -> "Hindi"
        "english" -> "English"
        else -> "Hinglish"
    }

    private fun showPicker(title: String, options: Array<String>, current: String, onPicked: (String) -> Unit) {
        val idx = options.indexOf(current).let { if (it >= 0) it else 0 }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, idx) { d, w -> onPicked(options[w]); d.dismiss() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askCustomModel(onDone: (String) -> Unit) {
        val box = EditText(this).apply { hint = "Model ka exact naam"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Custom Model")
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onDone(box.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** ScrollView wrapper — settings ka content scrollable banta hai. */
    private class ScrollViewWrap(private val ctx: Context) {
        val content: LinearLayout
        val view: android.widget.ScrollView

        init {
            content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            view = android.widget.ScrollView(ctx)
            view.addView(content)
        }

        private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showAppPicker() {
        val apps = targetAppManager.getLaunchableApps()
        if (apps.isEmpty()) { statusText.setText(R.string.status_no_apps); return }
        val labels = apps.map { "${it.label}  (${it.packageName})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        AlertDialog.Builder(this)
            .setTitle(R.string.select_target_title)
            .setAdapter(adapter) { _, w ->
                targetAppManager.targetPackage = apps[w].packageName
                updateTargetLabel()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateTargetLabel() {
        checkLabelTarget()
    }

    private fun checkLabelTarget() {
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
        if (requestCode == REQ_PERMISSIONS && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startAssistant()
        if (requestCode == REQ_MIC || requestCode == REQ_NOTIF) refreshRunState()
    }

    companion object {
        private const val REQ_PERMISSIONS = 1001
        private const val REQ_MIC = 1002
        private const val REQ_NOTIF =  1003

        /** Voice dropdown ke liye — TtsSpeaker init hone ke baad populate hota hai. */
        @Volatile
        var ttsList: List<android.speech.tts.Voice>? = null
            private set

        /** TtsSpeaker se call hota hai (init complete par). */
        fun onVoicesReady(voices: List<android.speech.tts.Voice>) {
            ttsList = voices
        }
    }
}

/** Accessibility enabled-list parsing (min SDK-safe). */
object TextUtilsSimple {
    fun split(s: String, sep: Char): List<String> =
        s.split(sep.toString()).filter { it.isNotBlank() }
}
