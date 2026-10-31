package com.zaferyilmaz.zoro

import okhttp3.Credentials
import okhttp3.OkHttpClient
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

    fun askBody(
        message: String,
        calendar: JSONArray?,
        clientNote: String? = null
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

        return JSONObject()
            .put("message", message)
            .put("client", client)
            .put("context", context)
    }
}
