package com.tiff.screenassistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.Voice
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tiff.screenassistant.data.AiProvider
import com.tiff.screenassistant.data.PrefsManager
import com.tiff.screenassistant.data.SoulRepository
import com.tiff.screenassistant.data.VoiceEngineMode
import com.tiff.screenassistant.service.TiffOverlayService
import com.tiff.screenassistant.voice.TiffVoiceManager

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var soulRepo: SoulRepository
    private var voiceManager: TiffVoiceManager? = null
    private var loadedFemaleVoices: List<Voice> = emptyList()

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, TiffOverlayService::class.java).apply {
                putExtra(TiffOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(TiffOverlayService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            findViewById<TextView>(R.id.tvStatus).text = "Status: Tiff Overlay Awake!"
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Screen recording permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val msg = if (granted) "Microphone granted." else "Microphone denied."
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private val callPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val msg = if (granted) "Phone call permission granted." else "Phone call denied (will open dialer instead)."
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PrefsManager(this)
        soulRepo = SoulRepository(this)

        findViewById<Button>(R.id.btnOpenSoul).setOnClickListener {
            startActivity(Intent(this, SoulActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenMemory).setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenTelegram).setOnClickListener {
            startActivity(Intent(this, TelegramActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenMacros).setOnClickListener {
            startActivity(Intent(this, MacrosActivity::class.java))
        }

        val spinnerVoiceEngine = findViewById<Spinner>(R.id.spinnerVoiceEngine)
        val layoutLocalPicker = findViewById<LinearLayout>(R.id.layoutLocalVoicePicker)
        val spinnerFemaleVoices = findViewById<Spinner>(R.id.spinnerFemaleVoices)
        val layoutGeminiTts = findViewById<LinearLayout>(R.id.layoutGeminiTtsPrompt)
        val etGeminiVoiceStyle = findViewById<EditText>(R.id.etGeminiVoiceStyle)

        val voiceModes = VoiceEngineMode.values()
        spinnerVoiceEngine.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            voiceModes.map { it.displayName }
        )
        spinnerVoiceEngine.setSelection(voiceModes.indexOf(prefs.voiceEngineMode))
        etGeminiVoiceStyle.setText(prefs.geminiVoiceStyle)

        spinnerVoiceEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val chosen = voiceModes[pos]
                layoutLocalPicker.visibility =
                    if (chosen == VoiceEngineMode.ANDROID_FEMALE) View.VISIBLE else View.GONE
                layoutGeminiTts.visibility =
                    if (chosen == VoiceEngineMode.GEMINI_NEURAL_TTS) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        voiceManager = TiffVoiceManager(this, prefs) { voices ->
            loadedFemaleVoices = voices
            val labels = if (voices.isEmpty()) {
                listOf("Default Female TTS Voice")
            } else {
                voices.mapIndexed { idx, v -> "Female Voice ${idx + 1} (${v.locale.displayCountry} - ${v.name.takeLast(14)})" }
            }
            spinnerFemaleVoices.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )
            val savedIdx = voices.indexOfFirst { it.name == prefs.selectedFemaleVoiceName }
            if (savedIdx >= 0) {
                spinnerFemaleVoices.setSelection(savedIdx)
            }
        }

        spinnerFemaleVoices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos in loadedFemaleVoices.indices) {
                    prefs.selectedFemaleVoiceName = loadedFemaleVoices[pos].name
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnPreviewVoice).setOnClickListener {
            prefs.voiceEngineMode = voiceModes[spinnerVoiceEngine.selectedItemPosition]
            prefs.geminiVoiceStyle = etGeminiVoiceStyle.text.toString()
            voiceManager?.speak("Hey ${soulRepo.userNickname}! I'm Tiff, ready whenever you need me.")
        }

        val spinner = findViewById<Spinner>(R.id.spinnerProvider)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etModelId = findViewById<EditText>(R.id.etModelId)
        val layoutCustom = findViewById<LinearLayout>(R.id.layoutCustomEndpoint)
        val etCustomEndpoint = findViewById<EditText>(R.id.etCustomEndpoint)
        val btnSave = findViewById<Button>(R.id.btnSaveConfig)

        val providers = AiProvider.values()
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            providers.map { it.displayName }
        )

        spinner.setSelection(providers.indexOf(prefs.provider))
        etApiKey.setText(prefs.apiKey)
        etModelId.setText(prefs.modelId)
        etCustomEndpoint.setText(prefs.customEndpoint)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selected = providers[pos]
                layoutCustom.visibility = if (selected == AiProvider.CUSTOM) View.VISIBLE else View.GONE
                if (etModelId.text.toString().isBlank() ||
                    providers.any { it.defaultModel == etModelId.text.toString() }
                ) {
                    etModelId.setText(selected.defaultModel)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSave.setOnClickListener {
            val selected = providers[spinner.selectedItemPosition]
            prefs.provider = selected
            prefs.apiKey = etApiKey.text.toString()
            prefs.modelId = etModelId.text.toString().ifBlank { selected.defaultModel }
            prefs.customEndpoint = etCustomEndpoint.text.toString()
            prefs.voiceEngineMode = voiceModes[spinnerVoiceEngine.selectedItemPosition]
            prefs.geminiVoiceStyle = etGeminiVoiceStyle.text.toString()
            Toast.makeText(this, "Saved Tiff's settings!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnAccessibilityPerm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnMicPerm).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                Toast.makeText(this, "Microphone already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnCallPerm).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                callPermLauncher.launch(Manifest.permission.CALL_PHONE)
            } else {
                Toast.makeText(this, "Phone permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnLaunchTiff).setOnClickListener {
            if (prefs.apiKey.isBlank()) {
                Toast.makeText(this, "Please enter and save an API Key first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant Floating Overlay permission first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
        }
    }

    override fun onDestroy() {
        voiceManager?.shutdown()
        super.onDestroy()
    }
}
