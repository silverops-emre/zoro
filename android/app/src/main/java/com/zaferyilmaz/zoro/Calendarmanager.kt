
package com.zaferyilmaz.zoro

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

// ==========================================
// ZORO TAKVIM VERI MODELI
// ==========================================

data class ZoroCalendarEvent(
    val id: String,
    val title: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String,
    val source: String,
    val allDay: Boolean = false,
    // Telefon takvimi etkinligi Zoro tarafindan degistirilebilir mi
    // (ayar acik, yazma izni var, tekrarlamayan, tum gun degil).
    val writable: Boolean = false
)

// ==========================================
// ZORO TAKVIM YONETICISI
// ==========================================

class CalendarManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "ZORO_CALENDAR"
        private const val PREFS_NAME = "zoro_calendar"
        private const val EVENTS_KEY = "local_events"

        const val SOURCE_ZORO = "ZORO"
        const val SOURCE_ANDROID = "ANDROID"
    }

    private val preferences =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    // ==========================================
    // ANDROID TAKVIM IZINLERI
    // ==========================================

    fun canReadAndroidCalendar(): Boolean {
        return context.checkSelfPermission(
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun canWriteAndroidCalendar(): Boolean {
        return context.checkSelfPermission(
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ==========================================
    // ZORO YEREL TAKVIM KAYITLARINI OKU
    // ==========================================

    fun getLocalEvents(): List<ZoroCalendarEvent> {

        val result = mutableListOf<ZoroCalendarEvent>()

        try {

            val stored = preferences.getString(
                EVENTS_KEY,
                "[]"
            ) ?: "[]"

            val array = JSONArray(stored)

            for (index in 0 until array.length()) {

                val item =
                    array.optJSONObject(index)
                        ?: continue

                val id = item.optString("id")
                val title = item.optString("title")
                val start = item.optLong("startMillis")
                val end = item.optLong("endMillis")

                if (
                    id.isBlank() ||
                    title.isBlank() ||
                    start <= 0L ||
                    end <= start
                ) {
                    continue
                }

                result.add(
                    ZoroCalendarEvent(
                        id = id,
                        title = title,
                        description = item.optString(
                            "description"
                        ),
                        startMillis = start,
                        endMillis = end,
                        location = item.optString(
                            "location"
                        ),
                        source = SOURCE_ZORO,
                        allDay = item.optBoolean(
                            "allDay",
                            false
                        )
                    )
                )
            }

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Yerel takvim okunamadi",
                error
            )
        }

        return result.sortedBy {
            it.startMillis
        }
    }

    // ==========================================
    // ZORO YEREL TAKVIMINI KAYDET
    // ==========================================

    private fun saveLocalEvents(
        events: List<ZoroCalendarEvent>
    ): Boolean {

        return try {

            val array = JSONArray()

            events.forEach { event ->

                val item = JSONObject().apply {

                    put("id", event.id)
                    put("title", event.title)
                    put(
                        "description",
                        event.description
                    )
                    put(
                        "startMillis",
                        event.startMillis
                    )
                    put(
                        "endMillis",
                        event.endMillis
                    )
                    put(
                        "location",
                        event.location
                    )
                    put(
                        "allDay",
                        event.allDay
                    )
                }

                array.put(item)
            }

            preferences.edit()
                .putString(
                    EVENTS_KEY,
                    array.toString()
                )
                .commit()

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Yerel takvim kaydedilemedi",
                error
            )

            false
        }
    }

    // ==========================================
    // YENI: RANDEVU CAKISMASI KONTROLU
    // ==========================================

    fun findConflictingEvents(
        startMillis: Long,
        endMillis: Long,
        excludeEventId: String? = null
    ): List<ZoroCalendarEvent> {

        if (
            startMillis <= 0L ||
            endMillis <= startMillis
        ) {
            return emptyList()
        }

        // Hem Zoro hem Android takvimini oku.
        val allEvents = getAllEvents(
            startMillis,
            endMillis
        )

        return allEvents.filter { existing ->

            // Duzenlenen randevu kendi kendisiyle
            // cakisma olusturmasin.
            val isSameEvent =
                excludeEventId != null &&
                        existing.id == excludeEventId &&
                        existing.source == SOURCE_ZORO

            // Tum gun etkinliklerini mesgul saat
            // olarak degerlendirmiyoruz.
            val isTimedEvent = !existing.allDay

            // Gercek saat araligi kesisimi.
            val overlaps =
                startMillis < existing.endMillis &&
                        endMillis > existing.startMillis

            !isSameEvent &&
                    isTimedEvent &&
                    overlaps
        }.sortedBy {
            it.startMillis
        }
    }

    // ==========================================
    // YENI: CAKISMA UYARISINI GOSTER
    // ==========================================

    private fun showConflictWarning(
        conflicts: List<ZoroCalendarEvent>
    ) {

        if (conflicts.isEmpty()) {
            return
        }

        val first = conflicts.first()

        val startTime = formatEventDate(
            first.startMillis
        )

        val endTime = SimpleDateFormat(
            "HH:mm",
            Locale.forLanguageTag("tr-TR")
        ).apply {
            timeZone = TimeZone.getTimeZone(
                "Europe/Istanbul"
            )
        }.format(
            Date(first.endMillis)
        )

        val message =
            "Zoro: Randevu çakışması var! " +
                    "${first.title}, " +
                    "$startTime - $endTime. " +
                    "Lütfen başka bir saat seçin."

        Log.w(
            TAG,
            message
        )

        Handler(
            Looper.getMainLooper()
        ).post {

            Toast.makeText(
                context.applicationContext,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ==========================================
    // YENI ZORO ETKINLIGI OLUSTUR
    // ==========================================

    @Synchronized
    fun addLocalEvent(
        title: String,
        startMillis: Long,
        endMillis: Long,
        description: String = "",
        location: String = "",
        allDay: Boolean = false
    ): ZoroCalendarEvent? {

        if (
            title.isBlank() ||
            startMillis <= 0L ||
            endMillis <= startMillis
        ) {
            return null
        }

        // ======================================
        // YENI: KAYDETMEDEN ONCE CAKISMA KONTROLU
        // ======================================

        if (!allDay) {

            val conflicts = findConflictingEvents(
                startMillis = startMillis,
                endMillis = endMillis
            )

            if (conflicts.isNotEmpty()) {

                showConflictWarning(
                    conflicts
                )

                return null
            }
        }

        val event = ZoroCalendarEvent(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            description = description.trim(),
            startMillis = startMillis,
            endMillis = endMillis,
            location = location.trim(),
            source = SOURCE_ZORO,
            allDay = allDay
        )

        val events =
            getLocalEvents().toMutableList()

        events.add(event)

        if (!saveLocalEvents(events)) {
            return null
        }

        Log.i(
            TAG,
            "Zoro etkinligi eklendi: ${event.title}"
        )

        // ======================================
        // MEVCUT: RANDEVU ALARMINI KUR
        // ======================================

        if (!event.allDay) {

            val alarmScheduled =
                ZoroCalendarAlarmScheduler.schedule(
                    context,
                    event
                )

            if (!alarmScheduled) {

                Log.w(
                    TAG,
                    "Etkinlik kaydedildi ancak " +
                            "hatirlatma alarmi " +
                            "kurulamadi veya alarm " +
                            "zamani gecmis: ${event.title}"
                )
            }
        }

        return event
    }

    // ==========================================
    // ZORO ETKINLIGINI DUZENLE
    // ==========================================

    @Synchronized
    fun updateLocalEvent(
        event: ZoroCalendarEvent
    ): Boolean {

        if (
            event.source != SOURCE_ZORO ||
            event.id.isBlank() ||
            event.title.isBlank() ||
            event.startMillis <= 0L ||
            event.endMillis <= event.startMillis
        ) {
            return false
        }

        val events =
            getLocalEvents().toMutableList()

        val index = events.indexOfFirst {
            it.id == event.id
        }

        if (index < 0) {
            return false
        }

        // ======================================
        // YENI: ERTELEMEDE CAKISMA KONTROLU
        // ======================================

        if (!event.allDay) {

            val conflicts = findConflictingEvents(
                startMillis = event.startMillis,
                endMillis = event.endMillis,
                excludeEventId = event.id
            )

            if (conflicts.isNotEmpty()) {

                showConflictWarning(
                    conflicts
                )

                return false
            }
        }

        events[index] = event

        if (!saveLocalEvents(events)) {
            return false
        }

        // ======================================
        // MEVCUT: ESKI ALARMI IPTAL ET
        // VE YENI SAATE GORE YENILE
        // ======================================

        if (event.allDay) {

            ZoroCalendarAlarmScheduler.cancel(
                context,
                event.id
            )

        } else {

            val alarmScheduled =
                ZoroCalendarAlarmScheduler.reschedule(
                    context,
                    event
                )

            if (!alarmScheduled) {

                Log.w(
                    TAG,
                    "Etkinlik duzenlendi ancak " +
                            "yeni alarm kurulamadi " +
                            "veya alarm zamani gecmis: " +
                            event.title
                )
            }
        }

        Log.i(
            TAG,
            "Zoro etkinligi duzenlendi: ${event.title}"
        )

        return true
    }

    // ==========================================
    // ZORO ETKINLIGINI SIL
    // ==========================================

    @Synchronized
    fun deleteLocalEvent(
        eventId: String
    ): Boolean {

        if (eventId.isBlank()) {
            return false
        }

        val events =
            getLocalEvents().toMutableList()

        val removed = events.removeAll {
            it.id == eventId
        }

        if (!removed) {
            return false
        }

        if (!saveLocalEvents(events)) {
            return false
        }

        // ======================================
        // MEVCUT: SILINEN ETKINLIGIN
        // TAKVIM ALARMINI IPTAL ET
        // ======================================

        val cancelled =
            ZoroCalendarAlarmScheduler.cancel(
                context,
                eventId
            )

        if (!cancelled) {

            Log.w(
                TAG,
                "Etkinlik silindi ancak " +
                        "alarm iptali dogrulanamadi: " +
                        eventId
            )
        }

        Log.i(
            TAG,
            "Zoro etkinligi silindi: $eventId"
        )

        return true
    }

    // ==========================================
    // ANDROID / SAMSUNG / GOOGLE
    // TAKVIM ETKINLIKLERINI OKU
    // ==========================================

    fun getAndroidEvents(
        fromMillis: Long,
        toMillis: Long
    ): List<ZoroCalendarEvent> {

        if (
            !canReadAndroidCalendar() ||
            fromMillis >= toMillis
        ) {
            return emptyList()
        }

        val result =
            mutableListOf<ZoroCalendarEvent>()

        try {

            val builder =
                CalendarContract.Instances
                    .CONTENT_URI
                    .buildUpon()

            ContentUris.appendId(
                builder,
                fromMillis
            )

            ContentUris.appendId(
                builder,
                toMillis
            )

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Instances.RRULE,
                CalendarContract.Instances.ORIGINAL_ID
            )

            val mayWrite =
                ZoroPrefs.calendarWrite(context) && canWriteAndroidCalendar()

            context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                CalendarContract.Instances.BEGIN + " ASC"
            )?.use { cursor ->

                val eventIdIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Instances.EVENT_ID
                    )

                val titleIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Instances.TITLE
                    )

                val descriptionIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Instances.DESCRIPTION
                    )

                val beginIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Instances.BEGIN
                    )

                val endIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Instances.END
                    )

                val locationIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Instances.EVENT_LOCATION
                    )

                val allDayIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Instances.ALL_DAY
                    )

                val calendarIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Instances.CALENDAR_DISPLAY_NAME
                    )

                while (cursor.moveToNext()) {

                    val eventId =
                        cursor.getLong(eventIdIndex)

                    val begin =
                        cursor.getLong(beginIndex)

                    val end =
                        cursor.getLong(endIndex)

                    val title =
                        cursor.getString(titleIndex)
                            ?: "İsimsiz etkinlik"

                    val description =
                        cursor.getString(
                            descriptionIndex
                        ) ?: ""

                    val location =
                        cursor.getString(
                            locationIndex
                        ) ?: ""

                    val calendarName =
                        cursor.getString(
                            calendarIndex
                        ) ?: "Android Takvim"

                    val isAllDay =
                        cursor.getInt(allDayIndex) != 0

                    val writable = mayWrite && !isAllDay &&
                            cursor.getInt(projection.size - 3) >=
                            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR &&
                            cursor.getString(projection.size - 2).isNullOrBlank() &&
                            cursor.isNull(projection.size - 1)

                    result.add(
                        ZoroCalendarEvent(
                            id = "android_${eventId}_$begin",
                            title = title,
                            description = description,
                            startMillis = begin,
                            endMillis = end,
                            location = location,
                            source =
                                "$SOURCE_ANDROID:$calendarName",
                            allDay = isAllDay,
                            writable = writable
                        )
                    )
                }
            }

        } catch (error: SecurityException) {

            Log.e(
                TAG,
                "Android takvim izni bulunamadi",
                error
            )

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Android takvim okuma hatasi",
                error
            )
        }

        return result
    }

    // ==========================================
    // ZORO 2.1: TELEFON TAKVIMINDE DEGISIKLIK
    // ==========================================
    // Kimlik "android_<eventId>_<baslangic>". Tekrarlayan etkinlik hic
    // degistirilmez: tek bir gunu silmek butun seriyi silebilir.

    private fun androidEventId(key: String): Long? =
        key.removePrefix("android_").substringBefore("_").toLongOrNull()
            ?.takeIf { key.startsWith("android_") }

    private fun androidEventChangeable(eventId: Long): Boolean {

        if (!ZoroPrefs.calendarWrite(context) || !canWriteAndroidCalendar()) {
            return false
        }

        return try {
            context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                arrayOf(
                    CalendarContract.Events.RRULE,
                    CalendarContract.Events.ORIGINAL_ID,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.CALENDAR_ACCESS_LEVEL
                ),
                null,
                null,
                null
            )?.use { cursor ->
                cursor.moveToFirst() &&
                        cursor.getString(0).isNullOrBlank() &&
                        cursor.isNull(1) &&
                        cursor.getInt(2) == 0 &&
                        cursor.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
            } == true
        } catch (error: Exception) {
            Log.e(TAG, "Telefon takvimi etkinligi okunamadi", error)
            false
        }
    }

    fun updateAndroidEvent(
        key: String,
        title: String,
        startMillis: Long,
        endMillis: Long,
        location: String
    ): Boolean {

        val eventId = androidEventId(key) ?: return false

        if (endMillis <= startMillis || !androidEventChangeable(eventId)) {
            return false
        }

        return try {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_LOCATION, location)
            }
            context.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                values,
                null,
                null
            ) == 1
        } catch (error: Exception) {
            Log.e(TAG, "Telefon takvimi etkinligi guncellenemedi", error)
            false
        }
    }

    fun deleteAndroidEvent(key: String): Boolean {

        val eventId = androidEventId(key) ?: return false

        if (!androidEventChangeable(eventId)) {
            return false
        }

        return try {
            context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                null,
                null
            ) == 1
        } catch (error: Exception) {
            Log.e(TAG, "Telefon takvimi etkinligi silinemedi", error)
            false
        }
    }

    // ==========================================
    // TUM TAKVIMLERI BIRLESTIR
    // ==========================================

    fun getAllEvents(
        fromMillis: Long,
        toMillis: Long
    ): List<ZoroCalendarEvent> {

        if (fromMillis >= toMillis) {
            return emptyList()
        }

        val local =
            getLocalEvents().filter {

                it.startMillis < toMillis &&
                        it.endMillis > fromMillis
            }

        val android =
            getAndroidEvents(
                fromMillis,
                toMillis
            )

        return (local + android)
            .sortedBy {
                it.startMillis
            }
    }

    // ==========================================
    // MEVCUT RANDEVULARIN
    // GELECEK ALARMLARINI YENILE
    // ==========================================

    fun restoreCalendarReminders() {

        try {

            ZoroCalendarAlarmScheduler
                .restoreFutureAlarms(context)

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Takvim alarmlari yenilenemedi",
                error
            )
        }
    }

    // ==========================================
    // ETKINLIK TARIHINI GOSTER
    // ==========================================

    fun formatEventDate(
        timestamp: Long
    ): String {

        return SimpleDateFormat(
            "dd.MM.yyyy HH:mm",
            Locale.forLanguageTag("tr-TR")
        ).apply {

            timeZone =
                TimeZone.getTimeZone(
                    "Europe/Istanbul"
                )

        }.format(
            Date(timestamp)
        )
    }
}
