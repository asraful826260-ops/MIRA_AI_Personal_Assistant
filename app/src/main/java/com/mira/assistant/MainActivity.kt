package com.mira.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.provider.Settings
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("mira_settings", MODE_PRIVATE) }

    private val speechPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startSpeechRecognition()
        }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) pendingSpeech?.invoke(spoken)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        setContent {
            MiraScreen(
                onSpeak = { speak(it) },
                onListen = { callback ->
                    pendingSpeech = callback
                    if (ContextCompat.checkSelfPermission(
                            this, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        startSpeechRecognition()
                    } else {
                        speechPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onAction = { command -> handleCommand(command) },
                onAsk = { prompt, done -> askGemini(prompt, done) },
                savedApiKey = {
                    prefs.getString("gemini_api_key", "") ?: ""
                },
                saveApiKey = {
                    prefs.edit().putString("gemini_api_key", it.trim()).apply()
                },
                savedModel = {
                    prefs.getString("gemini_model", "gemini-2.5-flash")
                        ?: "gemini-2.5-flash"
                },
                saveModel = {
                    prefs.edit().putString("gemini_model", it).apply()
                },
                onFetchModels = { done -> fetchModels(done) }
            )
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale("bn", "BD")
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mira_reply")
        }
    }

    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "MIRA-কে বলুন")
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: Exception) {
            speak("ভয়েস ইনপুট চালু করা যাচ্ছে না।")
        }
    }

    private fun fetchModels(done: (List<String>, String?) -> Unit) {
        val key = prefs.getString("gemini_api_key", "")?.trim().orEmpty()

        if (key.isBlank()) {
            done(emptyList(), "আগে API Key সংরক্ষণ করুন।")
            return
        }

        Thread {
            var models = emptyList<String>()
            var error: String? = null
            var conn: HttpURLConnection? = null

            try {
                val url = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"
                )
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20000
                    readTimeout = 30000
                    setRequestProperty("x-goog-api-key", key)
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) {
                    conn.inputStream
                } else {
                    conn.errorStream
                }
                val response = stream.bufferedReader(Charsets.UTF_8).use {
                    it.readText()
                }

                if (code in 200..299) {
                    val json = JSONObject(response)
                    val array = json.optJSONArray("models") ?: JSONArray()
                    val found = mutableListOf<String>()

                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val methods = item.optJSONArray(
                            "supportedGenerationMethods"
                        ) ?: JSONArray()

                        var supportsText = false
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") {
                                supportsText = true
                                break
                            }
                        }

                        val name = item.optString("name")
                            .removePrefix("models/")

                        if (supportsText && name.isNotBlank()) {
                            found.add(name)
                        }
                    }

                    models = found.distinct().sorted()

                    if (models.isEmpty()) {
                        error = "এই API Key-তে চ্যাটের উপযোগী মডেল পাওয়া যায়নি।"
                    }
                } else {
                    val message = try {
                        JSONObject(response).optJSONObject("error")
                            ?.optString("message").orEmpty()
                    } catch (_: Exception) {
                        ""
                    }

                    error = when (code) {
                        400, 403 -> "API Key বা API অনুমতিতে সমস্যা হয়েছে। ($code)"
                        429 -> "API ব্যবহারের সীমা শেষ হয়েছে। পরে চেষ্টা করুন।"
                        else -> "মডেল তালিকা আনা যায়নি। HTTP $code ${message.take(120)}"
                    }
                }
            } catch (e: Exception) {
                error = "ইন্টারনেট সংযোগে সমস্যা হয়েছে। (${e.javaClass.simpleName})"
            } finally {
                conn?.disconnect()
            }

            mainHandler.post {
                done(models, error)
            }
        }.start()
    }

    private fun askGemini(prompt: String, done: (String) -> Unit) {
        val key = prefs.getString("gemini_api_key", "")?.trim().orEmpty()
        val model = prefs.getString("gemini_model", "gemini-2.5-flash")
            ?.removePrefix("models/") ?: "gemini-2.5-flash"

        if (key.isBlank()) {
            done("প্রথমে সেটিংস থেকে Gemini API Key যোগ করুন।")
            return
        }

        Thread {
            var result: String
            var conn: HttpURLConnection? = null

            try {
                val url = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                )

                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 90000
                    setRequestProperty(
                        "Content-Type", "application/json; charset=utf-8"
                    )
                    setRequestProperty("x-goog-api-key", key)
                    doOutput = true
                }

                val body = JSONObject()
                    .put(
                        "system_instruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    "তুমি MIRA, একজন সহায়ক বাংলা AI assistant। ব্যবহারকারী যে ভাষায় কথা বলে, সেই ভাষায় সহজ ও পরিষ্কার উত্তর দাও।"
                                )
                            )
                        )
                    )
                    .put(
                        "contents",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put(
                                    "parts",
                                    JSONArray().put(
                                        JSONObject().put("text", prompt)
                                    )
                                )
                        )
                    )
                    .put(
                        "generationConfig",
                        JSONObject()
                            .put("temperature", 0.7)
                            .put("maxOutputTokens", 1024)
                    )

                conn.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) {
                    conn.inputStream
                } else {
                    conn.errorStream
                }
                val response = stream.bufferedReader(Charsets.UTF_8).use {
                    it.readText()
                }

                if (code in 200..299) {
                    val json = JSONObject(response)
                    result = json.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        ?.takeIf { !it.isNullOrBlank() }
                        ?: "দুঃখিত, এবার উত্তর পাওয়া যায়নি। আবার চেষ্টা করুন।"
                } else {
                    val message = try {
                        JSONObject(response).optJSONObject("error")
                            ?.optString("message").orEmpty()
                    } catch (_: Exception) {
                        ""
                    }

                    result = when (code) {
                        400, 403 ->
                            "Gemini API Key বা API অনুমতিতে সমস্যা হয়েছে। ($code)"
                        404 ->
                            "নির্বাচিত Gemini মডেল পাওয়া যায়নি। অন্য মডেল নির্বাচন করুন।"
                        429 ->
                            "Gemini API quota শেষ বা অনুরোধের সীমা অতিক্রম হয়েছে।"
                        500, 503 ->
                            "Gemini সার্ভার এখন ব্যস্ত। কিছুক্ষণ পরে চেষ্টা করুন।"
                        else ->
                            "Gemini সংযোগে সমস্যা হয়েছে। HTTP $code ${message.take(150)}"
                    }
                }
            } catch (e: Exception) {
                result =
                    "ইন্টারনেট সংযোগ বা সার্ভারে সমস্যা হয়েছে। আবার চেষ্টা করুন। (${e.javaClass.simpleName})"
            } finally {
                conn?.disconnect()
            }

            mainHandler.post {
                done(result)
            }
        }.start()
    }

    private fun handleCommand(raw: String): String {
        val c = raw.lowercase(Locale.ROOT)

        return try {
            when {
                "youtube" in c || "ইউটিউব" in c -> {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com")
                        )
                    )
                    "YouTube খুলছি।"
                }

                "wifi" in c || "ওয়াইফাই" in c || "ওয়াইফাই" in c -> {
                    startActivity(
                        Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    )
                    "ইন্টারনেট ও Wi-Fi সেটিংস খুলেছি।"
                }

                "bluetooth" in c || "ব্লুটুথ" in c -> {
                    startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    )
                    "Bluetooth সেটিংস খুলেছি।"
                }

                "setting" in c || "সেটিংস" in c -> {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                    "ফোনের সেটিংস খুলেছি।"
                }

                "flash" in c || "torch" in c ||
                    "টর্চ" in c || "ফ্ল্যাশ" in c -> {
                    val cm = getSystemService(CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.firstOrNull {
                        cm.getCameraCharacteristics(it).get(
                            android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE
                        ) == true
                    }

                    if (id != null) {
                        cm.setTorchMode(id, !torchOn)
                        torchOn = !torchOn
                        if (torchOn) {
                            "ফ্ল্যাশলাইট চালু করেছি।"
                        } else {
                            "ফ্ল্যাশলাইট বন্ধ করেছি।"
                        }
                    } else {
                        "এই ফোনে ফ্ল্যাশলাইট পাওয়া যায়নি।"
                    }
                }

                "volume" in c || "ভলিউম" in c || "শব্দ" in c -> {
                    (getSystemService(AUDIO_SERVICE) as AudioManager)
                        .adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE,
                            AudioManager.FLAG_SHOW_UI
                        )
                    "মিডিয়া ভলিউম বাড়িয়েছি।"
                }

                "message" in c || "sms" in c || "মেসেজ" in c -> {
                    startActivity(
                        Intent(
                            Intent.ACTION_SENDTO,
                            Uri.parse("smsto:")
                        )
                    )
                    "মেসেজ অ্যাপ খুলেছি। পাঠানোর আগে নম্বর ও লেখা যাচাই করুন।"
                }

                else -> ""
            }
        } catch (_: Exception) {
            "এই কাজটি করা যায়নি। ফোনের অনুমতি বা Android সীমাবদ্ধতা পরীক্ষা করুন।"
        }
    }

    private var torchOn = false

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun MiraScreen(
    onSpeak: (String) -> Unit,
    onListen: ((String) -> Unit) -> Unit,
    onAction: (String) -> String,
    onAsk: (String, (String) -> Unit) -> Unit,
    savedApiKey: () -> String,
    saveApiKey: (String) -> Unit,
    savedModel: () -> String,
    saveModel: (String) -> Unit,
    onFetchModels: ((List<String>, String?) -> Unit) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var history by remember {
        mutableStateOf(
            listOf("MIRA: হ্যালো! আমি MIRA। আমাকে বাংলায় কিছু বলুন।")
        )
    }
    var busy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf("home") }
    var apiKey by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("gemini-2.5-flash") }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelMessage by remember { mutableStateOf("") }

    val pink = Color(0xFFFF4FA3)
    val dark = Color(0xFF17101D)

    fun loadModels() {
        loadingModels = true
        modelMessage = "মডেল খোঁজা হচ্ছে..."
        onFetchModels { found, error ->
            models = found
            loadingModels = false
            modelMessage = error ?: "${found.size}টি মডেল পাওয়া গেছে।"
        }
    }

    fun sendMessage(raw: String) {
        val q = raw.trim()
        if (q.isEmpty() || busy) return

        history = history + "আপনি: $q"
        val local = onAction(q)

        if (local.isNotBlank()) {
            history = history + "MIRA: $local"
            onSpeak(local)
        } else {
            busy = true
            history = history + "MIRA: ভাবছি..."

            onAsk(q) { reply ->
                history = history.dropLast(1) + "MIRA: $reply"
                busy = false
                onSpeak(reply)
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = {
                Text(
                    when (settingsPage) {
                        "api" -> "API Key সেটিংস"
                        "models" -> "Gemini মডেল নির্বাচন"
                        else -> "MIRA Settings"
                    }
                )
            },
            text = {
                Column {
                    if (settingsPage != "home") {
                        TextButton(
                            onClick = { settingsPage = "home" }
                        ) {
                            Text("← Settings-এ ফিরে যান")
                        }
                    }

                    when (settingsPage) {
                        "api" -> {
                            Text(
                                "তোমার Gemini API Key এখানে সংরক্ষণ করো। " +
                                    "উত্তর পাওয়ার জন্য Key Google Gemini API-তে পাঠানো হবে।"
                            )
                            Spacer(Modifier.height(12.dp))

                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text("Gemini API Key") },
                                singleLine = true,
                                visualTransformation =
                                    PasswordVisualTransformation()
                            )

                            Spacer(Modifier.height(10.dp))

                            Button(
                                onClick = {
                                    saveApiKey(apiKey)
                                    modelMessage = "API Key সংরক্ষণ করা হয়েছে।"
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = pink
                                )
                            ) {
                                Text("API Key সংরক্ষণ")
                            }

                            Text(
                                modelMessage,
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }

                        "models" -> {
                            Text(
                                "API Key সংরক্ষণের পর মডেল খুঁজুন। " +
                                    "শুধু generateContent সমর্থন করা মডেল দেখানো হবে।"
                            )

                            Spacer(Modifier.height(8.dp))

                            Button(
                                onClick = { loadModels() },
                                enabled = !loadingModels,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = pink
                                )
                            ) {
                                Text(
                                    if (loadingModels) {
                                        "খোঁজা হচ্ছে..."
                                    } else {
                                        "মডেল খুঁজুন / তালিকা রিফ্রেশ"
                                    }
                                )
                            }

                            Text(
                                modelMessage,
                                fontSize = 12.sp,
                                color = Color.Gray
                            )

                            Spacer(Modifier.height(8.dp))

                            LazyColumn(
                                modifier = Modifier.heightIn(max = 300.dp)
                            ) {
                                items(models) { model ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedModel = model
                                                saveModel(model)
                                                modelMessage =
                                                    "নির্বাচিত মডেল: $model"
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment =
                                            Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selectedModel == model,
                                            onClick = {
                                                selectedModel = model
                                                saveModel(model)
                                                modelMessage =
                                                    "নির্বাচিত মডেল: $model"
                                            }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            model,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            Text("যে সেটিংস পরিবর্তন করতে চাও, সেটি বেছে নাও।")
                            Spacer(Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    apiKey = savedApiKey()
                                    modelMessage = ""
                                    settingsPage = "api"
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = pink
                                )
                            ) {
                                Text("Gemini API Key সেটিংস")
                            }

                            Spacer(Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    selectedModel = savedModel()
                                    models = emptyList()
                                    modelMessage = "মডেল তালিকা এখনো আনা হয়নি।"
                                    settingsPage = "models"
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF7040A0)
                                )
                            ) {
                                Text("Gemini Model খুঁজুন ও নির্বাচন করুন")
                            }

                            Spacer(Modifier.height(12.dp))
                            Text(
                                "বর্তমান মডেল: ${savedModel()}",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("বন্ধ করুন")
                }
            }
        )
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = pink,
            background = dark,
            surface = Color(0xFF261A2B),
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF30152F), dark)
                    )
                )
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "MIRA AI",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "PERSONAL SMART ASSISTANT",
                        color = pink,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp
                    )
                }

                IconButton(
                    onClick = {
                        apiKey = savedApiKey()
                        selectedModel = savedModel()
                        settingsPage = "home"
                        modelMessage = ""
                        showSettings = true
                    }
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = pink
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(135.dp)
                    .background(
                        Color(0xFF492044),
                        RoundedCornerShape(28.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(76.dp)
                            .background(
                                pink.copy(alpha = .18f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "M",
                            color = Color.White,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "তোমার বাংলা AI সঙ্গী",
                        color = Color.White.copy(alpha = .85f),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "কথোপকথন",
                color = pink,
                fontWeight = FontWeight.Bold
            )

            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                items(history) { message ->
                    Text(
                        message,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .background(
                                Color(0xFF281D30),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(12.dp)
                    )
                }
            }

            if (busy) {
                Text(
                    "MIRA উত্তর তৈরি করছে...",
                    color = pink,
                    fontSize = 12.sp
                )
            }

            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("MIRA-কে কিছু বলুন...") },
                shape = RoundedCornerShape(18.dp),
                trailingIcon = {
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            val q = input
                            input = ""
                            sendMessage(q)
                        }
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = pink
                        )
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF281D30),
                    unfocusedContainerColor = Color(0xFF281D30),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    onListen { spoken -> sendMessage(spoken) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = pink
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("কথা বলুন", fontSize = 16.sp)
            }

            Spacer(Modifier.height(6.dp))

            Text(
                "Gemini: ${savedModel()} • Settings খুলতে ⚙️ চাপুন",
                color = Color.LightGray,
                fontSize = 10.sp
            )
        }
    }
}
