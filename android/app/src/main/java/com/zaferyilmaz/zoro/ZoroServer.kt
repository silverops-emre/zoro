package com.zaferyilmaz.zoro

import android.content.Context
import android.util.Log
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ==========================================
// ZORO 2.0 SUNUCU AYARLARI
// ==========================================
// Adres ve sifreler koda yazilmaz.
// Derleme sirasinda android/zoro.properties dosyasindan okunur
// (bkz. zoro.properties.example). Dosya git'e girmez.

object ZoroServer {

    const val CLIENT_VERSION = 2

    // Telefonun yapabildigi isler. Sunucu yalnizca bunlari ister.
    val CAPABILITIES = listOf(
        "calendar",
        "navigate",
        "call",
        "message",
        "open_app",
        "alarm",
        "reminders"
    )

    val baseUrl: String
        get() = BuildConfig.ZORO_SERVER_URL.trimEnd('/')

    fun url(path: String): String =
        baseUrl + path

    val isConfigured: Boolean
        get() = baseUrl.startsWith("http")

    fun buildHttpClient(): OkHttpClient {

        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // Yapay zeka arac kullaninca cevap 10-20 sn surebilir.
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .addInterceptor { chain ->

                val builder = chain.request()
                    .newBuilder()
                    .header(
                        "X-Zoro-Client",
                        "android/$CLIENT_VERSION"
                    )

                if (BuildConfig.ZORO_BASIC_USER.isNotBlank()) {
                    builder.header(
                        "Authorization",
                        Credentials.basic(
                            BuildConfig.ZORO_BASIC_USER,
                            BuildConfig.ZORO_BASIC_PASSWORD,
                            Charsets.UTF_8
                        )
                    )
                }

                if (BuildConfig.ZORO_API_KEY.isNotBlank()) {
                    builder.header(
                        "X-Zoro-Key",
                        BuildConfig.ZORO_API_KEY
                    )
                }

                chain.proceed(builder.build())
            }
            .build()
    }

    // ==========================================
    // /ask ISTEK GOVDESI
    // ==========================================

    // Telefonda olan ve sunucunun bilmedigi son durum (orn. rehberde iki "Okay").
    // Bir sonraki istekle sunucuya gider. Ekran ve ses servisi ortak kullanir.
    @Volatile
    var clientNote: String? = null

    fun takeClientNote(): String? {
        val note = clientNote
        clientNote = null
        return note
    }

    fun askBody(
        message: String,
        calendar: JSONArray?,
        clientNote: String? = null,
        recentCalls: JSONArray? = null
    ): JSONObject {

        val client = JSONObject()
            .put("app", "zoro-android")
            .put("version", CLIENT_VERSION)
            .put("capabilities", JSONArray(CAPABILITIES))

        val context = JSONObject()
            .put("tz", java.util.TimeZone.getDefault().id)
            .put("now_ms", System.currentTimeMillis())

        if (calendar != null) {
            context.put("calendar", calendar)
        }

        if (!clientNote.isNullOrBlank()) {
            context.put("client_note", clientNote.take(500))
        }

        if (recentCalls != null) {
            context.put("recent_calls", recentCalls)
        }

        return JSONObject()
            .put("message", message)
            .put("client", client)
            .put("context", context)
    }

    // ==========================================
    // ZORO 2.1: SUNUCU OZELLIKLERI VE AYARLARI
    // ==========================================
    // Acilista bir kez: sunucu yeni ozellikleri biliyor mu, ayarlar ne?
    // Basarisiz olursa son bilinen degerler kalir. Arka planda cagrilir.

    fun syncSettings(context: Context, client: OkHttpClient) {

        try {
            client.newCall(Request.Builder().url(url("/version")).get().build())
                .execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string().orEmpty())
                        ZoroPrefs.setServerFeatures(context, json.optJSONArray("features"))
                    } else if (response.code == 404) {
                        // Eski sunucu (ya da geri alinmis surum): yeni ozellikler kapanir.
                        ZoroPrefs.setServerFeatures(context, null)
                    }
                }

            if (ZoroPrefs.serverHas(context, "settings")) {
                client.newCall(Request.Builder().url(url("/settings")).get().build())
                    .execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body?.string().orEmpty())
                            json.optJSONObject("settings")?.let {
                                ZoroPrefs.applyServerSettings(context, it)
                            }
                        }
                    }
            }

            ZoroMorningBriefingScheduler.scheduleNext(context)

        } catch (error: Exception) {
            Log.w("ZORO_SERVER", "Sunucu ayarlari alinamadi", error)
        }
    }
}
