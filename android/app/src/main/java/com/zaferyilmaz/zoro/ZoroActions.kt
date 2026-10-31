package com.zaferyilmaz.zoro

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ==========================================
// ZORO 2.0 SUNUCU AKSIYONLARI
// ==========================================
// Sunucu /ask cevabinda "actions" listesi doner.
// Veri aksiyonlari (takvim, hatirlatma) cevap okunmadan once,
// uygulama aksiyonlari (harita, arama, mesaj) cevap okunduktan
// sonra calisir.

interface ZoroReminderPort {

    // false: alarm telefonda kurulamadi
    fun scheduleReminderFromServer(
        taskText: String,
        dueAt: String,
        alarmId: String,
        repeatInterval: Long
    ): Boolean

    fun cancelReminderFromServer(
        alarmId: String,
        dueAt: String,
        taskText: String
    )
}

data class ZoroContactMatch(
    val name: String,
    val number: String
)

object ZoroActions {

    private const val TAG = "ZORO_ACTIONS"
    private const val DAY_MS = 86_400_000L

    private val turkish = Locale.forLanguageTag("tr-TR")

    private val DATA_TYPES = setOf(
        "calendar_add",
        "calendar_update",
        "calendar_delete",
        "schedule_reminder",
        "cancel_reminder"
    )

    fun list(json: JSONObject): List<JSONObject>? {

        val array = json.optJSONArray("actions")
            ?: return null

        return (0 until array.length()).mapNotNull {
            array.optJSONObject(it)
        }
    }

    fun isDataAction(action: JSONObject): Boolean =
        action.optString("type") in DATA_TYPES

    // ==========================================
    // SUNUCUYA GIDEN TAKVIM BAGLAMI
    // ==========================================

    fun calendarContext(context: Context): JSONArray {

        val array = JSONArray()
        val now = System.currentTimeMillis()

        val events = try {
            CalendarManager(context).getAllEvents(
                now - DAY_MS,
                now + 30 * DAY_MS
            )
        } catch (error: Exception) {
            Log.e(TAG, "Takvim baglami okunamadi", error)
            emptyList()
        }

        events.take(80).forEach { event ->
            array.put(
                JSONObject()
                    .put("id", event.id)
                    .put("title", event.title)
                    .put("start_ms", event.startMillis)
                    .put("end_ms", event.endMillis)
                    .put("location", event.location)
                    .put("source", event.source)
                    .put("all_day", event.allDay)
            )
        }

        return array
    }

    // ==========================================
    // VERI AKSIYONLARI
    // ==========================================

