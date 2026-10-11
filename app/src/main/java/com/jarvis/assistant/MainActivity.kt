package com.jarvis.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvResponse: TextView
    private lateinit var tvUserTranscript: TextView
    private lateinit var hologramView: HologramView
    private lateinit var btnTalk: Button
    private lateinit var btnForceStop: Button
    private lateinit var btnCameraVision: Button
    private lateinit var btnScreenVision: Button
    private lateinit var btnReminders: Button
    private lateinit var btnLaunchFloating: ImageButton
    private lateinit var btnSettings: ImageButton

    private lateinit var prefs: SharedPreferences
    private lateinit var speechEngine: SpeechEngine
    private lateinit var geminiClient: GeminiClient
    private lateinit var cameraHelper: CameraHelper
    private lateinit var screenHelper: ScreenCaptureHelper
    private lateinit var reminderManager: ReminderManager

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val REQ_OVERLAY = 301

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpManager.getMediaProjection(result.resultCode, result.data!!)
            JarvisFloatingService.mediaProjectionInstance = projection
            captureAndAnalyzeScreen()
        } else {
            setStatus("SCREEN CAPTURE DENIED")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(JarvisFloatingService.PREFS_NAME, Context.MODE_PRIVATE)
        speechEngine = SpeechEngine(this)
        geminiClient = GeminiClient()
        cameraHelper = CameraHelper(this)
        screenHelper = ScreenCaptureHelper(this)
        reminderManager = ReminderManager(this)

        initViews()
        initSpeechRecognizer()
        checkPermissions()

        lifecycleScope.launch {
            cameraHelper.initCamera(this@MainActivity)
        }

        // Check for API key on startup
        val key = prefs.getString(JarvisFloatingService.KEY_API, "")
        if (key.isNullOrBlank()) {
            showSettingsDialog(isFirstTime = true)
        }

        // Handle camera request from overlay
        if (intent.getBooleanExtra("trigger_camera", false)) {
            captureAndAnalyzeCamera()
        }
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvResponse = findViewById(R.id.tvResponse)
        tvUserTranscript = findViewById(R.id.tvUserTranscript)
        hologramView = findViewById(R.id.hologramView)
        btnTalk = findViewById(R.id.btnTalk)
        btnForceStop = findViewById(R.id.btnForceStop)
        btnCameraVision = findViewById(R.id.btnCameraVision)
        btnScreenVision = findViewById(R.id.btnScreenVision)
        btnReminders = findViewById(R.id.btnReminders)
        btnLaunchFloating = findViewById(R.id.btnLaunchFloating)
        btnSettings = findViewById(R.id.btnSettings)

        btnTalk.setOnClickListener {
            startListening()
        }

        btnForceStop.setOnClickListener {
            forceStop()
        }

        btnCameraVision.setOnClickListener {
            captureAndAnalyzeCamera()
        }

        btnScreenVision.setOnClickListener {
            requestScreenCapture()
        }

        btnReminders.setOnClickListener {
            showReminderDialog()
        }

        btnLaunchFloating.setOnClickListener {
            checkAndStartFloatingService()
        }

        btnSettings.setOnClickListener {
            showSettingsDialog(isFirstTime = false)
        }
    }

    private fun setStatus(status: String, state: HologramView.State = HologramView.State.IDLE) {
        tvStatus.text = status
        hologramView.setState(state)
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    setStatus("LISTENING • SPEAK NOW", HologramView.State.LISTENING)
                    tvUserTranscript.text = "Listening to your voice..."
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    setStatus("PROCESSING NEURAL DATA...", HologramView.State.THINKING)
                }

                override fun onError(error: Int) {
                    isListening = false
                    setStatus("SYSTEM READY", HologramView.State.IDLE)
                    tvUserTranscript.text = "Voice input cancelled or timed out."
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    tvUserTranscript.text = "\"$text\""
                    handleUserSpeech(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val pText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    tvUserTranscript.text = pText
                    val lower = pText.lowercase(Locale.ROOT)
                    if (lower.contains("stop") || lower.contains("chup") || lower.contains("quiet")) {
                        forceStop()
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (isListening) return
        speechEngine.stop()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        isListening = true
        setStatus("INITIALIZING MICROPHONE...", HologramView.State.LISTENING)
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            isListening = false
            setStatus("SYSTEM READY", HologramView.State.IDLE)
        }
    }

    private fun forceStop() {
        speechEngine.stop()
        geminiClient.cancelActiveCall()
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        isListening = false
        setStatus("SYSTEM STOPPED • IDLE", HologramView.State.IDLE)
        tvResponse.text = "Action cancelled, sir. System standing by."
    }

    private fun handleUserSpeech(text: String) {
        val lower = text.lowercase(Locale.ROOT)

        if (lower == "stop" || lower == "chup" || lower == "quiet" || lower == "cancel") {
            forceStop()
            return
        }

        if (lower.contains("look at my screen") || lower.contains("see my screen")) {
            requestScreenCapture()
            return
        }

        if (lower.contains("look through camera") || lower.contains("what is this") || lower.contains("see this")) {
            captureAndAnalyzeCamera()
            return
        }

        processPrompt(text)
    }

    private fun processPrompt(prompt: String, imageBytes: ByteArray? = null) {
        val apiKey = prefs.getString(JarvisFloatingService.KEY_API, "") ?: ""
        val model = prefs.getString(JarvisFloatingService.KEY_MODEL, GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL

        if (apiKey.isBlank()) {
            showSettingsDialog(isFirstTime = true)
            return
        }

        setStatus("NEURAL PROCESSING...", HologramView.State.THINKING)
        tvResponse.text = "Consulting quantum neural network..."

        lifecycleScope.launch {
            val result = geminiClient.generateContent(
                apiKey = apiKey,
                prompt = prompt,
                imageBytes = imageBytes,
                model = model
            )

            result.onSuccess { reply ->
                withContext(Dispatchers.Main) {
                    tvResponse.text = reply
                    setStatus("VOCALIZING RESPONSE", HologramView.State.SPEAKING)
                    speechEngine.speak(
                        text = reply,
                        onDone = {
                            setStatus("SYSTEM READY", HologramView.State.IDLE)
                        }
                    )
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    setStatus("ERROR DETECTED", HologramView.State.IDLE)
                    tvResponse.text = "Error: ${err.message}"
                    speechEngine.speak("Satellite neural connection interrupted, sir.")
                }
            }
        }
    }

    private fun captureAndAnalyzeCamera() {
        setStatus("SCANNING CAMERA SENSOR...", HologramView.State.THINKING)
        tvResponse.text = "Acquiring camera optics frame..."

        lifecycleScope.launch {
            val bytes = cameraHelper.capturePhotoBytes()
            if (bytes != null && bytes.isNotEmpty()) {
                processPrompt("Examine this camera image carefully and explain what you see with high detail.", bytes)
            } else {
                setStatus("CAMERA ERROR", HologramView.State.IDLE)
                tvResponse.text = "Unable to acquire camera frame, sir."
            }
        }
    }

    private fun requestScreenCapture() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun captureAndAnalyzeScreen() {
        val projection = JarvisFloatingService.mediaProjectionInstance
        if (projection == null) {
            requestScreenCapture()
            return
        }

        setStatus("CAPTURING SCREEN BUFFER...", HologramView.State.THINKING)
        tvResponse.text = "Reading display framebuffer..."

        lifecycleScope.launch {
            val bytes = screenHelper.captureScreenBytes(projection)
            if (bytes != null && bytes.isNotEmpty()) {
                processPrompt("Analyze everything displayed on my screen and give me concise, actionable insights.", bytes)
            } else {
                setStatus("SCREEN CAPTURE FAILED", HologramView.State.IDLE)
                tvResponse.text = "Unable to acquire screen pixels, sir."
            }
        }
    }

    private fun showReminderDialog() {
        val dialogView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, null)
        val input = EditText(this).apply {
            hint = "Reminder message (e.g. Call Rahul, Take medicine)"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }

        val options = arrayOf("In 5 Minutes", "In 15 Minutes", "In 30 Minutes", "In 1 Hour", "In 2 Hours")
        var selectedMinutes = 5

        AlertDialog.Builder(this)
            .setTitle("Set JARVIS Reminder")
            .setView(input)
            .setSingleChoiceItems(options, 0) { _, which ->
                selectedMinutes = when (which) {
                    0 -> 5
                    1 -> 15
                    2 -> 30
                    3 -> 60
                    4 -> 120
                    else -> 5
                }
            }
            .setPositiveButton("SET ALARM") { _, _ ->
                val text = input.text.toString().trim().ifEmpty { "Important task reminder" }
                val triggerAt = System.currentTimeMillis() + (selectedMinutes * 60 * 1000)
                reminderManager.scheduleReminder(text, triggerAt)
                val msg = "Reminder set for $selectedMinutes minutes from now: $text"
                tvResponse.text = msg
                speechEngine.speak("Protocol logged, sir. I will alert you in $selectedMinutes minutes.")
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun checkAndStartFloatingService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("To display the floating Arc Reactor over other apps (WhatsApp, YouTube, etc.), please grant 'Display over other apps' permission.")
                .setPositiveButton("OPEN SETTINGS") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, REQ_OVERLAY)
                }
                .setNegativeButton("CANCEL", null)
                .show()
            return
        }

        val serviceIntent = Intent(this, JarvisFloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Arc Reactor Hologram Active over apps!", Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsDialog(isFirstTime: Boolean) {
        val currentKey = prefs.getString(JarvisFloatingService.KEY_API, "") ?: ""
        val currentModel = prefs.getString(JarvisFloatingService.KEY_MODEL, GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL

        val input = EditText(this).apply {
            hint = "Paste free Gemini API Key (starts with AIza...)"
            setText(currentKey)
        }

        AlertDialog.Builder(this)
            .setTitle(if (isFirstTime) "First Time Protocol: API Key" else "JARVIS Neural Configuration")
            .setMessage("Get your free key from: https://aistudio.google.com/apikey")
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val newKey = input.text.toString().trim()
                prefs.edit()
                    .putString(JarvisFloatingService.KEY_API, newKey)
                    .putString(JarvisFloatingService.KEY_MODEL, currentModel)
                    .apply()
                speechEngine.speak("Neural credentials initialized, sir. Jarvis standing by.")
            }
            .setNegativeButton("CANCEL", null)
            .setCancelable(!isFirstTime)
            .show()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechEngine.shutdown()
        geminiClient.cancelActiveCall()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
    }
}
