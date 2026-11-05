package com.zulfi.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Apni Gemini API Key yahan dalein:
    private val GEMINI_API_KEY = "AIzaSyA9X_31011DrZAyVXNT_xYVb9c2QGoQDQc"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(WebAppBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/jarvis_ui.html")

        tts = TextToSpeech(this, this)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        } else {
            initVoiceRecognizer()
        }
    }

    inner class WebAppBridge {
        @JavascriptInterface
        fun startListeningNow() {
            runOnUiThread { startListening() }
        }
    }

    private fun updateUI(mode: String, text: String) {
        val safeText = text.replace("'", "\'").replace("
", " ")
        runOnUiThread {
            webView.evaluateJavascript("setMode('$mode', '$safeText')", null)
        }
    }

    private fun initVoiceRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                updateUI("listening", "Listening...")
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    handleUserSpeech(matches[0])
                } else {
                    restartListeningDelay()
                }
            }
            override fun onError(error: Int) {
                restartListeningDelay()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        startListening()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
        }
        speechRecognizer?.startListening(intent)
    }

    private fun restartListeningDelay() {
        scope.launch {
            delay(1500)
            startListening()
        }
    }

    private fun handleUserSpeech(spokenText: String) {
        updateUI("speaking", "You: $spokenText")
        scope.launch(Dispatchers.IO) {
            val aiResponse = askJarvisBrain(spokenText)
            withContext(Dispatchers.Main) {
                executeJarvisDecision(aiResponse)
            }
        }
    }

    private fun askJarvisBrain(userText: String): JSONObject {
        val systemPrompt = """
            You are JARVIS (also called Zulfi), an advanced, warm, witty, and human-like personal AI companion for Muhammad Zulfiqar.
            Speak naturally like a close intelligent friend in Roman Urdu / Urdu / English mix. Never sound robotic.
            Understand free-form human intent without fixed commands.
            Always respond strictly in JSON format with these keys:
            {
              "reply": "Your friendly spoken response to Zulfi",
              "action": "NONE | YOUTUBE_SEARCH | GITHUB_SEARCH | GOOGLE_SEARCH | WHATSAPP_MSG | INSTA_PROFILE | FB_SEARCH",
              "query": "search query or message text if applicable",
              "target": "phone number, username, or city if applicable"
            }
        """.trimIndent()

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", "$systemPrompt\n\nUser said: $userText")))
            }))
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$GEMINI_API_KEY")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string() ?: "{}"
            val textResp = JSONObject(raw)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text")
                .replace("```json", "").replace("```", "").trim()
            JSONObject(textResp)
        } catch (e: Exception) {
            JSONObject().apply {
                put("reply", "Zulfi bhai, net ya API key ka thora issue aa raha hai, zara check karein.")
                put("action", "NONE")
            }
        }
    }

    private fun executeJarvisDecision(decision: JSONObject) {
        val reply = decision.optString("reply", "Ji Zulfi bhai!")
        val action = decision.optString("action", "NONE")
        val query = decision.optString("query", "")
        val target = decision.optString("target", "")

        updateUI("speaking", reply)
        tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_UTTERANCE")

        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        when (action) {
            "YOUTUBE_SEARCH" -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery")))
            }
            "GITHUB_SEARCH" -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/search?q=$encodedQuery")))
            }
            "GOOGLE_SEARCH" -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encodedQuery")))
            }
            "WHATSAPP_MSG" -> {
                val url = "https://api.whatsapp.com/send?phone=$target&text=$encodedQuery"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            "INSTA_PROFILE" -> {
                val username = query.replace(" ", "")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/_u/$username")))
            }
            "FB_SEARCH" -> {
                val searchStr = URLEncoder.encode("$query $target", "UTF-8")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("fb://facewebmodal/f?href=https://www.facebook.com/search/top/?q=$searchStr")))
            }
        }

        restartListeningDelay()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("ur", "PK")
        }
    }
}
