package com.zaferyilmaz.zoro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// ==========================================
// ZORO 2.1: SON ARAMALAR
// ==========================================
// "Beni kim aradi", "onu geri ara" icin son 48 saatin en fazla 10 aramasi
// sunucuya gider. Ayar kapaliysa ya da izin yoksa hic gonderilmez.

object ZoroCallLog {

    private const val TAG = "ZORO_CALLS"
    private const val WINDOW_MS = 48 * 60 * 60 * 1000L
    private const val LIMIT = 10

    fun canRead(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED

    // null = ayar kapali ya da izin yok (sunucu "goremiyorum" der).
    fun recentCalls(context: Context): JSONArray? {

        if (!ZoroPrefs.callLog(context) || !canRead(context)) {
            return null
        }

        val array = JSONArray()

        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE
                ),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((System.currentTimeMillis() - WINDOW_MS).toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->

                while (cursor.moveToNext() && array.length() < LIMIT) {

                    val number = cursor.getString(0).orEmpty()

                    val type = when (cursor.getInt(2)) {
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        else -> null
                    }

                    if (number.isBlank() || type == null) continue

                    array.put(
                        JSONObject()
                            .put("name", cursor.getString(1).orEmpty())
                            .put("number", number)
                            .put("type", type)
                            .put("time_ms", cursor.getLong(3))
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Arama kaydi okunamadi", error)
            return null
        }

        return array
    }

    // Sunucu "geri ara" icin numara gonderdiginde: numara gercekten son
    // aramalarda var mi? Varsa dogrudan aranir, yoksa ceviricide acilir.
    fun isRecentNumber(context: Context, number: String): Boolean {

        val wanted = number.filter { it.isDigit() }.takeLast(10)

        if (wanted.length < 3 || !canRead(context)) {
            return false
        }

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((System.currentTimeMillis() - WINDOW_MS).toString()),
                null
            )?.use { cursor ->
                var found = false
                while (!found && cursor.moveToNext()) {
                    found = cursor.getString(0).orEmpty()
                        .filter { it.isDigit() }.takeLast(10) == wanted
                }
                found
            } == true
        } catch (error: Exception) {
            Log.e(TAG, "Arama kaydi kontrol edilemedi", error)
            false
        }
    }
}
