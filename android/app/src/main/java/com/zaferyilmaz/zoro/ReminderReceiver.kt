
package com.zaferyilmaz.zoro

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ZORO_REMINDER"
        private const val CHANNEL_ID = "zoro_reminders"
        private const val DAILY_INTERVAL = 86400000L
        private const val SPEECH_TIMEOUT_MS = 8000L
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        val alarmId = intent.getStringExtra("alarm_id")

        if (alarmId.isNullOrBlank()) {
            Log.w(TAG, "Alarm kimliği bulunamadı.")
            return
        }

        val reminder =
            ReminderStore.getAllReminders(context)
                .firstOrNull {
                    it.alarmId == alarmId
                }

        if (reminder == null) {
            Log.w(
                TAG,
                "Silinmiş veya kayıtlı olmayan alarm: $alarmId"
            )
            return
        }

        Log.i(
            TAG,
            "Hatırlatma zamanı geldi: ${reminder.taskText}"
        )

        // Mevcut bildirim sistemini koru.
        showNotification(
            context,
            reminder.taskText
        )

        // Sesli hatırlatmayı başlat.
        // goAsync, TTS başlatılırken receiver'ın
        // hemen sonlandırılmasını önlemeye yardımcı olur.
        val pendingResult = goAsync()

        try {
            speakReminder(
                context.applicationContext,
                reminder.taskText,
                pendingResult
            )
        } catch (error: Exception) {
            Log.e(TAG, "Ses başlatma hatası", error)
            pendingResult.finish()
        }

        // Mevcut tekrar sistemi.
        if (reminder.repeatInterval > 0L) {
            scheduleNextReminder(
                context,
                reminder
            )
        } else {
            ReminderStore.removeReminder(
                context,
                alarmId
            )

            Log.i(
                TAG,
                "Tek seferlik hatırlatma tamamlandı."
            )
        }
    }

    // ==========================================
    // SESLI HATIRLATMA
    // ==========================================

    private fun speakReminder(
        context: Context,
        taskText: String,
        pendingResult: PendingResult
    ) {
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val speechId =
            "zoro_reminder_${System.currentTimeMillis()}"

        var tts: TextToSpeech? = null

        fun finishSpeech() {
            if (!finished.compareAndSet(false, true)) {
                return
            }

            handler.post {
                try {
                    tts?.shutdown()
                } catch (error: Exception) {
                    Log.w(TAG, "TTS kapatma hatası", error)
                } finally {
                    pendingResult.finish()
                }
            }
        }

        // BroadcastReceiver'ın zaman sınırını aşma.
        handler.postDelayed(
            {
                Log.w(
                    TAG,
                    "Sesli hatırlatma zaman sınırına ulaştı."
                )
                finishSpeech()
            },
            SPEECH_TIMEOUT_MS
        )


        val speechText =
            "Zafer Bey, hatırlatmanız var. " +
                    taskText.replace(
                        Regex(
                            """(?<!\d)21\s*[.:]\s*35(?:['’]\s*(?:te|de))?(?!\d)""",
                            RegexOption.IGNORE_CASE
                        ),
                        "yirmi bir otuz beşte"
                    )


        tts = TextToSpeech(
            context,
            { status ->
                if (finished.get()) {
                    return@TextToSpeech
                }

                if (status != TextToSpeech.SUCCESS) {
                    Log.e(
                        TAG,
                        "Android ses motoru başlatılamadı: $status"
                    )
                    finishSpeech()
                    return@TextToSpeech
                }

                try {
                    val engine = tts

                    if (engine == null) {
                        finishSpeech()
                        return@TextToSpeech
                    }

                    val languageResult =
                        engine.setLanguage(
                            Locale.forLanguageTag("tr-TR")
                        )

                    if (
                        languageResult ==
                        TextToSpeech.LANG_MISSING_DATA ||
                        languageResult ==
                        TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        Log.e(
                            TAG,
                            "Türkçe ses verisi mevcut değil."
                        )
                        finishSpeech()
                        return@TextToSpeech
                    }

                    engine.setSpeechRate(0.95f)
                    engine.setPitch(1.0f)

                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build()
                    )

                    engine.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {

                            override fun onStart(
                                utteranceId: String
                            ) {
                                Log.i(
                                    TAG,
                                    "Zoro konuşmaya başladı."
                                )
                            }

                            override fun onDone(
                                utteranceId: String
                            ) {
                                Log.i(
                                    TAG,
                                    "Zoro konuşmayı tamamladı."
                                )
                                finishSpeech()
                            }

                            @Deprecated(
                                "Android TTS eski hata bildirimi"
                            )
                            override fun onError(
                                utteranceId: String
                            ) {
                                Log.e(
                                    TAG,
                                    "Zoro seslendirme hatası."
                                )
                                finishSpeech()
                            }

                            override fun onError(
                                utteranceId: String,
                                errorCode: Int
                            ) {
                                Log.e(
                                    TAG,
                                    "TTS hata kodu: $errorCode"
                                )
                                finishSpeech()
                            }
                        }
                    )

                    val result = engine.speak(
                        speechText,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        speechId
                    )

                    if (result == TextToSpeech.ERROR) {
                        Log.e(
                            TAG,
                            "Seslendirme isteği başarısız."
                        )
                        finishSpeech()
                    }

                } catch (error: Exception) {
                    Log.e(
                        TAG,
                        "Sesli hatırlatma hatası",
                        error
                    )
                    finishSpeech()
                }
            }
        )
    }

    // ==========================================
    // BILDIRIM GONDER
    // ==========================================

    private fun showNotification(
        context: Context,
        taskText: String
    ) {
        try {
            if (
                Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Bildirim izni verilmemiş.")
                return
            }

            val builder =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
                ) {
                    Notification.Builder(
                        context,
                        CHANNEL_ID
                    )
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context)
                }

            val notification =
                builder
                    .setContentTitle(
                        "Zoro Hatırlatma"
                    )
                    .setContentText(
                        taskText
                    )
                    .setStyle(
                        Notification.BigTextStyle()
                            .bigText(taskText)
                    )
                    .setSmallIcon(
                        android.R.drawable.ic_dialog_info
                    )
                    .setAutoCancel(true)
                    .build()

            val manager =
                context.getSystemService(
                    NotificationManager::class.java
                )

            manager.notify(
                System.currentTimeMillis().toInt(),
                notification
            )

            Log.i(
                TAG,
                "Bildirim gönderildi: $taskText"
            )

        } catch (error: Exception) {
            Log.e(
                TAG,
                "Bildirim gönderme hatası",
                error
            )
        }
    }

    // ==========================================
    // TEKRARLAYAN HATIRLATMAYI YENIDEN KUR
    // ==========================================

    private fun scheduleNextReminder(
        context: Context,
        reminder: ReminderStore.ReminderData
    ) {
        try {
            val originalTime =
                parseDueAt(reminder.dueAt)

            if (originalTime == null) {
                Log.e(
                    TAG,
                    "Tekrar tarihi okunamadı: ${reminder.dueAt}"
                )
                return
            }

            val nextTime =
                calculateNextTime(
                    originalTime,
                    reminder.repeatInterval
                )

            if (nextTime == null) {
                Log.e(
                    TAG,
                    "Sonraki hatırlatma zamanı hesaplanamadı."
                )
                return
            }

            val nextIntent =
                Intent(
                    context,
                    ReminderReceiver::class.java
                ).apply {
                    putExtra(
                        "task_text",
                        reminder.taskText
                    )

                    putExtra(
                        "alarm_id",
                        reminder.alarmId
                    )

                    putExtra(
                        "repeat_interval",
                        reminder.repeatInterval
                    )
                }

            val pendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    reminder.alarmId.hashCode(),
                    nextIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_IMMUTABLE
                )

            val alarmManager =
                context.getSystemService(
                    AlarmManager::class.java
                )

            val success =
                setSafeAlarm(
                    alarmManager,
                    nextTime,
                    pendingIntent
                )

            if (!success) {
                Log.e(
                    TAG,
                    "Tekrarlanan alarm yeniden kurulamadı."
                )
                return
            }

            val nextDueAt =
                formatDueAt(nextTime)

            ReminderStore.saveReminder(
                context = context,
                alarmId = reminder.alarmId,
                taskText = reminder.taskText,
                dueAt = nextDueAt,
                repeatInterval = reminder.repeatInterval
            )

            Log.i(
                TAG,
                "Sonraki hatırlatma kuruldu: $nextDueAt"
            )

        } catch (error: Exception) {
            Log.e(
                TAG,
                "Tekrar kurma hatası",
                error
            )
        }
    }

    // ==========================================
    // SONRAKI HATIRLATMA ZAMANINI HESAPLA
    // ==========================================

    private fun calculateNextTime(
        originalTime: Long,
        repeatInterval: Long
    ): Long? {

        if (repeatInterval <= 0L) {
            return null
        }

        val now = System.currentTimeMillis()

        if (repeatInterval == DAILY_INTERVAL) {
            val calendar =
                Calendar.getInstance(
                    TimeZone.getTimeZone(
                        "Europe/Istanbul"
                    )
                )

            calendar.timeInMillis = originalTime

            do {
                calendar.add(
                    Calendar.DAY_OF_MONTH,
                    1
                )
            } while (
                calendar.timeInMillis <= now
            )

            return calendar.timeInMillis
        }

        if (originalTime > now) {
            return originalTime
        }

        val elapsed = now - originalTime
        val steps = elapsed / repeatInterval + 1L

        if (
            steps >
            (Long.MAX_VALUE - originalTime) /
            repeatInterval
        ) {
            return null
        }

        return originalTime +
                steps * repeatInterval
    }

    // ==========================================
    // GUVENLI ALARM KUR
    // ==========================================

    private fun setSafeAlarm(
        alarmManager: AlarmManager,
        triggerTime: Long,
        pendingIntent: PendingIntent
    ): Boolean {

        return try {

            val exactAllowed =
                Build.VERSION.SDK_INT <
                        Build.VERSION_CODES.S ||
                        alarmManager.canScheduleExactAlarms()

            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }

            true

        } catch (error: SecurityException) {
            Log.w(
                TAG,
                "Kesin alarm izni yok; normal alarm deneniyor.",
                error
            )

            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                true

            } catch (fallbackError: Exception) {
                Log.e(
                    TAG,
                    "Alarm kurulamadı.",
                    fallbackError
                )
                false
            }

        } catch (error: Exception) {
            Log.e(
                TAG,
                "Alarm planlama hatası",
                error
            )
            false
        }
    }

    // ==========================================
    // PYTHON TARIHINI OKU
    // ==========================================

    private fun parseDueAt(
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
                // Diğer tarih biçimini dene.
            }
        }

        return null
    }

    // ==========================================
    // SONRAKI TARIHI KAYDET
    // ==========================================

    private fun formatDueAt(
        timestamp: Long
    ): String {

        val formatter =
            SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                Locale.US
            ).apply {
                timeZone =
                    TimeZone.getTimeZone(
                        "Europe/Istanbul"
                    )
            }

        return formatter.format(
            Date(timestamp)
        )
    }
}
