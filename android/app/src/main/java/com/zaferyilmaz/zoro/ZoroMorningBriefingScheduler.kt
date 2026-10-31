package com.zaferyilmaz.zoro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar
import java.util.TimeZone

object ZoroMorningBriefingScheduler {

    private const val TAG = "ZORO_MORNING_SCHEDULER"

    private const val REQUEST_CODE = 101000


    // ==========================================
    // HER GUN SAAT 10.00 ALARMINI PLANLA
    // ==========================================

    fun scheduleNext(
        context: Context
    ): Boolean {

        return try {

            val alarmManager =
                context.getSystemService(
                    AlarmManager::class.java
                )

            // ZORO 2.1: saat ve acik/kapali Ayarlar ekranindan gelir (varsayilan 10:00).
            val (briefingHour, briefingMinute) =
                ZoroPrefs.briefingTime(context)

            val zone = TimeZone.getTimeZone(
                "Europe/Istanbul"
            )

            val next = Calendar.getInstance(
                zone
            ).apply {

                set(
                    Calendar.HOUR_OF_DAY,
                    briefingHour
                )

                set(
                    Calendar.MINUTE,
                    briefingMinute
                )

                set(
                    Calendar.SECOND,
                    0
                )

                set(
                    Calendar.MILLISECOND,
                    0
                )

                if (
                    timeInMillis <=
                    System.currentTimeMillis()
                ) {
                    add(
                        Calendar.DAY_OF_YEAR,
                        1
                    )
                }
            }

            val intent = Intent(
                context,
                ZoroMorningBriefingReceiver::class.java
            ).apply {
                action = "com.zaferyilmaz.zoro.MORNING_BRIEFING"
            }

            val pendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_IMMUTABLE
                )

            if (!ZoroPrefs.briefingEnabled(context)) {
                alarmManager.cancel(pendingIntent)
                Log.i(TAG, "Sabah ozeti kapali, alarm kaldirildi")
                return true
            }

            val exactAllowed =
                Build.VERSION.SDK_INT <
                        Build.VERSION_CODES.S ||
                        alarmManager.canScheduleExactAlarms()

            if (exactAllowed) {

                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    next.timeInMillis,
                    pendingIntent
                )

            } else {

                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    next.timeInMillis,
                    pendingIntent
                )
            }

            Log.i(
                TAG,
                "Sonraki sabah brifingi planlandi: " +
                        next.time
            )

            true

        } catch (error: SecurityException) {

            Log.w(
                TAG,
                "Kesin alarm izni kullanilamadi",
                error
            )

            false

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Sabah brifingi planlanamadi",
                error
            )

            false
        }
    }
}
