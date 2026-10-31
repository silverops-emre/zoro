package com.zaferyilmaz.zoro

import android.content.Context
import android.util.Log
import java.util.Locale

object ZoroMemoryVoice {

    private const val TAG = "ZORO_MEMORY_VOICE"

    private val turkish =
        Locale.forLanguageTag("tr-TR")

    private val listCommands = listOf(
        "neleri hatırlıyorsun",
        "ne hatırlıyorsun",
        "hafızanda ne var",
        "hafızanda neler var",
        "hafızandakileri söyle",
        "kaydettiklerini söyle",
        "bana neyi kaydettin",
        "sana ne söylemiştim"
    )

    private val saveCommands = listOf(
        "bunu unutma",
        "şunu unutma",
        "aklında tut",
        "hafızana kaydet",
        "hafızaya kaydet",
        "bunu hatırla",
        "şunu hatırla"
    )

    private val deleteCommands = listOf(
        "hafızandan sil",
        "hafızadan sil",
        "hatırladığını sil",
        "kaydettiğin bilgiyi sil"
    )

    // ==========================================
    // TURKCE METNI DUZENLE
    // ==========================================

    private fun normalize(text: String): String {
        return text
            .lowercase(turkish)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun removeWakeWord(command: String): String {
        return normalize(command)
            .replace(
                Regex(
                    """^(?:zoro|zorro|zora|zara)\b[\s,.:]*"""
                ),
                ""
            )
            .trim()
    }

    // ==========================================
    // KAYDETME KOMUTU
    // ==========================================

    fun isMemorySaveCommand(command: String): Boolean {
        val text = removeWakeWord(command)
        return saveCommands.any { text.contains(it) }
    }

    // ==========================================
    // SILME KOMUTU
    // ==========================================

    fun isMemoryDeleteCommand(command: String): Boolean {
        val text = removeWakeWord(command)
        return deleteCommands.any { text.contains(it) }
    }

    // ==========================================
    // HAFIZA SORGULARINI TANIMA
    // ==========================================

    fun isMemoryQueryCommand(command: String): Boolean {

        val text = removeWakeWord(command)

        if (
            isMemorySaveCommand(command) ||
            isMemoryDeleteCommand(command)
        ) {
            return false
        }

        if (listCommands.any { text.contains(it) }) {
            return true
        }

        val questionPatterns = listOf(
            Regex("""\bne zaman(?:dı|dıydı)?\b"""),
            Regex("""\bne zamana\b"""),
            Regex("""\bhangi gün(?:dü)?\b"""),
            Regex("""\bhangi tarih(?:te|ti|eydi)?\b"""),
            Regex("""\bne tarih(?:te|ti)?\b"""),
            Regex("""\bnerede(?:ydi)?\b"""),
            Regex("""\bnereye\b"""),
            Regex("""\bhatırlıyor musun\b"""),
            Regex("""\bhatırlıyor muydun\b"""),
            Regex("""\bsana söylemiş miydim\b"""),
            Regex("""\bsana ne demiştim\b""")
        )

        // Belirli bir hafiza sorusu olmadan
        // normal sohbeti ele gecirme.
        val hasQuestion = questionPatterns.any {
            it.containsMatchIn(text)
        }

        if (!hasQuestion) {
            return false
        }

        // Tarih ve yer sorularinda konu bulunmali.
        val subject = extractSearchQuery(command)

        return subject.isNotBlank()
    }

    fun isMemoryCommand(command: String): Boolean {
        return isMemorySaveCommand(command) ||
                isMemoryDeleteCommand(command) ||
                isMemoryQueryCommand(command)
    }

    // ==========================================
    // KAYDEDILECEK BILGIYI AYIKLA
    // ==========================================

    private fun extractMemoryText(command: String): String {

        var text = removeWakeWord(command)

        saveCommands.forEach { phrase ->
            text = text.replace(
                phrase,
                " ",
                ignoreCase = true
            )
        }

        return text
            .trim(
                ' ', ':', ',', '.', '!', '?',
                ';', '\'', '’'
            )
            .replace(Regex("\\s+"), " ")
    }

    // ==========================================
    // SORUNUN KONUSUNU AYIKLA
    // ==========================================

    private fun extractSearchQuery(command: String): String {

        var text = removeWakeWord(command)

        val expressions = listOf(
            "ne zaman",
            "ne zamandı",
            "ne zamana",
            "hangi gün",
            "hangi gündü",
            "hangi tarihte",
            "hangi tarihti",
            "hangi tarih",
            "ne tarihte",
            "neredeydi",
            "nerede",
            "nereye",
            "hatırlıyor musun",
            "hatırlıyor muydun",
            "sana söylemiş miydim",
            "sana ne demiştim",
            "benim",
            "bana",
            "acaba",
            "lütfen",
            "söyle"
        )

        // Uzun ifadeleri once temizle.
        expressions.sortedByDescending {
            it.length
        }.forEach { phrase ->
            text = text.replace(
                phrase,
                " ",
                ignoreCase = true
            )
        }

        return text
            .replace(
                Regex("[^a-zçğıöşü0-9]+"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    // ==========================================
    // TUM KAYITLARI SESLENDIR
    // ==========================================

    private fun listMemories(
        manager: ZoroMemoryManager
    ): String {

        val memories = manager.getAllMemories()

        if (memories.isEmpty()) {
            return "Henüz kişisel hafızama " +
                    "bir bilgi kaydetmediniz."
        }

        val result = StringBuilder()

        result.append(
            "Kişisel hafızamda " +
                    "${memories.size} bilgi bulunuyor. "
        )

        memories.take(5).forEachIndexed {
                index, memory ->

            result.append(
                "${index + 1}. ${memory.text}. "
            )
        }

        if (memories.size > 5) {
            result.append(
                "Diğer kayıtlarınız da hafızamda saklanıyor."
            )
        }

        return result.toString()
    }

    // ==========================================
    // KAYITLI BILGIYI BUL
    // ==========================================

    private fun recallMemory(
        manager: ZoroMemoryManager,
        command: String
    ): String {

        val text = removeWakeWord(command)

        if (listCommands.any { text.contains(it) }) {
            return listMemories(manager)
        }

        val query = extractSearchQuery(command)

        if (query.isBlank()) {
            return "Hangi bilgiyi hatırlamamı " +
                    "istediğinizi söyleyin."
        }

        val matches = manager.searchMemories(query)

        if (matches.isEmpty()) {
            return "Bu konuyla ilgili kişisel " +
                    "hafızamda kayıt bulamadım."
        }

        val best = matches.first()

        return "Bu konuda kaydettiğim bilgi şu: " +
                best.text
    }

    // ==========================================
    // HAFIZA KAYDINI SILME
    // ==========================================

    private fun deleteMemory(
        manager: ZoroMemoryManager,
        command: String
    ): String {

        // Bu surumde yanlislikla silmeyi
        // onlemek icin sesli silme kapali.
        return "Hafızamdaki bilgileri sesli olarak " +
                "silme işlemi henüz etkin değil."
    }

    // ==========================================
    // SESLI KOMUTU ISLE
    // ==========================================

    fun handleCommand(
        context: Context,
        command: String
    ): String {

        return try {

            val manager = ZoroMemoryManager(context)

            when {

                isMemoryDeleteCommand(command) -> {
                    deleteMemory(manager, command)
                }

                isMemorySaveCommand(command) -> {

                    val memoryText =
                        extractMemoryText(command)

                    if (memoryText.isBlank()) {

                        "Kaydetmemi istediğiniz " +
                                "bilgiyi de söyleyin."

                    } else {

                        val saved =
                            manager.saveMemory(memoryText)

                        if (saved == null) {

                            "Bilgi kaydedilemedi. " +
                                    "Hafıza dolmuş olabilir " +
                                    "veya bilgi çok uzun."

                        } else {

                            "Kaydettim. " +
                                    saved.text +
                                    ". Bu bilgiyi hatırlayacağım."
                        }
                    }
                }

                isMemoryQueryCommand(command) -> {
                    recallMemory(manager, command)
                }

                else -> {
                    "Hafıza komutunu anlayamadım."
                }
            }

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Sesli hafiza hatasi",
                error
            )

            "Kişisel hafıza işlemi sırasında " +
                    "bir sorun oluştu."
        }
    }
}