    fun runDataActions(
        context: Context,
        actions: List<JSONObject>,
        reminders: ZoroReminderPort
    ): List<String> {

        val failures = mutableListOf<String>()
        val calendar by lazy { CalendarManager(context) }

        for (action in actions.filter { isDataAction(it) }) {

            try {

                when (action.optString("type")) {

                    "schedule_reminder" -> {

                        val dueAt = action.optString("due_at")

                        val scheduled = dueAt.isNotBlank() && dueAt != "null" &&
                                reminders.scheduleReminderFromServer(
                                    taskText = action.optString("text"),
                                    dueAt = dueAt,
                                    alarmId = action.optString("alarm_id"),
                                    repeatInterval = action.optLong("repeat_interval", 0L)
                                )

                        if (!scheduled) {
                            failures.add(
                                "${action.optString("text")} hatırlatması telefonda kurulamadı."
                            )
                        }
                    }

                    "cancel_reminder" -> {

                        reminders.cancelReminderFromServer(
                            alarmId = action.optString("alarm_id"),
                            dueAt = action.optString("due_at"),
                            taskText = action.optString("text")
                        )
                    }

                    "calendar_add" -> {

                        val added = calendar.addLocalEvent(
                            title = action.optString("title"),
                            startMillis = action.optLong("start_ms"),
                            endMillis = action.optLong("end_ms"),
                            description = action.optString("description"),
                            location = action.optString("location")
                        )

                        if (added == null) {
                            failures.add(
                                "${action.optString("title")} takvime eklenemedi. " +
                                        "O saatte başka bir kayıt olabilir."
                            )
                        }
                    }

                    "calendar_update" -> {

                        val id = action.optString("event_id")
                        val current = calendar.getLocalEvents()
                            .firstOrNull { it.id == id }

                        val updated = current != null &&
                                calendar.updateLocalEvent(
                                    current.copy(
                                        title = action.optString("title", current.title)
                                            .ifBlank { current.title },
                                        startMillis = action.optLong("start_ms", current.startMillis),
                                        endMillis = action.optLong("end_ms", current.endMillis),
                                        location = action.optString("location", current.location)
                                    )
                                )

                        if (!updated) {
                            failures.add(
                                "${action.optString("title")} güncellenemedi. " +
                                        "Kayıt bulunamadı ya da yeni saatte başka bir kayıt var."
                            )
                        }
                    }

                    "calendar_delete" -> {

                        val id = action.optString("event_id")

                        if (calendar.deleteLocalEvent(id)) {
                            ZoroCalendarAlarmScheduler.removeReminderSettings(
                                context,
                                id
                            )
                        } else {
                            failures.add(
                                "${action.optString("title")} takvimde bulunamadı, silinemedi."
                            )
                        }
                    }
                }

            } catch (error: Exception) {

                Log.e(TAG, "Veri aksiyonu hatasi: $action", error)
                failures.add("Bir işlem telefonda tamamlanamadı.")
            }
        }

        return failures
    }

    // ==========================================
    // UYGULAMA AKSIYONLARI
    // ==========================================

    // Bir aksiyonun sonucu: acilacak intent ya da kullaniciya okunacak sorun.
    private data class Built(
        val intent: Intent? = null,
        val problem: String? = null,
        val label: String = ""
    )

    fun runIntentActions(
        activity: Activity,
        actions: List<JSONObject>,
        inForeground: Boolean
    ): List<String> {

        val failures = mutableListOf<String>()

        for (action in actions.filterNot { isDataAction(it) }) {

            val built = try {
                build(activity, action)
            } catch (error: Exception) {
                Log.e(TAG, "Aksiyon hazirlanamadi: $action", error)
                Built(problem = "İşlem başlatılamadı.")
            }

            if (built.problem != null) {
                failures.add(built.problem)
                continue
            }

            val intent = built.intent ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (!inForeground) {
                // Android arka plandaki uygulamanin ekran acmasini sessizce engeller.
                // Bildirime dokununca acilir.
                failures.add(
                    if (notifyAction(activity, intent, built.label))
                        "Ekran kapalıyken açamıyorum. Bildirime dokunarak açabilirsiniz."
                    else
                        "Ekran kapalıyken açamıyorum. Zoro'yu açıp tekrar söyleyin."
                )
                continue
            }

            try {
                activity.startActivity(intent)
            } catch (error: ActivityNotFoundException) {
                Log.e(TAG, "Uygulama bulunamadi: $action", error)
                failures.add("Bu işi yapacak uygulama telefonda bulunamadı.")
            } catch (error: SecurityException) {
                Log.e(TAG, "Izin hatasi: $action", error)
                failures.add("Bu işlem için izin gerekiyor.")
            }
        }

        return failures
    }

