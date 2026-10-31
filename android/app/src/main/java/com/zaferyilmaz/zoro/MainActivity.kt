
package com.zaferyilmaz.zoro

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.core.content.ContextCompat

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

import org.json.JSONArray
import org.json.JSONObject

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

// ==========================================
// GOREV VERI MODELI
// ==========================================

data class ZoroTaskItem(
    val number: Int,
    val id: String,
    val text: String,
    val dueAt: String,
    val done: Boolean,
    val repeatInterval: Long
)

// ==========================================
// GOREV ISLEM TURU
// ==========================================

enum class ZoroTaskOperation(
    val apiPath: String
) {
    COMPLETE("complete"),
    DELETE("delete")
}

// ==========================================
// ANA ACTIVITY
// ==========================================

class MainActivity : ComponentActivity(), ZoroReminderPort {

    private lateinit var textToSpeech: TextToSpeech

    // ZORO_V2_SERVER_CONFIG
    // Sunucu adresi ve kimlik bilgisi zoro.properties dosyasindan gelir.
    private val httpClient: OkHttpClient = ZoroServer.buildHttpClient()

    private val backendUrl: String
        get() = ZoroServer.url("/ask")

    private val tasksUrl: String
        get() = ZoroServer.url("/tasks")

    // Son sunucu cevabi ana ekranda yazili da gorunur.
    private var lastReply by mutableStateOf("")

    // Cevap okunduktan sonra yapilacak is (harita, arama ...).
    private var afterSpeechId: String? = null
    private var afterSpeech: (() -> Unit)? = null
    private var listenAfterSpeech = false
    private var servicePausedForSpeech = false

    private val tag = "ZORO_REMINDER"

    private var commandReceiverRegistered = false

    private var voiceStatus by mutableStateOf(
        ZoroVoiceService.STATUS_OFF
    )

    private var tasksScreenOpen by mutableStateOf(false)

    private var calendarScreenOpen by mutableStateOf(false)

    private var calendarPermissionGranted by mutableStateOf(false)

