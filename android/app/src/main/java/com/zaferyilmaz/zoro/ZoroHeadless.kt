package com.zaferyilmaz.zoro

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// ==========================================
// ZORO 2.1: EKRANSIZ KOMUT
// ==========================================
// Uygulama ekrani tamamen kapatilmisken ses servisi komutu kendisi isler:
// sunucuya sorar, takvim/alarm isini yapar, cevabi okur, sonra arama gibi
// telefon isini yapar (ekran acamadigi isler bildirime duser).
// Ekran aciksa komut eskisi gibi ekrana gider.

object ZoroHeadless {

    private const val TAG = "ZORO_HEADLESS"

    private val main = Handler(Looper.getMainLooper())
    private val http by lazy { ZoroServer.buildHttpClient() }

    private data class ServerReply(val code: Int, val json: JSONObject?)

    fun handleCommand(service: ZoroVoiceService, command: String) {
        request(service, "/ask", command, briefing = false)
    }

    fun briefing(service: ZoroVoiceService) {
        request(service, "/briefing", "", briefing = true)
    }

    private fun request(
        service: ZoroVoiceService,
        path: String,
        message: String,
        briefing: Boolean
    ) {

        val context = service.applicationContext

        Thread({

            val reply = try {

                val calendar = try {
                    ZoroActions.calendarContext(context)
                } catch (error: Exception) {
                    null
                }

                val body = ZoroServer.askBody(
                    message = message,
                    calendar = calendar,
                    // Sabah ozeti bekleyen notu ("hangi Okay?") tuketmez.
                    clientNote = if (briefing) null else ZoroServer.takeClientNote(),
                    recentCalls = ZoroCallLog.recentCalls(context, message, briefing)
                ).toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                http.newCall(Request.Builder().url(ZoroServer.url(path)).post(body).build())
                    .execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        ServerReply(response.code, try { JSONObject(text) } catch (_: Exception) { null })
                    }

            } catch (error: Exception) {
                Log.e(TAG, "Sunucuya ulasilamadi", error)
                null
            }

            main.post { deliver(service, reply, briefing) }

        }, "zoro-headless").start()
    }

    private fun deliver(service: ZoroVoiceService, reply: ServerReply?, briefing: Boolean) {

        val context = service.applicationContext

        // Kullanici bu arada dinlemeyi durdurduysa ne cevap okunur ne is yapilir.
        if (!ZoroVoiceService.isActive) {
            return
        }

        val json = reply?.json

        if (briefing && (reply == null || reply.code !in 200..299 || json == null ||
                    json.optString("error").isNotBlank())
        ) {
            // Sunucu ya da yapay zeka yoksa 2.0'daki gibi telefonun kendi ozeti okunur.
            val local = ZoroMorningBriefingReceiver.buildMorningBriefing(context)
            ZoroMorningBriefingReceiver.showNotification(context, local)
            service.speakReply(local, false, null)
            return
        }

        if (reply == null) {
            service.speakReply("Sunucuya bağlanamadım. İnternet bağlantınızı kontrol edin.", false, null)
            return
        }

        if (reply.code !in 200..299 || json == null) {
            val text = when (reply.code) {
                401, 403 -> "Sunucu kimlik doğrulaması başarısız. Uygulama ayarlarını kontrol edin."
                else -> json?.optString("reply", "").orEmpty()
                    .ifBlank { "Sunucu cevabında bir sorun oluştu." }
            }
            service.speakReply(text, false, null)
            return
        }

        val replyText = json.optString("reply", "")
        val expectReply = json.optBoolean("expect_reply", false)
        val actions = ZoroActions.list(json) ?: emptyList()

        val failures = ZoroActions.runDataActions(context, actions, ContextReminderPort(context))
        val hasPhoneActions = actions.any { !ZoroActions.isDataAction(it) }

        val spoken =
            if (failures.isNotEmpty())
                failures.joinToString(" ") + (if (hasPhoneActions) " Diğer isteğinizi yapıyorum." else "")
            else replyText

        if (failures.isNotEmpty()) {
            ZoroServer.clientNote = spoken
        }

        if (briefing && spoken.isNotBlank()) {
            ZoroMorningBriefingReceiver.showNotification(context, spoken)
        }

        service.speakReply(spoken, listenForReply = expectReply && failures.isEmpty()) {

            val actionFailures = ZoroActions.runIntentActions(context, actions, inForeground = false)

            if (actionFailures.isNotEmpty()) {
                val text = actionFailures.joinToString(" ")
                // Sunucu bir sonraki istekte ne oldugunu bilsin.
                ZoroServer.clientNote = text
                service.speakReply(text, listenForReply = text.trimEnd().endsWith("?"), after = null)
            }
        }
    }
}