    private fun notifyAction(
        context: Context,
        intent: Intent,
        label: String
    ): Boolean {

        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return try {

            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                label.hashCode(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notification = androidx.core.app.NotificationCompat.Builder(context, "zoro_reminders")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Zoro")
                .setContentText(label.ifBlank { "Açmak için dokunun" })
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(4100 + (label.hashCode() and 0xff), notification)

            true

        } catch (error: Exception) {
            Log.e(TAG, "Bildirim gosterilemedi", error)
            false
        }
    }

    private fun build(activity: Activity, action: JSONObject): Built {

        return when (action.optString("type")) {
            "navigate" -> navigate(activity, action)
            "call" -> call(activity, action)
            "message" -> message(activity, action)
            "open_app" -> openApp(activity, action)
            "set_alarm" -> setAlarm(action)
            "set_timer" -> setTimer(action)
            else -> Built()
        }
    }

    private fun navigate(activity: Activity, action: JSONObject): Built {

        val destination = action.optString("destination").trim()

        if (destination.isBlank()) {
            return Built(problem = "Nereye gideceğinizi anlayamadım.")
        }

        val mode = when (action.optString("mode")) {
            "walking" -> "w"
            "transit" -> "r"
            else -> "d"
        }

        val encoded = Uri.encode(destination)

        // Haritalar yuklu degilse ayni adresi tarayici acar.
        val maps = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=$encoded&mode=$mode")
        ).setPackage("com.google.android.apps.maps")

        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$encoded")
        )

        val mapsInstalled = maps.resolveActivity(activity.packageManager) != null

