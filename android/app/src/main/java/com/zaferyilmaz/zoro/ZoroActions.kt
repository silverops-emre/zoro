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

    fun scheduleReminderFromServer(
        taskText: String,
        dueAt: String,
        alarmId: String,
        repeatInterval: Long
    )

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

                        if (dueAt.isNotBlank() && dueAt != "null") {
                            reminders.scheduleReminderFromServer(
                                taskText = action.optString("text"),
                                dueAt = dueAt,
                                alarmId = action.optString("alarm_id"),
                                repeatInterval = action.optLong("repeat_interval", 0L)
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

    fun runIntentActions(
        activity: Activity,
        actions: List<JSONObject>
    ): List<String> {

        val failures = mutableListOf<String>()

        for (action in actions.filterNot { isDataAction(it) }) {

            val failure = try {

                when (action.optString("type")) {
                    "navigate" -> navigate(activity, action)
                    "call" -> call(activity, action)
                    "message" -> message(activity, action)
                    "open_app" -> openApp(activity, action)
                    "set_alarm" -> setAlarm(activity, action)
                    "set_timer" -> setTimer(activity, action)
                    else -> null
                }

            } catch (error: ActivityNotFoundException) {

                Log.e(TAG, "Uygulama bulunamadi: $action", error)
                "Bu işi yapacak uygulama telefonda bulunamadı."

            } catch (error: Exception) {

                Log.e(TAG, "Aksiyon hatasi: $action", error)
                "İşlem başlatılamadı."
            }

            if (failure != null) {
                failures.add(failure)
            }
        }

        return failures
    }

    private fun start(activity: Activity, intent: Intent) {

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    private fun startFirst(activity: Activity, vararg intents: Intent) {

        var last: ActivityNotFoundException? = null

        for (intent in intents) {
            try {
                start(activity, intent)
                return
            } catch (error: ActivityNotFoundException) {
                last = error
            }
        }

        throw last ?: ActivityNotFoundException()
    }

    private fun navigate(activity: Activity, action: JSONObject): String? {

        val destination = action.optString("destination").trim()

        if (destination.isBlank()) {
            return "Nereye gideceğinizi anlayamadım."
        }

        val mode = when (action.optString("mode")) {
            "walking" -> "w"
            "transit" -> "r"
            else -> "d"
        }

        val encoded = Uri.encode(destination)

        startFirst(
            activity,
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=$encoded&mode=$mode")
            ).setPackage("com.google.android.apps.maps"),
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$encoded")
            )
        )

        return null
    }

    // ==========================================
    // REHBER
    // ==========================================

    fun findContacts(
        context: Context,
        name: String
    ): List<ZoroContactMatch>? {

        if (
            context.checkSelfPermission(
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val wanted = name.trim().lowercase(turkish)

        if (wanted.isBlank()) {
            return emptyList()
        }

        val exact = mutableListOf<ZoroContactMatch>()
        val starts = mutableListOf<ZoroContactMatch>()
        val words = mutableListOf<ZoroContactMatch>()

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
                val lower = display.lowercase(turkish)

                if (number.isBlank()) continue

                val match = ZoroContactMatch(display, number)

                when {
                    lower == wanted -> exact.add(match)
                    lower.startsWith(wanted) -> starts.add(match)
                    lower.split(" ").any { it.startsWith(wanted) } -> words.add(match)
                }
            }
        }

        val best = exact.ifEmpty { starts.ifEmpty { words } }

        return best.distinctBy {
            it.number.filter { ch -> ch.isDigit() }.takeLast(10)
        }
    }

    private fun resolveNumber(
        activity: Activity,
        name: String,
        spokenNumber: String
    ): Pair<String?, String?> {

        if (spokenNumber.any { it.isDigit() }) {
            return spokenNumber to null
        }

        val matches = findContacts(activity, name)
            ?: return null to "Rehber izni olmadığı için $name kişisini bulamadım. " +
                    "Zoro'ya rehber izni verin."

        val names = matches.map { it.name }.distinct()

        return when {
            matches.isEmpty() ->
                null to "Rehberde $name adında birini bulamadım."
            names.size > 1 ->
                null to "Rehberde birden fazla kişi var: " +
                        names.take(3).joinToString(", ") +
                        ". Hangisi olduğunu tam adıyla söyler misiniz?"
            else ->
                matches.first().number to null
        }
    }

    private fun call(activity: Activity, action: JSONObject): String? {

        val name = action.optString("name")
        val (number, problem) = resolveNumber(
            activity,
            name,
            action.optString("number")
        )

        if (number == null) {
            return problem
        }

        val canCall = activity.checkSelfPermission(
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        // Izin yoksa numara cevirici ekranda hazir acilir,
        // arama tusuna kullanici basar.
        start(
            activity,
            Intent(
                if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                Uri.parse("tel:" + Uri.encode(number))
            )
        )

        return null
    }

    fun whatsappDigits(number: String): String {

        var digits = number.filter { it.isDigit() }

        if (digits.startsWith("00")) digits = digits.drop(2)
        if (digits.length == 11 && digits.startsWith("0")) digits = "90" + digits.drop(1)
        if (digits.length == 10 && digits.startsWith("5")) digits = "90$digits"

        return digits
    }

    private fun message(activity: Activity, action: JSONObject): String? {

        val recipient = action.optString("recipient")
        val text = action.optString("text")

        val (number, problem) = resolveNumber(activity, recipient, "")

        if (number == null) {
            return problem
        }

        if (action.optString("app") == "sms") {

            start(
                activity,
                Intent(
                    Intent.ACTION_SENDTO,
                    Uri.parse("smsto:" + Uri.encode(number))
                ).putExtra("sms_body", text)
            )

        } else {

            start(
                activity,
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "https://wa.me/" + whatsappDigits(number) +
                                "?text=" + Uri.encode(text)
                    )
                )
            )
        }

        return null
    }

    private fun launchPackage(activity: Activity, packageName: String): Intent? =
        activity.packageManager.getLaunchIntentForPackage(packageName)

    private fun openApp(activity: Activity, action: JSONObject): String? {

        val query = action.optString("query").trim()
        val encoded = Uri.encode(query)

        val intent: Intent? = when (action.optString("app")) {

            "youtube" ->
                if (query.isNotBlank())
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
                else
                    launchPackage(activity, "com.google.android.youtube")

            "spotify" ->
                if (query.isNotBlank())
                    Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded"))
                else
                    launchPackage(activity, "com.spotify.music")

            "whatsapp" -> launchPackage(activity, "com.whatsapp")

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

        if (intent == null) {
            return "Bu uygulama telefonda yüklü değil."
        }

        start(activity, intent)
        return null
    }

    private fun setAlarm(activity: Activity, action: JSONObject): String? {

        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, action.optInt("hour"))
            .putExtra(AlarmClock.EXTRA_MINUTES, action.optInt("minute"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)

        val label = action.optString("label")

        if (label.isNotBlank()) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }

        start(activity, intent)
        return null
    }

    private fun setTimer(activity: Activity, action: JSONObject): String? {

        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, action.optInt("seconds"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)

        val label = action.optString("label")

        if (label.isNotBlank()) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }

        start(activity, intent)
        return null
    }
}
