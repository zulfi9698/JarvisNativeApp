package com.zulfi.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private lateinit var prefs: SharedPreferences
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var autoWakeEnabled = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(WebAppBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/jarvis_ui.html")

        tts = TextToSpeech(this, this)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        } else {
            setupSpeechRecognizer()
        }
    }

    override fun onResume() {
        super.onResume()
        val accActive = JarvisAccessibilityService.instance != null
        webView.postDelayed({
            webView.evaluateJavascript("updateAccBadge($accActive)", null)
        }, 600)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupSpeechRecognizer()
        }
    }

    inner class WebAppBridge {
        @JavascriptInterface
        fun startListeningNow() {
            runOnUiThread { startListeningSafe() }
        }

        @JavascriptInterface
        fun stopEverything() {
            runOnUiThread {
                autoWakeEnabled = false
                isListening = false
                speechRecognizer?.cancel()
                tts.stop()
                updateUI("idle", "<b>[SYSTEM]:</b> All voice & AI operations stopped.")
            }
        }

        @JavascriptInterface
        fun setAutoWake(enabled: Boolean) {
            autoWakeEnabled = enabled
        }

        @JavascriptInterface
        fun sendTextCommand(text: String) {
            runOnUiThread { handleUserCommand(text) }
        }

        @JavascriptInterface
        fun saveConfig(apiKey: String, lang: String, wake: String) {
            prefs.edit()
                .putString("api_key", apiKey)
                .putString("lang", lang)
                .putString("wake", wake)
                .apply()
            runOnUiThread {
                updateUI("idle", "<b>[SETTINGS SAVED]:</b> API Key & Voice Engine configured! Ab mic dabayein ya baat karein.")
                startListeningSafe()
            }
        }

        @JavascriptInterface
        fun getSavedApiKey(): String = prefs.getString("api_key", "") ?: ""

        @JavascriptInterface
        fun getSavedLang(): String = prefs.getString("lang", "ur-PK") ?: "ur-PK"

        @JavascriptInterface
        fun setModuleState(module: String, enabled: Boolean) {
            prefs.edit().putBoolean("mod_$module", enabled).apply()
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun updateUI(mode: String, htmlText: String) {
        val safe = htmlText.replace("'", "\'").replace("
", "<br>")
        runOnUiThread {
            webView.evaluateJavascript("setMode('$mode', '$safe')", null)
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                updateUI("listening", "")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!partial.isNullOrEmpty() && partial[0].isNotBlank()) {
                    updateUI("listening", "<b>You (Live):</b> ${partial[0]}...")
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                    handleUserCommand(matches[0])
                } else {
                    scheduleNextListen(1200)
                }
            }

            override fun onError(error: Int) {
                isListening = false
                // Error 7 = No match, Error 6 = Speech timeout -> cleanly restart if autoWake is on
                scheduleNextListen(1500)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListeningSafe() {
        if (tts.isSpeaking) tts.stop()
        try {
            speechRecognizer?.cancel()
            val lang = prefs.getString("lang", "ur-PK") ?: "ur-PK"
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            setupSpeechRecognizer()
        }
    }

    private fun scheduleNextListen(delayMs: Long) {
        if (!autoWakeEnabled) {
            updateUI("idle", "")
            return
        }
        scope.launch {
            delay(delayMs)
            if (autoWakeEnabled && !isListening && !tts.isSpeaking) {
                startListeningSafe()
            }
        }
    }

    private fun handleUserCommand(userText: String) {
        updateUI("thinking", "<b>You:</b> $userText<br><b>Jarvis:</b> Thinking...")

        scope.launch(Dispatchers.IO) {
            val decision = askGeminiBrain(userText)
            withContext(Dispatchers.Main) {
                executeDecision(userText, decision)
            }
        }
    }

    private fun askGeminiBrain(userText: String): JSONObject {
        val apiKey = prefs.getString("api_key", "")?.trim() ?: ""
        if (apiKey.isEmpty() || apiKey == "YOUR_GEMINI_API_KEY_HERE") {
            return JSONObject().apply {
                put("reply", "Zulfi bhai, upar Settings button par click kar ke apni Gemini API Key save karein taake mera dimagh kaam shuru kare!")
                put("action", "NONE")
            }
        }

        val systemPrompt = """
            You are JARVIS (also called Zulfi), Muhammad Zulfiqar's personal AI assistant and close friend from Mardan.
            Speak warmly and naturally in Roman Urdu / Urdu mix like a real human friend. Never sound robotic.
            Understand any free-form request without fixed commands.
            Return ONLY a valid JSON object with these exact keys:
            {
              "reply": "Friendly conversational reply in Urdu/Roman Urdu",
              "action": "NONE | YOUTUBE_SEARCH | GITHUB_SEARCH | GOOGLE_SEARCH | WHATSAPP_MSG | INSTA_PROFILE | FB_SEARCH | OPEN_APP",
              "query": "search query, message text, or package/app name",
              "target": "phone number, person name, or city name if mentioned"
            }
        """.trimIndent()

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", "$systemPrompt\n\nUser said: $userText")))
            }))
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: "{}"
            val rawText = JSONObject(bodyStr)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text")
                .replace("```json", "").replace("```", "").trim()
            JSONObject(rawText)
        } catch (e: Exception) {
            JSONObject().apply {
                put("reply", "Zulfi bhai, internet connection ya API key mein thora masla aa raha hai.")
                put("action", "NONE")
            }
        }
    }

    private fun executeDecision(userText: String, decision: JSONObject) {
        val reply = decision.optString("reply", "Ji Zulfi bhai!")
        val action = decision.optString("action", "NONE")
        val query = decision.optString("query", "")
        val target = decision.optString("target", "")

        updateUI("speaking", "<b>You:</b> $userText<br><b>Jarvis:</b> $reply")

        val params = Bundle()
        tts.speak(reply, TextToSpeech.QUEUE_FLUSH, params, "JARVIS_SPEECH")

        val encQuery = URLEncoder.encode(query, "UTF-8")

        try {
            when (action) {
                "YOUTUBE_SEARCH" -> if (prefs.getBoolean("mod_youtube", true)) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encQuery")))
                }
                "GITHUB_SEARCH" -> if (prefs.getBoolean("mod_chrome", true)) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/search?q=$encQuery")))
                }
                "GOOGLE_SEARCH" -> if (prefs.getBoolean("mod_chrome", true)) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encQuery")))
                }
                "WHATSAPP_MSG" -> if (prefs.getBoolean("mod_whatsapp", true)) {
                    JarvisAccessibilityService.autoClickWhatsAppSend = true
                    val url = "https://api.whatsapp.com/send?phone=$target&text=$encQuery"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                "INSTA_PROFILE" -> if (prefs.getBoolean("mod_insta", true)) {
                    val user = query.replace(" ", "")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/_u/$user")))
                }
                "FB_SEARCH" -> if (prefs.getBoolean("mod_fb", true)) {
                    val fbQuery = URLEncoder.encode("$query $target", "UTF-8")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/search/top/?q=$fbQuery")))
                }
            }
        } catch (e: Exception) {
            // Ignore intent errors if app not installed
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("ur", "PK")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    scheduleNextListen(800)
                }
                override fun onError(utteranceId: String?) {
                    scheduleNextListen(1000)
                }
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tts.shutdown()
    }
}
