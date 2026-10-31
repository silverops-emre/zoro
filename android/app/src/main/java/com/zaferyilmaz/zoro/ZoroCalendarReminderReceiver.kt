
package com.zaferyilmaz.zoro

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ZoroCalendarReminderReceiver : BroadcastReceiver() {

    companion object {

        const val EXTRA_EVENT_ID =
            "zoro_calendar_event_id"

        const val EXTRA_EVENT_TITLE =
            "zoro_calendar_event_title"

        const val EXTRA_EVENT_TIME =
            "zoro_calendar_event_time"

        const val CHANNEL_ID =
            "zoro_calendar_reminders"

        private const val TAG =
            "ZORO_CALENDAR_ALARM"
    }

    // ==========================================
    // ANDROID ALARM ALICISI
    // ==========================================

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        val eventId = intent.getStringExtra(
            EXTRA_EVENT_ID
        ).orEmpty()

        val title = intent.getStringExtra(
            EXTRA_EVENT_TITLE
        )?.trim().orEmpty()

        val eventTime = intent.getStringExtra(
            EXTRA_EVENT_TIME
        )?.trim().orEmpty()

        if (
            eventId.isBlank() ||
            title.isBlank()
        ) {

            Log.w(
                TAG,
                "Etkinlik bilgileri eksik."
            )

            return
        }

        // ======================================
        // RANDEVUNUN HATIRLATMA SURESINI OKU
        // ======================================

        val minutes =
            ZoroCalendarAlarmScheduler
                .getReminderMinutes(
                    context,
                    eventId
                )

        val reminderTimeText =
            when (minutes) {

                60 -> "1 saat"

                120 -> "2 saat"

                else -> "$minutes dakika"
            }

        // ======================================
        // SESLI VE YAZILI UYARI METNI
        // ======================================

        val message =
            if (eventTime.isNotBlank()) {

                "Zafer Bey, saat $eventTime için " +
                        "$title randevunuz var. " +
                        "Randevunuza $reminderTimeText kaldı."

            } else {

                "Zafer Bey, $title randevunuza " +
                        "$reminderTimeText kaldı."
            }

        Log.i(
            TAG,
            "Takvim alarmi: $title / $minutes dakika"
        )

        showCalendarNotification(
            context = context,
            title = title,
            message = message,
            eventId = eventId
        )

        speakCalendarReminder(
            context,
            message
        )
    }

    // ==========================================
    // TELEFON BILDIRIMI
    // ==========================================

    private fun showCalendarNotification(
        context: Context,
        title: String,
        message: String,
        eventId: String
    ) {

        try {

            if (
                Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                Log.w(
                    TAG,
                    "Bildirim izni bulunamadi."
                )

                return
            }

            val manager =
                context.getSystemService(
                    NotificationManager::class.java
                )

            if (
                Build.VERSION.SDK_INT >= 26
            ) {

                val channel =
                    NotificationChannel(
                        CHANNEL_ID,
                        "Zoro Takvim Hatırlatmaları",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {

                        description =
                            "Zoro takvim randevuları için " +
                                    "sesli ve yazılı bildirimler"

                        enableVibration(true)
                    }

                manager.createNotificationChannel(
                    channel
                )
            }

            val builder =
                if (
                    Build.VERSION.SDK_INT >= 26
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
                    .setSmallIcon(
                        android.R.drawable.ic_popup_reminder
                    )
                    .setContentTitle(
                        "Zoro Takvim: $title"
                    )
                    .setContentText(message)
                    .setStyle(
                        Notification.BigTextStyle()
                            .bigText(message)
                    )
                    .setAutoCancel(true)
                    .setWhen(
                        System.currentTimeMillis()
                    )
                    .build()

            val notificationId =
                ("calendar_$eventId").hashCode()

            manager.notify(
                notificationId,
                notification
            )

            Log.i(
                TAG,
                "Takvim bildirimi gonderildi: $title"
            )

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Takvim bildirimi hatasi",
                error
            )
        }
    }

    // ==========================================
    // SESLI RANDEVU HATIRLATMASI
    // ==========================================

    private fun speakCalendarReminder(
        context: Context,
        message: String
    ) {

        val pendingResult = goAsync()

        val handler =
            Handler(Looper.getMainLooper())

        val completed =
            AtomicBoolean(false)

        var engine: TextToSpeech? = null

        // ======================================
        // SES MOTORUNU GUVENLI KAPAT
        // ======================================

        fun finishSafely() {

            if (
                completed.compareAndSet(
                    false,
                    true
                )
            ) {

                try {
                    engine?.stop()
                    engine?.shutdown()
                } catch (_: Exception) {
                }

                pendingResult.finish()
            }
        }

        // Android BroadcastReceiver zaman siniri.
        handler.postDelayed(
            {
                finishSafely()
            },
            8000L
        )

        try {

            engine = TextToSpeech(
                context.applicationContext
            ) { status ->

                handler.post {

                    if (completed.get()) {
                        return@post
                    }

                    if (
                        status != TextToSpeech.SUCCESS
                    ) {

                        Log.w(
                            TAG,
                            "Ses motoru baslatilamadi."
                        )

                        finishSafely()
                        return@post
                    }

                    val tts = engine

                    if (tts == null) {

                        finishSafely()
                        return@post
                    }

                    // ==================================
                    // TURKCE SES AYARLARI
                    // ==================================

                    tts.language =
                        Locale.forLanguageTag(
                            "tr-TR"
                        )

                    tts.setPitch(0.85f)
                    tts.setSpeechRate(0.95f)

                    // ==================================
                    // SESLENDIRME TAMAMLANMA TAKIBI
                    // ==================================

                    tts.setOnUtteranceProgressListener(
                        object :
                            UtteranceProgressListener() {

                            override fun onStart(
                                utteranceId: String
                            ) {
                            }

                            override fun onDone(
                                utteranceId: String
                            ) {

                                handler.post {
                                    finishSafely()
                                }
                            }

                            @Deprecated(
                                "Android TTS"
                            )
                            override fun onError(
                                utteranceId: String
                            ) {

                                handler.post {
                                    finishSafely()
                                }
                            }

                            override fun onError(
                                utteranceId: String,
                                errorCode: Int
                            ) {

                                handler.post {
                                    finishSafely()
                                }
                            }
                        }
                    )

                    // ==================================
                    // ZORO SESLI UYARI
                    // ==================================

                    val result =
                        tts.speak(
                            message,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "ZORO_CALENDAR_REMINDER"
                        )

                    if (
                        result == TextToSpeech.ERROR
                    ) {

                        Log.w(
                            TAG,
                            "Seslendirme baslatilamadi."
                        )

                        finishSafely()
                    }
                }
            }

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Takvim seslendirme hatasi",
                error
            )

            finishSafely()
        }
    }
}