    private val calendarPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            calendarPermissionGranted = granted
        }
    private var tasksLoading by mutableStateOf(false)
    private var tasksError by mutableStateOf("")
    private var tasksMessage by mutableStateOf("")

    private var taskItems by mutableStateOf(
        emptyList<ZoroTaskItem>()
    )

    private var addingTask by mutableStateOf(false)

    private var taskActionInProgress by mutableStateOf(false)

    private var taskSavedCounter by mutableStateOf(0)

    // ==========================================
    // SES DURUMU
    // ==========================================

    private fun readSavedVoiceStatus() {

        voiceStatus = getSharedPreferences(
            ZoroVoiceService.STATUS_PREFS,
            Context.MODE_PRIVATE
        ).getString(
            ZoroVoiceService.STATUS_PREF_KEY,
            ZoroVoiceService.STATUS_OFF
        ) ?: ZoroVoiceService.STATUS_OFF
    }

    // ==========================================
    // MIKROFON IZNI
    // ==========================================

    private val microphonePermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                launchVoiceService()

            } else {

                Toast.makeText(
                    this,
                    "Mikrofon izni gerekiyor.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // ==========================================
    // SESLI KOMUT ALICISI
    // ==========================================

    private val voiceCommandReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {

                when (intent.action) {

                    ZoroVoiceService.ACTION_STATUS -> {

                        val status = intent.getStringExtra(
                            ZoroVoiceService.EXTRA_STATUS
                        )

                        if (!status.isNullOrBlank()) {
                            voiceStatus = status
                        }
                    }

                    ZoroVoiceService.ACTION_COMMAND -> {

                        val command = intent.getStringExtra(
                            ZoroVoiceService.EXTRA_COMMAND
                        )?.trim().orEmpty()

                        if (command.isNotEmpty()) {

                            askZoro(
                                message = command,
                                refreshTasksAfter = tasksScreenOpen
                            )
                        }
                    }
                }
            }
        }

    // ==========================================
    // UYGULAMA BASLANGICI
    // ==========================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)
        // ZORO_MORNING_BRIEFING_START
// Her sabah saat 10.00 icin brifing alarmi kur.
        ZoroMorningBriefingScheduler.scheduleNext(applicationContext)
        // ZORO_SAFE_FULLSCREEN_V2
        // Sistem cubuklarini yalnizca Zoro icinde gizle.
        // Icerigi guvenli ekran alaninda tut.
        androidx.core.view.WindowCompat
            .setDecorFitsSystemWindows(window, true)

        val zoroFullscreenController =
            androidx.core.view.WindowInsetsControllerCompat(
                window,
                window.decorView
            )

        zoroFullscreenController.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        zoroFullscreenController.hide(
            androidx.core.view.WindowInsetsCompat.Type.systemBars()
        )


        requestRequiredPermissions()
        initializeTextToSpeech()
        createNotificationChannel()
        readSavedVoiceStatus()
        registerVoiceReceiver()
        syncLocalMemoriesOnce()

        setContent {

            BackHandler(enabled = calendarScreenOpen) {
                calendarScreenOpen = false
            }

            if (calendarScreenOpen) {

                ZoroCalendarScreen(
                    onBack = {
                        calendarScreenOpen = false
                    },
                    requestCalendarPermission = {
                        calendarPermissionLauncher.launch(
                            Manifest.permission.READ_CALENDAR
                        )
                    }
                )

            } else {

            ZoroApp(
                voiceStatus = voiceStatus,
                tasksScreenOpen = tasksScreenOpen,
                tasksLoading = tasksLoading,
                tasksError = tasksError,
                tasksMessage = tasksMessage,
                addingTask = addingTask,
                taskActionInProgress = taskActionInProgress,
                taskSavedCounter = taskSavedCounter,
                taskItems = taskItems,

                startVoiceInput = {
                    startVoiceInput()
                },

                startVoiceListening = {
                    startVoiceListening()
                },

                stopVoiceListening = {
                    stopVoiceListening()
                },

                openTasks = {
                    openTasksScreen()
                },

                openCalendar = {
                    calendarScreenOpen = true
                },

                refreshTasks = {
                    loadTasks()
                },

                closeTasks = {
                    tasksScreenOpen = false
                },

                addTask = { text ->
                    addTaskFromScreen(text)
                },

                startTaskVoice = {
                    startTaskVoiceInput()
                },

                editTask = { task, text, dueAt ->
                    editTaskFromScreen(task, text, dueAt)
                },
                performTaskAction = { task, operation ->
                    performTaskAction(task, operation)
                },
                lastReply = lastReply,
                askText = { text ->
                    askZoro(text, typed = true)
                }
            )
            }
        }
    }

    override fun onResume() {

        super.onResume()
        readSavedVoiceStatus()

        calendarPermissionGranted =
            checkSelfPermission(
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
    }

    // ==========================================
    // ANDROID IZINLERI
    // ==========================================

    private fun requestRequiredPermissions() {

        if (
            Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(
                Manifest.permission.ACCESS_LOCAL_NETWORK
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_LOCAL_NETWORK
                ),
                2001
            )
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            requestPermissions(
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                2002
            )
        }

        // ZORO_V2_PERMISSIONS
        // "Okay'i ara", "Okay'a yaz" icin rehber ve arama izni.
        val callPermissions = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        ).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (callPermissions.isNotEmpty()) {
            requestPermissions(
                callPermissions.toTypedArray(),
                2003
            )
        }
    }

    // ==========================================
    // SESLENDIRME
    // ==========================================

    private fun initializeTextToSpeech() {

        textToSpeech = TextToSpeech(this) { status ->

            if (
                status == TextToSpeech.SUCCESS &&
                ::textToSpeech.isInitialized
            ) {

                textToSpeech.language =
                    Locale.forLanguageTag("tr-TR")

                textToSpeech.setPitch(0.85f)
                textToSpeech.setSpeechRate(0.95f)

                textToSpeech.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {

                        override fun onStart(utteranceId: String?) {
                        }

                        override fun onDone(utteranceId: String?) {
                            runOnUiThread { onSpeechFinished(utteranceId) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            runOnUiThread { onSpeechFinished(utteranceId) }
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            runOnUiThread { onSpeechFinished(utteranceId) }
                        }
                    }
                )
            }
        }
    }

    // ==========================================
    // EMOJI TEMIZLEME
    // ==========================================

    private fun cleanTextForSpeech(
        originalText: String
    ): String {

        val cleaned = StringBuilder()
        var index = 0

        while (index < originalText.length) {

            val codePoint =
                Character.codePointAt(originalText, index)

            val isEmoji =
                codePoint in 0x1F000..0x1FAFF ||
                        codePoint in 0x2600..0x27BF ||
                        codePoint in 0xFE00..0xFE0F ||
                        codePoint == 0x200D ||
                        codePoint == 0x20E3

            if (!isEmoji) {
                cleaned.appendCodePoint(codePoint)
            }

            index += Character.charCount(codePoint)
        }

        return cleaned
            .toString()
            .replace("*", "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .trim()
    }

    private fun speak(
        text: String,
        utteranceId: String
    ) {

        val cleanText = cleanTextForSpeech(text)

        if (cleanText.isBlank() || !::textToSpeech.isInitialized) {
            onSpeechFinished(utteranceId)
            return
        }

        // ZORO_V2_SPEECH_SYNC
        // Konusurken arka plandaki dinleme servisini sustur.
        if (ZoroVoiceService.isActive) {
            servicePausedForSpeech = true
            ZoroVoiceService.pauseForSpeech()
        }

        val result = textToSpeech.speak(
            cleanText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )

        if (result != TextToSpeech.SUCCESS) {
            onSpeechFinished(utteranceId)
        }
    }

    // ==========================================
    // ZORO 2.0: KONUS, SONRA YAP
    // ==========================================

    private fun speakThen(
        text: String,
        listenForReply: Boolean,
        after: (() -> Unit)?
    ) {

        val id = "ZORO_REPLY_" + System.currentTimeMillis()

        afterSpeechId = id
        afterSpeech = after
        listenAfterSpeech = listenForReply

        speak(text, id)
    }

    private fun onSpeechFinished(utteranceId: String?) {

        var listen = false

        if (utteranceId != null && utteranceId == afterSpeechId) {

            val work = afterSpeech
            listen = listenAfterSpeech

            afterSpeechId = null
            afterSpeech = null
            listenAfterSpeech = false

            try {
                work?.invoke()
            } catch (error: Exception) {
                Log.e("ZORO_ACTIONS", "Cevap sonrasi islem hatasi", error)
            }
        }

        if (servicePausedForSpeech) {

            servicePausedForSpeech = false
            ZoroVoiceService.resumeAfterSpeech(listenForReply = listen)

        } else if (listen) {

            // Arka plan dinleme kapaliysa soruya cevabi
            // Google ses penceresiyle al.
            startVoiceInput()
        }
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
                "zoro_reminders",
                "Zoro Hatırlatmaları",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {

                description =
                    "Zoro görev ve hatırlatma bildirimleri"
            }

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    // ==========================================
    // SES ALICISINI KAYDET
    // ==========================================

    private fun registerVoiceReceiver() {

        val filter = IntentFilter().apply {

            addAction(
                ZoroVoiceService.ACTION_COMMAND
            )

            addAction(
                ZoroVoiceService.ACTION_STATUS
            )
        }

        ContextCompat.registerReceiver(
            this,
            voiceCommandReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        commandReceiverRegistered = true
    }

    // ==========================================
    // SESLI DINLEME BASLAT
    // ==========================================

    private fun startVoiceListening() {

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            launchVoiceService()

        } else {

            microphonePermission.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun launchVoiceService() {

        try {

            val intent = Intent(
                this,
                ZoroVoiceService::class.java
            ).apply {

                action = ZoroVoiceService.ACTION_START
            }

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                startForegroundService(intent)

            } else {

                startService(intent)
            }

            voiceStatus =
                ZoroVoiceService.STATUS_STARTING

            Toast.makeText(
                this,
                "Zoro sesli dinleme başlatılıyor.",
                Toast.LENGTH_SHORT
            ).show()

        } catch (error: Exception) {

            voiceStatus =
                ZoroVoiceService.STATUS_OFF

            Log.e(
                "ZORO_VOICE",
                "Ses hizmeti baslatma hatasi",
                error
            )
        }
    }

    // ==========================================
    // SESLI DINLEME DURDUR
    // ==========================================

    private fun stopVoiceListening() {

        try {

            stopService(
                Intent(
                    this,
                    ZoroVoiceService::class.java
                )
            )

            voiceStatus =
                ZoroVoiceService.STATUS_OFF

            Toast.makeText(
                this,
                "Zoro sesli dinleme durduruldu.",
                Toast.LENGTH_SHORT
            ).show()

        } catch (error: Exception) {

            Log.e(
                "ZORO_VOICE",
                "Ses hizmeti durdurma hatasi",
                error
            )
        }
    }

    // ==========================================
    // ANA EKRANDAN SESLI KOMUT
    // ==========================================

    private fun startVoiceInput() {

        openSpeechRecognizer(1001)
    }

    // ==========================================
    // GOREV EKRANINDAN SESLI KOMUT
    // ==========================================

    private fun startTaskVoiceInput() {

        tasksMessage = ""

        openSpeechRecognizer(1002)
    }

    private fun openSpeechRecognizer(
        requestCode: Int
    ) {

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "tr-TR"
            )

            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                if (requestCode == 1002) {
                    "Eklemek istediğin görevi söyle."
                } else {
                    "Zoro seni dinliyor..."
                }
            )
        }

        try {

            @Suppress("DEPRECATION")
            startActivityForResult(
                intent,
                requestCode
            )

        } catch (error: Exception) {

            Log.e(
                "ZORO_VOICE",
                "Sesli giris hatasi",
                error
            )

            Toast.makeText(
                this,
                "Sesli giriş açılamadı.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated(
        "Mevcut ses tanima sonucunu almak icin"
    )
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (resultCode != RESULT_OK) return

        val spokenText =
            data?.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS
            )?.firstOrNull()?.trim().orEmpty()

        if (spokenText.isBlank()) return

        Log.d("ZORO_SPEECH_TEXT", "Algilanan komut: $spokenText")


        when (requestCode) {

            1001 -> {
                askZoro(spokenText)
            }

            1002 -> {
                addTaskFromScreen(spokenText)
            }
        }
    }

    // ==========================================
    // GOREVLER SAYFASI
    // ==========================================

    private fun openTasksScreen() {

        tasksScreenOpen = true
        tasksMessage = ""

        loadTasks()
    }

    // ==========================================
    // GOREVLERI LISTELE
    // ==========================================

    private fun loadTasks() {

        tasksLoading = true
        tasksError = ""

        val request = Request.Builder()
            .url(tasksUrl)
            .get()
            .build()

        httpClient.newCall(request).enqueue(
            object : Callback {

                override fun onFailure(
                    call: Call,
                    e: IOException
                ) {

                    Log.e(
                        "ZORO_TASKS",
                        "Görev listesi hatası",
                        e
                    )

                    runOnUiThread {

                        tasksLoading = false

                        tasksError =
                            "Görevler yüklenemedi. " +
                                    "Python sunucusunu ve " +
                                    "Wi-Fi bağlantısını kontrol et."
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response
                ) {

                    response.use { result ->

                        try {

                            if (!result.isSuccessful) {

                                throw IOException(
                                    "Sunucu hatası: ${result.code}"
                                )
                            }

                            val responseText =
                                result.body
                                    ?.string()
                                    .orEmpty()

                            val json =
                                JSONObject(responseText)

                            if (
                                !json.optBoolean(
                                    "success",
                                    false
                                )
                            ) {

                                throw IOException(
                                    "Görev listesi okunamadı."
                                )
                            }

                            val array =
                                json.optJSONArray("tasks")

                            val loaded =
                                mutableListOf<ZoroTaskItem>()

                            if (array != null) {

                                for (
                                index in 0 until array.length()
                                ) {

                                    val task =
                                        array.optJSONObject(index)
                                            ?: continue

                                    loaded.add(
                                        ZoroTaskItem(
                                            number =
                                                task.optInt(
                                                    "number",
                                                    index + 1
                                                ),
                                            id =
                                                task.optString(
                                                    "id",
                                                    ""
                                                ),
                                            text =
                                                task.optString(
                                                    "text",
                                                    ""
                                                ),
                                            dueAt =
                                                task.optString(
                                                    "due_at",
                                                    ""
                                                ),
                                            done =
                                                task.optBoolean(
                                                    "done",
                                                    false
                                                ),
                                            repeatInterval =
                                                task.optLong(
                                                    "repeat_interval",
                                                    0L
                                                )
                                        )
                                    )
                                }
                            }

                            runOnUiThread {

                                taskItems = loaded
                                tasksError = ""
                                tasksLoading = false
                            }

                        } catch (error: Exception) {

                            Log.e(
                                "ZORO_TASKS",
                                "Görev verisi işleme hatası",
                                error
                            )

                            runOnUiThread {

                                tasksLoading = false

                                tasksError =
                                    "Görev verileri okunamadı."
                            }
                        }
                    }
                }
            }
        )
    }

    // ==========================================
    // YAZARAK VEYA SESLE GOREV EKLE
    // ==========================================

    private fun addTaskFromScreen(
        enteredText: String
    ) {

        val clean = enteredText.trim()

        if (clean.isBlank()) {

            tasksMessage =
                "Lütfen önce bir görev yaz veya söyle."

            return
        }

        if (
            addingTask ||
            taskActionInProgress
        ) {
            return
        }

        val command =
            if (
                clean.startsWith(
                    "görev ekle",
                    ignoreCase = true
                )
            ) {
                clean
            } else {
                "Görev ekle $clean"
            }

        addingTask = true
        tasksMessage = "Görev kaydediliyor..."

        askZoro(
            message = command,
            refreshTasksAfter = true,
            fromTaskScreen = true
        )
    }

    // ==========================================
    // YENI: GOREVI TAMAMLA VEYA SIL
    // ==========================================

    // ZORO_TASK_EDIT_ANDROID_V1
    private fun editTaskFromScreen(
        task: ZoroTaskItem,
        newText: String,
        newDueAt: String
    ) {
        if (taskActionInProgress || addingTask || task.id.isBlank()) {
            return
        }

        if (newText.isBlank()) {
            tasksMessage = "Görev adı boş olamaz."
            return
        }

        taskActionInProgress = true
        tasksMessage = "Görev güncelleniyor..."

        val payload = JSONObject().apply {
            put("text", newText.trim())
            if (newDueAt != task.dueAt.take(16)) {
                put("due_at", newDueAt)
            }
        }

        val request = Request.Builder()
            .url("$tasksUrl/${Uri.encode(task.id)}/edit")
            .post(
                payload.toString().toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )
            )
            .build()

        httpClient.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("ZORO_TASK_EDIT", "Bağlantı hatası", e)

                    runOnUiThread {
                        taskActionInProgress = false
                        tasksMessage =
                            "Sunucuya bağlanılamadı. Görevleri yenileyerek kontrol edin."
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { result ->
                        try {
                            val json = JSONObject(
                                result.body?.string().orEmpty()
                            )

                            if (!result.isSuccessful ||
                                !json.optBoolean("success", false)
                            ) {
                                val message = json.optString(
                                    "reply", "Görev güncellenemedi."
                                )

                                runOnUiThread {
                                    taskActionInProgress = false
                                    tasksMessage = message
                                }
                                return
                            }

                            val savedText = json.optString("text", newText)
                            val savedDue = json.optString("due_at", newDueAt)
                            val interval = json.optLong(
                                "repeat_interval", task.repeatInterval
                            )

                            runOnUiThread {
                                try {
                                    val dateOrTimeChanged =
                                        newDueAt != task.dueAt.take(16)

                                    if (dateOrTimeChanged) {
                                        // Tarih veya saat degistiyse alarmi yenile.
                                        cancelReminderByAlarmId(task.id)

                                        scheduleReminder(
                                            taskText = savedText,
                                            dueAt = savedDue,
                                            alarmId = task.id,
                                            repeatInterval = interval
                                        )
                                    }
                                    // Yalnizca isim degistiyse mevcut alarma dokunma.

                                    tasksMessage = "Görev güncellendi."
                                } catch (error: Exception) {
                                    Log.e(
                                        "ZORO_TASK_EDIT",
                                        "Alarm yenileme hatası",
                                        error
                                    )

                                    tasksMessage =
                                        "Görev kaydedildi ancak alarm kontrol edilmeli."
                                } finally {
                                    taskActionInProgress = false
                                    loadTasks()
                                }
                            }

                        } catch (error: Exception) {
                            Log.e(
                                "ZORO_TASK_EDIT",
                                "Sunucu yanıtı hatası",
                                error
                            )

                            runOnUiThread {
                                taskActionInProgress = false
                                tasksMessage =
                                    "İşlem sonucu doğrulanamadı. Görevleri yenileyin."
                            }
                        }
                    }
                }
            }
        )
    }
    private fun performTaskAction(
        task: ZoroTaskItem,
        operation: ZoroTaskOperation
    ) {

        if (
            taskActionInProgress ||
            addingTask
        ) {
            return
        }

        if (task.id.isBlank()) {

            tasksMessage =
                "Görev kimliği bulunamadı. " +
                        "İşlem yapılamadı."

            return
        }

        if (
            operation == ZoroTaskOperation.COMPLETE &&
            task.done
        ) {

            tasksMessage =
                "Bu görev zaten tamamlanmış."

            return
        }

        taskActionInProgress = true

        tasksMessage =
            if (operation == ZoroTaskOperation.COMPLETE) {
                "Görev tamamlanıyor..."
            } else {
                "Görev siliniyor..."
            }

        val encodedId = Uri.encode(task.id)

        val url =
            "$tasksUrl/$encodedId/${operation.apiPath}"

        val emptyBody = "{}".toRequestBody(
            "application/json; charset=utf-8"
                .toMediaType()
        )

        val request = Request.Builder()
            .url(url)
            .post(emptyBody)
            .build()

        httpClient.newCall(request).enqueue(
            object : Callback {

                override fun onFailure(
                    call: Call,
                    e: IOException
                ) {

                    Log.e(
                        "ZORO_TASK_ACTION",
                        "Görev işlemi bağlantı hatası",
                        e
                    )

                    runOnUiThread {

                        taskActionInProgress = false

                        tasksMessage =
                            "Sunucuya bağlanılamadı. " +
                                    "İşlem durumunu görmek için " +
                                    "Görevleri Yenile'ye bas."
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response
                ) {

                    response.use { result ->

                        try {

                            val responseText =
                                result.body
                                    ?.string()
                                    .orEmpty()

                            val json =
                                JSONObject(responseText)

                            val success =
                                result.isSuccessful &&
                                        json.optBoolean(
                                            "success",
                                            false
                                        )

                            if (!success) {

                                val message =
                                    json.optString(
                                        "reply",
                                        "İşlem gerçekleştirilemedi."
                                    )

                                runOnUiThread {

                                    taskActionInProgress = false
                                    tasksMessage = message

                                    loadTasks()
                                }

                                return
                            }

                            val serverAlarmId =
                                json.optString(
                                    "alarm_id",
                                    ""
                                )

                            val alarmId =
                                if (
                                    serverAlarmId.isNotBlank() &&
                                    serverAlarmId != "null"
                                ) {
                                    serverAlarmId
                                } else {
                                    task.id
                                }

                            val reply =
                                json.optString(
                                    "reply",
                                    if (
                                        operation ==
                                        ZoroTaskOperation.COMPLETE
                                    ) {
                                        "Görev tamamlandı."
                                    } else {
                                        "Görev silindi."
                                    }
                                )

                            runOnUiThread {

                                // Python işlemi başarılı
                                // olduktan sonra yalnızca
                                // ilgili Android alarmını iptal et.

                                cancelReminderByAlarmId(
                                    alarmId
                                )

                                taskActionInProgress = false

                                tasksMessage = reply

                                // Güncel görevleri tekrar getir.
                                loadTasks()
                            }

                        } catch (error: Exception) {

                            Log.e(
                                "ZORO_TASK_ACTION",
                                "Görev işlemi yanıt hatası",
                                error
                            )

                            runOnUiThread {

                                taskActionInProgress = false

                                tasksMessage =
                                    "İşlem sonucu doğrulanamadı. " +
                                            "Tekrar işlem yapmadan önce " +
                                            "Görevleri Yenile'ye bas."
                            }
                        }
                    }
                }
            }
        )
    }

    // ==========================================
    // PYTHON SUNUCUSUNA KOMUT GONDER
    // ==========================================

    private fun askZoro(
        message: String,
        refreshTasksAfter: Boolean = false,
        fromTaskScreen: Boolean = false,
        typed: Boolean = false
    ) {


        // ZORO_V2_SERVER_FIRST
        // Butun komutlar once sunucudaki yapay zekaya gider.
        // Takvim baglami da gonderilir ki "toplantim ne zaman" anlasilsin.
        val calendarContext = try {
            ZoroActions.calendarContext(applicationContext)
        } catch (error: Exception) {
            null
        }

        val body = ZoroServer.askBody(
            message = message,
            calendar = calendarContext
        )
            .toString()
            .toRequestBody(
                "application/json; charset=utf-8"
                    .toMediaType()
            )

        val request = Request.Builder()
            .url(backendUrl)
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(
            object : Callback {

                override fun onFailure(
                    call: Call,
                    e: IOException
                ) {

                    Log.e(
                        "ZORO_NETWORK",
                        "Bağlantı hatası",
                        e
                    )

                    runOnUiThread {

                        addingTask = false

                        if (fromTaskScreen) {

                            tasksMessage =
                                "Bağlantı kurulamadı. " +
                                        "Görev kaydedilemedi."

                        } else if (!handleLocalCommand(message)) {

                            Toast.makeText(
                                this@MainActivity,
                                "Bağlantı hatası: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()

                            speak(
                                "Sunucuya bağlanamadım. İnternet bağlantınızı kontrol edin.",
                                "ZORO_ERROR"
                            )
                        }
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response
                ) {

                    response.use { result ->

                        try {

                            val responseText =
                                result.body
                                    ?.string()
                                    .orEmpty()

                            val json = try {
                                JSONObject(responseText)
                            } catch (error: Exception) {
                                null
                            }

                            // ZORO_V2_ERROR_REPLY
                            // Sunucu hata dondugunde de kendi cevabini okut.
                            if (!result.isSuccessful) {

                                val errorReply = when (result.code) {
                                    401, 403 ->
                                        "Sunucu kimlik doğrulaması başarısız. Uygulama ayarlarını kontrol edin."
                                    else ->
                                        json?.optString("reply", "")
                                            .orEmpty()
                                            .ifBlank { "Sunucu cevabında bir sorun oluştu." }
                                }

                                runOnUiThread {

                                    addingTask = false

                                    if (fromTaskScreen) {
                                        tasksMessage = errorReply
                                    } else {
                                        lastReply = errorReply
                                        speak(errorReply, "ZORO_RESPONSE_ERROR")
                                    }
                                }

                                return
                            }

                            if (json == null) {
                                throw IOException("Sunucu cevabı okunamadı.")
                            }

                            val serverActions = ZoroActions.list(json)

                            val action =
                                json.optString(
                                    "action",
                                    ""
                                )

                            // Yeni sunucu "actions" listesi gonderir; eski tek
                            // "action" alani yalnizca eski sunucu icin kullanilir.
                            if (serverActions == null) handleBackendAction(
                                action = action,
                                taskText = json.optString(
                                    "task_text",
                                    ""
                                ),
                                dueAt = json.optString(
                                    "due_at",
                                    ""
                                ),
                                alarmId = json.optString(
                                    "alarm_id",
                                    ""
                                ),
                                repeatInterval = json.optLong(
                                    "repeat_interval",
                                    0L
                                ),
                                cancelDueAts = jsonStringList(
                                    json,
                                    "cancel_due_ats"
                                ),
                                cancelAlarmIds = jsonStringList(
                                    json,
                                    "cancel_alarm_ids"
                                )
                            )

                            val reply =
                                json.optString(
                                    "reply",
                                    ""
                                )

                            val expectReply =
                                json.optBoolean(
                                    "expect_reply",
                                    false
                                )

                            runOnUiThread {

                                addingTask = false

                                // Takvim ve alarm degisiklikleri cevap okunmadan once yapilir;
                                // basarisiz olursa sunucunun cevabi yerine hata okunur.
                                val failures =
                                    if (serverActions != null)
                                        ZoroActions.runDataActions(
                                            applicationContext,
                                            serverActions,
                                            this@MainActivity
                                        )
                                    else emptyList()

                                val spoken =
                                    if (failures.isNotEmpty())
                                        failures.joinToString(" ")
                                    else reply

                                if (fromTaskScreen) {

                                    tasksMessage = spoken

                                    if (
                                        action == "add_task"
                                    ) {
                                        taskSavedCounter++
                                    }

                                } else if (
                                    spoken.isNotBlank()
                                ) {

                                    lastReply = spoken

                                    speakThen(
                                        text = spoken,
                                        // Yazili komutta sesli takip penceresi acilmaz.
                                        listenForReply = expectReply && failures.isEmpty() && !typed
                                    ) {

                                        if (serverActions != null && failures.isEmpty()) {

                                            val actionFailures =
                                                ZoroActions.runIntentActions(
                                                    this@MainActivity,
                                                    serverActions
                                                )

                                            if (actionFailures.isNotEmpty()) {
                                                val text = actionFailures.joinToString(" ")
                                                lastReply = text
                                                speak(text, "ZORO_ACTION_ERROR")
                                            }
                                        }
                                    }
                                }

                                val remindersChanged =
                                    serverActions?.any {
                                        it.optString("type") in listOf(
                                            "schedule_reminder",
                                            "cancel_reminder"
                                        )
                                    } == true

                                if (
                                    refreshTasksAfter &&
                                    (remindersChanged || action in listOf(
                                        "add_task",
                                        "delete_task",
                                        "complete_task",
                                        "clear_tasks"
                                    ))
                                ) {

                                    loadTasks()
                                }
                            }

                        } catch (error: Exception) {

                            Log.e(
                                "ZORO_RESPONSE",
                                "Sunucu cevabı hatası",
                                error
                            )

                            runOnUiThread {

                                addingTask = false

                                if (fromTaskScreen) {

                                    tasksMessage =
                                        "Görev kaydı doğrulanamadı. " +
                                                "Görevleri Yenile ile kontrol et."

                                } else {

                                    speak(
                                        "Sunucu cevabında bir sorun oluştu.",
                                        "ZORO_RESPONSE_ERROR"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    // ==========================================
    // ZORO 2.0: BAGLANTI YOKKEN YEREL KOMUTLAR
    // ==========================================
    // Eski kelime avcilari. Sunucuya ulasilamazsa calisir.
    // Randevu silme/erteleme avcisi ("sil" Silivri'yi de yakaliyordu)
    // cevrimdisi modda da kapali.

    private fun handleLocalCommand(
        message: String
    ): Boolean {

        // ZORO_PERSONAL_MEMORY_ROUTING_V1
        // Kisisel hafiza komutlarini once yerel olarak isle.
        if (ZoroMemoryVoice.isMemoryCommand(message)) {

            Thread {
                val answer = try {
                    ZoroMemoryVoice.handleCommand(
                        applicationContext,
                        message
                    )
                } catch (error: Exception) {
                    Log.e(
                        "ZORO_MEMORY",
                        "Hafiza komutu hatasi",
                        error
                    )
                    "Kisisel hafiza islemi tamamlanamadi."
                }

                runOnUiThread {
                    lastReply = answer
                    speak(
                        answer,
                        "ZORO_MEMORY_REPLY"
                    )
                }
            }.start()

            return true
        }
        // ZORO_CALENDAR_REMINDER_VOICE_ROUTING
        // Mevcut randevunun hatirlatma suresini sesle degistir.
        // Bu kontrol genel Python hatirlatmalarindan once calisir.
        if (
            ZoroCalendarReminderVoice.isReminderChangeCommand(
                message
            )
        ) {

            Thread {
                val answer = try {
                    ZoroCalendarReminderVoice.handleCommand(
                        applicationContext,
                        message
                    )
                } catch (error: Exception) {
                    Log.e(
                        "ZORO_REMINDER_VOICE",
                        "Hatirlatma suresi komut hatasi",
                        error
                    )
                    "Randevu hatirlatma suresi degistirilemedi."
                }

                runOnUiThread {
                    lastReply = answer
                    speak(
                        answer,
                        "ZORO_REMINDER_VOICE_REPLY"
                    )
                }
            }.start()

            return true
        }
        // ZORO_CALENDAR_ADD_ROUTING
        // Sesli randevulari telefondaki Zoro takvimine kaydet.
        if (ZoroCalendarVoice.isCalendarAddCommand(message)) {

            Thread {
                val answer = try {
                    ZoroCalendarVoice.addCalendarEvent(
                        applicationContext,
                        message
                    )
                } catch (error: Exception) {
                    Log.e(
                        "ZORO_CALENDAR_ADD",
                        "Sesli randevu ekleme hatasi",
                        error
                    )
                    "Randevu kaydedilirken bir hata olustu."
                }

                runOnUiThread {
                    lastReply = answer
                    speak(
                        answer,
                        "ZORO_CALENDAR_ADD_REPLY"
                    )
                }
            }.start()

            return true
        }
        // ZORO_CALENDAR_VOICE_ROUTING
        // Takvim sorgularini Python yerine telefonda isle.
        if (ZoroCalendarVoice.isCalendarQuery(message)) {

            Thread {
                val answer = try {
                    ZoroCalendarVoice.getCalendarAnswer(
                        applicationContext,
                        message
                    )
                } catch (error: Exception) {
                    Log.e(
                        "ZORO_CALENDAR_VOICE",
                        "Takvim sesli komut hatasi",
                        error
                    )
                    "Takvim sorgusu yapilirken bir hata olustu."
                }

                runOnUiThread {
                    lastReply = answer
                    speak(
                        answer,
                        "ZORO_CALENDAR_REPLY"
                    )
                }
            }.start()

            return true
        }

        return false
    }

    // ==========================================
    // ZORO 2.0: TELEFON HAFIZASINI SUNUCUYA TASI
    // ==========================================
    // Eski surumde "bunu unutma" kayitlari yalnizca telefonda
    // duruyordu, yapay zeka goremiyordu. Bir kez sunucuya yuklenir.
    // Telefondaki kayitlar silinmez.

    private fun syncLocalMemoriesOnce() {

        val prefs = getSharedPreferences("zoro_v2", Context.MODE_PRIVATE)

        if (prefs.getBoolean("memories_synced", false)) {
            return
        }

        val memories = try {
            ZoroMemoryManager(applicationContext).getAllMemories()
        } catch (error: Exception) {
            Log.e("ZORO_MEMORY", "Yerel hafiza okunamadi", error)
            return
        }

        if (memories.isEmpty()) {
            prefs.edit().putBoolean("memories_synced", true).apply()
            return
        }

        val items = JSONArray()

        memories.forEach {
            items.put(JSONObject().put("text", it.text))
        }

        val request = Request.Builder()
            .url(ZoroServer.url("/memory/import"))
            .post(
                JSONObject()
                    .put("items", items)
                    .toString()
                    .toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
            )
            .build()

        httpClient.newCall(request).enqueue(
            object : Callback {

                override fun onFailure(call: Call, e: IOException) {
                    Log.w("ZORO_MEMORY", "Hafiza yuklenemedi, sonra tekrar denenecek", e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) {
                            prefs.edit().putBoolean("memories_synced", true).apply()
                            Log.i("ZORO_MEMORY", "${memories.size} hafiza kaydi sunucuya yuklendi")
                        }
                    }
                }
            }
        )
    }

    // ==========================================
    // JSON METIN LISTESI
    // ==========================================

    private fun jsonStringList(
        json: JSONObject,
        key: String
    ): List<String> {

        val array = json.optJSONArray(key)
            ?: return emptyList()

        return (0 until array.length()).mapNotNull { index ->

            array.optString(
                index,
                ""
            ).takeIf { value ->

                value.isNotBlank() &&
                        value != "null"
            }
        }
    }

    // ==========================================
    // PYTHON GOREV AKSIYONLARI
    // ==========================================

    private fun handleBackendAction(
        action: String,
        taskText: String,
        dueAt: String,
        alarmId: String,
        repeatInterval: Long,
        cancelDueAts: List<String>,
        cancelAlarmIds: List<String>
    ) {

        when (action) {

            "add_task" -> {

                if (
                    taskText.isNotBlank() &&
                    isValidDueAt(dueAt)
                ) {

                    val id = alarmId.takeIf {
                        it.isNotBlank() &&
                                it != "null"
                    } ?: dueAt

                    scheduleReminder(
                        taskText = taskText,
                        dueAt = dueAt,
                        alarmId = id,
                        repeatInterval = repeatInterval
                    )
                }
            }

            "delete_task",
            "complete_task" -> {

                if (
                    alarmId.isNotBlank() &&
                    alarmId != "null"
                ) {

                    cancelReminderByAlarmId(
                        alarmId
                    )

                } else if (
                    isValidDueAt(dueAt)
                ) {

                    cancelReminderByDueAt(
                        dueAt
                    )

                } else if (
                    taskText.isNotBlank()
                ) {

                    cancelReminderByTaskText(
                        taskText
                    )
                }
            }

            "clear_tasks" -> {

                cancelAlarmIds.forEach {
                    cancelReminderByAlarmId(it)
                }

                cancelDueAts.forEach {
                    cancelReminderByDueAt(it)
                }

                cancelAllStoredReminders()
            }
        }
    }

    // ==========================================
    // ZORO 2.0: SUNUCUDAN GELEN HATIRLATMALAR
    // ==========================================

    override fun scheduleReminderFromServer(
        taskText: String,
        dueAt: String,
        alarmId: String,
        repeatInterval: Long
    ) {

        if (taskText.isBlank() || !isValidDueAt(dueAt)) {
            return
        }

        // Ayni alarm kimligi varsa once eskisini kaldir (saat degisikligi).
        if (alarmId.isNotBlank()) {
            cancelReminderByAlarmId(alarmId)
        }

        scheduleReminder(
            taskText = taskText,
            dueAt = dueAt,
            alarmId = alarmId.ifBlank { dueAt },
            repeatInterval = repeatInterval
        )
    }

    override fun cancelReminderFromServer(
        alarmId: String,
        dueAt: String,
        taskText: String
    ) {

        if (alarmId.isNotBlank() && alarmId != "null") {
            cancelReminderByAlarmId(alarmId)
        } else if (isValidDueAt(dueAt)) {
            cancelReminderByDueAt(dueAt)
        } else if (taskText.isNotBlank()) {
            cancelReminderByTaskText(taskText)
        }
    }

    // ==========================================
    // GUVENLI ALARM KURMA
    // ==========================================

    private fun setSafeAlarm(
        manager: AlarmManager,
        whenMillis: Long,
        pendingIntent: PendingIntent
    ): Boolean {

        return try {

            val allowed =
                Build.VERSION.SDK_INT <
                        Build.VERSION_CODES.S ||
                        manager.canScheduleExactAlarms()

            if (allowed) {

                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    whenMillis,
                    pendingIntent
                )

            } else {

                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    whenMillis,
                    pendingIntent
                )
            }

            true

        } catch (error: SecurityException) {

            Log.w(
                tag,
                "Kesin alarm izni yok, alternatif deneniyor.",
                error
            )

            try {

                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    whenMillis,
                    pendingIntent
                )

                true

            } catch (fallbackError: Exception) {

                Log.e(
                    tag,
                    "Alarm kurulamadı",
                    fallbackError
                )

                false
            }

        } catch (error: Exception) {

            Log.e(
                tag,
                "Alarm kurma hatası",
                error
            )

            false
        }
    }

    // ==========================================
    // HATIRLATMA OLUSTUR
    // ==========================================

    private fun scheduleReminder(
        taskText: String,
        dueAt: String,
        alarmId: String,
        repeatInterval: Long
    ) {

        try {

            val trigger =
                parseDueAtMillis(dueAt)
                    ?: return

            if (
                trigger <= System.currentTimeMillis()
            ) {

                Log.w(
                    tag,
                    "Geçmiş tarihe alarm kurulmadı: $dueAt"
                )

                return
            }

            val intent = Intent(
                this,
                ReminderReceiver::class.java
            ).apply {

                putExtra(
                    "task_text",
                    taskText
                )

                putExtra(
                    "alarm_id",
                    alarmId
                )

                putExtra(
                    "repeat_interval",
                    repeatInterval
                )
            }

            val pendingIntent =
                PendingIntent.getBroadcast(
                    this,
                    alarmId.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_IMMUTABLE
                )

            val manager =
                getSystemService(
                    AlarmManager::class.java
                )

            if (
                !setSafeAlarm(
                    manager,
                    trigger,
                    pendingIntent
                )
            ) {
                return
            }

            ReminderStore.saveReminder(
                context = this,
                alarmId = alarmId,
                taskText = taskText,
                dueAt = dueAt,
                repeatInterval = repeatInterval
            )

            Log.i(
                tag,
                "Alarm kaydedildi: $taskText / tekrar: $repeatInterval"
            )

        } catch (error: Exception) {

            Log.e(
                tag,
                "Hatırlatma kurulamadı",
                error
            )
        }
    }

    // ==========================================
    // TARIHE GORE ALARM IPTALI
    // ==========================================

    private fun cancelReminderByDueAt(
        dueAt: String
    ) {

        val item =
            ReminderStore.findByDueAt(
                this,
                dueAt
            )

        cancelReminderByAlarmId(
            item?.alarmId ?: dueAt
        )
    }

    // ==========================================
    // METNE GORE ALARM IPTALI
    // ==========================================

    private fun cancelReminderByTaskText(
        taskText: String
    ) {

        val item =
            ReminderStore.getAllReminders(this)
                .firstOrNull {
                    it.taskText == taskText
                }

        if (item != null) {

            cancelReminderByAlarmId(
                item.alarmId
            )
        }
    }

    // ==========================================
    // KIMLIGE GORE ALARM IPTALI
    // ==========================================

    private fun cancelReminderByAlarmId(
        alarmId: String
    ) {

        try {

            val intent = Intent(
                this,
                ReminderReceiver::class.java
            )

            val pendingIntent =
                PendingIntent.getBroadcast(
                    this,
                    alarmId.hashCode(),
                    intent,
                    PendingIntent.FLAG_NO_CREATE or
                            PendingIntent.FLAG_IMMUTABLE
                )

            if (pendingIntent != null) {

                getSystemService(
                    AlarmManager::class.java
                ).cancel(pendingIntent)

                pendingIntent.cancel()
            }

            ReminderStore.removeReminder(
                this,
                alarmId
            )

            Log.i(
                tag,
                "Alarm iptal edildi: $alarmId"
            )

        } catch (error: Exception) {

            Log.e(
                tag,
                "Alarm iptal hatası: $alarmId",
                error
            )
        }
    }

    // ==========================================
    // TUM ALARMLARI IPTAL ET
    // ==========================================

    private fun cancelAllStoredReminders() {

        ReminderStore.getAllReminders(this)
            .forEach { reminder ->

                cancelReminderByAlarmId(
                    reminder.alarmId
                )
            }

        ReminderStore.clearAll(this)
    }

    // ==========================================
    // PYTHON TARIHINI OKU
    // ==========================================

    private fun parseDueAtMillis(
        dueAt: String
    ): Long? {

        val normalized =
            dueAt.replace(
                Regex(
                    "\\.(\\d{3})\\d*(?=[+-]\\d{2}:\\d{2}$)"
                ),
                ".$1"
            )

        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )

        for (pattern in formats) {

            try {

                val formatter =
                    SimpleDateFormat(
                        pattern,
                        Locale.US
                    ).apply {
                        isLenient = false
                    }

                val result =
                    formatter.parse(normalized)

                if (result != null) {
                    return result.time
                }

            } catch (_: Exception) {
            }
        }

        Log.e(
            tag,
            "Tarih çözümlenemedi: $dueAt"
        )

        return null
    }

    private fun isValidDueAt(
        dueAt: String
    ): Boolean {

        return dueAt.isNotBlank() &&
                dueAt != "null"
    }

    // ==========================================
    // UYGULAMA KAPANIRKEN
    // ==========================================

    override fun onDestroy() {

        if (commandReceiverRegistered) {

            unregisterReceiver(
                voiceCommandReceiver
            )

            commandReceiverRegistered = false
        }

        if (::textToSpeech.isInitialized) {

            textToSpeech.stop()
            textToSpeech.shutdown()
        }

        httpClient.dispatcher.cancelAll()

        super.onDestroy()
    }
}

// ==========================================
// GOREV TARIHI GORUNUMU
// ==========================================

private fun formatTaskDate(
    dueAt: String,
    repeatInterval: Long
): String {

    if (
        dueAt.isBlank() ||
        dueAt == "null"
    ) {
        return "Saat belirtilmemiş"
    }

    return try {

        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )

        var parsedDate: java.util.Date? = null

        for (pattern in formats) {

            try {

                val formatter =
                    SimpleDateFormat(
                        pattern,
                        Locale.US
                    )

                parsedDate = formatter.parse(
                    dueAt
                )

                if (parsedDate != null) {
                    break
                }

            } catch (_: Exception) {
            }
        }

        val date = parsedDate

        if (date == null) {

            "Tarih okunamadı"

        } else if (
            repeatInterval == 86400000L
        ) {

            "Her gün " +
                    SimpleDateFormat(
                        "HH:mm",
                        Locale.forLanguageTag("tr-TR")
                    ).format(date)

        } else {

            SimpleDateFormat(
                "dd.MM.yyyy  HH:mm",
                Locale.forLanguageTag("tr-TR")
            ).format(date)
        }

    } catch (_: Exception) {

        dueAt
    }
}

// ==========================================
// ZORO ARAYUZU
// ==========================================

@Composable
fun ZoroApp(
    voiceStatus: String,
    tasksScreenOpen: Boolean,
    tasksLoading: Boolean,
    tasksError: String,
    tasksMessage: String,
    addingTask: Boolean,
    taskActionInProgress: Boolean,
    taskSavedCounter: Int,
    taskItems: List<ZoroTaskItem>,
    startVoiceInput: () -> Unit,
    startVoiceListening: () -> Unit,
    stopVoiceListening: () -> Unit,
    openTasks: () -> Unit,
    openCalendar: () -> Unit,
    refreshTasks: () -> Unit,
    closeTasks: () -> Unit,
    addTask: (String) -> Unit,
    startTaskVoice: () -> Unit,
    editTask: (ZoroTaskItem, String, String) -> Unit,    performTaskAction: (
        ZoroTaskItem,
        ZoroTaskOperation
    ) -> Unit,
    lastReply: String = "",
    askText: (String) -> Unit = {}
) {

    val red = Color(0xFFB71C1C)
    val skyBlue = Color(0xFF27A7E5)
    val lightBlue = Color(0xFFE5F5FF)
    val darkBlue = Color(0xFF135B8D)
    val pageBackground = Color(0xFFF4F8FC)

    var taskInput by remember {
        mutableStateOf("")
    }

    var pendingAction by remember {
        mutableStateOf<
                Pair<ZoroTaskItem, ZoroTaskOperation>?
                >(null)
    }

    var editingTask by remember {
        mutableStateOf<ZoroTaskItem?>(null)
    }
    var editSavePending by remember { mutableStateOf(false) }

    var editText by remember {
        mutableStateOf("")
    }

    var editDate by remember {
        mutableStateOf("")
    }

    var editTime by remember {
        mutableStateOf("")
    }

    var editError by remember {
        mutableStateOf("")
    }
    LaunchedEffect(taskSavedCounter) {

        if (taskSavedCounter > 0) {
            taskInput = ""
        }
    }

    BackHandler(enabled = tasksScreenOpen) {

        if (!taskActionInProgress) {
            closeTasks()
        }
    }

    MaterialTheme {

        // ======================================
        // ONAY PENCERESI
        // ======================================

        LaunchedEffect(editSavePending, taskActionInProgress, tasksMessage) {
            if (editSavePending && !taskActionInProgress) {
                if (
                    tasksMessage == "Görev güncellendi." ||
                    tasksMessage == "Görev kaydedildi ancak alarm kontrol edilmeli."
                ) {
                    editingTask = null
                } else {
                    editError = tasksMessage.ifBlank {
                        "Kayıt başarısız oldu. Tekrar deneyin."
                    }
                }
                editSavePending = false
            }
        }
        editingTask?.let { task ->
            AlertDialog(
                onDismissRequest = {
                    if (!taskActionInProgress) editingTask = null
                },
                title = {
                    Text("✏️ Görevi Düzenle")
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editText,
                            onValueChange = {
                                editText = it
                                editError = ""
                            },
                            label = { Text("Görev adı") },
                            singleLine = false
                        )

                        OutlinedTextField(
                            value = editDate,
                            onValueChange = {
                                editDate = it
                                editError = ""
                            },
                            label = { Text("Tarih (YYYY-AA-GG)") },
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = editTime,
                            onValueChange = {
                                editTime = it
                                editError = ""
                            },
                            label = { Text("Saat (SS:DD)") },
                            singleLine = true
                        )

                        if (editError.isNotBlank()) {
                            Text(
                                text = editError,
                                color = red
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !taskActionInProgress &&
                            editText.isNotBlank(),
                        onClick = {
                            val due = "${editDate.trim()}T${editTime.trim()}"

                            val valid = try {
                                java.text.SimpleDateFormat(
                                    "yyyy-MM-dd'T'HH:mm",
                                    java.util.Locale.US
                                ).apply {
                                    isLenient = false
                                }.parse(due)
                                true
                            } catch (_: Exception) {
                                false
                            }

                            if (!valid) {
                                editError =
                                    "Tarih ve saati kontrol edin. Örnek: 2026-10-20 ve 14:30"
                            } else {
                                editSavePending = true
                                editTask(task, editText.trim(), due)
                            }
                        }
                    ) {
                        Text("Kaydet")
                    }
                },
                dismissButton = {
                    Button(
                        enabled = !taskActionInProgress,
                        onClick = { editingTask = null }
                    ) {
                        Text("Vazgeç")
                    }
                }
            )
        }
        pendingAction?.let { selection ->

            val selectedTask = selection.first
            val selectedOperation = selection.second

            val isDelete =
                selectedOperation ==
                        ZoroTaskOperation.DELETE

            AlertDialog(
                onDismissRequest = {
                    if (!taskActionInProgress) {
                        pendingAction = null
                    }
                },

                title = {

                    Text(
                        text = if (isDelete) {
                            "Görevi Sil"
                        } else {
                            "Görevi Tamamla"
                        },
                        color = red,
                        fontWeight = FontWeight.Bold
                    )
                },

                text = {

                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(12.dp)
                    ) {

                        Text(
                            text =
                                "${selectedTask.number}. " +
                                        selectedTask.text,
                            fontWeight = FontWeight.Bold,
                            color = darkBlue
                        )

                        Text(
                            text = if (isDelete) {

                                "Bu görevi kalıcı olarak " +
                                        "silmek istiyor musun? " +
                                        "Varsa bağlı hatırlatması " +
                                        "da iptal edilecek."

                            } else {

                                "Bu görevi tamamlandı " +
                                        "olarak işaretlemek " +
                                        "istiyor musun? " +
                                        "Görev listede kalacak, " +
                                        "varsa hatırlatması " +
                                        "iptal edilecek."
                            },
                            color = Color.DarkGray
                        )
                    }
                },

                confirmButton = {

                    TextButton(
                        onClick = {

                            pendingAction = null

                            performTaskAction(
                                selectedTask,
                                selectedOperation
                            )
                        },
                        enabled = !taskActionInProgress
                    ) {

                        Text(
                            text = if (isDelete) {
                                "Evet, Sil"
                            } else {
                                "Evet, Tamamla"
                            },
                            color = if (isDelete) {
                                red
                            } else {
                                Color(0xFF16803C)
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                },

                dismissButton = {

                    TextButton(
                        onClick = {
                            pendingAction = null
                        }
                    ) {

                        Text(
                            text = "Vazgeç",
                            color = darkBlue
                        )
                    }
                },

                containerColor = Color.White
            )
        }

        if (tasksScreenOpen) {

            // ==================================
            // GOREVLER SAYFASI
            // ==================================

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(pageBackground)
                    .verticalScroll(
                        rememberScrollState()
                    )
                    .padding(16.dp),

                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    text = "GÖREVLERİM",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = red
                )

                Text(
                    text = "Zoro'nun kayıtlı görevleri",
                    fontSize = 14.sp,
                    color = darkBlue
                )

                Button(
                    onClick = closeTasks,
                    enabled = !taskActionInProgress,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = darkBlue
                    )
                ) {

                    Text("← Ana Ekrana Dön")
                }

                // ==================================
                // YENI GOREV EKLEME
                // ==================================

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    )
                ) {

                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp)
                    ) {

                        Text(
                            text = "Yeni Görev Ekle",
                            color = red,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = taskInput,
                            onValueChange = {
                                taskInput = it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text("Görevini yaz")
                            },
                            placeholder = {
                                Text(
                                    "Yarın saat 10.00'da " +
                                            "toplantımı hatırlat"
                                )
                            },
                            minLines = 2,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done
                            )
                        )

                        Button(
                            onClick = {
                                addTask(taskInput)
                            },
                            enabled =
                                !addingTask &&
                                        !taskActionInProgress,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = red
                            )
                        ) {

                            Text(
                                text = if (addingTask) {
                                    "Kaydediliyor..."
                                } else {
                                    "+ Görev Ekle"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = startTaskVoice,
                            enabled =
                                !addingTask &&
                                        !taskActionInProgress,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = lightBlue,
                                contentColor = darkBlue
                            )
                        ) {

                            Text(
                                text = "🎙 Sesle Ekle",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (tasksMessage.isNotBlank()) {

                            Text(
                                text = tasksMessage,
                                color = darkBlue,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // ==================================
                // GOREVLERI YENILE
                // ==================================

                Button(
                    onClick = refreshTasks,
                    enabled = !taskActionInProgress,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = skyBlue,
                        contentColor = Color.White
                    )
                ) {

                    Text("↻ Görevleri Yenile")
                }

                if (tasksLoading) {

                    Text(
                        text = "Görevler yükleniyor...",
                        color = darkBlue
                    )

                } else if (tasksError.isNotBlank()) {

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFE7E7)
                        )
                    ) {

                        Text(
                            text = tasksError,
                            modifier = Modifier.padding(16.dp),
                            color = red
                        )
                    }

                } else if (taskItems.isEmpty()) {

                    Text(
                        text = "Henüz kayıtlı görev yok.",
                        color = darkBlue
                    )

                } else {

                    Text(
                        text = "Toplam ${taskItems.size} görev",
                        color = darkBlue,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // ==================================
                    // KAYITLI GOREV KARTLARI
                    // ==================================

                    taskItems.forEach { task ->

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White
                            )
                        ) {

                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(10.dp)
                            ) {

                                Text(
                                    text =
                                        "${task.number}. ${task.text}",
                                    color = darkBlue,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    text = formatTaskDate(
                                        task.dueAt,
                                        task.repeatInterval
                                    ),
                                    color = Color.DarkGray,
                                    fontSize = 14.sp
                                )

                                Text(
                                    text = if (task.done) {
                                        "✓ Tamamlandı"
                                    } else {
                                        "○ Bekliyor"
                                    },
                                    color = if (task.done) {
                                        Color(0xFF16803C)
                                    } else {
                                        red
                                    },
                                    fontWeight = FontWeight.Bold
                                )

                                if (
                                    task.repeatInterval ==
                                    86400000L
                                ) {

                                    Text(
                                        text = "↻ Her gün tekrarlanır",
                                        color = skyBlue,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }

                                // ==========================
                                // TAMAMLA VE SIL DUGMELERI
                                // ==========================

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        if (!task.done) {
                                            Button(
                                                onClick = {
                                                    pendingAction = task to ZoroTaskOperation.COMPLETE
                                                },
                                                enabled = !taskActionInProgress &&
                                                        !addingTask &&
                                                        !tasksLoading &&
                                                        task.id.isNotBlank(),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(52.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF16803C),
                                                    contentColor = Color.White
                                                )
                                            ) {
                                                Text(
                                                    text = "✓ Tamamla",
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = {
                                                editingTask = task
                                                editText = task.text
                                                editDate = task.dueAt.take(10)
                                                editTime = if (task.dueAt.length >= 16) {
                                                    task.dueAt.substring(11, 16)
                                                } else {
                                                    ""
                                                }
                                                editError = ""
                                            },
                                            enabled = !taskActionInProgress &&
                                                    !addingTask &&
                                                    !tasksLoading &&
                                                    task.id.isNotBlank(),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(52.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = darkBlue,
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Text(
                                                text = "✏ Düzenle",
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            pendingAction = task to ZoroTaskOperation.DELETE
                                        },
                                        enabled = !taskActionInProgress &&
                                                !addingTask &&
                                                !tasksLoading &&
                                                task.id.isNotBlank(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = red,
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Text(
                                            text = "Sil",
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

        } else {

            // ==================================
            // ANA EKRAN
            // ==================================

            val statusTitle =
                when (voiceStatus) {

                    ZoroVoiceService.STATUS_LISTENING ->
                        "Zoro dinliyor"

                    ZoroVoiceService.STATUS_WAITING_COMMAND ->
                        "Komut bekleniyor"

                    ZoroVoiceService.STATUS_STARTING ->
                        "Mikrofon hazırlanıyor"

                    else ->
                        "Dinleme kapalı"
                }

            val statusDescription =
                when (voiceStatus) {

                    ZoroVoiceService.STATUS_LISTENING ->
                        "Zoro diyerek seslenebilirsin."

                    ZoroVoiceService.STATUS_WAITING_COMMAND ->
                        "Şimdi komutunu söyleyebilirsin."

                    ZoroVoiceService.STATUS_STARTING ->
                        "Ses tanıma oturumu hazırlanıyor."

                    else ->
                        "Sesli dinlemeyi başlatarak " +
                                "Zoro'yu etkinleştir."
                }

            val statusColor =
                when (voiceStatus) {

                    ZoroVoiceService.STATUS_LISTENING ->
                        Color(0xFF16803C)

                    ZoroVoiceService.STATUS_WAITING_COMMAND ->
                        Color(0xFF1565C0)

                    ZoroVoiceService.STATUS_STARTING ->
                        Color(0xFFB26B00)

                    else ->
                        Color(0xFF666666)
                }

            val statusSymbol =
                when (voiceStatus) {

                    ZoroVoiceService.STATUS_LISTENING ->
                        "●"

                    ZoroVoiceService.STATUS_WAITING_COMMAND ->
                        "●"

                    ZoroVoiceService.STATUS_STARTING ->
                        "◌"

                    else ->
                        "○"
                }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(pageBackground)
                    .verticalScroll(
                        rememberScrollState()
                    )
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = 28.dp
                    ),

                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    text = "ZORO",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = red
                )

                Text(
                    text = "Kişisel Yapay Zekâ Asistanı",
                    fontSize = 13.sp,
                    color = Color.DarkGray
                )

                // ==================================
                // SES DURUM KARTI
                // ==================================

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = lightBlue
                    )
                ) {

                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {

                        Text(
                            text =
                                "$statusSymbol  $statusTitle",
                            color = statusColor,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(
                            modifier = Modifier.height(4.dp)
                        )

                        Text(
                            text = statusDescription,
                            color = darkBlue,
                            fontSize = 13.sp
                        )
                    }
                }

                // ==================================
                // ZORO SESLI ASISTAN
                // ==================================

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = red
                    )
                ) {

                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {

                        Text(
                            text = "Merhaba Zafer",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text =
                                "Bugün senin için ne yapabilirim?",
                            color = Color.White,
                            fontSize = 14.sp
                        )

                        Button(
                            onClick = startVoiceInput,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = red
                            )
                        ) {

                            Text(
                                text = "🎙 Zoro'ya Konuş",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = startVoiceListening,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = lightBlue,
                                contentColor = darkBlue
                            )
                        ) {

                            Text(
                                text =
                                    "🎧 Sesli Dinlemeyi Başlat",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = stopVoiceListening,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor =
                                    Color(0xFF751515),
                                contentColor = Color.White
                            )
                        ) {

                            Text(
                                text =
                                    "⏹ Sesli Dinlemeyi Durdur",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // ZORO_V2_TEXT_COMMAND
                        // Sessiz ortamda yazarak komut ver.
                        var commandInput by remember {
                            mutableStateOf("")
                        }

                        OutlinedTextField(
                            value = commandInput,
                            onValueChange = { commandInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = "Zoro'ya yaz",
                                    color = Color.White.copy(alpha = 0.75f)
                                )
                            },
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    val text = commandInput.trim()
                                    if (text.isNotEmpty()) {
                                        askText(text)
                                        commandInput = ""
                                    }
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.6f),
                                cursorColor = Color.White
                            )
                        )

                        if (lastReply.isNotBlank()) {

                            Text(
                                text = lastReply,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // ==================================
                // CALISMA ALANLARI
                // ==================================

                Text(
                    text = "Çalışma Alanların",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF162B3A)
                )

                val areas = listOf(
                    "👤 Kişisel",
                    "🚁 İHAKUT",
                    "📋 Görevler",
                    "📅 Takvim",
                    "📁 Belgeler",
                    "📓 Günlük"
                )

                areas.chunked(2).forEach { pair ->

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(10.dp)
                    ) {

                        pair.forEach { area ->

                            val isTasks =
                                area.contains("Görevler")

                            val isCalendar =
                                area.contains("Takvim")

                            Card(
                                onClick = {
                                    if (isTasks) {
                                        openTasks()
                                    } else if (isCalendar) {
                                        openCalendar()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(94.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (
                                        isTasks || isCalendar
                                    ) {
                                        lightBlue
                                    } else {
                                        Color.White
                                    }
                                )
                            ) {

                                Column(
                                    modifier =
                                        Modifier.padding(12.dp)
                                ) {

                                    Text(
                                        text = area,
                                        color = darkBlue,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Spacer(
                                        modifier =
                                            Modifier.height(5.dp)
                                    )

                                    Text(
                                        text = when {
                                            isTasks ->
                                                "Görevleri görüntüle"
                                            isCalendar ->
                                                "Takvimi aç"
                                            else ->
                                                "Yakında"
                                        },
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "ZORO • Kişisel Asistan",
                    color = skyBlue,
                    fontSize = 11.sp
                )
            }
        }
    }
}
