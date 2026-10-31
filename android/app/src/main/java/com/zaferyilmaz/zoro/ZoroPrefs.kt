package com.zaferyilmaz.zoro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ==========================================
// ZORO 2.1 AYARLARI
// ==========================================
// Telefona ait anahtarlar (son aramalar, Google Takvim, kilitliyken calisma)
// yalnizca burada durur. Sunucu ayarlarinin (dogal ses, sabah ozeti) bir
// kopyasi da burada tutulur ki internet yokken ve telefon acilirken bilinsin.

object ZoroPrefs {

    private const val NAME = "zoro_v21_settings"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---------------- telefona ait

    fun callLog(context: Context): Boolean =
        prefs(context).getBoolean("call_log", true)

    fun setCallLog(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("call_log", value).apply()

    fun calendarWrite(context: Context): Boolean =
        prefs(context).getBoolean("calendar_write", true)

    fun setCalendarWrite(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("calendar_write", value).apply()

    fun backgroundCommands(context: Context): Boolean =
        prefs(context).getBoolean("background_commands", true)

    fun setBackgroundCommands(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("background_commands", value).apply()

    // ---------------- sunucu ayarlarinin kopyasi

    fun naturalVoice(context: Context): Boolean =
        prefs(context).getBoolean("natural_voice", false) &&
                serverHas(context, "tts")

    fun voice(context: Context): String =
        prefs(context).getString("voice", "marin") ?: "marin"

    fun briefingEnabled(context: Context): Boolean =
        prefs(context).getBoolean("briefing_enabled", true)

    // "SS:DD"; eski uygulama 10:00'da brifing veriyordu.
    fun briefingTime(context: Context): Pair<Int, Int> {

        val raw = prefs(context).getString("briefing_time", "10:00") ?: "10:00"
        val parts = raw.split(":")

        val hour = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: 10
        val minute = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0

        return hour to minute
    }

    fun applyServerSettings(context: Context, settings: JSONObject) {

        prefs(context).edit()
            .putBoolean("natural_voice", settings.optBoolean("natural_voice", false))
            .putString("voice", settings.optString("voice", "marin"))
            .putBoolean("briefing_enabled", settings.optBoolean("briefing_enabled", true))
            .putString("briefing_time", settings.optString("briefing_time", "10:00"))
            .apply()
    }

    // Sunucunun /version cevabindaki ozellikler ("settings", "tts" ...).
    // Eski sunucuda bos kalir ve yeni ekranlar gizlenir.
    fun serverHas(context: Context, feature: String): Boolean =
        prefs(context).getStringSet("server_features", emptySet())
            ?.contains(feature) == true

    fun setServerFeatures(context: Context, features: JSONArray?) {

        val set = mutableSetOf<String>()

        if (features != null) {
            for (i in 0 until features.length()) {
                set.add(features.optString(i))
            }
        }

        prefs(context).edit().putStringSet("server_features", set).apply()
    }
}
