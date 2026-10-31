
package com.zaferyilmaz.zoro

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ZoroCalendarVoice {

    private val turkish =
        Locale.forLanguageTag("tr-TR")

    private val timeZone =
        TimeZone.getTimeZone("Europe/Istanbul")

    private val months = listOf(
        "ocak", "şubat", "mart", "nisan",
        "mayıs", "haziran", "temmuz", "ağustos",
        "eylül", "ekim", "kasım", "aralık"
    )

    // ==========================================
    // METNI DUZENLE
    // ==========================================

    private fun normalize(
        command: String
    ): String {
        return command
            .lowercase(turkish)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun removeWakeWord(
        command: String
    ): String {
        return command.replace(
            Regex(
                """^(?:zoro|zorro|zora|zara)[,:\s]+""",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim()
    }

    // ==========================================
    // TAKVIM SORGUSUNU TANIMA
    // ==========================================

    fun isCalendarQuery(
        command: String
    ): Boolean {

        val text = normalize(command)

        val calendarWords = listOf(
            "takvim",
            "randevu",
            "etkinlik",
            "programım"
        )

        val queryWords = listOf(
            "ne var",
            "var mı",
            "varmi",
            "hangi",
            "neler",
            "söyle",
            "oku",
            "listele",
            "göster"
        )

        val modifyingWords = listOf(
            "ekle",
            "oluştur",
            "kaydet",
            "sil",
            "iptal et",
            "değiştir",
            "ertele",
            "taşı"
        )

        return calendarWords.any {
            text.contains(it)
        } && queryWords.any {
            text.contains(it)
        } && modifyingWords.none {
            text.contains(it)
        }
    }

    // ==========================================
    // SESLI RANDEVU EKLEME KOMUTUNU TANIMA
    // ==========================================

    fun isCalendarAddCommand(
        command: String
    ): Boolean {

        val text = normalize(
            removeWakeWord(command)
        )

        val addWords = listOf(
            "ekle",
            "oluştur",
            "kaydet"
        )

        if (addWords.none { text.contains(it) }) {
            return false
        }

        // Normal gorev komutlarini degistirmiyoruz.
        if (
            text.contains("görev ekle") ||
            text.contains("hatırlat") ||
            text.contains("alarm kur")
        ) {
            return false
        }

        // Takvime ekleme acikca soylenmisse.
        if (
            text.contains("takvime") ||
            text.contains("takvimime") ||
            text.contains("randevu ekle") ||
            text.contains("etkinlik ekle")
        ) {
            return true
        }

        // Ornek:
        // Yarin saat 14.00'e toplanti ekle.
        val hasDate =
            text.contains("bugün") ||
                    text.contains("yarın") ||
                    text.contains("öbür gün") ||
                    text.contains("öteki gün") ||
                    findExplicitDate(text) != null

        val hasTime =
            findTime(text) != null

        val hasEventWord = listOf(
            "toplantı",
            "randevu",
            "etkinlik",
            "görüşme",
            "buluşma"
        ).any {
            text.contains(it)
        }

        return hasDate &&
                hasTime &&
                hasEventWord
    }

    // ==========================================
    // TARIH TANIMA
    // ==========================================

    private fun findExplicitDate(
        text: String
    ): MatchResult? {

        val monthsPattern =
            months.joinToString("|")

        return Regex(
            """\b(\d{1,2})\s+""" +
                    """($monthsPattern)""" +
                    """(?:\s+(\d{4}))?""",
            RegexOption.IGNORE_CASE
        ).find(text)
    }

    // ==========================================
    // SAAT TANIMA
    // ==========================================

    private fun findTime(
        text: String
    ): MatchResult? {

        // Ornekler:
        // saat 14.00
        // saat 14:30
        // 14.00'e

        return Regex(
            """(?:saat\s*)?""" +
                    """(?<!\d)(\d{1,2})[.:](\d{2})""" +
                    """(?!\d)""",
            RegexOption.IGNORE_CASE
        ).find(text)
    }

    // ==========================================
    // TARIH HESAPLAMA
    // ==========================================

    private fun resolveDate(
        text: String
    ): Calendar? {

        val selected =
            Calendar.getInstance(timeZone)

        selected.set(
            Calendar.HOUR_OF_DAY,
            0
        )
        selected.set(Calendar.MINUTE, 0)
        selected.set(Calendar.SECOND, 0)
        selected.set(Calendar.MILLISECOND, 0)

        when {
            text.contains("öbür gün") ||
                    text.contains("öteki gün") -> {

                selected.add(
                    Calendar.DAY_OF_YEAR,
                    2
                )
            }

            text.contains("yarın") -> {

                selected.add(
                    Calendar.DAY_OF_YEAR,
                    1
                )
            }

            text.contains("bugün") -> {
                // Bugunun tarihi kullanilir.
            }

            else -> {
                val match =
                    findExplicitDate(text)
                        ?: return null

                val day =
                    match.groupValues[1].toIntOrNull()
                        ?: return null

                val month =
                    months.indexOf(
                        match.groupValues[2]
                            .lowercase(turkish)
                    )

                if (month !in 0..11) {
                    return null
                }

                val yearText =
                    match.groupValues[3]

                val year =
                    yearText.toIntOrNull()
                        ?: selected.get(Calendar.YEAR)

                if (year !in 1900..2200) {
                    return null
                }

                selected.isLenient = false

                selected.clear()

                selected.set(
                    year,
                    month,
                    day,
                    0,
                    0,
                    0
                )

                return try {
                    selected.timeInMillis
                    selected
                } catch (
                    error: IllegalArgumentException
                ) {
                    null
                }
            }
        }

        return selected
    }

    // ==========================================
    // ETKINLIK BASLIGINI AYIKLA
    // ==========================================

    private fun extractEventTitle(
        original: String
    ): String {

        var text = normalize(
            removeWakeWord(original)
        )

        // Acik tarih bilgisini temizle.
        val dateMatch = findExplicitDate(text)

        if (dateMatch != null) {
            text = text.replace(
                dateMatch.value,
                " "
            )
        }

        text = text
            .replace("öbür gün", " ")
            .replace("öteki gün", " ")
            .replace("yarın", " ")
            .replace("bugün", " ")

        // Saat ve saatten sonraki ekleri temizle.
        text = text.replace(
            Regex(
                """(?:saat\s*)?""" +
                        """\d{1,2}[.:]\d{2}""" +
                        """(?:['’]?[a-zçğıöşü]{1,4})?"""
            ),
            " "
        )

        // Takvime yonelik komut kelimeleri.
        text = text.replace(
            Regex(
                """\b(takvimime|takvime|""" +
                        """takvimde|randevuma)\b"""
            ),
            " "
        )

        text = text.replace(
            Regex(
                """\b(ekle|oluştur|kaydet|""" +
                        """lütfen|bir)\b"""
            ),
            " "
        )

        text = text
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim(
                ' ', '.', ',', ':', ';',
                '!', '?', '\'', '’'
            )

        return text
    }

    // ==========================================
    // SESLI KOMUTLA RANDEVU EKLE
    // ==========================================

    fun addCalendarEvent(
        context: Context,
        command: String
    ): String {

        if (!isCalendarAddCommand(command)) {
            return "Takvime eklenecek " +
                    "randevuyu anlayamadım."
        }

        val text = normalize(
            removeWakeWord(command)
        )

        val timeMatch =
            findTime(text)

        if (timeMatch == null) {
            return "Randevu için " +
                    "bir saat belirtmelisiniz."
        }

        val hour =
            timeMatch.groupValues[1]
                .toIntOrNull()

        val minute =
            timeMatch.groupValues[2]
                .toIntOrNull()

        if (
            hour == null ||
            minute == null ||
            hour !in 0..23 ||
            minute !in 0..59
        ) {
            return "Belirttiğiniz saat " +
                    "geçerli değil."
        }

        val date =
            resolveDate(text)
                ?: return "Randevu tarihini " +
                        "anlayamadım. Örneğin, " +
                        "yarın saat 14.00'e " +
                        "toplantı ekle diyebilirsiniz."

        date.set(
            Calendar.HOUR_OF_DAY,
            hour
        )

        date.set(
            Calendar.MINUTE,
            minute
        )

        date.set(
            Calendar.SECOND,
            0
        )

        date.set(
            Calendar.MILLISECOND,
            0
        )

        val startMillis = try {
            date.timeInMillis
        } catch (
            error: IllegalArgumentException
        ) {
            return "Belirtilen tarih " +
                    "veya saat geçersiz."
        }

        if (
            startMillis <= System.currentTimeMillis()
        ) {
            return "Geçmiş bir saate " +
                    "randevu ekleyemiyorum. " +
                    "Lütfen ileri bir tarih söyleyin."
        }

        val title =
            extractEventTitle(command)

        if (title.isBlank()) {
            return "Randevunun adını " +
                    "anlayamadım. Lütfen " +
                    "etkinliğin adını da söyleyin."
        }

        // Varsayilan etkinlik suresi 1 saat.
        val endMillis =
            startMillis + 60L * 60L * 1000L

        val manager =
            CalendarManager(context)

        val event = try {
            manager.addLocalEvent(
                title = title,
                startMillis = startMillis,
                endMillis = endMillis
            )
        } catch (error: Exception) {

            Log.e(
                "ZORO_CALENDAR_VOICE",
                "Randevu kaydetme hatasi",
                error
            )

            null
        }

        if (event == null) {
            return "Randevu takvime " +
                    "kaydedilemedi."
        }

        val formatter = SimpleDateFormat(
            "dd MMMM yyyy, HH:mm",
            turkish
        ).apply {
            timeZone = this@ZoroCalendarVoice.timeZone
        }

        val dateText = formatter.format(
            Date(startMillis)
        )

        return "$title randevunuz, " +
                "$dateText tarihine " +
                "Zoro takvimine kaydedildi."
    }

    // ==========================================
    // TAKVIM SORGUSUNU CEVAPLA
    // ==========================================

    fun getCalendarAnswer(
        context: Context,
        command: String
    ): String {

        val manager =
            CalendarManager(context)

        val text = normalize(command)

        val now =
            Calendar.getInstance(timeZone)

        val start =
            Calendar.getInstance(timeZone)

        val end =
            Calendar.getInstance(timeZone)

        var description: String

        if (
            text.contains("yaklaşan") ||
            text.contains("önümüzdeki")
        ) {

            start.timeInMillis =
                now.timeInMillis

            end.timeInMillis =
                now.timeInMillis

            end.add(
                Calendar.DAY_OF_YEAR,
                7
            )

            description =
                "Önümüzdeki 7 gün içinde"

        } else {

            val explicitDate =
                findExplicitDate(text)

            if (
                explicitDate != null ||
                text.contains("yarın") ||
                text.contains("öbür gün") ||
                text.contains("öteki gün") ||
                text.contains("bugün")
            ) {

                val resolved =
                    resolveDate(text)
                        ?: return "Belirttiğiniz " +
                                "tarih geçerli değil."

                start.timeInMillis =
                    resolved.timeInMillis

            } else {

                start.timeInMillis =
                    now.timeInMillis
            }

            start.set(
                Calendar.HOUR_OF_DAY,
                0
            )
            start.set(Calendar.MINUTE, 0)
            start.set(Calendar.SECOND, 0)
            start.set(Calendar.MILLISECOND, 0)

            end.timeInMillis =
                start.timeInMillis

            end.add(
                Calendar.DAY_OF_YEAR,
                1
            )

            description = when {
                text.contains("yarın") ->
                    "Yarın"

                text.contains("öbür gün") ||
                        text.contains("öteki gün") ->
                    "Öbür gün"

                explicitDate != null ->
                    SimpleDateFormat(
                        "dd MMMM yyyy",
                        turkish
                    ).apply {
                        timeZone =
                            this@ZoroCalendarVoice.timeZone
                    }.format(
                        Date(start.timeInMillis)
                    )

                else ->
                    "Bugün"
            }
        }

        // ======================================
        // TELEFON TAKVIM IZNI
        // ======================================

        if (!manager.canReadAndroidCalendar()) {

            return "Samsung ve Google " +
                    "Takvim kayıtlarını okuyabilmem " +
                    "için takvim erişim izni " +
                    "vermeniz gerekiyor."
        }

        // ======================================
        // RANDEVULARI GETIR
        // ======================================

        val events = try {

            manager.getAllEvents(
                start.timeInMillis,
                end.timeInMillis
            )

        } catch (error: Exception) {

            Log.e(
                "ZORO_CALENDAR_VOICE",
                "Takvim sorgu hatasi",
                error
            )

            return "Takvim kayıtlarını " +
                    "okurken bir sorun oluştu."
        }

        if (events.isEmpty()) {

            return "$description " +
                    "takviminizde kayıtlı " +
                    "bir randevu bulunmuyor."
        }

        val speech = StringBuilder()

        speech.append(
            "$description takviminizde "
        )

        speech.append(
            "${events.size} etkinlik bulunuyor. "
        )

        val formatter = SimpleDateFormat(
            "dd MMMM HH:mm",
            turkish
        ).apply {
            timeZone =
                this@ZoroCalendarVoice.timeZone
        }

        events.take(10).forEachIndexed {
                index, event ->

            val dateText =
                if (event.allDay) {
                    "Tüm gün"
                } else {
                    formatter.format(
                        Date(event.startMillis)
                    )
                }

            speech.append(
                "${index + 1}. "
            )

            speech.append(
                "$dateText, ${event.title}. "
            )

            if (event.location.isNotBlank()) {

                speech.append(
                    "Konum: ${event.location}. "
                )
            }
        }

        if (events.size > 10) {

            speech.append(
                "Diğer etkinlikler için " +
                        "Takvimim ekranına " +
                        "bakabilirsiniz."
            )
        }

        return speech.toString()
    }
}
