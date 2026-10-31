
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

class ZoroMorningBriefingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ZORO_MORNING"
        private const val CHANNEL_ID = "zoro_morning_briefing"
        private const val NOTIFICATION_ID = 100010
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        val pendingResult = goAsync()
        android.util.Log.i("ZORO_MORNING_TEST", "Sabah brifingi tetiklendi")
        // Ertesi gunun saat 10.00 brifingini planla.
        ZoroMorningBriefingScheduler.scheduleNext(context)
        val appContext = context.applicationContext

        Thread {
            try {
                val message = buildMorningBriefing(appContext)

                showNotification(
                    appContext,
                    message
                )

                speakBriefing(
                    appContext,
                    message
                ) {
                    pendingResult.finish()
                }

            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Sabah brifingi hatasi",
                    error
                )
                pendingResult.finish()
            }
        }.start()
    }

    // ==========================================
    // GUNLUK PROGRAMI HAZIRLA
    // ==========================================

    private fun buildMorningBriefing(
        context: Context
    ): String {

        val zone = TimeZone.getTimeZone(
            "Europe/Istanbul"
        )

        val turkish = Locale.forLanguageTag(
            "tr-TR"
        )

        val start = Calendar.getInstance(zone).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val end = Calendar.getInstance(zone).apply {
            timeInMillis = start.timeInMillis
            add(Calendar.DAY_OF_YEAR, 1)
        }

        val manager = CalendarManager(context)

        // Samsung/Google izni olmasa da
        // Zoro'nun kendi takvimi okunabilir.
        val events = manager.getAllEvents(
            start.timeInMillis,
            end.timeInMillis
        ).sortedBy {
            it.startMillis
        }

        val message = StringBuilder(
            "Zafer Bey, günaydın. "
        )

        if (events.isEmpty()) {
            message.append(
                "Bugün takviminizde kayıtlı " +
                        "bir randevu görünmüyor. "
            )
        } else {
            message.append(
                "Bugün ${events.size} " +
                        "takvim etkinliğiniz bulunuyor. "
            )

            val timeFormat = SimpleDateFormat(
                "HH:mm",
                turkish
            ).apply {
                timeZone = zone
            }

            events.take(10).forEach { event ->

                if (event.allDay) {
                    message.append(
                        "Tüm gün: ${event.title}. "
                    )
                } else {
                    val eventTime = timeFormat.format(
                        Date(event.startMillis)
                    )

                    message.append(
                        "Saat $eventTime, " +
                                "${event.title}. "
                    )
                }
            }

            if (events.size > 10) {
                message.append(
                    "Diğer etkinliklerinizi " +
                            "Takvimim bölümünden " +
                            "görebilirsiniz. "
                )
            }
        }

        // Hatirlatma kayitlarini say.
        // Bunlarin tamamlama durumu bu
        // ekranda dogrulanmadigi icin
        // "bekleyen gorev" demiyoruz.
        try {
            val reminders =
                ReminderStore.getAllReminders(
                    context
                )

            if (reminders.isNotEmpty()) {
                message.append(
                    "Ayrıca kayıtlı " +
                            "${reminders.size} görev " +
                            "hatırlatmanız bulunuyor. "
                )
            }

        } catch (error: Exception) {
            Log.w(
                TAG,
                "Gorev hatirlatmalari okunamadi",
                error
            )
        }

        message.append(
            "Güzel ve verimli bir gün dilerim."
        )

        return message.toString()
    }

    // ==========================================
    // TELEFON BILDIRIMI
    // ==========================================

    private fun showNotification(
        context: Context,
        message: String
    ) {
        try {
            if (
                Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val manager = context.getSystemService(
                NotificationManager::class.java
            )

            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Zoro Sabah Brifingi",
                    NotificationManager.IMPORTANCE_HIGH
                )

                manager.createNotificationChannel(
                    channel
                )
            }

            val builder =
                if (Build.VERSION.SDK_INT >= 26) {
                    Notification.Builder(
                        context,
                        CHANNEL_ID
                    )
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context)
                }

            val notification = builder
                .setSmallIcon(
                    android.R.drawable.ic_popup_reminder
                )
                .setContentTitle(
                    "Zoro Günlük Sabah Brifingi"
                )
                .setContentText(message)
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(message)
                )
                .setAutoCancel(true)
                .build()

            manager.notify(
                NOTIFICATION_ID,
                notification
            )

        } catch (error: Exception) {
            Log.e(
                TAG,
                "Sabah bildirimi hatasi",
                error
            )
        }
    }

    // ==========================================
    // TURKCE SESLENDIRME
    // ==========================================

    private fun speakBriefing(
        context: Context,
        message: String,
        onComplete: () -> Unit
    ) {
        val handler = Handler(
            Looper.getMainLooper()
        )

        val completed = AtomicBoolean(false)

        var tts: TextToSpeech? = null

        fun finishSafely() {
            if (
                completed.compareAndSet(
                    false,
                    true
                )
            ) {
                try {
                    tts?.stop()
                    tts?.shutdown()
                } catch (_: Exception) {
                }

                onComplete()
            }
        }

        // BroadcastReceiver uzun sure
        // arka planda calisamaz.
        handler.postDelayed(
            { finishSafely() },
            8000L
        )

        handler.post {
            try {
                tts = TextToSpeech(
                    context
                ) { status ->

                    handler.post speech@{

                        if (completed.get()) {
                            return@speech
                        }

                        if (
                            status != TextToSpeech.SUCCESS
                        ) {
                            finishSafely()
                            return@speech
                        }

                        val engine = tts

                        if (engine == null) {
                            finishSafely()
                            return@speech
                        }

                        engine.language =
                            Locale.forLanguageTag(
                                "tr-TR"
                            )

                        engine.setSpeechRate(0.95f)
                        engine.setPitch(0.85f)

                        engine.setOnUtteranceProgressListener(
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
                            }
                        )

                        val result = engine.speak(
                            message,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "ZORO_MORNING_BRIEFING"
                        )

                        if (
                            result == TextToSpeech.ERROR
                        ) {
                            finishSafely()
                        }
                    }
                }

            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Sabah seslendirme hatasi",
                    error
                )
                finishSafely()
            }
        }
    }
}
