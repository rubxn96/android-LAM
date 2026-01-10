// IMPORTANT: Make sure this package name matches your project
package com.example.actionassistant

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var generativeModel: GenerativeModel
    private val MAX_STEPS = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ===================================================================
        // IMPORTANT: Replace with your actual Gemini API key
        // ===================================================================
        val apiKey = "AIzaSyCUgCug5q4SWEPj8XnpJ66jmP2DL7HSB5I"

        if (apiKey.startsWith("YOUR_")) {
            Toast.makeText(this, "Please add your API key in MainActivity.kt", Toast.LENGTH_LONG).show()
            return
        }

        generativeModel = GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = 0.1f
            }
        )

        val goalEditText: EditText = findViewById(R.id.goalEditText)
        val startButton: Button = findViewById(R.id.startButton)

        startButton.setOnClickListener {
            val userGoal = goalEditText.text.toString()
            if (userGoal.isNotBlank()) {
                startActionLoop(userGoal)
            } else {
                Toast.makeText(this, "Please enter a goal", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startActionLoop(userGoal: String) {
        val TAG = "MainActivity"

        lifecycleScope.launch {
            // ## THE FIX IS HERE ##
            // Show a message and wait 5 seconds for the user to go to the home screen.
            Toast.makeText(this@MainActivity, "Starting in 5 seconds... Go to the home screen now!", Toast.LENGTH_LONG).show()
            delay(5000)
            // #####################

            if (MyAccessibilityService.instance == null) {
                Toast.makeText(this@MainActivity, "Error: Service not connected. Check Accessibility Settings.", Toast.LENGTH_LONG).show()
                return@launch
            }

            var currentStep = 0
            var isTaskComplete = false
            val actionHistory = mutableListOf<String>()

            while (currentStep < MAX_STEPS && !isTaskComplete) {
                currentStep++
                Toast.makeText(this@MainActivity, "Step $currentStep...", Toast.LENGTH_SHORT).show()

                val screenLayout = MyAccessibilityService.instance?.getScreenLayoutAsJson()
                if (screenLayout == null || screenLayout == "{}") {
                    Toast.makeText(this@MainActivity, "Error: Cannot read the screen.", Toast.LENGTH_LONG).show()
                    break
                }

                val prompt = createPrompt(userGoal, actionHistory, screenLayout)
                Log.d(TAG, "Sending prompt for step $currentStep...")

                try {
                    val response = generativeModel.generateContent(prompt)
                    val cleanedJsonString = extractJsonFromString(response.text)

                    if (cleanedJsonString == null) {
                        Toast.makeText(this@MainActivity, "AI did not return a valid JSON response.", Toast.LENGTH_LONG).show()
                        Log.w(TAG, "AI returned non-JSON text: ${response.text}")
                        break
                    }

                    Log.d(TAG, "AI Response (Cleaned): $cleanedJsonString")

                    val responseJson = JSONObject(cleanedJsonString)
                    val status = responseJson.optString("status", "IN_PROGRESS")

                    if (status.equals("COMPLETE", ignoreCase = true)) {
                        isTaskComplete = true
                        Toast.makeText(this@MainActivity, "Task completed!", Toast.LENGTH_LONG).show()
                    } else {
                        MyAccessibilityService.instance?.performActionFromJson(responseJson.toString())
                        actionHistory.add(responseJson.toString())
                        delay(3000)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "An error occurred. Check Logcat.", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "An exception occurred: ${e.message}", e)
                    break
                }
            }
        }
    }

    private fun extractJsonFromString(text: String?): String? {
        if (text == null) return null
        val startIndex = text.indexOf('{')
        val lastIndex = text.lastIndexOf('}')
        return if (startIndex != -1 && lastIndex != -1 && lastIndex > startIndex) {
            text.substring(startIndex, lastIndex + 1)
        } else {
            null
        }
    }

    private fun createPrompt(goal: String, history: List<String>, layout: String): String {
        val historyString = if (history.isEmpty()) "No actions taken yet." else history.joinToString(" | ")
        return """
        Goal: "$goal"
        History: [$historyString]
        ScreenJSON: $layout
        Instruction: Analyze the data and respond with the next action as a single, valid JSON object. The JSON must contain a 'status' ('IN_PROGRESS' or 'COMPLETE') and an 'action' (e.g., {'action': 'CLICK', 'element_id': 12345}). Do not add any text before or after the JSON.
        """.trim().replace("\n", " ")
    }
}