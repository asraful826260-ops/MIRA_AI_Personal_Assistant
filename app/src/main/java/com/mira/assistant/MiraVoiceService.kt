package com.mira.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * User-started microphone foreground service. Android displays an ongoing notification
 * while the microphone is in use. Speech recognition availability depends on the
 * device's speech service and network; this is not a low-power hardware wake-word engine.
 */
class MiraVoiceService : Service(), TextToSpeech.OnInitListener {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        tts = TextToSpeech(this, this)
        beginListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!listening) beginListening()
        return START_STICKY
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale("bn", "BD")
    }

    private fun beginListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { listening = false }
                    override fun onError(error: Int) {
                        listening = false
                        if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            android.os.Handler(mainLooper).postDelayed({ beginListening() }, 1200)
                        }
                    }
                    override fun onResults(results: android.os.Bundle?) {
                        listening = false
                        val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty().lowercase(Locale.ROOT)
                        if (said.contains("হ্যালো মাইরা") || said.contains("হ্যালো মীরা") ||
                            said.contains("hello mira") || said.contains("hello myra")) {
                            if (ttsReady) tts?.speak("হ্যাঁ, বলো। আমি শুনছি।", TextToSpeech.QUEUE_FLUSH, null, "mira_wake")
                        }
                        android.os.Handler(mainLooper).postDelayed({ beginListening() }, 900)
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                }
                sr.startListening(intent)
                listening = true
            }
        } catch (_: Exception) {
            listening = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MIRA Background Voice", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, MiraVoiceService::class.java).setAction(ACTION_STOP)
        val pending = android.app.PendingIntent.getService(
            this, 7, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MIRA Voice চালু আছে")
            .setContentText("‘হ্যালো মাইরা’ বললে MIRA সাড়া দেওয়ার চেষ্টা করবে")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "বন্ধ করুন", pending).build())
            .build()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.mira.assistant.STOP_VOICE_SERVICE"
        private const val CHANNEL_ID = "mira_voice_channel"
        private const val NOTIFICATION_ID = 2401
    }
}
