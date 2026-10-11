package com.jarvis.assistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * JarvisFloatingService
 * Real Android Foreground Service displaying an interactive floating Arc Reactor bubble
 * over all applications (WhatsApp, YouTube, games, browser).
 */
class JarvisFloatingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var hologramView: HologramView? = null
    private var expandedMenu: LinearLayout? = null

    private lateinit var speechEngine: SpeechEngine
    private lateinit var geminiClient: GeminiClient
    private var speechRecognizer: SpeechRecognizer? = null

    private lateinit var prefs: SharedPreferences
    private var isListening = false
    private var isExpanded = false

    companion object {
        const val CHANNEL_ID = "jarvis_floating_channel"
        const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "jarvis_prefs"
        const val KEY_API = "gemini_api_key"
        const val KEY_MODEL = "gemini_model"

        var mediaProjectionInstance: MediaProjection? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        speechEngine = SpeechEngine(this)
        geminiClient = GeminiClient()

        startForegroundNotification()
        initFloatingOverlay()
        initSpeechRecognizer()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "J.A.R.V.I.S. Floating Core",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps holographic Arc Reactor running over other apps"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("J.A.R.V.I.S. Active")
            .setContentText("Holographic overlay engaged. Tap to open main console.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun initFloatingOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_hologram_layout, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 200
        }

        hologramView = floatingView?.findViewById(R.id.floatingHologramView)
        expandedMenu = floatingView?.findViewById(R.id.floatingExpandedMenu)
        val bubbleContainer = floatingView?.findViewById<FrameLayout>(R.id.floatingBubbleContainer)

        // Dragging & Tapping Touch Listener
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        bubbleContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isClick = false
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        toggleMenu()
                    }
                    true
                }
                else -> false
            }
        }

        // Action Buttons inside expanded mini menu
        floatingView?.findViewById<ImageButton>(R.id.btnFloatingTalk)?.setOnClickListener {
            startListening()
        }

        floatingView?.findViewById<ImageButton>(R.id.btnFloatingStop)?.setOnClickListener {
            forceStop()
        }

        floatingView?.findViewById<ImageButton>(R.id.btnFloatingCam)?.setOnClickListener {
            triggerCameraVision()
        }

        floatingView?.findViewById<ImageButton>(R.id.btnFloatingScreen)?.setOnClickListener {
            triggerScreenVision()
        }

        floatingView?.findViewById<ImageButton>(R.id.btnFloatingClose)?.setOnClickListener {
            stopSelf()
        }

        try {
            windowManager?.addView(floatingView, params)
        } catch (_: Exception) {}
    }

    private fun toggleMenu() {
        isExpanded = !isExpanded
        expandedMenu?.visibility = if (isExpanded) View.VISIBLE else View.GONE
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    hologramView?.setState(HologramView.State.LISTENING)
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    hologramView?.setState(HologramView.State.THINKING)
                }

                override fun onError(error: Int) {
                    isListening = false
                    hologramView?.setState(HologramView.State.IDLE)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: return
                    handleUserSpeech(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // Barge-In Detection while user speaks:
                    val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val pText = partials?.firstOrNull()?.lowercase(Locale.ROOT) ?: ""
                    if (pText.contains("stop") || pText.contains("chup") || pText.contains("quiet") || pText.contains("cancel")) {
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
        hologramView?.setState(HologramView.State.LISTENING)
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            hologramView?.setState(HologramView.State.IDLE)
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
        hologramView?.setState(HologramView.State.IDLE)
    }

    private fun handleUserSpeech(text: String) {
        val lower = text.lowercase(Locale.ROOT)

        // Instant voice stop command
        if (lower == "stop" || lower == "chup" || lower == "quiet" || lower == "cancel") {
            forceStop()
            return
        }

        // Screen analysis voice trigger
        if (lower.contains("look at my screen") || lower.contains("see my screen") || lower.contains("check screen")) {
            triggerScreenVision()
            return
        }

        // Camera voice trigger
        if (lower.contains("look through camera") || lower.contains("what is this") || lower.contains("see this")) {
            triggerCameraVision()
            return
        }

        // Standard Gemini processing
        processQuery(text)
    }

    private fun processQuery(prompt: String, imageBytes: ByteArray? = null) {
        val apiKey = prefs.getString(KEY_API, "") ?: ""
        val model = prefs.getString(KEY_MODEL, GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL

        if (apiKey.isBlank()) {
            speechEngine.speak("Sir, please configure your Gemini API key in the main app.")
            hologramView?.setState(HologramView.State.IDLE)
            return
        }

        hologramView?.setState(HologramView.State.THINKING)

        serviceScope.launch {
            val result = geminiClient.generateContent(
                apiKey = apiKey,
                prompt = prompt,
                imageBytes = imageBytes,
                model = model
            )

            result.onSuccess { reply ->
                withContext(Dispatchers.Main) {
                    hologramView?.setState(HologramView.State.SPEAKING)
                    speechEngine.speak(
                        text = reply,
                        onDone = {
                            hologramView?.setState(HologramView.State.IDLE)
                        }
                    )
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    hologramView?.setState(HologramView.State.IDLE)
                    speechEngine.speak("Satellite neural connection interrupted, sir.")
                }
            }
        }
    }

    private fun triggerScreenVision() {
        val projection = mediaProjectionInstance
        if (projection == null) {
            speechEngine.speak("Sir, please grant screen permission inside the main app first.")
            return
        }

        hologramView?.setState(HologramView.State.THINKING)
        speechEngine.speak("Inspecting screen data, sir...")

        serviceScope.launch {
            val screenHelper = ScreenCaptureHelper(applicationContext)
            val bytes = screenHelper.captureScreenBytes(projection)
            if (bytes != null) {
                processQuery("Analyze what is displayed on my screen and provide immediate insights.", bytes)
            } else {
                speechEngine.speak("Unable to capture screen display buffer, sir.")
                hologramView?.setState(HologramView.State.IDLE)
            }
        }
    }

    private fun triggerCameraVision() {
        speechEngine.speak("Scanning visual sensor feed, sir.")
        // For camera from background overlay, redirect user or capture frame
        val openCameraIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("trigger_camera", true)
        }
        startActivity(openCameraIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        speechEngine.shutdown()
        geminiClient.cancelActiveCall()
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}

        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
    }
}
