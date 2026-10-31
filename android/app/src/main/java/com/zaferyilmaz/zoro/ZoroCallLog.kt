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
// sunucuya gider: yalnizca soz aramalarla ilgiliyse ("ara", "cevapsiz",
// "telefon"...) ya da sabah ozetinde. Ayar kapaliysa ya da izin yoksa hic.

object ZoroCallLog {

    private const val TAG = "ZORO_CALLS"
    private const val WINDOW_MS = 48 * 60 * 60 * 1000L
    private const val LIMIT = 10

    fun canRead(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED

    private val callTopic = Regex(
        "(?<!\\p{L})(ara|cevapsız|cevapsiz|çağrı|cagri|telefon|geri dön|geri don)",
        RegexOption.IGNORE_CASE
    )

    // null = gonderilmez (ayar kapali, izin yok ya da soz aramalarla ilgili degil).
    fun recentCalls(context: Context, message: String, briefing: Boolean = false): JSONArray? {

        if (!ZoroPrefs.callLog(context) || !canRead(context)) {
            return null
        }

        if (!briefing && !callTopic.containsMatchIn(message.lowercase(java.util.Locale.forLanguageTag("tr-TR")))) {
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
    // aramalarda var mi? Varsa telefonun kaydindaki numara doner (sunucunun
    // gonderdigi metin degil). Yoksa null.
    fun recentNumber(context: Context, number: String): String? {

        val wanted = number.filter { it.isDigit() }.takeLast(10)

        if (wanted.length < 3 || !canRead(context)) {
            return null
        }

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((System.currentTimeMillis() - WINDOW_MS).toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                var found: String? = null
                while (found == null && cursor.moveToNext()) {
                    val logged = cursor.getString(0).orEmpty()
                    if (logged.filter { it.isDigit() }.takeLast(10) == wanted) found = logged
                }
                found
            }
        } catch (error: Exception) {
            Log.e(TAG, "Arama kaydi kontrol edilemedi", error)
            null
        }
    }

    // Numara rehberde kayitli mi? Kayitli degilse (orn. tek calip kapanan
    // ucretli hat) geri arama dogrudan yapilmaz, ceviricide acilir.
    fun isContact(context: Context, number: String): Boolean {

        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return try {
            context.contentResolver.query(
                android.net.Uri.withAppendedPath(
                    android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(number)
                ),
                arrayOf(android.provider.ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } == true
        } catch (error: Exception) {
            Log.e(TAG, "Rehber kontrol edilemedi", error)
            false
        }
    }
}
