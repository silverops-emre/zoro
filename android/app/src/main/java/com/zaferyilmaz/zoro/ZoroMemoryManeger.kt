package com.zaferyilmaz.zoro

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ==========================================
// ZORO KISISEL HAFIZA VERI MODELI
// ==========================================

data class ZoroMemoryItem(
    val id: String,
    val text: String,
    val createdAt: Long
)

// ==========================================
// ZORO KISISEL HAFIZA YONETICISI
// ==========================================

class ZoroMemoryManager(
    context: Context
) {

    companion object {
        private const val TAG = "ZORO_MEMORY"
        private const val PREFS_NAME = "zoro_personal_memory"
        private const val MEMORIES_KEY = "saved_memories"

        private const val MAX_MEMORIES = 500
        private const val MAX_TEXT_LENGTH = 2000
    }

    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    // ==========================================
    // KAYITLI BILGILERI OKU
    // ==========================================

    @Synchronized
    fun getAllMemories(): List<ZoroMemoryItem> {

        val result = mutableListOf<ZoroMemoryItem>()

        try {
            val stored = preferences.getString(
                MEMORIES_KEY,
                "[]"
            ) ?: "[]"

            val array = JSONArray(stored)

            for (index in 0 until array.length()) {

                val item = array.optJSONObject(index)
                    ?: continue

                val id = item.optString("id")
                val text = item.optString("text")
                val createdAt = item.optLong("createdAt")

                if (
                    id.isBlank() ||
                    text.isBlank()
                ) {
                    continue
                }

                result.add(
                    ZoroMemoryItem(
                        id = id,
                        text = text,
                        createdAt = createdAt
                    )
                )
            }

        } catch (error: Exception) {
            Log.e(
                TAG,
                "Hafiza kayitlari okunamadi",
                error
            )
        }

        return result.sortedByDescending {
            it.createdAt
        }
    }

    // ==========================================
    // YENI BILGI KAYDET
    // ==========================================

    @Synchronized
    fun saveMemory(
        text: String
    ): ZoroMemoryItem? {

        val cleanText = text.trim()
            .replace(
                Regex("\\s+"),
                " "
            )

        if (
            cleanText.isBlank() ||
            cleanText.length > MAX_TEXT_LENGTH
        ) {
            return null
        }

        val existing = getAllMemories()

        // Ayni bilgi iki kez kaydedilmesin.
        val duplicate = existing.any {
            it.text.equals(
                cleanText,
                ignoreCase = true
            )
        }

        if (duplicate) {
            return existing.first {
                it.text.equals(
                    cleanText,
                    ignoreCase = true
                )
            }
        }

        val memory = ZoroMemoryItem(
            id = UUID.randomUUID().toString(),
            text = cleanText,
            createdAt = System.currentTimeMillis()
        )

        val updated = existing.toMutableList()
        updated.add(0, memory)

        if (updated.size > MAX_MEMORIES) {
            return null
        }

        if (!writeMemories(updated)) {
            return null
        }

        Log.i(
            TAG,
            "Yeni hafiza kaydi olusturuldu."
        )

        return memory
    }

    // ==========================================
    // BILGI ARA
    // ==========================================

    fun searchMemories(
        query: String
    ): List<ZoroMemoryItem> {

        val normalizedQuery = normalize(query)

        if (normalizedQuery.isBlank()) {
            return emptyList()
        }

        val words = normalizedQuery
            .split(" ")
            .filter {
                it.length >= 3
            }

        if (words.isEmpty()) {
            return emptyList()
        }

        return getAllMemories()
            .map { memory ->

                val normalizedText =
                    normalize(memory.text)

                val score = words.count {
                    normalizedText.contains(it)
                }

                memory to score
            }
            .filter {
                it.second > 0
            }
            .sortedByDescending {
                it.second
            }
            .map {
                it.first
            }
    }

    // ==========================================
    // HAFIZA KAYDINI SIL
    // ==========================================

    @Synchronized
    fun deleteMemory(
        memoryId: String
    ): Boolean {

        if (memoryId.isBlank()) {
            return false
        }

        val memories = getAllMemories()
            .toMutableList()

        val removed = memories.removeAll {
            it.id == memoryId
        }

        if (!removed) {
            return false
        }

        return writeMemories(memories)
    }

    // ==========================================
    // HAFIZA KAYITLARINI DISKE YAZ
    // ==========================================

    private fun writeMemories(
        memories: List<ZoroMemoryItem>
    ): Boolean {

        return try {

            val array = JSONArray()

            memories.forEach { memory ->

                val item = JSONObject().apply {

                    put("id", memory.id)
                    put("text", memory.text)
                    put(
                        "createdAt",
                        memory.createdAt
                    )
                }

                array.put(item)
            }

            preferences.edit()
                .putString(
                    MEMORIES_KEY,
                    array.toString()
                )
                .commit()

        } catch (error: Exception) {

            Log.e(
                TAG,
                "Hafiza kaydedilemedi",
                error
            )

            false
        }
    }

    // ==========================================
    // ARAMA METNINI DUZENLE
    // ==========================================

    private fun normalize(
        text: String
    ): String {

        return text
            .lowercase(
                Locale.forLanguageTag("tr-TR")
            )
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
    // HAFIZA TARIHINI GOSTER
    // ==========================================

    fun formatMemoryDate(
        createdAt: Long
    ): String {

        return SimpleDateFormat(
            "dd.MM.yyyy HH:mm",
            Locale.forLanguageTag("tr-TR")
        ).format(
            Date(createdAt)
        )
    }
}
