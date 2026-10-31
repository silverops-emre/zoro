
package com.zaferyilmaz.zoro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ZORO_BOOT"
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.i(
            TAG,
            "Telefon acildi. Alarm kurtarma basliyor."
        )

        // BroadcastReceiver zaman sinirina takilmamak
        // icin islemleri arka planda tamamla.
        val pendingResult = goAsync()

        Thread {
            try {

                // ======================================
                // 1. MEVCUT GOREV HATIRLATMALARI
                // ======================================

                restoreTaskReminders(context)

                // ======================================
                // 2. ZORO OZEL TAKVIM ALARMLARI
                // ======================================

                restoreCalendarReminders(context)
                // Sabah 10.00 brifingini yeniden planla
                ZoroMorningBriefingScheduler.scheduleNext(context)

                Log.i(
                    TAG,
                    "Tum alarm kurtarma islemleri tamamlandi."
                )

            } catch (error: Exception) {

                Log.e(
                    TAG,
                    "Telefon acilisi alarm hatasi",
                    error
                )

            } finally {

                pendingResult.finish()
            }
        }.start()
    }

    // ==========================================
    // MEVCUT GOREV ALARMLARINI GERI YUKLE
    // ==========================================

    private fun restoreTaskReminders(
        context: Context
    ) {

        val reminders =
            ReminderStore.getAllReminders(context)

        Log.i(
            TAG,
            "Kayitli gorev hatirlatmasi: ${reminders.size}"
        )

        val alarmManager =
            context.getSystemService(
                AlarmManager::class.java
            )

        val now = System.currentTimeMillis()

        reminders.forEach { reminder ->

            try {

                Log.i(
                    TAG,
                    "Kontrol: ${reminder.taskText} / " +
                            reminder.dueAt
                )

                val parsedDate =
                    parseZoroDate(reminder.dueAt)

                if (parsedDate == null) {

                    Log.e(
                        TAG,
                        "Tarih cozumlenemedi: " +
                                reminder.dueAt
                    )

                    return@forEach
                }

                var triggerTime =
                    parsedDate.time

                // ==================================
                // GECMIS TARIHLI GOREVLER
                // ==================================

                if (triggerTime <= now) {

                    if (
                        reminder.repeatInterval > 0L
                    ) {

                        val interval =
                            reminder.repeatInterval

                        val elapsed =
                            now - triggerTime

                        val steps =
                            elapsed / interval + 1

                        triggerTime +=
                            steps * interval

                    } else {

                        ReminderStore.removeReminder(
                            context,
                            reminder.alarmId
                        )

                        Log.i(
                            TAG,
                            "Suresi gecmis alarm kaldirildi: " +
                                    reminder.taskText
                        )

                        return@forEach
                    }
                }

                // ==================================
                // MEVCUT REMINDERRECEIVER
                // ==================================

                val reminderIntent =
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
                        reminderIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or
                                PendingIntent.FLAG_IMMUTABLE
                    )

                val alarmCreated =
                    scheduleSafely(
                        alarmManager,
                        triggerTime,
                        pendingIntent
                    )

                if (alarmCreated) {

                    Log.i(
                        TAG,
                        "Gorev alarmi yeniden kuruldu: " +
                                reminder.taskText
                    )

                } else {

                    Log.e(
                        TAG,
                        "Gorev alarmi kurulamadi: " +
                                reminder.taskText
                    )
                }

            } catch (error: Exception) {

                Log.e(
                    TAG,
                    "Gorev alarmi geri yukleme hatasi",
                    error
                )
            }
        }
    }

    // ==========================================
    // YENI: TAKVIM ALARMLARINI GERI YUKLE
    // ==========================================

    private fun restoreCalendarReminders(
        context: Context
    ) {

        try {

            val calendarManager =
                CalendarManager(context)

            val events =
                calendarManager.getLocalEvents()

            Log.i(
                TAG,
                "Kayitli Zoro takvim etkinligi: ${events.size}"
            )

            // Yalnizca gelecekteki takvim alarmlari
            // yeniden planlanir.
            ZoroCalendarAlarmScheduler
                .restoreFutureAlarms(context)

            Log.i(
                TAG,
                "Zoro takvim alarmlari yeniden planlandi."
            )

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Takvim alarmlari geri yuklenemedi",
                error
            )
        }
    }

    // ==========================================
    // GUVENLI ALARM PLANLAMA
    // ==========================================

    private fun scheduleSafely(
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

                Log.i(
                    TAG,
                    "Kesin zamanli alarm planlandi."
                )

            } else {

                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )

                Log.w(
                    TAG,
                    "Kesin alarm izni yok. " +
                            "Alternatif alarm planlandi."
                )
            }

            true

        } catch (error: SecurityException) {

            Log.w(
                TAG,
                "Kesin alarm izni reddedildi.",
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
                    "Alternatif alarm kurulamadi.",
                    fallbackError
                )

                false
            }

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Alarm planlama hatasi",
                error
            )

            false
        }
    }

    // ==========================================
    // PYTHON TARIHINI ANDROID TARIHINE CEVIR
    // ==========================================

    private fun parseZoroDate(
        dueAt: String
    ): Date? {

        val normalizedDate =
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

        formats.forEach { pattern ->

            try {

                val formatter =
                    SimpleDateFormat(
                        pattern,
                        Locale.US
                    ).apply {
                        isLenient = false
                    }

                val parsed =
                    formatter.parse(normalizedDate)

                if (parsed != null) {
                    return parsed
                }

            } catch (_: Exception) {
                // Diger tarih bicimini dene.
            }
        }

        return null
    }
}
