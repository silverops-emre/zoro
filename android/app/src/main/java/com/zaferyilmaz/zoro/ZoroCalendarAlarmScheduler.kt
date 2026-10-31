
package com.zaferyilmaz.zoro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ZoroCalendarAlarmScheduler {

    private const val TAG = "ZORO_CALENDAR_SCHEDULER"

    private const val PREFS_NAME =
        "zoro_calendar_reminder_settings"

    private const val MINUTES_PREFIX =
        "reminder_minutes_"

    private const val DEFAULT_MINUTES = 30

    private const val MILLIS_PER_MINUTE = 60_000L

    private const val ALARM_ACTION =
        "com.zaferyilmaz.zoro.CALENDAR_REMINDER"

    // ==========================================
    // DESTEKLENEN HATIRLATMA SURELERI
    // ==========================================

    val supportedMinutes = listOf(
        5,
        10,
        15,
        30,
        60,
        120
    )

    private fun settings(context: Context) =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    // ==========================================
    // RANDEVU HATIRLATMA SURESINI OKU
    // ==========================================

    fun getReminderMinutes(
        context: Context,
        eventId: String
    ): Int {

        return settings(context).getInt(
            MINUTES_PREFIX + eventId,
            DEFAULT_MINUTES
        )
    }

    // ==========================================
    // SUREYI GOSTERILEBILIR METNE CEVIR
    // ==========================================

    fun formatReminderMinutes(
        minutes: Int
    ): String {

        return when (minutes) {

            60 -> "1 saat önce"

            120 -> "2 saat önce"

            else -> "$minutes dakika önce"
        }
    }

    // ==========================================
    // ALARM PENDING INTENT
    // ==========================================

    private fun createPendingIntent(
        context: Context,
        eventId: String,
        title: String,
        eventTime: String,
        flags: Int
    ): PendingIntent? {

        val intent = Intent(
            context,
            ZoroCalendarReminderReceiver::class.java
        ).apply {

            action = ALARM_ACTION

            data = Uri.parse(
                "zoro-calendar://reminder/$eventId"
            )

            putExtra(
                ZoroCalendarReminderReceiver.EXTRA_EVENT_ID,
                eventId
            )

            putExtra(
                ZoroCalendarReminderReceiver.EXTRA_EVENT_TITLE,
                title
            )

            putExtra(
                ZoroCalendarReminderReceiver.EXTRA_EVENT_TIME,
                eventTime
            )
        }

        return PendingIntent.getBroadcast(
            context,
            eventId.hashCode(),
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ==========================================
    // ALARM OLUSTUR
    // ==========================================

    fun schedule(
        context: Context,
        event: ZoroCalendarEvent
    ): Boolean {

        if (
            event.source != CalendarManager.SOURCE_ZORO ||
            event.allDay ||
            event.id.isBlank()
        ) {
            return false
        }

        val minutes = getReminderMinutes(
            context,
            event.id
        )

        val triggerTime =
            event.startMillis -
                    minutes * MILLIS_PER_MINUTE

        if (
            triggerTime <= System.currentTimeMillis()
        ) {

            Log.i(
                TAG,
                "Alarm zamani gecmis: ${event.title}"
            )

            return false
        }

        val eventTime = SimpleDateFormat(
            "HH:mm",
            Locale.forLanguageTag("tr-TR")
        ).apply {

            timeZone = TimeZone.getTimeZone(
                "Europe/Istanbul"
            )

        }.format(
            Date(event.startMillis)
        )

        return try {

            val pendingIntent = createPendingIntent(
                context = context,
                eventId = event.id,
                title = event.title,
                eventTime = eventTime,
                flags = PendingIntent.FLAG_UPDATE_CURRENT
            ) ?: return false

            val alarmManager =
                context.getSystemService(
                    AlarmManager::class.java
                )

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

            Log.i(
                TAG,
                "Alarm kuruldu: ${event.title}, " +
                        "$minutes dakika once"
            )

            true

        } catch (error: SecurityException) {

            Log.w(
                TAG,
                "Kesin alarm izni hatasi",
                error
            )

            try {

                val pendingIntent = createPendingIntent(
                    context,
                    event.id,
                    event.title,
                    eventTime,
                    PendingIntent.FLAG_UPDATE_CURRENT
                ) ?: return false

                context.getSystemService(
                    AlarmManager::class.java
                ).setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )

                true

            } catch (fallbackError: Exception) {

                Log.e(
                    TAG,
                    "Alternatif alarm da kurulamadi",
                    fallbackError
                )

                false
            }

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Alarm kurma hatasi",
                error
            )

            false
        }
    }

    // ==========================================
    // ALARMI IPTAL ET
    // ==========================================

    fun cancel(
        context: Context,
        eventId: String
    ): Boolean {

        return try {

            val pendingIntent = createPendingIntent(
                context = context,
                eventId = eventId,
                title = "",
                eventTime = "",
                flags = PendingIntent.FLAG_NO_CREATE
            )

            if (pendingIntent != null) {

                context.getSystemService(
                    AlarmManager::class.java
                ).cancel(
                    pendingIntent
                )

                pendingIntent.cancel()
            }

            true

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Alarm iptal hatasi",
                error
            )

            false
        }
    }

    // ==========================================
    // ALARMI YENI AYARLA YENIDEN KUR
    // ==========================================

    fun reschedule(
        context: Context,
        event: ZoroCalendarEvent
    ): Boolean {

        if (!cancel(context, event.id)) {
            return false
        }

        return schedule(
            context,
            event
        )
    }

    // ==========================================
    // RANDEVUNUN HATIRLATMA SURESINI DEGISTIR
    // ==========================================

    fun setReminderMinutes(
        context: Context,
        event: ZoroCalendarEvent,
        minutes: Int
    ): Boolean {

        if (
            event.source != CalendarManager.SOURCE_ZORO ||
            event.allDay ||
            minutes !in supportedMinutes
        ) {
            return false
        }

        val existingEvent =
            CalendarManager(context)
                .getLocalEvents()
                .firstOrNull {
                    it.id == event.id
                } ?: return false

        val oldMinutes = getReminderMinutes(
            context,
            event.id
        )

        val saved = settings(context)
            .edit()
            .putInt(
                MINUTES_PREFIX + event.id,
                minutes
            )
            .commit()

        if (!saved) {
            return false
        }

        val scheduled = reschedule(
            context,
            existingEvent
        )

        if (!scheduled) {

            // Yeni ayar planlanamazsa eski
            // ayara ve alarma geri don.
            settings(context)
                .edit()
                .putInt(
                    MINUTES_PREFIX + event.id,
                    oldMinutes
                )
                .commit()

            reschedule(
                context,
                existingEvent
            )

            return false
        }

        Log.i(
            TAG,
            "Hatirlatma suresi degisti: " +
                    "${event.title}, $minutes dakika"
        )

        return true
    }

    // ==========================================
    // KALICI SILINEN RANDEVUNUN AYARINI TEMIZLE
    // ==========================================

    fun removeReminderSettings(
        context: Context,
        eventId: String
    ) {

        settings(context)
            .edit()
            .remove(
                MINUTES_PREFIX + eventId
            )
            .apply()
    }

    // ==========================================
    // TELEFON YENIDEN ACILDIGINDA
    // ALARMLARI GERI YUKLE
    // ==========================================

    fun restoreFutureAlarms(
        context: Context
    ) {

        val manager = CalendarManager(context)

        val now = System.currentTimeMillis()

        manager.getLocalEvents()
            .filter { event ->

                !event.allDay &&
                        event.startMillis -
                        getReminderMinutes(
                            context,
                            event.id
                        ) * MILLIS_PER_MINUTE > now

            }
            .forEach { event ->

                val scheduled = schedule(
                    context,
                    event
                )

                if (!scheduled) {

                    Log.w(
                        TAG,
                        "Alarm geri yuklenemedi: " +
                                event.title
                    )
                }
            }
    }
}
