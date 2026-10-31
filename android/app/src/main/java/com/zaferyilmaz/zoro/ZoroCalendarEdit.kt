
package com.zaferyilmaz.zoro

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ZoroCalendarEdit {

    private val locale =
        Locale.forLanguageTag("tr-TR")

    private val zone =
        TimeZone.getTimeZone("Europe/Istanbul")

    private val months = listOf(
        "ocak", "şubat", "mart", "nisan",
        "mayıs", "haziran", "temmuz", "ağustos",
        "eylül", "ekim", "kasım", "aralık"
    )

    private fun normalized(text: String): String {
        return text
            .lowercase(locale)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun words(text: String): List<String> {
        return normalized(text)
            .split(Regex("[^a-zçğıöşü0-9]+"))
            .filter { it.isNotBlank() }
    }

    // ==========================================
    // KOMUTU TANIMA
    // ==========================================

    fun isEditCommand(command: String): Boolean {
        val text = normalized(command)

        val editAction = listOf(
            "iptal et",
            "sil",
            "ertele"
        ).any { text.contains(it) }

        if (!editAction) return false

        // Gorev silme komutlarini degistirme.
        if (
            text.contains("görev") ||
            text.contains("hatırlatma") ||
            text.contains("alarmı")
        ) {
            return false
        }

        return listOf(
            "randevu",
            "toplantı",
            "görüşme",
            "etkinlik",
            "takvim"
        ).any { text.contains(it) }
    }

    // ==========================================
    // TARIH FİLTRESI
    // ==========================================

    private fun getRequestedDay(
        command: String
    ): Pair<Long, Long>? {

        val text = normalized(command)

        val day = Calendar.getInstance(zone)

        day.set(Calendar.HOUR_OF_DAY, 0)
        day.set(Calendar.MINUTE, 0)
        day.set(Calendar.SECOND, 0)
        day.set(Calendar.MILLISECOND, 0)

        when {
            text.contains("öbür gün") -> {
                day.add(Calendar.DAY_OF_YEAR, 2)
            }

            text.contains("yarın") -> {
                day.add(Calendar.DAY_OF_YEAR, 1)
            }

            text.contains("bugün") -> {
                // Bugun.
            }

            else -> {
                val monthPattern =
                    months.joinToString("|")

                val match = Regex(
                    """(\d{1,2})\s+($monthPattern)""" +
                            """(?:\s+(\d{4}))?""",
                    RegexOption.IGNORE_CASE
                ).find(text) ?: return null

                val requestedDay =
                    match.groupValues[1].toIntOrNull()
                        ?: return null

                val requestedMonth =
                    months.indexOf(
                        match.groupValues[2]
                    )

                val requestedYear =
                    match.groupValues[3]
                        .toIntOrNull()
                        ?: day.get(Calendar.YEAR)

                if (
                    requestedMonth !in 0..11 ||
                    requestedYear !in 1900..2200
                ) {
                    return null
                }

                day.isLenient = false
                day.clear()

                day.set(
                    requestedYear,
                    requestedMonth,
                    requestedDay,
                    0,
                    0,
                    0
                )

                try {
                    day.timeInMillis
                } catch (_: IllegalArgumentException) {
                    return null
                }
            }
        }

        val start = day.timeInMillis

        day.add(Calendar.DAY_OF_YEAR, 1)

        return start to day.timeInMillis
    }

    // ==========================================
    // RANDEVU ADINI ESLESTIRME
    // ==========================================

    private fun eventMatches(
        command: String,
        title: String
    ): Boolean {

        val ignored = setOf(
            "zoro", "bugün", "yarın", "yarınki",
            "öbür", "gün", "saat", "iptal",
            "sil", "ertele", "et", "eder",
            "lütfen", "takvim", "takvimde",
            "takvimimde", "takvimimden",
            "randevu", "randevum",
            "randevumu", "randevumu",
            "etkinlik", "etkinliğimi",
            "toplantıyı", "toplantımı",
            "görüşmeyi", "görüşmemi",
            "tarih", "değiştir"
        )

        val titleWords = words(title)

        val commandWords = words(command).filter {
            it.length >= 4 &&
                    it !in ignored &&
                    it !in months &&
                    it.toIntOrNull() == null &&
                    !Regex("""\d{1,2}""").matches(it)
        }

        // Genel "randevumu sil" komutunda
        // yanlislikla ilk etkinligi secme.
        if (commandWords.isEmpty()) {
            return false
        }

        return commandWords.any { commandWord ->

            titleWords.any { titleWord ->

                // Turkce hal eklerini tolere etmek icin
                // en az bes karakterlik ortak kok ara.
                val prefixLength =
                    minOf(5, commandWord.length)

                commandWord.length >= 5 &&
                        titleWord.length >= 5 &&
                        commandWord.substring(
                            0,
                            prefixLength
                        ) == titleWord.substring(
                    0,
                    prefixLength
                )
            }
        }
    }

    // ==========================================
    // YENI SAAT
    // ==========================================

    private fun requestedTime(
        command: String
    ): Pair<Int, Int>? {

        val text = normalized(command)

        val match = Regex(
            """saat\s*(\d{1,2})[.:](\d{2})"""
        ).find(text) ?: return null

        val hour =
            match.groupValues[1].toIntOrNull()
                ?: return null

        val minute =
            match.groupValues[2].toIntOrNull()
                ?: return null

        if (
            hour !in 0..23 ||
            minute !in 0..59
        ) {
            return null
        }

        return hour to minute
    }

    // ==========================================
    // TARIH GOSTERIMI
    // ==========================================

    private fun formatDate(
        millis: Long
    ): String {

        return SimpleDateFormat(
            "dd MMMM yyyy, HH:mm",
            locale
        ).apply {
            timeZone = zone
        }.format(Date(millis))
    }

    // ==========================================
    // KOMUTU UYGULA
    // ==========================================

    fun handleCommand(
        context: Context,
        command: String
    ): String {

        if (!isEditCommand(command)) {
            return "Randevu düzenleme " +
                    "komutunu anlayamadım."
        }

        val text = normalized(command)

        val isDelete =
            text.contains("iptal et") ||
                    text.contains("sil")

        val isPostpone =
            text.contains("ertele")

        val manager = CalendarManager(context)

        val now = System.currentTimeMillis()

        // Yalnizca Zoro'nun kendi gelecekteki
        // etkinliklerinde degisiklik yap.
        var candidates = manager.getLocalEvents()
            .filter {
                it.startMillis > now &&
                        !it.allDay
            }

        val dayRange = try {
            getRequestedDay(command)
        } catch (_: Exception) {
            null
        }

        if (dayRange != null) {

            candidates = candidates.filter {
                it.startMillis >= dayRange.first &&
                        it.startMillis < dayRange.second
            }
        }

        candidates = candidates.filter {
            eventMatches(
                command,
                it.title
            )
        }

        if (candidates.isEmpty()) {
            return "Bu açıklamaya uyan " +
                    "gelecekteki bir Zoro randevusu " +
                    "bulamadım. Randevu adını ve " +
                    "tarihini söyleyebilirsiniz."
        }

        // ======================================
        // BIRDEN FAZLA RANDEVU
        // ======================================

        if (candidates.size > 1) {

            val examples = candidates
                .take(3)
                .joinToString(". ") {
                    "${it.title}, " +
                            formatDate(it.startMillis)
                }

            return "Birden fazla randevu " +
                    "buldum. Hiçbirini değiştirmedim. " +
                    "Hangisini kastettiğinizi " +
                    "tarihiyle belirtin. $examples."
        }

        val selected = candidates.first()

        // ======================================
        // RANDEVUYU SIL
        // ======================================

        if (isDelete) {

            val deleted = manager.deleteLocalEvent(
                selected.id
            )

            return if (deleted) {

                "${selected.title} randevusu " +
                        "takvimden silindi. " +
                        "Bağlı alarmın iptali de istendi."

            } else {

                "Randevu silinemedi."
            }
        }

        // ======================================
        // RANDEVUYU ERTELE
        // ======================================

        if (isPostpone) {

            val time = requestedTime(command)

            if (time == null) {

                return "Randevuyu hangi saate " +
                        "ertelemek istediğinizi " +
                        "söyleyin. Örneğin, " +
                        "yarınki toplantımı saat " +
                        "16.00'ya ertele."
            }

            val date = Calendar.getInstance(
                zone
            ).apply {
                timeInMillis =
                    selected.startMillis

                set(
                    Calendar.HOUR_OF_DAY,
                    time.first
                )

                set(
                    Calendar.MINUTE,
                    time.second
                )

                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val newStart = date.timeInMillis

            if (newStart <= now) {

                return "Randevuyu geçmiş " +
                        "bir saate taşıyamıyorum."
            }

            val duration =
                selected.endMillis -
                        selected.startMillis

            val updated = selected.copy(
                startMillis = newStart,
                endMillis = newStart + duration
            )

            val success = try {

                manager.updateLocalEvent(
                    updated
                )

            } catch (error: Exception) {

                Log.e(
                    "ZORO_CALENDAR_EDIT",
                    "Randevu erteleme hatasi",
                    error
                )

                false
            }

            return if (success) {

                "${selected.title} randevusu " +
                        formatDate(newStart) +
                        " tarihine ertelendi. " +
                        "Hatırlatma da yeni saate " +
                        "göre güncellendi."

            } else {

                "Randevu ertelenemedi."
            }
        }

        return "Randevu komutunu " +
                "anlayamadım."
    }
}
