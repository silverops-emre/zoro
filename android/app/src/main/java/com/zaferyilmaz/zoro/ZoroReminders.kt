package com.zaferyilmaz.zoro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale

// ==========================================
// ZORO 2.1: HATIRLATMA ALARMLARI
// ==========================================
// Eskiden MainActivity icindeydi. Ekran kapali ve uygulama kapaliyken
// ses servisi de hatirlatma kurabilsin diye buraya tasindi.

object ZoroReminders {

    private const val TAG = "ZORO_REMINDER"

    // Sunucudan gelen hatirlatma. Kurulamazsa false.
    fun scheduleFromServer(
        context: Context,
        taskText: String,
        dueAt: String,
        alarmId: String,
        repeatInterval: Long
    ): Boolean {

        if (taskText.isBlank() || !isValidDueAt(dueAt)) {
            return false
        }

        val id = alarmId.ifBlank { dueAt }

        // Ayni kimlikle kurulan alarm eskisinin yerine gecer (FLAG_UPDATE_CURRENT),
        // once iptal edilmez: yeni alarm kurulamazsa eskisi kaybolmasin.
        schedule(context, taskText, dueAt, id, repeatInterval)

        // schedule yalnizca alarm kurulunca kaydeder.
        return ReminderStore.getAllReminders(context).any {
            it.alarmId == id && it.dueAt == dueAt
        }
    }

    fun cancelFromServer(
        context: Context,
        alarmId: String,
        dueAt: String,
        taskText: String
    ) {

        if (alarmId.isNotBlank() && alarmId != "null") {
            cancelByAlarmId(context, alarmId)
        } else if (isValidDueAt(dueAt)) {
            cancelByDueAt(context, dueAt)
        } else if (taskText.isNotBlank()) {
            cancelByTaskText(context, taskText)
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
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        manager.canScheduleExactAlarms()

            if (allowed) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pendingIntent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pendingIntent)
            }

            true

        } catch (error: SecurityException) {

            Log.w(TAG, "Kesin alarm izni yok, alternatif deneniyor.", error)

            try {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pendingIntent)
                true
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Alarm kurulamadı", fallbackError)
                false
            }

        } catch (error: Exception) {
            Log.e(TAG, "Alarm kurma hatası", error)
            false
        }
    }

    // ==========================================
    // HATIRLATMA OLUSTUR
    // ==========================================

    fun schedule(
        context: Context,
        taskText: String,
        dueAt: String,
        alarmId: String,
        repeatInterval: Long
    ) {

        try {

            val trigger = parseDueAtMillis(dueAt) ?: return

            if (trigger <= System.currentTimeMillis()) {
                Log.w(TAG, "Geçmiş tarihe alarm kurulmadı: $dueAt")
                return
            }

            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra("task_text", taskText)
                putExtra("alarm_id", alarmId)
                putExtra("repeat_interval", repeatInterval)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                alarmId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val manager = context.getSystemService(AlarmManager::class.java)

            if (!setSafeAlarm(manager, trigger, pendingIntent)) {
                return
            }

            ReminderStore.saveReminder(
                context = context,
                alarmId = alarmId,
                taskText = taskText,
                dueAt = dueAt,
                repeatInterval = repeatInterval
            )

            Log.i(TAG, "Alarm kaydedildi: $taskText / tekrar: $repeatInterval")

        } catch (error: Exception) {
            Log.e(TAG, "Hatırlatma kurulamadı", error)
        }
    }

    // ==========================================
    // ALARM IPTALI
    // ==========================================

    fun cancelByDueAt(context: Context, dueAt: String) {

        val item = ReminderStore.findByDueAt(context, dueAt)
        cancelByAlarmId(context, item?.alarmId ?: dueAt)
    }

    fun cancelByTaskText(context: Context, taskText: String) {

        val item = ReminderStore.getAllReminders(context)
            .firstOrNull { it.taskText == taskText }

        if (item != null) {
            cancelByAlarmId(context, item.alarmId)
        }
    }

    fun cancelByAlarmId(context: Context, alarmId: String) {

        try {

            val intent = Intent(context, ReminderReceiver::class.java)

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                alarmId.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )

            if (pendingIntent != null) {
                context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
                pendingIntent.cancel()
            }

            ReminderStore.removeReminder(context, alarmId)

            Log.i(TAG, "Alarm iptal edildi: $alarmId")

        } catch (error: Exception) {
            Log.e(TAG, "Alarm iptal hatası: $alarmId", error)
        }
    }

    fun cancelAll(context: Context) {

        ReminderStore.getAllReminders(context).forEach { reminder ->
            cancelByAlarmId(context, reminder.alarmId)
        }

        ReminderStore.clearAll(context)
    }

    // ==========================================
    // PYTHON TARIHINI OKU
    // ==========================================

    fun parseDueAtMillis(dueAt: String): Long? {

        val normalized = dueAt.replace(
            Regex("\\.(\\d{3})\\d*(?=[+-]\\d{2}:\\d{2}$)"),
            ".$1"
        )

        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )

        for (pattern in formats) {
            try {
                val formatter = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val result = formatter.parse(normalized)
                if (result != null) {
                    return result.time
                }
            } catch (_: Exception) {
            }
        }

        Log.e(TAG, "Tarih çözümlenemedi: $dueAt")
        return null
    }

    fun isValidDueAt(dueAt: String): Boolean =
        dueAt.isNotBlank() && dueAt != "null"
}

// Ekransiz calisan yerler (ses servisi) icin hatirlatma kapisi.
class ContextReminderPort(private val context: Context) : ZoroReminderPort {

    override fun scheduleReminderFromServer(
        taskText: String,
        dueAt: String,
        alarmId: String,
        repeatInterval: Long
    ): Boolean = ZoroReminders.scheduleFromServer(context, taskText, dueAt, alarmId, repeatInterval)

    override fun cancelReminderFromServer(
        alarmId: String,
        dueAt: String,
        taskText: String
    ) = ZoroReminders.cancelFromServer(context, alarmId, dueAt, taskText)
}
