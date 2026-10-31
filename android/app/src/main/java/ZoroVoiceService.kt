package com.zaferyilmaz.zoro

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class ZoroVoiceService : Service() {

    companion object {

        const val ACTION_START =
            "com.zaferyilmaz.zoro.START_VOICE"

        const val ACTION_STOP =
            "com.zaferyilmaz.zoro.STOP_VOICE"

        const val ACTION_COMMAND =
            "com.zaferyilmaz.zoro.VOICE_COMMAND"

        const val EXTRA_COMMAND = "voice_command"

        // Yeni: Ana ekrana dinleme durumunu bildirir.
        const val ACTION_STATUS =
            "com.zaferyilmaz.zoro.VOICE_STATUS"

        const val EXTRA_STATUS = "voice_status"

        const val STATUS_OFF = "OFF"
        const val STATUS_STARTING = "STARTING"
        const val STATUS_LISTENING = "LISTENING"
        const val STATUS_WAITING_COMMAND = "WAITING_COMMAND"

        const val STATUS_PREFS = "zoro_voice_status"
        const val STATUS_PREF_KEY = "current_status"

        private const val TAG = "ZORO_VOICE"
        private const val CHANNEL_ID = "zoro_voice"
        private const val NOTIFICATION_ID = 3050

        // ZORO_V2_SPEECH_SYNC
        // Ana ekran cevap okurken mikrofon kapanir, Zoro kendi
        // sesini komut sanmaz. Bitince dinleme geri acilir.
        @Volatile
        private var active: ZoroVoiceService? = null

        fun pauseForSpeech() {
            active?.let { service ->
                service.handler.post { service.pauseListeningForSpeech() }
            }
        }

        fun resumeAfterSpeech(listenForReply: Boolean) {
            active?.let { service ->
                service.handler.post { service.resumeListeningAfterSpeech(listenForReply) }
            }
        }

        val isActive: Boolean
            get() = active?.running == true
    }

    private val handler = Handler(Looper.getMainLooper())
    private val turkish = Locale.forLanguageTag("tr-TR")

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private var running = false
    private var recognizing = false
    private var speaking = false
    private var waitingForCommand = false
    private var ttsReady = false

    private var lastStatus = ""

    private val wakePattern = Regex(
        """\b(?:zoro|zorro|zora|zara)\b""",
        RegexOption.IGNORE_CASE
    )

    // ==========================================
    // YENI: DINLEME DURUMU BILDIRIMI
    // ==========================================

    private fun publishStatus(status: String) {

        if (lastStatus == status) {
            return
        }

        lastStatus = status

        getSharedPreferences(
            STATUS_PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putString(STATUS_PREF_KEY, status)
            .apply()

        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
        }

        sendBroadcast(intent)

        Log.d(TAG, "Ses durumu: $status")
    }

    private val restartListening = Runnable {
        beginListening()
    }

    private val commandTimeout = Runnable {

        if (waitingForCommand) {

            waitingForCommand = false

            Log.d(
                TAG,
                "Komut bekleme süresi doldu. Zoro kelimesi bekleniyor."
            )

            if (running) {
                publishStatus(STATUS_STARTING)
            }
        }
    }

    private val recognitionWatchdog = Runnable {

        if (running && recognizing && !speaking) {

            Log.w(
                TAG,
                "Ses tanıma yanıt vermedi. Oturum yenileniyor."
            )

            recognizing = false

            publishStatus(STATUS_STARTING)

            try {

                recognizer?.cancel()

            } catch (error: Exception) {

                Log.w(
                    TAG,
                    "Dinleme iptal uyarısı",
                    error
                )
            }

            scheduleListening(1800)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()
        initializeTextToSpeech()

        Log.i(
            TAG,
            "Zoro ses hizmeti oluşturuldu."
        )
    }

    // ==========================================
    // SES HIZMETINI BASLAT
    // ==========================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {

            stopSelf()
            return START_NOT_STICKY
        }

        if (running) {

            Log.d(TAG, "Dinleme zaten aktif.")
            return START_NOT_STICKY
        }

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Log.e(
                TAG,
                "Mikrofon izni verilmemiş."
            )

            publishStatus(STATUS_OFF)
            stopSelf()

            return START_NOT_STICKY
        }

        try {

            val notification = buildNotification()

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo
                        .FOREGROUND_SERVICE_TYPE_MICROPHONE
                )

            } else {

                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

            running = true
            active = this

            publishStatus(STATUS_STARTING)

            if (
                !SpeechRecognizer
                    .isRecognitionAvailable(this)
            ) {

                Log.e(
                    TAG,
                    "Ses tanıma hizmeti bulunamadı."
                )

                stopSelf()
                return START_NOT_STICKY
            }

            initializeRecognizer()
            scheduleListening(500)

            Log.i(
                TAG,
                "Zoro dinlemeye hazır."
            )

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Sesli dinleme başlatılamadı.",
                error
            )

            stopSelf()
        }

        return START_NOT_STICKY
    }

    // ==========================================
    // BILDIRIM KANALI
    // ==========================================

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zoro Sesli Dinleme",
                NotificationManager.IMPORTANCE_LOW
            ).apply {

                description =
                    "Zoro mikrofon dinleme hizmeti"
            }

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    // ==========================================
    // SES HIZMETI BILDIRIMI
    // ==========================================

    private fun buildNotification(): Notification {

        val openIntent = Intent(
            this,
            MainActivity::class.java
        )

        val openPendingIntent =
            PendingIntent.getActivity(
                this,
                3051,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val stopIntent = Intent(
            this,
            ZoroVoiceService::class.java
        ).apply {

            action = ACTION_STOP
        }

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                3052,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val builder =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                Notification.Builder(
                    this,
                    CHANNEL_ID
                )

            } else {

                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setContentTitle("Zoro sesli dinleme")
            .setContentText(
                "Zoro kelimesini dinliyorum."
            )
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now
            )
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable
                    .ic_menu_close_clear_cancel,
                "Dinlemeyi durdur",
                stopPendingIntent
            )
            .build()
    }

    // ==========================================
    // ZORO KONUSMA MOTORU
    // ==========================================

    private fun initializeTextToSpeech() {

        tts = TextToSpeech(this) { status ->

            handler.post {

                if (
                    status == TextToSpeech.SUCCESS
                ) {

                    tts?.language = turkish
                    tts?.setPitch(0.85f)
                    tts?.setSpeechRate(0.95f)

                    tts?.setOnUtteranceProgressListener(
                        object :
                            UtteranceProgressListener() {

                            override fun onStart(
                                utteranceId: String?
                            ) {
                            }

                            override fun onDone(
                                utteranceId: String?
                            ) {

                                if (
                                    utteranceId ==
                                    "ZORO_WAKE"
                                ) {

                                    handler.post {

                                        speaking = false

                                        scheduleListening(350)
                                    }
                                }
                            }

                            override fun onError(
                                utteranceId: String?
                            ) {

                                if (
                                    utteranceId ==
                                    "ZORO_WAKE"
                                ) {

                                    handler.post {

                                        speaking = false

                                        scheduleListening(500)
                                    }
                                }
                            }
                        }
                    )

                    ttsReady = true

                    Log.i(
                        TAG,
                        "Türkçe konuşma motoru hazır."
                    )

                } else {

                    ttsReady = false

                    Log.e(
                        TAG,
                        "Konuşma motoru açılamadı."
                    )
                }
            }
        }
    }

    // ==========================================
    // ANDROID SES TANIMA
    // ==========================================

    private fun initializeRecognizer() {

        recognizer =
            SpeechRecognizer
                .createSpeechRecognizer(this)

        recognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(
                    params: Bundle?
                ) {

                    Log.d(
                        TAG,
                        "Mikrofon konuşma bekliyor."
                    )

                    if (waitingForCommand) {

                        publishStatus(
                            STATUS_WAITING_COMMAND
                        )

                    } else {

                        publishStatus(
                            STATUS_LISTENING
                        )
                    }
                }

                override fun onBeginningOfSpeech() {

                    Log.d(
                        TAG,
                        "Konuşma başladı."
                    )
                }

                override fun onRmsChanged(
                    rmsdB: Float
                ) {
                }

                override fun onBufferReceived(
                    buffer: ByteArray?
                ) {
                }

                override fun onEndOfSpeech() {

                    Log.d(
                        TAG,
                        "Konuşma sona erdi."
                    )
                }

                override fun onError(
                    error: Int
                ) {

                    recognizing = false

                    handler.removeCallbacks(
                        recognitionWatchdog
                    )

                    Log.w(
                        TAG,
                        "Ses tanıma hata kodu: $error"
                    )

                    if (!running) {
                        return
                    }

                    if (
                        error ==
                        SpeechRecognizer
                            .ERROR_INSUFFICIENT_PERMISSIONS
                    ) {

                        Log.e(
                            TAG,
                            "Mikrofon yetkisi yok."
                        )

                        stopSelf()
                        return
                    }

                    publishStatus(
                        STATUS_STARTING
                    )

                    val delay =
                        when (error) {

                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                1200L

                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                3000L

                            else ->
                                2000L
                        }

                    scheduleListening(delay)
                }

                override fun onResults(
                    results: Bundle?
                ) {

                    recognizing = false

                    handler.removeCallbacks(
                        recognitionWatchdog
                    )

                    val alternatives = results
                        ?.getStringArrayList(
                            SpeechRecognizer
                                .RESULTS_RECOGNITION
                        )
                        .orEmpty()

                    processSpeech(alternatives)
                }

                override fun onPartialResults(
                    partialResults: Bundle?
                ) {
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?
                ) {
                }
            }
        )
    }

    // ==========================================
    // ZORO_V2_SPEECH_SYNC: KONUSURKEN DUR
    // ==========================================

    private val speechSafetyResume = Runnable {
        Log.w(TAG, "Konusma bitis sinyali gelmedi, dinleme geri aciliyor.")
        resumeListeningAfterSpeech(listenForReply = false)
    }

    private fun pauseListeningForSpeech() {

        if (!running) {
            return
        }

        speaking = true

        handler.removeCallbacks(restartListening)
        handler.removeCallbacks(recognitionWatchdog)

        if (recognizing) {
            try {
                recognizer?.cancel()
            } catch (error: Exception) {
                Log.w(TAG, "Dinleme durdurma uyarisi", error)
            }
            recognizing = false
        }

        // Bitis sinyali kaybolursa servis sonsuza kadar susmasin.
        handler.removeCallbacks(speechSafetyResume)
        handler.postDelayed(speechSafetyResume, 60000)
    }

    private fun resumeListeningAfterSpeech(
        listenForReply: Boolean
    ) {

        handler.removeCallbacks(speechSafetyResume)
        speaking = false

        if (!running) {
            return
        }

        if (listenForReply) {

            // Zoro soru sordu: uyandirma kelimesi beklemeden cevabi dinle.
            waitingForCommand = true

            handler.removeCallbacks(commandTimeout)
            handler.postDelayed(commandTimeout, 10000)

            publishStatus(STATUS_WAITING_COMMAND)
        }

        scheduleListening(450)
    }

    // ==========================================
    // DINLEMEYI BASLAT
    // ==========================================

    private fun beginListening() {

        if (
            !running ||
            recognizing ||
            speaking
        ) {
            return
        }

        val intent = Intent(
            RecognizerIntent
                .ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent
                    .LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "tr-TR"
            )

            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                false
            )

            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                5
            )
        }

        try {

            recognizing = true

            publishStatus(
                STATUS_STARTING
            )

            recognizer?.startListening(intent)

            handler.removeCallbacks(
                recognitionWatchdog
            )

            handler.postDelayed(
                recognitionWatchdog,
                14000
            )

            Log.d(
                TAG,
                "Dinleme oturumu başladı."
            )

        } catch (error: Exception) {

            recognizing = false

            handler.removeCallbacks(
                recognitionWatchdog
            )

            Log.e(
                TAG,
                "Dinleme hatası",
                error
            )

            scheduleListening(2500)
        }
    }

    // ==========================================
    // DINLEMEYI TEKRAR BASLAT
    // ==========================================

    private fun scheduleListening(
        delayMillis: Long
    ) {

        handler.removeCallbacks(
            restartListening
        )

        if (running) {

            handler.postDelayed(
                restartListening,
                delayMillis
            )
        }
    }

    // ==========================================
    // KONUSMAYI ISLE
    // ==========================================

    private fun processSpeech(
        alternatives: List<String>
    ) {

        if (!running) {
            return
        }

        val spoken = alternatives
            .firstOrNull {
                it.isNotBlank()
            }
            .orEmpty()

        if (spoken.isBlank()) {

            publishStatus(STATUS_STARTING)
            scheduleListening(900)

            return
        }

        Log.i(
            TAG,
            "Algılanan konuşma: $spoken"
        )

        if (waitingForCommand) {

            waitingForCommand = false

            handler.removeCallbacks(
                commandTimeout
            )

            sendVoiceCommand(spoken)

            publishStatus(STATUS_STARTING)
            scheduleListening(750)

            return
        }

        var selectedText: String? = null
        var selectedMatch: MatchResult? = null

        for (candidate in alternatives) {

            val match = wakePattern.find(
                candidate.lowercase(turkish)
            )

            if (match != null) {

                selectedText = candidate
                selectedMatch = match

                break
            }
        }

        if (
            selectedText == null ||
            selectedMatch == null
        ) {

            Log.d(
                TAG,
                "Uyandırma kelimesi bulunamadı."
            )

            publishStatus(STATUS_STARTING)
            scheduleListening(900)

            return
        }

        Log.i(
            TAG,
            "Uyandırma kelimesi: ${selectedMatch.value}"
        )

        val command = selectedText
            .substring(
                selectedMatch.range.last + 1
            )
            .trim()
            .trimStart(
                ',',
                '.',
                '!',
                '?',
                ' '
            )

        if (command.isNotBlank()) {

            sendVoiceCommand(command)

            publishStatus(STATUS_STARTING)
            scheduleListening(750)

        } else {

            waitingForCommand = true

            handler.removeCallbacks(
                commandTimeout
            )

            handler.postDelayed(
                commandTimeout,
                12000
            )

            publishStatus(
                STATUS_WAITING_COMMAND
            )

            speakWakeReply()
        }
    }

    // ==========================================
    // DINLIYORUM CEVABI
    // ==========================================

    private fun speakWakeReply() {

        handler.removeCallbacks(
            restartListening
        )

        if (!ttsReady) {

            Log.w(
                TAG,
                "Ses motoru hazır değil, komut bekleniyor."
            )

            scheduleListening(450)
            return
        }

        speaking = true

        val result = tts?.speak(
            "Dinliyorum.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "ZORO_WAKE"
        )

        if (
            result != TextToSpeech.SUCCESS
        ) {

            speaking = false
            scheduleListening(450)
        }
    }

    // ==========================================
    // KOMUTU ANA EKRANA GONDER
    // ==========================================

    private fun sendVoiceCommand(
        command: String
    ) {

        val cleaned = command.trim()

        if (cleaned.isBlank()) {
            return
        }

        Log.i(
            TAG,
            "Zoro komutu: $cleaned"
        )

        val broadcast = Intent(
            ACTION_COMMAND
        ).apply {

            setPackage(packageName)

            putExtra(
                EXTRA_COMMAND,
                cleaned
            )
        }

        sendBroadcast(broadcast)
    }

    // ==========================================
    // HIZMETI KAPAT
    // ==========================================

    override fun onDestroy() {

        if (active === this) {
            active = null
        }

        running = false
        recognizing = false
        speaking = false
        waitingForCommand = false

        publishStatus(STATUS_OFF)

        handler.removeCallbacksAndMessages(
            null
        )

        try {

            recognizer?.cancel()
            recognizer?.destroy()

        } catch (error: Exception) {

            Log.w(
                TAG,
                "Ses tanıma kapatma uyarısı",
                error
            )
        }

        recognizer = null

        tts?.stop()
        tts?.shutdown()
        tts = null

        Log.i(
            TAG,
            "Zoro ses hizmeti kapatıldı."
        )

        super.onDestroy()
    }
}