        return Built(
            intent = if (mapsInstalled) maps else web,
            label = "$destination için yol tarifi"
        )
    }

    // ==========================================
    // REHBER
    // ==========================================

    // Eslesme seviyesi: 0 tam ad, 1 bir kelimesi birebir ayni, 2 yalnizca on ek.
    data class ContactSearch(
        val matches: List<ZoroContactMatch>,
        val level: Int
    )

    fun findContacts(
        context: Context,
        name: String
    ): ContactSearch? {

        if (
            context.checkSelfPermission(
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val wanted = name.trim().lowercase(turkish)

        if (wanted.isBlank()) {
            return ContactSearch(emptyList(), 2)
        }

        val wantedWords = wanted.split(" ").filter { it.isNotBlank() }
        val levels = List(3) { mutableListOf<ZoroContactMatch>() }

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )?.use { cursor ->

            while (cursor.moveToNext()) {

                val display = cursor.getString(0).orEmpty()
                val number = cursor.getString(1).orEmpty()

                if (number.isBlank()) continue

                val lower = display.lowercase(turkish)
                val words = lower.split(" ").filter { it.isNotBlank() }
                val match = ZoroContactMatch(display, number)

                when {
                    lower == wanted -> levels[0].add(match)
                    wantedWords.all { it in words } -> levels[1].add(match)
                    wantedWords.all { w -> words.any { it.startsWith(w) } } -> levels[2].add(match)
                }
            }
        }

        val level = levels.indexOfFirst { it.isNotEmpty() }.let { if (it < 0) 2 else it }

        return ContactSearch(
            matches = levels[level].distinctBy {
                it.number.filter { ch -> ch.isDigit() }.takeLast(10)
            },
            level = level
        )
    }

    // Sonuc: numara, sorun, numara rehberden kesin eslesmeyle mi geldi
    private data class Resolved(
        val number: String?,
        val problem: String?,
        val trusted: Boolean
    )

    private fun resolveNumber(
        activity: Activity,
        name: String,
        spokenNumber: String
    ): Resolved {

        if (spokenNumber.count { it.isDigit() } >= 7) {
            // Sunucu yalnizca kullanicinin soyledigi numarayi gecirir;
            // yine de arama tusuna kullanici basar.
            return Resolved(spokenNumber, null, trusted = false)
        }

        val search = findContacts(activity, name)
            ?: return Resolved(
                null,
                "Rehber izni olmadığı için $name kişisini bulamadım. Zoro'ya rehber izni verin.",
                false
            )

        val names = search.matches.map { it.name }.distinct()

        return when {
            search.matches.isEmpty() ->
                Resolved(null, "Rehberde $name adında birini bulamadım.", false)
            names.size > 1 ->
                Resolved(
                    null,
                    "Rehberde birden fazla $name var: " +
                            names.take(3).joinToString(", ") +
                            ". Hangisi olduğunu söyler misiniz?",
                    false
                )
            else ->
                Resolved(search.matches.first().number, null, trusted = search.level <= 1)
        }
    }

    private fun call(activity: Activity, action: JSONObject): Built {

        val name = action.optString("name")
        val resolved = resolveNumber(activity, name, action.optString("number"))
        val number = resolved.number ?: return Built(problem = resolved.problem)

        val canCall = activity.checkSelfPermission(
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        // Dogrudan arama yalnizca rehberde birebir isim eslesmesinde.
        // Digerlerinde numara ceviricide hazir acilir, arama tusuna kullanici basar.
        val direct = canCall && resolved.trusted

        return Built(
            intent = Intent(
                if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                Uri.parse("tel:" + Uri.encode(number))
            ),
            label = "${name.ifBlank { number }} aranıyor"
        )
    }

    fun whatsappDigits(number: String): String {

        var digits = number.filter { it.isDigit() }

        if (digits.startsWith("00")) digits = digits.drop(2)
        if (digits.length == 11 && digits.startsWith("0")) digits = "90" + digits.drop(1)
        if (digits.length == 10 && digits.startsWith("5")) digits = "90$digits"

        return digits
    }

    private fun message(activity: Activity, action: JSONObject): Built {

        val recipient = action.optString("recipient")
        val text = action.optString("text")

        // Alici numara olarak soylendiyse rehberde aranmaz.
        val spoken = if (recipient.count { it.isDigit() } >= 7) recipient else ""
        val resolved = resolveNumber(activity, recipient, spoken)
        val number = resolved.number ?: return Built(problem = resolved.problem)

        val intent = if (action.optString("app") == "sms") {
            Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("smsto:" + Uri.encode(number))
            ).putExtra("sms_body", text)
        } else {
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/" + whatsappDigits(number) + "?text=" + Uri.encode(text))
            )
        }

        return Built(intent = intent, label = "$recipient için mesaj taslağı")
    }

    private fun launchPackage(activity: Activity, packageName: String): Intent? =
        activity.packageManager.getLaunchIntentForPackage(packageName)

    private fun openApp(activity: Activity, action: JSONObject): Built {

        val query = action.optString("query").trim()
        val encoded = Uri.encode(query)
        val app = action.optString("app")

        val intent: Intent? = when (app) {

            "youtube" ->
                if (query.isNotBlank())
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
                else
                    launchPackage(activity, "com.google.android.youtube")
                        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))

            "spotify" ->
                if (query.isNotBlank())
                    Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded"))
                else
                    launchPackage(activity, "com.spotify.music")

            "whatsapp" ->
                launchPackage(activity, "com.whatsapp")
                    ?: launchPackage(activity, "com.whatsapp.w4b")

            "maps" ->
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))

            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)

            "phone" -> Intent(Intent.ACTION_DIAL)

            "messages" ->
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)

            "calendar" ->
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)

            "settings" -> Intent(Settings.ACTION_SETTINGS)

            "browser" ->
                if (query.isNotBlank())
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded"))
                else
                    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)

            "clock" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)

            else -> null
        }

        return if (intent == null)
            Built(problem = "Bu uygulama telefonda yüklü değil.")
        else
            Built(intent = intent, label = "$app açılıyor")
    }

    private fun setAlarm(action: JSONObject): Built {

        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, action.optInt("hour"))
            .putExtra(AlarmClock.EXTRA_MINUTES, action.optInt("minute"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)

        val label = action.optString("label")

        if (label.isNotBlank()) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }

        return Built(intent = intent, label = "Alarm kuruluyor")
    }

    private fun setTimer(action: JSONObject): Built {

        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, action.optInt("seconds"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)

        val label = action.optString("label")

        if (label.isNotBlank()) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }

        return Built(intent = intent, label = "Zamanlayıcı kuruluyor")
    }
}
