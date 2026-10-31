package com.zaferyilmaz.zoro

import android.content.Context
import org.json.JSONObject

object ReminderStore {

    private const val PREFS_NAME = "zoro_reminder_store"
    private const val KEY_PREFIX = "reminder_"

    data class ReminderData(
        val alarmId: String,
        val taskText: String,
        val dueAt: String,
        val repeatInterval: Long
    )

    fun saveReminder(
        context: Context,
        alarmId: String,
        taskText: String,
        dueAt: String,
        repeatInterval: Long = 0L
    ) {
        val json =
            JSONObject().apply {
                put("alarm_id", alarmId)
                put("task_text", taskText)
                put("due_at", dueAt)
                put("repeat_interval", repeatInterval)
            }

        getPreferences(context)
            .edit()
            .putString(
                KEY_PREFIX + alarmId,
                json.toString()
            )
            .apply()
    }

    // ZORO_TASK_REMINDER_TEXT_EDIT
    // Sadece hatirlatma metnini degistirir.
    // Alarm kimligi, saati ve tekrar araligi korunur.
    fun updateReminderText(
        context: Context,
        alarmId: String,
        newText: String
    ): Boolean {
        val cleanText = newText.trim()

        if (alarmId.isBlank() || cleanText.isBlank()) {
            return false
        }

        val preferences = getPreferences(context)
        val key = KEY_PREFIX + alarmId
        val stored = preferences.getString(key, null) ?: return false

        return try {
            val json = JSONObject(stored)
            json.put("task_text", cleanText)

            preferences.edit()
                .putString(key, json.toString())
                .commit()
        } catch (_: Exception) {
            false
        }
    }
    fun removeReminder(
        context: Context,
        alarmId: String
    ) {
        getPreferences(context)
            .edit()
            .remove(KEY_PREFIX + alarmId)
            .apply()
    }

    fun findByDueAt(
        context: Context,
        dueAt: String
    ): ReminderData? {

        return getAllReminders(context)
            .firstOrNull {
                it.dueAt == dueAt
            }
    }

    fun getAllReminders(
        context: Context
    ): List<ReminderData> {

        val preferences =
            getPreferences(context)

        return preferences.all
            .filterKeys {
                it.startsWith(KEY_PREFIX)
            }
            .mapNotNull { (_, value) ->

                try {
                    val json =
                        JSONObject(
                            value as String
                        )

                    ReminderData(
                        alarmId =
                            json.optString(
                                "alarm_id"
                            ),

                        taskText =
                            json.optString(
                                "task_text"
                            ),

                        dueAt =
                            json.optString(
                                "due_at"
                            ),

                        repeatInterval =
                            json.optLong(
                                "repeat_interval",
                                0L
                            )
                    )

                } catch (_: Exception) {
                    null
                }
            }
    }

    fun clearAll(
        context: Context
    ) {
        getPreferences(context)
            .edit()
            .clear()
            .apply()
    }

    private fun getPreferences(
        context: Context
    ) =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
}