package com.zaferyilmaz.zoro

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ZoroCalendarReminderVoice {

    private val locale =
        Locale.forLanguageTag("tr-TR")

    private val zone =
        TimeZone.getTimeZone("Europe/Istanbul")

    private val months = listOf(
        "ocak", "şubat", "mart", "nisan",
        "mayıs", "haziran", "temmuz", "ağustos",
        "eylül", "ekim", "kasım", "aralık"
    )

    private val genericWords = setOf(
        "zoro", "zorro", "zara", "zora",
        "benim", "bana", "lütfen",
        "takvim", "takvimimde", "takvimime",
        "takvimde", "takvimdeki",
        "randevu", "randevuyu", "randevumu",
        "randevuma", "randevunun",
        "etkinlik", "etkinliği", "etkinliğimi",
        "toplantı", "toplantıyı", "toplantımı",
        "toplantının", "görüşme", "görüşmeyi",
        "görüşmemi", "buluşma", "buluşmamı",
        "bugün", "bugünkü", "yarın",
        "yarınki", "öbür", "gün",
        "önce", "hatırlat", "hatırlatır",
        "hatırlatma", "ayarla", "olarak",
        "dakika", "dakikaya", "dakikalık",
        "saat", "saate", "saatlik",
        "bir", "iki", "beş", "on",
        "onbeş", "otuz", "altmış",
        "yüz", "yirmi"
    )

    // ==========================================
    // METNI DUZENLE
    // ==========================================

    private fun normalize(
        value: String
    ): String {
        return value
            .lowercase(locale)
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun tokenize(
        value: String
    ): List<String> {
        return normalize(value)
            .split(
                Regex("[^a-zçğıöşü0-9]+")
            )
            .filter {
                it.isNotBlank()
            }
    }

    // ==========================================
    // KOMUTU TANIMA
    // ==========================================

    fun isReminderChangeCommand(
        command: String
    ): Boolean {

        val text = normalize(command)

        if (
            !text.contains("hatırlat") &&
            !text.contains("hatirlat")
        ) {
            return false
        }

        // Mevcut gorev ve genel hatirlatma
        // komutlarini degistirmiyoruz.
        if (
            text.contains("görev") ||
            text.contains("alarm kur") ||
            text.contains("not al")
        ) {
            return false
        }

        val containsEvent = listOf(
            "randevu",
            "toplantı",
            "görüşme",
            "buluşma",
            "etkinlik",
            "takvim"
        ).any {
            text.contains(it)
        }

        val containsDuration =
            Regex(
                """\b\d+\s*(?:dakika|saat)\b"""
            ).containsMatchIn(text) ||
                    listOf(
                        "bir saat",
                        "iki saat",
                        "beş dakika",
                        "on dakika",
                        "on beş dakika",
                        "otuz dakika"
                    ).any {
                        text.contains(it)
                    }

        return containsEvent &&
                containsDuration &&
                text.contains("önce")
    }

    // ==========================================
    // HATIRLATMA SURESINI BUL
    // ==========================================

    private fun parseMinutes(
        command: String
    ): Int? {

        val text = normalize(command)

        val numeric = Regex(
            """\b(\d{1,3})\s*(dakika|saat)\s*önce\b"""
        ).find(text)

        if (numeric != null) {

            val number =
                numeric.groupValues[1]
                    .toIntOrNull()
                    ?: return null

            val unit =
                numeric.groupValues[2]

            val minutes =
                if (unit == "saat") {
                    number * 60
                } else {
                    number
                }

            return minutes.takeIf {
                it in ZoroCalendarAlarmScheduler
                    .supportedMinutes
            }
        }

        val expressions = listOf(
            "iki saat önce" to 120,
            "bir saat önce" to 60,
            "otuz dakika önce" to 30,
            "on beş dakika önce" to 15,
            "onbeş dakika önce" to 15,
            "on dakika önce" to 10,
            "beş dakika önce" to 5
        )

        return expressions.firstOrNull {
            text.contains(it.first)
        }?.second
    }

    // ==========================================
    // TARIH FILTRESI
    // ==========================================

    private fun requestedDay(
        command: String
    ): Pair<Long, Long>? {

        val text = normalize(command)

        val calendar =
            Calendar.getInstance(zone)

        calendar.set(
            Calendar.HOUR_OF_DAY,
            0
        )
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        when {

            text.contains("öbür gün") -> {
                calendar.add(
                    Calendar.DAY_OF_YEAR,
                    2
                )
            }

            text.contains("yarın") -> {
                calendar.add(
                    Calendar.DAY_OF_YEAR,
                    1
                )
            }

            text.contains("bugün") -> {
                // Bugunun tarihi.
            }

            else -> {

                val monthPattern =
                    months.joinToString("|")

                val match = Regex(
                    """\b(\d{1,2})\s+""" +
                            """($monthPattern)""" +
                            """(?:\s+(\d{4}))?"""
                ).find(text) ?: return null

                val day =
                    match.groupValues[1]
                        .toIntOrNull()
                        ?: return null

                val month =
                    months.indexOf(
                        match.groupValues[2]
                    )

                val year =
                    match.groupValues[3]
                        .toIntOrNull()
                        ?: calendar.get(
                            Calendar.YEAR
                        )

                if (
                    month !in 0..11 ||
                    year !in 1900..2200
                ) {
                    return null
                }

                calendar.isLenient = false
                calendar.clear()

                calendar.set(
                    year,
                    month,
                    day,
                    0,
                    0,
                    0
                )

                try {
                    calendar.timeInMillis
                } catch (
                    error: IllegalArgumentException
                ) {
                    return null
                }
            }
        }

        val start = calendar.timeInMillis

        calendar.add(
            Calendar.DAY_OF_YEAR,
            1
        )

        return start to calendar.timeInMillis
    }

    // ==========================================
    // RANDEVU ADINI ESLESTIR
    // ==========================================

    private fun matchesTitle(
        command: String,
        title: String
    ): Boolean {

        val commandTokens = tokenize(command)
            .filter {
                it.length >= 4 &&
                        it !in genericWords &&
                        it !in months &&
                        it.toIntOrNull() == null
            }

        val titleTokens = tokenize(title)

        // Genel komutta yanlis randevuyu
        // secmemek icin baslik gerekli.
        if (commandTokens.isEmpty()) {
            return false
        }

        return commandTokens.any { spoken ->

            titleTokens.any { actual ->

                val length = minOf(
                    5,
                    spoken.length,
                    actual.length
                )

                length >= 4 &&
                        spoken.take(length) ==
                        actual.take(length)
            }
        }
    }

    // ==========================================
    // TARIHI SESLENDIR
    // ==========================================

    private fun formatDate(
        millis: Long
    ): String {

        return SimpleDateFormat(
            "dd MMMM yyyy, HH:mm",
            locale
        ).apply {
            timeZone = zone
        }.format(
            Date(millis)
        )
    }

    // ==========================================
    // HATIRLATMA SURESINI SESLE DEGISTIR
    // ==========================================

    fun handleCommand(
        context: Context,
        command: String
    ): String {

        if (!isReminderChangeCommand(command)) {
            return "Takvim hatırlatma süresi " +
                    "değiştirme komutunu anlayamadım."
        }

        val minutes =
            parseMinutes(command)

                ?: return "Hatırlatma süresini " +
                        "anlayamadım. 5, 10, 15, 30, " +
                        "60 veya 120 dakika " +
                        "seçebilirsiniz."

        val manager =
            CalendarManager(context)

        val now =
            System.currentTimeMillis()

        var candidates =
            manager.getLocalEvents()
                .filter {
                    it.startMillis > now &&
                            !it.allDay
                }

        val dayRange = try {
            requestedDay(command)
        } catch (error: Exception) {
            Log.w(
                "ZORO_REMINDER_VOICE",
                "Tarih filtreleme hatasi",
                error
            )
            null
        }

        if (dayRange != null) {

            candidates = candidates.filter {
                it.startMillis >=
                        dayRange.first &&
                        it.startMillis <
                        dayRange.second
            }
        }

        candidates = candidates.filter {
            matchesTitle(
                command,
                it.title
            )
        }

        if (candidates.isEmpty()) {

            return "Belirttiğiniz randevuyu " +
                    "Zoro takviminde bulamadım. " +
                    "Randevunun tam adını ve " +
                    "tarihini söyleyin."
        }

        if (candidates.size > 1) {

            val examples =
                candidates.take(3)
                    .joinToString(". ") {
                        "${it.title}, " +
                                formatDate(
                                    it.startMillis
                                )
                    }

            return "Birden fazla randevu " +
                    "bulundu. Hiçbirinin " +
                    "hatırlatma süresi değiştirilmedi. " +
                    "Lütfen tarih belirterek " +
                    "tekrar deneyin. $examples."
        }

        val event =
            candidates.first()

        val changed =
            ZoroCalendarAlarmScheduler
                .setReminderMinutes(
                    context,
                    event,
                    minutes
                )

        if (!changed) {

            return "Hatırlatma süresi " +
                    "değiştirilemedi. " +
                    "Randevuya yeterli süre " +
                    "kaldığından emin olun."
        }

        val description =
            ZoroCalendarAlarmScheduler
                .formatReminderMinutes(
                    minutes
                )

        return "${event.title} randevunuz " +
                "için hatırlatma zamanı " +
                "$description olarak ayarlandı."
    }
}
