package com.example.actionassistant

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import kotlin.math.pow

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var floatingIcon: ImageView
    private lateinit var statusBubble: TextView
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var generativeModel: GenerativeModel

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private val maxSteps = 15
    private var isAgentWorking = false

    // Cache to save API calls
    private val actionMemory = HashMap<String, String>()

    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.actionassistant.START_LISTENING") {
                startListening()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter("com.example.actionassistant.START_LISTENING")
        ContextCompat.registerReceiver(this, triggerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        floatingIcon = overlayView.findViewById(R.id.floating_icon)
        statusBubble = overlayView.findViewById(R.id.status_bubble)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.y = 100
        windowManager.addView(overlayView, params)

        // TODO: PASTE YOUR KEY HERE
        val apiKey = "AIzaSyA8oY4XFrxp6SX4rn9jiQG4DFZrXr7JEOk"

        generativeModel = GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = apiKey,
            generationConfig = generationConfig { temperature = 0.15f }
        )

        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts.language = Locale.US }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { showStatus("Listening..."); floatingIcon.alpha = 0.5f }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { showStatus("Thinking..."); floatingIcon.alpha = 1.0f }
            override fun onError(error: Int) {
                showStatus("Error: $error")
                serviceScope.launch { delay(2000); hideStatus() }
                isAgentWorking = false
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val command = matches[0]
                    if (actionMemory.containsKey(command)) {
                        showStatus("Memory Hit!")
                        val cachedAction = actionMemory[command]!!
                        MyAccessibilityService.instance?.performActionFromJson(cachedAction)
                        isAgentWorking = false
                    } else {
                        startActionLoop(command)
                    }
                } else {
                    isAgentWorking = false
                    hideStatus()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        floatingIcon.setOnClickListener { startListening() }
    }

    private fun startListening() {
        if (isAgentWorking) {
            speak("I am busy.")
            return
        }
        isAgentWorking = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        speechRecognizer.startListening(intent)
    }

    private fun startActionLoop(userGoal: String) {
        speak("On it.")
        showStatus("Processing...")

        serviceScope.launch {
            if (MyAccessibilityService.instance == null) {
                showStatus("Service Off")
                speak("Service disconnected.")
                isAgentWorking = false
                return@launch
            }

            var currentStep = 0
            var isTaskComplete = false
            val actionHistory = mutableListOf<String>()

            while (currentStep < maxSteps && !isTaskComplete) {
                currentStep++
                var screenLayout = MyAccessibilityService.instance?.getScreenLayoutAsHtml() ?: ""
                screenLayout = filterPrivacy(screenLayout)

                showStatus("Step $currentStep...")
                val prompt = createPrompt(userGoal, actionHistory, screenLayout)

                try {
                    // FIX: Use the smart retry function
                    val response = retryWithBackoff {
                        generativeModel.generateContent(prompt)
                    }

                    val cleanedJson = extractJsonFromString(response.text)

                    if (cleanedJson == null) {
                        delay(1000)
                        continue
                    }

                    val responseJson = JSONObject(cleanedJson)
                    val status = responseJson.optString("status", "IN_PROGRESS")
                    val message = responseJson.optString("message")

                    if (message.isNotEmpty()) {
                        speak(message)
                        showStatus(message)
                    }

                    if (status.equals("COMPLETE", true)) {
                        isTaskComplete = true
                        showStatus("Done.")
                        delay(2000)
                        hideStatus()
                    } else if (status.equals("WAIT_FOR_USER", true)) {
                        showStatus("Confirm?")
                        speak("I need confirmation.")
                        delay(5000)
                    } else {
                        MyAccessibilityService.instance?.performActionFromJson(cleanedJson)
                        actionHistory.add("Action: $message")

                        if (currentStep == 1) {
                            actionMemory[userGoal] = cleanedJson
                        }
                        delay(4000)
                    }
                } catch (e: Exception) {
                    showStatus("Failed: ${e.message}")
                    delay(2000)
                }
            }
            isAgentWorking = false
            hideStatus()
        }
    }

    // --- NEW: Smart Retry Logic (Exponential Backoff) ---
    private suspend fun retryWithBackoff(
        maxRetries: Int = 3,
        initialDelay: Long = 2000,
        block: suspend () -> GenerateContentResponse
    ): GenerateContentResponse {
        var currentDelay = initialDelay
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                // If Quota Exceeded (429), wait and retry
                if (e.message?.contains("429") == true || e.message?.contains("Quota") == true || e.message?.contains("Resource") == true) {
                    showStatus("Quota limit. Retrying in ${currentDelay/1000}s...")
                    delay(currentDelay)
                    currentDelay *= 2 // Wait longer next time (2s -> 4s -> 8s)
                } else {
                    throw e // Other errors (like No Internet) are real errors
                }
            }
        }
        return block() // Last attempt
    }

    private fun filterPrivacy(html: String): String {
        var safeHtml = html
        safeHtml = safeHtml.replace(Regex("[a-zA-Z0-9._-]+@[a-z]+\\.+[a-z]+"), "[EMAIL_HIDDEN]")
        safeHtml = safeHtml.replace(Regex("\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b"), "[PHONE_HIDDEN]")
        return safeHtml
    }

    private fun showStatus(text: String) {
        statusBubble.text = text
        statusBubble.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusBubble.visibility = View.GONE
    }

    private fun speak(text: String?) {
        if (!text.isNullOrBlank()) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun extractJsonFromString(text: String?): String? {
        if (text == null) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1)
        }
        return null
    }

    private fun createPrompt(goal: String, history: List<String>, layout: String): String {
        val historyStr = history.takeLast(5).joinToString(" -> ")
        return """
        ROLE: Android Automation Agent.
        GOAL: "$goal"
        HISTORY: [$historyStr]
        SCREEN_STATE (HTML):
        $layout
        
        INSTRUCTIONS:
        1. Parse HTML. Find <button>, <input>, or <scroller>.
        2. To SEARCH: Click <input>, type text, then send "ENTER".
        3. AFTER SEARCHING: Do NOT stop. Look for the result that matches the GOAL.
           - If you see the correct Channel/Video in the list, CLICK it.
           - Only return "COMPLETE" once the target is actually opened.
        4. If target is off-screen, action "SCROLL".
        
        OUTPUT JSON:
        { "status": "IN_PROGRESS" | "COMPLETE", "message": "reason", "action": { "action": "CLICK" | "TYPE" | "ENTER" | "SCROLL" | "HOME", "element_id": 123, "text": "optional" } }
        """.trimIndent()
    }

    override fun onDestroy() {
        try { unregisterReceiver(triggerReceiver) } catch (e: Exception) {}
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        speechRecognizer.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}